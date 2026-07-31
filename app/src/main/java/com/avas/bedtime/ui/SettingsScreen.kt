package com.avas.bedtime.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avas.bedtime.AvaBedtimeApp
import com.avas.bedtime.data.AvaPhotoStore
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.data.SettingsRepository
import com.avas.bedtime.plex.PlexApi
import com.avas.bedtime.ui.theme.AppThemeId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: BedtimeSettings,
    repository: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AvaBedtimeApp
    val scope = rememberCoroutineScope()
    val signIn by app.plexSignIn.state.collectAsStateWithLifecycle()
    val nights by app.nightLogRepository.nights.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val appVersionLabel = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "?"} ($code)"
        }.getOrDefault("unknown")
    }

    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf<List<PlexApi.ServerInfo>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlexApi.PlaylistSummary>>(emptyList()) }
    var libraries by remember { mutableStateOf<List<PlexApi.LibrarySection>>(emptyList()) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                AvaPhotoStore.decodeUri(context, uri)
            }
            if (bitmap == null) {
                status = "Could not open photo"
            } else {
                cropSource = bitmap
            }
        }
    }

    fun api(clientId: String) = PlexApi(clientId.ifBlank { "ava-bedtime" })

    suspend fun loadPlaylistsForUrl(url: String, token: String, clientId: String) {
        val plex = api(clientId)
        playlists = plex.listAudioPlaylists(url, token).getOrElse { err ->
            status = "Playlists error: ${err.message}"
            emptyList()
        }
        libraries = plex.listAudioLibraries(url, token).getOrElse { emptyList() }
    }

    suspend fun refreshCatalog(current: BedtimeSettings) {
        if (!current.isPlexSignedIn) {
            servers = emptyList()
            playlists = emptyList()
            libraries = emptyList()
            return
        }
        val plex = api(current.clientId)
        val serverList = plex.listServers(current.plexToken).getOrElse {
            status = "Could not list servers: ${it.message}"
            emptyList()
        }
        servers = serverList

        var url = current.serverUrl
        var token = current.pmsToken

        if (url.isBlank() && serverList.isNotEmpty()) {
            status = "Finding a connection this tablet can reach…"
            val preferred = serverList.first()
            token = preferred.accessToken
            val reachable = plex.findReachableConnection(preferred, token)
            url = reachable.getOrNull()?.uri?.trimEnd('/').orEmpty()
            if (url.isBlank()) {
                status = reachable.exceptionOrNull()?.message
                    ?: "Pick a connection under the server below"
                playlists = emptyList()
                libraries = emptyList()
                repository.update {
                    it.copy(
                        serverName = preferred.name,
                        serverAccessToken = token
                    )
                }
                return
            }
            repository.update {
                it.copy(
                    serverUrl = url,
                    serverName = preferred.name,
                    serverAccessToken = token
                )
            }
        }

        if (url.isBlank()) {
            playlists = emptyList()
            libraries = emptyList()
            return
        }

        // If saved URL is dead, try rediscovering.
        val alive = plex.testConnection(url, token).isSuccess
        if (!alive) {
            status = "Saved address unreachable — trying others…"
            val match = serverList.firstOrNull {
                it.name == current.serverName
            } ?: serverList.firstOrNull()
            if (match != null) {
                val found = plex.findReachableConnection(match, match.accessToken)
                val conn = found.getOrNull()
                if (conn != null) {
                    url = conn.uri.trimEnd('/')
                    token = match.accessToken
                    repository.update {
                        it.copy(
                            serverUrl = url,
                            serverName = match.name,
                            serverAccessToken = token
                        )
                    }
                    status = "Connected via ${conn.label}"
                } else {
                    status = found.exceptionOrNull()?.message
                        ?: "No reachable connection — pick one below"
                    playlists = emptyList()
                    libraries = emptyList()
                    return
                }
            }
        }

        loadPlaylistsForUrl(url, token, current.clientId)
    }

    LaunchedEffect(settings.plexToken, settings.serverUrl, settings.clientId, signIn.signedIn) {
        if (settings.isPlexSignedIn) {
            busy = true
            status = "Connecting to Plex…"
            runCatching { refreshCatalog(settings) }
                .onFailure { status = it.message ?: "Could not load library" }
                .onSuccess {
                    if (playlists.isNotEmpty()) {
                        status = "Tap Ava bedtime (or another playlist) below"
                    } else if (status.startsWith("Playlists error") ||
                        status.contains("Could not reach") ||
                        status.contains("unreachable") ||
                        status.contains("Pick a connection")
                    ) {
                        // keep detailed status
                    } else if (libraries.isNotEmpty()) {
                        status = "Connected — pick a music library or create playlists in Plex"
                    } else if (settings.serverUrl.isNotBlank()) {
                        status = "Connected, but no playlists found yet"
                    }
                }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        app.plexSignIn.openBrowser.collect { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (app.plexSignIn.state.value.waiting) app.plexSignIn.checkNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1A2433), Color(0xFF243044)))
            )
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Settings", style = SettingsTextStyles.screenTitle)

        SectionTitle("Install on Ava's tablet")
        Text(
            "Share the APK, then install on her phone (allow installs from Files if asked).",
            style = SettingsTextStyles.hint
        )
        Button(
            onClick = { com.avas.bedtime.share.ApkShareHelper.shareInstalledApk(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share/update apk")
        }

        SectionTitle("Child's name")
        OutlinedTextField(
            value = settings.childName,
            onValueChange = { name ->
                scope.launch {
                    repository.update {
                        it.copy(childName = name.take(24))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = settingsFieldColors()
        )

        SectionTitle("${settings.possessiveName} photo")
        if (settings.hasAvaPhoto) {
            val bitmap = remember(settings.avaPhotoPath) {
                runCatching {
                    BitmapFactory.decodeFile(settings.avaPhotoPath)?.asImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = settings.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .align(Alignment.CenterHorizontally)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Select photo")
            }
            if (settings.hasAvaPhoto) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            AvaPhotoStore.clear(context)
                            repository.update { it.copy(avaPhotoPath = "") }
                            status = "Photo removed"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Remove")
                }
            }
        }

        SectionTitle("Last night")
        val lastNight = nights.firstOrNull()
        if (lastNight == null) {
            Text("No nights logged yet.", style = SettingsTextStyles.hint)
        } else {
            Text(lastNight.formatSettingsBlock(), style = SettingsTextStyles.body)
        }

        SectionTitle("Discord night summary")
        OutlinedTextField(
            value = settings.discordWebhookUrl,
            onValueChange = { url ->
                scope.launch {
                    repository.update { it.copy(discordWebhookUrl = url.trim()) }
                }
            },
            singleLine = true,
            placeholder = {
                Text("Webhook URL (optional)", color = Color(0xFF6B7A8F))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = settingsFieldColors()
        )
        if (settings.discordWebhookUrl.isNotBlank() &&
            !com.avas.bedtime.notify.DiscordWebhookSender.isValidWebhookUrl(settings.discordWebhookUrl)
        ) {
            Text(
                "Needs a Discord webhook URL",
                color = Color(0xFFE8A0A0),
                style = SettingsTextStyles.hint
            )
        }

        SectionTitle("Plex")
        if (settings.isPlexSignedIn) {
            Text(
                "Signed in as ${settings.plexUsername.ifBlank { "Plex user" }}",
                style = SettingsTextStyles.body
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        repository.update {
                            it.copy(
                                plexToken = "",
                                serverAccessToken = "",
                                plexUsername = "",
                                serverUrl = "",
                                serverName = "",
                                playlistId = "",
                                playlistTitle = ""
                            )
                        }
                        servers = emptyList()
                        playlists = emptyList()
                        libraries = emptyList()
                        status = "Signed out"
                    }
                }
            ) { Text("Sign out") }
        } else {
            Text(
                "Sign in, then pick a server and playlist below.",
                style = SettingsTextStyles.hint
            )
            if (signIn.pinCode != null) {
                Text("Code: ${signIn.pinCode}", style = SettingsTextStyles.body)
            }
            if (signIn.message.isNotBlank()) {
                Text(signIn.message, style = SettingsTextStyles.hint)
            }
            signIn.error?.let {
                Text(it, color = Color(0xFFE8A0A0), style = SettingsTextStyles.hint)
            }
            Button(
                enabled = !signIn.waiting,
                onClick = { app.plexSignIn.startSignIn() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign in with Plex") }
            if (signIn.waiting || signIn.authUrl != null) {
                OutlinedButton(
                    onClick = { app.plexSignIn.checkNow() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("I've signed in — Check again")
                }
                TextButton(onClick = { app.plexSignIn.openBrowserAgain() }) {
                    Text("Open Plex page again")
                }
            }
        }

        if (busy || signIn.waiting) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        }
        if (status.isNotBlank()) {
            Text(status, style = SettingsTextStyles.hint)
        }

        if (settings.isPlexSignedIn && servers.isNotEmpty()) {
            SettingsDropdown(
                label = "Server",
                selectedText = settings.serverName.ifBlank { "Choose server" },
                options = servers,
                optionLabel = { it.name },
                optionSupporting = { server ->
                    val activeConn = api(settings.clientId).rankedConnections(server)
                        .firstOrNull { it.uri.trimEnd('/') == settings.serverUrl }
                    when {
                        activeConn != null -> "Using ${activeConn.label}"
                        settings.serverName == server.name && settings.serverUrl.isNotBlank() ->
                            "Connected"
                        else -> null
                    }
                },
                onSelect = { server ->
                    scope.launch {
                        busy = true
                        status = "Connecting to ${server.name}…"
                        val plex = api(settings.clientId)
                        val found = plex.findReachableConnection(server, server.accessToken)
                        val conn = found.getOrNull()
                        if (conn != null) {
                            val url = conn.uri.trimEnd('/')
                            repository.update {
                                it.copy(
                                    serverUrl = url,
                                    serverName = server.name,
                                    serverAccessToken = server.accessToken,
                                    playlistId = "",
                                    playlistTitle = ""
                                )
                            }
                            loadPlaylistsForUrl(url, server.accessToken, settings.clientId)
                            status = "Using ${conn.label}"
                        } else {
                            status = found.exceptionOrNull()?.message
                                ?: "Could not reach ${server.name}"
                        }
                        busy = false
                    }
                }
            )
        }

        if (settings.isPlexSignedIn && settings.serverUrl.isNotBlank()) {
            val musicOptions = remember(playlists, libraries) {
                buildList {
                    playlists.forEach { add(MusicPick.Playlist(it)) }
                    libraries.forEach { add(MusicPick.Library(it)) }
                }
            }
            val selectedMusicLabel = when {
                settings.playlistId.startsWith("section:") -> {
                    val key = settings.playlistId.removePrefix("section:")
                    libraries.firstOrNull { it.key == key }?.title
                        ?: settings.playlistTitle.ifBlank { "Library" }
                }
                settings.playlistId.isNotBlank() -> {
                    playlists.firstOrNull { it.id == settings.playlistId }?.title
                        ?: settings.playlistTitle.ifBlank { "Playlist" }
                }
                else -> "Choose playlist or library"
            }
            SettingsDropdown(
                label = "Bedtime music",
                selectedText = selectedMusicLabel,
                options = musicOptions,
                optionLabel = { pick ->
                    when (pick) {
                        is MusicPick.Playlist -> pick.item.title
                        is MusicPick.Library -> pick.item.title
                    }
                },
                optionSupporting = { pick ->
                    when (pick) {
                        is MusicPick.Playlist -> "Playlist · ${pick.item.leafCount} items"
                        is MusicPick.Library -> "Whole library · first 300 tracks"
                    }
                },
                enabled = musicOptions.isNotEmpty(),
                onSelect = { pick ->
                    scope.launch {
                        when (pick) {
                            is MusicPick.Playlist -> {
                                repository.update {
                                    it.copy(
                                        playlistId = pick.item.id,
                                        playlistTitle = pick.item.title
                                    )
                                }
                                status = "Playlist: ${pick.item.title}"
                            }
                            is MusicPick.Library -> {
                                repository.update {
                                    it.copy(
                                        playlistId = "section:${pick.item.key}",
                                        playlistTitle = pick.item.title
                                    )
                                }
                                status = "Library: ${pick.item.title}"
                            }
                        }
                    }
                }
            )
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Refreshing…"
                        loadPlaylistsForUrl(
                            settings.serverUrl,
                            settings.pmsToken,
                            settings.clientId
                        )
                        status = if (playlists.isEmpty() && libraries.isEmpty()) {
                            "Nothing found — check Plex playlists"
                        } else {
                            "Updated"
                        }
                        busy = false
                    }
                }
            ) {
                Text("Refresh music list")
            }
            if (musicOptions.isEmpty()) {
                Text(
                    "No playlists or music libraries on this server yet.",
                    style = SettingsTextStyles.hint
                )
            }
        }

        SectionTitle("Look & theme")
        SettingsDropdown(
            label = "Theme",
            selectedText = AppThemeId.fromStorage(settings.themeId).label,
            options = AppThemeId.entries.toList(),
            optionLabel = { it.label },
            onSelect = { theme ->
                scope.launch {
                    repository.update { it.copy(themeId = theme.storageKey) }
                    status = "Theme: ${theme.label}"
                }
            }
        )

        SectionTitle("Bedtime & wake-up")
        SettingsDropdown(
            label = "Stop when",
            selectedText = when (settings.resolvedEndMode) {
                com.avas.bedtime.data.EndMode.WakeUp -> "Wake-up time (${settings.wakeLabel})"
                com.avas.bedtime.data.EndMode.Duration -> "After ${settings.timerHours} hours"
            },
            options = com.avas.bedtime.data.EndMode.entries.toList(),
            optionLabel = { mode ->
                when (mode) {
                    com.avas.bedtime.data.EndMode.WakeUp -> "Wake-up time"
                    com.avas.bedtime.data.EndMode.Duration -> "Set number of hours"
                }
            },
            onSelect = { mode ->
                scope.launch {
                    repository.update { it.copy(endMode = mode.storageKey) }
                }
            }
        )

        Text("Wake-up ${settings.wakeLabel}", style = SettingsTextStyles.body)
        TimeAdjustRow(
            hour = settings.wakeHour,
            minute = settings.wakeMinute,
            onHour = { hour ->
                scope.launch { repository.update { it.copy(wakeHour = hour) } }
            },
            onMinute = { minute ->
                scope.launch { repository.update { it.copy(wakeMinute = minute) } }
            }
        )

        Text("Bedtime ${settings.bedtimeLabel}", style = SettingsTextStyles.body)
        TimeAdjustRow(
            hour = settings.bedtimeHour,
            minute = settings.bedtimeMinute,
            onHour = { hour ->
                scope.launch { repository.update { it.copy(bedtimeHour = hour) } }
            },
            onMinute = { minute ->
                scope.launch { repository.update { it.copy(bedtimeMinute = minute) } }
            }
        )

        if (settings.resolvedEndMode == com.avas.bedtime.data.EndMode.Duration) {
            Text("${settings.timerHours} hours", style = SettingsTextStyles.body)
            Slider(
                value = settings.timerHours.toFloat(),
                onValueChange = { hours ->
                    scope.launch {
                        repository.update { it.copy(timerHours = hours.toInt().coerceIn(1, 12)) }
                    }
                },
                valueRange = 1f..12f,
                steps = 10
            )
        }

        SectionTitle("Stir detection")
        Text(
            "Mic: crying / sharp sounds (never saved). Motion: bed movement — keep tablet on the mattress edge.",
            style = SettingsTextStyles.hint
        )
        ToggleRow("Listen with microphone", settings.micEnabled) { enabled ->
            scope.launch { repository.update { it.copy(micEnabled = enabled) } }
        }
        if (settings.micEnabled) {
            Text("Mic sensitivity ${(settings.micSensitivity * 100).toInt()}%")
            Text(
                "Lower = only louder cries / sharp noises. 10% ignores most music and little sounds.",
                style = SettingsTextStyles.hint
            )
            Slider(
                value = settings.micSensitivity,
                onValueChange = { value ->
                    val rounded = (value * 100f).roundToInt().coerceIn(10, 100) / 100f
                    if (kotlin.math.abs(rounded - settings.micSensitivity) < 0.001f) return@Slider
                    scope.launch { repository.update { it.copy(micSensitivity = rounded) } }
                },
                valueRange = 0.1f..1f,
                steps = 17
            )
        }
        ToggleRow("Feel motion", settings.motionEnabled) { enabled ->
            scope.launch { repository.update { it.copy(motionEnabled = enabled) } }
        }
        if (settings.motionEnabled) {
            Text("Motion sensitivity ${(settings.motionSensitivity * 100).toInt()}%")
            Text(
                "Lower = only clearer bed movement. Keep the phone on the mattress edge.",
                style = SettingsTextStyles.hint
            )
            Slider(
                value = settings.motionSensitivity,
                onValueChange = { value ->
                    val rounded = (value * 100f).roundToInt().coerceIn(10, 100) / 100f
                    if (kotlin.math.abs(rounded - settings.motionSensitivity) < 0.001f) return@Slider
                    scope.launch { repository.update { it.copy(motionSensitivity = rounded) } }
                },
                valueRange = 0.1f..1f,
                steps = 17
            )
        }
        Text(
            "Restart cooldown ${settings.cooldownSeconds}s",
            style = SettingsTextStyles.body
        )
        Text(
            "After an auto-restart, wait this long before another stir can restart again.",
            style = SettingsTextStyles.hint
        )
        Slider(
            value = settings.cooldownSeconds.toFloat(),
            onValueChange = { seconds ->
                val rounded = seconds.roundToInt().coerceIn(10, 120)
                if (rounded == settings.cooldownSeconds) return@Slider
                scope.launch {
                    repository.update {
                        it.copy(cooldownSeconds = rounded)
                    }
                }
            },
            valueRange = 10f..120f,
            steps = 21
        )

            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
            Text(
                "App version $appVersionLabel",
                style = SettingsTextStyles.hint,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    cropSource?.let { bitmap ->
        CircularPhotoCropOverlay(
            source = bitmap,
            onCancel = { cropSource = null },
            onCropped = { cropped ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        AvaPhotoStore.saveBitmap(context, cropped)
                    }
                    cropSource = null
                    if (ok) {
                        val path = AvaPhotoStore.photoFile(context).absolutePath
                        repository.update { it.copy(avaPhotoPath = path) }
                        status = "Photo saved"
                    } else {
                        status = "Could not save photo"
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
        )
    }
    }
}

@Composable
private fun TimeAdjustRow(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(onClick = { onHour((hour + 23) % 24) }) { Text("-H") }
        OutlinedButton(onClick = { onHour((hour + 1) % 24) }) { Text("+H") }
        Text(
            com.avas.bedtime.data.ScheduleTime.formatClock(hour, minute),
            style = SettingsTextStyles.body,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = { onMinute((minute + 45) % 60) }) { Text("-M") }
        OutlinedButton(onClick = { onMinute((minute + 15) % 60) }) { Text("+M") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = SettingsTextStyles.section,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = SettingsTextStyles.body)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private sealed class MusicPick {
    data class Playlist(val item: PlexApi.PlaylistSummary) : MusicPick()
    data class Library(val item: PlexApi.LibrarySection) : MusicPick()
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color(0xFFF2E8D5),
    unfocusedTextColor = Color(0xFFF2E8D5),
    disabledTextColor = Color(0xFF9AA8BC),
    focusedBorderColor = Color(0xFFC4A574),
    unfocusedBorderColor = Color(0xFF6B7A8F),
    disabledBorderColor = Color(0xFF3A4555),
    cursorColor = Color(0xFFC4A574),
    focusedContainerColor = Color(0xFF243044),
    unfocusedContainerColor = Color(0xFF243044),
    disabledContainerColor = Color(0xFF1A2433),
    focusedLabelColor = Color(0xFFC4A574),
    unfocusedLabelColor = Color(0xFFD5CBB8),
    disabledLabelColor = Color(0xFF6B7A8F),
    focusedTrailingIconColor = Color(0xFFC4A574),
    unfocusedTrailingIconColor = Color(0xFFD5CBB8)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdown(
    label: String,
    selectedText: String,
    options: List<T>,
    optionLabel: (T) -> String,
    optionSupporting: ((T) -> String?)? = null,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = enabled),
            singleLine = true,
            colors = settingsFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            containerColor = Color(0xFF243044)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                optionLabel(option),
                                color = Color(0xFFF2E8D5),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val supporting = optionSupporting?.invoke(option)
                            if (!supporting.isNullOrBlank()) {
                                Text(
                                    supporting,
                                    color = Color(0xFFD5CBB8),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

private object SettingsTextStyles {
    val screenTitle = androidx.compose.ui.text.TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFFF2E8D5)
    )
    val section = androidx.compose.ui.text.TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFFF2E8D5)
    )
    val body = androidx.compose.ui.text.TextStyle(
        fontSize = 13.sp,
        color = Color(0xFFF2E8D5)
    )
    val hint = androidx.compose.ui.text.TextStyle(
        fontSize = 11.sp,
        color = Color(0xFFD5CBB8)
    )
}
