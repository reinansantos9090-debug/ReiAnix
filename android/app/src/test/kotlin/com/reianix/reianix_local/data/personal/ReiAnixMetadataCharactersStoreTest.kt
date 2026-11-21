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
}
