package com.avas.bedtime.plex

import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Tells Plex Media Server what we're playing so it shows on the Plex / Tautulli dashboards.
 * Without these timeline pings, direct ExoPlayer streams are invisible to Now Playing.
 */
class PlexTimelineReporter(
    private val serverUrl: String,
    private val token: String,
    private val clientId: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val sessionId = UUID.randomUUID().toString()
    private var pulseJob: Job? = null
    private var currentRatingKey: String = ""
    private var currentDurationMs: Long = 0L
    private var positionProvider: () -> Long = { 0L }
    private var playing = false

    fun attach(
        ratingKey: String,
        durationMs: Long,
        positionMs: () -> Long
    ) {
        currentRatingKey = ratingKey
        currentDurationMs = durationMs.coerceAtLeast(1L)
        positionProvider = positionMs
    }

    fun onPlaying() {
        playing = true
        report("playing", positionProvider())
        startPulse()
    }

    fun onPaused() {
        playing = false
        stopPulse()
        if (currentRatingKey.isNotBlank()) {
            report("paused", positionProvider())
        }
    }

    fun onStopped() {
        playing = false
        stopPulse()
        if (currentRatingKey.isNotBlank()) {
            // Use a process-wide executor so "stopped" still fires after service scope cancel.
            reportIndependent("stopped", currentRatingKey, positionProvider(), currentDurationMs)
        }
        currentRatingKey = ""
    }

    fun onSeekOrTrackChange() {
        if (playing && currentRatingKey.isNotBlank()) {
            report("playing", positionProvider())
        }
    }

    private fun startPulse() {
        stopPulse()
        pulseJob = scope.launch {
            while (isActive && playing) {
                delay(30_000)
                if (playing && currentRatingKey.isNotBlank()) {
                    report("playing", positionProvider())
                }
            }
        }
    }

    private fun stopPulse() {
        pulseJob?.cancel()
        pulseJob = null
    }

    private fun report(state: String, timeMs: Long) {
        val key = currentRatingKey
        if (key.isBlank()) return
        val duration = currentDurationMs
        val time = timeMs.coerceIn(0L, duration)
        scope.launch(Dispatchers.IO) {
            runCatching {
                postTimeline(state, key, time, duration)
            }.onFailure {
                Log.w(TAG, "Timeline $state failed: ${it.message}")
            }
        }
    }

    private fun reportIndependent(
        state: String,
        ratingKey: String,
        timeMs: Long,
        durationMs: Long
    ) {
        val time = timeMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
        ioExecutor.execute {
            runCatching {
                postTimeline(state, ratingKey, time, durationMs.coerceAtLeast(1L))
            }.onFailure {
                Log.w(TAG, "Timeline $state failed: ${it.message}")
            }
        }
    }

    private fun postTimeline(
        state: String,
        ratingKey: String,
        timeMs: Long,
        durationMs: Long
    ) {
        val base = serverUrl.trimEnd('/')
        val keyPath = "/library/metadata/$ratingKey"
        val query = buildString {
            append("ratingKey=").append(enc(ratingKey))
            append("&key=").append(enc(keyPath))
            append("&state=").append(enc(state))
            append("&time=").append(timeMs)
            append("&duration=").append(durationMs)
            append("&playbackTime=").append(timeMs)
            append("&continuing=").append(if (state == "stopped") "0" else "1")
        }
        val url = "$base/:/timeline?$query"
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .header("Accept", "application/json")
            .header("X-Plex-Token", token)
            .header("X-Plex-Client-Identifier", clientId)
            .header("X-Plex-Product", PlexHeaders.PRODUCT)
            .header("X-Plex-Version", PlexHeaders.VERSION)
            .header("X-Plex-Platform", PlexHeaders.PLATFORM)
            .header("X-Plex-Device", "Android")
            .header("X-Plex-Device-Name", "Ava Bedtime")
            .header("X-Plex-Provides", "player")
            .header("X-Plex-Session-Identifier", sessionId)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val getReq = request.newBuilder().get().build()
                http.newCall(getReq).execute().use { getResponse ->
                    if (!getResponse.isSuccessful) {
                        error("timeline HTTP ${response.code}/${getResponse.code}")
                    }
                }
            }
        }
        Log.d(TAG, "Timeline $state ratingKey=$ratingKey t=${timeMs}ms")
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        private const val TAG = "PlexTimeline"
        private val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "PlexTimeline").apply { isDaemon = true }
        }
    }
}
