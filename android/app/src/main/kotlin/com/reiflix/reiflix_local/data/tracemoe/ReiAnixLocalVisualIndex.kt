package com.reiflix.reiflix_local.data.tracemoe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent on-device MPEG-7 Color Layout reference index.
 *
 * The 33-value descriptor follows the ColorLayout algorithm used by
 * soruly/trace.moe-id (MIT; Copyright (c) 2026 soruly). See
 * docs/THIRD_PARTY_NOTICES.md for the required license notice.
 *
 * Only thumbnails belonging to an identity already confirmed by ReiAnix's
 * visual consensus are inserted. This class never scans videos or extracts
 * additional frames.
 */
class ReiAnixLocalVisualIndex(
    private val indexFile: File,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val minimumSimilarity: Double = DEFAULT_MINIMUM_SIMILARITY,
) : ReiAnixTraceMoeSearchProvider {
    private val lock = Any()

    /** Changes whenever the persisted reference set is rewritten, invalidating stale negative cache entries. */
    internal fun cacheGeneration(): String {
        if (!indexFile.isFile) return "empty"
        return runCatching {
            JSONObject(indexFile.readText(Charsets.UTF_8))
                .optString("generation")
                .takeIf(String::isNotBlank)
                ?: indexFile.lastModified().toString() + "-" + indexFile.length().toString()
        }.getOrElse {
            indexFile.lastModified().toString() + "-" + indexFile.length().toString()
        }
    }

    init {
        require(maximumEntries in 1..MAXIMUM_ALLOWED_ENTRIES)
        require(minimumSimilarity in 0.0..1.0)
    }

    override suspend fun search(imageFile: File): List<TraceMoeSearchMatch> =
        withContext(Dispatchers.IO) {
            val imageBytes = readUsableJpeg(imageFile)
            val queryDescriptor = ReiAnixMpeg7ColorLayout.extract(imageBytes)
                ?: return@withContext emptyList()
            val references = synchronized(lock) { readEntries() }

            references.asSequence()
                .map { reference ->
                    reference.anilistId to ReiAnixMpeg7ColorLayout.similarity(
                        queryDescriptor,
                        reference.descriptor,
                    )
                }
                .filter { (_, similarity) -> similarity >= minimumSimilarity }
                .groupBy({ it.first }, { it.second })
                .mapNotNull { (anilistId, similarities) ->
                    similarities.maxOrNull()?.let { TraceMoeSearchMatch(anilistId, it) }
                }
                .sortedByDescending(TraceMoeSearchMatch::similarity)
                .take(MAX_RESULTS)
                .toList()
        }

    /**
     * Learns only from a result that the caller has already committed successfully
     * after the existing progressive visual-consensus policy.
     */
    suspend fun rememberConfirmedReference(imageFile: File, anilistId: Int) =
        withContext(Dispatchers.IO) {
            require(anilistId > 0) { "A confirmed AniList ID is required for a visual reference." }
            val imageBytes = readUsableJpeg(imageFile)
            val descriptor = ReiAnixMpeg7ColorLayout.extract(imageBytes) ?: return@withContext
            synchronized(lock) {
                val entries = readEntries().toMutableList()
                val now = System.currentTimeMillis()
                val existingIndex = entries.indexOfFirst {
                    it.anilistId == anilistId && it.descriptor == descriptor
                }
                if (existingIndex >= 0) {
                    entries[existingIndex] = entries[existingIndex].copy(addedAtMillis = now)
                } else {
                    entries += VisualReference(anilistId, descriptor, now)
                }
                writeEntries(
                    entries.sortedByDescending(VisualReference::addedAtMillis)
                        .take(maximumEntries),
                )
            }
        }

    private fun readUsableJpeg(imageFile: File): ByteArray {
        if (!imageFile.isFile || imageFile.length() !in 4L..ReiAnixTraceMoeClient.MAX_IMAGE_BYTES) {
            throw IOException("The local visual index requires a readable episode thumbnail.")
        }
        val bytes = try {
            imageFile.readBytes()
        } catch (error: Exception) {
            throw IOException("The episode thumbnail could not be read for local visual search.", error)
        }
        if (bytes.size.toLong() > ReiAnixTraceMoeClient.MAX_IMAGE_BYTES ||
            bytes.size < 4 ||
            bytes[0] != 0xff.toByte() ||
            bytes[1] != 0xd8.toByte() ||
            bytes[bytes.lastIndex - 1] != 0xff.toByte() ||
            bytes[bytes.lastIndex] != 0xd9.toByte()
        ) {
            throw IOException("The local visual index accepts only valid JPEG thumbnails.")
        }
        return bytes
    }

    private fun readEntries(): List<VisualReference> {
        if (!indexFile.exists()) return emptyList()
        if (indexFile.length() !in 1L..MAX_INDEX_FILE_BYTES) {
            // This is a rebuildable cache, not the user's library. Remove a corrupt
            // oversized cache instead of permanently blocking remote no-match results.
            indexFile.delete()
            return emptyList()
        }
        val root = try {
            JSONObject(indexFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            indexFile.delete()
            return emptyList()
        }
        // Version 1 stored grayscale perceptual hashes. It is intentionally
        // treated as an empty index instead of mixing incompatible descriptors.
        if (root.optInt("version", 0) != INDEX_VERSION) {
            indexFile.delete()
            return emptyList()
        }
        val array = root.optJSONArray("references") ?: run {
            indexFile.delete()
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val anilistId = item.optInt("anilistId", 0)
                val descriptorArray = item.optJSONArray("descriptor") ?: continue
                if (anilistId <= 0 || descriptorArray.length() != DESCRIPTOR_SIZE) continue
                val descriptor = buildList {
                    for (coefficientIndex in 0 until descriptorArray.length()) {
                        add(descriptorArray.optInt(coefficientIndex, Int.MIN_VALUE))
                    }
                }
                val addedAt = item.optLong("addedAtMillis", 0L)
                if (isValidDescriptor(descriptor) && addedAt > 0L) {
                    add(VisualReference(anilistId, descriptor, addedAt))
                }
            }
        }
    }

    private fun isValidDescriptor(values: List<Int>): Boolean =
        values.size == DESCRIPTOR_SIZE &&
            values[0] in 0..63 &&
            values.subList(1, 21).all { it in 0..31 } &&
            values[21] in 0..63 &&
            values.subList(22, 27).all { it in 0..31 } &&
            values[27] in 0..63 &&
            values.subList(28, 33).all { it in 0..31 }

    private fun writeEntries(entries: List<VisualReference>) {
        val parent = indexFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("The local visual reference index directory could not be created.")
        }
        val array = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("anilistId", entry.anilistId)
                        .put("descriptor", JSONArray(entry.descriptor))
                        .put("addedAtMillis", entry.addedAtMillis),
                )
            }
        }
        val root = JSONObject()
            .put("version", INDEX_VERSION)
            .put("generation", java.util.UUID.randomUUID().toString())
            .put("references", array)
        val temporary = File(indexFile.parentFile, "." + indexFile.name + ".tmp")
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            try {
                // POSIX rename replaces the destination atomically on the same filesystem.
                android.system.Os.rename(temporary.absolutePath, indexFile.absolutePath)
            } catch (error: android.system.ErrnoException) {
                throw IOException("The new local visual reference index could not be committed.", error)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private data class VisualReference(
        val anilistId: Int,
        val descriptor: List<Int>,
        val addedAtMillis: Long,
    )

    private companion object {
        const val INDEX_VERSION = 3
        const val DESCRIPTOR_SIZE = 33
        const val DEFAULT_MAXIMUM_ENTRIES = 5_000
        const val MAXIMUM_ALLOWED_ENTRIES = 20_000
        const val MAX_INDEX_FILE_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MINIMUM_SIMILARITY = 0.90
        const val MAX_RESULTS = 5
    }
}

