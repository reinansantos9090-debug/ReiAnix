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
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixGenreUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaKind
import com.reianix.reianix_local.ui.model.ReiAnixMetadataAvailability
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaAvailability
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

    @Test
    fun collectionActionsAreHiddenUntilMenuOpens() {
        var renamed = 0
        var deleted = 0
        composeRule.setContent {
            ReiAnixComposeRoot {
                CollectionOptionsMenu("test", true, { renamed++ }, { deleted++ })
            }
        }
        composeRule.onNodeWithText("Renomear").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Opções da coleção").performClick()
        composeRule.onNodeWithText("Renomear").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Opções da coleção").performClick()
        composeRule.onNodeWithText("Excluir").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, renamed)
            assertEquals(1, deleted)
        }
    }

    @Test
    fun animeCardCountsOnlyLocalAvailableEpisodesAndKeepsSeparateActions() {
        fun episode(id: Long, availability: ReiAnixMediaAvailability) = ReiAnixEpisodeUiModel(
            id = id, animeId = 1L, seasonNumber = 1, number = id.toDouble(),
            title = null, fileName = "ep.mp4",
            media = ReiAnixLocalMediaUiModel(
                reference = "/local/ep.mp4", uri = null, path = "/local/ep.mp4",
                mediaIdentity = null, sourceAvailabilityState = null, availability = availability,
            ),
            progressSeconds = null, durationSeconds = null, watched = false,
            consumptionState = null, artwork = null,
        )
        val anime = ReiAnixAnimeUiModel(
            id = 1L, title = "Anime de teste", year = 2023,
            genres = listOf(ReiAnixGenreUiModel(null, "Ação"), ReiAnixGenreUiModel(null, "Fantasia")),
            favorite = false, mediaKind = ReiAnixMediaKind.SERIES, artwork = null,
            metadataAvailability = ReiAnixMetadataAvailability.AVAILABLE,
            seasons = emptyList(), specials = emptyList(),
            mediaFiles = listOf(episode(1L, ReiAnixMediaAvailability.AVAILABLE),
                episode(2L, ReiAnixMediaAvailability.MISSING)),
            announcedEpisodeCount = 12, score = 82.0,
        )
        val cover = mutableStateOf(false)
        var opened = 0
        var removed = 0
        composeRule.setContent {
            ReiAnixComposeRoot {
                CollectionAnimeRow(
                    animeId = anime.id, anime = anime, isCover = cover.value, busy = false,
                    onOpen = { opened++ }, onSetCover = { cover.value = true }, onRemove = { removed++ },
                )
            }
        }
        composeRule.onNodeWithText("1 episódio disponível", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("2023 • Ação • Fantasia", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("8,2/10", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Usar capa").performClick()
        composeRule.onNodeWithContentDescription("Capa da coleção").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Remover da coleção").performClick()
        composeRule.onNodeWithText("Anime de teste").performClick()
        composeRule.runOnIdle {
            assertEquals(true, cover.value)
            assertEquals(1, removed)
            assertEquals(1, opened)
        }
    }

}
