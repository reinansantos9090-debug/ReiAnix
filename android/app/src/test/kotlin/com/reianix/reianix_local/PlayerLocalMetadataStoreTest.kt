package com.reianix.reianix_local
import android.app.Application
import org.robolectric.annotation.Config

import com.reianix.reianix_local.player.PlayerLocalMetadataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlayerLocalMetadataStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val metadataFile get() = File(context.filesDir, "player_metadata.json")

    @Before
    fun clearPersistedMetadata() {
        metadataFile.delete()
        File(context.filesDir, "player_metadata.json.tmp").delete()
    }

    private fun store() = PlayerLocalMetadataStore(context)

    @Test
    fun segmentValidationRejectsInvalidRanges() {
        val valid = PlayerLocalMetadataStore.Segment(1_000L, 2_000L)
        assertEquals(1_000L, valid.startMs)
        assertEquals(2_000L, valid.endMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun segmentValidationRejectsReversedRanges() {
        PlayerLocalMetadataStore.Segment(2_000L, 1_000L)
    }

    @Test
    fun markerRangesMustBeCompleteOrderedAndWithinKnownDuration() {
        assertTrue(PlayerLocalMetadataStore.isValidSegment(null, null, 100_000L))
        assertTrue(PlayerLocalMetadataStore.isValidSegment(10_000L, 20_000L, 100_000L))
        assertFalse(PlayerLocalMetadataStore.isValidSegment(20_000L, 10_000L, 100_000L))
        assertFalse(PlayerLocalMetadataStore.isValidSegment(10_000L, null, 100_000L))
        assertFalse(PlayerLocalMetadataStore.isValidSegment(10_000L, 120_000L, 100_000L))
        assertFalse(PlayerLocalMetadataStore.isValidSegment(-1L, 10L, 100_000L))
        assertFalse(PlayerLocalMetadataStore.isValidSegment(10L, 10L, 100_000L))
    }

    @Test
    fun validMarkerUpdatePersistsBothMarkers() {
        val metadataStore = store()

        val saved = metadataStore.setSegments("episode-a", 1_000L, 2_000L, 3_000L, 4_000L, 10_000L)

        assertEquals(saved, metadataStore.get("episode-a"))
        assertEquals(PlayerLocalMetadataStore.Segment(1_000L, 2_000L), saved.opening)
        assertEquals(PlayerLocalMetadataStore.Segment(3_000L, 4_000L), saved.ending)
    }

    @Test
    fun incompleteOpeningRejectsWholeOperationAndPreservesExistingMarkers() {
        val metadataStore = store()
        val before = metadataStore.setSegments("episode-a", 1_000L, 2_000L, 3_000L, 4_000L, 10_000L)

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 5_000L, null, 6_000L, 7_000L, 10_000L)
        }

        assertEquals(before, metadataStore.get("episode-a"))
    }

    @Test
    fun incompleteEndingRejectsWholeOperationAndPreservesExistingMarkers() {
        val metadataStore = store()
        val before = metadataStore.setSegments("episode-a", 1_000L, 2_000L, 3_000L, 4_000L, 10_000L)

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 5_000L, 6_000L, null, 7_000L, 10_000L)
        }

        assertEquals(before, metadataStore.get("episode-a"))
    }

    @Test
    fun reversedAndOutOfDurationIntervalsRejectWithoutChangingEitherMarker() {
        val metadataStore = store()
        val before = metadataStore.setSegments("episode-a", 1_000L, 2_000L, 3_000L, 4_000L, 10_000L)

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 8_000L, 7_000L, 5_000L, 6_000L, 10_000L)
        }
        assertEquals(before, metadataStore.get("episode-a"))

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 5_000L, 6_000L, 9_000L, 11_000L, 10_000L)
        }
        assertEquals(before, metadataStore.get("episode-a"))
    }

    @Test
    fun validOpeningWithInvalidEndingDoesNotPartiallyUpdateAndInvalidOpeningWithValidEndingDoesNotPartiallyUpdate() {
        val metadataStore = store()
        val before = metadataStore.setSegments("episode-a", 1_000L, 2_000L, 3_000L, 4_000L, 10_000L)

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 5_000L, 6_000L, 9_000L, 8_000L, 10_000L)
        }
        assertEquals(before, metadataStore.get("episode-a"))

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 8_000L, 7_000L, 5_000L, 6_000L, 10_000L)
        }
        assertEquals(before, metadataStore.get("episode-a"))
    }

    @Test
    fun explicitClearIsAllowedWhenOtherMarkerIsValid() {
        val metadataStore = store()
        metadataStore.setSegments("episode-a", 1_000L, 2_000L, 3_000L, 4_000L, 10_000L)

        val updated = metadataStore.setSegments("episode-a", null, null, 5_000L, 6_000L, 10_000L)

        assertEquals(null, updated.opening)
        assertEquals(PlayerLocalMetadataStore.Segment(5_000L, 6_000L), updated.ending)
        assertEquals(updated, metadataStore.get("episode-a"))
    }

    @Test
    fun invalidUpdatePreservesNotesAndMetadataForOtherEpisodes() {
        val metadataStore = store()
        metadataStore.setSegments("episode-a", 1_000L, 2_000L, 3_000L, 4_000L, 10_000L)
        metadataStore.addNote("episode-a", 1_500L, "nota preservada", nowMs = 42L)
        val otherBefore = metadataStore.setSegments("episode-b", 2_000L, 3_000L, 4_000L, 5_000L, 10_000L)
        val before = metadataStore.get("episode-a")
        val notesBefore = before.notes

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 7_000L, null, 8_000L, 9_000L, 10_000L)
        }

        assertEquals(before, metadataStore.get("episode-a"))
        assertEquals(notesBefore, metadataStore.get("episode-a").notes)
        assertEquals(otherBefore, metadataStore.get("episode-b"))
    }

    @Test
    fun unknownDurationAllowsOtherwiseValidIntervals() {
        val metadataStore = store()

        val saved = metadataStore.setSegments("episode-a", 50_000L, 60_000L, null, null, durationMs = null)

        assertEquals(PlayerLocalMetadataStore.Segment(50_000L, 60_000L), saved.opening)
        assertEquals(null, saved.ending)
        assertEquals(saved, metadataStore.get("episode-a"))
    }

    @Test
    fun invalidInputDoesNotCreateOrRewriteTheMetadataFile() {
        val metadataStore = store()

        assertThrows(IllegalArgumentException::class.java) {
            metadataStore.setSegments("episode-a", 1_000L, null, null, null, 10_000L)
        }

        assertFalse(metadataFile.exists())
    }

    @Test
    fun favoriteScenePersistsAcrossStoreReopeningAndCanBeDeleted() {
        val first = store().addNote("episode-a", 15_000L, "Cena favorita", favorite = true)
        assertTrue(first.notes.single().favorite)
        val reopened = store().get("episode-a")
        assertEquals(15_000L, reopened.notes.single().timestampMs)
        assertTrue(reopened.notes.single().favorite)
        assertTrue(store().get("episode-b").notes.isEmpty())
        val removed = store().deleteNote("episode-a", reopened.notes.single().id)
        assertTrue(removed.notes.isEmpty())
        assertTrue(store().get("episode-a").notes.isEmpty())
    }

    @Test
    fun clipPersistsBothBoundariesAndAppearsAcrossEpisodes() {
        val store = store()
        store.addNote("episode-a", 1_000L, "Nota comum")
        val first = store.saveFavoriteClip("episode-a", 5_000L, 65_000L, 100_000L,
            "Minha cena", "Anime A", "Episódio 1")
        val saved = first.notes.single { it.favorite }
        store.saveFavoriteClip("episode-b", 1_000L, 2_000L, 5_000L,
            "Outra cena", "Anime B", "Episódio 2")
        val all = store().favoriteScenes()
        assertEquals(2, all.size)
        assertEquals(saved, all.first { it.mediaId == "episode-a" }.note)
        assertEquals(65_000L, saved.clipEndMs)
        assertEquals("Anime A", saved.animeTitle)
        assertEquals("Episódio 1", saved.episodeLabel)
        assertEquals(2, store().get("episode-a").notes.size)
    }

    @Test
    fun editLegacyFavoritePreservesIdAndRejectsInvalidClipWithoutWriting() {
        val store = store()
        val old = store.addNote("episode-a", 15_000L, "Marcador", favorite = true).notes.single()
        assertEquals(null, old.clipEndMs)
        val updated = store.saveFavoriteClip("episode-a", 10_000L, 20_000L, 30_000L,
            "Clipe", "Anime A", "Episódio 1", old.id)
        assertEquals(old.id, updated.notes.single().id)
        assertEquals(20_000L, updated.notes.single().clipEndMs)
        assertThrows(IllegalArgumentException::class.java) {
            store.saveFavoriteClip("episode-a", 25_000L, 40_000L, 30_000L,
                "Inválido", "Anime A", "Episódio 1", old.id)
        }
        assertEquals(updated, store().get("episode-a"))
        store.deleteNote("episode-a", old.id)
        assertTrue(store().favoriteScenes().isEmpty())
    }

    @Test
    fun initialClipAndEditorStayInsideEpisodeBounds() {
        val policy = com.reianix.reianix_local.player.FavoriteClipRange
        assertEquals(PlayerLocalMetadataStore.Segment(10_000L, 70_000L),
            policy.initial(10_000L, 100_000L))
        assertEquals(PlayerLocalMetadataStore.Segment(99_000L, 100_000L),
            policy.initial(100_000L, 100_000L))
        assertEquals(PlayerLocalMetadataStore.Segment(0L, 500L), policy.initial(0L, 500L))
        assertEquals(19_000L, policy.editStart(25_000L, 20_000L, 100_000L))
        assertEquals(11_000L, policy.editEnd(0L, 10_000L, 100_000L))
        assertEquals(100_000L, policy.editEnd(150_000L, 10_000L, 100_000L))
    }

    @Test
    fun visualEditorStartsWithFullEpisodeAndPreservesExistingClip() {
        val policy = com.reianix.reianix_local.player.FavoriteClipRange
        assertEquals(PlayerLocalMetadataStore.Segment(0L, 100_000L),
            policy.editorInitial(100_000L, 30_000L, null))
        val note = store().saveFavoriteClip("episode-a", 12_000L, 42_000L,
            100_000L, "Cena", "Anime A", "Episódio 1").notes.single()
        assertEquals(PlayerLocalMetadataStore.Segment(12_000L, 42_000L),
            policy.editorInitial(100_000L, 12_000L, note))
        val legacy = note.copy(clipEndMs = null)
        assertEquals(PlayerLocalMetadataStore.Segment(12_000L, 72_000L),
            policy.editorInitial(100_000L, 12_000L, legacy))
    }

    @Test
    fun filmstripSamplesCoverEpisodeWithoutSeekingOutsideIt() {
        val policy = com.reianix.reianix_local.player.FavoriteClipRange
        val times = (0 until 8).map { policy.thumbnailTime(80_000L, it, 8) }
        assertEquals(listOf(5_000L, 15_000L, 25_000L, 35_000L,
            45_000L, 55_000L, 65_000L, 75_000L), times)
        assertEquals(0L, policy.thumbnailTime(1L, 7, 8))
    }

    @Test
    fun oldNotesRemainNormalNotes() {
        val saved = store().addNote("episode-a", 5_000L, "Nota comum")
        assertFalse(saved.notes.single().favorite)
        assertFalse(store().get("episode-a").notes.single().favorite)
    }
}