/**
 * MPEG-7 Color Layout descriptor extraction and weighted distance.
 *
 * The 8x8 RGB block averages are converted to Y/Cb/Cr, transformed with an
 * 8x8 DCT, zig-zag scanned, and quantized into the standard 33 coefficients:
 * 21 luminance values, 6 Cb values, and 6 Cr values. Matching uses the same
 * squared L2 metric and (255 - distance) / 255 score mapping as the API.
 */
internal object ReiAnixMpeg7ColorLayout {
    private const val MAX_IMAGE_PIXELS = 25_000_000L
    private const val MAX_PREPARED_DIMENSION = 320
    private const val MINIMUM_GRAYSCALE_VARIANCE = 64.0
    private const val OUTPUT_SCORE_RANGE = 255.0

    private val zigZag = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33,
        40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50,
        43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39,
        46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    private val cosine = arrayOf(
        doubleArrayOf(0.3535534, 0.3535534, 0.3535534, 0.3535534, 0.3535534, 0.3535534, 0.3535534, 0.3535534),
        doubleArrayOf(0.4903926, 0.4157348, 0.2777851, 0.09754516, -0.09754516, -0.2777851, -0.4157348, -0.4903926),
        doubleArrayOf(0.4619398, 0.1913417, -0.1913417, -0.4619398, -0.4619398, -0.1913417, 0.1913417, 0.4619398),
        doubleArrayOf(0.4157348, -0.09754516, -0.4903926, -0.2777851, 0.2777851, 0.4903926, 0.09754516, -0.4157348),
        doubleArrayOf(0.3535534, -0.3535534, -0.3535534, 0.3535534, 0.3535534, -0.3535534, -0.3535534, 0.3535534),
        doubleArrayOf(0.2777851, -0.4903926, 0.09754516, 0.4157348, -0.4157348, -0.09754516, 0.4903926, -0.2777851),
        doubleArrayOf(0.1913417, -0.4619398, 0.4619398, -0.1913417, -0.1913417, 0.4619398, -0.4619398, 0.1913417),
        doubleArrayOf(0.09754516, -0.2777851, 0.4157348, -0.4903926, 0.4903926, -0.4157348, 0.2777851, -0.09754516),
    )

