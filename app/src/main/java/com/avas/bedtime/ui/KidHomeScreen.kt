package com.avas.bedtime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.session.BedtimeService
import com.avas.bedtime.ui.theme.AppThemeId
import com.avas.bedtime.ui.theme.BedtimeThemeColors
import com.avas.bedtime.ui.theme.dusky
import com.avas.bedtime.ui.theme.themeColors
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

private const val HOLD_TO_STOP_MS = 1_500
private const val CALM_AFTER_MS = 60_000L
private const val CALM_BRIGHTNESS = 0.02f

private data class HomeMetrics(
    val photoSize: Dp,
    val buttonSize: Dp,
    val stopMinWidth: Dp,
    val stopHeight: Dp,
    val nameSp: Float,
    val themeSp: Float,
    val statusSp: Float,
    val timerSp: Float,
    val buttonLabelSp: Float,
    val stopLabelSp: Float,
    val topPad: Dp,
    val gapAfterPhoto: Dp,
    val gapBeforeButton: Dp,
    val contentTopBias: Dp,
    /** Tall phones: vertically center START / RESTART in leftover space. */
    val centerActions: Boolean
)

@Composable
private fun rememberHomeMetrics(
    maxHeight: Dp,
    maxWidth: Dp,
    sessionActive: Boolean
): HomeMetrics {
    val tabletish = maxWidth >= 600.dp || maxHeight >= 900.dp
    val phone = !tabletish && maxWidth < 600.dp
    val tallPhone = phone && maxHeight >= 700.dp
    // Portrait tablets (and tall phones) need the START block centered — otherwise
    // everything piles into the top third with a huge empty lower half.
    val tallPortrait = maxHeight > maxWidth * 1.15f
    return when {
        phone && maxHeight < 700.dp -> HomeMetrics(
            photoSize = if (sessionActive) 72.dp else 88.dp,
            buttonSize = if (sessionActive) 132.dp else 148.dp,
            stopMinWidth = 176.dp,
            stopHeight = 52.dp,
            nameSp = if (sessionActive) 30f else 34f,
            themeSp = 16f,
            statusSp = 16f,
            timerSp = if (sessionActive) 26f else 28f,
            buttonLabelSp = if (sessionActive) 26f else 32f,
            stopLabelSp = 20f,
            topPad = 4.dp,
            gapAfterPhoto = 4.dp,
            gapBeforeButton = 10.dp,
            contentTopBias = 0.dp,
            centerActions = true
        )
        tallPhone -> HomeMetrics(
            photoSize = if (sessionActive) 100.dp else 120.dp,
            buttonSize = if (sessionActive) 168.dp else 188.dp,
            stopMinWidth = 200.dp,
            stopHeight = 58.dp,
            nameSp = 38f,
            themeSp = 20f,
            statusSp = 18f,
            timerSp = 32f,
            buttonLabelSp = if (sessionActive) 30f else 38f,
            stopLabelSp = 22f,
            topPad = 8.dp,
            gapAfterPhoto = 8.dp,
            gapBeforeButton = 12.dp,
            contentTopBias = 0.dp,
            centerActions = true
        )
        tabletish -> HomeMetrics(
            photoSize = 168.dp,
            buttonSize = 220.dp,
            stopMinWidth = 240.dp,
            stopHeight = 68.dp,
            nameSp = 48f,
            themeSp = 26f,
            statusSp = 24f,
            timerSp = 36f,
            buttonLabelSp = 44f,
            stopLabelSp = 26f,
            topPad = if (tallPortrait) 24.dp else 40.dp,
            gapAfterPhoto = 16.dp,
            gapBeforeButton = 16.dp,
            contentTopBias = if (tallPortrait) 0.dp else 72.dp,
            centerActions = tallPortrait
        )
        else -> HomeMetrics(
            photoSize = 148.dp,
            buttonSize = if (sessionActive) 184.dp else 200.dp,
            stopMinWidth = 216.dp,
            stopHeight = 62.dp,
            nameSp = 44f,
            themeSp = 22f,
            statusSp = 20f,
            timerSp = 32f,
            buttonLabelSp = if (sessionActive) 34f else 40f,
            stopLabelSp = 24f,
            topPad = 16.dp,
            gapAfterPhoto = 10.dp,
            gapBeforeButton = 12.dp,
            contentTopBias = if (sessionActive) 12.dp else 36.dp,
            centerActions = tallPortrait
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KidHomeScreen(
    settings: BedtimeSettings,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val session by BedtimeService.state.collectAsStateWithLifecycle()
    val baseColors = themeColors(AppThemeId.fromStorage(settings.themeId))
    val colors = if (session.active && settings.dimThemeAtNight) baseColors.dusky() else baseColors

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startBedtime(context)
        }
    }

    fun neededPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (settings.micEnabled) {
            perms += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    fun hasPermissions(): Boolean =
        neededPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        } || neededPermissions().isEmpty()

    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(session.active) {
        while (session.active) {
            tick = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val remaining = if (session.active) {
        max(0L, session.endsAtElapsedRealtime - SystemClock.elapsedRealtime().coerceAtLeast(tick))
    } else {
        0L
    }

    val ready = settings.hasBedtimePlaylist
    val uiScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val restartRing = remember { Animatable(0f) }
    val holdProgress = remember { Animatable(0f) }
    var showHoldHint by remember { mutableStateOf(false) }
    LaunchedEffect(showHoldHint) {
        if (showHoldHint) {
            delay(2_500)
            showHoldHint = false
        }
    }

    // Calm mode: after a quiet minute the screen dims and goes still; any tap wakes it.
    var lastTouchElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.active) { lastTouchElapsed = SystemClock.elapsedRealtime() }
    val sessionActiveNow by rememberUpdatedState(session.active)
    val calm = session.active &&
        SystemClock.elapsedRealtime().coerceAtLeast(tick) - lastTouchElapsed > CALM_AFTER_MS
    val activity = context as? android.app.Activity
    DisposableEffect(calm, activity) {
        val window = activity?.window
        window?.attributes = window?.attributes?.apply {
            screenBrightness = if (calm) {
                CALM_BRIGHTNESS
            } else {
                android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    val pulse by animateFloatAsState(
        targetValue = if (session.active) 1.03f else 1f,
        animationSpec = tween(900),
        label = "pulse"
    )
    // Only breathe START while idle; nothing should keep animating overnight.
    val sparkleScale = if (!session.active && ready) rememberSparkleScale() else 1f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            lastTouchElapsed = SystemClock.elapsedRealtime()
                            if (sessionActiveNow) BedtimeService.suppressStirs(4_000L)
                        }
                    }
                }
            }
    ) {
        SoftAtmosphere(colors = colors, calm = calm)
        val metrics = rememberHomeMetrics(maxHeight, maxWidth, sessionActive = session.active)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = metrics.topPad, start = 16.dp, end = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (settings.hasAvaPhoto) {
                        val bitmap = remember(settings.avaPhotoPath) {
                            runCatching {
                                com.avas.bedtime.data.AvaPhotoStore
                                    .decodeFileForDisplay(settings.avaPhotoPath, maxEdge = 512)
                                    ?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (bitmap != null) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(metrics.photoSize + if (session.active) 20.dp else 36.dp)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(
                                                    colors.subtitle.copy(alpha = 0.28f),
                                                    Color.Transparent
                                                )
                                            ),
                                            shape = CircleShape
                                        )
                                )
                                Box(
                                    modifier = Modifier
                                        .shadow(
                                            elevation = 28.dp,
                                            shape = CircleShape,
                                            ambientColor = colors.shadowTint,
                                            spotColor = colors.shadowTint,
                                            clip = false
                                        )
                                        .size(metrics.photoSize + 12.dp)
                                        .clip(CircleShape)
                                        .background(colors.photoRing)
                                        .padding(5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = settings.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                    )
                                }
                            }
                            Spacer(Modifier.height(metrics.gapAfterPhoto))
                        }
                    }
                    Text(
                        text = settings.possessiveName,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = metrics.nameSp.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = colors.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when (colors.id) {
                            AppThemeId.Unicorn -> "Unicorn Bedtime"
                            AppThemeId.Rainbow -> "Rainbow Bedtime"
                            AppThemeId.Ocean -> "Ocean Bedtime"
                            AppThemeId.Forest -> "Forest Bedtime"
                            AppThemeId.Galaxy -> "Galaxy Bedtime"
                            AppThemeId.Night -> "Night Bedtime"
                        },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = metrics.themeSp.sp,
                            letterSpacing = 0.3.sp
                        ),
                        color = colors.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .semantics { contentDescription = "Settings" }
                        .shadow(
                            elevation = 8.dp,
                            shape = CircleShape,
                            ambientColor = colors.shadowTint,
                            spotColor = colors.shadowTint,
                            clip = false
                        )
                        .clip(CircleShape)
                        .background(colors.settingsBar)
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        tint = colors.settingsText,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .then(
                        if (metrics.centerActions) {
                            Modifier
                        } else {
                            Modifier.verticalScroll(rememberScrollState())
                        }
                    )
                    .padding(horizontal = 24.dp)
                    .padding(
                        top = if (metrics.centerActions) 8.dp else metrics.contentTopBias,
                        bottom = 24.dp
                    ),
                verticalArrangement = if (metrics.centerActions) {
                    Arrangement.Center
                } else {
                    Arrangement.Top
                },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val startingOver = session.active && session.statusMessage == "Starting over"
                // Fades in once whenever the icon changes; never loops.
                val statusIconAlpha = remember(startingOver) { Animatable(0f) }
                LaunchedEffect(startingOver) { statusIconAlpha.animateTo(1f, tween(600)) }
                if (settings.buttonIcons && session.active) {
                    KidIconView(
                        icon = if (startingOver) KidIcon.Restart else KidIcon.Note,
                        color = colors.subtitle.copy(alpha = 0.85f * statusIconAlpha.value),
                        size = (metrics.statusSp * 2.2f).dp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = when {
                        startingOver ->
                            "Heard a stir — starting over"
                        session.active -> "Sleepy music is on"
                        !ready -> "Grown-ups: tap the gear to pick music"
                        else -> settings.playlistTitle.ifBlank { "Ready for bed" }
                    },
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = metrics.statusSp.sp
                    ),
                    color = colors.body,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                if (!session.active && ready) {
                    Text(
                        text = when (settings.resolvedEndMode) {
                            com.avas.bedtime.data.EndMode.WakeUp ->
                                "Stops at ${settings.wakeLabel}"
                            com.avas.bedtime.data.EndMode.Duration ->
                                "Plays ${settings.timerHours} hours"
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = colors.subtitle,
                        modifier = Modifier.padding(bottom = metrics.gapBeforeButton)
                    )
                } else {
                    Spacer(Modifier.height(metrics.gapBeforeButton))
                }
                if (session.active && settings.nightProgressArc) {
                    val nowElapsed = SystemClock.elapsedRealtime().coerceAtLeast(tick)
                    val nightLength = session.endsAtElapsedRealtime - session.nightStartedAtElapsedRealtime
                    val nightProgress = if (nightLength > 0L && session.nightStartedAtElapsedRealtime > 0L) {
                        (nowElapsed - session.nightStartedAtElapsedRealtime).toFloat() / nightLength
                    } else {
                        0f
                    }
                    NightPathArc(
                        progress = nightProgress,
                        colors = colors,
                        widthFraction = 0.72f,
                        modifier = Modifier.widthIn(max = metrics.buttonSize * 1.5f)
                    )
                    Text(
                        text = BedtimeService.formatRemaining(remaining),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = colors.subtitle,
                        modifier = Modifier.padding(bottom = metrics.gapBeforeButton)
                    )
                } else if (session.active) {
                    Text(
                        text = BedtimeService.formatRemaining(remaining),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = metrics.timerSp.sp
                        ),
                        color = colors.title,
                        modifier = Modifier.padding(bottom = metrics.gapBeforeButton)
                    )
                }

                if (session.active) {
                    Box(contentAlignment = Alignment.Center) {
                        val ring = restartRing.value
                        if (ring > 0f) {
                            val ringColor = colors.startButton
                            Canvas(modifier = Modifier.size(metrics.buttonSize)) {
                                drawCircle(
                                    color = ringColor.copy(alpha = 0.55f * (1f - ring)),
                                    radius = size.minDimension / 2f * (1f + 0.45f * ring),
                                    style = Stroke(width = 6.dp.toPx() * (1f - ring) + 1f)
                                )
                            }
                        }
                        BigRoundButton(
                            label = "RESTART",
                            icon = if (settings.buttonIcons) KidIcon.Restart else null,
                            color = colors.startButton,
                            textColor = colors.buttonText,
                            shadowTint = colors.shadowTint,
                            scale = pulse,
                            size = metrics.buttonSize,
                            labelSp = metrics.buttonLabelSp,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                uiScope.launch {
                                    restartRing.snapTo(0f)
                                    restartRing.animateTo(1f, tween(900))
                                    restartRing.snapTo(0f)
                                }
                                val intent = Intent(context, BedtimeService::class.java)
                                    .setAction(BedtimeService.ACTION_RESTART)
                                context.startService(intent)
                            }
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    GlossyPillButton(
                        label = if (showHoldHint) "HOLD TO STOP" else "STOP",
                        icon = if (settings.buttonIcons) KidIcon.Stop else null,
                        color = colors.stopButton,
                        textColor = colors.buttonText,
                        shadowTint = colors.shadowTint,
                        minWidth = metrics.stopMinWidth,
                        height = metrics.stopHeight,
                        labelSp = if (showHoldHint) metrics.stopLabelSp * 0.8f else metrics.stopLabelSp,
                        fillFraction = holdProgress.value,
                        gestureModifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { press ->
                                    if (!insideStadium(press, size.width.toFloat(), size.height.toFloat())) {
                                        return@detectTapGestures
                                    }
                                    val hold = uiScope.launch {
                                        holdProgress.animateTo(
                                            1f,
                                            tween(durationMillis = HOLD_TO_STOP_MS, easing = LinearEasing)
                                        )
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val intent = Intent(context, BedtimeService::class.java)
                                            .setAction(BedtimeService.ACTION_STOP)
                                            .putExtra(BedtimeService.EXTRA_STOP_SOURCE, "app STOP (held)")
                                        context.startService(intent)
                                        holdProgress.snapTo(0f)
                                    }
                                    tryAwaitRelease()
                                    if (hold.isActive) {
                                        hold.cancel()
                                        showHoldHint = true
                                        uiScope.launch { holdProgress.animateTo(0f, tween(200)) }
                                    }
                                }
                            )
                        }
                    )
                } else {
                    BigRoundButton(
                        label = "START",
                        icon = if (settings.buttonIcons) KidIcon.Moon else null,
                        color = if (ready) colors.startButton else colors.startButtonDisabled,
                        textColor = colors.buttonText,
                        shadowTint = colors.shadowTint,
                        scale = if (ready) sparkleScale else 1f,
                        size = metrics.buttonSize,
                        labelSp = metrics.buttonLabelSp,
                        onClick = {
                            if (!ready) {
                                onOpenSettings()
                                return@BigRoundButton
                            }
                            if (!hasPermissions()) {
                                permissionLauncher.launch(neededPermissions())
                                return@BigRoundButton
                            }
                            startBedtime(context)
                        }
                    )
                }
            }
        }
        // Draw above home UI so the unicorn isn't hidden under START/STOP.
        ThemePasserby(
            colors = colors,
            avaPhotoPath = settings.avaPhotoPath,
            calm = calm
        )
        if (calm) {
            // Swallows the waking tap so it can't land on RESTART / STOP underneath.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { contentDescription = "Tap to wake the screen" }
            )
        }
    }
}

