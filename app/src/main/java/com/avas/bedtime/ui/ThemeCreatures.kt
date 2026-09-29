package com.avas.bedtime.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.avas.bedtime.ui.theme.BedtimeThemeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/*
 * Signature passersby for the non-unicorn themes. All Canvas-drawn (no assets).
 * Tap reactions are silent and slow — a sleepy kid is watching and the mic is listening.
 * `r` is the tap reaction progress 0→1 (0 when idle).
 */

private val pastelBands = listOf(
    Color(0xFFFF9EB5),
    Color(0xFFFFC98A),
    Color(0xFFFFF09A),
    Color(0xFFA8E6B4),
    Color(0xFF9ED8FF),
    Color(0xFFC9A8FF)
)

/** 0 → 1 → 0 over the reaction. */
private fun swell(r: Float): Float = sin(PI * r.coerceIn(0f, 1f)).toFloat()

/** Normalized heart curve, roughly within [-1, 1]. */
private fun heartPoint(t: Float): Offset {
    val x = 16f * sin(t).pow(3)
    val y = -(13f * cos(t) - 5f * cos(2f * t) - 2f * cos(3f * t) - cos(4f * t))
    return Offset(x / 16f, y / 16f)
}

internal fun DrawScope.drawHeart(center: Offset, size: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y + size * 0.35f)
        cubicTo(
            center.x - size, center.y - size * 0.25f,
            center.x - size * 0.45f, center.y - size * 0.95f,
            center.x, center.y - size * 0.4f
        )
        cubicTo(
            center.x + size * 0.45f, center.y - size * 0.95f,
            center.x + size, center.y - size * 0.25f,
            center.x, center.y + size * 0.35f
        )
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawZ(origin: Offset, size: Float, color: Color) {
    val p = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(origin.x + size, origin.y)
        lineTo(origin.x, origin.y + size)
        lineTo(origin.x + size, origin.y + size)
    }
    drawPath(p, color, style = Stroke(width = size * 0.22f, cap = StrokeCap.Round))
}

