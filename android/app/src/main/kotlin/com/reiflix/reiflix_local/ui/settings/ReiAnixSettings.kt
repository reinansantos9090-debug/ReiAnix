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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
            ReiAnixSettingsCategoryUiModel("Aparência", "Tema e apresentação", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Biblioteca", "Catálogo, grade e Continue Watching", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Player", "Reprodução, vídeo, controles e tela", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Gestos", "Interações de toque do player", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Áudio e Legendas", "Idiomas, legendas e áudio", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Metadata", "AniList e matching", Icons.Filled.Search),
            ReiAnixSettingsCategoryUiModel("Artwork", "Capas, thumbnails e cache", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Armazenamento", "Permissões, SAF, MediaStore e volumes", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Dados e Cache", "Configurações, importação, exportação e cache", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Backup e Restauração", "Backup, restauração, integridade e reconciliação", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Privacidade", "Dados locais e conectividade", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Varredura", "Estado e histórico das varreduras", Icons.Filled.Refresh),
            ReiAnixSettingsCategoryUiModel("Diagnóstico", "Informações técnicas e diagnóstico", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Sobre", "Versão e componentes do ReiAnix", Icons.Filled.Info),
        )
    }
}


private val NativeManagedSettingsCategories = setOf("Geral", "Aparência")

private data class SettingChoice(
    val value: String,
    val label: String,
)

private val themeChoices = listOf(
    SettingChoice("system", "Sistema"),
    SettingChoice("light", "Claro"),
    SettingChoice("dark", "Escuro"),
)

private val cardSizeChoices = listOf(
    SettingChoice("small", "Pequeno"),
    SettingChoice("medium", "Médio"),
    SettingChoice("large", "Grande"),
)

@Composable
fun ReiAnixSettingsRoute(
    viewModel: ReiAnixSettingsViewModel,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedCategory by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    if (selectedCategory != null) {
        ReiAnixComposeSettingsCategoryScreen(
            category = selectedCategory!!,
            state = state,
            onBack = { selectedCategory = null },
            onUpdateSetting = viewModel::setSetting,
        )
    } else {
        ReiAnixSettingsScreen(
            state = state,
            onBack = onBack,
            onOpenCategory = { label ->
                if (label in NativeManagedSettingsCategories) {
                    selectedCategory = label
                } else {
                    onOpenCategory(label)
                }
            },
        )
    }
}

@Composable
fun ReiAnixSettingsScreen(
    state: ReiAnixSettingsUiState,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
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
                SettingsHeader(
                    title = "Configurações",
                    subtitle = "Preferências do ReiAnix",
                    onBack = onBack,
                )
            }
            if (state.status == com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.LOADING) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = ReiAnixTokens.Spacing.xxxl),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (state.status == com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.ERROR) {
                item(key = "error") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Não foi possível carregar as configurações." +
                                state.error?.let { "
$it" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                key = { "category:" + it.label },
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
private fun ReiAnixComposeSettingsCategoryScreen(
    category: String,
    state: ReiAnixSettingsUiState,
    onBack: () -> Unit,
    onUpdateSetting: (String, String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            item(key = "header:" + category) {
                SettingsHeader(
                    title = category,
                    subtitle = when (category) {
                        "Geral" -> "Comportamento geral do aplicativo"
                        "Aparência" -> "Tema e apresentação"
                        else -> "Preferências"
                    },
                    onBack = onBack,
                )
            }
            when (category) {
                "Geral" -> {
                    item(key = "setting:app.confirm_destructive") {
                        BooleanSettingCard(
                            keyName = "app.confirm_destructive",
                            title = "Confirmar ações destrutivas",
                            description = "Pede confirmação antes de ações como limpar cache e restaurar configurações.",
                            checked = state.settings["app.confirm_destructive"] == "true",
                            onCheckedChange = { onUpdateSetting("app.confirm_destructive", it.toString()) },
                        )
                    }
                }
                "Aparência" -> {
                    item(key = "setting:appearance.theme") {
                        ChoiceSettingCard(
                            keyName = "appearance.theme",
                            title = "Tema",
                            description = "Aplica o tema da interface Compose imediatamente, sem reiniciar a Activity.",
                            selectedValue = state.settings["appearance.theme"],
                            choices = themeChoices,
                            onSelected = { onUpdateSetting("appearance.theme", it) },
                        )
                    }
                    item(key = "setting:appearance.card_size") {
                        ChoiceSettingCard(
                            keyName = "appearance.card_size",
                            title = "Tamanho dos cards",
                            description = "Controla o tamanho visual dos cards da biblioteca/Home.",
                            selectedValue = state.settings["appearance.card_size"],
                            choices = cardSizeChoices,
                            onSelected = { onUpdateSetting("appearance.card_size", it) },
                        )
                    }
                    item(key = "setting:appearance.show_thumbnails") {
                        BooleanSettingCard(
                            keyName = "appearance.show_thumbnails",
                            title = "Mostrar miniaturas",
                            description = "Quando desativado, a Home mantém o espaço do card, mas não carrega imagens.",
                            checked = state.settings["appearance.show_thumbnails"] == "true",
                            onCheckedChange = { onUpdateSetting("appearance.show_thumbnails", it.toString()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
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
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BooleanSettingCard(
    keyName: String,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Configuração " + keyName },
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun ChoiceSettingCard(
    keyName: String,
    title: String,
    description: String,
    selectedValue: String?,
    choices: List<SettingChoice>,
    onSelected: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Configuração " + keyName },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            choices.forEach { choice ->
                val selected = selectedValue == choice.value
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = title + ": " + choice.label
                        }
                        .clickable(
                            onClick = { onSelected(choice.value) },
                        )
                        .padding(vertical = ReiAnixTokens.Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelected(choice.value) },
                    )
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Abrir configurações da conta" },
    ) {
        SettingsCardRow(
            title = primary,
            description = secondary,
            icon = Icons.Filled.AccountCircle,
            trailingArrow = true,
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Abrir " + category.label },
    ) {
        SettingsCardRow(
            title = category.label,
            description = if (valueSummary.isBlank()) category.description else {
                category.description + " • " + valueSummary
            },
            icon = category.icon,
            trailingArrow = true,
        )
    }
}

@Composable
private fun SettingsCardRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailingArrow: Boolean,
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingArrow) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun categorySummary(label: String, settings: Map<String, String>): String =
    when (label) {
        "Geral" -> if (settings["app.confirm_destructive"] == "true") {
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
            if (settings["player.autoplay_next"] == "true") "Autoplay" else null,
            if (settings["player.resume"] == "true") "Retomar" else null,
            settings["player.default_speed"]?.takeIf { it.isNotBlank() }?.let { "$it×" },
        ).joinToString(" • ")
        "Gestos" -> if (
            settings["gestures.volume"] == "true" ||
            settings["gestures.brightness"] == "true" ||
            settings["gestures.double_tap"] == "true" ||
            settings["gestures.long_press"] == "true"
        ) "Ativados" else "Desativados"
        "Áudio e Legendas" -> settings["audio.preferred_language"]
            ?.takeIf { it.isNotBlank() }
            ?: "Padrão"
        "Metadata" -> if (settings["metadata.anilist_enabled"] == "true") "AniList ativo" else "AniList desativado"
        "Artwork" -> if (settings["artwork.enabled"] == "true") {
            "Artwork remoto ativo"
        } else {
            "Artwork remoto desativado"
        }
        else -> ""
    }
