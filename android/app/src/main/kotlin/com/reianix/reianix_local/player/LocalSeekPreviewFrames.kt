package com.reianix.reianix_local.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.reianix.reianix_local.security.ReiAnixPrivateLog as Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Progressive, local-only seek thumbnails. Based on the underlying idea used
 * by CloudStream's PreviewGenerator: get small sync frames in increasingly
 * detailed levels, and reuse the closest available image while seeking.
 *
 * No thumbnails are generated when the player starts, and none are generated
 * while playback is idle. The decoder runs only during an active scrub.
 *
 * One background worker owns the MediaMetadataRetriever; the Compose thread
 * only reads snapshots of the small, synchronized LRU caches.
 */
internal class LocalSeekPreviewFrames(
    context: Context,
    private val mediaUri: Uri,
) {
    private val appContext = context.applicationContext
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sourceMutex = Mutex()
    private val closed = AtomicBoolean(false)

    // 15 progressive locations are never displaced by active finger movement.
    private val progressiveCache = LruCache<Long, Bitmap>(15)
    // The 28 most recently requested locations cover fine, nearby movement.
    // 43 x 240 x 135 x 4 bytes = ~5.6 MB worst case; no full-size bitmap cache.
    private val focusedCache = LruCache<Long, Bitmap>(28)
    private val failedTimes = LinkedHashSet<Long>()

    @Volatile private var isScrubbing = false
    @Volatile private var requestedMs = 0L
    @Volatile private var currentDurationMs = 0L
    @Volatile private var sourceUnavailable = false

    private var diskCache: PersistentSeekPreviewCache? = null
    private var retriever: MediaMetadataRetriever? = null
    private var worker: Job? = null

    val isUnavailable: Boolean get() = sourceUnavailable

    /**
     * Fast non-blocking operation from Compose. It never decodes or copies
     * bitmap pixels and does not invalidate an already visible thumbnail.
     */
    fun nearestCached(positionMs: Long): Bitmap? {
        if (closed.get()) return null
        val target = positionMs.coerceAtLeast(0L)
        var nearest: Bitmap? = null
        var bestDelta = Long.MAX_VALUE
        for (cache in arrayOf(progressiveCache, focusedCache)) {
            for ((time, image) in cache.snapshot()) {
                val distance = abs(time - target)
                if (distance < bestDelta) {
                    bestDelta = distance
                    nearest = image
                }
            }
        }
        return nearest
    }

    /**
     * Called during slider movement. The latest position replaces the previous
     * request without queuing stale decode work or cancelling an in-flight
     * native decoder call. The worker always checks the latest target first.
     */
    fun requestScrub(positionMs: Long, durationMs: Long) {
        if (closed.get() || durationMs <= 0L) return
        currentDurationMs = durationMs
        requestedMs = positionMs.coerceIn(0L, durationMs)
        isScrubbing = true
        if (worker?.isActive != true) {
            worker = workerScope.launch { runGenerator() }
        }
    }

    /** Stop decoding once the finger is released; keep the cache for later. */
    fun stopScrub() {
        isScrubbing = false
    }

    private suspend fun runGenerator() {
        var lastDuration = -1L
        var progressiveTimes = emptyList<Long>()
        while (currentCoroutineContext().isActive && !closed.get()) {
            if (!isScrubbing || sourceUnavailable) {
                delay(120L)
                continue
            }
            val duration = currentDurationMs
            if (duration <= 0L) {
                delay(120L)
                continue
            }
            if (lastDuration != duration) {
                progressiveTimes = SeekPreviewPolicy.progressivePositions(duration)
                val disk = PersistentSeekPreviewCache(appContext, mediaUri, duration)
                diskCache = disk
                sourceMutex.withLock {
                    disk.availableTimes().filter { it in 0 until duration }.sortedBy { abs(it - requestedMs) }.take(43).forEach { time ->
                        currentCoroutineContext().ensureActive()
                        if (!closed.get() && !isStored(time)) disk.read(time)?.let { image ->
                            if (time in progressiveTimes) progressiveCache.put(time, image)
                            else focusedCache.put(time, image)
                        }
                    }
                }
                lastDuration = duration
            }

            // 1. User's current finger position always has priority.
            val priority = SeekPreviewPolicy.focusBucketTimeMs(requestedMs, duration)
            // 2. Fill the screen progressively at 1/2, 1/4, 3/4, 1/8, ...,
            // so another location quickly has a nearby usable fallback.
            val next = when {
                !isStored(priority) && priority !in failedTimes -> priority
                else -> progressiveTimes.firstOrNull {
                    !isStored(it) && it !in failedTimes
                }
            }

            if (next == null) {
                delay(120L)
                continue
            }
            decodeOne(next, progressiveTimes)
            // Leave a gap between extractions so video playback has priority.
            delay(75L)
        }
    }

    private fun isStored(timeMs: Long): Boolean =
        progressiveCache.get(timeMs) != null || focusedCache.get(timeMs) != null

    private suspend fun decodeOne(timeMs: Long, progressiveTimes: List<Long>) {
        sourceMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (closed.get() || !isScrubbing || isStored(timeMs)) return@withLock
            try {
                val stored = diskCache?.read(timeMs)
                val image = stored ?: run {
                    val source = openSource() ?: return@withLock
                    captureSmallFrame(source, timeMs)
                }
                currentCoroutineContext().ensureActive()
                if (image == null || image.width <= 1 || image.height <= 1) {
                    if (image != null) image.recycle()
                    recordFailure(timeMs)
                } else if (!closed.get()) {
                    if (timeMs in progressiveTimes) progressiveCache.put(timeMs, image)
                    else focusedCache.put(timeMs, image)
                    if (stored == null) diskCache?.put(timeMs, image)
                } else {
                    image.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordFailure(timeMs)
                Log.w(TAG, "SEEK_PREVIEW_FRAME_FAILED scheme=${mediaUri.scheme}", error)
            }
        }
    }

    private fun recordFailure(timeMs: Long) {
        failedTimes.add(timeMs)
        if (failedTimes.size > 128) {
            val iterator = failedTimes.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun openSource(): MediaMetadataRetriever? {
        retriever?.let { return it }
        val created = MediaMetadataRetriever()
        return try {
            when (mediaUri.scheme?.lowercase(Locale.ROOT)) {
                "file" -> created.setDataSource(
                    requireNotNull(mediaUri.path) { "Arquivo local sem caminho" },
                )
                "content" -> created.setDataSource(appContext, mediaUri)
                else -> error("Somente arquivos locais são aceitos")
            }
            retriever = created
            created
        } catch (error: Exception) {
            sourceUnavailable = true
            runCatching { created.release() }
            Log.w(TAG, "SEEK_PREVIEW_SOURCE_UNAVAILABLE scheme=${mediaUri.scheme}", error)
            null
        }
    }

    private fun captureSmallFrame(source: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        val timeUs = timeMs.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
        val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                source.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_WIDTH,
                    THUMB_HEIGHT,
                )
            }.getOrNull()
        } else null
        if (scaled != null) return scaled

        // Some MKV and device decoders return null rather than throwing from
        // getScaledFrameAtTime. Try an ordinary sync frame, then downscale.
        return source.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?.let { reduceFrame(it) }
    }

    private fun reduceFrame(original: Bitmap): Bitmap {
        if (original.width <= THUMB_WIDTH && original.height <= THUMB_HEIGHT) return original
        val ratio = minOf(
            THUMB_WIDTH.toFloat() / original.width,
            THUMB_HEIGHT.toFloat() / original.height,
        )
        val width = (original.width * ratio).toInt().coerceAtLeast(1)
        val height = (original.height * ratio).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(original, width, height, true)
        if (small !== original) original.recycle()
        return small
    }

    /** Closing may outlive a native frame decode, but never runs on the UI. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        isScrubbing = false
        workerScope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            sourceMutex.withLock {
                runCatching { retriever?.release() }
                retriever = null
                progressiveCache.evictAll()
                focusedCache.evictAll()
                failedTimes.clear()
            }
        }
    }

    companion object {
        private const val TAG = "ReiAnixSeekPreview"
        private const val THUMB_WIDTH = 240
        private const val THUMB_HEIGHT = 135
    }
}

/** Pure functions keep the sampling strategy independently testable. */
internal object SeekPreviewPolicy {
    /** Legacy bucketing, kept for backward compatibility and tests. */
    fun bucketTimeMs(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val intervalMs = (durationMs / 100L).coerceIn(10_000L, 30_000L)
        val lastValidMs = (durationMs - 1L).coerceAtLeast(0L)
        return (positionMs.coerceIn(0L, lastValidMs) / intervalMs) * intervalMs
    }

    /** More detailed frames near the active finger, 2–12 seconds apart. */
    fun focusBucketTimeMs(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val intervalMs = (durationMs / 240L).coerceIn(2_000L, 12_000L)
        val max = (durationMs - 1L).coerceAtLeast(0L)
        return (positionMs.coerceIn(0L, max) / intervalMs) * intervalMs
    }

    /**
     * Coarse-to-fine dyadic landmarks, as in CloudStream's progressive levels:
     * middle, quarters, eighths and sixteenths (1 + 2 + 4 + 8 = 15).
     */
    fun progressivePositions(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return emptyList()
        val lastValidMs = (durationMs - 1L).coerceAtLeast(0L)
        val result = ArrayList<Long>(15)
        for (level in 1..4) {
            val divisions = 1 shl level
            for (index in 1 until divisions step 2) {
                val time = (durationMs.toDouble() * index / divisions).toLong()
                    .coerceIn(0L, lastValidMs)
                if (time !in result) result.add(time)
            }
        }
        return result
    }
}
