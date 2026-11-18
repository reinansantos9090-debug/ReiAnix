package com.reianix.reianix_local.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Lightweight preview extraction for local episodes, inspired by CloudStream's
 * offline MediaMetadataRetriever approach, without decoding ahead of playback.
 *
 * Only the active scrub position is requested; a single retriever and mutex
 * bound concurrency, while the LRU limits memory consumption.
 */
internal class LocalSeekPreviewFrames(
    context: Context,
    private val mediaUri: Uri,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val cache = LruCache<Long, Bitmap>(12)
    private val failedPositions = HashSet<Long>()
    private var retriever: MediaMetadataRetriever? = null
    private var dataSourceUnavailable = false
    private var closed = false

    suspend fun frameAt(positionMs: Long, durationMs: Long): Bitmap? {
        if (durationMs <= 0L) return null
        val bucketMs = SeekPreviewPolicy.bucketTimeMs(positionMs, durationMs)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (closed || dataSourceUnavailable) return@withLock null
                cache.get(bucketMs)?.let { return@withLock it }
                if (bucketMs in failedPositions) return@withLock null

                try {
                    val source = retriever ?: MediaMetadataRetriever().also { created ->
                        try {
                            when (mediaUri.scheme?.lowercase(Locale.ROOT)) {
                                "file" -> created.setDataSource(
                                    requireNotNull(mediaUri.path) { "Arquivo sem caminho local" },
                                )
                                "content" -> created.setDataSource(appContext, mediaUri)
                                else -> error("Prévia aceita apenas arquivos locais")
                            }
                            retriever = created
                        } catch (error: Exception) {
                            dataSourceUnavailable = true
                            created.release()
                            throw error
                        }
                    }

                    val frameTimeUs = bucketMs * 1_000L
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        try {
                            source.getScaledFrameAtTime(
                                frameTimeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                THUMB_WIDTH,
                                THUMB_HEIGHT,
                            )
                        } catch (error: RuntimeException) {
                            // Some container/decoder combinations cannot scale directly.
                            source.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?.let { reduceFrame(it) }
                        }
                    } else {
                        source.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { reduceFrame(it) }
                    }
                    if (frame == null) failedPositions.add(bucketMs)
                    else cache.put(bucketMs, frame)
                    frame
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failedPositions.add(bucketMs)
                    Log.w(TAG, "SEEK_PREVIEW_UNAVAILABLE scheme=${mediaUri.scheme}", error)
                    null
                }
            }
        }
    }

    private fun reduceFrame(original: Bitmap): Bitmap {
        if (original.width <= THUMB_WIDTH && original.height <= THUMB_HEIGHT) return original
        val ratio = minOf(
            THUMB_WIDTH.toFloat() / original.width,
            THUMB_HEIGHT.toFloat() / original.height,
        )
        val width = (original.width * ratio).toInt().coerceAtLeast(1)
        val height = (original.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(original, width, height, true)
        if (scaled !== original) original.recycle()
        return scaled
    }

    /** Never release a retriever on the UI thread or while a frame is decoding. */
    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { retriever?.release() }
                retriever = null
                cache.evictAll()
                failedPositions.clear()
            }
        }
    }

    companion object {
        private const val TAG = "ReiAnixSeekPreview"
        private const val THUMB_WIDTH = 240
        private const val THUMB_HEIGHT = 135
    }
}

/** Short, quantized intervals keep seek previews useful without decoding each pixel move. */
internal object SeekPreviewPolicy {
    fun bucketTimeMs(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val intervalMs = (durationMs / 100L).coerceIn(10_000L, 30_000L)
        val lastValidMs = (durationMs - 1L).coerceAtLeast(0L)
        return (positionMs.coerceIn(0L, lastValidMs) / intervalMs) * intervalMs
    }
}
