package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixEpisodeThumbnailBackfillCandidate
import com.reiflix.reiflix_local.viewmodel.drainEpisodeThumbnailQueue
import com.reiflix.reiflix_local.viewmodel.releaseUncompletedEpisodeThumbnailRevisions
import com.reiflix.reiflix_local.viewmodel.selectPendingEpisodeThumbnailCandidates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLibraryViewModelContractTest {
    @Test
    fun startupThumbnailBackfillSelectsEveryPendingEpisodeAndDeduplicatesOnlySameRevision() {
        val scheduled = mutableSetOf<String>()
        val firstAnimeEpisodeOne = ReiAnixEpisodeThumbnailBackfillCandidate(
            animeId = 10L,
            episodeId = 101L,
            available = true,
            source = "content://media/external/video/101",
            mediaIdentity = "media-101",
            fileSizeBytes = 1024L,
            modifiedAtMillis = 100L,
        )
        val candidates = listOf(
            firstAnimeEpisodeOne,
            firstAnimeEpisodeOne.copy(episodeId = 102L, source = "content://media/external/video/102", mediaIdentity = "media-102"),
            firstAnimeEpisodeOne.copy(episodeId = 103L, source = "content://media/external/video/103", mediaIdentity = "media-103"),
            firstAnimeEpisodeOne.copy(animeId = 20L, episodeId = 201L, source = "content://media/external/video/201", mediaIdentity = "media-201"),
            firstAnimeEpisodeOne.copy(episodeId = 999L, source = null),
            firstAnimeEpisodeOne.copy(episodeId = 1000L, available = false, source = "content://media/external/video/1000"),
            firstAnimeEpisodeOne,
        )

        val selected = selectPendingEpisodeThumbnailCandidates(candidates, scheduled)
        assertEquals(listOf(101L, 102L, 103L, 201L), selected.map { it.episodeId })
        assertEquals(
            emptyList<ReiAnixEpisodeThumbnailBackfillCandidate>(),
            selectPendingEpisodeThumbnailCandidates(candidates, scheduled),
        )

        // A changed source revision and videos added after the initial catalog snapshot
        // can be selected in a later discovery pass.
        val updated = firstAnimeEpisodeOne.copy(fileSizeBytes = 2048L)
        val newlyAdded = firstAnimeEpisodeOne.copy(episodeId = 301L, source = "content://media/external/video/301")
        assertEquals(
            listOf(101L, 301L),
            selectPendingEpisodeThumbnailCandidates(listOf(updated, newlyAdded), scheduled).map { it.episodeId },
        )
    }

    @Test
    fun cancelledQueueReleasesUnprocessedRevisionsForDiscoveryOnResume() {
        val scheduled = mutableSetOf("episode-1", "episode-2", "episode-3")
        releaseUncompletedEpisodeThumbnailRevisions(
            queuedSourceRevisions = listOf("episode-1", "episode-2", "episode-3"),
            completedSourceRevisions = setOf("episode-1"),
            scheduledSourceRevisions = scheduled,
        )

        assertEquals(setOf("episode-1"), scheduled)
        assertTrue("Unprocessed revisions must be eligible on the next discovery pass.", "episode-2" !in scheduled)
        assertTrue("Unprocessed revisions must be eligible on the next discovery pass.", "episode-3" !in scheduled)
    }

    @Test
    fun thumbnailQueueContinuesAfterFirstAndMiddleFailures() = runBlocking {
        val items = (1L..5L).toList()
        val processed = mutableListOf<Long>()
        val failures = mutableListOf<Long>()
        val completed = mutableListOf<Long>()

        drainEpisodeThumbnailQueue(
            items = items,
            process = { id ->
                processed += id
                if (id == 1L || id == 3L) error("synthetic item failure $id")
            },
            onFailure = { id, _ -> failures += id },
            onProcessed = { id -> completed += id },
        )

        assertEquals(items, processed)
        assertEquals(listOf(1L, 3L), failures)
        assertEquals(items, completed)
    }

    @Test
    fun thumbnailQueueSkipsValidItemsButDoesNotDropOtherPendingItems() = runBlocking {
        data class Video(val id: Long, val hasValidThumbnail: Boolean)
        val items = listOf(
            Video(11L, true),
            Video(12L, false),
            Video(13L, true),
            Video(14L, false),
        )
        val regenerated = mutableListOf<Long>()

        drainEpisodeThumbnailQueue(
            items = items,
            process = { video ->
                if (!video.hasValidThumbnail) regenerated += video.id
            },
            onFailure = { video, error ->
                throw AssertionError("Unexpected failure for ${video.id}", error)
            },
        )

        assertEquals(listOf(12L, 14L), regenerated)
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
