package com.avas.bedtime.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.avas.bedtime.R
import com.avas.bedtime.ui.theme.AppThemeId
import com.avas.bedtime.ui.theme.BedtimeThemeColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Occasional themed passerby — unicorn dash, shooting star, fish, etc.
 *
 * Unicorn art: Public Domain (CC0) pink unicorn from FreeSVG / publicdomainvectors.org
 * (https://freesvg.org/unicorn-vector-clipart-pdv), recolored for Ava Bedtime.
 *
 * Unicorn normally trots. Tap it: neigh + rainbow, then weave left/right across
 * the screen for a couple seconds before flying off.
 */
@Composable
fun ThemePasserby(
    colors: BedtimeThemeColors,
    avaPhotoPath: String = ""
) {
    val progress = remember { Animatable(-0.3f) }
    var active by remember { mutableStateOf(false) }
    var startLane by remember { mutableFloatStateOf(0.28f) }
    var endLane by remember { mutableFloatStateOf(0.34f) }
    var goingRight by remember { mutableStateOf(true) }
    var flying by remember { mutableStateOf(false) }
    /** When flying, [progress] is raw screen X fraction; when trotting it's pass progress. */
    var flyUsesRawX by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val neighPlayer = rememberUnicornNeighPlayer()
    val flyBoost = remember { Channel<Unit>(Channel.CONFLATED) }
    val unicornSprite = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.passerby_unicorn)
            .asImageBitmap()
    }
    val riderFace = remember(avaPhotoPath) {
        if (avaPhotoPath.isBlank()) {
            null
        } else {
            runCatching {
                com.avas.bedtime.data.AvaPhotoStore
                    .decodeFileForDisplay(avaPhotoPath, maxEdge = 256)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }

    LaunchedEffect(colors.id) {
        active = false
        flying = false
        flyUsesRawX = false
        delay(Random.nextLong(2_500L, 6_000L))
        while (true) {
            // Idle passes always trot — flying is tap-only.
            flying = false
            flyUsesRawX = false
            goingRight = Random.nextBoolean()

            val bands = listOf(
                0.14f to 0.26f,
                0.22f to 0.34f,
                0.30f to 0.42f,
                0.18f to 0.38f,
                0.26f to 0.48f,
                0.12f to 0.20f
            )
            val band = bands.random()
            startLane = band.first + Random.nextFloat() * (band.second - band.first) * 0.35f
            endLane = band.first + Random.nextFloat() * (band.second - band.first)
            if (Random.nextFloat() < 0.45f) {
                endLane = (endLane + Random.nextFloat() * 0.14f - 0.07f).coerceIn(0.08f, 0.55f)
            }

            // Drop any stale tap from a previous pass.
            while (flyBoost.tryReceive().isSuccess) { /* drain */ }

            progress.snapTo(-0.35f)
            active = true
            val trotMs = Random.nextInt(6_800, 9_200)
            val trotJob = launch {
                progress.animateTo(
                    targetValue = 1.35f,
                    animationSpec = tween(durationMillis = trotMs, easing = LinearEasing)
                )
            }
            val boosted = select {
                trotJob.onJoin { false }
                flyBoost.onReceive {
                    true
                }
            }
            if (boosted) {
                trotJob.cancel()
                val curProgress = progress.value
                val curX = (if (goingRight) curProgress else (1f - curProgress))
                    .coerceIn(0.08f, 0.92f)
                val curT = ((curProgress + 0.35f) / 1.7f).coerceIn(0f, 1f)
                val curY = startLane + (endLane - startLane) * curT

                flying = true
                flyUsesRawX = true
                // Hold height roughly steady while weaving left/right.
                startLane = curY
                endLane = curY
                progress.snapTo(curX)

                // Random left↔right crossings for a couple seconds, then exit off-screen.
                val crossings = Random.nextInt(3, 5)
                var preferRight = curX < 0.5f
                val waypoints = ArrayList<Float>(crossings + 1)
                for (i in 0 until crossings) {
                    preferRight = if (i == 0) preferRight else !preferRight
                    // Occasionally flip again so it isn't a strict ping-pong.
                    if (i > 0 && Random.nextFloat() < 0.28f) preferRight = !preferRight
                    val target = if (preferRight) {
                        0.78f + Random.nextFloat() * 0.14f
                    } else {
                        0.08f + Random.nextFloat() * 0.14f
                    }
                    waypoints.add(target)
                }
                val last = waypoints.last()
                waypoints.add(if (last >= 0.5f) 1.35f else -0.35f)

                for (target in waypoints) {
                    goingRight = target >= progress.value
                    val dist = abs(target - progress.value)
                    val ms = (dist * Random.nextInt(1_050, 1_450))
                        .toInt()
                        .coerceIn(450, 1_700)
                    progress.animateTo(
                        targetValue = target,
                        animationSpec = tween(durationMillis = ms, easing = LinearEasing)
                    )
                }
            }
            active = false
            flying = false
            flyUsesRawX = false
            delay(Random.nextLong(12_000L, 28_000L))
        }
    }

    if (!active) return

    val progressValue = progress.value
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val t = ((progressValue + 0.35f) / 1.7f).coerceIn(0f, 1f)
        val xFrac = if (flyUsesRawX) {
            progressValue
        } else if (goingRight) {
            progressValue
        } else {
            1f - progressValue
        }
        val yFrac = startLane + (endLane - startLane) * (if (flyUsesRawX) 0f else t)
        val x = screenW * xFrac
        val y = screenH * yFrac
        val scale = with(density) { 1.dp.toPx() }
        val phase = progressValue * 28f
        val s = scale * 40f
        val drawW = 5.6f * s
        val drawH = drawW * (unicornSprite.height.toFloat() / unicornSprite.width.toFloat())

        Canvas(modifier = Modifier.fillMaxSize()) {
            when (colors.id) {
                AppThemeId.Unicorn -> drawUnicornSprite(
                    sprite = unicornSprite,
                    riderFace = riderFace,
                    x = x,
                    y = y,
                    scale = scale,
                    phase = phase,
                    flying = flying,
                    faceRight = goingRight
                )
                AppThemeId.Night, AppThemeId.Galaxy -> drawShootingStar(x, y, scale, colors)
                AppThemeId.Ocean -> drawFish(x, y, scale, colors, phase)
                AppThemeId.Forest -> drawFireflyTrail(x, y, scale, colors, phase)
                AppThemeId.Rainbow -> drawSoftBalloon(x, y, scale, colors, phase)
            }
        }

        // Invisible moving hit target — only covers the unicorn, so START stays tappable.
        if (colors.id == AppThemeId.Unicorn) {
            val hitW = with(density) { (drawW * 1.05f).toDp() }
            val hitH = with(density) { (drawH * 1.05f).toDp() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (x - drawW * 0.52f).roundToInt(),
                            (y - drawH * 0.52f).roundToInt()
                        )
                    }
                    .size(hitW, hitH)
                    .semantics { contentDescription = "Unicorn" }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        neighPlayer.play()
                        if (!flying) {
                            flyBoost.trySend(Unit)
                        }
                    }
            )
        }
    }
}

