package com.reiflix.reiflix_local.data.anilist

import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixArtworkDiscoveryPolicyTest {

    @Test
    fun scanWindowIsBoundedAndScalesWithLookupBudget() {
        assertEquals(32, artworkDiscoveryScanLimit(1))
        assertEquals(96, artworkDiscoveryScanLimit(12))
        assertEquals(192, artworkDiscoveryScanLimit(24))
    }

    @Test(expected = IllegalArgumentException::class)
    fun scanWindowRejectsAnInvalidLookupBudget() {
        artworkDiscoveryScanLimit(25)
    }
}
