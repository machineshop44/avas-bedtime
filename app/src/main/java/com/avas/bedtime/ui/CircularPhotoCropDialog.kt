package com.avas.bedtime.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Full-screen crop overlay (not a Dialog). Dialogs on some phones (Pixel) ignore
 * window insets and push Cancel / Use photo off-screen.
 */
@Composable
fun CircularPhotoCropOverlay(
    source: Bitmap,
    onCancel: () -> Unit,
    onCropped: (Bitmap) -> Unit
) {
    BackHandler(onBack = onCancel)

    val imageBitmap = remember(source) { source.asImageBitmap() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val cropDiameterDp = 280.dp
    val cropDiameterPx = with(density) { cropDiameterDp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101820))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Text(
            "Pinch to zoom in or out, drag to frame the face",
            color = Color(0xFFF2E8D5),
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.35f, 6f)
                        offset += pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val hole = ComposePath().apply {
                    addOval(
                        ComposeRect(
                            left = (size.width - cropDiameterPx) / 2f,
                            top = (size.height - cropDiameterPx) / 2f,
                            right = (size.width + cropDiameterPx) / 2f,
                            bottom = (size.height + cropDiameterPx) / 2f
                        )
                    )
                }
                clipPath(hole, clipOp = ClipOp.Difference) {
                    drawRect(Color(0x99000000))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101820))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) { Text("Cancel") }
            Button(
                onClick = {
                    val cropped = cropToCircle(
                        source = source,
                        viewport = viewport,
                        scale = scale,
                        offset = offset,
                        cropDiameterPx = cropDiameterPx
                    )
                    onCropped(cropped)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) { Text("Use photo") }
        }
    }
}

internal fun cropToCircle(
    source: Bitmap,
    viewport: IntSize,
    scale: Float,
    offset: Offset,
    cropDiameterPx: Float
): Bitmap {
    val outSize = 512
    val out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    val clip = Path().apply {
        addCircle(outSize / 2f, outSize / 2f, outSize / 2f, Path.Direction.CW)
    }
    canvas.clipPath(clip)

    if (viewport.width <= 0 || viewport.height <= 0) {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        canvas.drawBitmap(
            source,
            Rect(left, top, left + side, top + side),
            RectF(0f, 0f, outSize.toFloat(), outSize.toFloat()),
            paint
        )
        return out
    }

    val viewW = viewport.width.toFloat()
    val viewH = viewport.height.toFloat()
    val imgW = source.width.toFloat()
    val imgH = source.height.toFloat()
    val fit = min(viewW / imgW, viewH / imgH)
    val drawnW = imgW * fit
    val drawnH = imgH * fit
    val baseLeft = (viewW - drawnW) / 2f
    val baseTop = (viewH - drawnH) / 2f
    val centerX = viewW / 2f
    val centerY = viewH / 2f
    val radius = cropDiameterPx / 2f

    fun viewToImage(vx: Float, vy: Float): Pair<Float, Float> {
        val dx = vx - centerX
        val dy = vy - centerY
        val unscaledX = centerX + (dx - offset.x) / scale
        val unscaledY = centerY + (dy - offset.y) / scale
        val ix = (unscaledX - baseLeft) / fit
        val iy = (unscaledY - baseTop) / fit
        return ix to iy
    }

    val (cx, cy) = viewToImage(centerX, centerY)
    val (edgeX, _) = viewToImage(centerX + radius, centerY)
    val imageRadius = kotlin.math.abs(edgeX - cx).coerceAtLeast(1f)

    val srcRect = Rect(
        (cx - imageRadius).toInt().coerceIn(0, source.width),
        (cy - imageRadius).toInt().coerceIn(0, source.height),
        (cx + imageRadius).toInt().coerceIn(0, source.width),
        (cy + imageRadius).toInt().coerceIn(0, source.height)
    )
    if (srcRect.width() <= 0 || srcRect.height() <= 0) {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        canvas.drawBitmap(
            source,
            Rect(left, top, left + side, top + side),
            RectF(0f, 0f, outSize.toFloat(), outSize.toFloat()),
            paint
        )
    } else {
        canvas.drawBitmap(
            source,
            srcRect,
            RectF(0f, 0f, outSize.toFloat(), outSize.toFloat()),
            paint
        )
    }
    return out
}
