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
import org.json.JSONObject

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

    private data class CachedResult(
        val result: Result,
        val durationRepaired: Boolean,
    )

    private data class CachedMetadata(
        val durationMs: Long?,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val title: String?,
        val mimeType: String?,
    )

    private const val CACHE_METADATA_VERSION = 1

    private val inFlight = ConcurrentHashMap<String, Any>()
    private val extractionPermits = Semaphore(2)
    private const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    private const val MAX_FRAME_DIMENSION = 320
    internal fun cacheKey(mediaIdentity: String, size: Long, modifiedAt: Long): String =
        sha256(mediaIdentity + "|" + size + "|" + modifiedAt)

    internal fun parseDurationMs(raw: String?): Long =
        raw?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

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
            val cached = readCachedResult(context, uri, target, key, size, modifiedAt)
            if (cached != null) {
                NativeMailbox.writeBestEffort(
                    context,
                    JSONObject().put("type", "diagnostic").put("requestId", requestId)
                        .put(
                            "payload",
                            JSONObject()
                                .put("event", "THUMBNAIL_CACHE_HIT")
                                .put("requestId", requestId)
                                .put("mediaIdentity", identity)
                                .put("size", size)
                                .put("durationMs", cached.result.durationMs),
                        ),
                )
                if (cached.durationRepaired) {
                    NativeMailbox.writeBestEffort(
                        context,
                        JSONObject().put("type", "diagnostic").put("requestId", requestId)
                            .put(
                                "payload",
                                JSONObject()
                                    .put("event", "THUMBNAIL_DURATION_REPAIRED")
                                    .put("requestId", requestId)
                                    .put("mediaIdentity", identity)
                                    .put("durationMs", cached.result.durationMs),
                            ),
                    )
                }
                return cached.result
            }
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
                val cached = readCachedResult(context, uri, target, key, size, modifiedAt)
                if (cached != null) return cached.result
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
                    val cached = readCachedResult(context, uri, target, key, size, modifiedAt)
                    if (cached != null) return cached.result
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

            val durationMs = parseDurationMs(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            )
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
            val result = Result(
                path = target.absolutePath,
                durationMs = durationMs,
                width = width,
                height = height,
                rotation = rotation,
                title = title,
                mimeType = mimeType,
            )
            writeCacheMetadata(target, key, size, modifiedAt, result)
            result
        } catch (_: Exception) {
            temp.delete()
            null
        } finally {
            bitmap?.recycle()
            runCatching { retriever.release() }
        }
    }



    private fun readCachedResult(
        context: Context,
        uri: Uri,
        target: File,
        key: String,
        size: Long,
        modifiedAt: Long,
    ): CachedResult? {
        val metadataFile = cacheMetadataFile(target)
        val metadata = readCacheMetadata(metadataFile, key, size, modifiedAt)
        var repaired = false

        if (metadata?.durationMs == null) {
            val durationMs = readDurationMs(context, uri)
            val repairedResult = Result(
                path = target.absolutePath,
                durationMs = durationMs,
                width = metadata?.width ?: 0,
                height = metadata?.height ?: 0,
                rotation = metadata?.rotation ?: 0,
                title = metadata?.title,
                mimeType = metadata?.mimeType,
            )
            writeCacheMetadata(target, key, size, modifiedAt, repairedResult)
            repaired = metadata != null || metadataFile.exists()
            return CachedResult(repairedResult, durationRepaired = repaired)
        }

        val result = Result(
            path = target.absolutePath,
            durationMs = metadata.durationMs,
            width = metadata.width,
            height = metadata.height,
            rotation = metadata.rotation,
            title = metadata.title,
            mimeType = metadata.mimeType,
        )
        return CachedResult(result, durationRepaired = false)
    }

    private fun readDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            when (uri.scheme?.lowercase()) {
                "content" -> retriever.setDataSource(context, uri)
                "file" -> retriever.setDataSource(uri.path ?: return 0L)
                else -> return 0L
            }
            parseDurationMs(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            )
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun cacheMetadataFile(target: File): File =
        File(target.parentFile, target.nameWithoutExtension + ".json")

    private fun readCacheMetadata(
        file: File,
        key: String,
        size: Long,
        modifiedAt: Long,
    ): CachedMetadata? = runCatching {
        if (!file.isFile || file.length() <= 0L) return@runCatching null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("version", 0) != CACHE_METADATA_VERSION) return@runCatching null
        if (json.optString("cacheKey") != key) return@runCatching null
        if (json.optLong("size", Long.MIN_VALUE) != size) return@runCatching null
        if (json.optLong("modifiedAt", Long.MIN_VALUE) != modifiedAt) return@runCatching null
        val durationMs = when {
            !json.has("durationMs") || json.isNull("durationMs") -> null
            else -> json.optLong("durationMs", 0L).coerceAtLeast(0L)
        }
        CachedMetadata(
            durationMs = durationMs,
            width = json.optInt("width", 0).coerceAtLeast(0),
            height = json.optInt("height", 0).coerceAtLeast(0),
            rotation = ((json.optInt("rotation", 0) % 360) + 360) % 360,
            title = json.optString("title").takeIf { it.isNotBlank() },
            mimeType = json.optString("mimeType").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun writeCacheMetadata(
        target: File,
        key: String,
        size: Long,
        modifiedAt: Long,
        result: Result,
    ) {
        runCatching {
            val metadataFile = cacheMetadataFile(target)
            val temporary = File(
                metadataFile.parentFile,
                "." + metadataFile.name + ".tmp",
            )
            val json = JSONObject()
                .put("version", CACHE_METADATA_VERSION)
                .put("cacheKey", key)
                .put("size", size)
                .put("modifiedAt", modifiedAt)
                .put("durationMs", if (result.durationMs > 0L) result.durationMs else JSONObject.NULL)
                .put("width", result.width)
                .put("height", result.height)
                .put("rotation", result.rotation)
                .put("title", result.title ?: "")
                .put("mimeType", result.mimeType ?: "")
            temporary.writeText(json.toString(), Charsets.UTF_8)
            if (metadataFile.isFile) metadataFile.delete()
            if (!temporary.renameTo(metadataFile)) temporary.delete()
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
                if (file.delete()) {
                    total -= length
                    cacheMetadataFile(file).delete()
                }
            }
            directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                ?.forEach { metadata ->
                    val jpg = File(directory, metadata.nameWithoutExtension + ".jpg")
                    if (!jpg.isFile) metadata.delete()
                }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
