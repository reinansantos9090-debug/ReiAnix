package com.reiflix.reiflix_local

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import org.junit.Rule
import org.junit.Test

class ComposeFoundationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeFoundationRendersWithoutDomainUi() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                Text("ReiAnix Compose foundation")
            }
        }

        composeRule.onNodeWithText("ReiAnix Compose foundation").assertIsDisplayed()
    }
}
