package com.avas.bedtime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.avas.bedtime.ui.theme.AppThemeId
import com.avas.bedtime.ui.theme.BedtimeThemeColors
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Occasional themed passerby — unicorn dash, shooting star, fish, etc.
 */
@Composable
fun ThemePasserby(colors: BedtimeThemeColors) {
    val progress = remember { Animatable(-0.3f) }
    var active by remember { mutableStateOf(false) }
    var lane by remember { mutableFloatStateOf(0.45f) }
    val density = LocalDensity.current

    LaunchedEffect(colors.id) {
        active = false
        // First visit shows sooner so the motif is easy to notice
        delay(Random.nextLong(4_000L, 9_000L))
        while (true) {
            lane = 0.34f + Random.nextFloat() * 0.22f
            progress.snapTo(-0.35f)
            active = true
            progress.animateTo(
                targetValue = 1.35f,
                animationSpec = tween(durationMillis = 5200, easing = LinearEasing)
            )
            active = false
            delay(Random.nextLong(16_000L, 38_000L))
        }
    }

    if (!active) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val x = size.width * progress.value
        val y = size.height * lane
        val scale = with(density) { 1.dp.toPx() }
        when (colors.id) {
            AppThemeId.Unicorn -> drawUnicorn(x, y, scale, colors)
            AppThemeId.Night, AppThemeId.Galaxy -> drawShootingStar(x, y, scale, colors)
            AppThemeId.Ocean -> drawFish(x, y, scale, colors)
            AppThemeId.Forest -> drawFireflyTrail(x, y, scale, colors)
            AppThemeId.Rainbow -> drawSoftBalloon(x, y, scale, colors)
        }
    }
}

private fun DrawScope.drawUnicorn(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors
) {
    val s = scale * 22f
    val body = Color(0xFFFFF5FB)
    val mane = colors.subtitle
    val horn = Color(0xFFFFD36A)

    // Soft shadow under unicorn
    drawOval(
        color = Color.Black.copy(alpha = 0.12f),
        topLeft = Offset(x - 2.2f * s, y + 1.5f * s),
        size = Size(5.2f * s, 0.9f * s)
    )
    // Body
    drawOval(
        color = body,
        topLeft = Offset(x - 1.8f * s, y - 0.7f * s),
        size = Size(3.6f * s, 2.2f * s)
    )
    // Neck / head
    drawOval(
        color = body,
        topLeft = Offset(x + 1.1f * s, y - 2.0f * s),
        size = Size(1.7f * s, 1.5f * s)
    )
    drawCircle(color = body, radius = 0.85f * s, center = Offset(x + 2.5f * s, y - 2.1f * s))
    // Horn
    val hornPath = Path().apply {
        moveTo(x + 2.5f * s, y - 3.5f * s)
        lineTo(x + 2.15f * s, y - 2.5f * s)
        lineTo(x + 2.85f * s, y - 2.5f * s)
        close()
    }
    drawPath(hornPath, color = horn)
    // Mane
    drawCircle(color = mane.copy(alpha = 0.9f), radius = 0.45f * s, center = Offset(x + 1.7f * s, y - 2.4f * s))
    drawCircle(color = mane.copy(alpha = 0.75f), radius = 0.4f * s, center = Offset(x + 1.35f * s, y - 1.8f * s))
    drawCircle(color = mane.copy(alpha = 0.7f), radius = 0.38f * s, center = Offset(x + 1.1f * s, y - 1.2f * s))
    // Legs
    val legW = 0.28f * s
    drawLine(body, Offset(x - 1.1f * s, y + 1.1f * s), Offset(x - 1.3f * s, y + 2.3f * s), strokeWidth = legW)
    drawLine(body, Offset(x - 0.2f * s, y + 1.2f * s), Offset(x - 0.05f * s, y + 2.35f * s), strokeWidth = legW)
    drawLine(body, Offset(x + 0.6f * s, y + 1.15f * s), Offset(x + 0.45f * s, y + 2.3f * s), strokeWidth = legW)
    drawLine(body, Offset(x + 1.3f * s, y + 1.05f * s), Offset(x + 1.55f * s, y + 2.25f * s), strokeWidth = legW)
    // Tail
    drawCircle(color = mane.copy(alpha = 0.85f), radius = 0.5f * s, center = Offset(x - 2.1f * s, y - 0.1f * s))
    // Sparkle dust
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = 0.18f * s, center = Offset(x - 2.8f * s, y - 0.6f * s))
    drawCircle(color = mane.copy(alpha = 0.55f), radius = 0.14f * s, center = Offset(x - 3.3f * s, y + 0.2f * s))
}

