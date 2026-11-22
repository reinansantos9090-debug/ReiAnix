package com.reianix.reianix_local.ui.details

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixRecommendationListStateTest {
    @Test
    fun returningFromPlayerRetainsAlreadyLoadedSuggestionsWithoutAnotherDatabaseQuery() {
        assertFalse(shouldLoadRecommendations(
            loadedAnimeId = 42L,
            requestedAnimeId = 42L,
            hasResult = true,
            isLoading = false,
        ))
    }

    @Test
    fun navigatingBackDuringInitialLoadDoesNotLaunchDuplicateRecommendationQueries() {
        assertFalse(shouldLoadRecommendations(
            loadedAnimeId = 42L,
            requestedAnimeId = 42L,
            hasResult = false,
            isLoading = true,
        ))
    }

    @Test
    fun loadingAnotherAnimeOrRetryingAfterFailureStillWorks() {
        assertTrue(shouldLoadRecommendations(42L, 43L, hasResult = true, isLoading = false))
        assertTrue(shouldLoadRecommendations(42L, 42L, hasResult = false, isLoading = false))
        assertTrue(shouldLoadRecommendations(null, 42L, hasResult = false, isLoading = false))
    }

    @Test
    fun loadedEmptyResultsDoNotTriggerRepeatedQueries() {
        // Even an empty list is a completed result, not a loading state.
        assertFalse(shouldLoadRecommendations(42L, 42L, hasResult = true, isLoading = false))
    }
}
