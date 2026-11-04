package com.reiflix.reiflix_local.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeThumbnailWorkQueueTest {
    @Test
    fun interleavesEpisodesAcrossAnime() {
        val queue = EpisodeThumbnailWorkQueue()
        queue.offer(
            listOf(
                EpisodeThumbnailWorkItem(1L, 11L, "a1"),
                EpisodeThumbnailWorkItem(1L, 12L, "a2"),
                EpisodeThumbnailWorkItem(1L, 13L, "a3"),
                EpisodeThumbnailWorkItem(2L, 21L, "b1"),
                EpisodeThumbnailWorkItem(2L, 22L, "b2"),
                EpisodeThumbnailWorkItem(3L, 31L, "c1"),
            ),
        )
        val order = buildList {
            while (!queue.isEmpty()) add(queue.poll()!!.episodeId)
        }
        assertEquals(listOf(11L, 21L, 31L, 12L, 22L, 13L), order)
    }

    @Test
    fun prioritizesActiveAnimeThenReturnsToRoundRobin() {
        val queue = EpisodeThumbnailWorkQueue()
        queue.offer(
            listOf(
                EpisodeThumbnailWorkItem(1L, 11L, "a1"),
                EpisodeThumbnailWorkItem(1L, 12L, "a2"),
                EpisodeThumbnailWorkItem(2L, 21L, "b1"),
                EpisodeThumbnailWorkItem(2L, 22L, "b2"),
                EpisodeThumbnailWorkItem(3L, 31L, "c1"),
                EpisodeThumbnailWorkItem(3L, 32L, "c2"),
            ),
        )
        assertEquals(31L, queue.poll(priorityAnimeId = 3L)?.episodeId)
        assertEquals(32L, queue.poll(priorityAnimeId = 3L)?.episodeId)
        assertEquals(11L, queue.poll(priorityAnimeId = 3L)?.episodeId)
        assertEquals(21L, queue.poll()?.episodeId)
        assertEquals(12L, queue.poll()?.episodeId)
        assertEquals(22L, queue.poll()?.episodeId)
        assertNull(queue.poll())
    }

    @Test
    fun normalWorkerSkipsAnimeOwnedByPriorityWorker() {
        val queue = EpisodeThumbnailWorkQueue()
        queue.offer(
            listOf(
                EpisodeThumbnailWorkItem(1L, 11L, "a1"),
                EpisodeThumbnailWorkItem(1L, 12L, "a2"),
                EpisodeThumbnailWorkItem(2L, 21L, "b1"),
                EpisodeThumbnailWorkItem(3L, 31L, "c1"),
                EpisodeThumbnailWorkItem(3L, 32L, "c2"),
            ),
        )

        assertEquals(31L, queue.poll(priorityAnimeId = 3L)?.episodeId)
        assertEquals(11L, queue.poll(excludeAnimeId = 3L)?.episodeId)
        assertEquals(32L, queue.poll(priorityAnimeId = 3L)?.episodeId)
    }

    @Test
    fun newerQueuedRevisionReplacesOldEpisodeWork() {
        val queue = EpisodeThumbnailWorkQueue()
        queue.offer(listOf(EpisodeThumbnailWorkItem(1L, 11L, "old")))
        queue.offer(listOf(EpisodeThumbnailWorkItem(1L, 11L, "new")))
        assertEquals(1, queue.size())
        assertEquals("new", queue.poll()?.revision)
        assertNull(queue.poll())
    }
}
