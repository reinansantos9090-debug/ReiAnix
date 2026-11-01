package com.reiflix.reiflix_local.data.catalog

import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
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
     * Enriches a small round-robin slice. This method never performs a full-catalog network scan.
     * Every network and image operation is suspendable and uses the existing IO-backed services.
     */
    suspend fun enrichLibraryGaps(batchSize: Int = DEFAULT_BATCH_SIZE): Int =
        enrichmentPassMutex.withLock {
            runEnrichmentPass(batchSize)
        }

    private suspend fun runEnrichmentPass(batchSize: Int): Int {
        require(batchSize in 1..MAX_BATCH_SIZE)
        var cursor = library.externalCatalogCursor()
        var rows = library.listAnimeAfterExternalCatalogCursor(cursor, batchSize)
        if (rows.isEmpty() && cursor > 0L) {
            cursor = 0L
            rows = library.listAnimeAfterExternalCatalogCursor(0L, batchSize)
        }
        if (rows.isEmpty()) return 0

        var enriched = 0
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            if (isEligible(row) && enrichOne(row)) enriched++
            library.setExternalCatalogCursor(row.id)
        }
        return enriched
    }

    private suspend fun isEligible(anime: ReiAnixAnimeRecord): Boolean {
        if (anime.id <= 0L || anime.anilistMatchManual == true ||
            anime.metadataSource.equals("manual", ignoreCase = true) ||
            anime.metadataStatus.equals("manual", ignoreCase = true)
        ) return false
        val metadataMissing = hasMetadataGap(anime)
        val posterMissing = !artwork.hasValidAnimeArtwork(anime.id, "poster")
        val backdropMissing = !artwork.hasValidAnimeArtwork(anime.id, "backdrop")
        val bannerMissing = !artwork.hasValidAnimeArtwork(anime.id, "banner")
        return metadataMissing || posterMissing || backdropMissing || bannerMissing
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
        val posterReadyBeforeAniListArtwork = artwork.hasValidAnimeArtwork(anime.id, "poster")
        val backdropReadyBeforeAniListArtwork = artwork.hasValidAnimeArtwork(anime.id, "backdrop")
        val hasMissingAniListArtworkCandidate =
            (!posterReadyBeforeAniListArtwork && !anime.coverUrl.isNullOrBlank()) ||
                (!backdropReadyBeforeAniListArtwork && !anime.bannerUrl.isNullOrBlank())
        if (hasMissingAniListArtworkCandidate) {
            // Try the already-persisted AniList candidate first, including a backdrop-only gap.
            // The canonical artwork service reuses validated poster bytes and does not redownload them.
            artwork.requestAnimeArtwork(anime.id)
            artwork.awaitAnimeArtwork(anime.id)
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
            val needsMetadata = hasMetadataGap(anime)
            val needsPoster = !artwork.hasValidAnimeArtwork(anime.id, "poster")
            val needsBackdrop = !artwork.hasValidAnimeArtwork(anime.id, "backdrop")
            val needsBanner = !artwork.hasValidAnimeArtwork(anime.id, "banner")
            if (!needsMetadata && !needsPoster && !needsBackdrop && !needsBanner) break

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
            if (resolution == null) continue
            when (resolution) {
                is Resolution.Probable -> {
                    record(source, "PROBABLE_MATCH_REQUIRES_CONFIRMATION", anime.id)
                    continue
                }
                is Resolution.Ambiguous -> {
                    record(source, "AMBIGUOUS_MATCH_REJECTED", anime.id)
                    continue
                }
                is Resolution.Rejected -> {
                    record(source, "IDENTITY_MISMATCH_REJECTED", anime.id)
                    continue
                }
                is Resolution.None -> continue
                is Resolution.Verified -> {
                    val verified = resolution.value
                    try {
                        ids = library.saveExternalCatalogIds(verified.match)
                        anime = library.mergeExternalCatalogMetadata(verified.match, verified.remote)
                        changed = true
                        record(source, "VERIFIED_METADATA_MERGED", anime.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        record(source, "VERIFIED_RESULT_NOT_PERSISTED", anime.id, error)
                        continue
                    }

                    // Every supported provider field is saved under its declared canonical type.
                    // Unclassified fields remain in the DTO and are intentionally not persisted as candidates.
                    for (candidate in verified.remote.artworkCandidates()) {
                        val persistedCandidate = candidate.copy(localAnimeId = anime.id)
                        val type = persistedCandidate.type.key
                        val accepted = artwork.replaceExternalArtworkCandidate(
                            animeId = persistedCandidate.localAnimeId ?: anime.id,
                            artworkType = type,
                            rawUrl = persistedCandidate.url,
                            source = persistedCandidate.source.key,
                            sourceRef = persistedCandidate.sourceReference,
                            variant = persistedCandidate.variant ?: "original",
                            priority = persistedCandidate.priority,
                            width = persistedCandidate.width,
                            height = persistedCandidate.height,
                        )
                        if (!accepted) continue
                        artwork.requestAnimeArtwork(anime.id, force = true)
                        artwork.awaitAnimeArtwork(anime.id)
                        if (artwork.hasValidAnimeArtwork(anime.id, type)) {
                            record(source, "${type.uppercase(Locale.ROOT)}_PUBLISHED", anime.id)
                            changed = true
                        } else {
                            record(source, "${type.uppercase(Locale.ROOT)}_CANDIDATE_PERSISTED", anime.id)
                        }
                    }
                }
            }
        }
        return changed
    }

    private sealed interface Resolution {
        data class Verified(val value: VerifiedRemote) : Resolution
        data object Probable : Resolution
        data object Ambiguous : Resolution
        data object Rejected : Resolution
        data object None : Resolution
    }

    private suspend fun resolve(
        provider: ReiAnixAnimeCatalogProvider,
        anime: ReiAnixAnimeRecord,
        identity: ReiAnixCatalogIdentity,
        ids: ReiAnixExternalCatalogIds,
    ): Resolution {
        val knownId = ids.idFor(provider.source)
        if (!knownId.isNullOrBlank()) {
            return when (val result = provider.getAnime(knownId)) {
                is ReiAnixCatalogResult.Failure -> {
                    record(provider.source, "DETAIL_REQUEST_FAILED", anime.id)
                    Resolution.None
                }
                is ReiAnixCatalogResult.Success -> {
                    val remote = result.value ?: return Resolution.None
                    val evaluation = ReiAnixCatalogIdentityMatcher.evaluate(anime.id, identity, remote, ids)
                    when (evaluation.decision) {
                        ReiAnixCatalogIdentityDecision.CONFIRMED ->
                            Resolution.Verified(VerifiedRemote(remote, requireNotNull(evaluation.value)))
                        ReiAnixCatalogIdentityDecision.PROBABLE -> Resolution.Probable
                        ReiAnixCatalogIdentityDecision.AMBIGUOUS -> Resolution.Ambiguous
                        ReiAnixCatalogIdentityDecision.REJECTED -> Resolution.Rejected
                        ReiAnixCatalogIdentityDecision.UNRESOLVED -> Resolution.None
                    }
                }
            }
        }

        val candidates = linkedMapOf<String, ReiAnixCatalogAnime>()
        for (query in identity.titles.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().take(MAX_SEARCH_TERMS)
        ) {
            currentCoroutineContext().ensureActive()
            when (val result = provider.searchAnime(query, SEARCH_LIMIT)) {
                is ReiAnixCatalogResult.Failure -> record(provider.source, "SEARCH_REQUEST_FAILED", anime.id)
                is ReiAnixCatalogResult.Success -> {
                    result.value.forEach { remote ->
                        val remoteId = remote.ids.idFor(remote.source) ?: return@forEach
                        candidates.putIfAbsent(remoteId, remote)
                    }
                }
            }
        }

        val evaluation = ReiAnixCatalogIdentityMatcher.evaluateCandidates(
            anime.id, identity, candidates.values, ids,
        )
        return when (evaluation.decision) {
            ReiAnixCatalogIdentityDecision.CONFIRMED -> {
                val verified = requireNotNull(evaluation.value)
                Resolution.Verified(VerifiedRemote(verified.remote, verified.match))
            }
            ReiAnixCatalogIdentityDecision.PROBABLE -> Resolution.Probable
            ReiAnixCatalogIdentityDecision.AMBIGUOUS -> Resolution.Ambiguous
            ReiAnixCatalogIdentityDecision.REJECTED -> Resolution.Rejected
            ReiAnixCatalogIdentityDecision.UNRESOLVED -> Resolution.None
        }
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
            ReiAnixCatalogSource.TENRAI,
            ReiAnixCatalogSource.ANIPUB,
        )
        const val DEFAULT_BATCH_SIZE = 12
        const val MAX_BATCH_SIZE = 50
        const val SEARCH_LIMIT = 10
        const val MAX_SEARCH_TERMS = 3
        const val SOURCE_RETRY_COOLDOWN_SECONDS = 6L * 60L * 60L
    }
}
