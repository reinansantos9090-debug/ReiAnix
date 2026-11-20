package com.reianix.reianix_local.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.ReiAnixAnimeCard
import com.reianix.reianix_local.ui.navigation.ReiAnixCurrentDestination
import com.reianix.reianix_local.ui.ReiAnixEpisodeCard
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaAvailability
import com.reianix.reianix_local.ui.theme.LocalReiAnixWallpaperVisible
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReiAnixWallpaperCardsInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outgoingTransparentDestinationCannotDrawOverActiveEntry() {
        val active = mutableStateOf("profile")
        val wallpaper = Color(0xFF244A66)
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                Box(Modifier.fillMaxSize().background(wallpaper).testTag("navigation-frame")) {
                    ReiAnixCurrentDestination("home", { active.value }) {
                        Box(Modifier.fillMaxSize().background(Color.Green))
                    }
                    // Deliberately keep Profile composed above Home, exactly
                    // as a retained outgoing navigation entry can be.
                    ReiAnixCurrentDestination("profile", { active.value }) {
                        Box(Modifier.fillMaxSize().background(Color.Magenta))
                    }
                }
            }
        }
        fun centerColor(): Color {
            val pixels = composeRule.onNodeWithTag("navigation-frame").captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        assertTrue(centerColor().red > .9f && centerColor().blue > .9f)
        composeRule.runOnIdle { active.value = "home" }
        composeRule.waitForIdle()
        val current = centerColor()
        assertTrue("The retained profile must no longer be drawn", current.green > .9f && current.red < .1f && current.blue < .1f)
    }

    @Test
    fun customizationShowsCategoriesAndSavesFrameWithoutGlobalSave() {
        val store = ReiAnixProfileStore.from(composeRule.activity)
        val original = store.state.value
        try {
            runBlocking { store.save(original.copy(frame = "none")) }
            composeRule.setContent {
                ReiAnixComposeTheme(themeMode = "dark") {
                    ProfileCustomization(store, emptyList(), onBack = {})
                }
            }
            listOf("Foto e avatares", "Moldura", "Banner do perfil", "Papel de parede", "Anime em destaque").forEach {
                composeRule.onNodeWithText(it).assertExists()
            }
            composeRule.onNodeWithText("Cor do perfil").assertDoesNotExist()
            composeRule.onNodeWithText("Salvar perfil").assertDoesNotExist()
            composeRule.onNodeWithText("Avatares personalizados").assertDoesNotExist()
            composeRule.onNodeWithText("Moldura").performClick()
            composeRule.onNodeWithText("Dourada").assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5000) { store.state.value.frame == "gold" }
            assertEquals(original.color, store.state.value.color)
            composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Personalização do perfil").assertIsDisplayed()
            composeRule.onNodeWithText("Dourada").assertDoesNotExist()
        } finally {
            runBlocking { store.save(original) }
        }
    }

    @Test
    fun posterAndEpisodeMetadataRevealWallpaperOnlyWhenEnabled() {
        val active = mutableStateOf(true)
        val wallpaperColor = Color(0xFF244A66)
        val episode = ReiAnixEpisodeUiModel(
            id = 1, animeId = 2, seasonNumber = 1, number = 1.0,
            title = "Episódio", fileName = "episode.mp4",
            media = ReiAnixLocalMediaUiModel("episode.mp4", null, "episode.mp4",
                null, null, ReiAnixMediaAvailability.AVAILABLE),
            progressSeconds = null, durationSeconds = null, watched = false,
            consumptionState = null, artwork = null,
        )
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                Box(Modifier.fillMaxSize().background(wallpaperColor)) {
                    CompositionLocalProvider(LocalReiAnixWallpaperVisible provides active.value) {
                        Column {
                            ReiAnixAnimeCard(title = "Anime", artworkPath = null,
                                metadata = listOf("2026"), modifier = Modifier.width(132.dp).testTag("wallpaper-anime"))
                            ReiAnixEpisodeCard(episode = episode, showThumbnail = false,
                                modifier = Modifier.width(280.dp).testTag("wallpaper-episode"))
                        }
                    }
                }
            }
        }
        fun fraction(tag: String): Float {
            val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            val left = (pixels.width * .72f).toInt()
            val right = (pixels.width * .90f).toInt()
            val top = (pixels.height * .86f).toInt()
            val bottom = (pixels.height * .94f).toInt()
            var matching = 0
            var count = 0
            for (y in top until bottom) for (x in left until right) {
                val p = pixels[x, y]
                if (kotlin.math.abs(p.red - wallpaperColor.red) < .025f &&
                    kotlin.math.abs(p.green - wallpaperColor.green) < .025f &&
                    kotlin.math.abs(p.blue - wallpaperColor.blue) < .025f) matching++
                count++
            }
            return matching.toFloat() / count.coerceAtLeast(1)
        }
        assertTrue("Anime metadata should reveal the wallpaper", fraction("wallpaper-anime") > .5f)
        assertTrue("Episode row should reveal the wallpaper", fraction("wallpaper-episode") > .5f)
        composeRule.runOnIdle { active.value = false }
        composeRule.waitForIdle()
        assertTrue("Original anime background should be restored", fraction("wallpaper-anime") < .1f)
        assertTrue("Original episode background should be restored", fraction("wallpaper-episode") < .1f)
    }
}
