package com.reiflix.reiflix_local.data.collector

import com.reiflix.reiflix_local.data.sqlite.ReiAnixCollectorEpisodeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Manual Kotlin/JVM regression coverage. Deliberately not wired to an automatic CI trigger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReiAnixCollectorJourneyCalculatorTest {
    @Test
    fun completionThresholdIsNinetyPercent() {
        val result = ReiAnixCollectorJourneyCalculator.build(
            listOf(
                row(id = 1, animeId = 1, progress = 89.9),
                row(id = 2, animeId = 2, progress = 90.0),
            ),
            savedActiveTitle = null,
        )

        assertEquals(1, result.xp)
        assertEquals(1, result.episodesCompleted)
        assertEquals(1, result.animesCompleted)
        assertTrue(result.achievements.first { it.id == "first_episode" }.unlocked)
    }

    @Test
    fun activityThresholdIsFivePercent() {
        val result = ReiAnixCollectorJourneyCalculator.build(
            listOf(
                row(id = 1, animeId = 1, progress = 5.0, lastPlayedAt = 1_700_000_000.0),
                row(id = 2, animeId = 2, progress = 4.9, lastPlayedAt = 1_700_086_400.0),
            ),
            savedActiveTitle = null,
        )

        assertEquals(1, result.activeDays)
        assertEquals(0, result.episodesCompleted)
    }

    @Test
    fun onlyFullyCompletedAnimeUnlocksAnimeTitle() {
        val result = ReiAnixCollectorJourneyCalculator.build(
            listOf(
                row(id = 1, animeId = 10, watched = true),
                row(id = 2, animeId = 10, progress = 50.0),
                row(id = 3, animeId = 20, watched = true),
            ),
            savedActiveTitle = null,
        )

        assertEquals(2, result.episodesCompleted)
        assertEquals(1, result.animesCompleted)
        assertEquals(2, result.libraryTitles)
        assertFalse(result.titles.first { it.id == "collector" }.unlocked)
    }

    @Test
    fun genreAchievementUsesCompletedTitles() {
        val result = ReiAnixCollectorJourneyCalculator.build(
            listOf(
                row(id = 1, animeId = 1, watched = true, genresJson = "[\"Comedy\"]"),
                row(id = 2, animeId = 2, watched = true, genresJson = "[\"Comedy\"]"),
                row(id = 3, animeId = 3, watched = true, genresJson = "[\"Comedy\"]"),
            ),
            savedActiveTitle = null,
        )

        assertEquals(1, result.genresExplored)
        assertTrue(result.titles.first { it.id == "genre_comedy" }.unlocked)
        assertTrue(result.achievements.first { it.id == "genre_comedy" }.unlocked)
    }

    @Test
    fun corruptOrLockedActiveTitleFallsBackWithoutPersistingDerivedState() {
        val result = ReiAnixCollectorJourneyCalculator.build(
            listOf(row(id = 1, animeId = 1, watched = true)),
            savedActiveTitle = "old-title-that-is-not-unlocked",
        )

        assertEquals("collector_beginner", result.activeTitle)
        assertEquals(1, result.xp)
        assertNotNull(result.activeTitleLabel)
    }

    private fun row(
        id: Long,
        animeId: Long,
        progress: Double = 0.0,
        duration: Double = 100.0,
        watched: Boolean = false,
        lastPlayedAt: Double? = null,
        year: Int = 2020,
        genresJson: String? = "[\"Action\"]",
        format: String? = "TV",
        mediaKind: String? = "series",
    ) = ReiAnixCollectorEpisodeRow(
        id = id,
        animeId = animeId,
        progress = progress,
        duration = duration,
        watched = watched,
        missing = false,
        lastPlayedAt = lastPlayedAt,
        episodeType = "regular",
        animeTitle = "Anime " + animeId,
        year = year,
        genresJson = genresJson,
        format = format,
        mediaKind = mediaKind,
    )
}
