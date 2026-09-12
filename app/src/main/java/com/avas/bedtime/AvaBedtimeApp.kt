package com.avas.bedtime

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.avas.bedtime.data.NightLogRepository
import com.avas.bedtime.data.SettingsRepository
import com.avas.bedtime.player.OfflineDemoTone
import com.avas.bedtime.plex.PlexSignInCoordinator
import kotlin.concurrent.thread

class AvaBedtimeApp : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var nightLogRepository: NightLogRepository
        private set
    lateinit var plexSignIn: PlexSignInCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        nightLogRepository = NightLogRepository(this)
        plexSignIn = PlexSignInCoordinator(settingsRepository)
        createNotificationChannels()
        thread(name = "OfflineTonePrefetch", isDaemon = true) {
            runCatching { OfflineDemoTone.ensureFile(this) }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        // New id so importance change applies (Android won't fully update an existing channel).
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                setSound(null, null)
            }
        )
        runCatching { manager.deleteNotificationChannel("bedtime_playback") }
        runCatching { manager.deleteNotificationChannel("bedtime_playback_v2") }
        manager.createNotificationChannel(
            NotificationChannel(
                NIGHT_SUMMARY_CHANNEL_ID,
                getString(R.string.night_summary_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.night_summary_channel_desc)
            }
        )
    }

    companion object {
        // v3 = LOW importance quiet overnight channel
        const val NOTIFICATION_CHANNEL_ID = "bedtime_playback_v3"
        const val NIGHT_SUMMARY_CHANNEL_ID = "night_summary"
    }
}
