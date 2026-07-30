package com.avas.bedtime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avas.bedtime.AvaBedtimeApp
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.session.BedtimeService

private enum class Screen { Kid, Settings }

@Composable
fun BedtimeApp() {
    val context = LocalContext.current
    val app = context.applicationContext as AvaBedtimeApp
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = BedtimeSettings()
    )
    var screen by remember { mutableStateOf(Screen.Kid) }

    LaunchedEffect(settings) {
        BedtimeService.latestSettings = settings
        if (settings.clientId.isBlank()) {
            app.settingsRepository.ensureClientId()
        }
    }

    LaunchedEffect(Unit) {
        app.settingsRepository.migrateDetectionDefaultsIfNeeded()
    }

    BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Kid
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (screen) {
            Screen.Kid -> KidHomeScreen(
                settings = settings,
                onOpenSettings = { screen = Screen.Settings }
            )
            Screen.Settings -> SettingsScreen(
                settings = settings,
                repository = app.settingsRepository,
                onBack = { screen = Screen.Kid }
            )
        }
    }
}
