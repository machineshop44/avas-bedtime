package com.avas.bedtime.notify

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object DiscordWebhookSender {
    private const val TAG = "DiscordWebhook"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val asyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "DiscordWebhook").apply { isDaemon = true }
    }

    fun isValidWebhookUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://discord.com/api/webhooks/") ||
            trimmed.startsWith("https://discordapp.com/api/webhooks/")
    }

    /**
     * Fire-and-forget POST (UI / tests). Prefer [sendNightSummarySync] from the service
     * so the process is not killed before the request finishes.
     */
    fun sendNightSummaryAsync(webhookUrl: String, title: String, body: String) {
        asyncExecutor.execute {
            sendNightSummarySync(webhookUrl, title, body)
        }
    }

    /**
     * Blocking POST. Call from a background thread / IO dispatcher before [android.app.Service.stopSelf]
     * so wake-timer endings still reach Discord (manual STOP used to work because the app stayed alive).
     */
    fun sendNightSummarySync(webhookUrl: String, title: String, body: String): Boolean {
        val url = webhookUrl.trim()
        if (!isValidWebhookUrl(url)) {
            if (url.isNotBlank()) Log.w(TAG, "Ignoring invalid Discord webhook URL")
            return false
        }
        val payload = JSONObject()
            .put(
                "embeds",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("title", title.take(256))
                        .put("description", body.take(4000))
                        .put("color", 0xC4A574)
                )
            )
            .toString()

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMedia))
            .header("User-Agent", "AvaBedtime")
            .build()

        repeat(3) { attempt ->
            val ok = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Discord webhook HTTP ${response.code} (attempt ${attempt + 1})")
                        false
                    } else {
                        Log.i(TAG, "Discord night summary sent")
                        true
                    }
                }
            }.onFailure {
                Log.e(TAG, "Discord webhook failed (attempt ${attempt + 1})", it)
            }.getOrDefault(false)
            if (ok) return true
            if (attempt < 2) Thread.sleep(1_500L * (attempt + 1))
        }
        return false
    }
}
