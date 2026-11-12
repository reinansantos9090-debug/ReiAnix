package com.reianix.reianix_local

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.reianix.reianix_local.ui.ReiAnixArtwork
import com.reianix.reianix_local.ui.ReiAnixCard
import com.reianix.reianix_local.ui.ReiAnixChip
import com.reianix.reianix_local.ui.ReiAnixComposeRoot
import com.reianix.reianix_local.ui.ReiAnixPrimaryButton
import com.reianix.reianix_local.ui.ReiAnixProgressIndicator
import com.reianix.reianix_local.ui.ReiAnixSecondaryButton
import com.reianix.reianix_local.ui.ReiAnixSectionTitle
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
