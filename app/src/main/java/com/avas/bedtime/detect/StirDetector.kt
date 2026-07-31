package com.avas.bedtime.detect

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class StirSource { Mic, Motion }

/**
 * Bedtime stir detection for a kid getting up / crying.
 *
 * Motion: sensitive to bed movement transmitted into the tablet
 * (place on the mattress edge, bed frame, or something that moves with the bed).
 *
 * Mic: reacts to whining/crying (sustained louder sound) and sharp noises
 * like a metal water bottle (short spike). Samples are never saved.
 */
class StirDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val onStir: (StirSource) -> Unit
) {
    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var micJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var motionListening = false
    private var usingRawAccelerometer = false

    @Volatile
    private var micSensitivity = 0.45f
    @Volatile
    private var motionSensitivity = 0.45f
    @Volatile
    private var micEnabled = true
    @Volatile
    private var motionEnabled = true
    @Volatile
    private var cooldownMs = 25_000L
    private var startupGraceMs = 12_000L
    @Volatile
    private var startedAt = 0L
    @Volatile
    private var lastTriggerAt = 0L
    /** Ignore mic/motion briefly after playlist track changes (music jumps look like stirs). */
    @Volatile
    private var ignoreUntilMs = 0L
    private val triggerLock = Any()

    private var micBaseline = 0f
    private var micBaselineReady = false
    private var micChunks = 0
    private var loudStreak = 0

    private var motionSmoothed = 0f
    private var motionReady = false
    private var motionSamples = 0
    private var motionEnergy = 0f
    private var motionBurstCount = 0

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!motionEnabled) return
            val magnitude = sqrt(
                event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
            )

            if (!motionReady) {
                motionSmoothed = magnitude
                motionSamples++
                if (motionSamples >= 15) motionReady = true
                return
            }

            val delta = abs(magnitude - motionSmoothed)
            motionSmoothed = motionSmoothed * 0.9f + magnitude * 0.1f

            // hard=0 at 100% sensitivity, hard≈0.9 at 10% — much pickier when slider is low.
            val hard = 1f - motionSensitivity.coerceIn(0.05f, 1f)
            val spikeThr = if (usingRawAccelerometer) {
                0.35f + hard * 2.2f
            } else {
                0.22f + hard * 1.6f
            }
            val energyThr = if (usingRawAccelerometer) {
                2.2f + hard * 7f
            } else {
                1.3f + hard * 5f
            }
            val burstsNeeded = 3 + (hard * 5f).toInt() // 3 at 100%, ~7 at 10%

            if (delta >= spikeThr) {
                motionEnergy += delta
                motionBurstCount++
            } else {
                motionEnergy *= 0.88f
                if (motionBurstCount > 0) motionBurstCount--
            }

            val strongBump = delta >= spikeThr * (2.4f + hard)
            val bedWiggle = motionEnergy >= energyThr && motionBurstCount >= burstsNeeded
            if (strongBump || bedWiggle) {
                Log.d(
                    TAG,
                    "Motion delta=$delta energy=$motionEnergy bursts=$motionBurstCount sens=$motionSensitivity"
                )
                motionEnergy = 0f
                motionBurstCount = 0
                maybeTrigger(StirSource.Motion)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun updateConfig(
        micSensitivity: Float,
        motionSensitivity: Float,
        micEnabled: Boolean,
        motionEnabled: Boolean,
        cooldownSeconds: Int
    ) {
        val wasMic = this.micEnabled
        val wasMotion = this.motionEnabled
        this.micSensitivity = micSensitivity.coerceIn(0.05f, 1f)
        this.motionSensitivity = motionSensitivity.coerceIn(0.05f, 1f)
        this.micEnabled = micEnabled
        this.motionEnabled = motionEnabled
        val nextCooldownMs = cooldownSeconds.coerceIn(5, 300) * 1000L
        this.cooldownMs = nextCooldownMs
        // Hot-apply enable/disable while a session is already running.
        if (startedAt != 0L) {
            if (micEnabled && !wasMic) startMic()
            if (!micEnabled && wasMic) stopMic()
            if (motionEnabled && !wasMotion) startMotion()
            if (!motionEnabled && wasMotion) stopMotion()
            Log.i(
                TAG,
                "Config updated mic=$micSensitivity motion=$motionSensitivity " +
                    "cooldown=${nextCooldownMs}ms micOn=$micEnabled motionOn=$motionEnabled"
            )
        }
    }

    /**
     * Playlist volume/timbre often jumps between songs — treat that as ambient,
     * not a stir. Also snaps the mic baseline toward the new level.
     */
    fun ignoreAudioChange(durationMs: Long = 14_000L) {
        val until = System.currentTimeMillis() + durationMs.coerceAtLeast(0L)
        // Never shorten an existing mute/cooldown window.
        if (until > ignoreUntilMs) {
            ignoreUntilMs = until
        }
        loudStreak = 0
        motionEnergy = 0f
        motionBurstCount = 0
        Log.d(TAG, "Ignoring stirs until +${durationMs}ms (audio change)")
    }

    /** Remaining cooldown after the last stir restart, or 0 if none / expired. */
    fun remainingCooldownMs(): Long {
        val last = lastTriggerAt
        if (last == 0L) return 0L
        val left = (last + cooldownMs) - System.currentTimeMillis()
        return left.coerceAtLeast(0L)
    }

    fun start() {
        startedAt = System.currentTimeMillis()
        lastTriggerAt = 0L
        ignoreUntilMs = startedAt + startupGraceMs
        micBaseline = 0f
        micBaselineReady = false
        micChunks = 0
        loudStreak = 0
        motionSmoothed = 0f
        motionReady = false
        motionSamples = 0
        motionEnergy = 0f
        motionBurstCount = 0
        if (micEnabled) startMic()
        if (motionEnabled) startMotion()
        Log.i(
            TAG,
            "Listening for bed stirs / crying (grace ${startupGraceMs}ms, cooldown ${cooldownMs}ms, " +
                "micSens=$micSensitivity motionSens=$motionSensitivity)"
        )
    }

    fun stop() {
        stopMic()
        stopMotion()
    }

    private fun startMic() {
        if (micJob?.isActive == true) return
        micJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuffer <= 0) {
                Log.w(TAG, "AudioRecord buffer unavailable")
                return@launch
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channel,
                encoding,
                minBuffer * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize")
                recorder.release()
                return@launch
            }

            audioRecord = recorder
            val buffer = ShortArray(minBuffer)
            recorder.startRecording()
            Log.i(TAG, "Mic listening for cry/whine/bottle sounds")

            try {
                while (isActive && micEnabled) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    var sum = 0.0
                    var highSum = 0.0
                    var prev = 0
                    for (i in 0 until read) {
                        val sample = buffer[i].toInt()
                        sum += abs(sample)
                        // Simple high-frequency emphasis helps cries / metal clinks vs bass-y music.
                        val diff = abs(sample - prev)
                        highSum += diff
                        prev = sample
                    }
                    val average = (sum / read).toFloat()
                    val brightness = (highSum / read).toFloat()
                    buffer.fill(0)

                    if (!micBaselineReady) {
                        micBaseline = if (micChunks == 0) {
                            average
                        } else {
                            micBaseline * 0.75f + average * 0.25f
                        }
                        micChunks++
                        if (micChunks >= 10) {
                            micBaselineReady = true
                            Log.i(TAG, "Mic ambient baseline=$micBaseline")
                        }
                        continue
                    }

                    val ignoring = System.currentTimeMillis() < ignoreUntilMs
                    // During track changes, snap ambient toward the new song level quickly.
                    if (ignoring) {
                        micBaseline = micBaseline * 0.65f + average * 0.35f
                        loudStreak = 0
                        continue
                    }

                    // Catch up quickly when the room/music gets louder so soft→loud
                    // song passages don't look like a kid stir. Decay slowly on quiet.
                    micBaseline = if (average > micBaseline) {
                        micBaseline * 0.90f + average * 0.10f
                    } else {
                        micBaseline * 0.995f + average * 0.005f
                    }

                    val sens = micSensitivity.coerceIn(0.05f, 1f)
                    // hard=0 at 100%, ~0.9 at 10%. Squared so low slider values get much tougher.
                    val hard = 1f - sens
                    val hard2 = hard * hard

                    // At 10%: need ~4.5× baseline or +~900 absolute — music dynamics alone won't trip.
                    // At 100%: ~1.7× or +180 — still needs a clear jump (cry/bottle).
                    val spikeFactor = 1.7f + hard * 1.6f + hard2 * 2.2f
                    val minSpike = 180f + hard * 400f + hard2 * 500f
                    val spikeThr = max(micBaseline * spikeFactor, micBaseline + minSpike)

                    val sustainedFactor = 1.55f + hard * 1.2f + hard2 * 1.8f
                    val minSustained = 120f + hard * 350f + hard2 * 450f
                    val sustainedThr = max(micBaseline * sustainedFactor, micBaseline + minSustained)

                    // Brightness must clear a sensitivity-scaled gate (no easy bypass).
                    val brightGate = micBaseline * (0.55f + hard * 0.55f)
                    val brightEnough = brightness > brightGate

                    // Metal bottle / sudden yelp: one sharp jump above music.
                    val sharpNoise = average >= spikeThr && brightEnough

                    // Whine / cry: must stay loud for longer when sensitivity is low.
                    val streakNeeded = 5 + (hard * 10f).toInt() // 5 at 100%, ~14 at 10%
                    if (average >= sustainedThr && brightEnough) {
                        loudStreak++
                    } else {
                        loudStreak = max(0, loudStreak - 2)
                    }
                    val cryOrWhine = loudStreak >= streakNeeded

                    if (sharpNoise || cryOrWhine) {
                        Log.i(
                            TAG,
                            "Mic trip avg=$average bright=$brightness base=$micBaseline " +
                                "spikeThr=$spikeThr sustThr=$sustainedThr streak=$loudStreak/" +
                                "$streakNeeded sens=$sens hard=$hard"
                        )
                        loudStreak = 0
                        maybeTrigger(StirSource.Mic)
                    }
                }
            } finally {
                buffer.fill(0)
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
                audioRecord = null
            }
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        audioRecord?.let { recorder ->
            runCatching {
                recorder.stop()
                recorder.release()
            }
        }
        audioRecord = null
    }

    private fun startMotion() {
        if (motionListening) return
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sensor = linear ?: accel ?: return
        usingRawAccelerometer = linear == null
        sensorManager.registerListener(
            motionListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )
        motionListening = true
        Log.i(
            TAG,
            "Motion listening for bed movement (${if (usingRawAccelerometer) "accelerometer" else "linear"})"
        )
    }

    private fun stopMotion() {
        if (!motionListening) return
        sensorManager.unregisterListener(motionListener)
        motionListening = false
    }

    private fun maybeTrigger(source: StirSource) {
        val fired = synchronized(triggerLock) {
            val now = System.currentTimeMillis()
            if (now < ignoreUntilMs) {
                Log.d(TAG, "Stir ignored ($source) — audio-change mute ${ignoreUntilMs - now}ms left")
                return@synchronized false
            }
            if (startedAt != 0L && now - startedAt < startupGraceMs) {
                Log.d(TAG, "Stir ignored ($source) — startup grace")
                return@synchronized false
            }
            if (lastTriggerAt != 0L && now - lastTriggerAt < cooldownMs) {
                Log.d(
                    TAG,
                    "Stir ignored ($source) — cooldown ${(lastTriggerAt + cooldownMs) - now}ms left " +
                        "of ${cooldownMs}ms"
                )
                return@synchronized false
            }
            lastTriggerAt = now
            // Keep muted at least for the full cooldown so a track-change mute can't
            // expire first and let a second restart through early.
            val cooldownUntil = now + cooldownMs
            if (cooldownUntil > ignoreUntilMs) {
                ignoreUntilMs = cooldownUntil
            }
            true
        }
        if (!fired) return
        Log.i(TAG, "Stir detected from $source (cooldown ${cooldownMs}ms, no audio saved)")
        onStir(source)
    }

    companion object {
        private const val TAG = "StirDetector"
    }
}
