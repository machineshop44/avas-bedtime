package com.avas.bedtime.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.avas.bedtime.AvaBedtimeApp
import com.avas.bedtime.MainActivity
import com.avas.bedtime.R
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.data.EndMode
import com.avas.bedtime.data.NightSummary
import com.avas.bedtime.data.ScheduleTime
import com.avas.bedtime.detect.StirDetector
import com.avas.bedtime.detect.StirSource
import com.avas.bedtime.notify.DiscordWebhookSender
import com.avas.bedtime.player.PlaylistPlayer
import com.avas.bedtime.player.PlaylistProgress
import com.avas.bedtime.plex.PlexApi
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BedtimeSessionState(
    val active: Boolean = false,
    val trackTitle: String = "",
    val endsAtElapsedRealtime: Long = 0L,
    val lastStirSource: String? = null,
    val statusMessage: String = "Idle"
)

class BedtimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: PlaylistPlayer
    private var stirDetector: StirDetector? = null
    private var timerJob: Job? = null
    private var loadJob: Job? = null
    private var shutdownJob: Job? = null
    private var endsAtElapsed = 0L
    private var lastNotificationContent: String? = null

    private var loggingSession = false
    private var sessionStartedAtMs = 0L
    private var stretchStartedElapsed = 0L
    private var longestQuietMs = 0L
    private var micRestarts = 0
    private var motionRestarts = 0
    private var manualRestarts = 0
    private var farthestIndex = -1
    private var farthestTitle = ""
    private var farthestPositionMs = 0L
    private var trackCount = 0
    /** Bumped on START/STOP so cancelled Discord shutdown cannot stopSelf a new session. */
    private var lifecycleGeneration = 0
    /** Bumped when playback ownership changes so a late load cannot resume after STOP. */
    private var playGeneration = 0

    override fun onCreate() {
        super.onCreate()
        player = PlaylistPlayer(this, scope)
        player.onTrackChanged = {
            stirDetector?.ignoreAudioChange()
        }
        player.onPlaybackError = { code ->
            scope.launch {
                if (_state.value.active) {
                    _state.value = _state.value.copy(
                        statusMessage = "Connection issue — recovering ($code)"
                    )
                    stirDetector?.ignoreAudioChange(8_000L)
                }
            }
        }
        instance = this
        _state.value = BedtimeSessionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Never reset an in-progress bedtime timer if Start is sent again.
                if (_state.value.active && endsAtElapsed > SystemClock.elapsedRealtime()) {
                    Log.i(TAG, "Start ignored — session already active; timer unchanged")
                    return START_NOT_STICKY
                }
                startSession(latestSettings)
            }
            ACTION_STOP -> stopSession(reason = "manual")
            ACTION_RESTART -> restartPlaylistOnly(sourceLabel = null)
            else -> {
                // Sticky/system restart with no action: satisfy FGS timeout then exit.
                // Full overnight restore needs persisted session state (not done here).
                Log.w(TAG, "onStartCommand with no action — promoting FGS then stopping")
                startForeground(
                    NOTIFICATION_ID,
                    buildPlaybackNotification("Stopped")
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(settings: BedtimeSettings) {
        // Invalidate any in-flight Discord shutdown so its finally cannot stopSelf us.
        lifecycleGeneration++
        shutdownJob?.cancel()
        shutdownJob = null
        endsAtElapsed = when (settings.resolvedEndMode) {
            EndMode.WakeUp -> ScheduleTime.nextOccurrenceElapsedRealtime(
                settings.wakeHour,
                settings.wakeMinute
            )
            EndMode.Duration -> {
                val hours = settings.timerHours.coerceIn(1, 14)
                SystemClock.elapsedRealtime() + hours * 3_600_000L
            }
        }
        val remainingLabel = formatRemaining(max(0L, endsAtElapsed - SystemClock.elapsedRealtime()))
        Log.i(
            TAG,
            "Session end mode=${settings.resolvedEndMode} remaining=$remainingLabel wake=${settings.wakeLabel}"
        )

        resetNightCounters()
        lastNotificationContent = null
        startForeground(NOTIFICATION_ID, buildPlaybackNotification("Starting bedtime…"))
        lastNotificationContent = "Starting bedtime…"
        _state.value = BedtimeSessionState(
            active = true,
            endsAtElapsedRealtime = endsAtElapsed,
            statusMessage = "Getting your music ready…"
        )

        loadJob?.cancel()
        val loadGen = ++playGeneration
        loadJob = scope.launch {
            val started = startPlayback(settings, loadGen)
            if (!isActive || loadGen != playGeneration) return@launch
            if (!started) {
                _state.value = _state.value.copy(
                    statusMessage = "Could not reach Plex — playing offline tone"
                )
                if (loadGen == playGeneration) {
                    player.playDemoToneLoop()
                }
            }
            if (!isActive || loadGen != playGeneration) return@launch
            beginMonitoring(settings)
        }
    }

    private fun resetNightCounters() {
        loggingSession = true
        sessionStartedAtMs = System.currentTimeMillis()
        stretchStartedElapsed = SystemClock.elapsedRealtime()
        longestQuietMs = 0L
        micRestarts = 0
        motionRestarts = 0
        manualRestarts = 0
        farthestIndex = -1
        farthestTitle = ""
        farthestPositionMs = 0L
        trackCount = 0
    }

    private suspend fun startPlayback(settings: BedtimeSettings, loadGen: Int): Boolean {
        if (!settings.hasBedtimePlaylist) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val clientId = settings.clientId.ifBlank { "ava-bedtime" }
                val api = PlexApi(clientId)
                val tracks = if (settings.playlistId.startsWith("section:")) {
                    val sectionKey = settings.playlistId.removePrefix("section:")
                    api.libraryTracks(
                        settings.serverUrl,
                        settings.pmsToken,
                        sectionKey
                    ).getOrThrow()
                } else {
                    api.playlistTracks(
                        settings.serverUrl,
                        settings.pmsToken,
                        settings.playlistId
                    ).getOrThrow()
                }
                if (tracks.isEmpty()) error("Playlist is empty")
                withContext(Dispatchers.Main) {
                    if (loadGen != playGeneration) return@withContext false
                    player.setTracks(
                        api,
                        settings.serverUrl,
                        settings.pmsToken,
                        clientId,
                        tracks
                    )
                    true
                }
            }.onFailure {
                Log.e(TAG, "Plex playback failed", it)
            }.getOrDefault(false)
        }
    }

    private fun beginMonitoring(settings: BedtimeSettings) {
        stirDetector?.stop()
        val detector = StirDetector(this, scope) { source ->
            onStir(source)
        }
        detector.updateConfig(
            micSensitivity = settings.micSensitivity,
            motionSensitivity = settings.motionSensitivity,
            micEnabled = settings.micEnabled,
            motionEnabled = settings.motionEnabled,
            cooldownSeconds = settings.cooldownSeconds
        )
        detector.start()
        stirDetector = detector

        timerJob?.cancel()
        timerJob = scope.launch {
            var startingOverUntil = 0L
            while (isActive) {
                noteProgress(player.currentProgress())
                val remaining = max(0L, endsAtElapsed - SystemClock.elapsedRealtime())
                val previous = _state.value
                val now = SystemClock.elapsedRealtime()
                if (previous.statusMessage == "Starting over" && startingOverUntil == 0L) {
                    startingOverUntil = now + 4_000L
                }
                val status = when {
                    remaining == 0L -> "Good morning"
                    now < startingOverUntil -> "Starting over"
                    else -> {
                        startingOverUntil = 0L
                        "Playing"
                    }
                }
                _state.value = previous.copy(
                    active = true,
                    trackTitle = player.currentTitle(),
                    endsAtElapsedRealtime = endsAtElapsed,
                    statusMessage = status
                )
                val notifContent = when {
                    remaining == 0L -> "Timer finished"
                    status == "Starting over" ->
                        "Starting over · ${formatRemaining(remaining)} left"
                    else -> "Bedtime · ${formatRemaining(remaining)} left"
                }
                // Remaining text only changes each minute — avoid rebuilding every second.
                if (notifContent != lastNotificationContent) {
                    lastNotificationContent = notifContent
                    startForeground(NOTIFICATION_ID, buildPlaybackNotification(notifContent))
                }
                if (remaining == 0L) {
                    Log.i(TAG, "Wake/duration timer reached — ending session + night summary")
                    stopSession(reason = "timer")
                    break
                }
                delay(1_000)
            }
        }
    }

    private fun onStir(source: StirSource) {
        // Mic/motion callbacks arrive off the main thread; ExoPlayer requires main.
        scope.launch {
            restartPlaylistOnly(sourceLabel = source.name)
        }
    }

    /**
     * Restarts playlist audio only. Does not touch [endsAtElapsed] or the sleep timer.
     */
    private fun restartPlaylistOnly(sourceLabel: String?) {
        if (!_state.value.active) return
        val timerEnd = endsAtElapsed
        noteProgress(player.currentProgress())
        closeQuietStretch()
        when (sourceLabel) {
            StirSource.Mic.name -> micRestarts++
            StirSource.Motion.name -> motionRestarts++
            else -> manualRestarts++
        }
        // Mute detection for the restart seek; never shorter than remaining cooldown.
        val muteMs = max(14_000L, stirDetector?.remainingCooldownMs() ?: 0L)
        stirDetector?.ignoreAudioChange(muteMs)
        player.restartFromBeginning()
        stretchStartedElapsed = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            endsAtElapsedRealtime = timerEnd,
            lastStirSource = sourceLabel ?: _state.value.lastStirSource,
            statusMessage = "Starting over"
        )
        val left = formatRemaining(max(0L, timerEnd - SystemClock.elapsedRealtime()))
        val notifContent = "Starting over · $left left"
        lastNotificationContent = notifContent
        startForeground(NOTIFICATION_ID, buildPlaybackNotification(notifContent))
        Log.i(
            TAG,
            "Playlist restarted (mute ${muteMs}ms); timer unchanged ($left left)"
        )
    }

    private fun noteProgress(progress: PlaylistProgress) {
        if (progress.trackCount > 0) trackCount = progress.trackCount
        if (progress.index < 0) return
        val farther = progress.index > farthestIndex ||
            (progress.index == farthestIndex && progress.positionMs > farthestPositionMs)
        if (farther) {
            farthestIndex = progress.index
            farthestTitle = progress.title
            farthestPositionMs = progress.positionMs
        }
    }

    private fun closeQuietStretch() {
        if (!loggingSession) return
        val stretch = SystemClock.elapsedRealtime() - stretchStartedElapsed
        if (stretch > longestQuietMs) longestQuietMs = stretch
    }

    /**
     * @param reason `"timer"` (wake/duration) or `"manual"` (STOP button / notification).
     */
    private fun stopSession(reason: String) {
        if (shutdownJob?.isActive == true) {
            Log.i(TAG, "stopSession($reason) ignored — shutdown already in progress")
            return
        }
        val stopGen = ++lifecycleGeneration
        playGeneration++
        loadJob?.cancel()
        loadJob = null
        timerJob?.cancel()
        timerJob = null
        stirDetector?.stop()
        stirDetector = null
        noteProgress(player.currentProgress())
        closeQuietStretch()
        player.stopAndClear()

        val summary = takeNightSummaryOrNull()
        _state.value = BedtimeSessionState(
            active = false,
            statusMessage = if (reason == "timer") "Good morning" else "Stopped"
        )

        if (summary == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val app = applicationContext as? AvaBedtimeApp
        app?.nightLogRepository?.add(summary)
        postNightSummaryNotification(summary)
        Log.i(TAG, "Night summary ($reason):\n${summary.formatNotificationBody()}")

        val webhookUrl = latestSettings.discordWebhookUrl
        val title = "${latestSettings.possessiveName} night"
        val body = summary.formatNotificationBody()
        val needsDiscord = DiscordWebhookSender.isValidWebhookUrl(webhookUrl)

        // Hold CPU briefly so a 7am timer stop still finishes Discord before the process dies.
        // Manual STOP used to work because the UI kept the process alive; timer stop did not.
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvaBedtime:NightSummary")
            .apply {
                setReferenceCounted(false)
                acquire(45_000L)
            }

        startForeground(
            NOTIFICATION_ID,
            buildPlaybackNotification(
                if (reason == "timer") "Sending night summary…" else "Stopping…"
            )
        )
        lastNotificationContent = if (reason == "timer") "Sending night summary…" else "Stopping…"

        shutdownJob = scope.launch(Dispatchers.IO) {
            try {
                if (needsDiscord) {
                    val ok = DiscordWebhookSender.sendNightSummarySync(webhookUrl, title, body)
                    Log.i(TAG, "Discord night summary after $reason: ok=$ok")
                } else {
                    Log.i(TAG, "No Discord webhook configured — local summary only")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    runCatching {
                        if (wakeLock.isHeld) wakeLock.release()
                    }
                    // Skip teardown if START began a newer session while we were sending.
                    if (stopGen == lifecycleGeneration && !_state.value.active) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        Log.i(TAG, "Shutdown teardown skipped — newer session owns the service")
                    }
                }
            }
        }
    }

    private fun takeNightSummaryOrNull(): NightSummary? {
        if (!loggingSession) return null
        loggingSession = false
        return NightSummary(
            startedAtMs = sessionStartedAtMs,
            endedAtMs = System.currentTimeMillis(),
            micRestarts = micRestarts,
            motionRestarts = motionRestarts,
            manualRestarts = manualRestarts,
            farthestTrackIndex = farthestIndex,
            farthestTrackTitle = farthestTitle,
            trackCount = trackCount,
            farthestPositionMs = farthestPositionMs,
            longestQuietStretchMs = longestQuietMs
        )
    }

    private fun postNightSummaryNotification(summary: NightSummary) {
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = summary.formatNotificationBody()
        val childName = latestSettings.possessiveName
        val notification = NotificationCompat.Builder(this, AvaBedtimeApp.NIGHT_SUMMARY_CHANNEL_ID)
            .setContentTitle("$childName night")
            .setContentText(
                "Restarts ${summary.totalRestarts} · ${summary.formatFarthest()}"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(SUMMARY_NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e(TAG, "Could not post night summary notification", it)
        }
    }

    private fun buildPlaybackNotification(content: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restart = PendingIntent.getService(
            this,
            3,
            Intent(this, BedtimeService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BedtimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun playbackViews() = android.widget.RemoteViews(packageName, R.layout.notification_playback).apply {
            setTextViewText(R.id.notif_title, getString(R.string.notification_title))
            setTextViewText(R.id.notif_text, content)
            setOnClickPendingIntent(R.id.notif_btn_restart, restart)
            setOnClickPendingIntent(R.id.notif_btn_stop, stop)
        }
        // Lock screen: title-only public version (no buttons — kids shouldn't see transport).
        // Unlocked shade: big custom Restart / Stop.
        val lockSafe = NotificationCompat.Builder(this, AvaBedtimeApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        return NotificationCompat.Builder(this, AvaBedtimeApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockSafe)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCustomContentView(playbackViews())
            .setCustomBigContentView(playbackViews())
            .build()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        timerJob?.cancel()
        shutdownJob?.cancel()
        stirDetector?.stop()
        player.release()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BedtimeService"
        const val ACTION_START = "com.avas.bedtime.START"
        const val ACTION_STOP = "com.avas.bedtime.STOP"
        const val ACTION_RESTART = "com.avas.bedtime.RESTART"
        private const val NOTIFICATION_ID = 42
        private const val SUMMARY_NOTIFICATION_ID = 43

        @Volatile
        var instance: BedtimeService? = null
            private set

        private val _state = MutableStateFlow(BedtimeSessionState())
        val state: StateFlow<BedtimeSessionState> = _state.asStateFlow()

        @Volatile
        var latestSettings: BedtimeSettings = BedtimeSettings()

        /** Push sensitivity / mic-motion toggles into the live detector. */
        fun applyStirSettings(settings: BedtimeSettings) {
            latestSettings = settings
            instance?.stirDetector?.updateConfig(
                micSensitivity = settings.micSensitivity,
                motionSensitivity = settings.motionSensitivity,
                micEnabled = settings.micEnabled,
                motionEnabled = settings.motionEnabled,
                cooldownSeconds = settings.cooldownSeconds
            )
        }

        fun formatRemaining(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
        }
    }
}