private fun DrawScope.drawUnicornSprite(
    sprite: ImageBitmap,
    riderFace: ImageBitmap?,
    x: Float,
    y: Float,
    scale: Float,
    phase: Float,
    flying: Boolean,
    faceRight: Boolean
) {
    val s = scale * 40f
    val trot = sin(phase * 1.25f)
    val bob = if (flying) {
        sin(phase * 0.55f) * 0.28f * s
    } else {
        abs(trot) * 0.10f * s
    }
    val cy = y - bob
    val rock = if (flying) {
        (if (faceRight) -6f else 6f) + sin(phase * 0.45f) * 3.5f
    } else {
        trot * (if (faceRight) 3.2f else -3.2f)
    }

    val drawW = 5.6f * s
    val drawH = drawW * (sprite.height.toFloat() / sprite.width.toFloat())

    if (flying) {
        val hoofY = cy + drawH * 0.36f
        // Glittery rainbow wake only — no underfoot bridge (that felt like overkill).
        drawRainbowGlitterTrail(
            hoofX = x + (if (faceRight) -1f else 1f) * drawW * 0.28f,
            hoofY = hoofY,
            s = s,
            phase = phase,
            faceRight = faceRight
        )
    } else {
        val shadowY = cy + drawH * 0.42f
        drawOval(
            color = Color(0xFF5E3A68).copy(alpha = 0.14f),
            topLeft = Offset(x - drawW * 0.38f, shadowY),
            size = Size(drawW * 0.72f, 0.42f * s)
        )
    }

    // Sprite faces left in the asset; flip when traveling right.
    val flipX = if (faceRight) -1f else 1f
    withTransform({
        translate(left = x, top = cy)
        rotate(degrees = rock, pivot = Offset.Zero)
        scale(scaleX = flipX, scaleY = 1f, pivot = Offset.Zero)
    }) {
        drawImage(
            image = sprite,
            dstOffset = IntOffset((-drawW / 2f).toInt(), (-drawH / 2f).toInt()),
            dstSize = IntSize(drawW.toInt().coerceAtLeast(1), drawH.toInt().coerceAtLeast(1))
        )
        if (riderFace != null) {
            drawLittleRider(
                face = riderFace,
                seat = Offset(drawW * 0.04f, -drawH * 0.20f),
                s = s * 1.35f
            )
        }
    }
}

