package com.avas.bedtime.data

import android.content.Context

/**
 * Persists an active overnight session so OEM process kills can restore music.
 */
class NightSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val endsAtEpochMs: Long,
        val shufflePlaylist: Boolean
    ) {
        fun stillActive(nowMs: Long = System.currentTimeMillis()): Boolean =
            endsAtEpochMs > nowMs + 30_000L
    }

    fun save(endsAtEpochMs: Long, shufflePlaylist: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_ENDS, endsAtEpochMs)
            .putBoolean(KEY_SHUFFLE, shufflePlaylist)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun load(): Snapshot? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val ends = prefs.getLong(KEY_ENDS, 0L)
        if (ends <= 0L) return null
        return Snapshot(
            endsAtEpochMs = ends,
            shufflePlaylist = prefs.getBoolean(KEY_SHUFFLE, false)
        )
    }

    companion object {
        private const val PREFS = "night_session"
        private const val KEY_ACTIVE = "active"
        private const val KEY_ENDS = "ends_at_epoch_ms"
        private const val KEY_SHUFFLE = "shuffle"
    }
}
