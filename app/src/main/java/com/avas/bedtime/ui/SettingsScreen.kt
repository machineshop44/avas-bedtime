package com.avas.bedtime.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avas.bedtime.AvaBedtimeApp
import com.avas.bedtime.data.AvaPhotoStore
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.data.SettingsRepository
import com.avas.bedtime.plex.PlexApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    cropSource?.let { bitmap ->
        CircularPhotoCropDialog(
            source = bitmap,
            onCancel = {
                cropSource = null
            },
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
            }
        )
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Settings", style = SettingsTextStyles.screenTitle)

        SectionTitle("Install on Ava's tablet")
        Text(
            "Share the APK over Nearby Share / Quick Share, Bluetooth, or Wi‑Fi. " +
                "On her phone, open AvaBedtime-update.apk and tap Install " +
                "(allow installs from Files if asked). If Quick Share greys out her phone, try Bluetooth.",
            style = SettingsTextStyles.hint
        )
        Button(
            onClick = { com.avas.bedtime.share.ApkShareHelper.shareInstalledApk(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share/update apk")
        }

        SectionTitle("Child's name")
        Text(
            "Shown big on the home screen as ${settings.possessiveName}.",
            style = SettingsTextStyles.hint
        )
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
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFF2E8D5),
                unfocusedTextColor = Color(0xFFF2E8D5),
                focusedBorderColor = Color(0xFFC4A574),
                unfocusedBorderColor = Color(0xFF6B7A8F),
                cursorColor = Color(0xFFC4A574),
                focusedContainerColor = Color(0xFF243044),
                unfocusedContainerColor = Color(0xFF243044)
            )
        )

        SectionTitle("${settings.possessiveName} photo")
        Text(
            "Shows large on the home screen. After you pick a picture you can crop it to a circle.",
            style = SettingsTextStyles.hint
        )
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
                        .size(140.dp)
                        .clip(CircleShape)
                )
            }
        }
        Button(
            onClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (settings.hasAvaPhoto) "Change / crop photo" else "Choose / crop photo")
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Remove photo")
            }
        }

        SectionTitle("Last night")
        val lastNight = nights.firstOrNull()
        if (lastNight == null) {
            Text(
                "No nights logged yet — start bedtime once.",
                style = SettingsTextStyles.hint
            )
        } else {
            Text(lastNight.formatSettingsBlock(), style = SettingsTextStyles.body)
            if (nights.size > 1) {
                Text(
                    "${nights.size} nights saved (newest first).",
                    style = SettingsTextStyles.hint
                )
            }
        }

        SectionTitle("Plex account")
        if (settings.isPlexSignedIn) {
            Text(
                "Signed in as ${settings.plexUsername.ifBlank { "Plex user" }}",
                style = SettingsTextStyles.body
            )
            if (settings.playlistTitle.isNotBlank()) {
                Text(
                    "Bedtime playlist: ${settings.playlistTitle}",
                    style = SettingsTextStyles.body,
                    color = Color(0xFFC4A574)
                )
            }
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
                "1) Tap Sign in with Plex\n2) Log in on the Plex page\n3) Come back here (or tap Check again)",
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
                onClick = { app.plexSignIn.startSignIn() }
            ) { Text("Sign in with Plex") }
            if (signIn.waiting || signIn.authUrl != null) {
                OutlinedButton(onClick = { app.plexSignIn.checkNow() }) {
                    Text("I've signed in — Check again")
                }
                OutlinedButton(onClick = { app.plexSignIn.openBrowserAgain() }) {
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
            SectionTitle("1. Server")
            Text(
                "Tap a server — the app will try home Wi‑Fi first, then remote automatically.",
                style = SettingsTextStyles.hint
            )
            servers.forEach { server ->
                val selected = settings.serverName == server.name ||
                    api(settings.clientId).rankedConnections(server)
                        .any { it.uri.trimEnd('/') == settings.serverUrl }
                val activeConn = api(settings.clientId).rankedConnections(server)
                    .firstOrNull { it.uri.trimEnd('/') == settings.serverUrl }
                SelectRow(
                    title = server.name,
                    subtitle = when {
                        selected && activeConn != null -> "Using ${activeConn.label}"
                        selected && settings.serverUrl.isNotBlank() -> "Connected"
                        else -> "Tap to connect"
                    },
                    selected = selected,
                    onClick = {
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
                                status = "Using ${conn.label} · ${playlists.size} playlists"
                            } else {
                                status = found.exceptionOrNull()?.message
                                    ?: "Could not reach ${server.name}"
                            }
                            busy = false
                        }
                    }
                )
            }
        }

        if (settings.isPlexSignedIn && settings.serverUrl.isNotBlank()) {
            SectionTitle("2. Music / audiobook libraries")
            if (libraries.isEmpty()) {
                Text(
                    "No music libraries found on this connection yet.",
                    style = SettingsTextStyles.hint
                )
            }
            libraries.forEach { library ->
                SelectRow(
                    title = library.title,
                    subtitle = "Whole library (first 300 tracks)",
                    selected = settings.playlistId == "section:${library.key}",
                    onClick = {
                        scope.launch {
                            repository.update {
                                it.copy(
                                    playlistId = "section:${library.key}",
                                    playlistTitle = library.title
                                )
                            }
                            status = "Bedtime library set: ${library.title}"
                        }
                    }
                )
            }

            SectionTitle("3. Playlists")
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Refreshing playlists…"
                        loadPlaylistsForUrl(
                            settings.serverUrl,
                            settings.pmsToken,
                            settings.clientId
                        )
                        status = if (playlists.isEmpty()) {
                            "No playlists found"
                        } else {
                            "Loaded ${playlists.size} playlists"
                        }
                        busy = false
                    }
                }
            ) { Text("Refresh playlists") }

            if (playlists.isEmpty()) {
                Text(
                    "Looking for “Ava bedtime”. Tap your server above if this is empty.",
                    style = SettingsTextStyles.hint
                )
            }
            playlists.forEach { playlist ->
                val isAva = playlist.title.contains("Ava", ignoreCase = true)
                SelectRow(
                    title = playlist.title,
                    subtitle = "${playlist.leafCount} items",
                    selected = settings.playlistId == playlist.id,
                    emphasize = isAva,
                    onClick = {
                        scope.launch {
                            repository.update {
                                it.copy(
                                    playlistId = playlist.id,
                                    playlistTitle = playlist.title
                                )
                            }
                            status = "Bedtime playlist set: ${playlist.title}"
                        }
                    }
                )
            }
        }

        SectionTitle("Look & theme")
        Text(
            "Pick a look Ava likes. Unicorn is the default.",
            style = SettingsTextStyles.hint
        )
        com.avas.bedtime.ui.theme.AppThemeId.entries.forEach { theme ->
            SelectRow(
                title = theme.label,
                subtitle = theme.storageKey,
                selected = settings.themeId == theme.storageKey,
                emphasize = theme == com.avas.bedtime.ui.theme.AppThemeId.Unicorn,
                onClick = {
                    scope.launch {
                        repository.update { it.copy(themeId = theme.storageKey) }
                        status = "Theme: ${theme.label}"
                    }
                }
            )
        }

        SectionTitle("Bedtime & wake-up")
        Text(
            "Wake-up stops playback at a clock time (best when you start at different hours). " +
                "Bedtime is for your reference / later scheduling.",
            style = SettingsTextStyles.hint
        )
        SelectRow(
            title = "Stop at wake-up time",
            subtitle = "Ends at ${settings.wakeLabel}",
            selected = settings.resolvedEndMode == com.avas.bedtime.data.EndMode.WakeUp,
            onClick = {
                scope.launch {
                    repository.update {
                        it.copy(endMode = com.avas.bedtime.data.EndMode.WakeUp.storageKey)
                    }
                }
            }
        )
        SelectRow(
            title = "Stop after a set number of hours",
            subtitle = "${settings.timerHours} hours from Start",
            selected = settings.resolvedEndMode == com.avas.bedtime.data.EndMode.Duration,
            onClick = {
                scope.launch {
                    repository.update {
                        it.copy(endMode = com.avas.bedtime.data.EndMode.Duration.storageKey)
                    }
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
            "For bed movement: keep the tablet on the mattress edge or bed frame (not a solid nightstand). " +
                "Mic listens for whining/crying and sharp sounds like a metal bottle. " +
                "Audio is never saved.",
            style = SettingsTextStyles.hint
        )
        ToggleRow("Listen with microphone", settings.micEnabled) { enabled ->
            scope.launch { repository.update { it.copy(micEnabled = enabled) } }
        }
        if (settings.micEnabled) {
            Text("Mic sensitivity ${(settings.micSensitivity * 100).toInt()}%")
            Slider(
                value = settings.micSensitivity,
                onValueChange = { value ->
                    scope.launch { repository.update { it.copy(micSensitivity = value) } }
                },
                valueRange = 0.1f..1f
            )
        }
        ToggleRow("Feel motion", settings.motionEnabled) { enabled ->
            scope.launch { repository.update { it.copy(motionEnabled = enabled) } }
        }
        if (settings.motionEnabled) {
            Text("Motion sensitivity ${(settings.motionSensitivity * 100).toInt()}%")
            Slider(
                value = settings.motionSensitivity,
                onValueChange = { value ->
                    scope.launch { repository.update { it.copy(motionSensitivity = value) } }
                },
                valueRange = 0.1f..1f
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
                scope.launch {
                    repository.update {
                        it.copy(cooldownSeconds = seconds.toInt().coerceIn(10, 120))
                    }
                }
            },
            valueRange = 10f..120f,
            steps = 21
        )

            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text("Done")
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
        modifier = Modifier.padding(top = 6.dp)
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

@Composable
private fun SelectRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    emphasize: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        selected -> Color(0xFF3D7A7A)
        emphasize -> Color(0xFF3A4A2E)
        else -> Color(0xFF243044)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(title, style = SettingsTextStyles.body)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = SettingsTextStyles.hint)
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
