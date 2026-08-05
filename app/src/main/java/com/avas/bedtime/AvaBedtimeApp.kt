package com.avas.bedtime

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.avas.bedtime.data.NightLogRepository
import com.avas.bedtime.data.SettingsRepository
import com.avas.bedtime.plex.PlexSignInCoordinator

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
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        // New id so lock-screen visibility / importance actually apply (Android won't
        // fully update those on an already-created channel).
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
        // Remove legacy low-importance channel if present.
        runCatching { manager.deleteNotificationChannel("bedtime_playback") }
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
        const val NOTIFICATION_CHANNEL_ID = "bedtime_playback_v2"
        const val NIGHT_SUMMARY_CHANNEL_ID = "night_summary"
    }
}
