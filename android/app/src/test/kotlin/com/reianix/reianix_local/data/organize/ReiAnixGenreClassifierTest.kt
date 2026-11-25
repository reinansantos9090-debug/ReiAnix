package com.reianix.reianix_local.data.organize
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
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
            parseReiAnixGenreNames("[\"Action\", \" acción \", \"Drama\"]"),
        )
        assertEquals(emptyList<String>(), parseReiAnixGenreNames("null"))
        assertTrue(parseReiAnixGenreNames("not-json").contains("not-json"))

        val registered = listOf(com.reianix.reianix_local.ui.model.ReiAnixGenreUiModel("existing-id", "Action"))
        val preserved = ReiAnixGenreNames.preserveMetadataGenres(registered, listOf("Ação", "Drama", "Music"))
        assertEquals(listOf("Action", "Drama", "Music"), preserved.map { it.name })
        assertEquals(listOf("existing-id", null, null), preserved.map { it.id })
        assertEquals(listOf("Ação", "Drama", "Música"), ReiAnixGenreNames.options(preserved).map { it.name })
        assertEquals(listOf("Music"), ReiAnixGenreNames.preserveMetadataGenres(emptyList(), listOf("Music")).map { it.name })
    }
}