/** Night: a sleepy crescent moon in a nightcap. Tap → big yawn, star specks drift down. */
internal fun DrawScope.drawSleepyMoon(
    x: Float,
    y: Float,
    scale: Float,
    phase: Float,
    r: Float,
    faceRight: Boolean
) {
    val s = scale * 24f
    val dir = if (faceRight) 1f else -1f
    val c = Offset(x, y + sin(phase * 0.5f) * 0.25f * s)
    val moonColor = Color(0xFFFFE8A3)

    drawCircle(Color(0xFFFFF3C4).copy(alpha = 0.16f), 2.7f * s, c)
    drawCircle(Color(0xFFFFF3C4).copy(alpha = 0.10f), 3.6f * s, c)

    val outer = Path().apply { addOval(Rect(c, 1.6f * s)) }
    val bite = Path().apply {
        addOval(Rect(c + Offset(dir * 0.85f * s, -0.35f * s), 1.35f * s))
    }
    val crescent = Path().apply { op(outer, bite, PathOperation.Difference) }
    drawPath(crescent, moonColor)
    drawPath(crescent, Color.White.copy(alpha = 0.35f), style = Stroke(width = 0.08f * s))

    // Face on the fat part of the crescent.
    val face = c + Offset(-dir * 0.85f * s, 0.25f * s)
    val yawn = swell(r)
    drawArc(
        color = Color(0xFF8A6A3C),
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = face + Offset(-0.22f * s, -0.45f * s),
        size = Size(0.42f * s, 0.26f * s),
        style = Stroke(width = 0.08f * s, cap = StrokeCap.Round)
    )
    drawCircle(Color(0xFFFFA8A8).copy(alpha = 0.55f), 0.16f * s, face + Offset(-dir * 0.1f * s, 0.05f * s))
    if (yawn > 0.05f) {
        drawOval(
            Color(0xFF8A4A3C),
            topLeft = face + Offset(-0.14f * s * (0.6f + yawn), 0.18f * s),
            size = Size(0.28f * s * (0.6f + yawn), 0.42f * s * yawn)
        )
    } else {
        drawArc(
            color = Color(0xFF8A6A3C),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = face + Offset(-0.14f * s, 0.1f * s),
            size = Size(0.28f * s, 0.2f * s),
            style = Stroke(width = 0.06f * s, cap = StrokeCap.Round)
        )
    }

    // Nightcap flops back from the top tip.
    val tip = c + Offset(-dir * 0.35f * s, -1.45f * s)
    val point = tip + Offset(-dir * 1.7f * s, -0.7f * s + sin(phase * 0.6f) * 0.18f * s)
    val cap = Path().apply {
        moveTo(tip.x - 0.6f * s, tip.y + 0.15f * s)
        quadraticTo(tip.x - dir * 0.4f * s, tip.y - 1.0f * s, point.x, point.y)
        quadraticTo(tip.x + dir * 0.2f * s, tip.y - 0.4f * s, tip.x + 0.6f * s, tip.y + 0.15f * s)
        close()
    }
    drawPath(cap, Color(0xFF7A8CFF))
    drawRoundRect(
        Color.White.copy(alpha = 0.92f),
        topLeft = Offset(tip.x - 0.68f * s, tip.y),
        size = Size(1.36f * s, 0.3f * s),
        cornerRadius = CornerRadius(0.15f * s, 0.15f * s)
    )
    drawCircle(Color.White, 0.24f * s, point)

    // Floating Zzz behind.
    for (i in 0 until 3) {
        val cycle = ((phase * 0.08f + i / 3f) % 1f)
        val zPos = c + Offset(-dir * (1.9f + cycle * 1.4f) * s, (-0.6f - cycle * 1.6f) * s)
        drawZ(zPos, (0.22f + i * 0.06f) * s, Color.White.copy(alpha = (1f - cycle) * 0.55f))
    }

    if (r > 0f) {
        for (i in 0 until 7) {
            val sx = x + (i - 3) * 0.6f * s + sin(i * 1.7f) * 0.2f * s
            val sy = c.y + 1.3f * s + r * (2.4f + (i % 3) * 0.5f) * s
            drawStar(Offset(sx, sy), 0.22f * s, Color(0xFFFFF6C8).copy(alpha = (1f - r) * 0.9f))
        }
    }
}

