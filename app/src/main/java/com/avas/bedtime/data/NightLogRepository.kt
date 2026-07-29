package com.avas.bedtime.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class NightSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val micRestarts: Int,
    val motionRestarts: Int,
    val manualRestarts: Int,
    val farthestTrackIndex: Int,
    val farthestTrackTitle: String,
    val trackCount: Int,
    val farthestPositionMs: Long,
    val longestQuietStretchMs: Long
) {
    val totalRestarts: Int get() = micRestarts + motionRestarts + manualRestarts

    fun formatNotificationBody(): String = buildString {
        append(formatWindow())
        append('\n')
        append("Restarts: $totalRestarts")
        append(" (mic $micRestarts · motion $motionRestarts · manual $manualRestarts)")
        append('\n')
        append(formatFarthest())
        append('\n')
        append("Longest stretch: ${formatDuration(longestQuietStretchMs)}")
    }

    fun formatSettingsBlock(): String = formatNotificationBody()

    fun formatWindow(): String {
        val day = DAY_FMT.format(Date(startedAtMs))
        val start = CLOCK_FMT.format(Date(startedAtMs))
        val end = CLOCK_FMT.format(Date(endedAtMs))
        return "$day · $start → $end"
    }

    fun formatFarthest(): String {
        if (farthestTrackIndex < 0 || trackCount <= 0) {
            return "Farthest: —"
        }
        val n = farthestTrackIndex + 1
        val title = farthestTrackTitle.ifBlank { "Track $n" }
        return "Farthest: #$n/$trackCount $title"
    }

    companion object {
        private val DAY_FMT = SimpleDateFormat("EEE MMM d", Locale.getDefault())
        private val CLOCK_FMT = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun formatDuration(ms: Long): String {
            val totalMin = TimeUnit.MILLISECONDS.toMinutes(ms.coerceAtLeast(0L))
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }
    }
}

class NightLogRepository(context: Context) {
    private val file = File(context.applicationContext.filesDir, "night_logs.json")
    private val _nights = MutableStateFlow<List<NightSummary>>(emptyList())
    val nights: StateFlow<List<NightSummary>> = _nights.asStateFlow()

    init {
        _nights.value = loadFromDisk()
    }

    @Synchronized
    fun add(summary: NightSummary) {
        val next = (listOf(summary) + _nights.value).take(MAX_NIGHTS)
        _nights.value = next
        saveToDisk(next)
        Log.i(TAG, "Night saved: ${summary.formatNotificationBody().replace("\n", " | ")}")
    }

    private fun loadFromDisk(): List<NightSummary> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        NightSummary(
                            startedAtMs = o.getLong("startedAtMs"),
                            endedAtMs = o.getLong("endedAtMs"),
                            micRestarts = o.optInt("micRestarts"),
                            motionRestarts = o.optInt("motionRestarts"),
                            manualRestarts = o.optInt("manualRestarts"),
                            farthestTrackIndex = o.optInt("farthestTrackIndex", -1),
                            farthestTrackTitle = o.optString("farthestTrackTitle"),
                            trackCount = o.optInt("trackCount"),
                            farthestPositionMs = o.optLong("farthestPositionMs"),
                            longestQuietStretchMs = o.optLong("longestQuietStretchMs")
                        )
                    )
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to load night logs", it)
        }.getOrDefault(emptyList())
    }

    private fun saveToDisk(nights: List<NightSummary>) {
        runCatching {
            val arr = JSONArray()
            nights.forEach { n ->
                arr.put(
                    JSONObject()
                        .put("startedAtMs", n.startedAtMs)
                        .put("endedAtMs", n.endedAtMs)
                        .put("micRestarts", n.micRestarts)
                        .put("motionRestarts", n.motionRestarts)
                        .put("manualRestarts", n.manualRestarts)
                        .put("farthestTrackIndex", n.farthestTrackIndex)
                        .put("farthestTrackTitle", n.farthestTrackTitle)
                        .put("trackCount", n.trackCount)
                        .put("farthestPositionMs", n.farthestPositionMs)
                        .put("longestQuietStretchMs", n.longestQuietStretchMs)
                )
            }
            file.writeText(arr.toString())
        }.onFailure {
            Log.e(TAG, "Failed to save night logs", it)
        }
    }

    companion object {
        private const val TAG = "NightLogRepository"
        private const val MAX_NIGHTS = 14
    }
}
