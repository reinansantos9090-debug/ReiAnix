package com.reiflix.reiflix_local.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixAniListViewModelSearchGenerationTest {

    @Test
    fun obsoleteQueryCannotPublishResultsAfterASecondQueryStarts() {
        val firstQueryRequestId = 41L
        val latestQueryRequestId = 42L

        assertFalse(
            isCurrentAniListSearchRequest(
                requestId = firstQueryRequestId,
                currentRequestId = latestQueryRequestId,
            ),
        )
        assertTrue(
            isCurrentAniListSearchRequest(
                requestId = latestQueryRequestId,
                currentRequestId = latestQueryRequestId,
            ),
        )
    }

    @Test
    fun completedQueryIsAcceptedOnlyWhileItsGenerationRemainsCurrent() {
        val completedRequestId = 100L
        assertTrue(isCurrentAniListSearchRequest(completedRequestId, 100L))
        assertFalse(isCurrentAniListSearchRequest(completedRequestId, 101L))
    }
}
