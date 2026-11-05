package com.reiflix.reiflix_local.data.catalog

import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkCandidate
import com.reiflix.reiflix_local.data.artwork.allDistinctArtworkSearchTitles
import com.reiflix.reiflix_local.data.artwork.runParallelArtworkSearches
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.externalCatalogAttemptAt
import com.reiflix.reiflix_local.data.sqlite.externalCatalogCursor
import com.reiflix.reiflix_local.data.sqlite.getExternalCatalogIds
import com.reiflix.reiflix_local.data.sqlite.listAnimeAfterExternalCatalogCursor
import com.reiflix.reiflix_local.data.sqlite.markExternalCatalogAttempt
import com.reiflix.reiflix_local.data.sqlite.mergeExternalCatalogMetadata
import com.reiflix.reiflix_local.data.sqlite.saveExternalCatalogIds
import com.reiflix.reiflix_local.data.sqlite.setExternalCatalogCursor
import com.reiflix.reiflix_local.data.sqlite.toExternalCatalogIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.io.File

/**
 * Bounded, process-owned supplemental enrichment. AniList remains primary; Kitsu/Tenrai/AniPub are
 * consulted only for metadata gaps or missing usable artwork, and every association is reverified.
 */
class ReiAnixExternalCatalogIntegrationService(
    private val library: ReiAnixSqliteLibraryRepository,
    private val providers: ReiAnixCatalogProviderRegistry,
    private val artwork: ReiAnixArtworkService,
    private val identifierResolver: ReiAnixCatalogIdResolver? = null,
) {
    private val enrichmentPassMutex = Mutex()

    private data class VerifiedRemote(
        val remote: ReiAnixCatalogAnime,
        val match: ReiAnixCatalogIdentityMatch,
    )

    /**
     * Traverses every eligible anime with bounded SQLite keyset pages. Provider calls and
     * artwork materialization remain sequential per anime and use the existing shared services.
     */
    suspend fun enrichLibraryGaps(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        restartFromLibraryScan: Boolean = false,
    ): Int = enrichmentPassMutex.withLock {
        require(batchSize in 1..MAX_BATCH_SIZE)
        if (restartFromLibraryScan) library.setExternalCatalogCursor(0L)
        runEnrichmentPass(batchSize)
    }

    /**
     * Drain the eligible catalog through keyset-paginated SQLite pages. The persisted
     * anime ID cursor advances only after a row finishes, so cancellation resumes at
     * the first unfinished row and inserts made during a pass are visible to later pages.
     * A completed cursor is deliberately not wrapped: foreground resumes do not rescan
     * completed records. A confirmed library scan explicitly rewinds the same cursor.
     */
    private suspend fun runEnrichmentPass(batchSize: Int): Int {
        require(batchSize in 1..MAX_BATCH_SIZE)
        var cursor = library.externalCatalogCursor()
        var enriched = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = library.listAnimeAfterExternalCatalogCursor(cursor, batchSize)
            if (rows.isEmpty()) break

            for (row in rows) {
                currentCoroutineContext().ensureActive()
                if (isEligible(row) && enrichOne(row)) enriched++
                // Commit progress per row. If an exception/cancellation happens before
                // this write, the same row is retried; later rows are never skipped.
                library.setExternalCatalogCursor(row.id)
                cursor = row.id
            }
        }
        return enriched
    }

    private fun hasManualCatalogOwnership(anime: ReiAnixAnimeRecord): Boolean =
        anime.anilistMatchManual == true ||
            anime.metadataSource.equals("manual", ignoreCase = true) ||
            anime.metadataStatus.equals("manual", ignoreCase = true)

    private suspend fun isEligible(anime: ReiAnixAnimeRecord): Boolean {
        if (anime.id <= 0L) return false
        val manuallyOwned = hasManualCatalogOwnership(anime)
        val metadataMissing = !manuallyOwned && hasMetadataGap(anime)
        val posterMissing = !hasValidArtwork(anime.id, "poster")
        val backdropMissing = !hasValidArtwork(anime.id, "backdrop")
        return metadataMissing || posterMissing || backdropMissing
    }

    /** A ready, materialized row is treated as canonical so a lower-priority source cannot replace it. */
    private suspend fun hasValidArtwork(animeId: Long, requestedType: String): Boolean {
        val type = if (requestedType == "banner") "backdrop" else requestedType
        return library.artworkForAnime(animeId).any { row ->
            row.artworkType.equals(type, ignoreCase = true) &&
                row.status.equals("ready", ignoreCase = true) &&
                row.localPath?.let { path -> File(path).isFile && File(path).length() > 0L } == true
        }
    }

    private fun hasMetadataGap(anime: ReiAnixAnimeRecord): Boolean =
        anime.description.isNullOrBlank() ||
            anime.episodesCount == null ||
            anime.duration == null ||
            anime.status.isNullOrBlank() ||
            anime.format.isNullOrBlank() ||
            anime.year == null ||
            anime.score == null

    private suspend fun enrichOne(initial: ReiAnixAnimeRecord): Boolean {
        var anime = library.getAnime(initial.id) ?: return false
        val identity = anime.toExternalCatalogIdentity()
        val posterReadyBeforeAniListArtwork = hasValidArtwork(anime.id, "poster")
        val backdropReadyBeforeAniListArtwork = hasValidArtwork(anime.id, "backdrop")
        val hasMissingAniListArtworkCandidate =
            (!posterReadyBeforeAniListArtwork && !anime.coverUrl.isNullOrBlank()) ||
                (!backdropReadyBeforeAniListArtwork && !anime.bannerUrl.isNullOrBlank())
        if (hasMissingAniListArtworkCandidate) {
            // Try the already-persisted AniList candidate first, including a backdrop-only gap.
            // The canonical artwork service reuses validated poster bytes and does not redownload them.
            // The current artwork pipeline owns asynchronous downloads and publication.
            // Enqueue canonical AniList artwork without blocking catalog enrichment.
            artwork.requestAnimeArtwork(anime.id)
        }

        var ids = library.getExternalCatalogIds(anime.id) ?: ReiAnixExternalCatalogIds()
        var changed = false

        // AniMap is an identifier crosswalk only. Never search it by title or consume its images.
        // Existing AniList/Kitsu/MAL/AniDB IDs are the only permitted lookup keys.
        identifierResolver?.takeIf { ids.hasAnyId }?.let { resolver ->
            val mappingResult = try {
                resolver.resolveIds(ids)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                record(ReiAnixCatalogSource.ANIMAP, "IDENTIFIER_LOOKUP_FAILED", anime.id, error)
                null
            }
            when (mappingResult) {
                is ReiAnixCatalogResult.Success -> {
                    val mapping = mappingResult.value
                    if (mapping != null) {
                        val mappingEvaluation = ReiAnixCatalogIdentityMatcher.evaluateMapping(
                            anime.id, identity, mapping, ids,
                        )
                        val mappingMatch = mappingEvaluation.value
                        if (mappingMatch == null) {
                            val result = if (mappingEvaluation.decision == ReiAnixCatalogIdentityDecision.PROBABLE) {
                                "PROBABLE_CROSSWALK_REQUIRES_CONFIRMATION"
                            } else {
                                "IDENTITY_MISMATCH_REJECTED"
                            }
                            record(ReiAnixCatalogSource.ANIMAP, result, anime.id)
                        } else {
                            try {
                                ids = library.saveExternalCatalogIds(mappingMatch)
                                changed = true
                                record(ReiAnixCatalogSource.ANIMAP, "VERIFIED_CROSSWALK_PERSISTED", anime.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                record(ReiAnixCatalogSource.ANIMAP, "CROSSWALK_NOT_PERSISTED", anime.id, error)
                            }
                        }
                    } else {
                        record(ReiAnixCatalogSource.ANIMAP, "NO_CROSSWALK_FOUND", anime.id)
                    }
                }
                is ReiAnixCatalogResult.Failure ->
                    record(ReiAnixCatalogSource.ANIMAP, "IDENTIFIER_LOOKUP_FAILED", anime.id)
                null -> Unit
            }
        }

        for (source in SUPPLEMENTAL_SOURCES) {
            currentCoroutineContext().ensureActive()
            val needsMetadata = !hasManualCatalogOwnership(anime) && hasMetadataGap(anime)
            val needsPoster = !hasValidArtwork(anime.id, "poster")
            val needsBackdrop = !hasValidArtwork(anime.id, "backdrop")
            if (!needsMetadata && !needsPoster && !needsBackdrop) break

            val previousAttempt = library.externalCatalogAttemptAt(anime.id, source.key)
            val knownId = ids.idFor(source)
            if (knownId == null && previousAttempt != null &&
                System.currentTimeMillis() / 1_000.0 - previousAttempt < SOURCE_RETRY_COOLDOWN_SECONDS
            ) continue

            val provider = providers.provider(source) ?: continue
            val resolution = try {
                resolve(provider, anime, identity, ids)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                record(source, "PROVIDER_FAILED", anime.id, error)
                null
            }

            library.markExternalCatalogAttempt(anime.id, source.key)
            val resolved = resolution ?: continue
            val verified = resolved.verified
            if (verified != null) {
                // Only CONFIRMED identity is allowed to persist supplemental IDs or metadata.
                // A persistence error must not discard an independently usable artwork URL.
                val savedIds = try {
                    library.saveExternalCatalogIds(verified.match)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    record(source, "VERIFIED_IDS_NOT_PERSISTED", anime.id, error)
                    null
                }
                if (savedIds != null) {
                    ids = savedIds
                    changed = true
                    if (hasManualCatalogOwnership(anime)) {
                        record(source, "VERIFIED_ARTWORK_ONLY_MANUAL_METADATA_PRESERVED", anime.id)
                    } else {
                        try {
                            anime = library.mergeExternalCatalogMetadata(verified.match, verified.remote)
                            record(source, "VERIFIED_METADATA_MERGED", anime.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            record(source, "VERIFIED_METADATA_NOT_MERGED", anime.id, error)
                        }
                    }
                }
            }

            if (!resolved.artworkQueued) {
                val outcome = when (resolved.decision) {
                    ReiAnixCatalogIdentityDecision.PROBABLE -> "PROBABLE_WITHOUT_ARTWORK"
                    ReiAnixCatalogIdentityDecision.AMBIGUOUS -> "AMBIGUOUS_SEARCH_WITHOUT_ARTWORK"
                    ReiAnixCatalogIdentityDecision.REJECTED -> "REJECTED_SEARCH_WITHOUT_ARTWORK"
                    ReiAnixCatalogIdentityDecision.UNRESOLVED -> "UNRESOLVED_SEARCH_WITHOUT_ARTWORK"
                    ReiAnixCatalogIdentityDecision.CONFIRMED -> "CONFIRMED_RESULT_WITHOUT_ARTWORK"
                }
                record(source, outcome, anime.id)
            } else {
                if (verified == null) {
                    // Identity confidence only limits metadata and ID writes. It does not
                    // determine whether an independently valid image may be materialized.
                    record(source, "ARTWORK_QUEUED_WITHOUT_IDENTITY_ASSOCIATION", anime.id)
                }
                changed = true
            }
        }
        return changed
    }

    /**
     * Metadata/ID persistence remains confirmed-only. Artwork may use a separately selected
     * exact-title candidate with no explicit metadata contradictions.
     */
    private data class Resolution(
        val decision: ReiAnixCatalogIdentityDecision,
        val verified: VerifiedRemote? = null,
        val artworkQueued: Boolean = false,
    )

    /**
     * Artwork is queued from every successful title-search response as soon as that response
     * completes. The identity matcher is used only after all searches finish, and only for
     * metadata/identifier persistence. An uncertain or contradictory candidate can still provide
     * a poster/backdrop through the existing technically validated artwork pipeline.
     */
    private suspend fun resolve(
        provider: ReiAnixAnimeCatalogProvider,
        anime: ReiAnixAnimeRecord,
        identity: ReiAnixCatalogIdentity,
        ids: ReiAnixExternalCatalogIds,
    ): Resolution {
        var directDetail: ReiAnixCatalogAnime? = null
        val knownId = ids.idFor(provider.source)
        if (!knownId.isNullOrBlank()) {
            when (val result = provider.getAnime(knownId)) {
                is ReiAnixCatalogResult.Failure -> {
                    record(provider.source, "DETAIL_REQUEST_FAILED", anime.id)
                }
                is ReiAnixCatalogResult.Success -> {
                    directDetail = result.value
                }
            }
        }

        val candidates = linkedMapOf<String, ReiAnixCatalogAnime>()
        var incompleteSearch = false
        var artworkQueued = false
        val seenArtworkCandidates = HashSet<ReiAnixArtworkCandidate>()

        directDetail?.let { remote ->
            remote.ids.idFor(remote.source)?.takeIf(String::isNotBlank)?.let { candidates.putIfAbsent(it, remote) }
            artworkQueued = queueArtworkCandidates(anime.id, listOf(remote), seenArtworkCandidates) || artworkQueued
        }

        val searchTerms = allDistinctArtworkSearchTitles(identity.titles)
        if (searchTerms.isNotEmpty()) {
            runParallelArtworkSearches(
                titles = searchTerms,
                maxConcurrent = MAX_PARALLEL_PROVIDER_TITLE_SEARCHES,
                search = { query -> provider.searchAnime(query, SEARCH_LIMIT) },
                onCompleted = { query, outcome ->
                    outcome.fold(
                        onSuccess = { result ->
                            when (result) {
                                is ReiAnixCatalogResult.Failure -> {
                                    incompleteSearch = true
                                    record(provider.source, "SEARCH_REQUEST_FAILED", anime.id)
                                }
                                is ReiAnixCatalogResult.Success -> {
                                    if (!result.isComplete) incompleteSearch = true
                                    result.value.forEach { remote ->
                                        val remoteId = remote.ids.idFor(remote.source)
                                        if (remoteId.isNullOrBlank()) {
                                            incompleteSearch = true
                                        } else {
                                            candidates.putIfAbsent(remoteId, remote)
                                        }
                                    }
                                    // Do not wait for the other aliases. A valid image from this
                                    // response immediately enters the normal cache/SQLite/Compose path.
                                    artworkQueued = queueArtworkCandidates(
                                        anime.id,
                                        result.value,
                                        seenArtworkCandidates,
                                    ) || artworkQueued
                                }
                            }
                        },
                        onFailure = { error ->
                            incompleteSearch = true
                            record(provider.source, "SEARCH_REQUEST_FAILED", anime.id, error)
                        },
                    )
                },
            )
        }

        if (incompleteSearch) {
            record(provider.source, "INCOMPLETE_SEARCH_METADATA_REMAINS_UNRESOLVED", anime.id)
            return Resolution(
                decision = ReiAnixCatalogIdentityDecision.UNRESOLVED,
                artworkQueued = artworkQueued,
            )
        }

        val evaluation = ReiAnixCatalogIdentityMatcher.evaluateCandidates(
            anime.id, identity, candidates.values, ids,
        )
        val verified = evaluation.value?.let { VerifiedRemote(it.remote, it.match) }
        return Resolution(
            decision = evaluation.decision,
            verified = verified,
            artworkQueued = artworkQueued,
        )
    }

    private suspend fun queueArtworkCandidates(
        animeId: Long,
        remotes: Collection<ReiAnixCatalogAnime>,
        seen: MutableSet<ReiAnixArtworkCandidate>,
    ): Boolean {
        if (remotes.isEmpty()) return false
        val posterAlreadyReady = hasValidArtwork(animeId, "poster")
        val backdropAlreadyReady = hasValidArtwork(animeId, "backdrop")
        val pending = remotes.asSequence()
            .flatMap { it.artworkCandidates().asSequence() }
            .mapNotNull { candidate ->
                val artworkType = when (candidate.type) {
                    ReiAnixCatalogArtworkType.POSTER -> "poster"
                    ReiAnixCatalogArtworkType.BACKDROP,
                    ReiAnixCatalogArtworkType.BANNER -> "backdrop"
                }
                if ((artworkType == "poster" && posterAlreadyReady) ||
                    (artworkType == "backdrop" && backdropAlreadyReady)
                ) {
                    null
                } else {
                    ReiAnixArtworkCandidate(
                        anilistId = 0L,
                        posterUrl = candidate.url.takeIf { artworkType == "poster" },
                        bannerUrl = candidate.url.takeIf { artworkType == "backdrop" },
                        source = candidate.source.key,
                    )
                }
            }
            .filter(seen::add)
            .take(MAX_QUEUED_ARTWORK_CANDIDATES)
            .toList()
        return pending.isNotEmpty() &&
            artwork.requestAnimeArtworkCandidates(animeId, pending, force = true)
    }

    private fun record(
        source: ReiAnixCatalogSource,
        result: String,
        animeId: Long,
        error: Throwable? = null,
    ) {
        ReiAnixDiagnosticTimeline.record(
            name = "EXTERNAL_CATALOG_ENRICHMENT",
            severity = if (error == null && result.endsWith("FAILED")) "WARN" else "INFO",
            source = source.key,
            result = result,
            error = error,
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
        const val MAX_PARALLEL_PROVIDER_TITLE_SEARCHES = 4
        const val MAX_QUEUED_ARTWORK_CANDIDATES = 5
        const val SOURCE_RETRY_COOLDOWN_SECONDS = 6L * 60L * 60L
    }
}
