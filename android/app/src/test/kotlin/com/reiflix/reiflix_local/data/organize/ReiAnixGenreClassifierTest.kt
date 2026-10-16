package com.reiflix.reiflix_local.data.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixGenreClassifierTest {
    @Test
    fun legacyRuleFamiliesArePortedInStableOrder() {
        assertEquals(listOf("Ação"), ReiAnixGenreClassifier.classify("Naruto"))
        assertEquals(listOf("Aventura"), ReiAnixGenreClassifier.classify("One Piece"))
        assertEquals(listOf("Fantasia"), ReiAnixGenreClassifier.classify("Isekai Dungeon"))
        assertEquals(listOf("Romance"), ReiAnixGenreClassifier.classify("Horimiya"))
        assertEquals(listOf("Comédia"), ReiAnixGenreClassifier.classify("Kaguya-sama"))
        assertEquals(listOf("Terror"), ReiAnixGenreClassifier.classify("Tokyo Ghoul"))
        assertEquals(listOf("Esporte"), ReiAnixGenreClassifier.classify("Haikyuu"))
    }

    @Test
    fun unknownLocalTitleDoesNotReceivePlaceholderGenres() {
        assertEquals(emptyList<String>(), ReiAnixGenreClassifier.classify("Completely Unknown Local Title"))
    }

    @Test
    fun genreIdentityNormalizationMatchesLegacyAccentAndSeparatorRules() {
        assertEquals("sci fi action / drama", normalizeReiAnixGenre("  Sci-Fi_Action / Dráma! "))
        assertEquals("comedia", normalizeReiAnixGenre("COMÉDIA"))
        assertEquals("", normalizeReiAnixGenre(" !!! "))
    }

    @Test
    fun legacyGenreJsonParserPreservesNamesAndIgnoresInvalidArrayItems() {
        assertEquals(
            listOf("Action", "acción", "Drama"),
            parseReiAnixGenreNames("[\"Action\", \" acción \", \"Drama\", null, 4]"),
        )
        assertEquals(emptyList<String>(), parseReiAnixGenreNames("null"))
        assertTrue(parseReiAnixGenreNames("not-json").contains("not-json"))
    }
}
