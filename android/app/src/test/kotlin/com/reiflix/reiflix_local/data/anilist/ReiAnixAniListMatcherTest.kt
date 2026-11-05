package com.reiflix.reiflix_local.data.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun organizationalPrefixAndReleaseMetadataDoNotHideExactAniListTitle() {
        val candidate = anime(
            id = 202600,
            english = "Nyaight of the Living Cat",
            romaji = "Nyaight of the Living Cat",
        )

        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Home Animes [SubsPlease] Nyaight.of.the.Living_Cat.S01E01.1080p.WEB-DL.x265",
            candidates = listOf(candidate),
        )

        assertEquals(1.0, ranked.single().score, 0.0)
        assertEquals("english", ranked.single().titleKind)
    }

    @Test
    fun oneCharacterTitleVariationRemainsAHighConfidenceCandidateWithoutChangingIdentity() {
        val candidate = anime(id = 202601, english = "Night of the Living Cat")
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Home Animes Nyaight of the Living Cat",
            candidates = listOf(candidate),
        )

        assertTrue(ranked.single().score >= ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE)
        assertEquals(202601, ranked.single().anime.id)
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

    @Test
    fun multiAliasRankingMatchesMaximumOfExistingSingleTitleScores() {
        val candidates = listOf(
            anime(
                id = 16498,
                english = "Attack on Titan",
                romaji = "Shingeki no Kyojin",
                synonyms = listOf("Shingeki no Kyōjin", "Ataque dos Titãs"),
                year = 2013,
            ),
            anime(
                id = 35107,
                english = "Attack on Titan: The Final Season",
                romaji = "Shingeki no Kyojin: The Final Season",
                synonyms = listOf("Shingeki no Kyojin Season 4"),
                year = 2020,
            ),
            anime(
                id = 5114,
                english = "Fullmetal Alchemist: Brotherhood",
                romaji = "Hagane no Renkinjutsushi",
                synonyms = listOf("鋼の錬金術師"),
                year = 2009,
            ),
        )
        val localTitles = listOf(
            "[SubsPlease] Attack on Titan S01E01 1080p",
            "Shingeki no Kyojin season 4",
            "Ataque dos Titãs",
            "Fullmetal Alchemist Brotherhood 2009",
        )
        val context = ReiAnixAniListMatcher.Context(year = 2013, mediaKind = "movie")

        val expected = candidates.map { candidate ->
            candidate to localTitles.maxOf { title ->
                ReiAnixAniListMatcher.rank(title, listOf(candidate), context).single().score
            }
        }.sortedWith(
            compareByDescending<Pair<AniListAnime, Double>> { it.second }
                .thenBy { (it.first.titleEnglish ?: it.first.titleRomaji ?: "").lowercase(java.util.Locale.ROOT) }
                .thenBy { it.first.id },
        )

        val actual = ReiAnixAniListMatcher.rankAcrossTitles(
            localTitles = localTitles,
            candidates = candidates,
            context = context,
        )

        assertEquals(
            expected.map { it.first.id to it.second },
            actual.map { it.anime.id to it.score },
        )
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
