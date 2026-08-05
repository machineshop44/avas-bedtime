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
import com.avas.bedtime.ui.theme.themeColors
import kotlin.math.max
import kotlinx.coroutines.delay

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
    val colors = themeColors(AppThemeId.fromStorage(settings.themeId))

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startBedtime(context)
        }
    }

    fun neededPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    fun hasPermissions(): Boolean =
        neededPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

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
    val pulse by animateFloatAsState(
        targetValue = if (session.active) 1.03f else 1f,
        animationSpec = tween(900),
        label = "pulse"
    )
    val sparkle = rememberInfiniteTransition(label = "sparkle")
    val sparkleScale by sparkle.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "sparkleScale"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        SoftAtmosphere(colors = colors)
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
                                android.graphics.BitmapFactory.decodeFile(settings.avaPhotoPath)
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
                Text(
                    text = when {
                        session.active && session.statusMessage == "Starting over" ->
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
                if (session.active) {
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
                    BigRoundButton(
                        label = "RESTART",
                        color = colors.startButton,
                        textColor = colors.buttonText,
                        shadowTint = colors.shadowTint,
                        scale = pulse,
                        size = metrics.buttonSize,
                        labelSp = metrics.buttonLabelSp,
                        onClick = {
                            val intent = Intent(context, BedtimeService::class.java)
                                .setAction(BedtimeService.ACTION_RESTART)
                            context.startService(intent)
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    GlossyPillButton(
                        label = "STOP",
                        color = colors.stopButton,
                        textColor = colors.buttonText,
                        shadowTint = colors.shadowTint,
                        minWidth = metrics.stopMinWidth,
                        height = metrics.stopHeight,
                        labelSp = metrics.stopLabelSp,
                        onClick = {
                            val intent = Intent(context, BedtimeService::class.java)
                                .setAction(BedtimeService.ACTION_STOP)
                            context.startService(intent)
                        }
                    )
                } else {
                    BigRoundButton(
                        label = "START",
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
            avaPhotoPath = settings.avaPhotoPath
        )
    }
}

@Composable
private fun SoftAtmosphere(colors: BedtimeThemeColors) {
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

    // Soft cloud orbs
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

    // Twinkling stars
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

    // Gentle vignette
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
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
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
        Text(
            text = label,
            style = MaterialTheme.typography.headlineLarge.copy(
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

/** Same gloss language as [BigRoundButton], stadium / pill shape for STOP. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlossyPillButton(
    label: String,
    color: Color,
    textColor: Color,
    shadowTint: Color,
    minWidth: Dp,
    height: Dp,
    labelSp: Float,
    onClick: () -> Unit
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
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val corner = h / 2f
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

private fun startBedtime(context: android.content.Context) {
    val intent = Intent(context, BedtimeService::class.java)
        .setAction(BedtimeService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}
