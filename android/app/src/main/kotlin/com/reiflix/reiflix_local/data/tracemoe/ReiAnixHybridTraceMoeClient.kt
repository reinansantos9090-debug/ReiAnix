package com.reiflix.reiflix_local.data.tracemoe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Runs the public Trace.moe service and ReiAnix's independent remote index
 * concurrently. The independent engine also consults the persistent local
 * reference index in parallel. No provider is a fallback for another provider.
 *
 * Exact-image response caching is separate from the perceptual local index:
 * the cache avoids repeating the same request, while the local index can find
 * a previously confirmed thumbnail after small resize/compression differences.
 */
class ReiAnixHybridTraceMoeClient(
    private val publicProvider: ReiAnixTraceMoeSearchProvider,
    private val independentProvider: ReiAnixTraceMoeSearchProvider?,
    private val localReferenceIndex: ReiAnixLocalVisualIndex?,
    private val cacheDirectory: File,
    private val cacheNamespace: String = "public-only",
) {
    suspend fun search(imageFile: File): List<TraceMoeSearchMatch> {
        val key = withContext(Dispatchers.IO) { cacheKey(imageFile) }
        val cached = key?.let { withContext(Dispatchers.IO) { readCache(it) } }
        if (cached != null) return cached

        // All configured sources start before any result is awaited.
        val outcomes = supervisorScope {
            val publicCall = async { runProvider(publicProvider, imageFile) }
            val independentCall = independentProvider?.let { provider ->
                async { runProvider(provider, imageFile) }
            }
            val localCall = localReferenceIndex?.let { provider ->
                async { runProvider(provider, imageFile) }
            }
            SearchOutcomes(
                public = publicCall.await(),
                independent = independentCall?.await(),
                local = localCall?.await(),
            )
        }

        val remoteSourcesComplete =
            outcomes.public.failure == null && (outcomes.independent?.failure == null)
        val localSourceComplete = outcomes.local?.failure == null
        val completeSearch = remoteSourcesComplete && localSourceComplete
        val combined = mergeMatches(
            outcomes.public.matches +
                outcomes.independent?.matches.orEmpty() +
                outcomes.local?.matches.orEmpty(),
        )

        if (combined.isNotEmpty()) {
            // A partial positive result can still be used, but it is not cached
            // while a configured source is unavailable.
            if (completeSearch) {
                key?.let { cacheKey -> withContext(Dispatchers.IO) { writeCache(cacheKey, combined) } }
            }
            return combined
        }

        if (completeSearch) {
            key?.let { cacheKey -> withContext(Dispatchers.IO) { writeCache(cacheKey, emptyList()) } }
            return emptyList()
        }

        // An empty answer is trustworthy only when every configured source
        // completed. Otherwise the item remains eligible for a later retry.
        val failures = listOfNotNull(
            outcomes.public.failure,
            outcomes.independent?.failure,
            outcomes.local?.failure,
        )
        val primaryFailure = failures.firstOrNull()
        throw IOException(
            "The visual search was incomplete because one or more configured indexes were unavailable.",
            primaryFailure,
        ).also { combinedFailure ->
            failures.drop(1).forEach(combinedFailure::addSuppressed)
        }
    }

    /**
     * Persist a reference only after the caller has successfully committed the
     * final consensus association. A failed write must never undo that commit.
     */
    suspend fun rememberConfirmedReference(imageFile: File, anilistId: Int) {
        localReferenceIndex?.rememberConfirmedReference(imageFile, anilistId)
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
                sameAnimeMatches.maxByOrNull(TraceMoeSearchMatch::similarity)
            }
            .sortedByDescending(TraceMoeSearchMatch::similarity)
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
        val localGeneration = localReferenceIndex?.cacheGeneration().orEmpty()
        val scopedKey = CACHE_VERSION.toString() + "|" + cacheNamespace + "|" + localGeneration + "|" + imageDigest
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

    private data class ProviderOutcome(
        val matches: List<TraceMoeSearchMatch>,
        val failure: Exception?,
    )

    private data class SearchOutcomes(
        val public: ProviderOutcome,
        val independent: ProviderOutcome?,
        val local: ProviderOutcome?,
    )

    private companion object {
        const val CACHE_VERSION = 2
        const val MAX_CACHE_ENTRIES = 200
        const val MAX_CACHE_ENTRY_BYTES = 128L * 1024L
        const val MAX_MERGED_RESULTS = 10
        const val POSITIVE_CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val NEGATIVE_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    }
}