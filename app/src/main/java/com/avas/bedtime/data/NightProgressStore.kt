package com.avas.bedtime.data

import android.content.Context

/**
 * Tonight's running counters. Survives manual STOP and process kills so one night
 * produces one summary at wake time instead of one per stop.
 */
class NightProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Progress(
        val startedAtMs: Long,
        val lastEndedAtMs: Long,
        val deadlineEpochMs: Long,
        val runMs: Long,
        val micRestarts: Int,
        val motionRestarts: Int,
        val manualRestarts: Int,
        val farthestTrackIndex: Int,
        val farthestTrackTitle: String,
        val trackCount: Int,
        val farthestPositionMs: Long,
        val longestQuietStretchMs: Long,
        val earlyStops: Int = 0,
        val lastStopSource: String = ""
    ) {
        fun toSummary(): NightSummary = NightSummary(
            startedAtMs = startedAtMs,
            endedAtMs = lastEndedAtMs,
            micRestarts = micRestarts,
            motionRestarts = motionRestarts,
            manualRestarts = manualRestarts,
            farthestTrackIndex = farthestTrackIndex,
            farthestTrackTitle = farthestTrackTitle,
            trackCount = trackCount,
            farthestPositionMs = farthestPositionMs,
            longestQuietStretchMs = longestQuietStretchMs,
            earlyStops = earlyStops,
            lastStopSource = lastStopSource
        )
    }

    fun save(progress: Progress) {
        prefs.edit()
            .putBoolean(KEY_PRESENT, true)
            .putLong("startedAtMs", progress.startedAtMs)
            .putLong("lastEndedAtMs", progress.lastEndedAtMs)
            .putLong("deadlineEpochMs", progress.deadlineEpochMs)
            .putLong("runMs", progress.runMs)
            .putInt("micRestarts", progress.micRestarts)
            .putInt("motionRestarts", progress.motionRestarts)
            .putInt("manualRestarts", progress.manualRestarts)
            .putInt("farthestTrackIndex", progress.farthestTrackIndex)
            .putString("farthestTrackTitle", progress.farthestTrackTitle)
            .putInt("trackCount", progress.trackCount)
            .putLong("farthestPositionMs", progress.farthestPositionMs)
            .putLong("longestQuietStretchMs", progress.longestQuietStretchMs)
            .putInt("earlyStops", progress.earlyStops)
            .putString("lastStopSource", progress.lastStopSource)
            .apply()
    }

    fun load(): Progress? {
        if (!prefs.getBoolean(KEY_PRESENT, false)) return null
        return Progress(
            startedAtMs = prefs.getLong("startedAtMs", 0L),
            lastEndedAtMs = prefs.getLong("lastEndedAtMs", 0L),
            deadlineEpochMs = prefs.getLong("deadlineEpochMs", 0L),
            runMs = prefs.getLong("runMs", 0L),
            micRestarts = prefs.getInt("micRestarts", 0),
            motionRestarts = prefs.getInt("motionRestarts", 0),
            manualRestarts = prefs.getInt("manualRestarts", 0),
            farthestTrackIndex = prefs.getInt("farthestTrackIndex", -1),
            farthestTrackTitle = prefs.getString("farthestTrackTitle", "").orEmpty(),
            trackCount = prefs.getInt("trackCount", 0),
            farthestPositionMs = prefs.getLong("farthestPositionMs", 0L),
            longestQuietStretchMs = prefs.getLong("longestQuietStretchMs", 0L),
            earlyStops = prefs.getInt("earlyStops", 0),
            lastStopSource = prefs.getString("lastStopSource", "").orEmpty()
        )
    }

    /** Synchronous so a flush and a new START can't both claim the same night. */
    @Synchronized
    fun take(): Progress? {
        val progress = load() ?: return null
        prefs.edit().clear().commit()
        return progress
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "night_progress"
        private const val KEY_PRESENT = "present"
    }
}