/** Galaxy: ringed planet with a tiny astronaut (Ava's face in the helmet). Tap → ring spins, heart constellation. */
internal fun DrawScope.drawRingedPlanet(
    x: Float,
    y: Float,
    scale: Float,
    colors: BedtimeThemeColors,
    phase: Float,
    r: Float,
    face: ImageBitmap?
) {
    val s = scale * 24f
    val c = Offset(x, y + sin(phase * 0.45f) * 0.25f * s)
    val ringColor = Color(0xFFFFD98A).copy(alpha = 0.85f)
    val tilt = -16f + r * 360f
    val ringTopLeft = c - Offset(2.7f * s, 0.7f * s)
    val ringSize = Size(5.4f * s, 1.4f * s)

    drawCircle(colors.subtitle.copy(alpha = 0.14f), 2.6f * s, c)
    withTransform({ rotate(tilt, pivot = c) }) {
        drawArc(ringColor, 180f, 180f, false, ringTopLeft, ringSize, style = Stroke(0.26f * s))
    }
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFD8C4FF), Color(0xFF7A5CE0), Color(0xFF4A3499)),
            center = c + Offset(-0.5f * s, -0.5f * s),
            radius = 2.1f * s
        ),
        radius = 1.45f * s,
        center = c
    )
    drawCircle(Color.White.copy(alpha = 0.18f), 0.32f * s, c + Offset(0.5f * s, 0.4f * s))
    drawCircle(Color.White.copy(alpha = 0.14f), 0.2f * s, c + Offset(-0.6f * s, 0.6f * s))
    withTransform({ rotate(tilt, pivot = c) }) {
        drawArc(ringColor, 0f, 180f, false, ringTopLeft, ringSize, style = Stroke(0.26f * s))
    }

    // Little astronaut sitting on top, waving.
    val helmet = c + Offset(0.25f * s, -2.05f * s)
    drawRoundRect(
        Color(0xFFF4F4FF),
        topLeft = helmet + Offset(-0.38f * s, 0.3f * s),
        size = Size(0.76f * s, 0.62f * s),
        cornerRadius = CornerRadius(0.22f * s, 0.22f * s)
    )
    val wave = sin(phase * 0.9f) * 0.25f * s
    drawLine(
        Color(0xFFF4F4FF),
        helmet + Offset(0.34f * s, 0.45f * s),
        helmet + Offset(0.75f * s, -0.05f * s + wave),
        0.18f * s,
        StrokeCap.Round
    )
    drawCircle(Color(0xFFF4F4FF), 0.56f * s, helmet)
    val visorR = 0.42f * s
    if (face != null) {
        clipPath(Path().apply { addOval(Rect(helmet, visorR)) }) {
            val size = (visorR * 2f).toInt().coerceAtLeast(4)
            drawImage(
                face,
                dstOffset = IntOffset((helmet.x - visorR).toInt(), (helmet.y - visorR).toInt()),
                dstSize = IntSize(size, size)
            )
        }
    } else {
        drawCircle(Color(0xFF2A2F6A), visorR, helmet)
    }
    drawArc(
        Color.White.copy(alpha = 0.55f),
        200f,
        70f,
        false,
        helmet - Offset(visorR * 0.8f, visorR * 0.8f),
        Size(visorR * 1.6f, visorR * 1.6f),
        style = Stroke(0.06f * s, cap = StrokeCap.Round)
    )

    if (r > 0f) {
        val n = 14
        val shown = ((r * 1.7f).coerceAtMost(1f) * n).toInt()
        val fade = if (r > 0.75f) (1f - r) / 0.25f else 1f
        val hc = c + Offset(3.4f * s, -1.4f * s)
        var prev: Offset? = null
        for (i in 0 until shown) {
            val p = hc + heartPoint(2f * PI.toFloat() * i / n) * (1.3f * s)
            prev?.let {
                drawLine(Color.White.copy(alpha = 0.35f * fade), it, p, 0.05f * s, StrokeCap.Round)
            }
            drawStar(p, 0.18f * s, Color(0xFFFFF6C8).copy(alpha = 0.9f * fade))
            prev = p
        }
    }
}

