package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.player.PlayerLocalMetadataStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLocalMetadataStoreTest {
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
    fun playerMetadataJsonRoundTripKeepsNullMarkersExplicit() {
        val json = JSONObject()
            .put("openingStartMs", 60_000L)
            .put("openingEndMs", 90_000L)
            .put("endingStartMs", JSONObject.NULL)
            .put("endingEndMs", JSONObject.NULL)
        assertEquals(60_000L, json.getLong("openingStartMs"))
        assertTrue(json.isNull("endingStartMs"))
        assertTrue(json.isNull("endingEndMs"))
    }
}
