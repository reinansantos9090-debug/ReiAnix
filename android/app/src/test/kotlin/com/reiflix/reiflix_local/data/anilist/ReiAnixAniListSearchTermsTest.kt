package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer
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
    fun defaultSearchTermCollectionNoLongerStopsAfterSixAliases() {
        val aliases = (1..12).map { "Alternate Work Title $it" }
        val terms = boundedAniListSearchTerms(aliases)

        aliases.forEach { alias ->
            assertTrue(
                "Missing alias query: $alias",
                terms.any { MediaTitleNormalizer.normalizeTitle(it) == MediaTitleNormalizer.normalizeTitle(alias) },
            )
        }
        assertTrue(terms.size >= aliases.size)
    }

    @Test
    fun allFifteenPreviouslyMissingCoverTitlesRemainSearchable() {
        val missingCoverTitles = listOf(
            "Hanazakari no Kimitachi e",
            "Maou Gakuin no Futekigousha",
            "Sakamoto Days Part 2",
            "Futsutsuka na Akujo de Gozaimasu ga, Suuguu Chou...",
            "Otome Kaijuu Carameliser",
            "Omae Gotoki ga Maou ni Kateru to Omou na",
            "Saigo ni Hitotsu dake Onegai shitemo Yoroshii deshou ka",
            "Sakamoto Days",
            "Prism Rondo",
            "Rising Impact",
            "Hanagiri-san wa Tensei shitemo Kenka ga Shitai",
            "Tensai Ouji no Akaji Kokka Saisei Jutsu",
            "BULLET/BULLET",
            "Akuyaku Reijou to Kyouketsu Koushaku",
            "Pluto",
        )

        val terms = boundedAniListSearchTerms(missingCoverTitles, limit = 128)

        missingCoverTitles.forEach { title ->
            assertTrue(
                "A known missing-cover title was discarded: $title",
                terms.any {
                    MediaTitleNormalizer.normalizeTitle(it) ==
                        MediaTitleNormalizer.normalizeTitle(title)
                },
            )
        }
    }

}