/** Ocean: a sea turtle paddling slowly. Tap → gentle roll and a rising bubble ring. */
internal fun DrawScope.drawSeaTurtle(
    x: Float,
    y: Float,
    scale: Float,
    phase: Float,
    r: Float,
    faceRight: Boolean
) {
    val s = scale * 24f
    val cy = y + sin(phase * 0.5f) * 0.22f * s
    val c = Offset(x, cy)
    val paddle = sin(phase * 0.7f)
    val skin = Color(0xFF7CCB98)
    val skinDark = Color(0xFF5AAE7C)

    drawOval(
        Color.Black.copy(alpha = 0.10f),
        topLeft = Offset(x - 1.8f * s, cy + 1.5f * s),
        size = Size(3.6f * s, 0.6f * s)
    )

    withTransform({
        rotate(swell(r) * 14f, pivot = c)
        scale(if (faceRight) 1f else -1f, 1f, pivot = c)
    }) {
        // Far-side flippers first (dimmer).
        withTransform({ rotate(-20f - paddle * 22f, pivot = c + Offset(0.9f * s, -0.5f * s)) }) {
            drawOval(skinDark, c + Offset(0.6f * s, -1.15f * s), Size(1.3f * s, 0.55f * s))
        }
        withTransform({ rotate(15f + paddle * 16f, pivot = c + Offset(-1.0f * s, -0.4f * s)) }) {
            drawOval(skinDark, c + Offset(-1.8f * s, -0.9f * s), Size(0.95f * s, 0.45f * s))
        }
        drawPath(
            Path().apply {
                moveTo(c.x - 1.55f * s, c.y)
                lineTo(c.x - 2.1f * s, c.y + 0.12f * s)
                lineTo(c.x - 1.55f * s, c.y + 0.3f * s)
                close()
            },
            skin
        )
        // Head.
        drawCircle(skin, 0.52f * s, c + Offset(1.9f * s, -0.05f * s))
        drawCircle(Color(0xFF1E3A30), 0.1f * s, c + Offset(2.1f * s, -0.2f * s))
        drawCircle(Color.White, 0.04f * s, c + Offset(2.13f * s, -0.24f * s))
        drawArc(
            Color(0xFF1E3A30),
            20f,
            110f,
            false,
            c + Offset(1.9f * s, -0.1f * s),
            Size(0.4f * s, 0.3f * s),
            style = Stroke(0.05f * s, cap = StrokeCap.Round)
        )
        // Shell.
        drawOval(Color(0xFF3E8E6A), c - Offset(1.6f * s, 1.05f * s), Size(3.2f * s, 2.1f * s))
        listOf(
            Offset(0f, -0.15f) to 0.45f,
            Offset(-0.85f, 0.1f) to 0.34f,
            Offset(0.85f, 0.1f) to 0.34f,
            Offset(-0.35f, 0.6f) to 0.26f,
            Offset(0.4f, 0.6f) to 0.26f
        ).forEach { (o, rad) ->
            drawCircle(Color(0xFF8FD3A8).copy(alpha = 0.55f), rad * s, c + o * s)
        }
        drawOval(
            Color(0xFFBFE9C9),
            c - Offset(1.6f * s, 1.05f * s),
            Size(3.2f * s, 2.1f * s),
            style = Stroke(0.12f * s)
        )
        // Near-side flippers.
        withTransform({ rotate(20f + paddle * 26f, pivot = c + Offset(0.9f * s, 0.6f * s)) }) {
            drawOval(skin, c + Offset(0.6f * s, 0.5f * s), Size(1.5f * s, 0.62f * s))
        }
        withTransform({ rotate(-15f - paddle * 18f, pivot = c + Offset(-1.0f * s, 0.55f * s)) }) {
            drawOval(skin, c + Offset(-1.9f * s, 0.45f * s), Size(1.1f * s, 0.5f * s))
        }
    }

    // Idle bubbles from the nose.
    val nose = c + Offset((if (faceRight) 2.3f else -2.3f) * s, -0.4f * s)
    for (i in 0 until 2) {
        val cycle = (phase * 0.07f + i * 0.5f) % 1f
        drawCircle(
            Color.White.copy(alpha = (1f - cycle) * 0.5f),
            (0.12f + i * 0.05f) * s,
            nose + Offset(0f, -cycle * 1.8f * s),
            style = Stroke(0.04f * s)
        )
    }

    if (r > 0f) {
        val ringCenter = c + Offset(0f, -r * 2.6f * s)
        for (i in 0 until 10) {
            val a = 2f * PI.toFloat() * i / 10f + r
            val rad = (1.3f + r * 2.2f) * s
            drawCircle(
                Color.White.copy(alpha = (1f - r) * 0.75f),
                (0.2f - 0.07f * r) * s,
                ringCenter + Offset(cos(a) * rad, sin(a) * rad * 0.55f),
                style = Stroke(0.05f * s)
            )
        }
    }
}

