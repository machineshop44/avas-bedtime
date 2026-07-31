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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.avas.bedtime.ui.theme.AppThemeId
import com.avas.bedtime.ui.theme.BedtimeThemeColors
import kotlin.math.PI
import kotlin.math.cos
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
        delay(Random.nextLong(3_000L, 7_000L))
        while (true) {
            lane = 0.36f + Random.nextFloat() * 0.18f
            progress.snapTo(-0.4f)
            active = true
            // Slower so Ava can actually see it
            progress.animateTo(
                targetValue = 1.4f,
                animationSpec = tween(durationMillis = 6800, easing = LinearEasing)
            )
            active = false
            delay(Random.nextLong(14_000L, 32_000L))
        }
    }

    if (!active) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val x = size.width * progress.value
        val y = size.height * lane
        val scale = with(density) { 1.dp.toPx() }
        val gallop = progress.value * 28f
        when (colors.id) {
            AppThemeId.Unicorn -> drawCartoonUnicorn(x, y, scale, gallop)
            AppThemeId.Night, AppThemeId.Galaxy -> drawShootingStar(x, y, scale, colors)
            AppThemeId.Ocean -> drawFish(x, y, scale, colors, gallop)
            AppThemeId.Forest -> drawFireflyTrail(x, y, scale, colors, gallop)
            AppThemeId.Rainbow -> drawSoftBalloon(x, y, scale, colors, gallop)
        }
    }
}

