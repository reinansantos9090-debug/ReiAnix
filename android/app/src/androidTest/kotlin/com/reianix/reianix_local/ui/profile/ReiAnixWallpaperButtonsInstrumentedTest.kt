package com.reianix.reianix_local.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.ReiAnixSecondaryButton
import com.reianix.reianix_local.ui.ReiAnixPrimaryButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.test.assertIsNotEnabled
import com.reianix.reianix_local.ui.theme.LocalReiAnixWallpaperVisible
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReiAnixWallpaperButtonsInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optedInButtonsRevealWallpaperAndRestoreOriginalStyle() {
        val active = mutableStateOf(true)
        val wallpaper = Color(0xFF244A66)
        val labels = listOf("Selecionar pasta", "Adicionar pasta", "Atualizar")
        var clicks = 0
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                Column(Modifier.fillMaxSize().background(wallpaper)) {
                    CompositionLocalProvider(LocalReiAnixWallpaperVisible provides active.value) {
                        labels.forEachIndexed { index, label ->
                            ReiAnixSecondaryButton(
                                text = label,
                                onClick = { clicks++ },
                                modifier = Modifier.width(240.dp).testTag("wallpaper-action-$index"),
                                transparentOnWallpaper = true,
                            )
                        }
                        ReiAnixSecondaryButton(
                            text = "Outro botão",
                            onClick = {},
                            modifier = Modifier.width(240.dp).testTag("opaque-action"),
                        )
                    }
                }
            }
        }
        fun backgroundMatches(tag: String): Boolean {
            val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            val p = pixels[(pixels.width * .16f).toInt(), pixels.height / 2]
            return kotlin.math.abs(p.red - wallpaper.red) < .025f &&
                kotlin.math.abs(p.green - wallpaper.green) < .025f &&
                kotlin.math.abs(p.blue - wallpaper.blue) < .025f
        }
        labels.indices.forEach { index ->
            val tag = "wallpaper-action-$index"
            assertTrue(backgroundMatches(tag))
            composeRule.onNodeWithTag(tag).performClick()
        }
        composeRule.runOnIdle {
            assertEquals(3, clicks)
            active.value = false
        }
        composeRule.waitForIdle()
        labels.indices.forEach { assertTrue(!backgroundMatches("wallpaper-action-$it")) }
        composeRule.runOnIdle { active.value = true }
        composeRule.waitForIdle()
        assertTrue(!backgroundMatches("opaque-action"))
    }

    @Test
    fun detailButtonsRevealWallpaperAndKeepPlaybackStates() {
        val active = mutableStateOf(true)
        val playable = mutableStateOf(true)
        val wallpaper = Color(0xFF244A66)
        var clicks = 0
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                Column(Modifier.fillMaxSize().background(wallpaper)) {
                    CompositionLocalProvider(LocalReiAnixWallpaperVisible provides active.value) {
                        ReiAnixPrimaryButton(
                            text = "Assistir",
                            onClick = { clicks++ },
                            enabled = playable.value,
                            modifier = Modifier.width(280.dp).testTag("detail-play"),
                            leadingIcon = Icons.Filled.PlayArrow,
                            transparentOnWallpaper = true,
                        )
                        listOf("Maratona", "Identificar capa com Trace.moe").forEachIndexed { index, label ->
                            ReiAnixSecondaryButton(
                                text = label,
                                onClick = { clicks++ },
                                modifier = Modifier.width(280.dp).testTag("detail-secondary-$index"),
                                transparentOnWallpaper = true,
                            )
                        }
                        ReiAnixPrimaryButton(
                            text = "Outro botão",
                            onClick = {},
                            modifier = Modifier.width(280.dp).testTag("opaque-primary"),
                        )
                    }
                }
            }
        }
        fun backgroundMatches(tag: String): Boolean {
            val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            val p = pixels[(pixels.width * .08f).toInt(), pixels.height / 2]
            return kotlin.math.abs(p.red - wallpaper.red) < .025f &&
                kotlin.math.abs(p.green - wallpaper.green) < .025f &&
                kotlin.math.abs(p.blue - wallpaper.blue) < .025f
        }
        val tags = listOf("detail-play", "detail-secondary-0", "detail-secondary-1")
        tags.forEach { tag ->
            assertTrue("Wallpaper should be visible behind $tag", backgroundMatches(tag))
            composeRule.onNodeWithTag(tag).performClick()
        }
        composeRule.runOnIdle {
            assertEquals(3, clicks)
            playable.value = false
        }
        composeRule.onNodeWithTag("detail-play").assertIsNotEnabled()
        assertTrue(backgroundMatches("detail-play"))
        assertTrue(!backgroundMatches("opaque-primary"))
        composeRule.runOnIdle {
            playable.value = true
            active.value = false
        }
        composeRule.waitForIdle()
        tags.forEach { assertTrue("Original background should return for $it", !backgroundMatches(it)) }
    }

}
