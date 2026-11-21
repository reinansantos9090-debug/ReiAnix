package com.reianix.reianix_local.ui.artwork

import android.content.Context
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.reianix.reianix_local.ui.ReiAnixArtworkMissingState
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "ReiAnixLocalArtwork"

/** A single shell-provided preference. The default keeps existing fast image rendering. */
val LocalReiAnixRemoteArtworkAllowed = staticCompositionLocalOf { true }

/**
 * Holds ONLY memory-cache keys (never Bitmaps or Drawables). On a return from
 * Home/Details to Library, Coil can paint the previous successful decode while
 * resolving a changed target size or local-file revision of that SAME source.
 *
 * This supplements Coil's memory/disk caches without pinning large posters or
 * modifying their download order. An evicted entry simply yields no placeholder.
 */
internal class ArtworkLastSuccessfulMemoryKeys(private val maxEntries: Int = 128) {
    init { require(maxEntries > 0) }
    private data class SuccessKeyEntry(
        val source: String,
        val revision: String,
        val memoryKey: String,
    )
    private val entries = object : LinkedHashMap<String, SuccessKeyEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SuccessKeyEntry>?,
        ): Boolean = size > maxEntries
    }

    private fun slot(identity: String, type: String) = type + "|" + identity

    @Synchronized
    fun placeholderKey(
        identity: String,
        type: String,
        source: String,
        revision: String,
        currentKey: String,
    ): String {
        // Never show a different candidate or stale regenerated file.
        return entries[slot(identity, type)]?.takeIf {
            it.source == source && it.revision == revision
        }?.memoryKey ?: currentKey
    }

    @Synchronized
    fun recordSuccess(
        identity: String,
        type: String,
        source: String,
        revision: String,
        key: String,
    ) {
        entries[slot(identity, type)] = SuccessKeyEntry(source, revision, key)
    }
}

private val lastSuccessfulArtworkKeys = ArtworkLastSuccessfulMemoryKeys()

/**
 * Skips recently failed remote URLs only when another artwork candidate exists.
 * The first request is always immediate; a failed URL becomes eligible again
 * after a short cooldown. Local files are never suppressed.
 */
internal class ArtworkRemoteFailureCooldown(
    private val retryAfterMillis: Long = 15_000L,
    private val maxEntries: Int = 128,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(retryAfterMillis > 0)
        require(maxEntries > 0)
    }
    private val failedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun recordFailure(source: String) {
        if (artworkSourceKind(source) == "remote_http") failedAt[source] = nowMillis()
    }

    @Synchronized
    fun recordSuccess(source: String) {
        failedAt.remove(source)
    }

    @Synchronized
    fun nextCandidateIndex(candidates: List<String>, fromIndex: Int): Int? {
        var fallback: Int? = null
        for (index in fromIndex.coerceAtLeast(0) until candidates.size) {
            if (fallback == null) fallback = index
            val source = candidates[index]
            val failedTime = failedAt[source] ?: return index
            val elapsed = nowMillis() - failedTime
            if (elapsed >= retryAfterMillis || elapsed < 0L) {
                failedAt.remove(source)
                return index
            }
        }
        // Preserve the ability to recover even if every candidate recently failed.
        return fallback
    }
}

private val recentRemoteArtworkFailures = ArtworkRemoteFailureCooldown()

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
    sourceRevisions: Map<String, String> = emptyMap(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val remoteAllowed = LocalReiAnixRemoteArtworkAllowed.current

    // Source selection, identity and the Coil loader are independent of layout.
    // Hoist them out of BoxWithConstraints so each lazy item subcomposes only
    // the image request whose first-frame dimensions must remain exact.
    val candidates = remember(localPath, fallbackLocalPath, externalUrl, fallbackExternalUrl, artworkType, remoteAllowed) {
        artworkCandidatesForType(
            artworkType = artworkType,
            localPath = localPath,
            fallbackLocalPath = fallbackLocalPath,
            externalUrl = externalUrl,
            fallbackExternalUrl = fallbackExternalUrl,
            allowRemoteArtwork = remoteAllowed,
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
    var candidateIndex by remember(stableIdentity, candidates) {
        mutableIntStateOf(recentRemoteArtworkFailures.nextCandidateIndex(candidates, 0) ?: 0)
    }
    val source = candidates.getOrNull(candidateIndex)
    var isMissing by remember(stableIdentity, candidates) {
        mutableStateOf(false)
    }
    val imageLoader = ReiAnixArtworkImageLoader.get(context)

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

        if (source == null) {

            ReiAnixArtworkMissingState(label = placeholder)
        } else if (effectiveWidthPx <= 0 || effectiveHeightPx <= 0) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            // A regenerated local thumbnail can keep the same path; include its file
            // revision so Coil cannot continue serving a previously cached black frame.
            val sourceRevision = sourceRevisions[source].orEmpty()
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
            val placeholderKey = remember(stableIdentity, artworkType, source, sourceRevision, memoryCacheKey) {
                lastSuccessfulArtworkKeys.placeholderKey(
                    identity = stableIdentity,
                    type = artworkType,
                    source = source,
                    revision = sourceRevision,
                    currentKey = memoryCacheKey,
                )
            }
            val request = remember(
                context,
                memoryCacheKey,
                diskCacheKey,
                placeholderKey,
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
                    .placeholderMemoryCacheKey(placeholderKey)
                    .build()
            }

            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = {
                    recentRemoteArtworkFailures.recordSuccess(source)
                    // The same source may become readable after its revision
                    // changes. A prior terminal failure must not keep the
                    // "Sem poster" overlay above a successfully decoded image.
                    if (isMissing) isMissing = false
                    lastSuccessfulArtworkKeys.recordSuccess(
                        identity = stableIdentity,
                        type = artworkType,
                        source = source,
                        revision = sourceRevision,
                        key = memoryCacheKey,
                    )
                },
                onError = { state ->
                    val error = state.result.throwable
                    recentRemoteArtworkFailures.recordFailure(source)
                    if (generateSequence(error) { it.cause }.any { it is java.net.SocketTimeoutException }) {
                        // A timeout is expected to be recoverable by another candidate.
                        // Avoid emitting an entire OkHttp stack trace for every recycled grid item.
                        Log.w(TAG, "Artwork candidate timed out candidateIndex=" + candidateIndex)
                    } else {
                        Log.w(TAG, "Artwork candidate failed candidateIndex=" + candidateIndex, error)
                    }

                    val next = recentRemoteArtworkFailures.nextCandidateIndex(candidates, candidateIndex + 1)
                    if (next != null) {
                        isMissing = false
                        candidateIndex = next
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
    allowRemoteArtwork: Boolean = true,
): List<String> = if (artworkType.equals("episode_thumbnail", ignoreCase = true)) {
    localArtworkCandidates(localPath, fallbackLocalPath)
} else {
    artworkCandidates(
        localPath, fallbackLocalPath,
        externalUrl.takeIf { allowRemoteArtwork },
        fallbackExternalUrl.takeIf { allowRemoteArtwork },
    )
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
    sourceRevisions: Map<String, String> = emptyMap(),
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
        sourceRevisions = sourceRevisions,
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
    sourceRevisions: Map<String, String> = emptyMap(),
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
        sourceRevisions = sourceRevisions,
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
    sourceRevisions: Map<String, String> = emptyMap(),
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
        sourceRevisions = sourceRevisions,
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
