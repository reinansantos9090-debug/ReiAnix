package com.reiflix.reiflix_local.ui.artwork

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import com.reiflix.reiflix_local.ui.ReiAnixArtworkMissingState
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface LocalArtworkLoadState {
    data object Loading : LocalArtworkLoadState
    data class Ready(val bitmap: ImageBitmap) : LocalArtworkLoadState
    data object Missing : LocalArtworkLoadState
    data object Error : LocalArtworkLoadState
}

private sealed interface LocalArtworkDecodeResult {
    data class Ready(val bitmap: ImageBitmap) : LocalArtworkDecodeResult
    data object Missing : LocalArtworkDecodeResult
    data object Error : LocalArtworkDecodeResult
}

private const val TAG = "ReiAnixLocalArtwork"

/**
 * Offline artwork renderer for Compose.
 *
 * The source is the existing local/cache path from the ReiAnix projection.
 * External URLs are deliberately not fetched by the Home UI.
 *
 * ArtworkEngine remains the owner of persistent artwork discovery/cache.
 * This composable only decodes the already-resolved local/cache reference for
 * the pixels actually needed by its measured layout; it does not introduce a
 * second disk cache or a second artwork source of truth.
 */
@Composable
fun ReiAnixLocalArtwork(
    localPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: String = "Sem arte",
    maxDimensionPx: Int = 1024,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(ReiAnixTokens.Colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val measuredWidthPx = if (maxWidth != Dp.Infinity) {
            with(density) { maxWidth.toPx().roundToInt() }
        } else {
            0
        }
        val measuredHeightPx = if (maxHeight != Dp.Infinity) {
            with(density) { maxHeight.toPx().roundToInt() }
        } else {
            0
        }
        val targetMaxDimensionPx = resolveTargetDimensionPx(
            widthPx = measuredWidthPx,
            heightPx = measuredHeightPx,
            maxDimensionPx = maxDimensionPx,
        )

        val imageState by produceState<LocalArtworkLoadState>(
            initialValue = if (localPath.isNullOrBlank() || targetMaxDimensionPx <= 0) {
                LocalArtworkLoadState.Error
            } else {
                LocalArtworkLoadState.Loading
            },
            key1 = localPath,
            key2 = targetMaxDimensionPx,
        ) {
            if (localPath.isNullOrBlank() || targetMaxDimensionPx <= 0) {
                value = LocalArtworkLoadState.Missing
                return@produceState
            }

            val decoded = try {
                withContext(Dispatchers.IO) {
                    decodeLocalArtwork(context, localPath, targetMaxDimensionPx)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w(TAG, "Artwork decode failed for a local reference", exception)
                LocalArtworkDecodeResult.Error
            }

            value = when (decoded) {
                is LocalArtworkDecodeResult.Ready -> LocalArtworkLoadState.Ready(decoded.bitmap)
                LocalArtworkDecodeResult.Missing -> LocalArtworkLoadState.Missing
                LocalArtworkDecodeResult.Error -> LocalArtworkLoadState.Error
            }
        }

        when (val state = imageState) {
            LocalArtworkLoadState.Loading -> {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.labelMedium,
                    color = ReiAnixTokens.Colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics {
                        this.contentDescription = "Carregando " + placeholder
                    },
                )
            }

            is LocalArtworkLoadState.Ready -> {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            }

            LocalArtworkLoadState.Missing -> {
                ReiAnixArtworkMissingState(label = placeholder)
            }

            LocalArtworkLoadState.Error -> {
                ReiAnixArtworkMissingState(label = "Não foi possível carregar a arte")
            }
        }
    }
}

/**
 * Caps decoding by both the real measured layout and the existing caller
 * safety limit. This preserves the historical 320 px thumbnail cap while
 * avoiding unnecessary poster resolution when the actual slot is smaller.
 */
internal fun resolveTargetDimensionPx(
    widthPx: Int,
    heightPx: Int,
    maxDimensionPx: Int,
): Int {
    if (maxDimensionPx <= 0) return 0
    val measured = max(widthPx, heightPx)
    return if (measured > 0) min(measured, maxDimensionPx) else maxDimensionPx
}

private fun decodeLocalArtwork(
    context: Context,
    rawPath: String?,
    maxDimensionPx: Int,
): LocalArtworkDecodeResult {
    val path = rawPath?.trim().orEmpty()
    if (path.isEmpty() || maxDimensionPx <= 0) {
        return LocalArtworkDecodeResult.Missing
    }

    val bounds = try {
        openArtworkStream(context, path)?.use { stream ->
            BitmapFactory.Options().also { options ->
                options.inJustDecodeBounds = true
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
    } catch (exception: Exception) {
        Log.w(TAG, "Artwork source could not be inspected", exception)
        return LocalArtworkDecodeResult.Error
    } ?: return LocalArtworkDecodeResult.Missing

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return LocalArtworkDecodeResult.Error
    }

    val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
    val bitmap = try {
        openArtworkStream(context, path)?.use { stream ->
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    } catch (exception: Exception) {
        Log.w(TAG, "Artwork pixels could not be decoded", exception)
        return LocalArtworkDecodeResult.Error
    } ?: return LocalArtworkDecodeResult.Error

    return LocalArtworkDecodeResult.Ready(bitmap)
}

internal fun calculateSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
    if (width <= 0 || height <= 0 || maxDimensionPx <= 0) return 1

    var sample = 1
    val largest = max(width, height)
    while (largest / sample > maxDimensionPx) {
        if (sample > Int.MAX_VALUE / 2) break
        sample *= 2
    }
    return sample
}

private fun openArtworkStream(context: Context, path: String): InputStream? =
    if (path.startsWith("content://", ignoreCase = true)) {
        context.contentResolver.openInputStream(Uri.parse(path))
    } else {
        File(path).takeIf { it.isFile && it.canRead() }?.inputStream()
    }
