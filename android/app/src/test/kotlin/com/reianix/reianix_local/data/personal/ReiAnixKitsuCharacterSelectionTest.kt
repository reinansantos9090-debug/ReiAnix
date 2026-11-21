package com.reianix.reianix_local.data.personal

import com.reianix.reianix_local.data.catalog.ReiAnixCatalogAnime
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogIdentity
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogSource
import com.reianix.reianix_local.data.catalog.ReiAnixExternalCatalogIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The candidate-only Kitsu artwork path has no trusted year/format dimensions. */
class ReiAnixKitsuCharacterSelectionTest {
    private val identity = ReiAnixCatalogIdentity(
        titles = setOf("Cowboy Bebop"),
        releaseYear = null,
        format = null,
        mediaKind = null,
    )
    private fun kitsu(id: String, title: String, poster: String) = ReiAnixCatalogAnime(
        source = ReiAnixCatalogSource.KITSU,
        ids = ReiAnixExternalCatalogIds(kitsuId = id),
        title = title,
        coverUrl = poster,
        releaseYear = 1998,
        format = "TV",
    )

    @Test
    fun candidateOnlyMetadataUsesUniquelyMatchingReadyKitsuPosterAndExactTitle() {
        val match = kitsu("2", "Cowboy Bebop", "https://media.kitsu.app/poster-2.jpg")
        val different = kitsu("99", "Cowboy Bebop", "https://media.kitsu.app/poster-99.jpg")
        val result = selectKitsuCastCandidate(
            42L, identity, listOf(different, match), ReiAnixExternalCatalogIds(),
            setOf("https://media.kitsu.app/poster-2.jpg"),
        )
        assertEquals("2", result?.ids?.kitsuId)
    }

    @Test
    fun unanchoredTitleOnlyMatchCannotAttachPotentiallyWrongKitsuCast() {
        val candidate = kitsu("2", "Cowboy Bebop", "https://media.kitsu.app/poster-2.jpg")
        assertNull(selectKitsuCastCandidate(
            42L, identity, listOf(candidate), ReiAnixExternalCatalogIds(), emptySet(),
        ))
        assertNull(selectKitsuCastCandidate(
            42L, identity, listOf(candidate), ReiAnixExternalCatalogIds(),
            setOf("https://media.kitsu.app/wrong.jpg"),
        ))
    }

    @Test
    fun posterMatchWithoutCompatibleTitleCannotAttachAnotherAnimesCharacters() {
        val candidate = kitsu("2", "Some Other Anime", "https://media.kitsu.app/poster-2.jpg")
        assertNull(selectKitsuCastCandidate(
            42L, identity, listOf(candidate), ReiAnixExternalCatalogIds(),
            setOf("https://media.kitsu.app/poster-2.jpg"),
        ))
    }

    @Test
    fun ambiguousPosterAnchorDoesNotGuessAnAnime() {
        val candidates = listOf(
            kitsu("2", "Cowboy Bebop", "https://media.kitsu.app/shared.jpg"),
            kitsu("3", "Cowboy Bebop", "https://media.kitsu.app/shared.jpg"),
        )
        assertNull(selectKitsuCastCandidate(
            42L, identity, candidates, ReiAnixExternalCatalogIds(),
            setOf("https://media.kitsu.app/shared.jpg"),
        ))
    }
}
