package com.weback.robot.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.weback.robot.R
import com.weback.robot.domain.map.RasterSnapshot
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_ZOOM_SCALE: Float = 0.5f
private const val MAX_ZOOM_SCALE: Float = 8.0f
private const val ZOOM_STEP: Float = 0.5f
private const val PAN_STEP_PX: Float = 24f

/**
 * Converts a validated unsigned 8-bit grayscale [RasterSnapshot] into ARGB pixels `(v, v, v, 255)`
 * directly without decompression or coordinate transforms (`AC-503.2`, `AC-505.1`).
 */
internal fun RasterSnapshot.toArgbPixels(): IntArray {
    val raw = copyPixels()
    val argb = IntArray(raw.size)
    for (i in raw.indices) {
        val v = raw[i].toInt() and 0xFF
        argb[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return argb
}

internal fun RasterSnapshot.toGrayscaleImageBitmap(): ImageBitmap {
    val argb = toArgbPixels()
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Cached grayscale [ImageBitmap] renderer keyed strictly by [RasterSnapshot.frameGeneration]
 * with purely visual pan/zoom/reset controls and zero unverified pose/trajectory overlays (`LF-010`).
 */
@Composable
fun MapCanvas(
    snapshot: RasterSnapshot?,
    modifier: Modifier = Modifier,
    onBitmapCreatedForGeneration: ((Long) -> Unit)? = null
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offsetX by remember { mutableFloatStateOf(0.0f) }
    var offsetY by remember { mutableFloatStateOf(0.0f) }

    val cachedBitmap = remember(snapshot?.frameGeneration) {
        snapshot?.let { snap ->
            onBitmapCreatedForGeneration?.invoke(snap.frameGeneration)
            snap.toGrayscaleImageBitmap()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            if (snapshot == null || cachedBitmap == null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("map_empty_placeholder")
                        .semantics {
                            contentDescription = "No valid map raster received"
                        }
                ) {
                    Text(
                        text = stringResource(id = R.string.local_map_no_frame),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val canvasDesc =
                    "Local HOSLAM map raster ${snapshot.width} by ${snapshot.height}, generation ${snapshot.frameGeneration}"
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("map_canvas")
                        .semantics { contentDescription = canvasDesc }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                ) {
                    val canvasW = size.width
                    val canvasH = size.height
                    if (canvasW > 0f && canvasH > 0f) {
                        val fitScale = minOf(canvasW / snapshot.width, canvasH / snapshot.height)
                        val dstW = (snapshot.width * fitScale).roundToInt().coerceAtLeast(1)
                        val dstH = (snapshot.height * fitScale).roundToInt().coerceAtLeast(1)
                        val dstX = ((canvasW - dstW) / 2f).roundToInt()
                        val dstY = ((canvasH - dstH) / 2f).roundToInt()

                        withTransform({
                            translate(left = offsetX, top = offsetY)
                            scale(scaleX = scale, scaleY = scale)
                        }) {
                            drawImage(
                                image = cachedBitmap,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(snapshot.width, snapshot.height),
                                dstOffset = IntOffset(dstX, dstY),
                                dstSize = IntSize(dstW, dstH),
                                filterQuality = FilterQuality.None
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = String.format(
                Locale.US,
                "Zoom: %.1fx | Pan: (%.0f, %.0f)",
                scale,
                offsetX,
                offsetY
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("map_viewport_label")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { scale = (scale + ZOOM_STEP).coerceAtMost(MAX_ZOOM_SCALE) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("btn_map_zoom_in")
            ) {
                Text(text = stringResource(id = R.string.action_zoom_in))
            }
            OutlinedButton(
                onClick = { scale = (scale - ZOOM_STEP).coerceAtLeast(MIN_ZOOM_SCALE) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("btn_map_zoom_out")
            ) {
                Text(text = stringResource(id = R.string.action_zoom_out))
            }
            OutlinedButton(
                onClick = { offsetX += PAN_STEP_PX },
                modifier = Modifier
                    .weight(1f)
                    .testTag("btn_map_pan_right")
            ) {
                Text(text = stringResource(id = R.string.action_pan_right))
            }
            OutlinedButton(
                onClick = {
                    scale = 1.0f
                    offsetX = 0.0f
                    offsetY = 0.0f
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("btn_map_reset_view")
            ) {
                Text(text = stringResource(id = R.string.action_reset_view))
            }
        }
    }
}
