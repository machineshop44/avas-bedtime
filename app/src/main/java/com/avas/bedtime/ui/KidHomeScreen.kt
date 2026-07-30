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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avas.bedtime.data.BedtimeSettings
import com.avas.bedtime.session.BedtimeService
import com.avas.bedtime.ui.theme.AppThemeId
import com.avas.bedtime.ui.theme.themeColors
import kotlin.math.max
import kotlinx.coroutines.delay

private data class HomeMetrics(
    val photoSize: Dp,
    val buttonSize: Dp,
    val nameSp: Float,
    val themeSp: Float,
    val statusSp: Float,
    val timerSp: Float,
    val buttonLabelSp: Float,
    val topPad: Dp,
    val gapAfterPhoto: Dp,
    val gapBeforeButton: Dp
)

@Composable
private fun rememberHomeMetrics(maxHeight: Dp): HomeMetrics {
    return when {
        maxHeight < 640.dp -> HomeMetrics(
            photoSize = 88.dp,
            buttonSize = 150.dp,
            nameSp = 34f,
            themeSp = 18f,
            statusSp = 18f,
            timerSp = 28f,
            buttonLabelSp = 34f,
            topPad = 8.dp,
            gapAfterPhoto = 6.dp,
            gapBeforeButton = 10.dp
        )
        maxHeight < 740.dp -> HomeMetrics(
            photoSize = 120.dp,
            buttonSize = 180.dp,
            nameSp = 40f,
            themeSp = 22f,
            statusSp = 20f,
            timerSp = 32f,
            buttonLabelSp = 38f,
            topPad = 12.dp,
            gapAfterPhoto = 10.dp,
            gapBeforeButton = 12.dp
        )
        else -> HomeMetrics(
            photoSize = 168.dp,
            buttonSize = 230.dp,
            nameSp = 48f,
            themeSp = 26f,
            statusSp = 24f,
            timerSp = 36f,
            buttonLabelSp = 46f,
            topPad = 20.dp,
            gapAfterPhoto = 14.dp,
            gapBeforeButton = 18.dp
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
            delay(500)
        }
    }
    val remaining = if (session.active) {
        max(0L, session.endsAtElapsedRealtime - SystemClock.elapsedRealtime().coerceAtLeast(tick))
    } else {
        0L
    }

    val ready = settings.hasBedtimePlaylist
    val pulse by animateFloatAsState(
        targetValue = if (session.active) 1.05f else 1f,
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
        val metrics = rememberHomeMetrics(maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = metrics.topPad, start = 20.dp, end = 20.dp),
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
                        Image(
                            bitmap = bitmap,
                            contentDescription = settings.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(metrics.photoSize)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.height(metrics.gapAfterPhoto))
                    }
                }
                Text(
                    text = settings.possessiveName,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = metrics.nameSp.sp
                    ),
                    color = colors.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (colors.id) {
                        AppThemeId.Unicorn -> "Unicorn Bedtime"
                        AppThemeId.Rainbow -> "Rainbow Bedtime"
                        AppThemeId.Ocean -> "Ocean Bedtime"
                        AppThemeId.Night -> "Bedtime"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = metrics.themeSp.sp
                    ),
                    color = colors.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        session.active && session.statusMessage == "Starting over" ->
                            "Heard a stir — starting over"
                        session.active -> "Sleepy music is on"
                        !ready -> "Grown-ups: pick music in Settings"
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
                    Box(
                        modifier = Modifier
                            .widthIn(min = 180.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(colors.stopButton)
                            .clickable {
                                val intent = Intent(context, BedtimeService::class.java)
                                    .setAction(BedtimeService.ACTION_STOP)
                                context.startService(intent)
                            }
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "STOP",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                            color = colors.buttonText
                        )
                    }
                } else {
                    BigRoundButton(
                        label = "START",
                        color = if (ready) colors.startButton else colors.startButtonDisabled,
                        textColor = colors.buttonText,
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.settingsBar)
                    .clickable(onClick = onOpenSettings)
                    .navigationBarsPadding()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    color = colors.settingsText
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigRoundButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    scale: Float,
    size: Dp,
    labelSp: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .scale(scale)
            .size(size)
            .clip(CircleShape)
            .background(color)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = labelSp.sp),
            color = textColor
        )
    }
}

private fun startBedtime(context: android.content.Context) {
    val intent = Intent(context, BedtimeService::class.java)
        .setAction(BedtimeService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}
