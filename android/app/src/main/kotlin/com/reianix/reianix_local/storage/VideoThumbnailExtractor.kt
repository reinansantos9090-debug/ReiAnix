package com.reianix.reianix_local.storage

import com.reianix.reianix_local.bridge.NativeMailbox
import com.reianix.reianix_local.ReiAnixAppDependencies
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    /** Small, deterministic frame statistics; intentionally independent of Bitmap for JVM tests. */
    internal data class FrameQualityMetrics(
        val meanLuminance: Double,
        val standardDeviation: Double,
        val darkPixelFraction: Double,
        val brightPixelFraction: Double,
        val dynamicRange: Int,
        val sampleCount: Int,
    )

    private const val CACHE_METADATA_VERSION = 1
    private const val THUMBNAIL_ALGORITHM_VERSION = 3

    private val inFlight = ConcurrentHashMap<String, Any>()
    private val latestKeyByUri = ConcurrentHashMap<String, String>()
    private val extractionPermits = Semaphore(2)
    private const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
    private const val MAX_FRAME_DIMENSION = 320
    internal fun cacheKey(mediaIdentity: String, size: Long, modifiedAt: Long): String =
        // Version the file identity so old black-frame files and Coil's old disk entry
        // cannot win again after the visual selector has been corrected.
        sha256("thumbnail-algorithm-" + THUMBNAIL_ALGORITHM_VERSION + "|" + mediaIdentity + "|" + size + "|" + modifiedAt)

    internal fun parseDurationMs(raw: String?): Long =
        raw?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    /**
     * True only when a cached local thumbnail decodes and has useful visual
     * structure. Used by the library before deciding to skip regeneration.
     * This is intentionally called from an IO dispatcher.
     */
    internal fun isUsableThumbnailFile(path: String?): Boolean {
        val file = localThumbnailFile(path) ?: return false
        return isValidCachedThumbnail(file)
    }

    /** Validates the encoded JPEG container independently from source-frame quality. */
    internal fun isDecodableThumbnailFile(path: String?): Boolean {
        val file = localThumbnailFile(path) ?: return false
        return isDecodableImageFile(file)
    }

    private fun localThumbnailFile(path: String?): File? {
        val value = path?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // Absolute paths may contain spaces, so handle them before URI parsing.
        if (value.startsWith("/") && !value.startsWith("//")) return File(value)
        val parsed = runCatching { java.net.URI(value) }.getOrNull()
            ?: return if (!value.contains("://")) File(value) else null
        return when {
            parsed.scheme.equals("file", ignoreCase = true) ->
                parsed.path?.takeIf(String::isNotBlank)?.let(::File)
            parsed.scheme.isNullOrBlank() -> File(value)
            else -> null
        }
    }

    private fun isDecodableImageFile(file: File): Boolean = runCatching {
        // Generated files must be complete JPEGs. Some platform decoders are
        // permissive with truncated data, so validate both container markers first.
        if (!file.isFile || file.length() < 4L) return false
        val hasJpegStart = file.inputStream().use { input ->
            input.read() == 0xFF && input.read() == 0xD8
        }
        val hasJpegEnd = java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - 2L)
            input.readUnsignedByte() == 0xFF && input.readUnsignedByte() == 0xD9
        }
        if (!hasJpegStart || !hasJpegEnd) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sampleSize = 1
        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (largestDimension / sampleSize > MAX_FRAME_DIMENSION && sampleSize <= Int.MAX_VALUE / 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
        try {
            decoded.width > 0 && decoded.height > 0
        } finally {
            decoded.recycle()
        }
    }.getOrDefault(false)

    /** Whether the library must regenerate a thumbnail instead of reusing its path. */
    internal fun thumbnailNeedsRegeneration(
        localPath: String?,
        isUsable: Boolean,
    ): Boolean = localPath.isNullOrBlank() || !isUsable

    /**
     * Candidate timestamps avoid the exact start frame and sample only a bounded
     * spread through the beginning and early/middle part of an episode.
     */
    internal fun frameCandidateTimestampsUs(durationMs: Long): List<Long> {
        val duration = durationMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
        if (duration == 0L) {
            return listOf(250_000L, 750_000L, 1_500_000L, 3_000_000L, 5_000_000L, 10_000_000L, 20_000_000L)
        }
        if (duration <= 100_000L) return listOf((duration / 2L).coerceAtLeast(1L))

        val minimumCandidate = minOf(250_000L, duration / 4L).coerceAtLeast(1L)
        val latestCandidate = (duration - maxOf(1_000L, duration / 100L)).coerceAtLeast(1L)
        val fractions = doubleArrayOf(0.005, 0.02, 0.05, 0.10, 0.18, 0.30, 0.48, 0.65, 0.82, 0.93)
        val candidates = fractions.map { fraction ->
            (duration.toDouble() * fraction).toLong().coerceAtLeast(minimumCandidate).coerceAtMost(latestCandidate)
        }.distinct().take(10)
        return candidates.ifEmpty { listOf((duration / 2L).coerceAtLeast(1L)) }
    }

    /** Calculate basic visual usefulness from sampled luminance values (0..255). */
    internal fun analyzeLuminanceSamples(samples: IntArray): FrameQualityMetrics? {
        if (samples.isEmpty()) return null
        var sum = 0.0
        var minValue = 255
        var maxValue = 0
        var darkCount = 0
        var brightCount = 0
        val normalized = IntArray(samples.size)
        samples.forEachIndexed { index, raw ->
            val value = raw.coerceIn(0, 255)
            normalized[index] = value
            sum += value.toDouble()
            minValue = minOf(minValue, value)
            maxValue = maxOf(maxValue, value)
            if (value <= 12) darkCount++
            if (value >= 244) brightCount++
        }
        val mean = sum / normalized.size.toDouble()
        var squaredError = 0.0
        normalized.forEach { value ->
            val delta = value.toDouble() - mean
            squaredError += delta * delta
        }
        val deviation = kotlin.math.sqrt(squaredError / normalized.size.toDouble())
        return FrameQualityMetrics(
            meanLuminance = mean,
            standardDeviation = deviation,
            darkPixelFraction = darkCount.toDouble() / normalized.size.toDouble(),
            brightPixelFraction = brightCount.toDouble() / normalized.size.toDouble(),
            dynamicRange = maxValue - minValue,
            sampleCount = normalized.size,
        )
    }

    /**
     * Rejects near-empty frames while allowing genuinely dark scenes when they
     * still contain meaningful tonal structure.
     */
    internal fun isUsableFrame(metrics: FrameQualityMetrics): Boolean {
        if (metrics.sampleCount < 16) return false

        // Do not reject a dark/bright frame on clipping ratio alone: a small
        // subject can occupy fewer than 1.5% of samples while still carrying real
        // scene detail. Reject near-solid black/white only when tonal variation
        // is also very low. This preserves dark scenes without accepting blank frames.
        if (
            metrics.meanLuminance < 20.0 &&
            metrics.darkPixelFraction >= 0.985 &&
            metrics.standardDeviation < 4.0
        ) return false
        if (
            metrics.meanLuminance > 235.0 &&
            metrics.brightPixelFraction >= 0.985 &&
            metrics.standardDeviation < 4.0
        ) return false
        if (metrics.standardDeviation < 1.8 && metrics.dynamicRange < 7) return false
        return true
    }

    /** Higher values favor visible detail without treating darkness alone as failure. */
    internal fun frameQualityScore(metrics: FrameQualityMetrics): Double {
        val tonalDetail = minOf(metrics.standardDeviation, 64.0) * 0.65
        val dynamicDetail = minOf(metrics.dynamicRange, 180).toDouble() * 0.10
        val brightnessPenalty = kotlin.math.abs(metrics.meanLuminance - 127.5) / 127.5 * 12.0
        val clippingPenalty = (metrics.darkPixelFraction + metrics.brightPixelFraction) * 12.0
        return tonalDetail + dynamicDetail - brightnessPenalty - clippingPenalty
    }

    private fun frameQualityMetrics(bitmap: Bitmap): FrameQualityMetrics? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val columns = minOf(20, bitmap.width)
        val rows = minOf(20, bitmap.height)
        val samples = IntArray(columns * rows)
        var index = 0
        for (row in 0 until rows) {
            val y = if (rows == 1) 0 else row * (bitmap.height - 1) / (rows - 1)
            for (column in 0 until columns) {
                val x = if (columns == 1) 0 else column * (bitmap.width - 1) / (columns - 1)
                val pixel = bitmap.getPixel(x, y)
                val red = (pixel shr 16) and 0xff
                val green = (pixel shr 8) and 0xff
                val blue = pixel and 0xff
                samples[index++] = (77 * red + 150 * green + 29 * blue) ushr 8
            }
        }
        return analyzeLuminanceSamples(samples)
    }

    /** Pure source-reference normalization, testable without Android framework stubs. */
    internal fun normalizeLocalMediaReference(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val parsed = runCatching { java.net.URI(value) }.getOrNull()
        val scheme = parsed?.scheme
        return when {
            scheme.isNullOrBlank() && value.startsWith("/") ->
                runCatching { File(value).canonicalFile.toURI().toString() }.getOrNull()
            scheme.equals("content", ignoreCase = true) || scheme.equals("file", ignoreCase = true) ->
                value
            else -> null
        }
    }

    /** Android URI adapter used by the thumbnail extractor. */
    internal fun normalizeLocalMediaUri(raw: String?): Uri? =
        normalizeLocalMediaReference(raw)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun extract(
        context: Context,
        uri: Uri,
        size: Long = 0L,
        modifiedAt: Long = 0L,
        mediaIdentity: String? = null,
    ): Result? {
        val cacheDir = File(context.cacheDir, "reianix/thumbnails")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {

            return null
        }
        if (!cacheDir.isDirectory || !cacheDir.canRead() || !cacheDir.canWrite()) {

            return null
        }

        val identity = mediaIdentity?.trim().takeUnless { it.isNullOrEmpty() } ?: uri.toString()
        val key = cacheKey(identity, size, modifiedAt)
        latestKeyByUri[uri.toString()] = key
        if (latestKeyByUri.size > 2048) {
            latestKeyByUri.keys.asSequence().filter { it != uri.toString() }.take(1024).forEach(latestKeyByUri::remove)
        }
        val target = File(cacheDir, key + ".jpg")
        val initialCacheEntryValid = target.isFile && target.length() > 0L &&
            isValidCachedThumbnail(target)

        
        if (initialCacheEntryValid) {
            val cached = readCachedResult(context, uri, target, key, size, modifiedAt)
            if (cached != null) {

                return registerGeneratedArtwork(context, uri, identity, size, modifiedAt, cached.result)
            }
        }

        

        val lock = inFlight.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            if (target.isFile && target.length() > 0L && isValidCachedThumbnail(target)) {
                val cached = readCachedResult(context, uri, target, key, size, modifiedAt)
                if (cached != null) return registerGeneratedArtwork(context, uri, identity, size, modifiedAt, cached.result)
            }
            var permitAcquired = false
            try {
                extractionPermits.acquire()
                permitAcquired = true
                if (target.exists() && !target.delete()) {
                    val error = IOException("THUMBNAIL_INVALID_CACHE_ENTRY_COULD_NOT_BE_REMOVED")

                    return null
                }

                
                if (target.isFile && target.length() > 0L && isValidCachedThumbnail(target)) {
                    val cached = readCachedResult(context, uri, target, key, size, modifiedAt)
                    if (cached != null) return registerGeneratedArtwork(context, uri, identity, size, modifiedAt, cached.result)
                }
                val result = extractLocked(context, uri, target, key, size, modifiedAt)
                
                if (result == null) {

                    return null
                }

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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sampleSize = 1
        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (largestDimension / sampleSize > MAX_FRAME_DIMENSION && sampleSize <= Int.MAX_VALUE / 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
        try {
            val metrics = frameQualityMetrics(decoded) ?: return false
            isUsableFrame(metrics)
        } finally {
            decoded.recycle()
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
        var diagnosticStage = "source_read"
        return try {
            when (uri.scheme?.lowercase()) {
                "content" -> retriever.setDataSource(context, uri)
                "file" -> {
                    val path = uri.path
                    if (path.isNullOrBlank()) {

                        return null
                    }
                    retriever.setDataSource(path)
                }
                else -> {

                    return null
                }
            }

            diagnosticStage = "metadata_read"
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

            diagnosticStage = "frame_decode"
            val candidateTimestampsUs = frameCandidateTimestampsUs(durationMs)
            var selectedTimestampUs = -1L
            var selectedQuality: FrameQualityMetrics? = null
            var bestScore = Double.NEGATIVE_INFINITY
            var decodedCandidates = 0
            var rejectedCandidates = 0
            var failedCandidates = 0
            var syncFallbackAttempts = 0

            candidateTimestampsUs.forEach { timestampUs ->
                var candidate: Bitmap? = null
                var candidateQuality: FrameQualityMetrics? = null
                var decodedAtTimestamp = false
                var syncFallbackUsed = false
                var lastFrameError: Throwable? = null

                // Prefer the closest decoded frame. If that mode returns no frame or
                // only an unusable frame, try the nearest sync frame before abandoning
                // this timestamp. CloudStream uses CLOSEST_SYNC for its preview frames;
                // retaining CLOSEST as the first attempt keeps ReiAnix's finer sampling.
                intArrayOf(
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ).forEachIndexed { optionIndex, option ->
                    if (candidate != null) return@forEachIndexed
                    if (optionIndex > 0) {
                        syncFallbackAttempts++
                        syncFallbackUsed = true
                    }

                    val frameAttempt = runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(
                                timestampUs,
                                option,
                                MAX_FRAME_DIMENSION,
                                MAX_FRAME_DIMENSION,
                            )
                        } else {
                            retriever.getFrameAtTime(timestampUs, option)
                        }
                    }
                    val decoded = frameAttempt.getOrNull()
                    if (decoded == null) {
                        frameAttempt.exceptionOrNull()?.let { lastFrameError = it }
                        return@forEachIndexed
                    }

                    decodedAtTimestamp = true
                    decodedCandidates++
                    val quality = frameQualityMetrics(decoded)
                    if (quality == null || !isUsableFrame(quality)) {
                        rejectedCandidates++
                        decoded.recycle()
                        return@forEachIndexed
                    }
                    candidate = decoded
                    candidateQuality = quality
                }

                val selectedCandidate = candidate
                val quality = candidateQuality
                if (selectedCandidate == null || quality == null) {
                    if (!decodedAtTimestamp) {
                        failedCandidates++

                    }
                    return@forEach
                }

                val score = frameQualityScore(quality)
                if (score > bestScore) {
                    bitmap?.recycle()
                    bitmap = selectedCandidate
                    selectedTimestampUs = timestampUs
                    selectedQuality = quality
                    bestScore = score
                } else {
                    selectedCandidate.recycle()
                }
            }

            if (bitmap == null) {

                return null
            }

            diagnosticStage = "image_transform"
            var outputBitmap = bitmap ?: return null

            if (outputBitmap.width > MAX_FRAME_DIMENSION || outputBitmap.height > MAX_FRAME_DIMENSION) {
                val scale = minOf(
                    MAX_FRAME_DIMENSION.toFloat() / outputBitmap.width.toFloat(),
                    MAX_FRAME_DIMENSION.toFloat() / outputBitmap.height.toFloat(),
                )
                val scaledWidth = (outputBitmap.width * scale).toInt().coerceAtLeast(1)
                val scaledHeight = (outputBitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(outputBitmap, scaledWidth, scaledHeight, true)
                if (scaled !== outputBitmap) {
                    outputBitmap.recycle()
                    bitmap = scaled
                    outputBitmap = scaled
                }
            }

            if (rotation != 0 && outputBitmap.width > 1 && outputBitmap.height > 1) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(outputBitmap, 0, 0, outputBitmap.width, outputBitmap.height, matrix, true)
                if (rotated !== outputBitmap) {
                    outputBitmap.recycle()
                    bitmap = rotated
                    outputBitmap = rotated
                }
            }

            temp.delete()
            diagnosticStage = "cache_write"
            FileOutputStream(temp).use { output ->
                if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)) {
                    throw IOException("THUMBNAIL_JPEG_COMPRESSION_FAILED")
                }
                output.fd.sync()
            }

            if (target.isFile && target.length() > 0L && isValidCachedThumbnail(target)) {
                // Another valid entry won a race; discard the redundant temporary file.
                temp.delete()
            } else {
                if (target.exists() && !target.delete()) {
                    temp.delete()

                    return null
                }
                if (!temp.renameTo(target)) {
                temp.delete()

                    return null
                }
            }

            // The selected in-memory frame has already passed visual validation.
            // Validate the persisted JPEG for decodeability, not a second time with
            // stricter pixel heuristics: JPEG quantization can alter low-contrast metrics.
            if (!isDecodableThumbnailFile(target.absolutePath)) {
                target.delete()
                cacheMetadataFile(target).delete()

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
        } catch (error: Exception) {
            temp.delete()
            val eventName = when (diagnosticStage) {
                "source_read" -> "THUMBNAIL_SOURCE_READ_FAILED"
                "metadata_read" -> "THUMBNAIL_SOURCE_METADATA_READ_FAILED"
                "frame_decode" -> "THUMBNAIL_DECODE_FAILED"
                "cache_write" -> "THUMBNAIL_CACHE_WRITE_FAILED"
                else -> "THUMBNAIL_IMAGE_PROCESSING_FAILED"
            }

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
        if (metadataFile.exists() && metadata == null) {

        }
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
        } catch (error: Exception) {

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
            if (!temporary.renameTo(metadataFile)) {
                temporary.delete()
                throw IOException("THUMBNAIL_CACHE_METADATA_ATOMIC_RENAME_FAILED")
            }
        }.onFailure { error ->

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
        }.onFailure { error ->

        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
