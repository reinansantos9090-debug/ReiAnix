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

        assertEquals(0.85, ranked.single().score, 0.001)
        assertEquals(42, ranked.single().anime.id)
    }

    @Test
    fun japaneseVoicingMarksSurviveUnicodeDecompositionAndNormalization() {
        val candidate = anime(id = 91, native = "がんばって")
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "か\u3099んは\u3099って",
            candidates = listOf(candidate),
        )
        assertEquals(1.0, ranked.single().score, 0.0)
        assertEquals("native", ranked.single().titleKind)
    }

    @Test
    fun localMediaPathsContributeFolderAndFilenameTitleVariants() {
        val candidates = ReiAnixAniListMatcher.extractLocalTitleCandidates(
            "/library/Nome do Anime/Temporada 2/[Fansub] Nome_do_Anime_S02E03_1080p_WEB-DL.mkv",
        )
        assertTrue(candidates.any { ReiAnixAniListMatcher.normalizeForLookup(it) == "nome do anime" })
        assertTrue(candidates.none { it.contains("1080p", ignoreCase = true) })
        assertTrue(candidates.none { it.contains("WEB-DL", ignoreCase = true) })
    }

    @Test
    fun ordinaryWordsThatCanAlsoLookLikeReleaseLabelsRemainInTitleVariants() {
        val variants = ReiAnixAniListMatcher.localTitleSearchVariants("The Complete Batch")
        assertTrue(variants.any { it.equals("The Complete Batch", ignoreCase = true) })
    }

    @Test
    fun accentAndPunctuationEquivalentSearchVariantsAreDeduplicated() {
        val variants = boundedAniListSearchTerms(
            listOf("Shingeki no Kyōjin", "Shingeki-no-Kyojin", "Shingeki no Kyojin"),
        )
        assertEquals(1, variants.size)
    }

    @Test
    fun seasonConflictReducesScoreAndPreventsAutomaticConfirmation() {
        val candidate = anime(id = 92, english = "Demon Slayer: Kimetsu no Yaiba 2nd Season")
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = "Demon Slayer: Kimetsu no Yaiba Season 3",
            candidates = listOf(candidate),
        )
        assertTrue(ranked.single().score < ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE)
    }

    @Test
    fun explicitFileAndParentFolderSeasonsAreKeptAsSeparateEvidence() {
        assertEquals(
            2,
            ReiAnixAniListMatcher.extractLocalSeason(
                "/library/Nome do Anime/Temporada 2/[Fansub] Nome do Anime S02E03 1080p.mkv",
            ),
        )
        assertEquals(
            3,
            ReiAnixAniListMatcher.extractLocalSeason("/library/Nome do Anime S03E02.mkv"),
        )
        assertEquals(
            2,
            ReiAnixAniListMatcher.extractLocalSeason("/library/Nome do Anime/Season 2/episode 03.mkv"),
        )
        assertEquals(
            null,
            ReiAnixAniListMatcher.extractLocalSeason("/library/Nome do Anime/episode 03.mkv"),
        )
        assertEquals(
            null,
            ReiAnixAniListMatcher.extractLocalSeason("/library/Nome do Anime/Nome do Anime 03.mkv"),
        )
    }

    @Test
    fun languageAndOrdinaryWordsAreNotRemovedAsTechnicalNoise() {
        val title = "The English Dub of Japanese Tales"
        assertEquals(
            title.lowercase(),
            ReiAnixAniListMatcher.normalizeForLookup(title),
        )
    }

    @Test
    fun numericEpisodeSuffixIsRemovedWithoutDiscardingRealLanguageWords() {
        val numericEpisode = ReiAnixAniListMatcher.extractLocalTitleCandidates(
            "/library/Nome do Anime/[Fansub] Nome do Anime - 01 [1080p].mkv",
        )
        assertTrue(numericEpisode.any { ReiAnixAniListMatcher.normalizeForLookup(it) == "nome do anime" })
        assertTrue(numericEpisode.none { ReiAnixAniListMatcher.normalizeForLookup(it) == "nome do anime 01" })

        val languageWords = ReiAnixAniListMatcher.extractLocalTitleCandidates(
            "/library/The English Dub of Japanese Tales S01E02.mkv",
        )
        assertTrue(languageWords.any {
            ReiAnixAniListMatcher.normalizeForLookup(it) == "the english dub of japanese tales"
        })
        assertTrue(languageWords.none { ReiAnixAniListMatcher.normalizeForLookup(it) == "the of tales" })
    }

    @Test
    fun folderTitleIsUsedWhenFilenameContainsOnlyEpisodeMetadata() {
        val candidates = ReiAnixAniListMatcher.extractLocalTitleCandidates(
            "/library/Nome do Anime/Season 2/[Fansub] Episode 03 [1080p].mkv",
        )
        assertTrue(candidates.any {
            ReiAnixAniListMatcher.normalizeForLookup(it) == "nome do anime"
        })
    }

    @Test
    fun folderTitlesWithWordsThatLookLikeReleaseLabelsRemainAvailableAsFallback() {
        val candidates = ReiAnixAniListMatcher.extractLocalTitleCandidates(
            "/library/Complete Batch/Episode 03.mkv",
        )
        assertTrue(candidates.any {
            ReiAnixAniListMatcher.normalizeForLookup(it) == "complete batch"
        })
    }

    @Test
    fun localMediaPathPreservesOneUnambiguousYearAsSeparateEvidence() {
        assertEquals(
            2024,
            ReiAnixAniListMatcher.extractLocalYear(
                "/library/Nome do Anime (2024)/Season 2/Nome do Anime S02E03.mkv",
            ),
        )
        assertEquals(
            2024,
            ReiAnixAniListMatcher.extractLocalYear(
                "/library/Anime (2023)/Season 2/Anime (2024) S02E03.mkv",
            ),
        )
        assertEquals(
            null,
            ReiAnixAniListMatcher.extractLocalYear("/library/Anime/2024/Anime S02E03.mkv"),
        )
    }

    private fun anime(
        id: Int,
        english: String? = null,
        romaji: String? = null,
        native: String? = null,
        synonyms: List<String> = emptyList(),
        format: String? = "TV",
        year: Int? = null,
    ) = AniListAnime(
        id = id,
        titleRomaji = romaji,
        titleEnglish = english,
        titleNative = native,
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
