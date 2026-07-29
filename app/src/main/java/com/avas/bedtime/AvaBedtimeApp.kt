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
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
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
        const val NOTIFICATION_CHANNEL_ID = "bedtime_playback"
        const val NIGHT_SUMMARY_CHANNEL_ID = "night_summary"
    }
}
