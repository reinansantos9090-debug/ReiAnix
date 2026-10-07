package com.reiflix.reiflix_local.ui.artwork

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.reiflix.reiflix_local.ui.ReiAnixArtworkMissingState
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ReiAnixLocalArtwork"

/**
 * One process-wide Coil loader for Compose artwork.
 *
 * ArtworkEngine remains responsible for persistent artwork discovery and
 * materialization. Coil is the asynchronous renderer/transport for the current
 * viewport and keeps its disk cache disabled, so there is no second persistent
 * artwork cache/source of truth.
 */
private object ReiAnixArtworkImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
                .also { instance = it }
        }
    }
}

/**
 * Artwork resolution order:
 * 1. materialized/local path
 * 2. cache/fallback local path
 * 3. external URL
 * 4. external fallback URL
 *
 * A failed candidate advances once. There is no retry loop and no permanent
 * "missing" state while a viable later candidate exists.
 */
@Composable
fun ReiAnixLocalArtwork(
    localPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: String = "Sem arte",
    maxDimensionPx: Int = 1024,
    shape: Shape = ReiAnixTokens.Shapes.artwork,
    identity: String? = null,
    fallbackLocalPath: String? = null,
    externalUrl: String? = null,
    fallbackExternalUrl: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
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

        val requestWidthPx = requestDimension(measuredWidthPx, maxDimensionPx)
        val requestHeightPx = requestDimension(measuredHeightPx, maxDimensionPx)
        val fallbackDimensionPx = resolveTargetDimensionPx(
            widthPx = measuredWidthPx,
            heightPx = measuredHeightPx,
            maxDimensionPx = maxDimensionPx,
        )
        val effectiveWidthPx = if (requestWidthPx > 0) requestWidthPx else fallbackDimensionPx
        val effectiveHeightPx = if (requestHeightPx > 0) requestHeightPx else fallbackDimensionPx

        val candidates = remember(
            localPath,
            fallbackLocalPath,
            externalUrl,
            fallbackExternalUrl,
        ) {
            listOf(localPath, fallbackLocalPath, externalUrl, fallbackExternalUrl)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
        }
        val stableIdentity = remember(
            identity,
            localPath,
            fallbackLocalPath,
            externalUrl,
            fallbackExternalUrl,
        ) {
            identity?.trim().takeUnless { it.isNullOrEmpty() }
                ?: candidates.firstOrNull().orEmpty()
        }

        var candidateIndex by remember(stableIdentity, candidates) {
            mutableIntStateOf(0)
        }
        val source = candidates.getOrNull(candidateIndex)
        var requestState by remember(stableIdentity, source) {
            mutableStateOf(ArtworkRequestState.LOADING)
        }

        if (source == null || effectiveWidthPx <= 0 || effectiveHeightPx <= 0) {
            ReiAnixArtworkMissingState(label = placeholder)
        } else {
            val request = remember(
                stableIdentity,
                source,
                effectiveWidthPx,
                effectiveHeightPx,
            ) {
                ImageRequest.Builder(context)
                    .data(source)
                    .size(
                        Size(
                            Dimension.Pixels(effectiveWidthPx),
                            Dimension.Pixels(effectiveHeightPx),
                        ),
                    )
                    .crossfade(false)
                    .memoryCacheKey(
                        stableIdentity + "|" +
                            source + "|" +
                            effectiveWidthPx + "x" + effectiveHeightPx,
                    )
                    .build()
            }

            AsyncImage(
                model = request,
                imageLoader = ReiAnixArtworkImageLoader.get(context),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onLoading = {
                    requestState = ArtworkRequestState.LOADING
                },
                onSuccess = {
                    requestState = ArtworkRequestState.READY
                },
                onError = { state ->
                    Log.w(
                        TAG,
                        "Artwork candidate failed for " + stableIdentity,
                        state.result.throwable,
                    )
                    if (candidateIndex < candidates.lastIndex) {
                        candidateIndex += 1
                    } else {
                        requestState = ArtworkRequestState.MISSING
                    }
                },
            )

            when (requestState) {
                ArtworkRequestState.LOADING -> {
                    ArtworkLoadingPlaceholder(label = placeholder)
                }
                ArtworkRequestState.READY -> Unit
                ArtworkRequestState.MISSING -> {
                    ReiAnixArtworkMissingState(label = placeholder)
                }
            }
        }
    }
}

private enum class ArtworkRequestState {
    LOADING,
    READY,
    MISSING,
}

@Composable
private fun ArtworkLoadingPlaceholder(
    label: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics(mergeDescendants = true) {
                contentDescription = "Carregando " + label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ReiAnixPoster(
    localPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    identity: String? = null,
    fallbackLocalPath: String? = null,
    maxDimensionPx: Int = 512,
    externalUrl: String? = null,
    fallbackExternalUrl: String? = null,
) {
    ReiAnixLocalArtwork(
        localPath = localPath,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = "Sem poster",
        maxDimensionPx = maxDimensionPx,
        shape = ReiAnixTokens.Shapes.artwork,
        identity = identity,
        fallbackLocalPath = fallbackLocalPath,
        externalUrl = externalUrl,
        fallbackExternalUrl = fallbackExternalUrl,
    )
}

@Composable
fun ReiAnixBackdrop(
    localPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    identity: String? = null,
    fallbackLocalPath: String? = null,
    maxDimensionPx: Int = 1024,
    externalUrl: String? = null,
    fallbackExternalUrl: String? = null,
) {
    ReiAnixLocalArtwork(
        localPath = localPath,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = "Sem backdrop",
        maxDimensionPx = maxDimensionPx,
        shape = ReiAnixTokens.Shapes.hero,
        identity = identity,
        fallbackLocalPath = fallbackLocalPath,
        externalUrl = externalUrl,
        fallbackExternalUrl = fallbackExternalUrl,
    )
}

@Composable
fun ReiAnixEpisodeThumbnail(
    localPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    identity: String? = null,
    fallbackLocalPath: String? = null,
    externalUrl: String? = null,
    fallbackExternalUrl: String? = null,
) {
    ReiAnixLocalArtwork(
        localPath = localPath,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = "Sem thumbnail",
        maxDimensionPx = 320,
        shape = ReiAnixTokens.Shapes.small,
        identity = identity,
        fallbackLocalPath = fallbackLocalPath,
        externalUrl = externalUrl,
        fallbackExternalUrl = fallbackExternalUrl,
    )
}

private fun requestDimension(measured: Int, maxDimensionPx: Int): Int {
    if (maxDimensionPx <= 0) return 0
    return if (measured > 0) min(measured, maxDimensionPx) else 0
}

internal fun resolveTargetDimensionPx(
    widthPx: Int,
    heightPx: Int,
    maxDimensionPx: Int,
): Int {
    if (maxDimensionPx <= 0) return 0
    val measured = max(widthPx, heightPx)
    return if (measured > 0) min(measured, maxDimensionPx) else maxDimensionPx
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
