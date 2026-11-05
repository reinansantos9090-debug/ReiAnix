package com.reiflix.reiflix_local.data.catalog

import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkCandidate
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.externalCatalogAttemptAt
import com.reiflix.reiflix_local.data.sqlite.externalCatalogCursor
import com.reiflix.reiflix_local.data.sqlite.getExternalCatalogIds
import com.reiflix.reiflix_local.data.sqlite.listAnimeAfterExternalCatalogCursor
import com.reiflix.reiflix_local.data.sqlite.markExternalCatalogAttempt
import com.reiflix.reiflix_local.data.sqlite.setExternalCatalogCursor
import com.reiflix.reiflix_local.data.sqlite.toExternalCatalogIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Artwork-only supplementary discovery. It searches title variants until it finds one
 * exact, compatible candidate, publishes that poster, and stops searching that anime.
 * It deliberately does not reconcile provider IDs or run a second identity/cover promotion pass.
 */
internal fun boundedExternalCatalogSearchTerms(
    titles: Collection<String>,
    limit: Int = 128,
): List<String> {
    require(limit in 1..128)
    return titles.asSequence()
        .flatMap { com.reiflix.reiflix_local.data.media.MediaTitleNormalizer.searchTitleVariants(it).asSequence() }
        .map(String::trim)
        .filter { it.length in 2..120 }
        .distinctBy(ReiAnixCatalogIdentityMatcher::normalizeCatalogTitle)
        .take(limit)
        .toList()
}

