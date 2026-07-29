package com.avas.bedtime.data

import android.os.SystemClock
import java.util.Calendar
import java.util.Locale

enum class EndMode(val storageKey: String) {
    Duration("duration"),
    WakeUp("wakeup");

    companion object {
        fun fromStorage(key: String): EndMode =
            entries.firstOrNull { it.storageKey == key } ?: WakeUp
    }
}

object ScheduleTime {
    fun formatClock(hour24: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
        }
        val h = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val m = cal.get(Calendar.MINUTE)
        val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        return String.format(Locale.US, "%d:%02d %s", h, m, amPm)
    }

    /**
     * Next wall-clock occurrence of hour:minute, as [SystemClock.elapsedRealtime] deadline.
     * If that time has already passed today, uses tomorrow (typical overnight bedtime → morning wake).
     */
    fun nextOccurrenceElapsedRealtime(hour24: Int, minute: Int): Long {
        val nowWall = System.currentTimeMillis()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowWall + 30_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val delayMs = (target.timeInMillis - nowWall).coerceAtLeast(60_000L)
        return SystemClock.elapsedRealtime() + delayMs
    }
}
