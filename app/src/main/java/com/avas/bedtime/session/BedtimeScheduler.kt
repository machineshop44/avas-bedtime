package com.avas.bedtime.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.avas.bedtime.AvaBedtimeApp
import com.avas.bedtime.data.BedtimeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Optional auto-start at the configured bedtime clock.
 */
object BedtimeScheduler {
    private const val TAG = "BedtimeScheduler"
    private const val REQ = 77
    const val ACTION_ALARM = "com.avas.bedtime.BEDTIME_ALARM"

    fun reschedule(context: Context, settings: BedtimeSettings) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        am.cancel(pi)
        if (!settings.autoStartAtBedtime || !settings.hasBedtimePlaylist) {
            Log.i(TAG, "Auto-start alarm cleared")
            return
        }
        val triggerAt = nextBedtimeEpochMs(settings.bedtimeHour, settings.bedtimeMinute)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.w(TAG, "Exact alarms not allowed — using inexact while-idle")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "Auto-start scheduled for ${settings.bedtimeLabel} ($triggerAt)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not schedule bedtime alarm", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BedtimeAlarmReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun nextBedtimeEpochMs(hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
            if (timeInMillis <= System.currentTimeMillis() + 15_000L) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }
}

class BedtimeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != BedtimeScheduler.ACTION_ALARM && action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        Log.i("BedtimeScheduler", "Received $action")
        val pending = goAsync()
        Thread({
            try {
                val app = context.applicationContext as? AvaBedtimeApp
                val settings = if (app != null) {
                    runBlocking { app.settingsRepository.settings.first() }
                } else {
                    BedtimeService.latestSettings
                }
                BedtimeService.applyStirSettings(settings)
                if (action == Intent.ACTION_BOOT_COMPLETED) {
                    if (settings.autoStartAtBedtime) {
                        BedtimeScheduler.reschedule(context, settings)
                    }
                    return@Thread
                }
                if (!settings.hasBedtimePlaylist) {
                    Log.w("BedtimeScheduler", "Alarm fired but playlist not configured")
                    return@Thread
                }
                val start = Intent(context, BedtimeService::class.java)
                    .setAction(BedtimeService.ACTION_START)
                ContextCompat.startForegroundService(context, start)
                if (settings.autoStartAtBedtime) {
                    BedtimeScheduler.reschedule(context, settings)
                }
            } catch (e: Exception) {
                Log.e("BedtimeScheduler", "Alarm handling failed", e)
            } finally {
                pending.finish()
            }
        }, "BedtimeAlarm").start()
    }
}