@Composable
private fun rememberSparkleScale(): Float {
    val sparkle = rememberInfiniteTransition(label = "sparkle")
    val scale by sparkle.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "sparkleScale"
    )
    return scale
}

@Composable
private fun SoftAtmosphere(colors: BedtimeThemeColors, calm: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        SoftOrb(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-40).dp, y = 40.dp)
                .size(180.dp),
            color = colors.subtitle.copy(alpha = 0.22f)
        )
        SoftOrb(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 50.dp, y = 90.dp)
                .size(220.dp),
            color = colors.accentChip.copy(alpha = 0.55f)
        )
        SoftOrb(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-60).dp, y = 40.dp)
                .size(200.dp),
            color = Color.White.copy(alpha = 0.18f)
        )
        SoftOrb(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 40.dp, y = (-120).dp)
                .size(240.dp),
            color = colors.subtitle.copy(alpha = 0.18f)
        )
    }
    // Hidden under the calm scrim anyway; skipping it stops the per-frame redraws.
    if (!calm) TwinklingStars()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1f to colors.shadowTint.copy(alpha = 0.22f)
                )
            )
    )
}

@Composable
private fun TwinklingStars() {
    val twinkle = rememberInfiniteTransition(label = "twinkle")
    val phase by twinkle.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7000), RepeatMode.Restart),
        label = "phase"
    )
    val sparkles = remember {
        List(28) { i ->
            val rng = Random(i * 97 + 13)
            Sparkle(
                xFrac = rng.nextFloat(),
                yFrac = rng.nextFloat() * 0.72f,
                radius = 1.5f + rng.nextFloat() * 2.8f,
                speed = 0.6f + rng.nextFloat() * 1.4f,
                offset = rng.nextFloat() * 6f
            )
        }
    }
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        sparkles.forEach { s ->
            val alpha = (0.15f + 0.55f * ((sin((phase * s.speed) + s.offset) + 1f) / 2f))
                .coerceIn(0.08f, 0.75f)
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = with(density) { s.radius.dp.toPx() },
                center = Offset(size.width * s.xFrac, size.height * s.yFrac)
            )
        }
    }
}

