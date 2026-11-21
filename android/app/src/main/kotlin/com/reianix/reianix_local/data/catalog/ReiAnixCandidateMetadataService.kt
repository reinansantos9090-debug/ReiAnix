package com.reianix.reianix_local.data.catalog

import com.reianix.reianix_local.data.anilist.AniListAnime
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
    // Characters from the same provider response, committed alongside
    // supplementary metadata gaps (never a second API call).
    private val charactersPublisher: ((
        Long, String, String, List<com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter>?
    ) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 128)
    val updates = mutableUpdates.asSharedFlow()

    fun notifyVerifiedMetadataCommitted(animeId: Long) {
        if (animeId > 0L) mutableUpdates.tryEmit(animeId)
    }

    fun offerKitsu(animeId: Long, remote: ReiAnixCatalogAnime) {
        if (remote.source != ReiAnixCatalogSource.KITSU) return
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
            sourceId = remote.ids.kitsuId.orEmpty(),
            characters = remote.characters,
        )
    }

    fun offerAniList(animeId: Long, remote: AniListAnime) {
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
            sourceId = remote.id.toString(),
            characters = remote.characters,
        )
    }

    private fun queue(
        animeId: Long,
        candidate: ReiAnixCandidateMetadata,
        sourceId: String,
        characters: List<com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter>?,
    ) {
        if (animeId <= 0L) return
        scope.launch {
            try {
                if (library.mergeCandidateMetadataGaps(animeId, candidate)) {
                    // The same candidate supplied metadata such as year and its cast.
                    // Publish the cast before UI observers see the metadata update.
                    try {
                        charactersPublisher?.invoke(animeId, candidate.source, sourceId, characters)
                    } catch (_: Exception) {
                        // A malformed supplemental cast may not hold back metadata.
                    }
                    mutableUpdates.tryEmit(animeId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {

            }
        }
    }
}
