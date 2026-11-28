package com.reianix.reianix_local.ui.profile

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class AvatarCropRect(val left: Int, val top: Int, val size: Int)

internal fun avatarCropRect(width: Int, height: Int, cx: Float, cy: Float, fraction: Float): AvatarCropRect {
    require(width > 0 && height > 0)
    val side = (minOf(width, height) * fraction.coerceIn(.15f, 1f)).roundToInt()
        .coerceIn(1, minOf(width, height))
    return AvatarCropRect(
        (cx * width - side / 2f).roundToInt().coerceIn(0, width - side),
        (cy * height - side / 2f).roundToInt().coerceIn(0, height - side),
        side,
    )
}

private data class AvatarEditorImage(val bitmap: Bitmap? = null, val loading: Boolean = true)

/** Editing is local and explicit; the gallery image remains untouched. */
@Composable
internal fun ReiAnixAvatarEditor(
    draft: File?,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onSave: (AvatarCropRect) -> Unit,
    loadingSelection: Boolean = false,
) {
    // Keep the same screen and toolbar while the selection is copied.
    // Key only the bitmap loader: a new file never displays the old empty result.
    val loaded = key(draft) {
        val result by produceState(AvatarEditorImage()) {
            val decoded = withContext(Dispatchers.IO) {
                draft?.let { file -> runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
            }
            value = AvatarEditorImage(decoded, false)
        }
        result
    }
    var cx by rememberSaveable(draft?.name) { mutableStateOf(.5f) }
    var cy by rememberSaveable(draft?.name) { mutableStateOf(.5f) }
    var fraction by rememberSaveable(draft?.name) { mutableStateOf(1f) }
    val bitmap = loaded.bitmap
    BackHandler { if (!busy) onCancel() }
    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.align(Alignment.End)
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                .widthIn(max = 260.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("avatar-editor-cancel"),
            ) {
                Text(stringResource(R.string.locale_cancel), color = Color.White, fontSize = 18.sp)
            }
            TextButton(
                onClick = {
                    bitmap?.let { onSave(avatarCropRect(it.width, it.height, cx, cy, fraction)) }
                },
                enabled = !busy && bitmap != null,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("avatar-editor-save"),
            ) {
                Text(stringResource(R.string.pref_save), color = Color.White, fontSize = 18.sp)
            }
        }
        // Reserve the same height throughout loading, editing and saving.
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (busy || loadingSelection) LinearProgressIndicator(Modifier.fillMaxSize())
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                val w = bitmap.width
                val h = bitmap.height
                Canvas(Modifier.fillMaxSize().pointerInput(bitmap, busy) {
                    if (busy) return@pointerInput
                    val scale = minOf(size.width.toFloat() / w, size.height.toFloat() / h)
                    val ox = (size.width - w * scale) / 2f
                    val oy = (size.height - h * scale) / 2f
                    var sx = 0
                    var sy = 0
                    detectDragGestures(
                        onDragStart = { touch ->
                            val rect = avatarCropRect(w, h, cx, cy, fraction)
                            val x = (touch.x - ox) / scale
                            val y = (touch.y - oy) / scale
                            val tolerance = 28.dp.toPx() / scale
                            sx = when {
                                abs(x - rect.left) <= tolerance -> -1
                                abs(x - rect.left - rect.size) <= tolerance -> 1
                                else -> 0
                            }
                            sy = when {
                                abs(y - rect.top) <= tolerance -> -1
                                abs(y - rect.top - rect.size) <= tolerance -> 1
                                else -> 0
                            }
                            if (sx == 0 || sy == 0) { sx = 0; sy = 0 }
                        },
                        onDragEnd = { sx = 0; sy = 0 },
                        onDragCancel = { sx = 0; sy = 0 },
                    ) { change, delta ->
                        change.consume()
                        val rect = avatarCropRect(w, h, cx, cy, fraction)
                        val dx = delta.x / scale
                        val dy = delta.y / scale
                        if (sx != 0 && sy != 0) {
                            val anchorX = if (sx > 0) rect.left.toFloat() else (rect.left + rect.size).toFloat()
                            val anchorY = if (sy > 0) rect.top.toFloat() else (rect.top + rect.size).toFloat()
                            val maxSide = minOf(if (sx > 0) w - anchorX else anchorX,
                                if (sy > 0) h - anchorY else anchorY)
                            val minSide = minOf(w, h) * .15f
                            val side = (rect.size + (sx * dx + sy * dy) / 2f)
                                .coerceIn(minSide.coerceAtMost(maxSide), maxSide)
                            fraction = (side / minOf(w, h)).coerceIn(.15f, 1f)
                            cx = (anchorX + sx * side / 2f) / w
                            cy = (anchorY + sy * side / 2f) / h
                        } else {
                            val half = rect.size / 2f
                            cx = (rect.left + half + dx).coerceIn(half, w - half) / w
                            cy = (rect.top + half + dy).coerceIn(half, h - half) / h
                        }
                    }
                }) {
                    val scale = minOf(size.width / w, size.height / h)
                    val iw = (w * scale).roundToInt().coerceAtLeast(1)
                    val ih = (h * scale).roundToInt().coerceAtLeast(1)
                    val ox = ((size.width - iw) / 2f).roundToInt().toFloat()
                    val oy = ((size.height - ih) / 2f).roundToInt().toFloat()
                    drawImage(image, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(iw, ih))
                    val rect = avatarCropRect(w, h, cx, cy, fraction)
                    val left = ox + rect.left * scale
                    val top = oy + rect.top * scale
                    val side = rect.size * scale
                    val right = left + side
                    val bottom = top + side
                    val shade = Color.Black.copy(alpha = .65f)
                    fun shadeRect(x: Float, y: Float, width: Float, height: Float) {
                        if (width > 0f && height > 0f) drawRect(shade, Offset(x, y), Size(width, height))
                    }
                    shadeRect(ox, oy, iw.toFloat(), top - oy)
                    shadeRect(ox, bottom, iw.toFloat(), oy + ih - bottom)
                    shadeRect(ox, top, left - ox, side)
                    shadeRect(right, top, ox + iw - right, side)
                    drawRect(Color.White, Offset(left, top), Size(side, side), style = Stroke(1.dp.toPx()))
                    for (i in 1..2) {
                        val step = side * i / 3f
                        drawLine(Color.White.copy(alpha = .65f), Offset(left + step, top), Offset(left + step, bottom), 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = .65f), Offset(left, top + step), Offset(right, top + step), 1.dp.toPx())
                    }
                    for (x in listOf(left, right)) for (y in listOf(top, bottom)) {
                        drawCircle(Color.White, 4.dp.toPx(), Offset(x, y))
                    }
                }
            } else if (loadingSelection || loaded.loading) {
                CircularProgressIndicator(Modifier.testTag("avatar-editor-loading"))
            } else {
                Text(stringResource(R.string.tail_avatar_open_failed), color = Color.White)
            }
        }
        if (bitmap != null) {
            Text(stringResource(R.string.tail_avatar_crop), color = Color.White,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            Slider(value = fraction, onValueChange = { fraction = it }, valueRange = .15f..1f,
                enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
    }
}
