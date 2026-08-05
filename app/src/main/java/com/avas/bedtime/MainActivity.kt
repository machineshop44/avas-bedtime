package com.avas.bedtime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.avas.bedtime.notify.DiscordWebhookSender
import com.avas.bedtime.ui.BedtimeApp
import com.avas.bedtime.ui.theme.AvaBedtimeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDiscordWebhookExtra(intent)
        enableEdgeToEdge()
        setContent {
            AvaBedtimeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BedtimeApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDiscordWebhookExtra(intent)
    }

    private fun applyDiscordWebhookExtra(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_DISCORD_WEBHOOK)?.trim().orEmpty()
        if (url.isBlank() || !DiscordWebhookSender.isValidWebhookUrl(url)) return
        val app = application as AvaBedtimeApp
        lifecycleScope.launch {
            app.settingsRepository.update { it.copy(discordWebhookUrl = url) }
        }
        intent?.removeExtra(EXTRA_DISCORD_WEBHOOK)
    }

    companion object {
        const val EXTRA_DISCORD_WEBHOOK = "discord_webhook"
    }
}
