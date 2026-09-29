package com.avas.bedtime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.avas.bedtime.ui.theme.BedtimeThemeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Picture labels so a pre-reader can tell the big buttons apart. */
enum class KidIcon { Moon, Restart, Stop, Note }

@Composable
fun KidIconView(icon: KidIcon, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        drawKidIcon(icon, color, center, this.size.minDimension)
    }
}

fun DrawScope.drawKidIcon(icon: KidIcon, color: Color, center: Offset, size: Float) {
    when (icon) {
        KidIcon.Moon -> drawCrescent(center, size * 0.46f, color)
        KidIcon.Restart -> {
            val stroke = size * 0.13f
            val r = size * 0.36f
            val start = -60f
            val sweep = 290f
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Arrow head at the arc's end, pointing along the direction of travel.
            val endRad = Math.toRadians((start + sweep).toDouble())
            val tip = Offset(
                center.x + (cos(endRad) * r).toFloat(),
                center.y + (sin(endRad) * r).toFloat()
            )
            val tangent = endRad + PI / 2.0
            val head = size * 0.2f
            val forward = Offset((cos(tangent) * head).toFloat(), (sin(tangent) * head).toFloat())
            val side = Offset((cos(endRad) * head * 0.75f).toFloat(), (sin(endRad) * head * 0.75f).toFloat())
            val arrow = Path().apply {
                moveTo(tip.x + forward.x, tip.y + forward.y)
                lineTo(tip.x + side.x, tip.y + side.y)
                lineTo(tip.x - side.x, tip.y - side.y)
                close()
            }
            drawPath(arrow, color)
        }
        KidIcon.Stop -> {
            val side = size * 0.62f
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                size = Size(side, side),
                cornerRadius = CornerRadius(side * 0.22f, side * 0.22f)
            )
        }
        KidIcon.Note -> {
            val head = size * 0.17f
            val headCenter = Offset(center.x - size * 0.1f, center.y + size * 0.26f)
            drawOval(
                color = color,
                topLeft = Offset(headCenter.x - head * 1.25f, headCenter.y - head),
                size = Size(head * 2.5f, head * 2f)
            )
            val stemX = headCenter.x + head * 1.1f
            val stemTop = center.y - size * 0.38f
            drawLine(
                color = color,
                start = Offset(stemX, headCenter.y),
                end = Offset(stemX, stemTop),
                strokeWidth = size * 0.08f,
                cap = StrokeCap.Round
            )
            val flag = Path().apply {
                moveTo(stemX, stemTop)
                quadraticBezierTo(stemX + size * 0.3f, stemTop + size * 0.12f, stemX + size * 0.22f, stemTop + size * 0.36f)
                quadraticBezierTo(stemX + size * 0.18f, stemTop + size * 0.2f, stemX, stemTop + size * 0.16f)
                close()
            }
            drawPath(flag, color)
        }
    }
}

private fun DrawScope.drawCrescent(center: Offset, radius: Float, color: Color) {
    val outer = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(center, radius)
        )
    }
    val bite = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                Offset(center.x + radius * 0.45f, center.y - radius * 0.3f),
                radius * 0.92f
            )
        )
    }
    val crescent = Path().apply { op(outer, bite, PathOperation.Difference) }
    drawPath(crescent, color)
}

/**
 * Dotted arc from a sleepy moon (bedtime) to a sun (wake-up) with a star that
 * travels along it. [progress] is 0 at the start of the night and 1 at wake time.
 */
@Composable
fun NightPathArc(
    progress: Float,
    colors: BedtimeThemeColors,
    widthFraction: Float = 1f,
    modifier: Modifier = Modifier
) {
    val p = progress.coerceIn(0f, 1f)
    val trackColor = colors.subtitle
    val moonColor = Color(0xFFF2E6C8)
    val nearMorning = p >= 0.9f
    val sunColor = if (nearMorning) Color(0xFFFFC857) else colors.subtitle.copy(alpha = 0.45f)
    val starColor = Color(0xFFFFE9A8)
    Canvas(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .aspectRatio(2.4f)
            .semantics {
                contentDescription = "Night is ${(p * 100).toInt()} percent over"
            }
    ) {
        val w = size.width
        val h = size.height
        val iconR = min(w, h) * 0.14f
        val cx = w / 2f
        val cy = h - iconR * 1.1f
        val r = min(w / 2f - iconR * 1.6f, cy - iconR * 0.8f)
        fun pointAt(t: Float): Offset {
            val a = PI + PI * t
            return Offset(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat())
        }

        val dots = 26
        for (i in 0..dots) {
            val t = i / dots.toFloat()
            val passed = t <= p
            drawCircle(
                color = trackColor.copy(alpha = if (passed) 0.75f else 0.28f),
                radius = if (passed) iconR * 0.13f else iconR * 0.09f,
                center = pointAt(t)
            )
        }

        // Moon (bedtime) on the left.
        val moonAt = pointAt(0f)
        drawCrescent(moonAt, iconR, moonColor.copy(alpha = if (p < 0.5f) 0.95f else 0.6f))

        // Sun (morning) on the right; stays dim until the last stretch of the night.
        val sunAt = pointAt(1f)
        drawCircle(color = sunColor, radius = iconR * 0.62f, center = sunAt)
        for (i in 0 until 8) {
            val a = i * PI / 4.0
            val inner = iconR * 0.85f
            val outer = iconR * 1.15f
            drawLine(
                color = sunColor,
                start = Offset(sunAt.x + (cos(a) * inner).toFloat(), sunAt.y + (sin(a) * inner).toFloat()),
                end = Offset(sunAt.x + (cos(a) * outer).toFloat(), sunAt.y + (sin(a) * outer).toFloat()),
                strokeWidth = iconR * 0.14f,
                cap = StrokeCap.Round
            )
        }

        // The traveling star with a soft glow.
        val starAt = pointAt(p)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(starColor.copy(alpha = 0.45f), Color.Transparent),
                center = starAt,
                radius = iconR * 1.3f
            ),
            radius = iconR * 1.3f,
            center = starAt
        )
        drawStar(starAt, iconR * 0.62f, starColor)
    }
}
