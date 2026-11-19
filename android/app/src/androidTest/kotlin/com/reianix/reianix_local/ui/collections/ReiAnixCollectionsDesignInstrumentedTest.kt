package com.reianix.reianix_local.ui.collections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reianix.reianix_local.ui.ReiAnixComposeRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReiAnixCollectionsDesignInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun actionCardsKeepSeparateCallbacks() {
        var created = 0
        var folders = 0
        composeRule.setContent {
            ReiAnixComposeRoot {
                Box(Modifier.fillMaxSize()) {
                    CollectionsPrimaryActions(
                        enabled = true,
                        onCreate = { created++ },
                        onAddFolder = { folders++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Nova coleção").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Adicionar pasta").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, created)
            assertEquals(1, folders)
        }
    }

    @Test
    fun fullscreenNameEditorValidatesNameAndKeepsBackAction() {
        val name = mutableStateOf("")
        var saved = ""
        var backed = 0
        composeRule.setContent {
            ReiAnixComposeRoot {
                CollectionNameScreen(
                    name = name.value,
                    onNameChange = { name.value = it },
                    editing = false,
                    busy = false,
                    error = null,
                    onBack = { backed++ },
                    onSave = { saved = name.value },
                )
            }
        }
        composeRule.onNodeWithText("Crie sua coleção").assertIsDisplayed()
        composeRule.onNodeWithText("Criar coleção").assertIsNotEnabled()
        composeRule.onNodeWithText("Nome da coleção").performTextInput("Obras-primas")
        composeRule.onNodeWithText("Criar coleção").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Voltar").performClick()
        composeRule.runOnIdle {
            assertEquals("Obras-primas", saved)
            assertEquals(1, backed)
        }
        composeRule.onNodeWithText("Cancelar").assertDoesNotExist()
    }

    @Test
    fun renameUsesSameScreenAndShowsStorageError() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                CollectionNameScreen(
                    name = "Minha coleção",
                    onNameChange = {},
                    editing = true,
                    busy = false,
                    error = "Já existe uma coleção com esse nome.",
                    onBack = {},
                    onSave = {},
                )
            }
        }
        composeRule.onNodeWithText("Renomear coleção").assertIsDisplayed()
        composeRule.onNodeWithText("Já existe uma coleção com esse nome.").assertIsDisplayed()
        composeRule.onNodeWithText("Salvar nome").assertIsEnabled()
    }
}
