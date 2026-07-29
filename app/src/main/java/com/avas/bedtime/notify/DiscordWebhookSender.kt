package com.avas.bedtime.notify

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
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

    fun isValidWebhookUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://discord.com/api/webhooks/") ||
            trimmed.startsWith("https://discordapp.com/api/webhooks/")
    }

    /**
     * Fire-and-forget POST so bedtime stop is not blocked / cancelled with the service scope.
     */
    fun sendNightSummaryAsync(webhookUrl: String, title: String, body: String) {
        val url = webhookUrl.trim()
        if (!isValidWebhookUrl(url)) {
            if (url.isNotBlank()) Log.w(TAG, "Ignoring invalid Discord webhook URL")
            return
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

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Discord webhook failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.e(TAG, "Discord webhook HTTP ${it.code}: ${it.body?.string()}")
                    } else {
                        Log.i(TAG, "Discord night summary sent")
                    }
                }
            }
        })
    }
}
