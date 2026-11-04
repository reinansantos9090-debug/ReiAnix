package com.reiflix.reiflix_local.data.catalog

import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkCandidate
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.io.File
import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer

/**
 * Collect every normalized title variant used by the supplemental providers.
 * The limit is a defensive bound on corrupt/hostile local alias payloads, not
 * the former three-title search preference.
 */
internal fun boundedExternalCatalogSearchTerms(
    titles: Collection<String>,
    limit: Int = 128,
): List<String> {
    require(limit in 1..128)
    return titles.asSequence()
        .flatMap { MediaTitleNormalizer.searchTitleVariants(it).asSequence() }
        .map(String::trim)
        .filter { it.length in 2..120 }
        .distinctBy(ReiAnixCatalogIdentityMatcher::normalizeCatalogTitle)
        .take(limit)
        .toList()
}

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
        val posterMissing = !hasValidArtwork(anime.id, "poster") ||
            artwork.hasProvisionalPosterCandidate(anime.id)
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
        val posterNeedsVerification = artwork.hasProvisionalPosterCandidate(anime.id)
        val backdropReadyBeforeAniListArtwork = hasValidArtwork(anime.id, "backdrop")
        val hasMissingAniListArtworkCandidate =
            ((!posterReadyBeforeAniListArtwork || posterNeedsVerification) && !anime.coverUrl.isNullOrBlank()) ||
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
            val needsPoster = !hasValidArtwork(anime.id, "poster") ||
                artwork.hasProvisionalPosterCandidate(anime.id)
            val needsBackdrop = !hasValidArtwork(anime.id, "backdrop")
            if (!needsMetadata && !needsPoster && !needsBackdrop) break

            val previousAttempt = library.externalCatalogAttemptAt(anime.id, source.key)
            val knownId = ids.idFor(source)
            if (knownId == null && previousAttempt != null &&
                System.currentTimeMillis() / 1_000.0 - previousAttempt < SOURCE_RETRY_COOLDOWN_SECONDS
            ) continue

            val provider = providers.provider(source) ?: continue
            val resolution = try {
                resolve(provider, anime, identity, ids, allowArtwork = needsPoster)
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

            if (resolved.artworkQueued) changed = true
            val remoteArtwork = resolved.artworkCandidate ?: verified?.remote
            if (remoteArtwork == null) {
                val outcome = when (resolved.decision) {
                    ReiAnixCatalogIdentityDecision.PROBABLE -> "PROBABLE_MATCH_HAS_NO_ARTWORK"
                    ReiAnixCatalogIdentityDecision.AMBIGUOUS -> "AMBIGUOUS_NO_SAFE_ARTWORK_CANDIDATE"
                    ReiAnixCatalogIdentityDecision.REJECTED -> "IDENTITY_MISMATCH_REJECTED"
                    ReiAnixCatalogIdentityDecision.UNRESOLVED -> "IDENTITY_UNRESOLVED_NO_ARTWORK"
                    ReiAnixCatalogIdentityDecision.CONFIRMED -> "CONFIRMED_RESULT_HAS_NO_ARTWORK"
                }
                record(source, outcome, anime.id)
                continue
            }
            if (verified == null) {
                val outcome = when (resolved.decision) {
                    ReiAnixCatalogIdentityDecision.PROBABLE -> "PROBABLE_ARTWORK_ONLY"
                    ReiAnixCatalogIdentityDecision.AMBIGUOUS -> "AMBIGUOUS_ARTWORK_CANDIDATE_SELECTED"
                    ReiAnixCatalogIdentityDecision.REJECTED -> "IDENTITY_MISMATCH_ARTWORK_REJECTED"
                    ReiAnixCatalogIdentityDecision.UNRESOLVED -> "PARTIAL_OR_UNRESOLVED_SEARCH_ARTWORK_ONLY"
                    ReiAnixCatalogIdentityDecision.CONFIRMED -> "CONFIRMED_ARTWORK_WITHOUT_METADATA_WRITE"
                }
                record(source, outcome, anime.id)
            }

            // Queue external candidates through the current artwork publication pipeline.
            // The zero AniList ID is intentional: supplemental providers must not create or
            // rewrite the canonical AniList association. The pipeline still protects manual
            // and local artwork and publishes the existing Compose refresh signal.
            val pendingArtwork = remoteArtwork.artworkCandidates().mapNotNull { candidate ->
                val artworkType = when (candidate.type) {
                    ReiAnixCatalogArtworkType.POSTER -> "poster"
                    ReiAnixCatalogArtworkType.BACKDROP,
                    ReiAnixCatalogArtworkType.BANNER -> "backdrop"
                }
                if (hasValidArtwork(anime.id, artworkType)) {
                    record(source, "${artworkType.uppercase(Locale.ROOT)}_SKIPPED_VALID_LOCAL", anime.id)
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
            val additionalArtwork = if (resolved.artworkQueued) {
                pendingArtwork.filter { it.posterUrl.isNullOrBlank() }
            } else {
                pendingArtwork
            }
            if (additionalArtwork.isNotEmpty() &&
                artwork.requestAnimeArtworkCandidates(anime.id, additionalArtwork, force = true)
            ) {
                record(source, "ARTWORK_CANDIDATES_QUEUED", anime.id)
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
        val artworkCandidate: ReiAnixCatalogAnime? = null,
        val artworkQueued: Boolean = false,
    )

    private suspend fun resolve(
        provider: ReiAnixAnimeCatalogProvider,
        anime: ReiAnixAnimeRecord,
        identity: ReiAnixCatalogIdentity,
        ids: ReiAnixExternalCatalogIds,
        allowArtwork: Boolean,
    ): Resolution {
        val knownId = ids.idFor(provider.source)
        if (!knownId.isNullOrBlank()) {
            return when (val result = provider.getAnime(knownId)) {
                is ReiAnixCatalogResult.Failure -> {
                    record(provider.source, "DETAIL_REQUEST_FAILED", anime.id)
                    Resolution(ReiAnixCatalogIdentityDecision.UNRESOLVED)
                }
                is ReiAnixCatalogResult.Success -> {
                    val remote = result.value ?: return Resolution(ReiAnixCatalogIdentityDecision.UNRESOLVED)
                    val evaluation = ReiAnixCatalogIdentityMatcher.evaluate(anime.id, identity, remote, ids)
                    val verified = evaluation.value?.let { VerifiedRemote(remote, it) }
                    // The first image remains provisional and immediate. An already anchored,
                    // CONFIRMED provider identity is also queued as a separate promotion candidate.
                    val hasPoster = remote.artworkCandidates().any {
                        it.type == ReiAnixCatalogArtworkType.POSTER
                    }
                    val exactAliases = ReiAnixCatalogIdentityMatcher.matchingDiscriminativeTitleAliases(
                        identity.titles, remote.alternativeTitles + remote.title,
                    )
                    val evidenceEligible = exactAliases.isNotEmpty() &&
                        evaluation.decision != ReiAnixCatalogIdentityDecision.REJECTED &&
                        evaluation.decision != ReiAnixCatalogIdentityDecision.UNRESOLVED
                    val evidenceKey = artworkEvidenceRecordKey(remote)
                    val queued = allowArtwork && hasPoster &&
                        queueArtworkCandidates(
                            animeId = anime.id,
                            remoteCandidates = listOf(remote),
                            identityVerified = verified != null,
                            identityEligibleRecordKeys = if (evidenceEligible && evidenceKey != null) setOf(evidenceKey) else emptySet(),
                        )
                    Resolution(
                        evaluation.decision,
                        verified,
                        remote.takeIf { hasPoster },
                        queued,
                    )
                }
            }
        }

        val queries = boundedExternalCatalogSearchTerms(identity.titles, MAX_SEARCH_TERMS)
        if (queries.isEmpty()) return Resolution(ReiAnixCatalogIdentityDecision.UNRESOLVED)

        val candidates = linkedMapOf<String, ReiAnixCatalogAnime>()
        var incompleteSearch = false
        var firstArtworkCandidate: ReiAnixCatalogAnime? = null
        var artworkQueued = false

        // Launch every title query together. The shared provider registry still enforces
        // its process-wide request limit, while the result channel lets the first response
        // publish its poster without waiting for slower aliases.
        coroutineScope {
            val queryQueue = Channel<String>(Channel.UNLIMITED)
            val results = Channel<Pair<String, ReiAnixCatalogResult<List<ReiAnixCatalogAnime>>>>(Channel.UNLIMITED)
            val aliasAttribution = ReiAnixCatalogIdentityMatcher.searchAliasAttribution(identity.titles)
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
                        results.send(query to result)
                    }
                }
            }
            for (query in queries) queryQueue.send(query)
            queryQueue.close()

            repeat(queries.size) {
                currentCoroutineContext().ensureActive()
                val (completedQuery, result) = results.receive()
                when (result) {
                    is ReiAnixCatalogResult.Failure -> {
                        incompleteSearch = true
                        record(provider.source, "SEARCH_REQUEST_FAILED", anime.id)
                    }
                    is ReiAnixCatalogResult.Success -> {
                        val returned = result.value
                        returned.forEach { remote ->
                            val remoteId = remote.ids.idFor(remote.source)
                            if (remoteId.isNullOrBlank()) {
                                incompleteSearch = true
                            } else {
                                candidates.putIfAbsent(remoteId, remote)
                            }
                        }

                        // Artwork is independent from identity confirmation. Queue every
                        // poster returned by this completed alias search; later results join
                        // the existing ArtworkService job as fallbacks if earlier downloads fail.
                        if (allowArtwork) {
                            val withPosters = returned.filter { remote ->
                                remote.artworkCandidates().any {
                                    it.type == ReiAnixCatalogArtworkType.POSTER
                                }
                            }
                            if (withPosters.isNotEmpty()) {
                                if (firstArtworkCandidate == null) {
                                    firstArtworkCandidate = withPosters.first()
                                }
                                val queryAliasKeys = aliasAttribution[
                                    ReiAnixCatalogIdentityMatcher.normalizeCatalogTitle(completedQuery)
                                ].orEmpty()
                                val eligibleKeys = withPosters.mapNotNull { remote ->
                                    val recordKey = artworkEvidenceRecordKey(remote)
                                    val exactAliases = ReiAnixCatalogIdentityMatcher.matchingDiscriminativeTitleAliases(
                                        identity.titles, remote.alternativeTitles + remote.title,
                                    )
                                    val evaluation = ReiAnixCatalogIdentityMatcher.evaluate(
                                        anime.id, identity, remote, ids,
                                    )
                                    recordKey?.takeIf {
                                        exactAliases.isNotEmpty() &&
                                            evaluation.decision != ReiAnixCatalogIdentityDecision.REJECTED &&
                                            evaluation.decision != ReiAnixCatalogIdentityDecision.UNRESOLVED
                                    }
                                }.toSet()
                                val aliasKeysByRecord = eligibleKeys.associateWith { queryAliasKeys }
                                if (queueArtworkCandidates(
                                        animeId = anime.id,
                                        remoteCandidates = withPosters,
                                        matchedTitleAliasesByRecord = aliasKeysByRecord,
                                        identityEligibleRecordKeys = eligibleKeys,
                                    )
                                ) {
                                    artworkQueued = true
                                }
                            }
                        }
                    }
                }
            }
            workers.joinAll()
            results.close()
        }

        // Candidates from successful aliases remain usable even when a different alias
        // fails. Only the metadata/ID decision is withheld for an incomplete search set.
        val artworkCandidate = firstArtworkCandidate
            ?: ReiAnixCatalogIdentityMatcher.selectArtworkCandidate(anime.id, identity, candidates.values, ids)
        if (incompleteSearch) {
            record(
                provider.source,
                if (artworkCandidate != null) "INCOMPLETE_SEARCH_ARTWORK_CANDIDATE_RETAINED"
                else "INCOMPLETE_SEARCH_CANDIDATE_SET_REJECTED",
                anime.id,
            )
            return Resolution(
                decision = ReiAnixCatalogIdentityDecision.UNRESOLVED,
                artworkCandidate = artworkCandidate,
                artworkQueued = artworkQueued,
            )
        }

        val evaluation = ReiAnixCatalogIdentityMatcher.evaluateCandidates(
            anime.id, identity, candidates.values, ids,
        )
        val verified = evaluation.value?.let { VerifiedRemote(it.remote, it.match) }
        if (allowArtwork && !incompleteSearch && verified != null &&
            verified.remote.artworkCandidates().any { it.type == ReiAnixCatalogArtworkType.POSTER }
        ) {
            if (queueArtworkCandidates(anime.id, listOf(verified.remote), identityVerified = true)) {
                artworkQueued = true
                record(provider.source, "VERIFIED_ARTWORK_CANDIDATE_QUEUED", anime.id)
            }
        }
        return Resolution(
            decision = evaluation.decision,
            verified = verified,
            artworkCandidate = artworkCandidate ?: verified?.remote,
            artworkQueued = artworkQueued,
        )
    }

    /**
     * Sends image-only candidates through the one existing artwork/cache/SQLite pipeline.
     * The zero AniList ID prevents guessing or rewriting the local AniList association.
     */
    private fun queueArtworkCandidates(
        animeId: Long,
        remoteCandidates: List<ReiAnixCatalogAnime>,
        identityVerified: Boolean = false,
        matchedTitleAliasesByRecord: Map<String, Set<String>> = emptyMap(),
        identityEligibleRecordKeys: Set<String> = emptySet(),
    ): Boolean {
        val pending = remoteCandidates.asSequence()
            .flatMap { remote ->
                val recordKey = artworkEvidenceRecordKey(remote)
                remote.artworkCandidates().asSequence()
                    .filter { it.type == ReiAnixCatalogArtworkType.POSTER }
                    .map { candidate -> candidate to recordKey }
            }
            .distinctBy { it.first.url }
            .sortedWith(
                compareByDescending<Pair<ReiAnixCatalogArtworkCandidate, String?>> {
                    it.second?.let { key -> key in identityEligibleRecordKeys } == true
                }.thenByDescending { it.second?.let { key -> matchedTitleAliasesByRecord[key]?.size } ?: 0 },
            )
            .take(MAX_SEARCH_ARTWORK_CANDIDATES)
            .map { (candidate, recordKey) ->
                ReiAnixArtworkCandidate(
                    anilistId = 0L,
                    posterUrl = candidate.url,
                    bannerUrl = null,
                    source = candidate.source.key,
                    identityVerified = identityVerified,
                    identityEvidenceKey = recordKey,
                    matchedTitleAliases = recordKey?.let { matchedTitleAliasesByRecord[it] }.orEmpty(),
                    identityEvidenceEligible = recordKey?.let { it in identityEligibleRecordKeys } == true,
                )
            }
            .toList()
        return pending.isNotEmpty() && artwork.requestAnimeArtworkCandidates(animeId, pending, force = false)
    }

    private fun artworkEvidenceRecordKey(remote: ReiAnixCatalogAnime): String? =
        remote.ids.idFor(remote.source)?.takeIf(String::isNotBlank)
            ?.let { remote.source.key + ":" + it }

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
        const val MAX_SEARCH_TERMS = 128
        const val MAX_SEARCH_ARTWORK_CANDIDATES = 2
        const val TITLE_SEARCH_CONCURRENCY = 2
        const val SOURCE_RETRY_COOLDOWN_SECONDS = 6L * 60L * 60L
    }
}
