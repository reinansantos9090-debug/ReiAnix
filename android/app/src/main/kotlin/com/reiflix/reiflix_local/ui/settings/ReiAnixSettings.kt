package com.reiflix.reiflix_local.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

data class ReiAnixSettingsCategoryUiModel(
    val label: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    companion object {
        fun defaultCategories(): List<ReiAnixSettingsCategoryUiModel> = listOf(
            ReiAnixSettingsCategoryUiModel("Geral", "Comportamento geral do aplicativo", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Aparência", "Tema e apresentação", Icons.Filled.DarkMode),
            ReiAnixSettingsCategoryUiModel("Biblioteca", "Catálogo, grade e Continue Watching", Icons.Filled.VideoLibrary),
            ReiAnixSettingsCategoryUiModel("Player", "Reprodução, vídeo, controles e tela", Icons.Filled.PlayCircle),
            ReiAnixSettingsCategoryUiModel("Gestos", "Interações de toque do player", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Áudio e Legendas", "Idiomas, legendas e áudio", Icons.Filled.Headphones),
            ReiAnixSettingsCategoryUiModel("Metadata", "AniList e matching", Icons.Filled.Search),
            ReiAnixSettingsCategoryUiModel("Artwork", "Capas, thumbnails e cache", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Armazenamento", "Permissões, SAF, MediaStore e volumes", Icons.Filled.Storage),
            ReiAnixSettingsCategoryUiModel("Dados e Cache", "Configurações, importação, exportação e cache", Icons.Filled.Cached),
            ReiAnixSettingsCategoryUiModel("Backup e Restauração", "Backup, restauração, integridade e reconciliação", Icons.Filled.Security),
            ReiAnixSettingsCategoryUiModel("Privacidade", "Dados locais e conectividade", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Varredura", "Estado e histórico das varreduras", Icons.Filled.Refresh),
            ReiAnixSettingsCategoryUiModel("Diagnóstico", "Informações técnicas e diagnóstico", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Sobre", "Versão e componentes do ReiAnix", Icons.Filled.Info),
        )
    }
}

@Composable
fun ReiAnixSettingsRoute(
    viewModel: ReiAnixSettingsViewModel,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReiAnixSettingsScreen(
        state = state,
        onBack = onBack,
        onOpenCategory = onOpenCategory,
    )
}

@Composable
fun ReiAnixSettingsScreen(
    state: ReiAnixSettingsUiState,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ReiAnixTokens.Colors.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Voltar das configurações"
                        },
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Configurações",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = ReiAnixTokens.Colors.text,
                        )
                        Text(
                            text = "Preferências do ReiAnix",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReiAnixTokens.Colors.textMuted,
                        )
                    }
                }
            }

            if (state.status == com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.LOADING) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = ReiAnixTokens.Spacing.xxxl),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (state.status == com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.ERROR) {
                item(key = "error") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Não foi possível carregar as configurações." +
                                state.error?.let { "\n$it" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ReiAnixTokens.Colors.textMuted,
                            modifier = Modifier.padding(ReiAnixTokens.Spacing.lg),
                        )
                    }
                }
            }

            if (state.account.integrationAvailable) {
                item(key = "account") {
                    ReiAnixSettingsAccountCard(
                        state = state.account,
                        onClick = { onOpenCategory("Conta") },
                    )
                }
            }

            items(
                items = state.categories,
                key = { it.label },
            ) { category ->
                ReiAnixSettingsCategoryCard(
                    category = category,
                    valueSummary = categorySummary(category.label, state.settings),
                    onClick = { onOpenCategory(category.label) },
                )
            }
        }
    }
}

@Composable
private fun ReiAnixSettingsAccountCard(
    state: com.reiflix.reiflix_local.ui.model.ReiAnixSettingsAccountUiState,
    onClick: () -> Unit,
) {
    val primary = state.name.ifBlank { state.email.ifBlank { "Não conectado" } }
    val secondary = when {
        state.connected && state.email.isNotBlank() && state.name.isNotBlank() -> state.email
        state.connected -> "Conta conectada"
        state.state == "configuration_required" -> "Configuração necessária"
        state.state == "connecting" -> "Conectando…"
        else -> "Não conectado"
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Abrir configurações da conta" },
    ) {
        SettingsCardRow(
            icon = Icons.Filled.AccountCircle,
            title = primary,
            description = secondary,
        )
    }
}

@Composable
private fun ReiAnixSettingsCategoryCard(
    category: ReiAnixSettingsCategoryUiModel,
    valueSummary: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Abrir " + category.label },
    ) {
        SettingsCardRow(
            icon = category.icon,
            title = category.label,
            description = if (valueSummary.isBlank()) category.description else {
                category.description + " • " + valueSummary
            },
        )
    }
}

@Composable
private fun SettingsCardRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ReiAnixTokens.Spacing.lg,
                vertical = ReiAnixTokens.Spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = ReiAnixTokens.Colors.text)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ReiAnixTokens.Colors.text,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = ReiAnixTokens.Colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = ReiAnixTokens.Colors.textMuted,
        )
    }
}

private fun categorySummary(label: String, settings: Map<String, String>): String =
    when (label) {
        "Geral" -> if (settings["app.confirm_destructive"].toBoolean()) {
            "Confirmações ativadas"
        } else {
            "Confirmações desativadas"
        }
        "Aparência" -> when (settings["appearance.theme"]) {
            "system" -> "Sistema"
            "light" -> "Claro"
            "dark" -> "Escuro"
            else -> ""
        }
        "Biblioteca" -> settings["library.page_size"]?.takeIf { it.isNotBlank() }?.let {
            "$it itens/página"
        }.orEmpty()
        "Player" -> listOfNotNull(
            if (settings["player.autoplay_next"].toBoolean()) "Autoplay" else null,
            if (settings["player.resume"].toBoolean()) "Retomar" else null,
            settings["player.default_speed"]?.takeIf { it.isNotBlank() }?.let { "$it×" },
        ).joinToString(" • ")
        "Gestos" -> if (
            settings["gestures.volume"].toBoolean() ||
            settings["gestures.brightness"].toBoolean() ||
            settings["gestures.double_tap"].toBoolean() ||
            settings["gestures.long_press"].toBoolean()
        ) "Ativados" else "Desativados"
        "Áudio e Legendas" -> settings["audio.preferred_language"]
            ?.takeIf { it.isNotBlank() }
            ?: "Padrão"
        "Metadata" -> if (settings["metadata.anilist_enabled"].toBoolean()) "AniList ativo" else "AniList desativado"
        "Artwork" -> if (settings["artwork.enabled"].toBoolean()) {
            "Artwork remoto ativo"
        } else {
            "Artwork remoto desativado"
        }
        else -> ""
    }
}
