package com.reiflix.reiflix_local.ui.model

import androidx.annotation.Keep

@Keep
enum class ReiAnixLibraryLoadStatus {
    LOADING,
    READY,
    EMPTY,
    SOURCE_UNAVAILABLE,
    ERROR,
}

@Keep
data class ReiAnixLibraryUiState(
    val status: ReiAnixLibraryLoadStatus = ReiAnixLibraryLoadStatus.LOADING,
    val storage: ReiAnixStorageUiState = ReiAnixStorageUiState(),
    val revision: Long = 0L,
    val animes: List<ReiAnixAnimeUiModel> = emptyList(),
    val continueWatching: List<ReiAnixContinueWatchingUiModel> = emptyList(),
    val sourceAvailable: Boolean = false,
    val sourceState: String = "UNKNOWN",
    val scanInProgress: Boolean = false,
    val scanState: String = "IDLE",
    val error: String? = null,
    val lastCommandId: String? = null,
    val lastCommandAction: String? = null,
    val lastCommandStatus: String? = null,
    val lastCommandError: String? = null,
) {
    val isEmpty: Boolean
        get() = status == ReiAnixLibraryLoadStatus.EMPTY

    val isAvailable: Boolean
        get() = sourceAvailable && status != ReiAnixLibraryLoadStatus.ERROR
}


@Keep
data class ReiAnixHomeLibraryUiState(
    val status: ReiAnixLibraryLoadStatus = ReiAnixLibraryLoadStatus.LOADING,
    val animes: List<ReiAnixHomeAnimeUiModel> = emptyList(),
    val sourceAvailable: Boolean = false,
    val sourceState: String = "UNKNOWN",
    val error: String? = null,
) {
    companion object {
        fun from(state: ReiAnixLibraryUiState): ReiAnixHomeLibraryUiState =
            ReiAnixHomeLibraryUiState(
                status = state.status,
                animes = state.animes.map { anime ->
                    val targetEpisode = anime.playbackTargetEpisodeId?.let { targetId ->
                        anime.contentEpisodes.firstOrNull { it.id == targetId }
                    }
                    ReiAnixHomeAnimeUiModel(
                        id = anime.id,
                        title = anime.title,
                        year = anime.year,
                        genres = anime.genres,
                        favorite = anime.favorite,
                        mediaKind = anime.mediaKind,
                        artwork = anime.artwork,
                        playbackTargetEpisodeId = anime.playbackTargetEpisodeId,
                        availableContentCount = anime.contentEpisodes.count { episode ->
                            episode.media.availability == ReiAnixMediaAvailability.AVAILABLE
                        },
                        playbackActionLabel = targetEpisode?.playbackActionLabel ?: "Assistir",
                        isWatching = anime.isWatching,
                        score = anime.score,
                        addedAt = anime.addedAt,
                        lastPlayedAt = anime.lastPlayedAt,
                        pinned = anime.pinned,
                        description = anime.description,
                        status = anime.status,
                        format = anime.format,
                        studio = anime.studio,
                    )
                },
                sourceAvailable = state.sourceAvailable,
                sourceState = state.sourceState,
                error = state.error,
            )
    }
}
