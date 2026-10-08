package com.reiflix.reiflix_local.ui.model

import androidx.annotation.Keep

@Keep
enum class ReiAnixDetailsLoadStatus {
    LOADING,
    READY,
    EMPTY,
    SOURCE_UNAVAILABLE,
    NOT_FOUND,
    ERROR,
}

@Keep
data class ReiAnixDetailsAnimeUiModel(
    val id: Long,
    val title: String,
    val year: Int?,
    val genres: List<ReiAnixGenreUiModel>,
    val score: Double?,
    val favorite: Boolean,
    val artwork: ReiAnixArtworkUiModel?,
    val episodeCount: Int?,
    val playbackTargetEpisodeId: Long?,
    val shouldContinue: Boolean,
    val seasons: List<ReiAnixSeasonUiModel> = emptyList(),
    val specials: List<ReiAnixEpisodeUiModel> = emptyList(),
    val mediaFiles: List<ReiAnixEpisodeUiModel> = emptyList(),
    val mediaKind: ReiAnixMediaKind = ReiAnixMediaKind.UNKNOWN,
    val description: String? = null,
    val romajiTitle: String? = null,
    val englishTitle: String? = null,
    val nativeTitle: String? = null,
    val status: String? = null,
    val format: String? = null,
    val durationMinutes: Int? = null,
    val studio: String? = null,
    val seasonLabel: String? = null,
    val metadataAvailability: ReiAnixMetadataAvailability = ReiAnixMetadataAvailability.UNRESOLVED,
    val presentationState: ReiAnixPresentationState = ReiAnixPresentationState.METADATA_MISSING,
    val pinned: Boolean = false,
    val playbackTargetEpisode: ReiAnixEpisodeUiModel? = null,
) {
    val stableKey: String
        get() = "anime:" + id

    val preferredAlternateTitle: String?
        get() = sequenceOf(nativeTitle, romajiTitle, englishTitle)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
}

@Keep
data class ReiAnixDetailsUiState(
    val status: ReiAnixDetailsLoadStatus = ReiAnixDetailsLoadStatus.LOADING,
    val anime: ReiAnixDetailsAnimeUiModel? = null,
    val sourceAvailable: Boolean = false,
    val sourceState: String = "UNKNOWN",
    val error: String? = null,
)

object ReiAnixDetailsUiStateProjection {
    fun from(
        state: ReiAnixLibraryUiState,
        animeId: Long,
    ): ReiAnixDetailsUiState {
        val anime = state.animes.firstOrNull { it.id == animeId }
        if (anime != null) {
            val target = anime.playbackTargetEpisodeId?.let { targetId ->
                anime.contentEpisodes.firstOrNull { it.id == targetId }
            }
            val playableTarget = target?.takeIf { it.isPlayable }

            return ReiAnixDetailsUiState(
                status = ReiAnixDetailsLoadStatus.READY,
                anime = ReiAnixDetailsAnimeUiModel(
                    id = anime.id,
                    title = anime.title,
                    year = anime.year,
                    genres = anime.genres,
                    score = anime.score,
                    favorite = anime.favorite,
                    artwork = anime.artwork,
                    episodeCount = anime.availableContentCount.takeIf { it > 0 },
                    playbackTargetEpisodeId = playableTarget?.id,
                    shouldContinue = playableTarget?.consumptionState == ReiAnixConsumptionState.IN_PROGRESS,
                    seasons = anime.seasons,
                    specials = anime.specials,
                    mediaFiles = anime.mediaFiles,
                    mediaKind = anime.mediaKind,
                    description = anime.description,
                    romajiTitle = anime.romajiTitle,
                    englishTitle = anime.englishTitle,
                    nativeTitle = anime.nativeTitle,
                    status = anime.status,
                    format = anime.format,
                    durationMinutes = anime.durationMinutes,
                    studio = anime.studio,
                    seasonLabel = anime.seasonLabel,
                    metadataAvailability = anime.metadataAvailability,
                    presentationState = anime.presentationState,
                    pinned = anime.pinned,
                    playbackTargetEpisode = playableTarget,
                ),
                sourceAvailable = state.sourceAvailable,
                sourceState = state.sourceState,
            )
        }

        return when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING ->
                ReiAnixDetailsUiState(
                    status = ReiAnixDetailsLoadStatus.LOADING,
                    sourceAvailable = state.sourceAvailable,
                    sourceState = state.sourceState,
                )

            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE ->
                ReiAnixDetailsUiState(
                    status = ReiAnixDetailsLoadStatus.SOURCE_UNAVAILABLE,
                    sourceAvailable = false,
                    sourceState = state.sourceState,
                    error = state.error,
                )

            ReiAnixLibraryLoadStatus.ERROR ->
                ReiAnixDetailsUiState(
                    status = ReiAnixDetailsLoadStatus.ERROR,
                    sourceAvailable = state.sourceAvailable,
                    sourceState = state.sourceState,
                    error = state.error ?: "Não foi possível carregar os dados deste anime.",
                )

            ReiAnixLibraryLoadStatus.EMPTY ->
                ReiAnixDetailsUiState(
                    status = ReiAnixDetailsLoadStatus.EMPTY,
                    sourceAvailable = state.sourceAvailable,
                    sourceState = state.sourceState,
                )

            ReiAnixLibraryLoadStatus.READY ->
                ReiAnixDetailsUiState(
                    status = ReiAnixDetailsLoadStatus.NOT_FOUND,
                    sourceAvailable = state.sourceAvailable,
                    sourceState = state.sourceState,
                    error = "Anime não encontrado na biblioteca local.",
                )
        }
    }

    fun invalidAnimeId(): ReiAnixDetailsUiState =
        ReiAnixDetailsUiState(
            status = ReiAnixDetailsLoadStatus.NOT_FOUND,
            error = "Identificador de anime inválido.",
        )
}