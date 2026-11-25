package com.reianix.reianix_local
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.reianix.reianix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reianix.reianix_local.ui.model.ReiAnixDetailsLoadStatus
import com.reianix.reianix_local.data.library.ReiAnixLibrarySnapshotCodec
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import com.reianix.reianix_local.viewmodel.EpisodeThumbnailQueue
import com.reianix.reianix_local.viewmodel.shouldScheduleAutomaticThumbnailBatch
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReiAnixLibraryViewModelContractTest {
    @Test
    fun automaticThumbnailQueueIsSerialDeduplicatedAndPrioritizesDetails() {
        val queue = EpisodeThumbnailQueue()
        queue.enqueue(1L, false)
        queue.enqueue(2L, false)
        queue.enqueue(3L, false)
        queue.enqueue(2L, false)
        assertEquals(EpisodeThumbnailQueue.Batch(1L, false), queue.poll())

        queue.enqueue(3L, true)
        queue.enqueue(3L, false)
        assertTrue(queue.hasPriorityOtherThan(1L))
        assertTrue(queue.isExplicit(3L))
        // Automatic work yields after the current episode and resumes later.
        queue.enqueue(1L, false)
        assertEquals(EpisodeThumbnailQueue.Batch(3L, true), queue.poll())
        assertEquals(EpisodeThumbnailQueue.Batch(2L, false), queue.poll())
        assertEquals(EpisodeThumbnailQueue.Batch(1L, false), queue.poll())
        assertFalse(queue.contains(1L))
        assertNull(queue.poll())
    }

    @Test
    fun automaticThumbnailDiscoveryDoesNotRequeueItsOwnPublications() {
        val missing = mapOf<String, String?>("episode1:revision1" to null)
        val ready = mapOf<String, String?>("episode1:revision1" to "/cache/one.jpg")
        assertTrue(shouldScheduleAutomaticThumbnailBatch(null, missing))
        assertFalse(shouldScheduleAutomaticThumbnailBatch(missing, missing))
        assertFalse(shouldScheduleAutomaticThumbnailBatch(missing, ready))
        assertFalse(shouldScheduleAutomaticThumbnailBatch(ready, ready))
        assertTrue(shouldScheduleAutomaticThumbnailBatch(ready, missing))
        assertTrue(shouldScheduleAutomaticThumbnailBatch(ready, ready + ("episode2:revision1" to null)))
        assertTrue(shouldScheduleAutomaticThumbnailBatch(ready, mapOf("episode1:revision2" to null)))
        assertFalse(shouldScheduleAutomaticThumbnailBatch(ready, emptyMap()))
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

        // Details can render the current canonical snapshot immediately on reentry;
        // it must not transiently reset to the synthetic LOADING skeleton.
        assertEquals(ReiAnixDetailsLoadStatus.READY, details.status)
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
