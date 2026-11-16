package com.reianix.reianix_local.ui.discovery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reianix.reianix_local.data.library.ReiAnixLibraryDiscovery
import com.reianix.reianix_local.data.sqlite.ReiAnixTimelineSourceRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReiAnixTimelineListTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun timelineShowsOnlyPosterAndTitleAndOpensOriginalAnimeId() {
        var openedId = -1L
        val grouped = ReiAnixLibraryDiscovery.timelineGroups(
            listOf(source(7L, "Anime principal", 2026)),
        )
        composeRule.setContent {
            MaterialTheme {
                ReiAnixTimelineList(
                    groups = grouped,
                    artworkByAnimeId = emptyMap(),
                    onOpenAnime = { openedId = it },
                )
            }
        }
        composeRule.onNodeWithText("2026").assertIsDisplayed()
        composeRule.onNodeWithText("Anime principal").assertIsDisplayed()
        composeRule.onNodeWithText("Abrir").assertDoesNotExist()
        composeRule.onNodeWithText("Anime principal").performClick()
        assertEquals(7L, openedId)
    }

    @Test
    fun timelineLargeListRendersOnDemandWithoutLosingSortingOrNavigation() {
        val grouped = ReiAnixLibraryDiscovery.timelineGroups(
            (1L..120L).map { id ->
                source(id, "Anime ${id.toString().padStart(3, '0')}", 2026)
            } + source(999L, "Anterior", 2024),
        )
        var openedId = -1L
        composeRule.setContent {
            MaterialTheme {
                ReiAnixTimelineList(
                    groups = grouped,
                    artworkByAnimeId = emptyMap(),
                    onOpenAnime = { openedId = it },
                )
            }
        }
        composeRule.onNodeWithText("Anime 001").assertIsDisplayed()
        // A distant item must not be eagerly composed when the list opens.
        composeRule.onNodeWithText("Anime 120").assertDoesNotExist()
        composeRule.onRoot().performScrollToNode(hasText("Anime 120"))
        composeRule.onNodeWithText("Anime 120").performClick()
        assertEquals(120L, openedId)
    }

    private fun source(id: Long, title: String, year: Int) = ReiAnixTimelineSourceRecord(
        id = id,
        title = title,
        year = year,
        season = "Fall",
        status = "Returning",
        mediaKind = "series",
        coverCache = null,
        coverUrl = "https://example.invalid/never-download.jpg",
        addedAt = null,
    )
}
