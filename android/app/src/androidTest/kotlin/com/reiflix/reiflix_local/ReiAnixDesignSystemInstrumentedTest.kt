package com.reiflix.reiflix_local

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.reiflix.reiflix_local.ui.ReiAnixArtwork
import com.reiflix.reiflix_local.ui.ReiAnixCard
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixSectionTitle
import org.junit.Rule
import org.junit.Test

class ReiAnixDesignSystemInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reusableDesignSystemComponentsRender() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixCard {
                    Text("Card content")
                }
                ReiAnixSectionTitle(
                    title = "Section title",
                    subtitle = "Section subtitle",
                )
                ReiAnixPrimaryButton(
                    text = "Primary",
                    onClick = {},
                )
                ReiAnixSecondaryButton(
                    text = "Secondary",
                    onClick = {},
                )
                ReiAnixChip(
                    text = "Chip",
                    onClick = {},
                )
                ReiAnixArtwork(
                    painter = null,
                    contentDescription = "Artwork",
                )
                ReiAnixProgressIndicator(
                    progress = 0.5f,
                )
            }
        }

        composeRule.onNodeWithText("Card content").assertIsDisplayed()
        composeRule.onNodeWithText("Section title").assertIsDisplayed()
        composeRule.onNodeWithText("Primary").assertIsDisplayed()
        composeRule.onNodeWithText("Secondary").assertIsDisplayed()
        composeRule.onNodeWithText("Chip").assertIsDisplayed()
        composeRule.onNodeWithText("Sem arte").assertIsDisplayed()
        composeRule.onRoot().assertExists()
    }
}
