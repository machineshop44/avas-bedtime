package com.avas.bedtime.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.avas.bedtime.plex.PlexApi
import com.avas.bedtime.plex.PlexTimelineReporter
import kotlinx.coroutines.CoroutineScope

class PlaylistPlayer(
    context: Context,
    private val scope: CoroutineScope
) {
    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ALL
    }

    private var tracks: List<PlexApi.Track> = emptyList()
    private var timeline: PlexTimelineReporter? = null

    val isPlaying: Boolean
        get() = player.isPlaying

    fun currentTitle(): String =
        player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: "Bedtime music"

    /** Snapshot of how far into the playlist playback currently is. */
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
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    timeline?.onStopped()
                }
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
        timeline?.onStopped()
        timeline = PlexTimelineReporter(
            serverUrl = serverUrl,
            token = token,
            clientId = clientId,
            scope = scope
        )
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setUri(api.streamUrl(serverUrl, token, track.partKey))
                .setMediaId(track.ratingKey)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
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

    fun playDemoToneLoop() {
        timeline?.onStopped()
        timeline = null
        tracks = emptyList()
        val item = MediaItem.fromUri(
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        )
        player.setMediaItem(item)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.play()
        Log.i(TAG, "Demo loop started")
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun restartFromBeginning() {
        if (player.mediaItemCount == 0) return
        player.seekTo(0, 0L)
        bindCurrentTrackToTimeline()
        player.play()
        timeline?.onSeekOrTrackChange()
        Log.i(TAG, "Playlist restarted from beginning")
    }

    fun release() {
        timeline?.onStopped()
        timeline = null
        player.release()
    }

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
    }
}

data class PlaylistProgress(
    val index: Int,
    val trackCount: Int,
    val title: String,
    val positionMs: Long
)
