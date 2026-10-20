package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.media.MediaFilenameParser
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


    @Test
    fun exactTitleRanksAboveSimilarFranchiseTitle() {
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Naruto",
            candidates = listOf(
                anime(id = 1, romaji = "Naruto"),
                anime(id = 2, english = "Boruto: Naruto Next Generations"),
            ),
        )

        assertEquals(1, ranked.first().anime.id)
        assertEquals(1.0, ranked.first().score, 0.0)
        assertTrue(ranked.last().score < ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE)
    }

    @Test
    fun synonymCanMatchWhenCanonicalTitlesAreDifferent() {
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "AOT",
            candidates = listOf(
                anime(
                    id = 1,
                    english = "Attack on Titan",
                    romaji = "Shingeki no Kyojin",
                    synonyms = listOf("AOT"),
                ),
            ),
        )

        assertEquals(1, ranked.single().anime.id)
        assertEquals(1.0, ranked.single().score, 0.0)
        assertEquals("synonym", ranked.single().titleKind)
    }

    @Test
    fun nativeJapaneseTitleMatchesWithoutReplacingRemoteIdentity() {
        val candidate = anime(
            id = 16498,
            english = "Attack on Titan",
            romaji = "Shingeki no Kyojin",
        ).copy(titleNative = "進撃の巨人")
        val ranked = ReiAnixAniListMatcher.rank("進撃の巨人", listOf(candidate))

        assertEquals(16498, ranked.single().anime.id)
        assertEquals(1.0, ranked.single().score, 0.0)
        assertEquals("native", ranked.single().titleKind)
        assertEquals("進撃の巨人", ranked.single().anime.titleNative)
    }

    @Test
    fun romajiTitleCanMatchWhenEnglishTitleIsDifferent() {
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Boku no Hero Academia",
            candidates = listOf(
                anime(id = 2, english = "My Hero Academia", romaji = "Boku no Hero Academia"),
            ),
        )

        assertEquals(2, ranked.single().anime.id)
        assertEquals(1.0, ranked.single().score, 0.0)
        assertEquals("romaji", ranked.single().titleKind)
    }

    @Test
    fun seasonMarkerRanksMatchingExplicitRemoteSeasonFirst() {
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Show S02",
            candidates = listOf(
                anime(id = 1, english = "Show Season 1"),
                anime(id = 2, english = "Show Season 2"),
            ),
        )

        assertEquals(2, ranked.first().anime.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun movieContextRanksMovieAboveConflictingTelevisionTitle() {
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Example Movie",
            candidates = listOf(
                anime(id = 1, english = "Example", format = "TV"),
                anime(id = 2, english = "Example Movie", format = "MOVIE"),
            ),
            context = ReiAnixAniListMatcher.Context(mediaKind = "movie", episodeType = "movie"),
        )

        assertEquals(2, ranked.first().anime.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun closeIdenticalCandidatesHaveEqualScoresAndStableIdentityOrder() {
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Kanon",
            candidates = listOf(
                anime(id = 20, english = "Kanon"),
                anime(id = 10, english = "Kanon"),
            ),
        )

        assertEquals(listOf(10, 20), ranked.map { it.anime.id })
        assertEquals(ranked[0].score, ranked[1].score, 0.0)
        assertTrue(ranked[0].score >= ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE)
    }

    @Test
    fun parsedFilenameTitleCanFeedNativeMatcherWithoutInfrastructure() {
        val parsed = MediaFilenameParser.parseVideoPath(
            "[Group] One.Piece.S02E03.1080p.WEB-DL.x265.mkv",
        )
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = parsed.animeTitle,
            candidates = listOf(anime(id = 100, english = "One Piece")),
        )

        assertEquals("One Piece", parsed.animeTitle)
        assertEquals(2, parsed.season)
        assertEquals(3.0, parsed.episodeNumber!!, 0.0)
        assertEquals(100, ranked.single().anime.id)
        assertEquals(1.0, ranked.single().score, 0.0)
    }

    @Test
    fun selectedUnicodeCaseFoldExamplesRemainEquivalent() {
        val sharpS = ReiAnixAniListMatcher.rank(
            "Straße",
            listOf(anime(id = 70, english = "STRASSE")),
        )
        val finalSigma = ReiAnixAniListMatcher.rank(
            "ς",
            listOf(anime(id = 71, english = "σ")),
        )

        assertEquals(1.0, sharpS.single().score, 0.0)
        assertEquals(1.0, finalSigma.single().score, 0.0)
    }


    @Test
    fun completeLegacyReleaseNoiseDoesNotChangeExactTitleMatch() {
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "[SubsPlease] Naruto Shippuden S01 E001 1080p WEB-DL x265",
            candidates = listOf(anime(id = 1, english = "Naruto Shippuden")),
        )

        assertEquals(1, ranked.single().anime.id)
        assertEquals(1.0, ranked.single().score, 0.0)
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