private fun DrawScope.drawCartoonUnicorn(
    x: Float,
    y: Float,
    scale: Float,
    gallop: Float
) {
    val s = scale * 34f
    val bob = sin(gallop) * 0.18f * s
    val cy = y + bob

    val body = Color(0xFFFFFBFE)
    val outline = Color(0xFFE8A0C8)
    val maneA = Color(0xFFFF7EB9)
    val maneB = Color(0xFFB88CFF)
    val maneC = Color(0xFF7ED6FF)
    val hornGold = Color(0xFFFFD36A)
    val hornPink = Color(0xFFFF9EC8)
    val hoof = Color(0xFFC4A574)
    val blush = Color(0xFFFFB0C8)

    // Soft ground shadow
    drawOval(
        color = Color(0xFF5E3A68).copy(alpha = 0.18f),
        topLeft = Offset(x - 2.8f * s, cy + 2.35f * s),
        size = Size(5.6f * s, 0.7f * s)
    )

    // Sparkle trail behind
    for (i in 1..7) {
        val t = i / 7f
        val sx = x - (1.8f + i * 0.55f) * s
        val sy = cy - 0.2f * s + sin(gallop * 1.4f + i) * 0.55f * s
        val sparkle = when (i % 3) {
            0 -> maneA
            1 -> maneB
            else -> Color.White
        }
        drawStar(Offset(sx, sy), (0.28f - t * 0.12f) * s, sparkle.copy(alpha = 0.85f - t * 0.5f))
    }

    withTransform({
        // Tiny nose-down lean while galloping
        rotate(degrees = -4f, pivot = Offset(x, cy))
    }) {
        // Tail (behind body)
        val tail = Path().apply {
            moveTo(x - 1.9f * s, cy - 0.15f * s)
            cubicTo(
                x - 3.0f * s, cy - 1.1f * s + sin(gallop) * 0.25f * s,
                x - 3.6f * s, cy + 0.2f * s + cos(gallop) * 0.3f * s,
                x - 2.7f * s, cy + 0.9f * s
            )
            cubicTo(
                x - 3.2f * s, cy + 0.35f * s,
                x - 2.6f * s, cy - 0.35f * s,
                x - 1.9f * s, cy - 0.05f * s
            )
            close()
        }
        drawPath(tail, maneB)
        drawPath(tail, outline.copy(alpha = 0.55f), style = Stroke(width = 0.08f * s))

        // Back legs (gallop opposite phase)
        val backSwing = sin(gallop) * 0.55f * s
        drawLeg(
            hip = Offset(x - 1.05f * s, cy + 0.85f * s),
            foot = Offset(x - 1.35f * s - backSwing * 0.35f, cy + 2.25f * s),
            kneeBend = 0.35f * s,
            body = body,
            outline = outline,
            hoof = hoof,
            width = 0.32f * s
        )
        drawLeg(
            hip = Offset(x - 0.45f * s, cy + 0.9f * s),
            foot = Offset(x - 0.25f * s + backSwing * 0.4f, cy + 2.3f * s),
            kneeBend = 0.3f * s,
            body = body,
            outline = outline,
            hoof = hoof,
            width = 0.32f * s
        )

        // Body
        val torso = Path().apply {
            moveTo(x - 1.85f * s, cy + 0.15f * s)
            cubicTo(
                x - 2.0f * s, cy - 1.05f * s,
                x - 0.4f * s, cy - 1.35f * s,
                x + 1.15f * s, cy - 0.85f * s
            )
            cubicTo(
                x + 1.85f * s, cy - 0.55f * s,
                x + 1.95f * s, cy + 0.55f * s,
                x + 1.35f * s, cy + 1.05f * s
            )
            cubicTo(
                x + 0.4f * s, cy + 1.35f * s,
                x - 1.0f * s, cy + 1.4f * s,
                x - 1.85f * s, cy + 0.55f * s
            )
            close()
        }
        drawPath(torso, body)
        drawPath(
            torso,
            outline,
            style = Stroke(width = 0.12f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Belly blush
        drawOval(
            color = blush.copy(alpha = 0.35f),
            topLeft = Offset(x - 0.7f * s, cy + 0.35f * s),
            size = Size(1.5f * s, 0.55f * s)
        )

        // Front legs
        val frontSwing = sin(gallop + PI.toFloat()) * 0.6f * s
        drawLeg(
            hip = Offset(x + 0.55f * s, cy + 0.85f * s),
            foot = Offset(x + 0.25f * s - frontSwing * 0.4f, cy + 2.28f * s),
            kneeBend = 0.4f * s,
            body = body,
            outline = outline,
            hoof = hoof,
            width = 0.34f * s
        )
        drawLeg(
            hip = Offset(x + 1.1f * s, cy + 0.8f * s),
            foot = Offset(x + 1.45f * s + frontSwing * 0.35f, cy + 2.22f * s),
            kneeBend = 0.35f * s,
            body = body,
            outline = outline,
            hoof = hoof,
            width = 0.34f * s
        )

        // Neck
        val neck = Path().apply {
            moveTo(x + 1.0f * s, cy - 0.55f * s)
            cubicTo(
                x + 1.15f * s, cy - 1.55f * s,
                x + 1.55f * s, cy - 2.15f * s,
                x + 2.05f * s, cy - 2.25f * s
            )
            lineTo(x + 2.35f * s, cy - 1.55f * s)
            cubicTo(
                x + 1.85f * s, cy - 1.35f * s,
                x + 1.55f * s, cy - 0.55f * s,
                x + 1.45f * s, cy + 0.05f * s
            )
            close()
        }
        drawPath(neck, body)
        drawPath(neck, outline, style = Stroke(width = 0.1f * s, join = StrokeJoin.Round))

        // Head
        val head = Path().apply {
            moveTo(x + 1.95f * s, cy - 2.55f * s)
            cubicTo(
                x + 1.75f * s, cy - 3.05f * s,
                x + 2.35f * s, cy - 3.25f * s,
                x + 2.85f * s, cy - 2.85f * s
            )
            cubicTo(
                x + 3.35f * s, cy - 2.45f * s,
                x + 3.45f * s, cy - 1.85f * s,
                x + 3.05f * s, cy - 1.55f * s
            )
            cubicTo(
                x + 2.55f * s, cy - 1.25f * s,
                x + 2.05f * s, cy - 1.55f * s,
                x + 1.95f * s, cy - 2.05f * s
            )
            close()
        }
        drawPath(head, body)
        drawPath(head, outline, style = Stroke(width = 0.1f * s, join = StrokeJoin.Round))

        // Ear
        val ear = Path().apply {
            moveTo(x + 2.15f * s, cy - 2.85f * s)
            lineTo(x + 2.05f * s, cy - 3.45f * s)
            lineTo(x + 2.45f * s, cy - 3.0f * s)
            close()
        }
        drawPath(ear, body)
        drawPath(ear, outline, style = Stroke(width = 0.07f * s))
        drawPath(
            Path().apply {
                moveTo(x + 2.18f * s, cy - 2.95f * s)
                lineTo(x + 2.15f * s, cy - 3.25f * s)
                lineTo(x + 2.32f * s, cy - 3.0f * s)
                close()
            },
            blush.copy(alpha = 0.7f)
        )

        // Horn
        val horn = Path().apply {
            moveTo(x + 2.55f * s, cy - 4.35f * s)
            lineTo(x + 2.25f * s, cy - 2.95f * s)
            lineTo(x + 2.85f * s, cy - 2.95f * s)
            close()
        }
        drawPath(
            horn,
            Brush.verticalGradient(listOf(hornGold, hornPink, hornGold)),
        )
        drawPath(horn, outline.copy(alpha = 0.65f), style = Stroke(width = 0.07f * s))
        // Horn spiral marks
        drawLine(
            color = Color.White.copy(alpha = 0.55f),
            start = Offset(x + 2.45f * s, cy - 3.85f * s),
            end = Offset(x + 2.7f * s, cy - 3.55f * s),
            strokeWidth = 0.07f * s,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White.copy(alpha = 0.45f),
            start = Offset(x + 2.42f * s, cy - 3.4f * s),
            end = Offset(x + 2.68f * s, cy - 3.15f * s),
            strokeWidth = 0.07f * s,
            cap = StrokeCap.Round
        )

        // Mane flowing over neck
        val mane = Path().apply {
            moveTo(x + 2.05f * s, cy - 2.95f * s)
            cubicTo(
                x + 1.35f * s, cy - 3.35f * s + sin(gallop * 1.2f) * 0.15f * s,
                x + 0.55f * s, cy - 2.45f * s,
                x + 0.35f * s, cy - 1.35f * s
            )
            cubicTo(
                x + 0.65f * s, cy - 1.55f * s,
                x + 1.15f * s, cy - 2.15f * s,
                x + 1.55f * s, cy - 2.35f * s
            )
            cubicTo(
                x + 1.25f * s, cy - 1.85f * s,
                x + 0.95f * s, cy - 1.15f * s,
                x + 0.85f * s, cy - 0.55f * s
            )
            cubicTo(
                x + 1.35f * s, cy - 0.95f * s,
                x + 1.85f * s, cy - 1.75f * s,
                x + 2.15f * s, cy - 2.45f * s
            )
            close()
        }
        drawPath(
            mane,
            Brush.verticalGradient(listOf(maneA, maneB, maneC))
        )

        // Forelock curl
        val forelock = Path().apply {
            moveTo(x + 2.35f * s, cy - 3.05f * s)
            cubicTo(
                x + 2.75f * s, cy - 3.45f * s,
                x + 3.15f * s, cy - 3.05f * s,
                x + 2.95f * s, cy - 2.65f * s
            )
            cubicTo(
                x + 2.75f * s, cy - 2.85f * s,
                x + 2.55f * s, cy - 2.95f * s,
                x + 2.4f * s, cy - 2.9f * s
            )
            close()
        }
        drawPath(forelock, maneA)

        // Eye
        drawCircle(Color(0xFF4A3560), radius = 0.14f * s, center = Offset(x + 2.85f * s, cy - 2.35f * s))
        drawCircle(Color.White, radius = 0.05f * s, center = Offset(x + 2.9f * s, cy - 2.4f * s))
        // Cheek
        drawCircle(blush.copy(alpha = 0.55f), radius = 0.16f * s, center = Offset(x + 2.95f * s, cy - 1.95f * s))
        // Smile
        drawLine(
            color = outline.copy(alpha = 0.7f),
            start = Offset(x + 3.05f * s, cy - 1.78f * s),
            end = Offset(x + 3.25f * s, cy - 1.72f * s),
            strokeWidth = 0.06f * s,
            cap = StrokeCap.Round
        )

        // Nose tip highlight
        drawCircle(Color.White.copy(alpha = 0.5f), radius = 0.08f * s, center = Offset(x + 3.2f * s, cy - 1.95f * s))
    }

    // Horn tip sparkle (outside transform so it pops)
    drawStar(Offset(x + 2.55f * s, y + bob - 4.55f * s), 0.32f * s, Color.White)
    drawStar(Offset(x + 3.15f * s, y + bob - 3.9f * s), 0.18f * s, maneA.copy(alpha = 0.9f))
}

private fun DrawScope.drawLeg(
    hip: Offset,
    foot: Offset,
    kneeBend: Float,
    body: Color,
    outline: Color,
    hoof: Color,
    width: Float
) {
    val mid = Offset(
        (hip.x + foot.x) / 2f + kneeBend * 0.15f,
        (hip.y + foot.y) / 2f
    )
    drawLine(outline.copy(alpha = 0.35f), hip, mid, strokeWidth = width + 0.08f * width, cap = StrokeCap.Round)
    drawLine(body, hip, mid, strokeWidth = width, cap = StrokeCap.Round)
    drawLine(body, mid, foot, strokeWidth = width * 0.9f, cap = StrokeCap.Round)
    drawLine(outline, hip, mid, strokeWidth = width * 0.22f, cap = StrokeCap.Round)
    drawOval(
        color = hoof,
        topLeft = Offset(foot.x - width * 0.55f, foot.y - width * 0.15f),
        size = Size(width * 1.1f, width * 0.45f)
    )
}

private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val path = Path()
    for (i in 0 until 8) {
        val angle = (i * PI / 4.0) - PI / 2.0
        val r = if (i % 2 == 0) radius else radius * 0.4f
        val px = center.x + (cos(angle) * r).toFloat()
        val py = center.y + (sin(angle) * r).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawShootingStar(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors
) {
    val s = scale * 22f
    val tip = Offset(x, y)
    val tail = Offset(x - 6.5f * s, y + 1.8f * s)
    drawLine(
        brush = Brush.linearGradient(
            listOf(Color.Transparent, colors.subtitle.copy(alpha = 0.9f), Color.White)
        ),
        start = tail,
        end = tip,
        strokeWidth = 0.45f * s,
        cap = StrokeCap.Round
    )
    drawCircle(colors.subtitle.copy(alpha = 0.45f), radius = 1.3f * s, center = tip)
    drawCircle(Color.White, radius = 0.65f * s, center = tip)
    drawStar(Offset(x - 1.2f * s, y + 0.4f * s), 0.28f * s, Color.White.copy(alpha = 0.7f))
}

private fun DrawScope.drawFish(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors,
    phase: Float
) {
    val s = scale * 24f
    val bob = sin(phase) * 0.2f * s
    val cy = y + bob
    drawOval(
        color = Color.Black.copy(alpha = 0.14f),
        topLeft = Offset(x - 2.0f * s, cy + 1.25f * s),
        size = Size(3.8f * s, 0.75f * s)
    )
    val body = Path().apply {
        moveTo(x + 1.8f * s, cy)
        cubicTo(x + 1.2f * s, cy - 1.1f * s, x - 1.0f * s, cy - 1.15f * s, x - 1.7f * s, cy)
        cubicTo(x - 1.0f * s, cy + 1.15f * s, x + 1.2f * s, cy + 1.1f * s, x + 1.8f * s, cy)
        close()
    }
    drawPath(body, colors.startButton)
    drawPath(body, Color.White.copy(alpha = 0.35f), style = Stroke(width = 0.1f * s))
    val tail = Path().apply {
        moveTo(x - 1.55f * s, cy)
        lineTo(x - 2.9f * s, cy - 1.05f * s)
        lineTo(x - 2.55f * s, cy)
        lineTo(x - 2.9f * s, cy + 1.05f * s)
        close()
    }
    drawPath(tail, colors.subtitle)
    drawCircle(Color.White, radius = 0.28f * s, center = Offset(x + 1.05f * s, cy - 0.2f * s))
    drawCircle(Color(0xFF103040), radius = 0.13f * s, center = Offset(x + 1.12f * s, cy - 0.2f * s))
    drawCircle(Color.White.copy(alpha = 0.5f), radius = 0.28f * s, center = Offset(x + 2.3f * s, cy - 1.3f * s))
    drawCircle(Color.White.copy(alpha = 0.35f), radius = 0.2f * s, center = Offset(x + 2.9f * s, cy - 2.0f * s))
}

private fun DrawScope.drawFireflyTrail(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors,
    phase: Float
) {
    val s = scale * 18f
    for (i in 0..6) {
        val dx = -i * 0.95f * s
        val dy = sin(phase + i * 1.1f) * 0.7f * s
        val glow = colors.subtitle.copy(alpha = 0.6f - i * 0.07f)
        drawCircle(glow, radius = (0.85f - i * 0.07f) * s, center = Offset(x + dx, y + dy))
        drawCircle(Color.White.copy(alpha = 0.9f), radius = 0.25f * s, center = Offset(x + dx, y + dy))
    }
}

private fun DrawScope.drawSoftBalloon(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors,
    phase: Float
) {
    val s = scale * 22f
    val bob = sin(phase * 0.7f) * 0.25f * s
    val cy = y + bob
    drawOval(
        color = Color.Black.copy(alpha = 0.12f),
        topLeft = Offset(x - 1.2f * s, cy + 2.6f * s),
        size = Size(2.4f * s, 0.55f * s)
    )
    drawOval(
        color = colors.startButton,
        topLeft = Offset(x - 1.35f * s, cy - 1.8f * s),
        size = Size(2.7f * s, 3.1f * s)
    )
    drawCircle(Color.White.copy(alpha = 0.4f), radius = 0.4f * s, center = Offset(x - 0.4f * s, cy - 0.85f * s))
    drawLine(
        color = colors.body.copy(alpha = 0.6f),
        start = Offset(x, cy + 1.3f * s),
        end = Offset(x + sin(phase) * 0.15f * s, cy + 3.4f * s),
        strokeWidth = 0.14f * s
    )
}
