package com.avas.bedtime.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.avas.bedtime.R

/**
 * Tiny one-shot player for the unicorn tap easter egg.
 */
class UnicornNeighPlayer(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private var soundId: Int = 0
    private var loaded = false
    private var lastPlayAtMs = 0L

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == soundId) loaded = true
        }
        soundId = runCatching {
            pool.load(context.applicationContext, R.raw.unicorn_neigh, 1)
        }.onFailure {
            Log.w(TAG, "Failed to load unicorn neigh", it)
        }.getOrDefault(0)
    }

    fun play() {
        if (!loaded || soundId == 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPlayAtMs < 700L) return
        lastPlayAtMs = now
        // Slight random pitch so repeats feel playful (keep near natural neigh).
        val rate = 0.96f + (Math.random().toFloat() * 0.1f)
        pool.play(soundId, 0.95f, 0.95f, 1, 0, rate)
    }

    fun release() {
        runCatching { pool.release() }
        loaded = false
        soundId = 0
    }

    companion object {
        private const val TAG = "UnicornNeigh"
    }
}

@Composable
fun rememberUnicornNeighPlayer(): UnicornNeighPlayer {
    val context = LocalContext.current
    val player = remember { UnicornNeighPlayer(context) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}