/** Forest: an owl gliding with slow wing beats. Tap → fireflies gather into a heart around it. */
internal fun DrawScope.drawGlidingOwl(
    x: Float,
    y: Float,
    scale: Float,
    phase: Float,
    r: Float,
    faceRight: Boolean
) {
    val s = scale * 24f
    val c = Offset(x, y + sin(phase * 0.5f) * 0.3f * s)
    val flap = sin(phase * 0.8f)
    val feather = Color(0xFF8B6A4E)
    val featherDark = Color(0xFF6A4E38)
    val glow = Color(0xFFE6FF8A)

    withTransform({ rotate(if (faceRight) 6f else -6f, pivot = c) }) {
        withTransform({ rotate(-20f - flap * 22f, pivot = c + Offset(-0.8f * s, -0.3f * s)) }) {
            drawOval(featherDark, c + Offset(-2.7f * s, -0.7f * s), Size(2.0f * s, 1.0f * s))
        }
        withTransform({ rotate(20f + flap * 22f, pivot = c + Offset(0.8f * s, -0.3f * s)) }) {
            drawOval(featherDark, c + Offset(0.7f * s, -0.7f * s), Size(2.0f * s, 1.0f * s))
        }
        // Ear tufts.
        listOf(-1f, 1f).forEach { side ->
            drawPath(
                Path().apply {
                    moveTo(c.x + side * 0.35f * s, c.y - 1.1f * s)
                    lineTo(c.x + side * 0.85f * s, c.y - 1.75f * s)
                    lineTo(c.x + side * 0.9f * s, c.y - 0.95f * s)
                    close()
                },
                feather
            )
        }
        drawOval(feather, c + Offset(-1.1f * s, -1.35f * s), Size(2.2f * s, 2.8f * s))
        drawOval(Color(0xFFD9C3A0), c + Offset(-0.7f * s, -0.3f * s), Size(1.4f * s, 1.6f * s))
        for (i in 0 until 3) {
            val vy = c.y + (0.05f + i * 0.35f) * s
            drawArc(
                featherDark.copy(alpha = 0.5f),
                20f,
                140f,
                false,
                Offset(c.x - 0.18f * s, vy),
                Size(0.36f * s, 0.2f * s),
                style = Stroke(0.05f * s, cap = StrokeCap.Round)
            )
        }
        // Eyes: slow blink when idle, wide awake during a tap reaction.
        val blinking = r == 0f && (phase % 14f) < 0.6f
        listOf(-1f, 1f).forEach { side ->
            val eye = c + Offset(side * 0.45f * s, -0.7f * s)
            drawCircle(Color.White, 0.45f * s, eye)
            if (blinking) {
                drawArc(
                    featherDark,
                    0f,
                    180f,
                    false,
                    eye - Offset(0.3f * s, 0.15f * s),
                    Size(0.6f * s, 0.3f * s),
                    style = Stroke(0.08f * s, cap = StrokeCap.Round)
                )
            } else {
                drawCircle(Color(0xFFFFC857), 0.3f * s, eye)
                drawCircle(Color(0xFF2A1E14), (0.15f + swell(r) * 0.06f) * s, eye)
                drawCircle(Color.White, 0.05f * s, eye + Offset(0.07f * s, -0.08f * s))
            }
        }
        drawPath(
            Path().apply {
                moveTo(c.x - 0.14f * s, c.y - 0.42f * s)
                lineTo(c.x + 0.14f * s, c.y - 0.42f * s)
                lineTo(c.x, c.y - 0.1f * s)
                close()
            },
            Color(0xFFFFA64D)
        )
        drawOval(Color(0xFFFFA64D), c + Offset(-0.5f * s, 1.35f * s), Size(0.35f * s, 0.18f * s))
        drawOval(Color(0xFFFFA64D), c + Offset(0.15f * s, 1.35f * s), Size(0.35f * s, 0.18f * s))
    }

    // Fireflies: orbit idly, gather into a heart on tap.
    val n = if (r > 0f) 12 else 4
    val gather = swell(r)
    for (i in 0 until n) {
        val scatter = c + Offset(
            cos(i * 2.1f + phase * 0.12f) * 2.6f * s,
            sin(i * 1.7f + phase * 0.1f) * 2.0f * s
        )
        val heart = c + Offset(0f, -0.2f * s) + heartPoint(2f * PI.toFloat() * i / n) * (2.3f * s)
        val p = Offset(
            scatter.x + (heart.x - scatter.x) * gather,
            scatter.y + (heart.y - scatter.y) * gather
        )
        val twinkle = 0.55f + 0.45f * sin(phase * 0.9f + i * 1.3f)
        drawCircle(glow.copy(alpha = 0.28f * twinkle), 0.32f * s, p)
        drawCircle(Color.White.copy(alpha = 0.9f * twinkle), 0.1f * s, p)
    }
}

