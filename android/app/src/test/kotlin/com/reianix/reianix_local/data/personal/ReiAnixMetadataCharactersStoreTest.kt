package com.reianix.reianix_local.data.personal

import com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixMetadataCharactersStoreTest {

    @Test
    fun publishFromMetadataIsVisibleImmediatelyWithoutRequestOrOpenDetails() {
        val store = ReiAnixMetadataCharactersStore(RuntimeEnvironment.getApplication())
        val characters = listOf(ReiAnixAnimeCharacter(42, "Hero", "Principal", "https://img/42.jpg"))
        store.publish(91L, "anilist", "123", characters)
        assertEquals(characters, store.entries.value[91L]?.characters)
        assertNull(store.entries.value[92L])
    }

    @Test
    fun primaryAniListCastIsNotOverwrittenBySupplementaryKitsu() {
        val store = ReiAnixMetadataCharactersStore(RuntimeEnvironment.getApplication())
        val kitsu = listOf(ReiAnixAnimeCharacter(2, "Kitsu Hero", "Principal", null))
        val aniList = listOf(ReiAnixAnimeCharacter(3, "AniList Hero", "Principal", null))
        store.publish(93L, "kitsu", "72", kitsu)
        assertEquals(kitsu, store.entries.value[93L]?.characters)
        store.publish(93L, "anilist", "44", aniList)
        store.publish(93L, "kitsu", "72", kitsu)
        assertEquals(aniList, store.entries.value[93L]?.characters)
        assertEquals("anilist", store.entries.value[93L]?.source)
    }

    @Test
    fun aMissingOptionalCastDoesNotEraseExistingMetadata() {
        val store = ReiAnixMetadataCharactersStore(RuntimeEnvironment.getApplication())
        val existing = listOf(ReiAnixAnimeCharacter(2, "Hero", "Principal", null))
        store.publish(94L, "anilist", "55", existing)
        store.publish(94L, "anilist", "55", null)
        store.publish(94L, "anilist", "55", emptyList())
        assertEquals(existing, store.entries.value[94L]?.characters)
        assertTrue(store.entries.value[94L]!!.characters.isNotEmpty())
    }
    @Test
    fun paginatedMetadataAppendsAllCharactersWithoutLegacyEighteenItemLimit() {
        val store = ReiAnixMetadataCharactersStore(RuntimeEnvironment.getApplication())
        val first = (1..10).map { ReiAnixAnimeCharacter(it, "Character $it", "Principal", null) }
        val second = (11..35).map { ReiAnixAnimeCharacter(it, "Character $it", "Secundário", null) }
        store.publish(95L, "anilist", "100", first, complete = false)
        store.publish(95L, "anilist", "100", second, complete = true)
        assertEquals(35, store.entries.value[95L]?.characters?.size)
        assertTrue(store.entries.value[95L]?.complete == true)
        store.publish(95L, "anilist", "100", first, complete = false)
        assertEquals(35, store.entries.value[95L]?.characters?.size)
        assertTrue(store.entries.value[95L]?.complete == true)
    }

    @Test
    fun missingAniListCastDoesNotEraseExistingKitsuCharacters() {
        val store = ReiAnixMetadataCharactersStore(RuntimeEnvironment.getApplication())
        val cast = listOf(ReiAnixAnimeCharacter(7, "Only Kitsu", "Principal", null))
        store.publish(96L, "kitsu", "180", cast, complete = true)
        store.publish(96L, "anilist", "20", emptyList(), complete = true)
        assertEquals(cast, store.entries.value[96L]?.characters)
    }

}