    fun extract(jpegBytes: ByteArray): List<Int>? {
        val bitmap = decodePreparedBitmap(jpegBytes)
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            if (!hasSufficientDetail(pixels)) return null
            extractFromPixels(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    fun similarity(first: List<Int>, second: List<Int>): Double {
        val distance = distance(first, second)
        return ((OUTPUT_SCORE_RANGE - distance).coerceIn(0.0, OUTPUT_SCORE_RANGE)) / OUTPUT_SCORE_RANGE
    }

    /**
     * Squared Euclidean L2 over all 33 coefficients. Milvus returns the sum
     * of squared coefficient differences for MetricType.L2; the upstream API
     * maps that value with (255 - distance) / 255. Do not take the square root.
     */
    private fun distance(first: List<Int>, second: List<Int>): Double {
        require(first.size == DESCRIPTOR_SIZE && second.size == DESCRIPTOR_SIZE)
        var squaredDistance = 0.0
        for (index in 0 until DESCRIPTOR_SIZE) {
            val difference = (first[index] - second[index]).toDouble()
            squaredDistance += difference * difference
        }
        return squaredDistance
    }

    private fun decodePreparedBitmap(jpegBytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_IMAGE_PIXELS
        ) {
            throw IOException("The thumbnail dimensions are invalid for local visual search.")
        }

        var sampleSize = 1
        val longestSide = max(bounds.outWidth, bounds.outHeight)
        while (longestSide / (sampleSize * 2) >= MAX_PREPARED_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var working = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: throw IOException("The thumbnail could not be decoded for local visual search.")

        try {
            val scale = min(
                1.0,
                min(
                    MAX_PREPARED_DIMENSION.toDouble() / working.width,
                    MAX_PREPARED_DIMENSION.toDouble() / working.height,
                ),
            )
            val width = max(1, (working.width * scale).toInt())
            val height = max(1, (working.height * scale).toInt())
            if (width != working.width || height != working.height) {
                val scaled = Bitmap.createScaledBitmap(working, width, height, true)
                if (scaled !== working) working.recycle()
                working = scaled
            }

            val ratio = working.width.toDouble() / working.height
            val targetRatios = doubleArrayOf(4.0 / 3.0, 16.0 / 9.0, 21.0 / 9.0)
            val nearestRatio = nearestAspectRatio(ratio, targetRatios)
            if (abs(ratio - nearestRatio) > 0.05) {
                val detected = detectFrameRect(working)
                val cropped = snapRectToAspectRatio(detected, working.width, working.height)
                if (cropped.x != 0 || cropped.y != 0 ||
                    cropped.width != working.width || cropped.height != working.height
                ) {
                    val result = Bitmap.createBitmap(
                        working,
                        cropped.x,
                        cropped.y,
                        cropped.width,
                        cropped.height,
                    )
                    if (result !== working) working.recycle()
                    working = result
                }
            }
            return working
        } catch (error: Exception) {
            if (!working.isRecycled) working.recycle()
            throw IOException("The thumbnail could not be prepared for local visual search.", error)
        }
    }

    private fun nearestAspectRatio(value: Double, ratios: DoubleArray): Double {
        var best = ratios.first()
        var smallestDifference = abs(value - best)
        for (index in 1 until ratios.size) {
            val difference = abs(value - ratios[index])
            if (difference < smallestDifference) {
                best = ratios[index]
                smallestDifference = difference
            }
        }
        return best
    }

    private data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun detectFrameRect(bitmap: Bitmap): CropRect {
        val width = bitmap.width
        val height = bitmap.height
        val centerX = width / 2
        val centerY = height / 2

        fun isDark(x: Int, y: Int): Boolean {
            val color = bitmap.getPixel(x, y)
            return ((color shr 16) and 0xff) <= 10 &&
                ((color shr 8) and 0xff) <= 10 &&
                (color and 0xff) <= 10
        }

        fun isRowDark(y: Int): Boolean {
            var count = 0
            for (x in 0 until width) if (isDark(x, y)) count++
            return count > width * 0.95
        }

        fun isColumnDark(x: Int): Boolean {
            var count = 0
            for (y in 0 until height) if (isDark(x, y)) count++
            return count > height * 0.95
        }

        var top = 0
        var bottom = height - 1
        var left = 0
        var right = width - 1
        if (!isDark(centerX, centerY)) {
            top = centerY
            bottom = centerY
            left = centerX
            right = centerX
            while (top > 0 && !isRowDark(top - 1)) top--
            while (bottom < height - 1 && !isRowDark(bottom + 1)) bottom++
            while (left > 0 && !isColumnDark(left - 1)) left--
            while (right < width - 1 && !isColumnDark(right + 1)) right++
        } else {
            while (top < height && isRowDark(top)) top++
            while (bottom > top && isRowDark(bottom)) bottom--
            while (left < width && isColumnDark(left)) left++
            while (right > left && isColumnDark(right)) right--
        }
        return CropRect(
            x = left.coerceIn(0, width - 1),
            y = top.coerceIn(0, height - 1),
            width = max(1, right - left + 1),
            height = max(1, bottom - top + 1),
        )
    }

    private fun snapRectToAspectRatio(rect: CropRect, maxWidth: Int, maxHeight: Int): CropRect {
        val ratios = doubleArrayOf(4.0 / 3.0, 16.0 / 9.0, 21.0 / 9.0)
        val currentRatio = rect.width.toDouble() / max(1, rect.height)
        val ratio = nearestAspectRatio(currentRatio, ratios)
        var width = rect.width.toDouble()
        var height = rect.height.toDouble()
        if (width / ratio > height) width = height * ratio else height = width / ratio
        if (width < 10.0) width = 10.0
        if (height < 10.0) height = 10.0

        val centerX = rect.x + rect.width / 2.0
        val centerY = rect.y + rect.height / 2.0
        var x = centerX - width / 2.0
        var y = centerY - height / 2.0
        if (x < 0) x = 0.0
        if (y < 0) y = 0.0
        if (x + width > maxWidth) {
            x = maxWidth - width
            if (x < 0) {
                x = 0.0
                width = maxWidth.toDouble()
            }
        }
        if (y + height > maxHeight) {
            y = maxHeight - height
            if (y < 0) {
                y = 0.0
                height = maxHeight.toDouble()
            }
        }

        val left = x.roundToInt().coerceIn(0, maxWidth - 1)
        val top = y.roundToInt().coerceIn(0, maxHeight - 1)
        val cropWidth = width.roundToInt().coerceIn(1, maxWidth - left)
        val cropHeight = height.roundToInt().coerceIn(1, maxHeight - top)
        return CropRect(left, top, cropWidth, cropHeight)
    }

    private fun hasSufficientDetail(pixels: IntArray): Boolean {
        var sum = 0.0
        var sumSquared = 0.0
        for (color in pixels) {
            val red = ((color shr 16) and 0xff).toDouble()
            val green = ((color shr 8) and 0xff).toDouble()
            val blue = (color and 0xff).toDouble()
            val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
            sum += luminance
            sumSquared += luminance * luminance
        }
        val mean = sum / pixels.size
        return (sumSquared / pixels.size - mean * mean) >= MINIMUM_GRAYSCALE_VARIANCE
    }

    private fun extractFromPixels(pixels: IntArray, width: Int, height: Int): List<Int> {
        val sumRed = FloatArray(64)
        val sumGreen = FloatArray(64)
        val sumBlue = FloatArray(64)
        val xStart = IntArray(9) { index -> ceil(index * width / 8.0).toInt() }
        val yStart = IntArray(9) { index -> ceil(index * height / 8.0).toInt() }

        for (blockY in 0 until 8) {
            for (y in yStart[blockY] until yStart[blockY + 1]) {
                for (blockX in 0 until 8) {
                    val blockIndex = (blockY shl 3) + blockX
                    var red = sumRed[blockIndex].toDouble()
                    var green = sumGreen[blockIndex].toDouble()
                    var blue = sumBlue[blockIndex].toDouble()
                    for (x in xStart[blockX] until xStart[blockX + 1]) {
                        val color = pixels[y * width + x]
                        red += ((color shr 16) and 0xff)
                        green += ((color shr 8) and 0xff)
                        blue += (color and 0xff)
                    }
                    sumRed[blockIndex] = red.toFloat()
                    sumGreen[blockIndex] = green.toFloat()
                    sumBlue[blockIndex] = blue.toFloat()
                }
            }
        }

        val shape = Array(3) { IntArray(64) }
        for (blockY in 0 until 8) {
            val blockHeight = yStart[blockY + 1] - yStart[blockY]
            for (blockX in 0 until 8) {
                val blockWidth = xStart[blockX + 1] - xStart[blockX]
                val count = blockWidth * blockHeight
                val blockIndex = (blockY shl 3) + blockX
                if (count > 0) {
                    val inverseCount = 1.0 / (count * 256.0)
                    val red = sumRed[blockIndex].toDouble() * inverseCount
                    val green = sumGreen[blockIndex].toDouble() * inverseCount
                    val blue = sumBlue[blockIndex].toDouble() * inverseCount
                    val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
                    shape[0][blockIndex] = floor(219.0 * luminance + 16.5).toInt()
                    shape[1][blockIndex] = floor(126.336 * (blue - luminance) + 128.5).toInt()
                    shape[2][blockIndex] = floor(159.712 * (red - luminance) + 128.5).toInt()
                }
            }
        }

        shape.forEach(::forwardDct)
        val yCoefficients = IntArray(64)
        val cbCoefficients = IntArray(64)
        val crCoefficients = IntArray(64)
        yCoefficients[0] = quantizeYdc(shape[0][0] shr 3) shr 1
        cbCoefficients[0] = quantizeCdc(shape[1][0] shr 3)
        crCoefficients[0] = quantizeCdc(shape[2][0] shr 3)

        for (index in 1 until 64) {
            val zigZagIndex = zigZag[index]
            yCoefficients[index] = quantizeAc(shape[0][zigZagIndex] shr 1) shr 3
            cbCoefficients[index] = quantizeAc(shape[1][zigZagIndex]) shr 3
            crCoefficients[index] = quantizeAc(shape[2][zigZagIndex]) shr 3
        }

        return buildList(DESCRIPTOR_SIZE) {
            add(yCoefficients[0])
            for (index in 1..20) add(yCoefficients[index])
            add(cbCoefficients[0])
            for (index in 1..5) add(cbCoefficients[index])
            add(crCoefficients[0])
            for (index in 1..5) add(crCoefficients[index])
        }
    }

    private fun forwardDct(shape: IntArray) {
        val intermediate = FloatArray(64)
        for (row in 0 until 8) {
            for (frequency in 0 until 8) {
                var sum = 0.0
                for (column in 0 until 8) {
                    sum += cosine[frequency][column] * shape[8 * row + column]
                }
                intermediate[8 * row + frequency] = sum.toFloat()
            }
        }
        for (column in 0 until 8) {
            for (frequency in 0 until 8) {
                var sum = 0.0
                for (row in 0 until 8) {
                    sum += cosine[frequency][row] * intermediate[8 * row + column]
                }
                shape[8 * frequency + column] = floor(sum + 0.499999).toInt()
            }
        }
    }

    private fun quantizeYdc(value: Int): Int = when {
        value > 192 -> 112 + ((value - 192) shr 2)
        value > 160 -> 96 + ((value - 160) shr 1)
        value > 96 -> 32 + (value - 96)
        value > 64 -> 16 + ((value - 64) shr 1)
        else -> value shr 2
    }

    private fun quantizeCdc(value: Int): Int = when {
        value > 191 -> 63
        value > 160 -> 56 + ((value - 160) shr 2)
        value > 144 -> 48 + ((value - 144) shr 1)
        value > 112 -> 16 + (value - 112)
        value > 96 -> 8 + ((value - 96) shr 1)
        value > 64 -> (value - 64) shr 2
        else -> 0
    }

    private fun quantizeAc(value: Int): Int {
        val bounded = value.coerceIn(-256, 255)
        val absolute = abs(bounded)
        var quantized = when {
            absolute > 127 -> 64 + (absolute shr 2)
            absolute > 63 -> 32 + (absolute shr 1)
            else -> absolute
        }
        if (bounded < 0) quantized = -quantized
        return quantized + 128
    }

    private const val DESCRIPTOR_SIZE = 33
}