/** Rainbow: a smiling cloud trailing a soft rainbow. Tap → rainbow arc and a shower of pastel hearts. */
internal fun DrawScope.drawRainbowCloud(
    x: Float,
    y: Float,
    scale: Float,
    phase: Float,
    r: Float,
    faceRight: Boolean
) {
    val s = scale * 24f
    val dir = if (faceRight) 1f else -1f
    val c = Offset(x, y + sin(phase * 0.55f) * 0.25f * s)

    // Soft rainbow tail streaming behind.
    pastelBands.forEachIndexed { i, band ->
        val tail = Path()
        for (j in 0..16) {
            val t = j / 16f
            val px = c.x - dir * (1.6f + t * 5.0f) * s
            val py = c.y + (i - 2.5f) * 0.17f * s + sin(phase * 0.6f + t * 3f) * 0.3f * s * t
            if (j == 0) tail.moveTo(px, py) else tail.lineTo(px, py)
        }
        drawPath(
            tail,
            band.copy(alpha = 0.55f - i * 0.03f),
            style = Stroke(width = 0.17f * s, cap = StrokeCap.Round)
        )
    }

    if (r > 0f) {
        val pop = swell(r)
        pastelBands.forEachIndexed { i, band ->
            val rad = (2.5f - i * 0.17f) * s
            drawArc(
                band.copy(alpha = 0.6f * pop),
                180f,
                180f,
                false,
                Offset(c.x - rad, c.y + 0.2f * s - rad),
                Size(rad * 2f, rad * 2f),
                style = Stroke(0.16f * s)
            )
        }
    }

    val puffs = listOf(
        Offset(-1.1f, 0.2f) to 0.9f,
        Offset(-0.2f, -0.4f) to 1.2f,
        Offset(0.9f, 0.1f) to 0.95f
    )
    val outline = Color(0xFF9C8CC8).copy(alpha = 0.35f)
    drawOval(Color.Black.copy(alpha = 0.08f), c + Offset(-1.7f * s, 1.35f * s), Size(3.4f * s, 0.5f * s))
    puffs.forEach { (o, rad) -> drawCircle(outline, (rad + 0.07f) * s, c + o * s) }
    drawRoundRect(
        outline,
        topLeft = c + Offset(-1.97f * s, -0.07f * s),
        size = Size(3.94f * s, 1.24f * s),
        cornerRadius = CornerRadius(0.62f * s, 0.62f * s)
    )
    puffs.forEach { (o, rad) -> drawCircle(Color.White, rad * s, c + o * s) }
    drawRoundRect(
        Color.White,
        topLeft = c + Offset(-1.9f * s, 0f),
        size = Size(3.8f * s, 1.1f * s),
        cornerRadius = CornerRadius(0.55f * s, 0.55f * s)
    )

    val face = c + Offset(dir * 0.2f * s, 0.15f * s)
    listOf(-0.4f, 0.4f).forEach { ex ->
        drawArc(
            Color(0xFF6A5A8A),
            180f,
            180f,
            false,
            face + Offset((ex - 0.14f) * s, -0.2f * s),
            Size(0.28f * s, 0.2f * s),
            style = Stroke(0.07f * s, cap = StrokeCap.Round)
        )
        drawCircle(Color(0xFFFFB3C7).copy(alpha = 0.6f), 0.13f * s, face + Offset(ex * 1.4f * s, 0.12f * s))
    }
    drawArc(
        Color(0xFF6A5A8A),
        20f,
        140f,
        false,
        face + Offset(-0.2f * s, 0.0f),
        Size(0.4f * s, 0.3f * s),
        style = Stroke(0.07f * s, cap = StrokeCap.Round)
    )

    if (r > 0f) {
        for (i in 0 until 8) {
            val hx = x + (i - 3.5f) * 0.6f * s
            val hy = c.y + 1.2f * s + r * (2.3f + (i % 3) * 0.5f) * s
            drawHeart(
                Offset(hx + sin(r * 6f + i) * 0.2f * s, hy),
                0.24f * s,
                pastelBands[i % pastelBands.size].copy(alpha = (1f - r) * 0.9f)
            )
        }
    }
}
