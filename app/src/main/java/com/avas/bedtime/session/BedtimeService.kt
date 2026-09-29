package com.avas.bedtime.session

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.avas.bedtime.AvaBedtimeApp
import com.avas.bedtime.MainActivity
import com.avas.bedtime.R
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.data.EndMode
import com.avas.bedtime.data.NightProgressStore
import com.avas.bedtime.data.NightSessionStore
import com.avas.bedtime.data.ScheduleTime
import com.avas.bedtime.detect.StirDetector
import com.avas.bedtime.detect.StirSource
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BedtimeSessionState(
    val active: Boolean = false,
    val trackTitle: String = "",
    val endsAtElapsedRealtime: Long = 0L,
    /** When tonight began (earlier if a paused night was resumed). */
    val nightStartedAtElapsedRealtime: Long = 0L,
    val lastStirSource: String? = null,
    val statusMessage: String = "Idle"
)

class BedtimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: PlaylistPlayer
    private lateinit var sessionStore: NightSessionStore
    private var stirDetector: StirDetector? = null
    private var timerJob: Job? = null
    private var loadJob: Job? = null
    private var shutdownJob: Job? = null
    private var endsAtElapsed = 0L
    private var lastNotificationContent: String? = null
    private var wifiLock: WifiManager.WifiLock? = null

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
    private var accumulatedRunMs = 0L
    private var runStartedElapsed = 0L
    private var nightDeadlineEpochMs = 0L
    private var earlyStops = 0
    private var lastStopSource = ""
    private lateinit var progressStore: NightProgressStore
    /** Notification Stop sits beside Restart, so it needs a second tap within this window. */
    private var stopArmedUntilElapsed = 0L
    private var disarmJob: Job? = null
    /** Bumped on START/STOP so cancelled Discord shutdown cannot stopSelf a new session. */
    private var lifecycleGeneration = 0
    /** Bumped when playback ownership changes so a late load cannot resume after STOP. */
    private var playGeneration = 0

    override fun onCreate() {
        super.onCreate()
        sessionStore = NightSessionStore(this)
        progressStore = NightProgressStore(this)
        player = PlaylistPlayer(this, scope) {
            _state.value.active
        }
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
                if (_state.value.active && endsAtElapsed > SystemClock.elapsedRealtime()) {
                    Log.i(TAG, "Start ignored — session already active; timer unchanged")
                    return START_STICKY
                }
                startSession(latestSettings)
            }
            ACTION_STOP -> stopSession(
                reason = "manual",
                source = intent.getStringExtra(EXTRA_STOP_SOURCE) ?: "unknown STOP"
            )
            ACTION_STOP_ARM -> armOrConfirmStop()
            ACTION_RESTART -> restartPlaylistOnly(sourceLabel = null)
            else -> {
                val snap = sessionStore.load()
                if (snap != null && snap.stillActive()) {
                    Log.i(TAG, "Restoring overnight session after process restart")
                    promoteForeground("Restoring bedtime…")
                    lastNotificationContent = "Restoring bedtime…"
                    val remainingMs = snap.endsAtEpochMs - System.currentTimeMillis()
                    endsAtElapsed = SystemClock.elapsedRealtime() + remainingMs.coerceAtLeast(60_000L)
                    scope.launch {
                        val app = applicationContext as? AvaBedtimeApp
                        val settings = if (app != null) {
                            app.settingsRepository.settings.first()
                        } else {
                            latestSettings
                        }.let { base ->
                            // Prefer shuffle flag captured when the night started.
                            base.copy(shufflePlaylist = snap.shufflePlaylist)
                        }
                        applyStirSettings(settings)
                        startSession(settings, restoreEndsAtElapsed = endsAtElapsed)
                    }
                } else {
                    Log.w(TAG, "onStartCommand with no action — promoting FGS then stopping")
                    sessionStore.clear()
                    promoteForeground("Stopped")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startSession(
        settings: BedtimeSettings,
        restoreEndsAtElapsed: Long? = null
    ) {
        lifecycleGeneration++
        shutdownJob?.cancel()
        shutdownJob = null
        endsAtElapsed = restoreEndsAtElapsed ?: when (settings.resolvedEndMode) {
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

        beginOrResumeNight(
            deadlineEpochMs = System.currentTimeMillis() +
                max(0L, endsAtElapsed - SystemClock.elapsedRealtime())
        )
        lastNotificationContent = null
        promoteForeground("Starting bedtime…")
        lastNotificationContent = "Starting bedtime…"
        acquireWifiLock()
        _state.value = BedtimeSessionState(
            active = true,
            endsAtElapsedRealtime = endsAtElapsed,
            nightStartedAtElapsedRealtime = SystemClock.elapsedRealtime() -
                (System.currentTimeMillis() - sessionStartedAtMs).coerceAtLeast(0L),
            statusMessage = "Getting your music ready…"
        )
        persistSession(settings)

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
                    withContext(Dispatchers.IO) {
                        // Ensure WAV exists off-main before ExoPlayer opens it.
                        com.avas.bedtime.player.OfflineDemoTone.ensureFile(this@BedtimeService)
                    }
                    player.playDemoToneLoop()
                }
            }
            if (!isActive || loadGen != playGeneration) return@launch
            beginMonitoring(settings)
        }
    }

    private fun persistSession(settings: BedtimeSettings) {
        val endsEpoch = System.currentTimeMillis() +
            max(0L, endsAtElapsed - SystemClock.elapsedRealtime())
        sessionStore.save(endsEpoch, settings.shufflePlaylist)
    }

    /**
     * Continue tonight's counters if bedtime was stopped (or the process was killed)
     * before wake time; otherwise start a fresh night.
     */
    private fun beginOrResumeNight(deadlineEpochMs: Long) {
        NightSummaryDispatcher.cancelFlush(this)
        val nowMs = System.currentTimeMillis()
        val pending = progressStore.take()
        val resume = when {
            pending == null -> false
            pending.deadlineEpochMs <= nowMs -> {
                val settings = latestSettings
                scope.launch(Dispatchers.IO) {
                    NightSummaryDispatcher.deliverSync(applicationContext, settings, pending)
                }
                false
            }
            pending.runMs < NightSummaryDispatcher.MIN_RUN_MS_TO_REPORT &&
                nowMs - pending.lastEndedAtMs > NightSummaryDispatcher.STALE_SHORT_GAP_MS -> false
            else -> true
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        loggingSession = true
        stretchStartedElapsed = nowElapsed
        runStartedElapsed = nowElapsed
        nightDeadlineEpochMs = deadlineEpochMs
        if (resume && pending != null) {
            Log.i(TAG, "Resuming tonight's summary counters")
            sessionStartedAtMs = pending.startedAtMs
            accumulatedRunMs = pending.runMs
            longestQuietMs = pending.longestQuietStretchMs
            micRestarts = pending.micRestarts
            motionRestarts = pending.motionRestarts
            manualRestarts = pending.manualRestarts
            farthestIndex = pending.farthestTrackIndex
            farthestTitle = pending.farthestTrackTitle
            farthestPositionMs = pending.farthestPositionMs
            trackCount = pending.trackCount
            earlyStops = pending.earlyStops
            lastStopSource = pending.lastStopSource
        } else {
            earlyStops = 0
            lastStopSource = ""
            sessionStartedAtMs = nowMs
            accumulatedRunMs = 0L
            longestQuietMs = 0L
            micRestarts = 0
            motionRestarts = 0
            manualRestarts = 0
            farthestIndex = -1
            farthestTitle = ""
            farthestPositionMs = 0L
            trackCount = 0
        }
        checkpointNight()
    }

    private fun currentNightProgress(): NightProgressStore.Progress {
        val nowElapsed = SystemClock.elapsedRealtime()
        return NightProgressStore.Progress(
            startedAtMs = sessionStartedAtMs,
            lastEndedAtMs = System.currentTimeMillis(),
            deadlineEpochMs = nightDeadlineEpochMs,
            runMs = accumulatedRunMs + (nowElapsed - runStartedElapsed),
            micRestarts = micRestarts,
            motionRestarts = motionRestarts,
            manualRestarts = manualRestarts,
            farthestTrackIndex = farthestIndex,
            farthestTrackTitle = farthestTitle,
            trackCount = trackCount,
            farthestPositionMs = farthestPositionMs,
            longestQuietStretchMs = max(longestQuietMs, nowElapsed - stretchStartedElapsed),
            earlyStops = earlyStops,
            lastStopSource = lastStopSource
        )
    }

    /** Persist so an OEM process kill mid-night doesn't lose the counters. */
    private fun checkpointNight() {
        if (!loggingSession) return
        progressStore.save(currentNightProgress())
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
                val ordered = orderTracks(tracks, settings.shufflePlaylist)
                withContext(Dispatchers.Main) {
                    if (loadGen != playGeneration) return@withContext false
                    player.setTracks(
                        api,
                        settings.serverUrl,
                        settings.pmsToken,
                        clientId,
                        ordered
                    )
                    true
                }
            }.onFailure {
                Log.e(TAG, "Plex playback failed", it)
            }.getOrDefault(false)
        }
    }

    /** Keep track #1 first (favorite); optionally shuffle the rest. */
    private fun orderTracks(
        tracks: List<PlexApi.Track>,
        shuffle: Boolean
    ): List<PlexApi.Track> {
        if (!shuffle || tracks.size <= 1) return tracks
        val first = tracks.first()
        val rest = tracks.drop(1).shuffled()
        return listOf(first) + rest
    }

    private fun beginMonitoring(settings: BedtimeSettings) {
        stirDetector?.stop()
        val detector = StirDetector(this, scope) { source ->
            onStir(source)
        }
        detector.updateConfig(
            micSensitivity = settings.micSensitivity,
            motionSensitivity = settings.motionSensitivity,
            micEnabled = settings.micEnabled && hasMicPermission(),
            motionEnabled = settings.motionEnabled,
            cooldownSeconds = settings.cooldownSeconds
        )
        detector.start()
        stirDetector = detector

        timerJob?.cancel()
        timerJob = scope.launch {
            var startingOverUntil = 0L
            var lastCheckpointElapsed = SystemClock.elapsedRealtime()
            while (isActive) {
                noteProgress(player.currentProgress())
                if (SystemClock.elapsedRealtime() - lastCheckpointElapsed >= 60_000L) {
                    lastCheckpointElapsed = SystemClock.elapsedRealtime()
                    checkpointNight()
                }
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
                if (notifContent != lastNotificationContent) {
                    lastNotificationContent = notifContent
                    promoteForeground(notifContent)
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
        scope.launch {
            restartPlaylistOnly(sourceLabel = source.name)
        }
    }

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
        val muteMs = max(14_000L, stirDetector?.remainingCooldownMs() ?: 0L)
        stirDetector?.ignoreAudioChange(muteMs)
        player.restartFromBeginning()
        stretchStartedElapsed = SystemClock.elapsedRealtime()
        checkpointNight()
        stopArmedUntilElapsed = 0L
        disarmJob?.cancel()
        _state.value = _state.value.copy(
            endsAtElapsedRealtime = timerEnd,
            lastStirSource = sourceLabel ?: _state.value.lastStirSource,
            statusMessage = "Starting over"
        )
        val left = formatRemaining(max(0L, timerEnd - SystemClock.elapsedRealtime()))
        val notifContent = "Starting over · $left left"
        lastNotificationContent = notifContent
        promoteForeground(notifContent)
        Log.i(TAG, "Playlist restarted (mute ${muteMs}ms); timer unchanged ($left left)")
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

    private fun stopSession(reason: String, source: String = reason) {
        if (shutdownJob?.isActive == true) {
            Log.i(TAG, "stopSession($reason) ignored — shutdown already in progress")
            return
        }
        Log.w(TAG, "stopSession reason=$reason source=$source active=${_state.value.active}")
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
        sessionStore.clear()
        releaseWifiLock()

        stopArmedUntilElapsed = 0L
        disarmJob?.cancel()
        if (loggingSession && reason != "timer" &&
            nightDeadlineEpochMs > System.currentTimeMillis() + 30_000L
        ) {
            earlyStops++
            lastStopSource = source
        }
        val progress = if (loggingSession) currentNightProgress() else null
        loggingSession = false
        _state.value = BedtimeSessionState(
            active = false,
            statusMessage = if (reason == "timer") "Good morning" else "Stopped"
        )

        if (progress == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val nightOver = reason == "timer" ||
            progress.deadlineEpochMs <= System.currentTimeMillis() + 30_000L
        if (!nightOver) {
            progressStore.save(progress)
            NightSummaryDispatcher.scheduleFlush(this, progress.deadlineEpochMs)
            Log.i(TAG, "Stopped before wake time — night paused; summary sends at wake time")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        progressStore.clear()
        NightSummaryDispatcher.cancelFlush(this)
        val settings = latestSettings
        Log.i(TAG, "Night over ($reason):\n${progress.toSummary().formatNotificationBody()}")

        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvaBedtime:NightSummary")
            .apply {
                setReferenceCounted(false)
                acquire(45_000L)
            }

        promoteForeground(
            if (reason == "timer") "Sending night summary…" else "Stopping…"
        )
        lastNotificationContent = if (reason == "timer") "Sending night summary…" else "Stopping…"

        shutdownJob = scope.launch(Dispatchers.IO) {
            try {
                NightSummaryDispatcher.deliverSync(applicationContext, settings, progress)
            } finally {
                withContext(Dispatchers.Main) {
                    runCatching {
                        if (wakeLock.isHeld) wakeLock.release()
                    }
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

    private fun armOrConfirmStop() {
        val now = SystemClock.elapsedRealtime()
        if (!_state.value.active || now < stopArmedUntilElapsed) {
            stopSession(reason = "manual", source = "notification Stop")
            return
        }
        stopArmedUntilElapsed = now + STOP_CONFIRM_WINDOW_MS
        lastNotificationContent?.let { promoteForeground(it) }
        disarmJob?.cancel()
        disarmJob = scope.launch {
            delay(STOP_CONFIRM_WINDOW_MS)
            if (stopArmedUntilElapsed != 0L && _state.value.active) {
                stopArmedUntilElapsed = 0L
                lastNotificationContent?.let { promoteForeground(it) }
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun foregroundTypes(): Int {
        val wantMic = latestSettings.micEnabled && hasMicPermission()
        return if (wantMic) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
    }

    private fun promoteForeground(content: String) {
        val notification = buildPlaybackNotification(content)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, foregroundTypes())
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "FGS start not allowed", e)
            } else {
                Log.e(TAG, "startForeground failed — retrying media-only", e)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                }
            }
        }
    }

    private fun acquireWifiLock() {
        releaseWifiLock()
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AvaBedtime:Stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            wifiLock?.takeIf { it.isHeld }?.release()
        }
        wifiLock = null
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
            Intent(this, BedtimeService::class.java).setAction(ACTION_STOP_ARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopArmed = SystemClock.elapsedRealtime() < stopArmedUntilElapsed
        fun playbackViews() = android.widget.RemoteViews(packageName, R.layout.notification_playback).apply {
            setTextViewText(R.id.notif_title, getString(R.string.notification_title))
            setTextViewText(R.id.notif_text, content)
            setTextViewText(
                R.id.notif_btn_stop,
                getString(
                    if (stopArmed) R.string.notification_action_stop_confirm
                    else R.string.notification_action_stop
                )
            )
            setOnClickPendingIntent(R.id.notif_btn_restart, restart)
            setOnClickPendingIntent(R.id.notif_btn_stop, stop)
        }
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
        releaseWifiLock()
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
        const val ACTION_STOP_ARM = "com.avas.bedtime.STOP_ARM"
        const val EXTRA_STOP_SOURCE = "stop_source"
        private const val STOP_CONFIRM_WINDOW_MS = 4_000L
        private const val NOTIFICATION_ID = 42
        @Volatile
        var instance: BedtimeService? = null
            private set

        private val _state = MutableStateFlow(BedtimeSessionState())
        val state: StateFlow<BedtimeSessionState> = _state.asStateFlow()

        @Volatile
        var latestSettings: BedtimeSettings = BedtimeSettings()

        /** Screen taps jolt the mattress and passerby sounds reach the mic — don't count them as stirs. */
        fun suppressStirs(durationMs: Long) {
            instance?.stirDetector?.ignoreAudioChange(durationMs)
        }

        fun applyStirSettings(settings: BedtimeSettings) {
            latestSettings = settings
            val svc = instance ?: return
            svc.stirDetector?.updateConfig(
                micSensitivity = settings.micSensitivity,
                motionSensitivity = settings.motionSensitivity,
                micEnabled = settings.micEnabled && svc.hasMicPermission(),
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
