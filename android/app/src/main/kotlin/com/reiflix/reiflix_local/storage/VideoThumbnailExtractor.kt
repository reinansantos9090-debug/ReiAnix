package com.reiflix.reiflix_local.storage

import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ReiAnixAppDependencies
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
        val canonicalArtworkRegistered: Boolean = false,
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
    private val latestKeyByUri = ConcurrentHashMap<String, String>()
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
        // Keep database-referenced thumbnails in durable app-private storage under
        // the existing artwork cache root. Android may delete cacheDir contents at
        // any time, which would otherwise leave persistent SQLite rows dangling.
        val cacheDir = File(File(File(context.filesDir, "data/covers"), "artwork"), "thumbnails")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return null

        val identity = mediaIdentity?.trim().takeUnless { it.isNullOrEmpty() } ?: uri.toString()
        val key = cacheKey(identity, size, modifiedAt)
        latestKeyByUri[uri.toString()] = key
        if (latestKeyByUri.size > 2048) {
            latestKeyByUri.keys.asSequence().filter { it != uri.toString() }.take(1024).forEach(latestKeyByUri::remove)
        }
        val target = File(cacheDir, key + ".jpg")
        // Preserve already-generated images from older releases before attempting to
        // decode the source video again. The legacy cache remains read-only here.
        val legacyDirectory = File(context.cacheDir, "reiflix/thumbnails")
        val legacyTarget = File(legacyDirectory, key + ".jpg")
        if (!target.isFile || target.length() <= 0L || !isValidCachedThumbnail(target)) {
            promoteLegacyCache(legacyTarget, target)
        }
        // Keep valid legacy metadata (duration, dimensions, rotation and title) too.
        // The sidecar is promoted independently so a good image can still be reused
        // when the old JSON is missing or invalid.
        if (target.isFile && isValidCachedThumbnail(target)) {
            promoteLegacyMetadata(
                cacheMetadataFile(File(legacyDirectory, key + ".jpg")),
                cacheMetadataFile(target),
                key,
                size,
                modifiedAt,
            )
        }
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
                return registerGeneratedArtwork(context, uri, identity, size, modifiedAt, cached.result)
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
                if (cached != null) return registerGeneratedArtwork(context, uri, identity, size, modifiedAt, cached.result)
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
                    if (cached != null) return registerGeneratedArtwork(context, uri, identity, size, modifiedAt, cached.result)
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
                if (result == null) return null
                return registerGeneratedArtwork(context, uri, identity, size, modifiedAt, result)
            } finally {
                if (permitAcquired) extractionPermits.release()
                inFlight.remove(key, lock)
            }
        }
    }

    private fun registerGeneratedArtwork(
        context: Context,
        uri: Uri,
        identity: String,
        size: Long,
        modifiedAt: Long,
        result: Result,
    ): Result {
        // A delayed extraction for an older source revision must not overwrite
        // the artwork row produced for the latest URI version.
        if (latestKeyByUri[uri.toString()] != cacheKey(identity, size, modifiedAt)) {
            return result.copy(canonicalArtworkRegistered = false)
        }
        val registered = runCatching {
            ReiAnixAppDependencies.existingOrNull(context.applicationContext)
                ?.artworkService
                ?.registerGeneratedThumbnail(
                    mediaUri = uri.toString(),
                    thumbnailPath = result.path,
                    size = size,
                    modifiedAt = modifiedAt,
                    mediaIdentity = identity,
                    durationMs = result.durationMs,
                ) == true
        }.getOrElse { error ->
            android.util.Log.w("VideoThumbnailExtractor", "Canonical thumbnail registration failed", error)
            false
        }
        return result.copy(canonicalArtworkRegistered = registered)
    }

    private fun isValidCachedThumbnail(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 && options.outHeight > 0
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

            trimCache(context, target.parentFile ?: return null, target)
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
            return CachedResult(repairedResult, durationRepaired = true)
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

    private fun trimCache(context: Context, directory: File, protected: File) {
        runCatching {
            // Generated thumbnails are persisted in SQLite. Never evict a file while
            // an artwork/cover_cache row still points at it; the budget is soft when
            // all remaining images are owned by the library.
            val artworkService = ReiAnixAppDependencies.existingOrNull(context.applicationContext)
                ?.artworkService ?: return@runCatching
            val files = directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                ?.sortedBy { it.lastModified() }
                ?: return@runCatching
            var total = files.sumOf { it.length() }
            if (total > MAX_CACHE_BYTES) {
                // Read SQLite path references once for the whole eviction pass.
                val referencedPaths = artworkService.referencedCanonicalPaths(files.map { it.absolutePath })
                for (file in files) {
                    if (file == protected) continue
                    if (total <= MAX_CACHE_BYTES) break
                    val canonical = runCatching { file.canonicalFile.path }.getOrNull() ?: continue
                    if (canonical in referencedPaths) continue
                    val length = file.length()
                    if (file.delete()) {
                        total -= length
                        cacheMetadataFile(file).delete()
                    }
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

    private fun promoteLegacyCache(legacy: File, target: File): Boolean {
        if (!legacy.isFile || !isValidCachedThumbnail(legacy)) return false
        // A valid destination always wins. An empty/corrupt destination may be
        // replaced, but only after the legacy image has been copied and validated.
        if (target.isFile && isValidCachedThumbnail(target)) return true
        val temporary = File(target.parentFile, "." + target.name + "." +
            android.os.SystemClock.elapsedRealtimeNanos() + ".tmp")
        return try {
            legacy.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!isValidCachedThumbnail(temporary)) return false
            // Recheck after the copy in case another request populated the target.
            if (target.isFile && isValidCachedThumbnail(target)) return true
            if (target.exists() && !target.delete()) return false
            if (!temporary.renameTo(target)) {
                target.isFile && isValidCachedThumbnail(target)
            } else {
                isValidCachedThumbnail(target)
            }
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    private fun promoteLegacyMetadata(
        legacy: File,
        target: File,
        key: String,
        size: Long,
        modifiedAt: Long,
    ): Boolean {
        if (readCacheMetadata(target, key, size, modifiedAt) != null) return true
        if (readCacheMetadata(legacy, key, size, modifiedAt) == null) return false
        val temporary = File(target.parentFile, "." + target.name + "." +
            android.os.SystemClock.elapsedRealtimeNanos() + ".tmp")
        return try {
            legacy.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (readCacheMetadata(temporary, key, size, modifiedAt) == null) return false
            // Never replace a sidecar another concurrent request already repaired.
            if (readCacheMetadata(target, key, size, modifiedAt) != null) return true
            if (target.exists() && !target.delete()) return false
            if (!temporary.renameTo(target)) {
                readCacheMetadata(target, key, size, modifiedAt) != null
            } else {
                readCacheMetadata(target, key, size, modifiedAt) != null
            }
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
