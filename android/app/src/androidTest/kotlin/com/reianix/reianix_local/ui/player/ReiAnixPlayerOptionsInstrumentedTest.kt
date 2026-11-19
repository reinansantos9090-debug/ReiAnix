package com.reianix.reianix_local.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReiAnixPlayerOptionsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun audioAndSubtitleActionsLiveInTheirOwnSubmenus() {
        val uiState = mutableStateOf(ReiAnixNativePlayerUiState(
            audioTrackSelectionEnabled = true,
            subtitleTrackSelectionEnabled = true,
        ))
        var audioSelections = 0
        var subtitleSelections = 0
        var appearanceOpens = 0
        composeRule.setContent {
            ReiAnixComposeTheme {
                ReiAnixNativePlayerOptionsPanel(
                    state = uiState.value,
                    showPictureInPicture = false,
                    resetSignal = 0,
                    onPrevious = {},
                    onNext = {},
                    onPlaybackSpeed = {},
                    onSleepTimer = {},
                    onAudioTracks = { audioSelections++ },
                    onSubtitles = { subtitleSelections++ },
                    onRestart = {},
                    onHistory = {},
                    onMarkers = {},
                    onAutoplayChanged = {},
                    onGesturesChanged = {},
                    onBrightnessVolumeChanged = {},
                    onZoomChanged = {},
                    onDoubleTapChanged = {},
                    onLongPressChanged = {},
                    onOpenWithExternal = {},
                    onPictureInPicture = {},
                    onInformation = {},
                    onSubtitleAppearance = { appearanceOpens++ },
                    onAudioOnlyChanged = { enabled ->
                        uiState.value = uiState.value.copy(audioOnly = enabled)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Personalizar legendas").assertDoesNotExist()
        composeRule.onNodeWithText("Somente áudio").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Áudio").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Selecionar faixa de áudio").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, audioSelections) }
        composeRule.onNodeWithText("Somente áudio").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Somente áudio") and hasClickAction()).performClick()
        composeRule.runOnIdle { assertEquals(true, uiState.value.audioOnly) }
        composeRule.onNodeWithContentDescription("Selecionar faixa de áudio").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Voltar").performClick()
        composeRule.onNodeWithContentDescription("Legendas").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Selecionar legendas").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Personalizar legendas").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, subtitleSelections)
            assertEquals(1, appearanceOpens)
        }
    }
}
