package com.reiflix.reiflix_local.ui.artwork

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
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
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.ui.ReiAnixArtworkMissingState
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ReiAnixLocalArtwork"

/**
 * One process-wide Coil loader for Compose artwork.
 *
 * ReiAnixArtworkService owns persistent artwork materialization for Compose.
 * Coil renders local results and may temporarily use an external URL while native
 * download completes; Coil caches do not replace canonical SQLite artwork records.
 */
private object ReiAnixArtworkImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
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
    artworkType: String = "poster",
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

        val candidates = remember(localPath, fallbackLocalPath, externalUrl, fallbackExternalUrl, artworkType) {
            artworkCandidatesForType(
                artworkType = artworkType,
                localPath = localPath,
                fallbackLocalPath = fallbackLocalPath,
                externalUrl = externalUrl,
                fallbackExternalUrl = fallbackExternalUrl,
            )
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
        val artworkEntityId = remember(identity, artworkType) {
            val keyPrefix = if (artworkType == "episode_thumbnail") "episode:" else "anime:"
            identity?.takeIf { it.startsWith(keyPrefix) }
                ?.removePrefix(keyPrefix)
                ?.substringBefore(':')
                ?.toLongOrNull()
                ?.toString()
        }
        val artworkEntityType = if (artworkType == "episode_thumbnail") "episode" else "anime_or_movie"

        var candidateIndex by remember(stableIdentity, candidates) {
            mutableIntStateOf(0)
        }
        val source = candidates.getOrNull(candidateIndex)
        val artworkTraceId = remember(stableIdentity, source) {
            "art-" + Integer.toHexString((stableIdentity + "|" + source.orEmpty()).hashCode())
        }
        val artworkRequestStartedAt = remember(artworkTraceId, effectiveWidthPx, effectiveHeightPx) {
            SystemClock.elapsedRealtime()
        }
        var isMissing by remember(stableIdentity, candidates) {
            mutableStateOf(false)
        }
        val imageLoader = ReiAnixArtworkImageLoader.get(context)

        if (source == null) {
            LaunchedEffect(stableIdentity, candidates) {
                ReiAnixDiagnosticTimeline.record(
                    name = "ARTWORK_SOURCE_NOT_FOUND",
                    severity = "WARN",
                    source = "ReiAnixLocalArtwork",
                    result = if (candidates.isEmpty()) "NOT_CONFIGURED" else "CANDIDATES_EXHAUSTED",
                    requestId = artworkTraceId,
                    context = mapOf(
                        "entity_type" to artworkEntityType,
                        "entity_id" to artworkEntityId,
                        "artwork_type" to artworkType,
                        "candidate_count" to candidates.size,
                        "local_source_present" to candidates.any { !it.startsWith("http://", true) && !it.startsWith("https://", true) },
                        "remote_source_present" to candidates.any { it.startsWith("http://", true) || it.startsWith("https://", true) },
                        "source_kind" to "none",
                    ),
                )
            }
            ReiAnixArtworkMissingState(label = placeholder)
        } else if (effectiveWidthPx <= 0 || effectiveHeightPx <= 0) {
            LaunchedEffect(artworkTraceId, effectiveWidthPx, effectiveHeightPx) {
                ReiAnixDiagnosticTimeline.record(
                    name = "ARTWORK_LOAD_DEFERRED",
                    source = "ReiAnixLocalArtwork",
                    result = "NOT_REQUESTED",
                    requestId = artworkTraceId,
                    context = mapOf(
                        "entity_type" to artworkEntityType,
                        "entity_id" to artworkEntityId,
                        "artwork_type" to artworkType,
                        "width_px" to effectiveWidthPx,
                        "height_px" to effectiveHeightPx,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            // A regenerated local thumbnail can keep the same path; include its file
            // revision so Coil cannot continue serving a previously cached black frame.
            val sourceRevision = localArtworkSourceRevision(source)
            val memoryCacheKey = remember(
                stableIdentity,
                source,
                sourceRevision,
                effectiveWidthPx,
                effectiveHeightPx,
            ) {
                buildArtworkMemoryCacheKey(
                    stableIdentity = stableIdentity,
                    source = source,
                    widthPx = effectiveWidthPx,
                    heightPx = effectiveHeightPx,
                    sourceRevision = sourceRevision,
                )
            }
            val diskCacheKey = remember(stableIdentity, source, sourceRevision) {
                buildArtworkDiskCacheKey(source, sourceRevision)
            }
            val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
            val placeholderPainter = remember(placeholderColor) {
                ColorPainter(placeholderColor)
            }
            val request = remember(
                context,
                memoryCacheKey,
                diskCacheKey,
            ) {
                ImageRequest.Builder(context)
                    .data(coilData(source))
                    .size(
                        Size(
                            Dimension.Pixels(effectiveWidthPx),
                            Dimension.Pixels(effectiveHeightPx),
                        ),
                    )
                    .crossfade(false)
                    .memoryCacheKey(memoryCacheKey)
                    .diskCacheKey(diskCacheKey)
                    .placeholderMemoryCacheKey(memoryCacheKey)
                    .build()
            }

            LaunchedEffect(artworkTraceId, effectiveWidthPx, effectiveHeightPx) {
                ReiAnixDiagnosticTimeline.record(
                    name = "ARTWORK_LOAD_REQUESTED",
                    source = "ReiAnixLocalArtwork",
                    result = "STARTED",
                    requestId = artworkTraceId,
                    context = mapOf(
                        "entity_type" to artworkEntityType,
                        "entity_id" to artworkEntityId,
                        "artwork_type" to artworkType,
                        "source_kind" to artworkSourceKind(source),
                        "source_scheme" to (source.substringBefore(':').takeIf { ':' in source } ?: "file"),
                        "candidate_index" to candidateIndex,
                        "candidate_count" to candidates.size,
                        "width_px" to effectiveWidthPx,
                        "height_px" to effectiveHeightPx,
                    ),
                )
            }
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                placeholder = placeholderPainter,
                onLoading = {
                    ReiAnixDiagnosticTimeline.record(
                        name = "ARTWORK_LOAD_STARTED",
                        source = "ReiAnixLocalArtwork",
                        result = "RUNNING",
                        requestId = artworkTraceId,
                        context = mapOf(
                            "entity_type" to artworkEntityType,
                            "entity_id" to artworkEntityId,
                            "artwork_type" to artworkType,
                            "source_kind" to artworkSourceKind(source),
                            "candidate_index" to candidateIndex,
                        ),
                    )
                },
                onSuccess = { state ->
                    val dataSource = (state.result as? coil.request.SuccessResult)?.dataSource?.name ?: "UNKNOWN"
                    if (dataSource.contains("CACHE", ignoreCase = true)) {
                        ReiAnixDiagnosticTimeline.record(
                            name = "ARTWORK_CACHE_HIT",
                            source = "ReiAnixLocalArtwork",
                            result = dataSource,
                            requestId = artworkTraceId,
                            context = mapOf(
                                "entity_type" to artworkEntityType,
                                "entity_id" to artworkEntityId,
                                "artwork_type" to artworkType,
                                "source_kind" to artworkSourceKind(source),
                                "candidate_index" to candidateIndex,
                                "width_px" to effectiveWidthPx,
                                "height_px" to effectiveHeightPx,
                            ),
                        )
                    }
                    ReiAnixDiagnosticTimeline.record(
                        name = "ARTWORK_IMAGE_READY",
                        source = "ReiAnixLocalArtwork",
                        result = "COMPLETED",
                        requestId = artworkTraceId,
                        context = mapOf(
                            "entity_type" to artworkEntityType,
                            "entity_id" to artworkEntityId,
                            "artwork_type" to artworkType,
                            "source_kind" to artworkSourceKind(source),
                            "width_px" to effectiveWidthPx,
                            "height_px" to effectiveHeightPx,
                            "candidate_index" to candidateIndex,
                            "source_scheme" to (source.substringBefore(':').takeIf { ':' in source } ?: "file"),
                            "data_source" to dataSource,
                            "elapsed_ms" to (SystemClock.elapsedRealtime() - artworkRequestStartedAt).coerceAtLeast(0L),
                        ),
                    )
                },
                onError = { state ->
                    Log.w(
                        TAG,
                        "Artwork candidate failed requestId=" + artworkTraceId +
                            " candidateIndex=" + candidateIndex,
                        state.result.throwable,
                    )
                    ReiAnixDiagnosticTimeline.record(
                        name = "ARTWORK_LOAD_FAILED",
                        severity = "ERROR",
                        source = "ReiAnixLocalArtwork",
                        result = "FAILED",
                        requestId = artworkTraceId,
                        error = state.result.throwable,
                        context = mapOf(
                            "entity_type" to artworkEntityType,
                            "entity_id" to artworkEntityId,
                            "artwork_type" to artworkType,
                            "source_kind" to artworkSourceKind(source),
                            "candidate_index" to candidateIndex,
                            "candidate_count" to candidates.size,
                            "width_px" to effectiveWidthPx,
                            "height_px" to effectiveHeightPx,
                            "elapsed_ms" to (SystemClock.elapsedRealtime() - artworkRequestStartedAt).coerceAtLeast(0L),
                        ),
                    )
                    if (candidateIndex < candidates.lastIndex) {
                        isMissing = false
                        candidateIndex += 1
                    } else {
                        isMissing = true
                    }
                },
            )

            if (isMissing) {
                ReiAnixArtworkMissingState(label = placeholder)
            }
        }
    }
}

internal fun artworkSourceKind(source: String?): String = when {
    source.isNullOrBlank() -> "none"
    source.startsWith("https://", ignoreCase = true) ||
        source.startsWith("http://", ignoreCase = true) -> "remote_http"
    source.startsWith("content://", ignoreCase = true) -> "content_uri"
    source.startsWith("file://", ignoreCase = true) -> "file_uri"
    source.startsWith("android.resource://", ignoreCase = true) -> "android_resource"
    else -> "local_file"
}

internal fun bucketDimensionPx(dimension: Int, step: Int = 32): Int {
    if (dimension <= 0) return 0
    return ((dimension + step / 2) / step) * step
}

internal fun localArtworkSourceRevision(source: String?): String {
    if (source.isNullOrBlank()) return ""
    val file = when {
        source.startsWith("file://", ignoreCase = true) ->
            runCatching { Uri.parse(source).path?.let(::File) }.getOrNull()
        source.startsWith("content://", ignoreCase = true) ||
            source.startsWith("android.resource://", ignoreCase = true) ||
            source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) -> null
        else -> runCatching { File(source) }.getOrNull()
    } ?: return ""
    if (!file.isFile) return ""
    return file.length().toString() + "-" + file.lastModified().toString()
}