@Composable
private fun SoftOrb(modifier: Modifier, color: Color) {
    Box(
        modifier = modifier.background(
            Brush.radialGradient(listOf(color, Color.Transparent)),
            shape = CircleShape
        )
    )
}

private data class Sparkle(
    val xFrac: Float,
    val yFrac: Float,
    val radius: Float,
    val speed: Float,
    val offset: Float
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigRoundButton(
    label: String,
    icon: KidIcon? = null,
    color: Color,
    textColor: Color,
    shadowTint: Color,
    scale: Float,
    size: Dp,
    labelSp: Float,
    onClick: () -> Unit
) {
    val highlight = Color.White.copy(alpha = 0.42f)
    val mid = color
    val deep = Color(
        red = (color.red * 0.78f).coerceIn(0f, 1f),
        green = (color.green * 0.78f).coerceIn(0f, 1f),
        blue = (color.blue * 0.78f).coerceIn(0f, 1f),
        alpha = color.alpha
    )
    val rimLight = Color.White.copy(alpha = 0.38f)
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier = Modifier
            .scale(scale)
            .shadow(
                elevation = 28.dp,
                shape = CircleShape,
                ambientColor = shadowTint,
                spotColor = shadowTint,
                clip = false
            )
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.28f).compositeOver(mid),
                    0.38f to mid,
                    1f to deep
                )
            )
            .border(width = 2.5.dp, color = rimLight, shape = CircleShape)
            .semantics {
                contentDescription = label
                onClick(label = label) { currentOnClick(); true }
            }
            // clip() doesn't shrink the touch area, so ignore taps in the square's corners.
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val r = this.size.width / 2f
                    val dx = tap.x - r
                    val dy = tap.y - this.size.height / 2f
                    if (dx * dx + dy * dy <= r * r) currentOnClick()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            // Soft top gloss.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(highlight, Color.Transparent),
                    center = Offset(this.size.width * 0.5f, this.size.height * 0.28f),
                    radius = r * 0.72f
                )
            )
            // Bottom shade for roundness.
            drawCircle(
                brush = Brush.radialGradient(
                    0.58f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.16f),
                    center = Offset(this.size.width * 0.5f, this.size.height * 0.55f),
                    radius = r
                )
            )
            // Inner rim ring.
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = r - 5.dp.toPx(),
                style = Stroke(width = 2.5.dp.toPx())
            )
            // Tiny highlight crescent near top.
            drawArc(
                color = Color.White.copy(alpha = 0.35f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(r * 0.28f, r * 0.16f),
                size = Size(r * 1.44f, r * 0.9f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                KidIconView(icon = icon, color = textColor, size = size * 0.42f)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = (if (icon != null) labelSp * 0.55f else labelSp).sp,
                    letterSpacing = 0.8.sp
                ),
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Same gloss language as [BigRoundButton], stadium / pill shape for STOP. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlossyPillButton(
    label: String,
    icon: KidIcon? = null,
    color: Color,
    textColor: Color,
    shadowTint: Color,
    minWidth: Dp,
    height: Dp,
    labelSp: Float,
    fillFraction: Float = 0f,
    gestureModifier: Modifier
) {
    val shape = RoundedCornerShape(percent = 50)
    val highlight = Color.White.copy(alpha = 0.42f)
    val mid = color
    val deep = Color(
        red = (color.red * 0.78f).coerceIn(0f, 1f),
        green = (color.green * 0.78f).coerceIn(0f, 1f),
        blue = (color.blue * 0.78f).coerceIn(0f, 1f),
        alpha = color.alpha
    )
    val rimLight = Color.White.copy(alpha = 0.38f)
    Box(
        modifier = Modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = shadowTint,
                spotColor = shadowTint,
                clip = false
            )
            .height(height)
            .widthIn(min = minWidth)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.28f).compositeOver(mid),
                    0.38f to mid,
                    1f to deep
                )
            )
            .border(width = 2.5.dp, color = rimLight, shape = shape)
            .then(gestureModifier)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val corner = h / 2f
            if (fillFraction > 0f) {
                drawRect(
                    color = Color.White.copy(alpha = 0.34f),
                    topLeft = Offset(-28.dp.toPx(), 0f),
                    size = Size((w + 56.dp.toPx()) * fillFraction, h)
                )
            }
            // Soft top gloss blob.
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(highlight, Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.22f),
                    radius = w * 0.42f
                ),
                cornerRadius = CornerRadius(corner, corner)
            )
            // Bottom shade for roundness.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.16f)
                ),
                cornerRadius = CornerRadius(corner, corner)
            )
            // Inner rim nearly flush with outer edge (same idea as RESTART).
            val inset = 4.dp.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(inset, inset),
                size = Size(w - inset * 2f, h - inset * 2f),
                cornerRadius = CornerRadius(corner - inset, corner - inset),
                style = Stroke(width = 2.5.dp.toPx())
            )
            // Top highlight crescent.
            drawArc(
                color = Color.White.copy(alpha = 0.35f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(w * 0.18f, h * 0.12f),
                size = Size(w * 0.64f, h * 0.72f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                KidIconView(icon = icon, color = textColor, size = height * 0.5f)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = labelSp.sp,
                    letterSpacing = 0.8.sp
                ),
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun Color.compositeOver(destination: Color): Color {
    val a = alpha
    val aOut = a + destination.alpha * (1f - a)
    if (aOut < 1e-4f) return Color.Transparent
    return Color(
        red = (red * a + destination.red * destination.alpha * (1f - a)) / aOut,
        green = (green * a + destination.green * destination.alpha * (1f - a)) / aOut,
        blue = (blue * a + destination.blue * destination.alpha * (1f - a)) / aOut,
        alpha = aOut
    )
}

/** True if [p] lands on the pill itself, not the empty corners of its bounding box. */
private fun insideStadium(p: Offset, w: Float, h: Float): Boolean {
    val r = h / 2f
    val cx = p.x.coerceIn(r, (w - r).coerceAtLeast(r))
    val dx = p.x - cx
    val dy = p.y - r
    return dx * dx + dy * dy <= r * r
}

private fun startBedtime(context: android.content.Context) {
    val intent = Intent(context, BedtimeService::class.java)
        .setAction(BedtimeService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}
