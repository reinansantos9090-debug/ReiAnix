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

/**
 * Bounded, process-owned supplemental enrichment. AniList remains primary; Kitsu/Jikan are
 * consulted only for metadata gaps or missing usable artwork, and every association is reverified.
 */
class ReiAnixExternalCatalogIntegrationService(
    private val library: ReiAnixSqliteLibraryRepository,
    private val providers: ReiAnixCatalogProviderRegistry,
    private val artwork: ReiAnixArtworkService,
) {
    private data class VerifiedRemote(
        val remote: ReiAnixCatalogAnime,
        val match: ReiAnixCatalogIdentityMatch,
    )

    /**
     * Enriches a small round-robin slice. This method never performs a full-catalog network scan.
     * Every network and image operation is suspendable and uses the existing IO-backed services.
     */
    suspend fun enrichLibraryGaps(batchSize: Int = DEFAULT_BATCH_SIZE): Int {
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
            library.setExternalCatalogCursor(row.id)
            if (!isEligible(row)) continue
            if (enrichOne(row)) enriched++
        }
        return enriched
    }

    private suspend fun isEligible(anime: ReiAnixAnimeRecord): Boolean {
        if (anime.id <= 0L || anime.anilistMatchManual == true ||
            anime.metadataSource.equals("manual", ignoreCase = true) ||
            anime.metadataStatus.equals("manual", ignoreCase = true)
        ) return false
        val artworkReady = artwork.hasValidAnimeArtwork(anime.id, "poster")
        return hasMetadataGap(anime) || !artworkReady
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
        if (!artwork.hasValidAnimeArtwork(anime.id, "poster") && !anime.coverUrl.isNullOrBlank()) {
            // Give the already-persisted AniList candidate its normal first opportunity.
            artwork.requestAnimeArtwork(anime.id)
            artwork.awaitAnimeArtwork(anime.id)
        }

        var ids = library.getExternalCatalogIds(anime.id) ?: ReiAnixExternalCatalogIds()
        var changed = false
        for (source in SUPPLEMENTAL_SOURCES) {
            currentCoroutineContext().ensureActive()
            val needsMetadata = hasMetadataGap(anime)
            val needsPoster = !artwork.hasValidAnimeArtwork(anime.id, "poster")
            val needsBackdrop = !artwork.hasValidAnimeArtwork(anime.id, "backdrop")
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
            } finally {
                // Timestamp is committed below only after a completed provider attempt.
            }

            library.markExternalCatalogAttempt(anime.id, source.key)
            if (resolution == null) continue
            when (resolution) {
                is Resolution.Ambiguous -> {
                    record(source, "AMBIGUOUS_MATCH_REJECTED", anime.id)
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

                    var posterReady = artwork.hasValidAnimeArtwork(anime.id, "poster")
                    if (!posterReady && !verified.remote.coverUrl.isNullOrBlank()) {
                        if (artwork.replaceExternalArtworkCandidate(anime.id, "poster", verified.remote.coverUrl)) {
                            artwork.requestAnimeArtwork(anime.id, force = true)
                            artwork.awaitAnimeArtwork(anime.id)
                        }
                        posterReady = artwork.hasValidAnimeArtwork(anime.id, "poster")
                        if (posterReady) {
                            record(source, "POSTER_PUBLISHED", anime.id)
                            changed = true
                        } else {
                            record(source, "POSTER_CANDIDATE_FAILED", anime.id)
                        }
                    }

                    if (!artwork.hasValidAnimeArtwork(anime.id, "backdrop") &&
                        !verified.remote.backdropUrl.isNullOrBlank() &&
                        artwork.replaceExternalArtworkCandidate(anime.id, "backdrop", verified.remote.backdropUrl)
                    ) {
                        artwork.requestAnimeArtwork(anime.id, force = true)
                        artwork.awaitAnimeArtwork(anime.id)
                        if (artwork.hasValidAnimeArtwork(anime.id, "backdrop")) changed = true
                    }
                }
            }
        }
        return changed
    }

    private sealed interface Resolution {
        data class Verified(val value: VerifiedRemote) : Resolution
        data object Ambiguous : Resolution
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
                    val match = ReiAnixCatalogIdentityMatcher.verify(anime.id, identity, remote)
                        ?: return run {
                            record(provider.source, "PERSISTED_IDENTITY_CONFLICT", anime.id)
                            Resolution.None
                        }
                    Resolution.Verified(VerifiedRemote(remote, match))
                }
            }
        }

        val verified = linkedMapOf<String, VerifiedRemote>()
        for (query in identity.titles.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().take(MAX_SEARCH_TERMS)
        ) {
            currentCoroutineContext().ensureActive()
            when (val result = provider.searchAnime(query, SEARCH_LIMIT)) {
                is ReiAnixCatalogResult.Failure -> record(provider.source, "SEARCH_REQUEST_FAILED", anime.id)
                is ReiAnixCatalogResult.Success -> {
                    result.value.forEach { remote ->
                        val match = ReiAnixCatalogIdentityMatcher.verify(anime.id, identity, remote)
                            ?: return@forEach
                        val remoteId = remote.ids.idFor(remote.source) ?: return@forEach
                        verified.putIfAbsent(remoteId, VerifiedRemote(remote, match))
                    }
                }
            }
        }
        return when (verified.size) {
            0 -> Resolution.None
            1 -> Resolution.Verified(verified.values.first())
            else -> Resolution.Ambiguous
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
            ReiAnixCatalogSource.JIKAN,
        )
        const val DEFAULT_BATCH_SIZE = 12
        const val MAX_BATCH_SIZE = 50
        const val SEARCH_LIMIT = 10
        const val MAX_SEARCH_TERMS = 3
        const val SOURCE_RETRY_COOLDOWN_SECONDS = 6L * 60L * 60L
    }
}
