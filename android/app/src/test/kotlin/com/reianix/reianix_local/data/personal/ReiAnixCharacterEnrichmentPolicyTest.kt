package com.reianix.reianix_local.data.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy tests: no AniList network, emulator, SQLite or image-loading side effects. */
class ReiAnixCharacterEnrichmentPolicyTest {

    @Test
    fun onlyConfirmedPositiveAniListIdsMayEnterTheCharacterLane() {
        assertNull(validCharacterAniListId(null))
        assertNull(validCharacterAniListId(0L))
        assertNull(validCharacterAniListId(-9L))
        assertNull(validCharacterAniListId(Int.MAX_VALUE.toLong() + 1))
        assertEquals(1, validCharacterAniListId(1L))
        assertEquals(16498, validCharacterAniListId(16498L))
        assertEquals(Int.MAX_VALUE, validCharacterAniListId(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun validCharacterCacheRemainsFreshForSevenDaysWithoutAForcedRefresh() {
        val now = 1_900_000_000_000L
        val day = 24L * 60 * 60 * 1_000
        assertTrue(ReiAnixCharacterCache.isCharacterCacheFresh(now, now))
        assertTrue(ReiAnixCharacterCache.isCharacterCacheFresh(now - 7 * day, now))
        assertFalse(ReiAnixCharacterCache.isCharacterCacheFresh(now - 7 * day - 1, now))
        assertFalse(ReiAnixCharacterCache.isCharacterCacheFresh(0L, now))
        assertFalse(ReiAnixCharacterCache.isCharacterCacheFresh(now + day, now))
    }
}
