package com.reianix.reianix_local.data.anilist

import com.reianix.reianix_local.data.media.MediaTitleNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixAniListSearchTermsTest {
    @Test
    fun expandsOrganizationalPrefixAndReleaseMetadataForProviderQueries() {
        val terms = boundedAniListSearchTerms(
            listOf(
                "Home Animes Ninja vs gokudo",
                "Home Animes Nyaight.of.the.Living_Cat.S01E01.1080p.WEB-DL.x265",
            ),
            limit = 8,
        )

        assertTrue(terms.any { MediaTitleNormalizer.normalizeTitle(it) == "ninja vs gokudo" })
        assertTrue(terms.any { MediaTitleNormalizer.normalizeTitle(it) == "nyaight of the living cat" })
        assertEquals(terms.size, terms.distinctBy { MediaTitleNormalizer.normalizeTitle(it) }.size)
        assertTrue(terms.size <= 8)
    }

    @Test
    fun keepsDistinctAlternateLanguageTitlesAndRespectsQueryBudget() {
        val terms = boundedAniListSearchTerms(
            listOf(
                "Home Animes Nyaight of the Living Cat",
                "夜は猫といっしょ",
                "Nyaight of the Living Cat",
                "The Night of the Cat",
            ),
            limit = 6,
        )

        assertTrue(terms.any { it.contains("夜は猫といっしょ") })
        assertTrue(terms.any { it.equals("The Night of the Cat", ignoreCase = true) })
        assertTrue(terms.size <= 6)
    }

    @Test
    fun artworkCandidatesKeepProviderOrderWithoutIdentityScoring() {
        fun anime(
            id: Int,
            title: String,
            poster: String?,
            banner: String? = null,
            year: Int? = null,
            format: String? = null,
        ) = AniListAnime(
            id = id,
            titleRomaji = title,
            titleEnglish = null,
            titleNative = null,
            synonyms = emptyList(),
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
            coverExtraLargeUrl = poster,
            bannerUrl = banner,
            studios = emptyList(),
        )

        val unrelatedFirst = anime(
            id = 31,
            title = "Completely Different Title",
            poster = "https://cdn.example/first.jpg",
            year = 1990,
            format = "MOVIE",
        )
        val noArtwork = anime(id = 32, title = "No Cover", poster = null)
        val matchingLater = anime(
            id = 33,
            title = "Expected Anime",
            poster = "https://cdn.example/later.jpg",
            year = 2026,
            format = "TV",
        )

        val selected = usableAniListArtworkCandidates(listOf(unrelatedFirst, noArtwork, matchingLater))
        assertEquals(listOf(31, 33), selected.map { it.id })
    }

}
