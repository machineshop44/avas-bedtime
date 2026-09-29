package com.avas.bedtime.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.avas.bedtime.AvaBedtimeApp
import com.avas.bedtime.MainActivity
import com.avas.bedtime.R
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.data.NightProgressStore
import com.avas.bedtime.data.NightSummary
import com.avas.bedtime.notify.DiscordWebhookSender

/**
 * One summary per night, delivered at wake time (or when the timer ends).
 */
object NightSummaryDispatcher {
    private const val TAG = "NightSummary"
    private const val SUMMARY_NOTIFICATION_ID = 43
    private const val FLUSH_REQ = 78
    const val ACTION_FLUSH = "com.avas.bedtime.FLUSH_NIGHT"

    /** Blips (tests, a kid tapping START then STOP) aren't worth a Discord post. */
    const val MIN_RUN_MS_TO_REPORT = 10 * 60_000L

    /** A short paused session older than this starts a fresh night instead of merging. */
    const val STALE_SHORT_GAP_MS = 60 * 60_000L

    /**
     * Save to the in-app log, post the local notification, and send to Discord.
     * Blocking — call off the main thread.
     */
    fun deliverSync(context: Context, settings: BedtimeSettings, progress: NightProgressStore.Progress) {
        if (progress.runMs < MIN_RUN_MS_TO_REPORT) {
            Log.i(TAG, "Skipping summary — only ${progress.runMs / 1000}s of playback")
            return
        }
        val summary = progress.toSummary()
        val app = context.applicationContext as? AvaBedtimeApp
        app?.nightLogRepository?.add(summary)
        postNotification(context, settings, summary)
        val webhookUrl = settings.discordWebhookUrl
        if (DiscordWebhookSender.isValidWebhookUrl(webhookUrl)) {
            val ok = DiscordWebhookSender.sendNightSummarySync(
                webhookUrl,
                "${settings.possessiveName} night",
                summary.formatNotificationBody()
            )
            Log.i(TAG, "Discord night summary ok=$ok")
        } else {
            Log.i(TAG, "No Discord webhook configured — local summary only")
        }
    }

    /** Deliver a paused night whose wake time has passed. Blocking. */
    fun flushIfDueSync(context: Context, settings: BedtimeSettings) {
        val store = NightProgressStore(context)
        val pending = store.load() ?: return
        if (BedtimeService.state.value.active) return
        if (pending.deadlineEpochMs > System.currentTimeMillis()) return
        val taken = store.take() ?: return
        Log.i(TAG, "Flushing paused night (deadline passed)")
        deliverSync(context, settings, taken)
    }

    fun scheduleFlush(context: Context, atEpochMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = flushIntent(context)
        am.cancel(pi)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi)
        }
        Log.i(TAG, "Paused-night summary scheduled for $atEpochMs")
    }

    fun cancelFlush(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(flushIntent(context))
    }

    private fun flushIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            FLUSH_REQ,
            Intent(context, BedtimeAlarmReceiver::class.java).setAction(ACTION_FLUSH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun postNotification(context: Context, settings: BedtimeSettings, summary: NightSummary) {
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AvaBedtimeApp.NIGHT_SUMMARY_CHANNEL_ID)
            .setContentTitle("${settings.possessiveName} night")
            .setContentText("Restarts ${summary.totalRestarts} · ${summary.formatFarthest()}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.formatNotificationBody()))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e(TAG, "Could not post night summary notification", it)
        }
    }
}