private val rainbowBands = listOf(
    Color(0xFFFF6B8A),
    Color(0xFFFFB04A),
    Color(0xFFFFE066),
    Color(0xFF7AD88A),
    Color(0xFF6EC8FF),
    Color(0xFFB48CFF)
)

/**
 * Sparkly rainbow wake behind a flying unicorn — streams back & up from near the rear hoof.
 */
private fun DrawScope.drawRainbowGlitterTrail(
    hoofX: Float,
    hoofY: Float,
    s: Float,
    phase: Float,
    faceRight: Boolean
) {
    val dir = if (faceRight) -1f else 1f
    // Start a little behind the rear hoof on the rainbow — gap so it doesn't touch the body.
    val originX = hoofX + dir * 0.55f * s
    val originY = hoofY - 0.08f * s

    fun trailPoint(t: Float, wave: Float = 1f): Offset {
        val lift = t * t * 1.55f * s // rises more as it goes back
        val along = (0.2f + t * 6.2f) * s
        val wiggle = sin(phase * 0.85f + t * 4.0f) * (0.1f + t * 0.35f) * s * wave
        return Offset(originX + dir * along, originY - lift + wiggle)
    }

    // Soft outer glow ribbon.
    val glow = Path()
    for (i in 0 until 18) {
        val p = trailPoint(i / 17f, 0.7f)
        if (i == 0) glow.moveTo(p.x, p.y) else glow.lineTo(p.x, p.y)
    }
    drawPath(
        glow,
        Color.White.copy(alpha = 0.18f),
        style = Stroke(width = 0.55f * s, cap = StrokeCap.Round)
    )

    // Separate color ribbons with slight offsets so bands read clearly (not one fat blob).
    rainbowBands.forEachIndexed { i, color ->
        val band = Path()
        val lateral = (i - 2.5f) * 0.07f * s
        for (j in 0 until 18) {
            val t = j / 17f
            val p = trailPoint(t, 1f + i * 0.04f)
            val px = p.x
            val py = p.y + lateral * (1f - t * 0.4f)
            if (j == 0) band.moveTo(px, py) else band.lineTo(px, py)
        }
        val strokeW = (0.20f - i * 0.016f).coerceAtLeast(0.08f) * s
        drawPath(
            band,
            color.copy(alpha = (0.68f - i * 0.055f).coerceAtLeast(0.22f)),
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
        // Tip tapers thinner.
        val tip = Path()
        for (j in 10 until 18) {
            val t = j / 17f
            val p = trailPoint(t, 1f + i * 0.04f)
            val py = p.y + lateral * (1f - t * 0.4f)
            if (j == 10) tip.moveTo(p.x, py) else tip.lineTo(p.x, py)
        }
        drawPath(
            tip,
            color.copy(alpha = 0.38f - i * 0.03f),
            style = Stroke(width = strokeW * 0.5f, cap = StrokeCap.Round)
        )
    }

    // Bright white core through the middle of the wake.
    val core = Path()
    for (i in 0 until 18) {
        val p = trailPoint(i / 17f)
        if (i == 0) core.moveTo(p.x, p.y) else core.lineTo(p.x, p.y)
    }
    drawPath(core, Color.White.copy(alpha = 0.42f), style = Stroke(width = 0.09f * s, cap = StrokeCap.Round))

    // Dense glitter: stars, dots, little diamond flecks along the wake.
    for (i in 0 until 20) {
        val t = (i + 0.4f) / 20f
        val p = trailPoint(t, 1.1f)
        val drift = Offset(
            cos(phase * 1.2f + i * 1.7f).toFloat() * 0.22f * s,
            sin(phase * 0.9f + i) * 0.18f * s
        )
        val c = rainbowBands[i % rainbowBands.size]
        val fade = (0.9f - t * 0.65f)
        val r = (0.16f - t * 0.09f) * s
        when (i % 3) {
            0 -> drawStar(p + drift, r, c.copy(alpha = fade))
            1 -> {
                drawCircle(Color.White.copy(alpha = fade * 0.85f), r * 0.45f, p + drift)
                drawCircle(c.copy(alpha = fade * 0.7f), r * 0.28f, p + drift * 0.6f)
            }
            else -> {
                // Tiny diamond fleck.
                val d = Path().apply {
                    val q = p + drift
                    moveTo(q.x, q.y - r)
                    lineTo(q.x + r * 0.55f, q.y)
                    lineTo(q.x, q.y + r)
                    lineTo(q.x - r * 0.55f, q.y)
                    close()
                }
                drawPath(d, c.copy(alpha = fade))
            }
        }
    }
}

/**
 * Side-view little kid on the saddle: Ava's photo face, dress, arms on mane, dangling legs.
 */
private fun DrawScope.drawLittleRider(
    face: ImageBitmap,
    seat: Offset,
    s: Float
) {
    val skin = Color(0xFFFFCDB8)
    val dress = Color(0xFFFF6FAF)
    val dressDark = Color(0xFFD9448E)
    val dressLight = Color(0xFFFFC0DC)
    val sleeve = Color(0xFFFF8FC0)
    val shoe = Color(0xFF4E2F62)
    val sock = Color(0xFFFFF6FB)
    val outline = Color(0xFF5C2E5E)

    // Slightly big kid head — still connected to a readable body.
    val headR = 0.42f * s
    val head = Offset(seat.x - 0.08f * s, seat.y - 0.92f * s)
    val neckBottom = Offset(head.x + 0.02f * s, head.y + headR * 0.95f)
    val shoulder = Offset(seat.x + 0.02f * s, seat.y - 0.38f * s)
    val hip = Offset(seat.x + 0.12f * s, seat.y + 0.08f * s)

    // Far leg (drawn first), bent over the flank.
    val farKnee = Offset(hip.x + 0.48f * s, hip.y + 0.42f * s)
    val farAnkle = Offset(farKnee.x + 0.05f * s, farKnee.y + 0.34f * s)
    drawLine(skin, hip + Offset(0.06f * s, 0.04f * s), farKnee, 0.13f * s, StrokeCap.Round)
    drawLine(skin, farKnee, farAnkle, 0.11f * s, StrokeCap.Round)
    drawOval(sock, Offset(farAnkle.x - 0.08f * s, farAnkle.y - 0.04f * s), Size(0.16f * s, 0.1f * s))
    drawOval(shoe, Offset(farAnkle.x - 0.1f * s, farAnkle.y), Size(0.24f * s, 0.11f * s))

    // Near leg — more forward so both read as legs.
    val knee = Offset(hip.x + 0.62f * s, hip.y + 0.28f * s)
    val ankle = Offset(knee.x + 0.08f * s, knee.y + 0.4f * s)
    drawLine(skin, hip, knee, 0.15f * s, StrokeCap.Round)
    drawLine(skin, knee, ankle, 0.12f * s, StrokeCap.Round)
    drawOval(sock, Offset(ankle.x - 0.09f * s, ankle.y - 0.05f * s), Size(0.18f * s, 0.11f * s))
    drawOval(shoe, Offset(ankle.x - 0.12f * s, ankle.y - 0.01f * s), Size(0.28f * s, 0.12f * s))

    // A-line dress: narrow shoulders → wide hem over the seat.
    val dressPath = Path().apply {
        moveTo(shoulder.x - 0.18f * s, shoulder.y)
        lineTo(shoulder.x + 0.22f * s, shoulder.y + 0.02f * s)
        cubicTo(
            shoulder.x + 0.38f * s, hip.y - 0.12f * s,
            hip.x + 0.55f * s, hip.y + 0.05f * s,
            hip.x + 0.5f * s, hip.y + 0.32f * s
        )
        quadraticTo(hip.x + 0.1f * s, hip.y + 0.38f * s, hip.x - 0.38f * s, hip.y + 0.3f * s)
        cubicTo(
            hip.x - 0.42f * s, hip.y + 0.02f * s,
            shoulder.x - 0.28f * s, shoulder.y + 0.12f * s,
            shoulder.x - 0.18f * s, shoulder.y
        )
        close()
    }
    drawPath(dressPath, dress)
    drawPath(
        Path().apply {
            moveTo(shoulder.x - 0.02f * s, shoulder.y + 0.1f * s)
            quadraticTo(hip.x + 0.05f * s, hip.y + 0.02f * s, hip.x + 0.22f * s, hip.y + 0.26f * s)
        },
        dressLight.copy(alpha = 0.65f),
        style = Stroke(width = 0.07f * s, cap = StrokeCap.Round)
    )
    // Hem stitch line.
    drawLine(
        dressDark.copy(alpha = 0.55f),
        Offset(hip.x - 0.34f * s, hip.y + 0.28f * s),
        Offset(hip.x + 0.46f * s, hip.y + 0.3f * s),
        0.045f * s,
        StrokeCap.Round
    )
    drawPath(dressPath, outline.copy(alpha = 0.45f), style = Stroke(0.04f * s))

    // Sleeves + arms reaching to the mane (clear “holding on”).
    val elbow = Offset(shoulder.x - 0.42f * s, shoulder.y + 0.18f * s)
    val hand = Offset(shoulder.x - 0.88f * s, shoulder.y + 0.02f * s)
    drawLine(sleeve, shoulder + Offset(-0.1f * s, 0.05f * s), elbow, 0.14f * s, StrokeCap.Round)
    drawLine(skin, elbow, hand, 0.11f * s, StrokeCap.Round)
    drawCircle(skin, 0.09f * s, hand)
    // Far arm tucked.
    drawLine(
        sleeve.copy(alpha = 0.85f),
        shoulder + Offset(0.1f * s, 0.08f * s),
        Offset(seat.x - 0.5f * s, seat.y + 0.05f * s),
        0.12f * s,
        StrokeCap.Round
    )

    // Short neck.
    drawRoundRect(
        skin,
        Offset(neckBottom.x - 0.06f * s, neckBottom.y - 0.02f * s),
        Size(0.12f * s, 0.14f * s),
        CornerRadius(0.04f * s, 0.04f * s)
    )

    // Face photo in a white ring (photo already has hair — no drawn pigtails).
    drawCircle(Color.White, headR + 0.045f * s, head)
    clipPath(
        Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    left = head.x - headR,
                    top = head.y - headR,
                    right = head.x + headR,
                    bottom = head.y + headR
                )
            )
        }
    ) {
        val faceSize = (headR * 2f).toInt().coerceAtLeast(8)
        drawImage(
            face,
            dstOffset = IntOffset((head.x - headR).toInt(), (head.y - headR).toInt()),
            dstSize = IntSize(faceSize, faceSize)
        )
    }
    drawCircle(outline.copy(alpha = 0.55f), headR, head, style = Stroke(0.04f * s))
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
