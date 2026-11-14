package com.reianix.reianix_local.data.catalog

import com.reianix.reianix_local.data.artwork.ReiAnixArtworkService
import com.reianix.reianix_local.data.artwork.ReiAnixArtworkCandidate
import com.reianix.reianix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reianix.reianix_local.data.sqlite.ReiAnixAnimeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reianix.reianix_local.data.sqlite.externalCatalogAttemptAt
import com.reianix.reianix_local.data.sqlite.externalCatalogCursor
import com.reianix.reianix_local.data.sqlite.findCachedPostersWithMetadataGaps
import com.reianix.reianix_local.data.sqlite.candidateMetadataRecoveryAttemptAt
import com.reianix.reianix_local.data.sqlite.markCandidateMetadataRecoveryAttempt
import com.reianix.reianix_local.data.sqlite.getExternalCatalogIds
import com.reianix.reianix_local.data.sqlite.listAnimeAfterExternalCatalogCursor
import com.reianix.reianix_local.data.sqlite.markExternalCatalogAttempt
import com.reianix.reianix_local.data.sqlite.mergeExternalCatalogMetadata
import com.reianix.reianix_local.data.sqlite.saveExternalCatalogIds
import com.reianix.reianix_local.data.sqlite.setExternalCatalogCursor
import com.reianix.reianix_local.data.sqlite.toExternalCatalogIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val candidateMetadataPublisher: ((Long, ReiAnixCatalogAnime) -> Unit)? = null,
    private val metadataCommitPublisher: ((Long) -> Unit)? = null,
) {
    private val enrichmentPassMutex = Mutex()

    private data class VerifiedRemote(
        val remote: ReiAnixCatalogAnime,
        val match: ReiAnixCatalogIdentityMatch,
    )

    /**
     * Returns anime IDs without a usable local poster. This deliberately reads only the
     * anime/artwork tables: loading the full catalog snapshot here would also materialize
     * every episode, thumbnail and genre just to discover a list of missing poster IDs.
     */
    suspend fun missingPosterAnimeIds(): List<Long> = withContext(Dispatchers.IO) {
        library.database.read { db ->
            db.rawQuery(
                """
                SELECT a.id, art.local_path
                FROM anime a
                LEFT JOIN artwork art
                  ON art.entity_id=CAST(a.id AS TEXT)
                 AND art.entity_type IN ('anime','movie')
                 AND art.artwork_type='poster'
                 AND LOWER(COALESCE(art.status,''))='ready'
                 AND TRIM(COALESCE(art.local_path,''))!=''
                ORDER BY a.id, art.priority DESC, art.updated_at DESC, art.id DESC
                """.trimIndent(),
                null,
            ).use { cursor ->
                val missingAnimeIds = ArrayList<Long>()
                var currentAnimeId: Long? = null
                var hasUsablePoster = false

                fun finishCurrentAnime() {
                    val completedId = currentAnimeId ?: return
                    if (!hasUsablePoster) missingAnimeIds += completedId
                }

                while (cursor.moveToNext()) {
                    val animeId = cursor.getLong(0)
                    if (currentAnimeId != animeId) {
                        finishCurrentAnime()
                        currentAnimeId = animeId
                        hasUsablePoster = false
                    }
                    if (!hasUsablePoster) {
                        val path = cursor.getString(1)?.trim().orEmpty()
                        if (path.isNotEmpty()) {
                            val file = File(path)
                            if (file.isFile && file.length() > 0L && file.canRead()) {
                                val options = android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                hasUsablePoster = runCatching {
                                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                                }.isSuccess && options.outWidth > 0 && options.outHeight > 0
                            }
                        }
                    }
                }
                finishCurrentAnime()
                missingAnimeIds
            }
        }
    }

    /**
     * Traverses every eligible anime with bounded SQLite keyset pages. Provider calls and
     * artwork materialization remain sequential per anime and use the existing shared services.
     */
    suspend fun enrichLibraryGaps(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        restartFromLibraryScan: Boolean = false,
        forceRetry: Boolean = false,
    ): Int = enrichmentPassMutex.withLock {
        require(batchSize in 1..MAX_BATCH_SIZE)
        if (restartFromLibraryScan) library.setExternalCatalogCursor(0L)
        runEnrichmentPass(batchSize, forceRetry)
    }

    /**
     * Recover legacy entries whose poster was downloaded in a previous run but whose
     * metadata was rejected by the old strict matcher. This is a separate, read-only
     * catalog lookup: it never calls any artwork request or changes the artwork cursor.
     *
     * Defer while posters/backdrops are actively downloading so metadata-only network
     * traffic does not compete with the existing image pipeline.
     */
    suspend fun recoverMetadataForReadyPosters(
        maxRecords: Int = 100,
    ): Int = enrichmentPassMutex.withLock {
        require(maxRecords in 1..200)
        val provider = providers.provider(ReiAnixCatalogSource.KITSU) ?: return@withLock 0
        val artworkDownloading = library.database.read { db ->
            db.rawQuery(
                "SELECT 1 FROM artwork WHERE artwork_type IN ('poster','backdrop') " +
                    "AND status='downloading' AND last_attempt_at>=? LIMIT 1",
                arrayOf((System.currentTimeMillis() / 1_000.0 - 120.0).toString()),
            ).use { it.moveToFirst() }
        }
        if (artworkDownloading) return@withLock 0

        val targets = library.findCachedPostersWithMetadataGaps(maxRecords)
        var offered = 0
        for (target in targets) {
            // Let the AniList-specific metadata pass own an AniList poster.
            if (target.posterSource.equals("anilist", ignoreCase = true)) continue
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis() / 1_000.0
            val previous = library.candidateMetadataRecoveryAttemptAt(target.animeId)
            if (previous != null && now - previous < SOURCE_RETRY_COOLDOWN_SECONDS) continue
            // A distinct metadata-only retry marker does not change artwork attempts,
            // artwork timestamps, or the persisted supplemental catalog scan cursor.
            library.markCandidateMetadataRecoveryAttempt(target.animeId, now)
            val result = try {
                provider.searchAnime(target.title, SEARCH_LIMIT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                record(ReiAnixCatalogSource.KITSU, "CACHED_POSTER_METADATA_LOOKUP_FAILED", target.animeId, error)
                continue
            }
            when (result) {
                is ReiAnixCatalogResult.Success -> {
                    val eligible = result.value.filterNot(::isCatalogMusicVideo)
                    val candidate = eligible.firstOrNull {
                        target.posterUrl != null && it.coverUrl == target.posterUrl
                    } ?: eligible.firstOrNull {
                        !it.synopsis.isNullOrBlank()
                    } ?: eligible.firstOrNull()
                    if (candidate != null) {
                        candidateMetadataPublisher?.invoke(target.animeId, candidate)
                        offered++
                    } else {
                        record(ReiAnixCatalogSource.KITSU, "CACHED_POSTER_METADATA_NOT_FOUND", target.animeId)
                    }
                }
                is ReiAnixCatalogResult.Failure ->
                    record(ReiAnixCatalogSource.KITSU, "CACHED_POSTER_METADATA_LOOKUP_FAILED", target.animeId)
            }
        }
        offered
    }

    /**
     * Rechecks only the supplied anime for missing poster artwork. Unlike a folder-scan
     * enrichment pass, this never rewinds or drains the full catalog cursor and never
     * updates identity/metadata; supplemental candidates are artwork-only.
     */
    suspend fun enrichMissingArtworkForAnime(
        animeIds: List<Long>,
        maxRecords: Int = 12,
        forceRetry: Boolean = false,
    ): Int = enrichmentPassMutex.withLock {
        require(maxRecords in 1..24)
        var enriched = 0
        val selectedIds = animeIds.asSequence()
            .filter { it > 0L }
            .distinct()
            .take(maxRecords)
            .toList()
        for (animeId in selectedIds) {
            currentCoroutineContext().ensureActive()
            val anime = library.getAnime(animeId) ?: continue
            if (hasValidArtwork(animeId, "poster")) continue
            if (enrichOne(anime, forceRetry, artworkOnly = true)) enriched++
        }
        enriched
    }

    /**
     * Drain the eligible catalog through keyset-paginated SQLite pages. The persisted
     * anime ID cursor advances only after a row finishes, so cancellation resumes at
     * the first unfinished row and inserts made during a pass are visible to later pages.
     * A completed cursor is deliberately not wrapped: foreground resumes do not rescan
     * completed records. A confirmed library scan explicitly rewinds the same cursor.
     */
    private suspend fun runEnrichmentPass(batchSize: Int, forceRetry: Boolean): Int {
        require(batchSize in 1..MAX_BATCH_SIZE)
        var cursor = library.externalCatalogCursor()
        var enriched = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = library.listAnimeAfterExternalCatalogCursor(cursor, batchSize)
            if (rows.isEmpty()) break

            for (row in rows) {
                currentCoroutineContext().ensureActive()
                if (isEligible(row) && enrichOne(row, forceRetry)) enriched++
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

    private suspend fun enrichOne(
        initial: ReiAnixAnimeRecord,
        forceRetry: Boolean,
        artworkOnly: Boolean = false,
    ): Boolean {
        var anime = library.getAnime(initial.id) ?: return false
        val identity = anime.toExternalCatalogIdentity()
        val posterReadyBeforeAniListArtwork = hasValidArtwork(anime.id, "poster")
        val backdropReadyBeforeAniListArtwork = hasValidArtwork(anime.id, "backdrop")
        val hasMissingAniListArtworkCandidate =
            (!posterReadyBeforeAniListArtwork && !anime.coverUrl.isNullOrBlank()) ||
                (!backdropReadyBeforeAniListArtwork && !anime.bannerUrl.isNullOrBlank())
        if (!artworkOnly && hasMissingAniListArtworkCandidate) {
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
        if (!artworkOnly) {
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
        }

        for (source in SUPPLEMENTAL_SOURCES) {
            currentCoroutineContext().ensureActive()
            val needsMetadata = !artworkOnly && !hasManualCatalogOwnership(anime) && hasMetadataGap(anime)
            val needsPoster = !hasValidArtwork(anime.id, "poster")
            val needsBackdrop = !artworkOnly && !hasValidArtwork(anime.id, "backdrop")
            if (!needsMetadata && !needsPoster && !needsBackdrop) break

            val previousAttempt = library.externalCatalogAttemptAt(anime.id, source.key)
            val knownId = ids.idFor(source)
            if (!forceRetry && knownId == null && previousAttempt != null &&
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
            if (verified != null && !artworkOnly) {
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
                            metadataCommitPublisher?.invoke(anime.id)
                            record(source, "VERIFIED_METADATA_MERGED", anime.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            record(source, "VERIFIED_METADATA_NOT_MERGED", anime.id, error)
                        }
                    }
                }
            }

            val remoteArtworkCandidates = resolved.artworkCandidates
            if (remoteArtworkCandidates.isEmpty()) {
                record(source, "NO_USABLE_ARTWORK_CANDIDATES", anime.id)
                continue
            }

            // Queue external candidates through the current artwork publication pipeline.
            // The zero AniList ID is intentional: supplemental providers must not create or
            // rewrite the canonical AniList association. The pipeline still protects manual
            // and local artwork and publishes the existing Compose refresh signal.
            val pendingArtwork = remoteArtworkCandidates
                .flatMap { it.artworkCandidates() }
                .filter { !artworkOnly || it.type == ReiAnixCatalogArtworkType.POSTER }
                .distinctBy { "${it.source.key}|${it.externalRecordId}|${it.type.key}|${it.url}" }
                .mapNotNull { candidate ->
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
            if (pendingArtwork.isNotEmpty() &&
                artwork.requestAnimeArtworkCandidates(anime.id, pendingArtwork, force = true)
            ) {
                record(source, "ARTWORK_CANDIDATES_QUEUED", anime.id)
                changed = true
            }

            // Metadata is already present in the same provider response that supplied
            // the poster candidates. Write missing fields independently, even when the
            // strict identity matcher cannot confirm IDs. Never wait for this operation
            // before sending images through the original artwork pipeline.
            if (needsMetadata) {
                val posterSource = remoteArtworkCandidates.firstOrNull { !it.coverUrl.isNullOrBlank() }
                    ?: remoteArtworkCandidates.first()
                candidateMetadataPublisher?.invoke(anime.id, posterSource)
            }
        }
        return changed
    }

    /** Metadata/ID persistence remains confirmed-only; artwork candidates are independent. */
    private data class Resolution(
        val decision: ReiAnixCatalogIdentityDecision,
        val verified: VerifiedRemote? = null,
        val artworkCandidates: List<ReiAnixCatalogAnime> = emptyList(),
    )

    private suspend fun resolve(
        provider: ReiAnixAnimeCatalogProvider,
        anime: ReiAnixAnimeRecord,
        identity: ReiAnixCatalogIdentity,
        ids: ReiAnixExternalCatalogIds,
    ): Resolution {
        val knownId = ids.idFor(provider.source)
        if (!knownId.isNullOrBlank()) {
            when (val result = provider.getAnime(knownId)) {
                is ReiAnixCatalogResult.Failure -> {
                    record(provider.source, "DETAIL_REQUEST_FAILED", anime.id)
                    return Resolution(ReiAnixCatalogIdentityDecision.UNRESOLVED)
                }
                is ReiAnixCatalogResult.Success -> {
                    val remote = result.value ?: return Resolution(ReiAnixCatalogIdentityDecision.UNRESOLVED)
                    if (isCatalogMusicVideo(remote)) {
                        // Only an old MUSIC association takes this path. Search the already
                        // configured title terms for a non-MUSIC cover instead of stopping.
                        // Valid existing IDs still keep the original single-fetch fast path.
                        record(provider.source, "MUSIC_VIDEO_CANDIDATE_SKIPPED", anime.id)
                    } else {
                        val evaluation = ReiAnixCatalogIdentityMatcher.evaluate(anime.id, identity, remote, ids)
                        val artworkCandidates = usableCatalogArtworkCandidates(listOf(remote))
                        val verified = evaluation.value?.let { VerifiedRemote(remote, it) }
                        return Resolution(evaluation.decision, verified, artworkCandidates)
                    }
                }
            }
        }

        val candidates = linkedMapOf<String, ReiAnixCatalogAnime>()
        var incompleteSearch = false
        for (query in identity.titles.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().take(MAX_SEARCH_TERMS)
        ) {
            currentCoroutineContext().ensureActive()
            when (val result = provider.searchAnime(query, SEARCH_LIMIT)) {
                is ReiAnixCatalogResult.Failure -> {
                    incompleteSearch = true
                    record(provider.source, "SEARCH_REQUEST_FAILED", anime.id)
                }
                is ReiAnixCatalogResult.Success -> {
                    result.value.forEach { remote ->
                        // No catalog association, synopsis or poster may originate from MUSIC.
                        if (isCatalogMusicVideo(remote)) return@forEach
                        val remoteId = remote.ids.idFor(remote.source)
                        if (remoteId.isNullOrBlank()) {
                            incompleteSearch = true
                        } else {
                            candidates.putIfAbsent(remoteId, remote)
                        }
                    }
                }
            }
        }

        // An incomplete search cannot authorize metadata or ID persistence. Candidate images
        // from successful queries remain usable and do not suppress later providers.
        val artworkCandidates = usableCatalogArtworkCandidates(candidates.values)
        if (incompleteSearch) {
            record(
                provider.source,
                if (artworkCandidates.isNotEmpty()) "INCOMPLETE_SEARCH_ARTWORK_CANDIDATES_RETAINED"
                else "INCOMPLETE_SEARCH_NO_ARTWORK_CANDIDATES",
                anime.id,
            )
            return Resolution(
                decision = ReiAnixCatalogIdentityDecision.UNRESOLVED,
                artworkCandidates = artworkCandidates,
            )
        }

        val evaluation = ReiAnixCatalogIdentityMatcher.evaluateCandidates(
            anime.id, identity, candidates.values, ids,
        )
        val verified = evaluation.value?.let { VerifiedRemote(it.remote, it.match) }
        return Resolution(
            decision = evaluation.decision,
            verified = verified,
            artworkCandidates = artworkCandidates,
        )
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
        const val MAX_SEARCH_TERMS = 3
        const val SOURCE_RETRY_COOLDOWN_SECONDS = 6L * 60L * 60L
    }
}
