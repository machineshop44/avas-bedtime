package com.avas.bedtime.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bedtime_settings")

data class BedtimeSettings(
    val clientId: String = "",
    val plexToken: String = "",
    val serverAccessToken: String = "",
    val plexUsername: String = "",
    val serverUrl: String = "",
    val serverName: String = "",
    val playlistId: String = "",
    val playlistTitle: String = "",
    val themeId: String = "unicorn",
    val endMode: String = EndMode.WakeUp.storageKey,
    val timerHours: Int = 8,
    val bedtimeHour: Int = 20,
    val bedtimeMinute: Int = 0,
    val wakeHour: Int = 7,
    val wakeMinute: Int = 0,
    val micSensitivity: Float = 0.8f,
    val motionSensitivity: Float = 0.85f,
    val micEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val cooldownSeconds: Int = 25,
    /** Absolute path to copied Ava photo in app files; blank if none. */
    val avaPhotoPath: String = "",
    val childName: String = "Ava"
) {
    val isPlexSignedIn: Boolean get() = plexToken.isNotBlank()
    val pmsToken: String get() = serverAccessToken.ifBlank { plexToken }
    val hasBedtimePlaylist: Boolean
        get() = serverUrl.isNotBlank() && playlistId.isNotBlank() && pmsToken.isNotBlank()
    val hasAvaPhoto: Boolean get() = avaPhotoPath.isNotBlank()
    val displayName: String get() = childName.trim().ifBlank { "Ava" }
    /** Possessive form for UI: Ava → Ava's, Jess → Jess' */
    val possessiveName: String
        get() {
            val name = displayName
            return if (name.endsWith("s", ignoreCase = true)) "$name'" else "$name's"
        }
    val resolvedEndMode: EndMode get() = EndMode.fromStorage(endMode)
    val bedtimeLabel: String get() = ScheduleTime.formatClock(bedtimeHour, bedtimeMinute)
    val wakeLabel: String get() = ScheduleTime.formatClock(wakeHour, wakeMinute)
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val clientId = stringPreferencesKey("client_id")
        val plexToken = stringPreferencesKey("plex_token")
        val serverAccessToken = stringPreferencesKey("server_access_token")
        val plexUsername = stringPreferencesKey("plex_username")
        val serverUrl = stringPreferencesKey("server_url")
        val serverName = stringPreferencesKey("server_name")
        val playlistId = stringPreferencesKey("playlist_id")
        val playlistTitle = stringPreferencesKey("playlist_title")
        val themeId = stringPreferencesKey("theme_id")
        val endMode = stringPreferencesKey("end_mode")
        val timerHours = intPreferencesKey("timer_hours")
        val bedtimeHour = intPreferencesKey("bedtime_hour")
        val bedtimeMinute = intPreferencesKey("bedtime_minute")
        val wakeHour = intPreferencesKey("wake_hour")
        val wakeMinute = intPreferencesKey("wake_minute")
        val micSensitivity = floatPreferencesKey("mic_sensitivity")
        val motionSensitivity = floatPreferencesKey("motion_sensitivity")
        val micEnabled = booleanPreferencesKey("mic_enabled")
        val motionEnabled = booleanPreferencesKey("motion_enabled")
        val cooldownSeconds = intPreferencesKey("cooldown_seconds")
        val avaPhotoPath = stringPreferencesKey("ava_photo_path")
        val childName = stringPreferencesKey("child_name")
    }

    val settings: Flow<BedtimeSettings> = context.dataStore.data.map { prefs ->
        BedtimeSettings(
            clientId = prefs[Keys.clientId].orEmpty(),
            plexToken = prefs[Keys.plexToken].orEmpty(),
            serverAccessToken = prefs[Keys.serverAccessToken].orEmpty(),
            plexUsername = prefs[Keys.plexUsername].orEmpty(),
            serverUrl = prefs[Keys.serverUrl].orEmpty(),
            serverName = prefs[Keys.serverName].orEmpty(),
            playlistId = prefs[Keys.playlistId].orEmpty(),
            playlistTitle = prefs[Keys.playlistTitle].orEmpty(),
            themeId = prefs[Keys.themeId] ?: "unicorn",
            endMode = prefs[Keys.endMode] ?: EndMode.WakeUp.storageKey,
            timerHours = prefs[Keys.timerHours] ?: 8,
            bedtimeHour = prefs[Keys.bedtimeHour] ?: 20,
            bedtimeMinute = prefs[Keys.bedtimeMinute] ?: 0,
            wakeHour = prefs[Keys.wakeHour] ?: 7,
            wakeMinute = prefs[Keys.wakeMinute] ?: 0,
            micSensitivity = prefs[Keys.micSensitivity] ?: 0.8f,
            motionSensitivity = prefs[Keys.motionSensitivity] ?: 0.85f,
            micEnabled = prefs[Keys.micEnabled] ?: true,
            motionEnabled = prefs[Keys.motionEnabled] ?: true,
            cooldownSeconds = prefs[Keys.cooldownSeconds] ?: 25,
            avaPhotoPath = prefs[Keys.avaPhotoPath].orEmpty(),
            childName = prefs[Keys.childName] ?: "Ava"
        )
    }

    suspend fun ensureClientId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[Keys.clientId].orEmpty()
            if (id.isBlank()) {
                id = UUID.randomUUID().toString()
                prefs[Keys.clientId] = id
            }
        }
        return id
    }

    /** Older builds used very insensitive defaults / long cooldown; nudge once. */
    suspend fun migrateDetectionDefaultsIfNeeded() {
        update { current ->
            val needsBump = current.cooldownSeconds >= 60 ||
                current.micSensitivity <= 0.5f ||
                current.motionSensitivity <= 0.5f
            if (!needsBump) current
            else current.copy(
                micSensitivity = maxOf(current.micSensitivity, 0.8f),
                motionSensitivity = maxOf(current.motionSensitivity, 0.85f),
                cooldownSeconds = if (current.cooldownSeconds >= 60) 25 else current.cooldownSeconds
            )
        }
    }

    suspend fun update(transform: (BedtimeSettings) -> BedtimeSettings) {
        context.dataStore.edit { prefs ->
            val current = BedtimeSettings(
                clientId = prefs[Keys.clientId].orEmpty(),
                plexToken = prefs[Keys.plexToken].orEmpty(),
                serverAccessToken = prefs[Keys.serverAccessToken].orEmpty(),
                plexUsername = prefs[Keys.plexUsername].orEmpty(),
                serverUrl = prefs[Keys.serverUrl].orEmpty(),
                serverName = prefs[Keys.serverName].orEmpty(),
                playlistId = prefs[Keys.playlistId].orEmpty(),
                playlistTitle = prefs[Keys.playlistTitle].orEmpty(),
                themeId = prefs[Keys.themeId] ?: "unicorn",
                endMode = prefs[Keys.endMode] ?: EndMode.WakeUp.storageKey,
                timerHours = prefs[Keys.timerHours] ?: 8,
                bedtimeHour = prefs[Keys.bedtimeHour] ?: 20,
                bedtimeMinute = prefs[Keys.bedtimeMinute] ?: 0,
                wakeHour = prefs[Keys.wakeHour] ?: 7,
                wakeMinute = prefs[Keys.wakeMinute] ?: 0,
                micSensitivity = prefs[Keys.micSensitivity] ?: 0.8f,
                motionSensitivity = prefs[Keys.motionSensitivity] ?: 0.85f,
                micEnabled = prefs[Keys.micEnabled] ?: true,
                motionEnabled = prefs[Keys.motionEnabled] ?: true,
                cooldownSeconds = prefs[Keys.cooldownSeconds] ?: 25,
                avaPhotoPath = prefs[Keys.avaPhotoPath].orEmpty(),
                childName = prefs[Keys.childName] ?: "Ava"
            )
            val next = transform(current)
            prefs[Keys.clientId] = next.clientId
            prefs[Keys.plexToken] = next.plexToken
            prefs[Keys.serverAccessToken] = next.serverAccessToken
            prefs[Keys.plexUsername] = next.plexUsername
            prefs[Keys.serverUrl] = next.serverUrl
            prefs[Keys.serverName] = next.serverName
            prefs[Keys.playlistId] = next.playlistId
            prefs[Keys.playlistTitle] = next.playlistTitle
            prefs[Keys.themeId] = next.themeId
            prefs[Keys.endMode] = next.endMode
            prefs[Keys.timerHours] = next.timerHours
            prefs[Keys.bedtimeHour] = next.bedtimeHour
            prefs[Keys.bedtimeMinute] = next.bedtimeMinute
            prefs[Keys.wakeHour] = next.wakeHour
            prefs[Keys.wakeMinute] = next.wakeMinute
            prefs[Keys.micSensitivity] = next.micSensitivity
            prefs[Keys.motionSensitivity] = next.motionSensitivity
            prefs[Keys.micEnabled] = next.micEnabled
            prefs[Keys.motionEnabled] = next.motionEnabled
            prefs[Keys.cooldownSeconds] = next.cooldownSeconds
            prefs[Keys.avaPhotoPath] = next.avaPhotoPath
            prefs[Keys.childName] = next.childName
        }
    }
}