private fun DrawScope.drawShootingStar(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors
) {
    val s = scale * 18f
    val tip = Offset(x, y)
    val tail = Offset(x - 5.5f * s, y + 1.6f * s)
    drawLine(
        brush = androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(Color.Transparent, colors.subtitle.copy(alpha = 0.85f), Color.White)
        ),
        start = tail,
        end = tip,
        strokeWidth = 0.35f * s,
        cap = StrokeCap.Round
    )
    drawCircle(Color.White, radius = 0.55f * s, center = tip)
    drawCircle(colors.subtitle.copy(alpha = 0.5f), radius = 1.1f * s, center = tip)
}

private fun DrawScope.drawFish(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors
) {
    val s = scale * 20f
    drawOval(
        color = Color.Black.copy(alpha = 0.12f),
        topLeft = Offset(x - 1.8f * s, y + 1.1f * s),
        size = Size(3.4f * s, 0.7f * s)
    )
    drawOval(
        color = colors.startButton,
        topLeft = Offset(x - 1.6f * s, y - 0.7f * s),
        size = Size(3.0f * s, 1.5f * s)
    )
    val tail = Path().apply {
        moveTo(x - 1.5f * s, y)
        lineTo(x - 2.7f * s, y - 0.9f * s)
        lineTo(x - 2.7f * s, y + 0.9f * s)
        close()
    }
    drawPath(tail, color = colors.subtitle)
    drawCircle(Color.White.copy(alpha = 0.9f), radius = 0.22f * s, center = Offset(x + 0.9f * s, y - 0.15f * s))
    drawCircle(Color(0xFF103040), radius = 0.1f * s, center = Offset(x + 0.95f * s, y - 0.15f * s))
    // Bubbles
    drawCircle(Color.White.copy(alpha = 0.45f), radius = 0.25f * s, center = Offset(x + 2.2f * s, y - 1.2f * s))
    drawCircle(Color.White.copy(alpha = 0.35f), radius = 0.18f * s, center = Offset(x + 2.8f * s, y - 1.9f * s))
}

private fun DrawScope.drawFireflyTrail(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors
) {
    val s = scale * 16f
    for (i in 0..5) {
        val dx = -i * 0.9f * s
        val dy = sin(i * 1.1f) * 0.55f * s
        val glow = colors.subtitle.copy(alpha = 0.55f - i * 0.07f)
        drawCircle(glow, radius = (0.7f - i * 0.07f) * s, center = Offset(x + dx, y + dy))
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 0.22f * s, center = Offset(x + dx, y + dy))
    }
}

private fun DrawScope.drawSoftBalloon(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors
) {
    val s = scale * 18f
    drawOval(
        color = Color.Black.copy(alpha = 0.10f),
        topLeft = Offset(x - 1.1f * s, y + 2.4f * s),
        size = Size(2.2f * s, 0.55f * s)
    )
    drawOval(
        color = colors.startButton,
        topLeft = Offset(x - 1.2f * s, y - 1.6f * s),
        size = Size(2.4f * s, 2.8f * s)
    )
    drawCircle(Color.White.copy(alpha = 0.35f), radius = 0.35f * s, center = Offset(x - 0.35f * s, y - 0.7f * s))
    drawLine(
        color = colors.body.copy(alpha = 0.55f),
        start = Offset(x, y + 1.2f * s),
        end = Offset(x, y + 3.2f * s),
        strokeWidth = 0.12f * s
    )
}
