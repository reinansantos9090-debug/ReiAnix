package com.reiflix.reiflix_local.data.tracemoe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Public-first visual identification with an optional independently hosted index.
 *
 * The private index is queried only when the public Trace.moe endpoint fails or
 * returns no match, so a healthy public response keeps its normal latency and
 * does not cause a second upload. Successful responses are cached by exact image
 * content; failed requests are never cached as "not found".
 */
class ReiAnixHybridTraceMoeClient(
    private val publicProvider: ReiAnixTraceMoeSearchProvider,
    private val independentProvider: ReiAnixTraceMoeSearchProvider?,
    private val cacheDirectory: File,
    private val cacheNamespace: String = "public-only",
) {
    suspend fun search(imageFile: File): List<TraceMoeSearchMatch> {
        val key = withContext(Dispatchers.IO) { cacheKey(imageFile) }
        val cached = key?.let { withContext(Dispatchers.IO) { readCache(it) } }
        if (cached != null) return cached

        var publicMatches: List<TraceMoeSearchMatch>? = null
        var publicFailure: Exception? = null
        try {
            val matches = publicProvider.search(imageFile)
            publicMatches = matches
            if (matches.any { it.similarity >= MINIMUM_TRACE_MOE_SUPPORTING_FRAME_SIMILARITY }) {
                key?.let { cacheKey -> withContext(Dispatchers.IO) { writeCache(cacheKey, matches) } }
                return matches
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publicFailure = error
        }

        val fallback = independentProvider
        if (fallback == null) {
            val validPublicMatches = publicMatches
            if (validPublicMatches != null) {
                key?.let { cacheKey ->
                    withContext(Dispatchers.IO) { writeCache(cacheKey, validPublicMatches) }
                }
                return validPublicMatches
            }
            throw IOException(
                "The public Trace.moe search failed and no independent visual index is configured.",
                publicFailure,
            )
        }

        try {
            val independentMatches = fallback.search(imageFile)
            val combinedMatches = (publicMatches.orEmpty() + independentMatches)
                .groupBy(TraceMoeSearchMatch::anilistId)
                .values
                .mapNotNull { sameAnimeMatches -> sameAnimeMatches.maxByOrNull(TraceMoeSearchMatch::similarity) }
                .sortedByDescending(TraceMoeSearchMatch::similarity)
            key?.let { cacheKey -> withContext(Dispatchers.IO) { writeCache(cacheKey, combinedMatches) } }
            return combinedMatches
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (independentFailure: Exception) {
            // If the public index returned no match but our own index is unavailable,
            // the combined decision is incomplete. Never cache it as a valid no-match.
            throw IOException(
                "The independent visual index failed after the public Trace.moe search returned no match.",
                independentFailure,
            ).also { combined ->
                publicFailure?.let(combined::addSuppressed)
            }
        }
    }

    private fun cacheKey(imageFile: File): String? {
        if (!imageFile.isFile || imageFile.length() !in 4L..ReiAnixTraceMoeClient.MAX_IMAGE_BYTES) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
        imageFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val imageDigest = digest.digest().joinToString("") { "%02x".format(it) }
        val scopedKey = "$CACHE_VERSION|$cacheNamespace|$imageDigest"
        return MessageDigest.getInstance("SHA-256")
            .digest(scopedKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): List<TraceMoeSearchMatch>? = runCatching {
        val file = File(cacheDirectory, "$key.json")
        if (!file.isFile || file.length() !in 1L..MAX_CACHE_ENTRY_BYTES) return@runCatching null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optInt("version", 0) != CACHE_VERSION) return@runCatching null
        val storedAt = root.optLong("storedAtMillis", 0L)
        val now = System.currentTimeMillis()
        val array = root.optJSONArray("matches") ?: return@runCatching null
        val hasPotentialMatch = (0 until array.length()).any { index ->
            array.optJSONObject(index)?.optDouble("similarity", Double.NaN)
                ?.let { it.isFinite() && it >= MINIMUM_TRACE_MOE_SUPPORTING_FRAME_SIMILARITY }
                ?: false
        }
        val ttl = if (hasPotentialMatch) POSITIVE_CACHE_TTL_MILLIS else NEGATIVE_CACHE_TTL_MILLIS
        if (storedAt <= 0L || now < storedAt || now - storedAt > ttl) {
            file.delete()
            return@runCatching null
        }
        val matches = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optInt("anilistId", 0)
                val similarity = item.optDouble("similarity", Double.NaN)
                if (id > 0 && similarity.isFinite() && similarity in 0.0..1.0) {
                    add(TraceMoeSearchMatch(id, similarity))
                }
            }
        }.sortedByDescending(TraceMoeSearchMatch::similarity)
        file.setLastModified(now)
        matches
    }.getOrNull()

    private fun writeCache(key: String, matches: List<TraceMoeSearchMatch>) {
        runCatching {
            if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) return
            val target = File(cacheDirectory, "$key.json")
            val temporary = File(cacheDirectory, ".$key." + java.util.UUID.randomUUID() + ".tmp")
            val array = JSONArray().apply {
                matches.forEach { match ->
                    put(JSONObject().put("anilistId", match.anilistId).put("similarity", match.similarity))
                }
            }
            val root = JSONObject()
                .put("version", CACHE_VERSION)
                .put("storedAtMillis", System.currentTimeMillis())
                .put("matches", array)
            temporary.writeText(root.toString(), Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                temporary.delete()
                return
            }
            if (!temporary.renameTo(target)) temporary.delete()
            trimCache()
        }
    }

    private fun trimCache() {
        val entries = cacheDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy(File::lastModified)
            ?: return
        entries.take((entries.size - MAX_CACHE_ENTRIES).coerceAtLeast(0)).forEach { it.delete() }
    }

    private companion object {
        const val CACHE_VERSION = 1
        const val MAX_CACHE_ENTRIES = 200
        const val MAX_CACHE_ENTRY_BYTES = 128L * 1024L
        const val POSITIVE_CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val NEGATIVE_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    }
}
