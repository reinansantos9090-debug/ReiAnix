package com.reiflix.reiflix_local.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * Best-effort local timeline preview. Each request supersedes the previous
 * decode so dragging quickly cannot publish stale thumbnails.
 */
internal class ReiAnixTimelinePreviewGenerator(
    private val context: Context,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ReiAnix-TimelinePreview").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    @Volatile private var pending: Future<*>? = null
    @Volatile private var released = false

    fun request(
        uri: Uri,
        positionMs: Long,
        onResult: (Bitmap?, Long) -> Unit,
    ) {
        if (released) return
        val requestGeneration = generation.incrementAndGet()
        pending?.cancel(true)
        pending = try {
            worker.submit {
                var retriever: MediaMetadataRetriever? = null
                val bitmap = try {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val frame = retriever.getFrameAtTime(
                        positionMs.coerceAtLeast(0L) * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                    frame?.let { source ->
                        val width = 320
                        val height = (source.height.toFloat() * width / source.width.coerceAtLeast(1))
                            .toInt().coerceAtLeast(1)
                        if (source.width > width) {
                            Bitmap.createScaledBitmap(source, width, height, true).also {
                                if (it !== source) source.recycle()
                            }
                        } else {
                            source
                        }
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { retriever?.release() }
                }
                if (released || generation.get() != requestGeneration) {
                    bitmap?.recycle()
                    return@submit
                }
                mainHandler.post {
                    if (!released && generation.get() == requestGeneration) {
                        onResult(bitmap, positionMs)
                    } else {
                        bitmap?.recycle()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        pending?.cancel(true)
        pending = null
    }

    fun release() {
        released = true
        cancel()
        worker.shutdownNow()
    }
}