class ReiAnixExternalCatalogIntegrationService(
    private val library: ReiAnixSqliteLibraryRepository,
    private val providers: ReiAnixCatalogProviderRegistry,
    private val artwork: ReiAnixArtworkService,
    @Suppress("UNUSED_PARAMETER") identifierResolver: ReiAnixCatalogIdResolver? = null,
) {
    private val enrichmentPassMutex = Mutex()

    suspend fun enrichLibraryGaps(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        restartFromLibraryScan: Boolean = false,
    ): Int = enrichmentPassMutex.withLock {
        require(batchSize in 1..MAX_BATCH_SIZE)
        if (restartFromLibraryScan) library.setExternalCatalogCursor(0L)
        runArtworkDiscoveryPass(batchSize)
    }

    private suspend fun runArtworkDiscoveryPass(batchSize: Int): Int {
        var cursor = library.externalCatalogCursor()
        var discovered = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = library.listAnimeAfterExternalCatalogCursor(cursor, batchSize)
            if (rows.isEmpty()) break

            for (row in rows) {
                currentCoroutineContext().ensureActive()
                if (row.id > 0L && !hasValidPoster(row.id) && discoverPoster(row)) {
                    discovered++
                }
                library.setExternalCatalogCursor(row.id)
                cursor = row.id
            }
        }
        return discovered
    }

    private suspend fun hasValidPoster(animeId: Long): Boolean =
        library.artworkForAnime(animeId).any { row ->
            row.artworkType.equals("poster", ignoreCase = true) &&
                row.status.equals("ready", ignoreCase = true) &&
                row.localPath?.let { path ->
                    val file = File(path)
                    file.isFile && file.length() > 0L
                } == true
        }

    private suspend fun discoverPoster(initial: ReiAnixAnimeRecord): Boolean {
        val anime = library.getAnime(initial.id) ?: return false
        if (hasValidPoster(anime.id)) return false

        val identity = anime.toExternalCatalogIdentity()
        val ids = library.getExternalCatalogIds(anime.id) ?: ReiAnixExternalCatalogIds()

        for (source in SUPPLEMENTAL_SOURCES) {
            currentCoroutineContext().ensureActive()
            if (hasValidPoster(anime.id)) return false

            val previousAttempt = library.externalCatalogAttemptAt(anime.id, source.key)
            if (previousAttempt != null &&
                System.currentTimeMillis() / 1_000.0 - previousAttempt < SOURCE_RETRY_COOLDOWN_SECONDS
            ) continue

            val provider = providers.provider(source) ?: continue
            val knownId = ids.idFor(source)
            val knownCandidate = if (!knownId.isNullOrBlank()) {
                when (val result = provider.getAnime(knownId)) {
                    is ReiAnixCatalogResult.Success -> result.value?.let { remote ->
                        ReiAnixCatalogIdentityMatcher.selectArtworkCandidate(
                            anime.id, identity, listOf(remote), ids,
                        )
                    }
                    is ReiAnixCatalogResult.Failure -> {
                        record(source, "DETAIL_REQUEST_FAILED", anime.id)
                        null
                    }
                }
            } else {
                null
            }

            val candidate = knownCandidate ?: discoverFirstMatchingAlias(
                provider = provider,
                animeId = anime.id,
                identity = identity,
                knownIds = ids,
                source = source,
            )
            library.markExternalCatalogAttempt(anime.id, source.key)

            if (candidate == null) continue
            val posterCandidates = candidate.artworkCandidates()
                .asSequence()
                .filter { it.type == ReiAnixCatalogArtworkType.POSTER }
                .distinctBy { it.url }
                .take(MAX_ARTWORK_CANDIDATES)
                .map { artworkCandidate ->
                    ReiAnixArtworkCandidate(
                        anilistId = 0L,
                        posterUrl = artworkCandidate.url,
                        bannerUrl = null,
                        source = artworkCandidate.source.key,
                    )
                }
                .toList()

            if (posterCandidates.isNotEmpty() &&
                artwork.requestAnimeArtworkCandidates(anime.id, posterCandidates, force = false)
            ) {
                record(source, "INITIAL_POSTER_CANDIDATE_QUEUED", anime.id)
                return true
            }
        }
        return false
    }

    /**
     * Searches alternate titles with two bounded workers. It stops as soon as one exact,
     * metadata-compatible image candidate is found; no remaining aliases are checked after
     * the first candidate is selected.
     */
    private suspend fun discoverFirstMatchingAlias(
        provider: ReiAnixAnimeCatalogProvider,
        animeId: Long,
        identity: ReiAnixCatalogIdentity,
        knownIds: ReiAnixExternalCatalogIds,
        source: ReiAnixCatalogSource,
    ): ReiAnixCatalogAnime? = coroutineScope {
        val queries = boundedExternalCatalogSearchTerms(identity.titles, MAX_SEARCH_TERMS)
        if (queries.isEmpty()) return@coroutineScope null

        val queryQueue = Channel<String>(Channel.UNLIMITED)
        val results = Channel<ReiAnixCatalogResult<List<ReiAnixCatalogAnime>>>(Channel.UNLIMITED)
        val workers = List(TITLE_SEARCH_CONCURRENCY) {
            launch {
                for (query in queryQueue) {
                    currentCoroutineContext().ensureActive()
                    val result = try {
                        provider.searchAnime(query, SEARCH_LIMIT)
                    } catch (timeout: TimeoutCancellationException) {
                        ReiAnixCatalogResult.Failure(ReiAnixCatalogError.TimedOut)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        ReiAnixCatalogResult.Failure(reiAnixCatalogErrorFor(error))
                    }
                    results.send(result)
                }
            }
        }
        queries.forEach(queryQueue::send)
        queryQueue.close()

        var selected: ReiAnixCatalogAnime? = null
        for (ignored in queries.indices) {
            currentCoroutineContext().ensureActive()
            when (val result = results.receive()) {
                is ReiAnixCatalogResult.Failure -> record(source, "ALIAS_SEARCH_FAILED", animeId)
                is ReiAnixCatalogResult.Success -> {
                    selected = ReiAnixCatalogIdentityMatcher.selectArtworkCandidate(
                        animeId,
                        identity,
                        result.value,
                        knownIds,
                    )
                    if (selected != null) break
                }
            }
        }

        if (selected != null) {
            workers.forEach { it.cancel() }
        }
        workers.joinAll()
        queryQueue.cancel()
        results.close()
        selected
    }

    private fun record(
        source: ReiAnixCatalogSource,
        result: String,
        animeId: Long,
    ) {
        ReiAnixDiagnosticTimeline.record(
            name = "EXTERNAL_CATALOG_ARTWORK_DISCOVERY",
            source = source.key,
            result = result,
            context = mapOf("anime_id" to animeId),
        )
    }

    companion object {
        private val SUPPLEMENTAL_SOURCES = listOf(
            ReiAnixCatalogSource.KITSU,
            ReiAnixCatalogSource.ANIPUB,
        )
        const val DEFAULT_BATCH_SIZE = 12
        const val MAX_BATCH_SIZE = 50
        const val SEARCH_LIMIT = 10
        const val MAX_SEARCH_TERMS = 128
        const val MAX_ARTWORK_CANDIDATES = 2
        const val TITLE_SEARCH_CONCURRENCY = 2
        const val SOURCE_RETRY_COOLDOWN_SECONDS = 6L * 60L * 60L
    }
}
