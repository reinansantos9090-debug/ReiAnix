package com.reiflix.reiflix_local

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ReiAnixMainActivityLibraryHostInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun realMainActivityOpenLibraryCommandMountsTheComposeLibrary() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "reiflix://native?action=open_library" +
                    "&request_id=compose-host-runtime" +
                    "",
            ),
        )

        composeRule.activity.onNewIntent(intent)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Biblioteca", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Seu conteúdo local", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun realMainActivityBackDismissesOnlyTheComposeLibraryOverlay() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "reiflix://native?action=open_library" +
                    "&request_id=compose-host-runtime-back" +
                    "",
            ),
        )
        composeRule.activity.onNewIntent(intent)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Biblioteca", useUnmergedTree = true)
            .assertIsDisplayed()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Biblioteca", useUnmergedTree = true)
            .assertIsNotDisplayed()
    }
}
