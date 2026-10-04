package com.reiflix.reiflix_local

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.reiflix.reiflix_local.ui.ReiAnixArtwork
import com.reiflix.reiflix_local.ui.ReiAnixArtworkMissingState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixFileUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixScannerInProgressState
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixCard
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixSectionTitle
import org.junit.Assert.assertEquals
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

    @Test
    fun reusableStateComponentsRenderAndRecoveryActionsExecute() {
        var actionCount = 0
        var retryCount = 0

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLoadingState(
                    title = "Carregando biblioteca",
                    message = "Lendo dados locais…",
                )
                ReiAnixEmptyLibraryState(
                    message = "A biblioteca está vazia.",
                    actionLabel = "Atualizar biblioteca",
                    onAction = { actionCount++ },
                )
                ReiAnixScannerInProgressState(
                    scanState = "SCANNING",
                    compact = true,
                )
                ReiAnixSourceUnavailableState(
                    title = "Fonte sem permissão",
                    message = "A fonte local não está autorizada.",
                    actionLabel = "Escolher fonte",
                    onAction = { actionCount++ },
                )
                ReiAnixFileUnavailableState(
                    message = "O arquivo local não está acessível.",
                    compact = true,
                )
                ReiAnixArtworkMissingState(label = "Sem capa")
                ReiAnixEmptyState(
                    title = "Nenhum resultado",
                    message = "Nada corresponde à busca.",
                )
                ReiAnixRecoverableErrorState(
                    title = "Erro recuperável",
                    message = "A operação falhou.",
                    onRetry = { retryCount++ },
                )
            }
        }

        composeRule.onNodeWithText("Carregando biblioteca").assertIsDisplayed()
        composeRule.onNodeWithText("A biblioteca está vazia.").assertIsDisplayed()
        composeRule.onNodeWithText("Varredura em andamento").assertIsDisplayed()
        composeRule.onNodeWithText("Fonte sem permissão").assertIsDisplayed()
        composeRule.onNodeWithText("Arquivo indisponível").assertIsDisplayed()
        composeRule.onNodeWithText("Sem capa").assertIsDisplayed()
        composeRule.onNodeWithText("Nenhum resultado").assertIsDisplayed()
        composeRule.onNodeWithText("Erro recuperável").assertIsDisplayed()

        composeRule.onNodeWithText("Escolher fonte").performClick()
        composeRule.onNodeWithText("Tentar novamente").performClick()

        assertEquals(1, actionCount)
        assertEquals(1, retryCount)
    }

}
