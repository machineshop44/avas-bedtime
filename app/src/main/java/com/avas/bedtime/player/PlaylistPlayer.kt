package com.avas.bedtime.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.avas.bedtime.R
import com.avas.bedtime.plex.PlexApi
import com.avas.bedtime.plex.PlexHeaders
import com.avas.bedtime.plex.PlexTimelineReporter
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope

class PlaylistPlayer(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val artworkPng: ByteArray by lazy { loadArtworkPng(appContext) }

    /** Token goes in headers — not the stream URL — so it stays out of logs/proxies. */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)

    private val player = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ALL
            setWakeMode(C.WAKE_MODE_NETWORK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
        }

    private var tracks: List<PlexApi.Track> = emptyList()
    private var timeline: PlexTimelineReporter? = null
    private var usingLocalDemo = false

    /** Fired when ExoPlayer advances/seeks to another playlist item. */
    var onTrackChanged: (() -> Unit)? = null

    /** Fired when playback fails mid-night (network/Plex). UI may show status. */
    var onPlaybackError: ((String) -> Unit)? = null

    val isPlaying: Boolean
        get() = player.isPlaying

    fun currentTitle(): String =
        player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: "Bedtime music"

    fun currentProgress(): PlaylistProgress {
        val count = player.mediaItemCount
        if (count <= 0) {
            return PlaylistProgress(
                index = -1,
                trackCount = 0,
                title = currentTitle(),
                positionMs = 0L
            )
        }
        return PlaylistProgress(
            index = player.currentMediaItemIndex.coerceIn(0, count - 1),
            trackCount = count,
            title = currentTitle(),
            positionMs = player.currentPosition.coerceAtLeast(0L)
        )
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val reporter = timeline ?: return
                if (isPlaying) reporter.onPlaying() else reporter.onPaused()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                bindCurrentTrackToTimeline()
                timeline?.onSeekOrTrackChange()
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    onTrackChanged?.invoke()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    timeline?.onStopped()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.errorCodeName}", error)
                onPlaybackError?.invoke(error.errorCodeName ?: "playback_error")
                recoverFromError()
            }
        })
    }

    fun setTracks(
        api: PlexApi,
        serverUrl: String,
        token: String,
        clientId: String,
        tracks: List<PlexApi.Track>
    ) {
        this.tracks = tracks
        usingLocalDemo = false
        httpDataSourceFactory.setDefaultRequestProperties(
            mapOf(
                "X-Plex-Token" to token,
                "X-Plex-Client-Identifier" to clientId.ifBlank { "ava-bedtime" },
                "X-Plex-Product" to PlexHeaders.PRODUCT,
                "X-Plex-Version" to PlexHeaders.VERSION,
                "X-Plex-Platform" to PlexHeaders.PLATFORM,
                "X-Plex-Device" to "Android",
                "X-Plex-Device-Name" to "Ava Bedtime",
                "Accept" to "*/*"
            )
        )
        timeline?.onStopped()
        timeline = PlexTimelineReporter(
            serverUrl = serverUrl,
            token = token,
            clientId = clientId,
            scope = scope
        )
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setUri(api.streamUrl(serverUrl, track.partKey))
                .setMediaId(track.ratingKey)
                .setMediaMetadata(bedtimeMetadata(track.title, track.artist))
                .build()
        }
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.setMediaItems(mediaItems, /* resetPosition = */ true)
        player.prepare()
        bindCurrentTrackToTimeline()
        player.play()
        timeline?.onPlaying()
        Log.i(TAG, "Playing ${tracks.size} Plex tracks (timeline reporting on)")
    }

    /** Soft local WAV loop — works with zero network (Hershey / airplane mode). */
    fun playDemoToneLoop() {
        timeline?.onStopped()
        timeline = null
        tracks = emptyList()
        usingLocalDemo = true
        val demo = OfflineDemoTone.file(appContext)
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(demo))
            .setMediaId("offline-demo")
            .setMediaMetadata(bedtimeMetadata("Offline bedtime tone", "Ava Bedtime"))
            .build()
        player.setMediaItem(item)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.play()
        Log.i(TAG, "Local offline demo loop started (${demo.length()} bytes)")
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Hard stop so a cancelled load cannot resume audio after STOP. */
    fun stopAndClear() {
        timeline?.onStopped()
        timeline = null
        tracks = emptyList()
        usingLocalDemo = false
        runCatching {
            player.pause()
            player.stop()
            player.clearMediaItems()
        }
    }

    fun restartFromBeginning() {
        if (player.mediaItemCount == 0) return
        player.seekTo(0, 0L)
        bindCurrentTrackToTimeline()
        player.play()
        timeline?.onSeekOrTrackChange()
        Log.i(TAG, "Playlist restarted from beginning")
    }

    fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    fun currentDurationMs(): Long = player.duration.coerceAtLeast(0L)

    fun release() {
        timeline?.onStopped()
        timeline = null
        player.release()
    }

    private fun recoverFromError() {
        if (usingLocalDemo) {
            playDemoToneLoop()
            return
        }
        val count = player.mediaItemCount
        if (count <= 0) {
            playDemoToneLoop()
            return
        }
        val next = (player.currentMediaItemIndex + 1) % count
        runCatching {
            player.seekTo(next, 0L)
            player.prepare()
            player.play()
            bindCurrentTrackToTimeline()
            timeline?.onSeekOrTrackChange()
            Log.i(TAG, "Recovered by skipping to index $next")
        }.onFailure {
            Log.e(TAG, "Skip recovery failed — falling back to offline demo", it)
            playDemoToneLoop()
        }
    }

    private fun bedtimeMetadata(title: String, artist: String?): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist?.ifBlank { null } ?: "Ava Bedtime")
            .setAlbumTitle("Bedtime")
            .setArtworkData(artworkPng, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

    private fun bindCurrentTrackToTimeline() {
        val reporter = timeline ?: return
        val index = player.currentMediaItemIndex
        val track = tracks.getOrNull(index) ?: return
        val duration = when {
            track.durationMs > 0L -> track.durationMs
            player.duration > 0L -> player.duration
            else -> 1L
        }
        reporter.attach(
            ratingKey = track.ratingKey,
            durationMs = duration,
            positionMs = { player.currentPosition.coerceAtLeast(0L) }
        )
    }

    companion object {
        private const val TAG = "PlaylistPlayer"

        private fun loadArtworkPng(context: Context): ByteArray {
            val size = (128 * context.resources.displayMetrics.density).toInt().coerceAtLeast(128)
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: ContextCompat.getDrawable(context, R.drawable.ic_notification)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (drawable != null) {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            } else {
                canvas.drawColor(0xFF2E1F4A.toInt())
            }
            return try {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

data class PlaylistProgress(
    val index: Int,
    val trackCount: Int,
    val title: String,
    val positionMs: Long
)
