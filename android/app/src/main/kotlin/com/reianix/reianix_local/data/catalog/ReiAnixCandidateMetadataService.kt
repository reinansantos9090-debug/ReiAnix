package com.reianix.reianix_local.data.catalog

import com.reianix.reianix_local.data.anilist.AniListAnime
import com.reianix.reianix_local.data.anilist.isAniListMusicVideo
import com.reianix.reianix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reianix.reianix_local.data.sqlite.ReiAnixCandidateMetadata
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reianix.reianix_local.data.sqlite.mergeCandidateMetadataGaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Independent metadata companion to the existing poster pipeline.
 *
 * The provider already fetched these fields alongside the image URL. Never wait for this
 * writer before searching, scheduling, downloading or displaying a poster; no artwork
 * services are referenced here.
 */
class ReiAnixCandidateMetadataService(
    private val library: ReiAnixSqliteLibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 128)
    val updates = mutableUpdates.asSharedFlow()

    fun notifyVerifiedMetadataCommitted(animeId: Long) {
        if (animeId > 0L) mutableUpdates.tryEmit(animeId)
    }

    fun offerKitsu(animeId: Long, remote: ReiAnixCatalogAnime) {
        if (remote.source != ReiAnixCatalogSource.KITSU || isCatalogMusicVideo(remote)) return
        queue(
            animeId,
            ReiAnixCandidateMetadata(
                source = "kitsu",
                synopsis = remote.synopsis,
                year = remote.releaseYear,
                season = remote.season,
                status = remote.status,
                episodes = remote.episodes,
                durationMinutes = remote.durationMinutes,
                score = remote.averageScore,
                format = remote.format,
            ),
        )
    }

    fun offerAniList(animeId: Long, remote: AniListAnime) {
        if (isAniListMusicVideo(remote)) return
        queue(
            animeId,
            ReiAnixCandidateMetadata(
                source = "anilist",
                synopsis = remote.description,
                year = remote.seasonYear,
                season = remote.season,
                status = remote.status,
                episodes = remote.episodes,
                durationMinutes = remote.durationMinutes,
                score = remote.averageScore,
                format = remote.format,
                studio = remote.studios.joinToString(", ").takeIf(String::isNotBlank),
                genres = remote.genres,
            ),
        )
    }

    private fun queue(animeId: Long, candidate: ReiAnixCandidateMetadata) {
        if (animeId <= 0L) return
        scope.launch {
            try {
                if (library.mergeCandidateMetadataGaps(animeId, candidate)) {
                    mutableUpdates.tryEmit(animeId)
                    ReiAnixDiagnosticTimeline.record(
                        name = "CATALOG_CANDIDATE_METADATA",
                        source = candidate.source,
                        result = "GAPS_FILLED",
                        context = mapOf("anime_id" to animeId),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ReiAnixDiagnosticTimeline.record(
                    name = "CATALOG_CANDIDATE_METADATA",
                    severity = "WARN",
                    source = candidate.source,
                    result = "MERGE_FAILED",
                    error = error,
                    context = mapOf("anime_id" to animeId),
                )
            }
        }
    }
}
