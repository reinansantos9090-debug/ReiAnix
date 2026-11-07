package com.reiflix.reiflix_local.data.tracemoe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, on-device visual reference index.
 *
 * Only thumbnails belonging to an identity already confirmed by the normal
 * ReiAnix consensus flow are inserted. This is deliberately not a video scanner
 * and never extracts additional frames. The index is a compact perceptual-hash
 * lookup for images the user has already had identified on this device.
 */
class ReiAnixLocalVisualIndex(
    private val indexFile: File,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    private val minimumSimilarity: Double = DEFAULT_MINIMUM_SIMILARITY,
) : ReiAnixTraceMoeSearchProvider {
    private val lock = Any()

    /** Changes whenever the persisted reference set is rewritten, invalidating stale negative cache entries. */
    internal fun cacheGeneration(): String =
        if (indexFile.isFile) indexFile.lastModified().toString() + "-" + indexFile.length().toString()
        else "empty"

    init {
        require(maximumEntries in 1..MAXIMUM_ALLOWED_ENTRIES)
        require(minimumSimilarity in 0.0..1.0)
    }

    override suspend fun search(imageFile: File): List<TraceMoeSearchMatch> =
        withContext(Dispatchers.IO) {
            val imageBytes = readUsableJpeg(imageFile)
            val queryHash = ReiAnixPerceptualHash.calculate(imageBytes) ?: return@withContext emptyList()
            val references = synchronized(lock) { readEntries() }

            references.asSequence()
                .map { reference ->
                    reference.anilistId to ReiAnixPerceptualHash.similarity(queryHash, reference.hash)
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
            val hash = ReiAnixPerceptualHash.calculate(imageBytes) ?: return@withContext
            synchronized(lock) {
                val entries = readEntries().toMutableList()
                val now = System.currentTimeMillis()
                val existingIndex = entries.indexOfFirst {
                    it.anilistId == anilistId && it.hash == hash
                }
                if (existingIndex >= 0) {
                    entries[existingIndex] = entries[existingIndex].copy(addedAtMillis = now)
                } else {
                    entries += VisualReference(anilistId, hash, now)
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
            throw IOException("The local visual reference index has an invalid file size.")
        }
        val root = try {
            JSONObject(indexFile.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("The local visual reference index is unreadable.", error)
        }
        if (root.optInt("version", 0) != INDEX_VERSION) {
            throw IOException("The local visual reference index version is unsupported.")
        }
        val array = root.optJSONArray("references")
            ?: throw IOException("The local visual reference index has no references array.")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val anilistId = item.optInt("anilistId", 0)
                val rawHash = item.optString("hash", "")
                val hash = runCatching { rawHash.toULong(16).toLong() }.getOrNull()
                val addedAt = item.optLong("addedAtMillis", 0L)
                if (anilistId > 0 && rawHash.length == HASH_HEX_LENGTH && hash != null && addedAt > 0L) {
                    add(VisualReference(anilistId, hash, addedAt))
                }
            }
        }
    }

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
                        .put("hash", java.lang.Long.toUnsignedString(entry.hash, 16).padStart(HASH_HEX_LENGTH, '0'))
                        .put("addedAtMillis", entry.addedAtMillis),
                )
            }
        }
        val root = JSONObject()
            .put("version", INDEX_VERSION)
            .put("references", array)
        val temporary = File(indexFile.parentFile, "." + indexFile.name + ".tmp")
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            try {
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
        val hash: Long,
        val addedAtMillis: Long,
    )

    private companion object {
        const val INDEX_VERSION = 1
        const val HASH_HEX_LENGTH = 16
        const val DEFAULT_MAXIMUM_ENTRIES = 5_000
        const val MAXIMUM_ALLOWED_ENTRIES = 20_000
        const val MAX_INDEX_FILE_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_MINIMUM_SIMILARITY = 0.94
        const val MAX_RESULTS = 5
    }
}

/**
 * 64-bit DCT perceptual hash over the already-generated JPEG thumbnail.
 * The lowest-frequency 8x8 coefficients are robust to small resize/compression
 * differences; a strict distance threshold prevents broad visual guesses.
 */
internal object ReiAnixPerceptualHash {
    private const val INPUT_SIZE = 32
    private const val LOW_FREQUENCY_SIZE = 8
    private const val HASH_BITS = 63
    private const val MAX_IMAGE_PIXELS = 25_000_000L

    fun calculate(jpegBytes: ByteArray): Long? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_IMAGE_PIXELS
        ) {
            throw IOException("The thumbnail dimensions are invalid for local visual search.")
        }

        val source = BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: throw IOException("The thumbnail could not be decoded for local visual search.")

        val scaled = try {
            Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        } catch (error: Exception) {
            source.recycle()
            throw IOException("The thumbnail could not be resized for local visual search.", error)
        }
        if (scaled !== source) source.recycle()

        try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val grayscale = DoubleArray(pixels.size) { index ->
                val color = pixels[index]
                val red = (color shr 16) and 0xff
                val green = (color shr 8) and 0xff
                val blue = color and 0xff
                0.299 * red + 0.587 * green + 0.114 * blue
            }
            val mean = grayscale.average()
            val variance = grayscale.sumOf { value ->
                val difference = value - mean
                difference * difference
            } / grayscale.size
            if (variance < MINIMUM_GRAYSCALE_VARIANCE) return null

            val cosine = Array(LOW_FREQUENCY_SIZE) { frequency ->
                DoubleArray(INPUT_SIZE) { coordinate ->
                    kotlin.math.cos(
                        ((2 * coordinate + 1) * frequency * Math.PI) / (2.0 * INPUT_SIZE),
                    )
                }
            }
            val coefficients = DoubleArray(LOW_FREQUENCY_SIZE * LOW_FREQUENCY_SIZE)
            for (vertical in 0 until LOW_FREQUENCY_SIZE) {
                for (horizontal in 0 until LOW_FREQUENCY_SIZE) {
                    var sum = 0.0
                    for (y in 0 until INPUT_SIZE) {
                        val verticalWeight = cosine[vertical][y]
                        val rowOffset = y * INPUT_SIZE
                        for (x in 0 until INPUT_SIZE) {
                            sum += grayscale[rowOffset + x] * cosine[horizontal][x] * verticalWeight
                        }
                    }
                    val horizontalScale = if (horizontal == 0) {
                        kotlin.math.sqrt(1.0 / INPUT_SIZE)
                    } else {
                        kotlin.math.sqrt(2.0 / INPUT_SIZE)
                    }
                    val verticalScale = if (vertical == 0) {
                        kotlin.math.sqrt(1.0 / INPUT_SIZE)
                    } else {
                        kotlin.math.sqrt(2.0 / INPUT_SIZE)
                    }
                    coefficients[vertical * LOW_FREQUENCY_SIZE + horizontal] =
                        sum * horizontalScale * verticalScale
                }
            }

            val median = coefficients.drop(1).sorted()[HASH_BITS / 2]
            var hash = 0L
            var bit = 0
            for (coefficientIndex in 1 until coefficients.size) {
                if (coefficients[coefficientIndex] > median) {
                    hash = hash or (1L shl bit)
                }
                bit += 1
            }
            return hash
        } finally {
            scaled.recycle()
        }
    }

    fun similarity(first: Long, second: Long): Double {
        val differentBits = java.lang.Long.bitCount(first xor second)
        return 1.0 - differentBits.toDouble() / HASH_BITS
    }

    private const val MINIMUM_GRAYSCALE_VARIANCE = 64.0
}