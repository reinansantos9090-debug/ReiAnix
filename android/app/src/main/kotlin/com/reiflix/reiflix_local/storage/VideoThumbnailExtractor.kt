package com.reiflix.reiflix_local.storage

import com.reiflix.reiflix_local.bridge.NativeMailbox
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Extracts a bounded local video frame and metadata without crossing the
 * Python/native mailbox boundary with a Bitmap. Results are persisted as
 * small JPEG files and are safe to regenerate when the source version changes.
 */
object VideoThumbnailExtractor {
    data class Result(
        val path: String,
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0,
        val title: String? = null,
        val mimeType: String? = null,
    )

    private val inFlight = ConcurrentHashMap<String, Any>()
    private val extractionPermits = Semaphore(2)
    private const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    private const val MAX_FRAME_DIMENSION = 320
    internal fun cacheKey(mediaIdentity: String, size: Long, modifiedAt: Long): String =
        sha256(mediaIdentity + "|" + size + "|" + modifiedAt)

    fun extract(
        context: Context,
        uri: Uri,
        size: Long = 0L,
        modifiedAt: Long = 0L,
        mediaIdentity: String? = null,
    ): Result? {
        val cacheDir = File(context.cacheDir, "reiflix/thumbnails")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return null

        val identity = mediaIdentity?.trim().takeUnless { it.isNullOrEmpty() } ?: uri.toString()
        val key = cacheKey(identity, size, modifiedAt)
        val target = File(cacheDir, key + ".jpg")
        val requestId = "thumb-" + key.take(12)
        NativeMailbox.writeBestEffort(
            context,
            org.json.JSONObject().put("type", "diagnostic").put("requestId", requestId)
                .put("payload", org.json.JSONObject().put("event", "THUMBNAIL_REQUESTED")
                    .put("requestId", requestId).put("mediaIdentity", identity).put("size", size)
                    .put("modifiedAt", modifiedAt)),
        )
        if (target.isFile && target.length() > 0L && isValidCachedThumbnail(target)) {
            NativeMailbox.writeBestEffort(
                context,
                org.json.JSONObject().put("type", "diagnostic").put("requestId", requestId)
                    .put("payload", org.json.JSONObject().put("event", "THUMBNAIL_CACHE_HIT")
                        .put("requestId", requestId).put("mediaIdentity", identity).put("size", size)),
            )
            return Result(target.absolutePath)
        }
        NativeMailbox.writeBestEffort(
            context,
            org.json.JSONObject().put("type", "diagnostic").put("requestId", requestId)
                .put("payload", org.json.JSONObject().put("event", "THUMBNAIL_CACHE_MISS")
                    .put("requestId", requestId).put("mediaIdentity", identity).put("size", size)),
        )

        val lock = inFlight.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            if (target.isFile && target.length() > 0L && isValidCachedThumbnail(target)) {
                return Result(target.absolutePath)
            }
            runCatching {
                if (target.exists()) target.delete()
            }
            var permitAcquired = false
            try {
                extractionPermits.acquire()
                permitAcquired = true
                val startedNs = android.os.SystemClock.elapsedRealtimeNanos()
                NativeMailbox.writeBestEffort(
                    context,
                    org.json.JSONObject().put("type", "diagnostic").put("requestId", requestId)
                        .put("payload", org.json.JSONObject().put("event", "THUMBNAIL_GENERATION_STARTED")
                            .put("requestId", requestId)),
                )
                if (target.isFile && target.length() > 0L && isValidCachedThumbnail(target)) {
                    return Result(target.absolutePath)
                }
                val result = extractLocked(context, uri, target, key, size, modifiedAt)
                NativeMailbox.writeBestEffort(
                    context,
                    org.json.JSONObject().put("type", "diagnostic").put("requestId", requestId)
                        .put("payload", org.json.JSONObject()
                            .put("event", if (result != null) "THUMBNAIL_GENERATION_FINISHED" else "THUMBNAIL_FAILED")
                            .put("requestId", requestId)
                            .put("durationMs", (android.os.SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0)),
                )
                return result
            } finally {
                if (permitAcquired) extractionPermits.release()
                inFlight.remove(key, lock)
            }
        }
    }

    private fun isValidCachedThumbnail(file: File): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            false
        } else {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) bitmap.recycle()
            bitmap != null
        }
    }.getOrDefault(false)

    private fun extractLocked(
        context: Context,
        uri: Uri,
        target: File,
        key: String,
        size: Long,
        modifiedAt: Long,
    ): Result? {
        val temp = File(target.parentFile, ".$key.tmp")
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        return try {
            when (uri.scheme?.lowercase()) {
                "content" -> retriever.setDataSource(context, uri)
                "file" -> retriever.setDataSource(uri.path ?: return null)
                else -> return null
            }

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

            bitmap = if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    MAX_FRAME_DIMENSION,
                    MAX_FRAME_DIMENSION,
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: return null

            if (bitmap.width > MAX_FRAME_DIMENSION || bitmap.height > MAX_FRAME_DIMENSION) {
                val scale = minOf(
                    MAX_FRAME_DIMENSION.toFloat() / bitmap.width.toFloat(),
                    MAX_FRAME_DIMENSION.toFloat() / bitmap.height.toFloat(),
                )
                val scaledWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            if (rotation != 0 && bitmap.width > 1 && bitmap.height > 1) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }

            temp.delete()
            FileOutputStream(temp).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)) return null
                output.fd.sync()
            }

            if (target.isFile && target.length() > 0L) {
                temp.delete()
            } else if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }

            trimCache(target.parentFile ?: return null, target)
            Result(
                path = target.absolutePath,
                durationMs = durationMs,
                width = width,
                height = height,
                rotation = rotation,
                title = title,
                mimeType = mimeType,
            )
        } catch (_: Exception) {
            temp.delete()
            null
        } finally {
            bitmap?.recycle()
            runCatching { retriever.release() }
        }
    }


    private fun trimCache(directory: File, protected: File) {
        runCatching {
            val files = directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                ?.sortedBy { it.lastModified() }
                ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            for (file in files) {
                if (file == protected) continue
                if (total <= MAX_CACHE_BYTES) break
                val length = file.length()
                if (file.delete()) total -= length
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
