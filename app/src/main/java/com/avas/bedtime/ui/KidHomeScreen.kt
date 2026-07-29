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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 28.dp, start = 24.dp, end = 24.dp),
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
                            .size(168.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
            Text(
                text = settings.possessiveName,
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 48.sp),
                color = colors.title
            )
            Text(
                text = when (colors.id) {
                    AppThemeId.Unicorn -> "Unicorn Bedtime"
                    AppThemeId.Rainbow -> "Rainbow Bedtime"
                    AppThemeId.Ocean -> "Ocean Bedtime"
                    AppThemeId.Night -> "Bedtime"
                },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
                color = colors.subtitle
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
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
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                color = colors.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (!session.active && ready) {
                Text(
                    text = when (settings.resolvedEndMode) {
                        com.avas.bedtime.data.EndMode.WakeUp ->
                            "Stops at ${settings.wakeLabel}"
                        com.avas.bedtime.data.EndMode.Duration ->
                            "Plays ${settings.timerHours} hours"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = colors.subtitle,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
            if (session.active) {
                Text(
                    text = BedtimeService.formatRemaining(remaining),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp),
                    color = colors.title,
                    modifier = Modifier.padding(bottom = 22.dp)
                )
            }

            if (session.active) {
                BigRoundButton(
                    label = "RESTART",
                    color = colors.startButton,
                    textColor = colors.buttonText,
                    scale = pulse,
                    onClick = {
                        val intent = Intent(context, BedtimeService::class.java)
                            .setAction(BedtimeService.ACTION_RESTART)
                        context.startService(intent)
                    }
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .widthIn(min = 200.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(colors.stopButton)
                        .clickable {
                            val intent = Intent(context, BedtimeService::class.java)
                                .setAction(BedtimeService.ACTION_STOP)
                            context.startService(intent)
                        }
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "STOP",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                        color = colors.buttonText
                    )
                }
            } else {
                BigRoundButton(
                    label = "START",
                    color = if (ready) colors.startButton else colors.startButtonDisabled,
                    textColor = colors.buttonText,
                    scale = if (ready) sparkleScale else 1f,
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
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.settingsBar)
                .clickable(onClick = onOpenSettings)
                .navigationBarsPadding()
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                color = colors.settingsText
            )
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
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .scale(scale)
            .size(230.dp)
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
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 46.sp),
            color = textColor
        )
    }
}

private fun startBedtime(context: android.content.Context) {
    val intent = Intent(context, BedtimeService::class.java)
        .setAction(BedtimeService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}
