package com.reiflix.reiflix_local.data.anilist

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixAniListSynopsisOwnershipTest {
    @Test
    fun missingBlankAndEmptyOwnershipListsRemainUnknown() {
        assertEquals("unknown", classifySynopsisOwnership(null, null, null))
        assertEquals("unknown", classifySynopsisOwnership(null, null, ""))
        assertEquals("unknown", classifySynopsisOwnership(null, null, "   "))
        assertEquals("unknown", classifySynopsisOwnership(null, null, "[]"))
    }

    @Test
    fun malformedOrNonStringOwnershipEntriesRemainUnknown() {
        assertEquals("unknown", classifySynopsisOwnership(null, null, "{broken"))
        assertEquals("unknown", classifySynopsisOwnership(null, null, """["genres", 7]"""))
        assertEquals("unknown", classifySynopsisOwnership(null, null, """[""]"""))
    }

    @Test
    fun descriptionFieldAndRowLevelManualMarkersProtectSynopsis() {
        assertEquals("manual", classifySynopsisOwnership(null, null, """["description"]"""))
        assertEquals("manual", classifySynopsisOwnership("manual", null, null))
        assertEquals("manual", classifySynopsisOwnership(null, "manual", "[]"))
    }

    @Test
    fun validNonEmptyOwnershipListWithoutDescriptionAllowsAutomaticSynopsisRecovery() {
        assertEquals("automatic", classifySynopsisOwnership(null, null, """["genres", "title"]"""))
    }
}
