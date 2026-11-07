package com.reiflix.reiflix_local.data.tracemoe

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the public anime-scene matcher and optional Google Web Detection in parallel.
 * Both receive only the already-generated JPEG thumbnail. Google labels are resolved
 * to exact AniList titles by its provider and remain weak, multi-episode evidence.
 *
 * The SHA-256 cache avoids repeating completed searches for identical image bytes;
 * no scene catalogue, custom server, or device-local perceptual index is required.
 */
class ReiAnixHybridTraceMoeClient(
    private val publicProvider: ReiAnixTraceMoeSearchProvider,
    private val webDetectionProvider: ReiAnixTraceMoeSearchProvider?,
    private val cacheDirectory: File,
    private val cacheNamespace: String = "trace-only",
) {
    suspend fun search(
        imageFile: File,
        allowPublicSearch: suspend () -> Boolean = { true },
    ): List<TraceMoeSearchMatch> {
        val key = withContext(Dispatchers.IO) { cacheKey(imageFile) }
        val cached = key?.let { withContext(Dispatchers.IO) { readCache(it) } }
        if (cached != null) return cached

        // The optional web search starts before the public quota check finishes.
        // Neither provider waits for the other to be attempted.
        val outcomes = supervisorScope {
            val publicCall = async {
                try {
                    if (allowPublicSearch()) {
                        runProvider(publicProvider, imageFile)
                    } else {
                        ProviderOutcome(
                            emptyList(),
                            TraceMoeRequestException(
                                TraceMoeFailureCategory.QUOTA_OR_CONCURRENCY,
                                "The public Trace.moe daily request budget is exhausted.",
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ProviderOutcome(emptyList(), error)
                }
            }
            val webCall = webDetectionProvider?.let { provider ->
                async { runProvider(provider, imageFile) }
            }
            SearchOutcomes(
                public = publicCall.await(),
                web = webCall?.await(),
            )
        }

        val completeSearch = outcomes.public.failure == null && outcomes.web?.failure == null
        val combined = mergeMatches(outcomes.public.matches + outcomes.web?.matches.orEmpty())
        if (combined.isNotEmpty()) {
            // A positive partial answer may be useful, but it is not cached while
            // an enabled provider is unavailable.
            if (completeSearch) {
                key?.let { withContext(Dispatchers.IO) { writeCache(it, combined) } }
            }
            return combined
        }

        if (completeSearch) {
            key?.let { withContext(Dispatchers.IO) { writeCache(it, emptyList()) } }
            return emptyList()
        }

        val failures = listOfNotNull(outcomes.public.failure, outcomes.web?.failure)
        val primaryFailure = failures.firstOrNull()
        throw IOException(
            "The visual search was incomplete because one or more enabled image-search sources were unavailable.",
            primaryFailure,
        ).also { combinedFailure ->
            failures.drop(1).forEach(combinedFailure::addSuppressed)
        }
    }

    private suspend fun runProvider(
        provider: ReiAnixTraceMoeSearchProvider,
        imageFile: File,
    ): ProviderOutcome = try {
        ProviderOutcome(
            matches = provider.search(imageFile).filter {
                it.anilistId > 0 && it.similarity.isFinite() && it.similarity in 0.0..1.0
            },
            failure = null,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ProviderOutcome(emptyList(), error)
    }

    private fun mergeMatches(matches: List<TraceMoeSearchMatch>): List<TraceMoeSearchMatch> =
        matches.asSequence()
            .groupBy(TraceMoeSearchMatch::anilistId)
            .values
            .mapNotNull { sameAnimeMatches ->
                sameAnimeMatches.maxWithOrNull(
                    compareBy<TraceMoeSearchMatch> { it.similarity }
                        .thenBy { if (it.source == TraceMoeEvidenceSource.TRACE_MOE) 1 else 0 },
                )
            }
            .sortedWith(
                compareByDescending<TraceMoeSearchMatch> { it.similarity }
                    .thenByDescending { if (it.source == TraceMoeEvidenceSource.TRACE_MOE) 1 else 0 },
            )
            .take(MAX_MERGED_RESULTS)

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
        val scopedKey = CACHE_VERSION.toString() + "|" + cacheNamespace + "|" + imageDigest
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
                val source = runCatching {
                    TraceMoeEvidenceSource.valueOf(
                        item.optString("source", TraceMoeEvidenceSource.TRACE_MOE.name),
                    )
                }.getOrDefault(TraceMoeEvidenceSource.TRACE_MOE)
                if (id > 0 && similarity.isFinite() && similarity in 0.0..1.0) {
                    add(TraceMoeSearchMatch(id, similarity, source))
                }
            }
        }.sortedByDescending(TraceMoeSearchMatch::similarity)
        file.setLastModified(now)
        matches
    }.getOrNull()

    internal var lastCacheWriteFailure: Throwable? = null
        private set

    private fun writeCache(key: String, matches: List<TraceMoeSearchMatch>) {
        lastCacheWriteFailure = null
        try {
            if (!cacheDirectory.isDirectory &&
                (!cacheDirectory.mkdirs() || !cacheDirectory.isDirectory)
            ) return

            val array = JSONArray().apply {
                matches.forEach { match ->
                    put(
                        JSONObject()
                            .put("anilistId", match.anilistId)
                            .put("similarity", match.similarity)
                            .put("source", match.source.name),
                    )
                }
            }
            val root = JSONObject()
                .put("version", CACHE_VERSION)
                .put("storedAtMillis", System.currentTimeMillis())
                .put("matches", array)

            // The search cache is rebuildable, not canonical user data. Malformed
            // or partial writes are treated as cache misses on the next lookup.
            File(cacheDirectory, "$key.json").writeText(root.toString(), Charsets.UTF_8)
            trimCache()
        } catch (error: Exception) {
            lastCacheWriteFailure = error
        }
    }

    private fun trimCache() {
        val entries = cacheDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy(File::lastModified)
            ?: return
        entries.take((entries.size - MAX_CACHE_ENTRIES).coerceAtLeast(0)).forEach { it.delete() }
    }

    private data class ProviderOutcome(
        val matches: List<TraceMoeSearchMatch>,
        val failure: Exception?,
    )

    private data class SearchOutcomes(
        val public: ProviderOutcome,
        val web: ProviderOutcome?,
    )

    private companion object {
        const val CACHE_VERSION = 5
        const val MAX_CACHE_ENTRIES = 200
        const val MAX_CACHE_ENTRY_BYTES = 128L * 1024L
        const val MAX_MERGED_RESULTS = 10
        const val POSITIVE_CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val NEGATIVE_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    }
}
