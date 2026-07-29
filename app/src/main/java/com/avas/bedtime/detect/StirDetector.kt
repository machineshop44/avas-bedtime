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

    private var micSensitivity = 0.8f
    private var motionSensitivity = 0.85f
    private var micEnabled = true
    private var motionEnabled = true
    private var cooldownMs = 25_000L
    private var startupGraceMs = 12_000L
    private var startedAt = 0L
    private var lastTriggerAt = 0L

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

            // Bed movement is often small but repeated (sitting up, scooting, standing).
            val spikeThr = if (usingRawAccelerometer) {
                0.22f + (1f - motionSensitivity) * 1.1f
            } else {
                0.12f + (1f - motionSensitivity) * 0.7f
            }
            val energyThr = if (usingRawAccelerometer) {
                1.4f + (1f - motionSensitivity) * 3.5f
            } else {
                0.7f + (1f - motionSensitivity) * 2.2f
            }

            if (delta >= spikeThr) {
                motionEnergy += delta
                motionBurstCount++
            } else {
                motionEnergy *= 0.92f
                if (motionBurstCount > 0) motionBurstCount--
            }

            // One stronger bump (getting out of bed) OR several smaller bed wiggles.
            val strongBump = delta >= spikeThr * 2.2f
            val bedWiggle = motionEnergy >= energyThr && motionBurstCount >= 3
            if (strongBump || bedWiggle) {
                Log.d(
                    TAG,
                    "Motion delta=$delta energy=$motionEnergy bursts=$motionBurstCount"
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
        this.micSensitivity = micSensitivity.coerceIn(0.05f, 1f)
        this.motionSensitivity = motionSensitivity.coerceIn(0.05f, 1f)
        this.micEnabled = micEnabled
        this.motionEnabled = motionEnabled
        this.cooldownMs = cooldownSeconds.coerceIn(5, 300) * 1000L
    }

    fun start() {
        startedAt = System.currentTimeMillis()
        lastTriggerAt = 0L
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
            "Listening for bed stirs / crying (grace ${startupGraceMs}ms, cooldown ${cooldownMs}ms)"
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

                    // Slow ambient tracking so ongoing playlist level is ignored.
                    micBaseline = micBaseline * 0.985f + average * 0.015f

                    val spikeFactor = 1.25f + (1f - micSensitivity) * 1.1f
                    val minSpike = 45f + (1f - micSensitivity) * 140f
                    val spikeThr = max(micBaseline * spikeFactor, micBaseline + minSpike)

                    val sustainedFactor = 1.15f + (1f - micSensitivity) * 0.7f
                    val minSustained = 30f + (1f - micSensitivity) * 90f
                    val sustainedThr = max(micBaseline * sustainedFactor, micBaseline + minSustained)

                    val brightEnough = brightness > micBaseline * (0.45f + micSensitivity * 0.35f)

                    // Metal bottle / sudden yelp: one sharp jump.
                    val sharpNoise = average >= spikeThr && brightEnough
                    // Whine / cry: louder than music for several chunks in a row.
                    if (average >= sustainedThr && (brightEnough || average > micBaseline * 1.5f)) {
                        loudStreak++
                    } else {
                        loudStreak = max(0, loudStreak - 1)
                    }
                    val cryOrWhine = loudStreak >= 4

                    if (sharpNoise || cryOrWhine) {
                        Log.d(
                            TAG,
                            "Mic avg=$average bright=$brightness base=$micBaseline streak=$loudStreak"
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
        val now = System.currentTimeMillis()
        if (now - startedAt < startupGraceMs) return
        if (lastTriggerAt != 0L && now - lastTriggerAt < cooldownMs) return
        lastTriggerAt = now
        Log.i(TAG, "Stir detected from $source (no audio saved)")
        onStir(source)
    }

    companion object {
        private const val TAG = "StirDetector"
    }
}
