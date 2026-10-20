package com.reiflix.reiflix_local.data.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixAniListMatcherTest {
    @Test
    fun normalizesDiacriticsReleaseTagsAndEpisodeMarkers() {
        val candidate = anime(
            id = 16498,
            english = "Attack on Titan",
            romaji = "Shingeki no Kyojin",
            synonyms = listOf("Shingeki no Kyōjin"),
        )

        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "[Group] Shingeki no Kyōjin! S01E01 1080p",
            candidates = listOf(candidate),
        )

        assertEquals(1.0, ranked.single().score, 0.0)
        assertEquals("romaji", ranked.single().titleKind)
    }

    @Test
    fun shortContainmentDoesNotAutomaticallyConfirmFranchiseSequels() {
        val candidate = anime(id = 20, english = "Naruto")
        val ranked = ReiAnixAniListMatcher.rank("Naruto Shippuden", listOf(candidate))

        assertTrue(ranked.single().score < ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE)
    }

    @Test
    fun contradictoryMediaFormatReducesAnExactTitleBelowAutomaticThreshold() {
        val candidate = anime(
            id = 42,
            english = "Cowboy Bebop",
            format = "TV",
        )
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Cowboy Bebop",
            candidates = listOf(candidate),
            context = ReiAnixAniListMatcher.Context(mediaKind = "movie"),
        )

        assertEquals(0.82, ranked.single().score, 0.001)
        assertTrue(ranked.single().score < ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE)
    }

    @Test
    fun contradictoryYearLowersScoreWithoutChangingRemoteIdentity() {
        val candidate = anime(id = 42, english = "Fruits Basket", year = 2001)
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Fruits Basket 2019",
            candidates = listOf(candidate),
        )

        assertEquals(0.97, ranked.single().score, 0.001)
        assertEquals(42, ranked.single().anime.id)
    }

    private fun anime(
        id: Int,
        english: String? = null,
        romaji: String? = null,
        synonyms: List<String> = emptyList(),
        format: String? = "TV",
        year: Int? = null,
    ) = AniListAnime(
        id = id,
        titleRomaji = romaji,
        titleEnglish = english,
        titleNative = null,
        synonyms = synonyms,
        description = null,
        format = format,
        episodes = null,
        status = null,
        averageScore = null,
        durationMinutes = null,
        season = null,
        seasonYear = year,
        genres = emptyList(),
        coverLargeUrl = null,
        coverExtraLargeUrl = null,
        bannerUrl = null,
        studios = emptyList(),
    )
}