internal fun buildArtworkMemoryCacheKey(
    stableIdentity: String,
    source: String,
    widthPx: Int,
    heightPx: Int,
    sourceRevision: String = "",
): String {
    val base = "reianix-artwork|" + stableIdentity + "|" + source + "|" +
        bucketDimensionPx(widthPx) + "x" + bucketDimensionPx(heightPx)
    return if (sourceRevision.isBlank()) base else base + "|revision:" + sourceRevision
}

internal fun buildArtworkDiskCacheKey(source: String, sourceRevision: String = ""): String =
    "reianix-artwork-disk|" + source +
        if (sourceRevision.isBlank()) "" else "|revision:" + sourceRevision

internal fun localArtworkCandidates(
    localPath: String?,
    fallbackLocalPath: String?,
): List<String> =
    listOf(localPath, fallbackLocalPath)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .filterNot { candidate ->
            val scheme = candidate.substringBefore(':')
            scheme.equals("http", ignoreCase = true) ||
                scheme.equals("https", ignoreCase = true)
        }
        .distinct()

/**
 * Resolve materialized local artwork first, then configured remote candidates for
 * poster/backdrop UI. Episode thumbnails are different: they must represent pixels
 * extracted from the local video and must never silently fall back to a series poster.
 */
internal fun artworkCandidatesForType(
    artworkType: String,
    localPath: String?,
    fallbackLocalPath: String?,
    externalUrl: String?,
    fallbackExternalUrl: String?,
): List<String> = if (artworkType.equals("episode_thumbnail", ignoreCase = true)) {
    localArtworkCandidates(localPath, fallbackLocalPath)
} else {
    artworkCandidates(localPath, fallbackLocalPath, externalUrl, fallbackExternalUrl)
}

/** Resolve materialized local artwork first, then configured remote poster/backdrop candidates. */
internal fun artworkCandidates(
    localPath: String?,
    fallbackLocalPath: String?,
    externalUrl: String?,
    fallbackExternalUrl: String?,
): List<String> {
    val local = localArtworkCandidates(localPath, fallbackLocalPath)
    val remote = listOf(externalUrl, fallbackExternalUrl)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
    return (local + remote).distinct()
}

private fun coilData(source: String): Any =
    when {
        source.startsWith("content://", ignoreCase = true) ||
            source.startsWith("file://", ignoreCase = true) ||
            source.startsWith("android.resource://", ignoreCase = true) ||
            source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) ->
            Uri.parse(source)
        else ->
            File(source)
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
        artworkType = "poster",
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
        artworkType = "backdrop",
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
        artworkType = "episode_thumbnail",
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
