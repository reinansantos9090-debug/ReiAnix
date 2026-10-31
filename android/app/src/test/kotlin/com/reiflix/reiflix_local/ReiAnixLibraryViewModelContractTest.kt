package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.mergeAnimeArtworkOnly
import com.reiflix.reiflix_local.viewmodel.mergeContinueWatchingArtworkOnly
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixLibraryViewModelContractTest {
    @Test
    fun artworkProjectionMergePreservesPlaybackProgress() {
        val state = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":7,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
               "animes":[{"id":7,"main_title":"Local","media_kind":"series","favorite":false,
               "year":2026,"genres":[],"genre_ids":[],"meta":{},
               "seasons":[{"season":1,"season_name":"Season 1","episodes":[
                 {"id":72,"anime_id":7,"season":1,"number":7,"episode_title":"E07","file_name":"E07.mkv",
                  "path":"content://episode/72","media_identity":"episode-72","availability_state":"available",
                  "missing":false,"progress":18.0,"duration":100.0,"watched":false,
                  "consumption_state":"in_progress"}]}],
               "specials":[],"media_files":[]}],
               "continue_watching":[{"episode_id":72,"anime_id":7,"anime_title":"Local","season":1,"number":7,
               "episode_title":"E07","file_name":"E07.mkv","progress":18.0,"duration":100.0}]}""".trimIndent(),
        )
        val currentAnime = state.animes.single()
        val poster = ReiAnixArtworkUiModel(
            localPath = "/cache/poster.jpg",
            externalUrl = null,
            revision = "sha256:poster",
        )
        val thumbnail = ReiAnixArtworkUiModel(
            localPath = "/cache/episode.jpg",
            externalUrl = null,
            revision = "sha256:episode",
        )
        val refreshedAnime = currentAnime.copy(
            artwork = poster,
            seasons = currentAnime.seasons.map { season ->
                season.copy(
                    episodes = season.episodes.map { episode ->
                        episode.copy(
                            progressSeconds = 99.0,
                            artwork = thumbnail,
                        )
                    },
                )
            },
        )

        val mergedAnime = mergeAnimeArtworkOnly(currentAnime, refreshedAnime)
        val mergedEpisode = mergedAnime.seasons.single().episodes.single()
        assertEquals("sha256:poster", mergedAnime.artwork?.revision)
        assertEquals("sha256:episode", mergedEpisode.artwork?.revision)
        assertEquals(18.0, mergedEpisode.progressSeconds ?: -1.0, 0.0)

        val currentContinueWatching = state.continueWatching.single()
        val refreshedContinueWatching = currentContinueWatching.copy(
            progressSeconds = 99.0,
            artwork = thumbnail,
        )
        val mergedContinueWatching = mergeContinueWatchingArtworkOnly(
            currentContinueWatching,
            refreshedContinueWatching,
        )
        assertEquals("sha256:episode", mergedContinueWatching.artwork?.revision)
        assertEquals(18.0, mergedContinueWatching.progressSeconds ?: -1.0, 0.0)
    }

    @Test
    fun homeProjectionExcludesEpisodeProgressButDetailsProjectionRetainsIt() {
        val state = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":7,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
               "animes":[{"id":7,"main_title":"Local","media_kind":"series","favorite":false,
               "year":2026,"genres":[],"genre_ids":[],"meta":{},
               "seasons":[{"season":1,"season_name":"Season 1","episodes":[
                 {"id":72,"anime_id":7,"season":1,"number":7,"episode_title":"E07","file_name":"E07.mkv",
                  "path":"content://episode/72","media_identity":"episode-72","availability_state":"available",
                  "missing":false,"progress":18.0,"duration":100.0,"watched":false,
                  "consumption_state":"in_progress"}]}],
               "specials":[],"media_files":[]}],
               "continue_watching":[{"episode_id":72,"anime_id":7,"anime_title":"Local","season":1,"number":7,
               "episode_title":"E07","file_name":"E07.mkv","progress":18.0,"duration":100.0}]}""".trimIndent(),
        )

        assertEquals(ReiAnixLibraryLoadStatus.READY, state.status)

        val home = ReiAnixLibraryViewModel.projectHomeState(state)
        val details = ReiAnixLibraryViewModel.projectDetailsState(state, 7L)

        assertEquals(1, home.animes.single().availableContentCount)
        assertEquals(72L, details.anime?.seasons?.single()?.episodes?.single()?.id)
        assertEquals(18.0, details.anime?.seasons?.single()?.episodes?.single()?.progressSeconds ?: -1.0, 0.0)

        val advanced = state.copy(
            animes = state.animes.map { anime ->
                anime.copy(
                    seasons = anime.seasons.map { season ->
                        season.copy(
                            episodes = season.episodes.map { episode ->
                                episode.copy(progressSeconds = 42.0)
                            },
                        )
                    },
                )
            },
            continueWatching = state.continueWatching.map { it.copy(progressSeconds = 42.0) },
        )
        assertEquals(
            home,
            ReiAnixLibraryViewModel.projectHomeState(advanced),
        )
    }
}
