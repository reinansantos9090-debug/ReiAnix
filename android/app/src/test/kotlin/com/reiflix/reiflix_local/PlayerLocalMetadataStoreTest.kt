package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.player.PlayerLocalMetadataStore
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
}
