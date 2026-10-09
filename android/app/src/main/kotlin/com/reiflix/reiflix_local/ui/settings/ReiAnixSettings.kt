package com.reiflix.reiflix_local.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.BuildConfig
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixTextField
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsOperationState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.widthIn
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot

data class ReiAnixSettingsCategoryUiModel(
    val label: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    companion object {
        fun defaultCategories(): List<ReiAnixSettingsCategoryUiModel> = listOf(
            ReiAnixSettingsCategoryUiModel("Conta", "Conta Google e sessão", Icons.Filled.AccountCircle),
            ReiAnixSettingsCategoryUiModel("Geral", "Preferências gerais do aplicativo", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Aparência", "Tema, cores e densidade visual", Icons.Filled.Palette),
            ReiAnixSettingsCategoryUiModel("Biblioteca", "Organização, ordenação e progresso", Icons.Filled.LibraryBooks),
            ReiAnixSettingsCategoryUiModel("Player", "Reprodução, vídeo e controles", Icons.Filled.PlayArrow),
            ReiAnixSettingsCategoryUiModel("Gestos", "Toque, swipe, seek e zoom", Icons.Filled.TouchApp),
            ReiAnixSettingsCategoryUiModel("Áudio e Legendas", "Idiomas, faixas e legendas", Icons.Filled.Headphones),
            ReiAnixSettingsCategoryUiModel("Metadata", "AniList e correspondência", Icons.Filled.ManageSearch),
            ReiAnixSettingsCategoryUiModel("Artwork", "Capas, thumbnails e cache", Icons.Filled.Image),
            ReiAnixSettingsCategoryUiModel("Armazenamento", "SAF, MediaStore, permissões e volumes", Icons.Filled.Storage),
            ReiAnixSettingsCategoryUiModel("Dados e Cache", "Configurações e dados temporários", Icons.Filled.Cached),
            ReiAnixSettingsCategoryUiModel("Backup e Restauração", "Backup, restore e integridade", Icons.Filled.Backup),
            ReiAnixSettingsCategoryUiModel("Privacidade", "Dados locais e conectividade", Icons.Filled.PrivacyTip),
            ReiAnixSettingsCategoryUiModel("Varredura", "Estado e histórico do scanner", Icons.Filled.Refresh),
            ReiAnixSettingsCategoryUiModel("Diagnóstico", "Informações técnicas e diagnóstico", Icons.Filled.BugReport),
            ReiAnixSettingsCategoryUiModel("Sobre", "Versão e informações do ReiAnix", Icons.Filled.Info),
        )
    }
}


private val NativeManagedSettingsCategories = setOf(
    "Conta",
    "Geral",
    "Aparência",
    "Biblioteca",
    "Player",
    "Gestos",
    "Áudio e Legendas",
    "Metadata",
    "Artwork",
    "Armazenamento",
    "Dados e Cache",
    "Backup e Restauração",
    "Privacidade",
    "Varredura",
    "Diagnóstico",
    "Sobre",
)

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

private val defaultSpeedChoices = listOf(
    SettingChoice("0.5", "0,50x"),
    SettingChoice("0.75", "0,75x"),
    SettingChoice("1.0", "1,00x"),
    SettingChoice("1.25", "1,25x"),
    SettingChoice("1.5", "1,50x"),
    SettingChoice("1.75", "1,75x"),
    SettingChoice("2.0", "2,00x"),
)

private val aspectRatioChoices = listOf(
    SettingChoice("fit", "Ajustar"),
    SettingChoice("fill", "Preencher"),
)

private val immersiveChoices = listOf(
    SettingChoice("always", "Sempre"),
    SettingChoice("landscape", "Somente landscape"),
    SettingChoice("never", "Nunca"),
)

private val rotationChoices = listOf(
    SettingChoice("auto", "Automática"),
    SettingChoice("portrait", "Portrait"),
    SettingChoice("landscape", "Landscape"),
)

private val autoHideChoices = listOf(
    SettingChoice("5", "5s"),
    SettingChoice("10", "10s"),
    SettingChoice("15", "15s"),
    SettingChoice("30", "30s"),
    SettingChoice("0", "Nunca"),
)

private val doubleTapSeekChoices = listOf(
    SettingChoice("5", "5s"),
    SettingChoice("10", "10s"),
    SettingChoice("15", "15s"),
    SettingChoice("30", "30s"),
)

private val longPressSpeedChoices = listOf(
    SettingChoice("1.5", "1,50x"),
    SettingChoice("1.75", "1,75x"),
    SettingChoice("2.0", "2,00x"),
)

private val maxVideoResolutionChoices = listOf(
    SettingChoice("auto", "Automática"),
    SettingChoice("480p", "480p"),
    SettingChoice("720p", "720p"),
    SettingChoice("1080p", "1080p"),
    SettingChoice("1440p", "1440p"),
    SettingChoice("2160p", "2160p"),
)

private val maxVideoFrameRateChoices = listOf(
    SettingChoice("0", "Automático"),
    SettingChoice("24", "24 fps"),
    SettingChoice("30", "30 fps"),
    SettingChoice("60", "60 fps"),
)

private val maxAudioChannelsChoices = listOf(
    SettingChoice("0", "Automático"),
    SettingChoice("2", "2 canais"),
    SettingChoice("6", "5.1 / 6"),
    SettingChoice("8", "7.1 / 8"),
)

private val subtitleScaleChoices = listOf(
    SettingChoice("0.75", "75%"),
    SettingChoice("1.0", "100%"),
    SettingChoice("1.25", "125%"),
    SettingChoice("1.5", "150%"),
)

private val subtitlePaddingChoices = listOf(
    SettingChoice("4", "4%"),
    SettingChoice("8", "8% (padrão)"),
    SettingChoice("12", "12%"),
    SettingChoice("16", "16%"),
)

private val subtitleModeChoices = listOf(
    SettingChoice("auto", "Automático"),
    SettingChoice("always", "Sempre"),
    SettingChoice("never", "Nunca"),
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
    val rootListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    val activeCategory = selectedCategory
    if (activeCategory != null) {
        ReiAnixComposeSettingsCategoryScreen(
            category = activeCategory,
            state = state,
            onBack = { selectedCategory = null },
            onUpdateSetting = viewModel::setSetting,
            onAccountAction = viewModel::requestAccountAction,
            onAction = viewModel::requestAction,
            onRetry = viewModel::refresh,
        )
    } else {
        ReiAnixSettingsScreen(
            state = state,
            listState = rootListState,
            onBack = onBack,
            onOpenCategory = { label ->
                if (label in NativeManagedSettingsCategories) {
                    selectedCategory = label
                } else {
                    onOpenCategory(label)
                }
            },
            onRetry = viewModel::refresh,
        )
    }
}

@Composable
fun ReiAnixSettingsScreen(
    state: ReiAnixSettingsUiState,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onRetry: () -> Unit,
) {
    ReiAnixResponsiveRoot {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(
                    start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    top = ReiAnixTokens.Spacing.sm,
                    bottom = ReiAnixTokens.Spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.none),
            ) {
                when (state.status) {
                    com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.LOADING -> {
                        item(key = "settings:loading") {
                            ReiAnixLoadingState(
                                title = "Carregando configurações",
                                message = "Lendo as preferências locais…",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.ERROR -> {
                        item(key = "settings:error") {
                            ReiAnixRecoverableErrorState(
                                title = "Não foi possível carregar as configurações",
                                message = state.error
                                    ?: "As configurações locais retornaram um erro.",
                                onRetry = onRetry,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.READY -> {
                        item(key = "settings:account-profile") {
                            SettingsProfileRow(
                                state = state,
                                onClick = {
                                    if (state.categories.any { it.label == "Conta" }) {
                                        onOpenCategory("Conta")
                                    }
                                },
                            )
                        }

                        items(
                            items = state.categories.filter { it.label != "Conta" },
                            key = { category -> "settings-flat:${category.label}" },
                        ) { category ->
                            SettingsFlatCategoryRow(
                                category = category,
                                onClick = { onOpenCategory(category.label) },
                            )
                        }

                        item(key = "settings:footer") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = ReiAnixTokens.Spacing.lg,
                                        bottom = ReiAnixTokens.Spacing.md,
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "ReiAnix",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsProfileRow(
    state: ReiAnixSettingsUiState,
    onClick: () -> Unit,
) {
    val identity = state.account.name.trim().ifBlank { state.account.email.trim() }
    val title = identity.ifBlank { "ReiAnix" }
    val subtitle = when {
        state.account.connected -> state.account.email.trim().takeIf { it.isNotBlank() } ?: "Conta conectada"
        state.account.integrationAvailable -> "Conta Google não conectada"
        else -> "Conta local"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)
            .clickable(
                enabled = state.categories.any { it.label == "Conta" },
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Conta. ${title}. ${subtitle}"
            }
            .padding(
                horizontal = ReiAnixTokens.Spacing.xs,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        Box(
            modifier = Modifier.size(ReiAnixTokens.Dimensions.settingsIconContainerSize),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            Text(
                text = title,
                style = ReiAnixTokens.TypographyTokens.settingsCategory,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = ReiAnixTokens.TypographyTokens.settingsDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ReiAnixTokens.Dimensions.iconSmall),
        )
    }
}

@Composable
private fun SettingsFlatCategoryRow(
    category: ReiAnixSettingsCategoryUiModel,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)
                .clickable(
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = if (category.description.isBlank()) {
                        category.label
                    } else {
                        category.label + ". " + category.description
                    }
                }
                .padding(
                    horizontal = ReiAnixTokens.Spacing.xs,
                    vertical = ReiAnixTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Box(
                modifier = Modifier.size(ReiAnixTokens.Dimensions.settingsIconContainerSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = category.label,
                    style = ReiAnixTokens.TypographyTokens.settingsCategory,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = category.description,
                    style = ReiAnixTokens.TypographyTokens.settingsDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconSmall),
            )
        }
        HorizontalDivider(
            thickness = ReiAnixTokens.Dimensions.dividerHeight,
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = ReiAnixTokens.Colors.dividerAlpha,
            ),
        )
    }
}

@Composable
private fun ReiAnixComposeSettingsCategoryScreen(
    category: String,
    state: ReiAnixSettingsUiState,
    onBack: () -> Unit,
    onUpdateSetting: (String, String) -> Unit,
    onAccountAction: (String) -> Unit,
    onAction: (String) -> Unit,
    onRetry: () -> Unit,
) {
    fun actionBusy(action: String): Boolean =
        state.operations.values.any {
            it.action == action &&
                it.state in setOf(
                    ReiAnixSettingsOperationState.QUEUED,
                    ReiAnixSettingsOperationState.RUNNING,
                )
        }

    val latestOperationError = state.operations.values.lastOrNull()
        ?.takeIf { it.state == ReiAnixSettingsOperationState.ERROR }

    var pendingConfirmationAction by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    fun requestDestructiveAction(action: String) {
        if (actionBusy(action)) return
        if (state.settings["app.confirm_destructive"] == "true") {
            pendingConfirmationAction = action
        } else {
            onAction(action)
        }
    }

    ReiAnixResponsiveRoot {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        pendingConfirmationAction?.let { action ->
            val (title, message, confirmLabel) = when (action) {
                "clear_anilist_cache" -> Triple(
                    "Limpar cache de artwork?",
                    "A biblioteca, progresso, favoritos, notas, pins e arquivos não serão apagados.",
                    "Limpar",
                )
                "reset_all_settings" -> Triple(
                    "Restaurar todas as configurações?",
                    "Somente as preferências do ReiAnix serão restauradas. Biblioteca, consumo, metadata manual, artwork, arquivos e permissões permanecem intactos.",
                    "Restaurar",
                )
                "backup_restore" -> Triple(
                    "Restaurar backup?",
                    "O BackupService existente validará o arquivo antes de alterar o estado lógico local.",
                    "Continuar",
                )
                else -> Triple(
                    "Confirmar ação",
                    "Confirme para continuar.",
                    "Confirmar",
                )
            }
            AlertDialog(
                onDismissRequest = { pendingConfirmationAction = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingConfirmationAction = null
                            onAction(action)
                        },
                        enabled = !actionBusy(action),
                    ) {
                        Text(confirmLabel)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmationAction = null }) {
                        Text("Cancelar")
                    }
                },
            )
        }

        LazyColumn(
            state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
            modifier = Modifier
                .widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                vertical = ReiAnixTokens.Spacing.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            item(key = "header:" + category) {
                SettingsHeader(
                    title = category,
                    subtitle = settingsCategoryDescription(category),
                    onBack = onBack,
                )
            }
            item(key = "operation-error") {
                latestOperationError?.let { operation ->
                    ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(ReiAnixTokens.Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                        ) {
                            Text(
                                text = operation.error ?: "A operação de configurações não pôde ser concluída.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            operation.message?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (state.status == com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.LOADING) {
                item(key = "category-loading") {
                    ReiAnixLoadingState(
                        title = "Carregando configurações",
                        message = "Lendo as preferências locais…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (state.status == com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.ERROR) {
                item(key = "category-error") {
                    ReiAnixRecoverableErrorState(
                        title = "Não foi possível carregar as configurações",
                        message = state.error ?: "As configurações locais retornaram um erro.",
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                when (category) {
                "Conta" -> {
                    item(key = "account:profile") {
                        ReiAnixSettingsAccountContent(
                            state = state.account,
                            onAction = onAccountAction,
                        )
                    }
                }

                "Geral" -> {
                    item(key = "section:general:behavior") { SettingsSectionLabel("COMPORTAMENTO") }
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
                    item(key = "section:appearance:visual") { SettingsSectionLabel("VISUAL") }
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

                "Biblioteca" -> {
                    item(key = "section:library:organization") { SettingsSectionLabel("ORGANIZAÇÃO") }
                    item(key = "setting:library.sort_default") {
                        ChoiceSettingCard(
                            keyName = "library.sort_default",
                            title = "Ordenação padrão",
                            description = "Define a ordenação inicial quando outra ordenação não foi salva.",
                            selectedValue = state.settings["library.sort_default"],
                            choices = listOf(
                                SettingChoice("added_desc", "Mais recentes"),
                                SettingChoice("title_asc", "Nome A–Z"),
                                SettingChoice("title_desc", "Nome Z–A"),
                                SettingChoice("recently_watched", "Assistidos recentemente"),
                            ),
                            onSelected = { onUpdateSetting("library.sort_default", it) },
                        )
                    }
                    item(key = "setting:library.grid_density") {
                        ChoiceSettingCard(
                            keyName = "library.grid_density",
                            title = "Densidade da grade",
                            description = "Controla a largura efetiva dos cards.",
                            selectedValue = state.settings["library.grid_density"],
                            choices = listOf(
                                SettingChoice("small", "Menos cards"),
                                SettingChoice("medium", "Equilibrada"),
                                SettingChoice("large", "Mais cards"),
                            ),
                            onSelected = { onUpdateSetting("library.grid_density", it) },
                        )
                    }
                    item(key = "setting:library.page_size") {
                        ChoiceSettingCard(
                            keyName = "library.page_size",
                            title = "Itens por página",
                            description = "Quantidade persistida de itens da paginação.",
                            selectedValue = state.settings["library.page_size"],
                            choices = listOf(
                                SettingChoice("24", "24"),
                                SettingChoice("36", "36"),
                                SettingChoice("48", "48"),
                                SettingChoice("72", "72"),
                            ),
                            onSelected = { onUpdateSetting("library.page_size", it) },
                        )
                    }
                    item(key = "setting:library.continue_watching") {
                        BooleanSettingCard(
                            keyName = "library.continue_watching",
                            title = "Continue Watching",
                            description = "Controla a preferência global dessa seção.",
                            checked = state.settings["library.continue_watching"] == "true",
                            onCheckedChange = { onUpdateSetting("library.continue_watching", it.toString()) },
                        )
                    }
                    item(key = "setting:library.continue_watching_limit") {
                        ChoiceSettingCard(
                            keyName = "library.continue_watching_limit",
                            title = "Limite de Continue Watching",
                            description = "Quantidade persistida de itens na seção.",
                            selectedValue = state.settings["library.continue_watching_limit"],
                            choices = listOf(
                                SettingChoice("5", "5"),
                                SettingChoice("10", "10"),
                                SettingChoice("15", "15"),
                                SettingChoice("20", "20"),
                            ),
                            onSelected = { onUpdateSetting("library.continue_watching_limit", it) },
                        )
                    }
                }

                "Player" -> {
                    item(key = "section:player:playback") { SettingsSectionLabel("REPRODUÇÃO") }
                    item(key = "setting:player.autoplay_next") {
                        BooleanSettingCard(
                            keyName = "player.autoplay_next",
                            title = "Autoplay do próximo episódio",
                            description = "Permite o avanço automático no player local.",
                            checked = state.settings["player.autoplay_next"] == "true",
                            onCheckedChange = { onUpdateSetting("player.autoplay_next", it.toString()) },
                        )
                    }
                    item(key = "setting:player.resume") {
                        BooleanSettingCard(
                            keyName = "player.resume",
                            title = "Continuar reprodução",
                            description = "Usa a posição de progresso já salva; desligar não apaga o progresso.",
                            checked = state.settings["player.resume"] == "true",
                            onCheckedChange = { onUpdateSetting("player.resume", it.toString()) },
                        )
                    }
                    item(key = "section:player:video") { SettingsSectionLabel("VÍDEO") }
                    item(key = "setting:player.default_speed") {
                        ChoiceSettingCard(
                            keyName = "player.default_speed",
                            title = "Velocidade padrão",
                            description = "Aplicada quando um episódio é aberto.",
                            selectedValue = state.settings["player.default_speed"],
                            choices = defaultSpeedChoices,
                            onSelected = { onUpdateSetting("player.default_speed", it) },
                        )
                    }
                    item(key = "setting:player.aspect_ratio") {
                        ChoiceSettingCard(
                            keyName = "player.aspect_ratio",
                            title = "Modo de vídeo",
                            description = "Ajustar preserva toda a imagem; Preencher ocupa a tela cortando somente o excedente.",
                            selectedValue = state.settings["player.aspect_ratio"],
                            choices = aspectRatioChoices,
                            onSelected = { onUpdateSetting("player.aspect_ratio", it) },
                        )
                    }
                    item(key = "section:player:controls") { SettingsSectionLabel("CONTROLES") }
                    item(key = "setting:player.zoom_enabled") {
                        BooleanSettingCard(
                            keyName = "player.zoom_enabled",
                            title = "Zoom por gesto",
                            description = "Permite ampliar e mover o vídeo com gesto de pinça.",
                            checked = state.settings["player.zoom_enabled"] == "true",
                            onCheckedChange = { onUpdateSetting("player.zoom_enabled", it.toString()) },
                        )
                    }
                    item(key = "setting:player.lock_mode") {
                        BooleanSettingCard(
                            keyName = "player.lock_mode",
                            title = "Bloqueio dos controles",
                            description = "Define o estado persistido de bloqueio dos controles ao abrir o player.",
                            checked = state.settings["player.lock_mode"] == "true",
                            onCheckedChange = { onUpdateSetting("player.lock_mode", it.toString()) },
                        )
                    }
                    item(key = "setting:player.double_tap_seek_seconds") {
                        ChoiceSettingCard(
                            keyName = "player.double_tap_seek_seconds",
                            title = "Salto no double tap",
                            description = "Define quantos segundos são avançados ou retrocedidos pelo double tap.",
                            selectedValue = state.settings["player.double_tap_seek_seconds"],
                            choices = doubleTapSeekChoices,
                            onSelected = { onUpdateSetting("player.double_tap_seek_seconds", it) },
                        )
                    }
                    item(key = "setting:player.long_press_speed") {
                        ChoiceSettingCard(
                            keyName = "player.long_press_speed",
                            title = "Velocidade da pressão longa",
                            description = "Velocidade temporária aplicada enquanto a pressão longa estiver ativa.",
                            selectedValue = state.settings["player.long_press_speed"],
                            choices = longPressSpeedChoices,
                            onSelected = { onUpdateSetting("player.long_press_speed", it) },
                        )
                    }
                    item(key = "setting:player.max_video_resolution") {
                        ChoiceSettingCard(
                            keyName = "player.max_video_resolution",
                            title = "Resolução máxima",
                            description = "Limita a faixa de vídeo selecionada pelo Media3 quando o arquivo oferece múltiplas tracks.",
                            selectedValue = state.settings["player.max_video_resolution"],
                            choices = maxVideoResolutionChoices,
                            onSelected = { onUpdateSetting("player.max_video_resolution", it) },
                        )
                    }
                    item(key = "setting:player.max_video_frame_rate") {
                        ChoiceSettingCard(
                            keyName = "player.max_video_frame_rate",
                            title = "FPS máximo",
                            description = "Limita a taxa de frames da track de vídeo selecionada.",
                            selectedValue = state.settings["player.max_video_frame_rate"],
                            choices = maxVideoFrameRateChoices,
                            onSelected = { onUpdateSetting("player.max_video_frame_rate", it) },
                        )
                    }
                    item(key = "setting:player.max_audio_channels") {
                        ChoiceSettingCard(
                            keyName = "player.max_audio_channels",
                            title = "Canais de áudio máximos",
                            description = "Limita a seleção de áudio sem criar um mixer ou decoder alternativo.",
                            selectedValue = state.settings["player.max_audio_channels"],
                            choices = maxAudioChannelsChoices,
                            onSelected = { onUpdateSetting("player.max_audio_channels", it) },
                        )
                    }
                    item(key = "section:player:display") { SettingsSectionLabel("TELA") }
                    item(key = "setting:player.immersive") {
                        ChoiceSettingCard(
                            keyName = "player.immersive",
                            title = "Modo imersivo",
                            description = "Controla as barras do sistema somente no player.",
                            selectedValue = state.settings["player.immersive"],
                            choices = immersiveChoices,
                            onSelected = { onUpdateSetting("player.immersive", it) },
                        )
                    }
                    item(key = "setting:player.rotation") {
                        ChoiceSettingCard(
                            keyName = "player.rotation",
                            title = "Rotação",
                            description = "Define a orientação do player sem forçar o aplicativo inteiro.",
                            selectedValue = state.settings["player.rotation"],
                            choices = rotationChoices,
                            onSelected = { onUpdateSetting("player.rotation", it) },
                        )
                    }
                    item(key = "setting:player.pip") {
                        BooleanSettingCard(
                            keyName = "player.pip",
                            title = "Picture-in-Picture",
                            description = "Permite PiP quando suportado pelo Android.",
                            checked = state.settings["player.pip"] == "true",
                            onCheckedChange = { onUpdateSetting("player.pip", it.toString()) },
                        )
                    }
                    item(key = "setting:player.auto_hide_seconds") {
                        ChoiceSettingCard(
                            keyName = "player.auto_hide_seconds",
                            title = "Auto-hide dos controles",
                            description = "0 significa nunca.",
                            selectedValue = state.settings["player.auto_hide_seconds"],
                            choices = autoHideChoices,
                            onSelected = { onUpdateSetting("player.auto_hide_seconds", it) },
                        )
                    }

                    item(key = "setting:player.reset") {
                        ReiAnixSettingsSurface(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Restaurar Player",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Restaura somente as preferências do Player aos padrões existentes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ReiAnixSecondaryButton(
                                    text = "Restaurar",
                                    onClick = { onAction("reset_player") },
                                    enabled = !actionBusy("reset_player"),
                                    modifier = Modifier.align(Alignment.End),
                                )
                            }
                        }
                    }
                }
                "Gestos" -> {
                    item(key = "section:gestures:gestures") { SettingsSectionLabel("GESTOS") }
                    item(key = "setting:gestures.volume") {
                        BooleanSettingCard(
                            keyName = "gestures.volume",
                            title = "Gestos de volume",
                            description = "Swipe vertical no lado direito ajusta o volume quando ativado.",
                            checked = state.settings["gestures.volume"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.volume", it.toString()) },
                        )
                    }
                    item(key = "setting:gestures.brightness") {
                        BooleanSettingCard(
                            keyName = "gestures.brightness",
                            title = "Gestos de brilho",
                            description = "Swipe vertical no lado esquerdo ajusta o brilho quando ativado.",
                            checked = state.settings["gestures.brightness"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.brightness", it.toString()) },
                        )
                    }
                    item(key = "setting:gestures.double_tap") {
                        BooleanSettingCard(
                            keyName = "gestures.double_tap",
                            title = "Double tap para seek",
                            description = "Controla o double tap existente.",
                            checked = state.settings["gestures.double_tap"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.double_tap", it.toString()) },
                        )
                    }
                    item(key = "setting:gestures.long_press") {
                        BooleanSettingCard(
                            keyName = "gestures.long_press",
                            title = "Pressão longa",
                            description = "Controla a ação de long press existente.",
                            checked = state.settings["gestures.long_press"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.long_press", it.toString()) },
                        )
                    }
                }
                "Metadata" -> {
                    item(key = "section:metadata:matching") { SettingsSectionLabel("ANILIST") }
                    item(key = "setting:metadata.anilist_enabled") {
                        BooleanSettingCard(
                            keyName = "metadata.anilist_enabled",
                            title = "Usar AniList",
                            description = "Permite ou bloqueia chamadas remotas do cliente AniList.",
                            checked = state.settings["metadata.anilist_enabled"] == "true",
                            onCheckedChange = { onUpdateSetting("metadata.anilist_enabled", it.toString()) },
                        )
                    }
                    item(key = "setting:metadata.auto_match") {
                        BooleanSettingCard(
                            keyName = "metadata.auto_match",
                            title = "Auto-match AniList",
                            description = "Controla a associação automática de novos itens.",
                            checked = state.settings["metadata.auto_match"] == "true",
                            onCheckedChange = { onUpdateSetting("metadata.auto_match", it.toString()) },
                        )
                    }
                }

                "Artwork" -> {
                    item(key = "setting:artwork.enabled") {
                        BooleanSettingCard(
                            keyName = "artwork.enabled",
                            title = "Artwork remoto",
                            description = "Permite downloads remotos de capas pelo Artwork Engine existente.",
                            checked = state.settings["artwork.enabled"] == "true",
                            onCheckedChange = { onUpdateSetting("artwork.enabled", it.toString()) },
                        )
                    }
                    item(key = "setting:artwork.cache_limit_mb") {
                        ChoiceSettingCard(
                            keyName = "artwork.cache_limit_mb",
                            title = "Limite do cache de artwork",
                            description = "Limite persistido aplicado ao único Artwork Engine.",
                            selectedValue = state.settings["artwork.cache_limit_mb"],
                            choices = listOf(
                                SettingChoice("64", "64 MB"),
                                SettingChoice("128", "128 MB"),
                                SettingChoice("256", "256 MB"),
                                SettingChoice("512", "512 MB"),
                            ),
                            onSelected = { onUpdateSetting("artwork.cache_limit_mb", it) },
                        )
                    }
                    item(key = "artwork:clear-cache") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Limpar cache de artwork",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Remove somente o cache temporário administrado pelo catálogo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixSecondaryButton(
                                    text = "Limpar cache",
                                    onClick = { requestDestructiveAction("clear_anilist_cache") },
                                    enabled = !actionBusy("clear_anilist_cache"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                "Armazenamento" -> {
                    val storage = state.storage
                    item(key = "section:storage:access") { SettingsSectionLabel("ACESSO") }
                    item(key = "storage:status") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Estado real do armazenamento",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Vídeos: ${storageMediaAccessLabel(storage.mediaReadState)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Armazenamento amplo: ${storageBroadAccessLabel(storage.broadStorageState)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "SAF autorizadas: ${storage.safRootCount} • volumes removíveis: ${storage.removableVolumeCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                storage.api?.let {
                                    Text(
                                        text = "API Android: $it • ciclo: ${storage.lifecycleState}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (storage.safSelectionPending) {
                                    ReiAnixBadge(
                                        text = "Seleção de pasta em andamento",
                                        tone = ReiAnixBadgeTone.Info,
                                    )
                                }
                            }
                        }
                    }
                    item(key = "storage:actions") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                ReiAnixPrimaryButton(
                                    text = "Adicionar pasta",
                                    onClick = { onAction("select_saf") },
                                    enabled = !actionBusy("select_saf"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Verificar acesso",
                                    onClick = { onAction("check_storage_access") },
                                    enabled = !actionBusy("check_storage_access"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (storage.mediaReadState != "full") {
                                    ReiAnixSecondaryButton(
                                        text = "Solicitar acesso aos vídeos",
                                        onClick = { onAction("request_media_access") },
                                        enabled = !actionBusy("request_media_access"),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (storage.broadStorageState != "available") {
                                    ReiAnixSecondaryButton(
                                        text = "Armazenamento amplo",
                                        onClick = { onAction("open_broad_storage_settings") },
                                        enabled = !actionBusy("open_broad_storage_settings"),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }

                "Dados e Cache" -> {
                    item(key = "section:data:maintenance") { SettingsSectionLabel("DADOS LOCAIS") }
                    item(key = "data-cache:info") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Dados locais",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "As preferências continuam persistidas pelo SettingsStore existente.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixBadge(
                                    text = "Fonte única: SettingsStore",
                                    tone = ReiAnixBadgeTone.Neutral,
                                )
                            }
                        }
                    }
                    item(key = "data-cache:actions") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                ReiAnixPrimaryButton(
                                    text = "Exportar configurações",
                                    onClick = { onAction("settings_export") },
                                    enabled = !actionBusy("settings_export"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Importar configurações",
                                    onClick = { onAction("settings_import") },
                                    enabled = !actionBusy("settings_import"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Limpar cache de artwork",
                                    onClick = { requestDestructiveAction("clear_anilist_cache") },
                                    enabled = !actionBusy("clear_anilist_cache"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Restaurar configurações",
                                    onClick = { requestDestructiveAction("reset_all_settings") },
                                    enabled = !actionBusy("reset_all_settings"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                "Backup e Restauração" -> {
                    item(key = "section:backup:operations") { SettingsSectionLabel("BACKUP") }
                    item(key = "backup:info") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Backup e restauração",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Utiliza o BackupService e o schema SQLite existentes. Nenhum banco paralelo é criado.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item(key = "backup:actions") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                ReiAnixPrimaryButton(
                                    text = "Fazer backup",
                                    onClick = { onAction("backup_create") },
                                    enabled = !actionBusy("backup_create"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Restaurar backup",
                                    onClick = { requestDestructiveAction("backup_restore") },
                                    enabled = !actionBusy("backup_restore"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Verificar integridade",
                                    onClick = { onAction("backup_integrity") },
                                    enabled = !actionBusy("backup_integrity"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Reconciliar arquivos",
                                    onClick = { onAction("backup_reconcile") },
                                    enabled = !actionBusy("backup_reconcile"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                "Diagnóstico" -> {
                    val diagnosticStorage = state.storage
                    item(key = "section:diagnostic:technical") { SettingsSectionLabel("TÉCNICO") }
                    item(key = "diagnostic:info") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Diagnóstico técnico",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Versão: ${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Revisão do snapshot: ${state.revision}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "API Android: ${storageApiLabel(diagnosticStorage.api)} • ciclo: ${diagnosticStorage.lifecycleStateLabel()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixBadge(
                                    text = "DiagnosticsService existente",
                                    tone = ReiAnixBadgeTone.Neutral,
                                )
                            }
                        }
                    }
                    item(key = "diagnostic:actions") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                ReiAnixPrimaryButton(
                                    text = "Verificar integridade",
                                    onClick = { onAction("backup_integrity") },
                                    enabled = !actionBusy("backup_integrity"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ReiAnixSecondaryButton(
                                    text = "Exportar diagnóstico",
                                    onClick = { onAction("diagnostic_export") },
                                    enabled = !actionBusy("diagnostic_export"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                "Áudio e Legendas" -> {
                    item(key = "section:audio:subtitles") { SettingsSectionLabel("LEGENDAS") }
                    item(key = "setting:audio.subtitle_scale") {
                        ChoiceSettingCard(
                            keyName = "audio.subtitle_scale",
                            title = "Escala da legenda",
                            description = "Aplica o tamanho relativo usando o SubtitleView do Media3.",
                            selectedValue = state.settings["audio.subtitle_scale"],
                            choices = subtitleScaleChoices,
                            onSelected = { onUpdateSetting("audio.subtitle_scale", it) },
                        )
                    }
                    item(key = "setting:audio.subtitle_bottom_padding") {
                        ChoiceSettingCard(
                            keyName = "audio.subtitle_bottom_padding",
                            title = "Margem inferior da legenda",
                            description = "Controla a margem inferior quando a cue não especifica uma linha fixa.",
                            selectedValue = state.settings["audio.subtitle_bottom_padding"],
                            choices = subtitlePaddingChoices,
                            onSelected = { onUpdateSetting("audio.subtitle_bottom_padding", it) },
                        )
                    }
                    item(key = "setting:audio.subtitle_embedded_style") {
                        BooleanSettingCard(
                            keyName = "audio.subtitle_embedded_style",
                            title = "Estilo embutido da legenda",
                            description = "Permite que o estilo declarado pela própria faixa seja aplicado.",
                            checked = state.settings["audio.subtitle_embedded_style"] == "true",
                            onCheckedChange = { onUpdateSetting("audio.subtitle_embedded_style", it.toString()) },
                        )
                    }
                    item(key = "section:audio:languages") { SettingsSectionLabel("IDIOMAS") }
                    item(key = "setting:audio.preferred_language") {
                        LanguageSettingCard(
                            keyName = "audio.preferred_language",
                            title = "Idioma de áudio",
                            description = "Use uma tag BCP-47 como pt-BR, en ou ja. O Media3 usa fallback seguro se não existir.",
                            selectedValue = state.settings["audio.preferred_language"].orEmpty(),
                            onSave = { onUpdateSetting("audio.preferred_language", it) },
                        )
                    }
                    item(key = "setting:audio.preferred_subtitle_language") {
                        LanguageSettingCard(
                            keyName = "audio.preferred_subtitle_language",
                            title = "Idioma da legenda",
                            description = "Use uma tag BCP-47. A seleção ocorre somente entre tracks existentes no arquivo.",
                            selectedValue = state.settings["audio.preferred_subtitle_language"].orEmpty(),
                            onSave = { onUpdateSetting("audio.preferred_subtitle_language", it) },
                        )
                    }
                    item(key = "section:audio:behavior") { SettingsSectionLabel("COMPORTAMENTO") }
                    item(key = "setting:audio.subtitles") {
                        ChoiceSettingCard(
                            keyName = "audio.subtitles",
                            title = "Legendas",
                            description = "Automático respeita as preferências do arquivo; Sempre tenta selecionar uma legenda; Nunca desativa a track de texto.",
                            selectedValue = state.settings["audio.subtitles"],
                            choices = subtitleModeChoices,
                            onSelected = { onUpdateSetting("audio.subtitles", it) },
                        )
                    }
                }

                "Sobre" -> {
                    item(key = "section:about:identity") { SettingsSectionLabel("REIANIX") }
                    item(key = "about:app") {
                        ReiAnixSettingsSurface(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "ReiAnix",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Aplicativo local para organização e reprodução de mídia.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixBadge(
                                    text = BuildConfig.VERSION_NAME,
                                    tone = ReiAnixBadgeTone.Neutral,
                                )
                                Text(
                                    text = "Player: Media3 / NativePlayer existente.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Armazenamento: MediaStore / SAF / scanners nativos existentes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                "Varredura" -> {
                    item(key = "section:scan:state") { SettingsSectionLabel("SCANNER") }
                    item(key = "scan:existing") {
                        ReiAnixSettingsSurface(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Scanner",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "MediaStore, SAF e broad storage continuam sob o ScanCoordinator e scanners existentes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixBadge(text = "Existente", tone = ReiAnixBadgeTone.Neutral)
                            }
                        }
                    }
                    item(key = "scan:refresh") {
                        ReiAnixSettingsSurface(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Atualização",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Settings não inicia uma varredura automaticamente; o refresh continua no fluxo existente da Biblioteca.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixBadge(text = "Sem scan", tone = ReiAnixBadgeTone.Neutral)
                            }
                        }
                    }
                }

                "Privacidade" -> {
                    item(key = "section:privacy:local") { SettingsSectionLabel("PRIVACIDADE") }
                    item(key = "privacy:local") {
                        ReiAnixSettingsSurface(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Biblioteca local",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Biblioteca, histórico e caminhos locais permanecem no dispositivo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixBadge(text = "Local", tone = ReiAnixBadgeTone.Neutral)
                            }
                        }
                    }
                    item(key = "privacy:connectivity") {
                        ReiAnixSettingsSurface(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Conectividade",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Google Login é opcional; a biblioteca local e o player não dependem de conectividade.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ReiAnixBadge(text = "Offline-first", tone = ReiAnixBadgeTone.Neutral)
                            }
                        }
                    }
                }


                }
            }
            }
        }
    
    }
}

@Composable
private fun SettingsSectionLabel(
    title: String,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = ReiAnixTokens.Spacing.sm,
                bottom = ReiAnixTokens.Spacing.xs,
                start = ReiAnixTokens.Spacing.xs,
            )
            .semantics { heading() },
    )
}

@Composable
fun ReiAnixSettingsSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = ReiAnixTokens.Elevation.none,
        content = content,
    )
}

@Composable
fun ReiAnixSettingsRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = if (description.isBlank()) title else "$title. $description"
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(
                    horizontal = ReiAnixTokens.Spacing.xs,
                    vertical = ReiAnixTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Box(
                modifier = Modifier.size(ReiAnixTokens.Dimensions.settingsIconContainerSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ReiAnixTokens.Colors.disabledTextAlpha)
                    },
                    modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailingContent?.let {
                Box(
                    modifier = Modifier.size(ReiAnixTokens.Dimensions.settingsTrailingSize),
                    contentAlignment = Alignment.Center,
                ) {
                    it()
                }
            }
        }
        HorizontalDivider(
            thickness = ReiAnixTokens.Dimensions.dividerHeight,
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = ReiAnixTokens.Colors.dividerAlpha,
            ),
        )
    }
}
@Composable
fun SettingsHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    backContentDescription: String = "Voltar das configurações",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = ReiAnixTokens.Spacing.none,
                bottom = ReiAnixTokens.Spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(ReiAnixTokens.Dimensions.touchTarget)
                .semantics {
                    contentDescription = backContentDescription
                },
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun settingsCategoryDescription(category: String): String =
    ReiAnixSettingsCategoryUiModel.defaultCategories()
        .firstOrNull { it.label == category }
        ?.description
        .orEmpty()
        .ifBlank { "Preferências do ReiAnix" }

@Composable
private fun BooleanSettingCard(
    keyName: String,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = if (description.isBlank()) title else title + ". " + description
                stateDescription = if (checked) "Ativado" else "Desativado"
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)
                .clickable(
                    role = Role.Switch,
                    onClick = { onCheckedChange(!checked) },
                )
                .padding(
                    horizontal = ReiAnixTokens.Spacing.xs,
                    vertical = ReiAnixTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier
                    .size(ReiAnixTokens.Dimensions.settingsTrailingSize)
                    .semantics {
                        stateDescription = if (checked) "Ativado" else "Desativado"
                    },
            )
        }
        HorizontalDivider(
            thickness = ReiAnixTokens.Dimensions.dividerHeight,
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = ReiAnixTokens.Colors.dividerAlpha,
            ),
        )
    }
}

@Composable
private fun LanguageSettingCard(
    keyName: String,
    title: String,
    description: String,
    selectedValue: String,
    onSave: (String) -> Unit,
) {
    var draftValue by androidx.compose.runtime.saveable.rememberSaveable(selectedValue) {
        androidx.compose.runtime.mutableStateOf(selectedValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (description.isBlank()) title else title + ". " + description
            },
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { onSave(draftValue.trim()) },
                modifier = Modifier
                    .heightIn(min = ReiAnixTokens.Dimensions.touchTarget)
                    .semantics { contentDescription = "Salvar " + title },
            ) {
                Text("Salvar")
            }
        }
        ReiAnixTextField(
            value = draftValue,
            onValueChange = { draftValue = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.Spacing.xs)
                .padding(bottom = ReiAnixTokens.Spacing.sm)
                .semantics { contentDescription = title },
        )
        HorizontalDivider(
            thickness = ReiAnixTokens.Dimensions.dividerHeight,
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = ReiAnixTokens.Colors.dividerAlpha,
            ),
        )
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
    var dialogOpen by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val selectedLabel = choices.firstOrNull { it.value == selectedValue }?.label
        ?: selectedValue?.ifBlank { null }
        ?: "Selecionar"

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(title) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(
                        items = choices,
                        key = { choice -> keyName + ":" + choice.value },
                    ) { choice ->
                        val selected = choice.value == selectedValue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        onSelected(choice.value)
                                        dialogOpen = false
                                    },
                                    role = Role.RadioButton,
                                )
                                .semantics {
                                    contentDescription = title + ": " + choice.label
                                    stateDescription = if (selected) "Selecionado" else "Não selecionado"
                                }
                                .heightIn(min = ReiAnixTokens.Dimensions.touchTarget),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                            )
                            Text(
                                text = choice.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text("Fechar")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = if (description.isBlank()) title else title + ". " + description
                stateDescription = "Atual: " + selectedLabel
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)
                .clickable(
                    role = Role.Button,
                    onClick = { dialogOpen = true },
                )
                .padding(
                    horizontal = ReiAnixTokens.Spacing.xs,
                    vertical = ReiAnixTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .widthIn(min = 64.dp, max = 120.dp)
                    .semantics { contentDescription = selectedLabel },
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ReiAnixTokens.Dimensions.iconSmall),
                )
            }
        }
        HorizontalDivider(
            thickness = ReiAnixTokens.Dimensions.dividerHeight,
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = ReiAnixTokens.Colors.dividerAlpha,
            ),
        )
    }
}

@Composable
private fun ReiAnixSettingsAccountContent(
    state: com.reiflix.reiflix_local.ui.model.ReiAnixSettingsAccountUiState,
    onAction: (String) -> Unit,
) {
    val busy = state.state in setOf("connecting", "awaiting_google", "disconnecting")
    val name = state.name.trim()
    val email = state.email.trim()
    val title = name.ifBlank { email.ifBlank { "Conta Google" } }
    val status = when (state.state) {
        "connected" -> "Conta conectada"
        "connecting" -> "Conectando com o Google…"
        "awaiting_google" -> "Aguardando a escolha da conta Google…"
        "disconnecting" -> "Encerrando a sessão…"
        "configuration_required" -> "Configuração necessária"
        "error" -> "Não foi possível concluir a operação Google."
        else -> "Não conectado"
    }
    val statusTone = when {
        state.state == "connected" -> ReiAnixBadgeTone.Success
        state.state == "error" -> ReiAnixBadgeTone.Error
        state.state in setOf("connecting", "awaiting_google", "disconnecting") -> ReiAnixBadgeTone.Info
        state.state == "configuration_required" -> ReiAnixBadgeTone.Warning
        else -> ReiAnixBadgeTone.Neutral
    }
    val retryAction = if (state.connected) "logout" else "login"

    ReiAnixSettingsSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
            ) {
                Box(
                    modifier = Modifier.size(ReiAnixTokens.Dimensions.settingsIconContainerSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Conta Google",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.connected && email.isNotBlank()) {
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            ReiAnixBadge(
                text = status,
                tone = statusTone,
            )

            if (state.state == "error") {
                Text(
                    text = if (state.connected) {
                        "A conta continua conectada. Você pode tentar encerrar a sessão novamente."
                    } else {
                        "A conta não foi conectada. Você pode tentar entrar novamente."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (state.state == "configuration_required") {
                Text(
                    text = "Este APK precisa de um Web Client ID Google público configurado para iniciar a autenticação.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = "A biblioteca local, o scanner e o player continuam disponíveis sem login e sem conectividade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    com.reiflix.reiflix_local.ui.ReiAnixLoadingIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Operação da conta Google em andamento"
                        },
                    )
                } else if (state.state == "error") {
                    ReiAnixPrimaryButton(
                        text = "Tentar novamente",
                        onClick = { onAction(retryAction) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Filled.Refresh,
                    )
                } else if (state.connected) {
                    ReiAnixPrimaryButton(
                        text = "Trocar conta",
                        onClick = { onAction("switch") },
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Filled.AccountCircle,
                    )
                    ReiAnixSecondaryButton(
                        text = "Sair",
                        onClick = { onAction("logout") },
                        modifier = Modifier.weight(1f),
                    )
                } else if (state.integrationAvailable) {
                    ReiAnixPrimaryButton(
                        text = "Entrar com Google",
                        onClick = { onAction("login") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = Icons.Filled.AccountCircle,
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
    val identity = state.name.trim().ifBlank { state.email.trim() }
    val description = when {
        !state.integrationAvailable -> "Não disponível"
        state.connected && identity.isNotBlank() -> identity +
            state.email.takeIf { state.name.isNotBlank() && it.isNotBlank() }?.let { " • $it" }.orEmpty()
        state.state == "connecting" -> "Conectando…"
        state.state == "configuration_required" -> "Configuração necessária"
        state.state == "error" -> "Erro ao conectar"
        else -> "Não conectado"
    }

    ReiAnixSettingsRow(
        icon = Icons.Filled.AccountCircle,
        title = "Conta",
        description = description,
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(ReiAnixTokens.Dimensions.iconMedium)
                .rotate(180f),
        )
    }
}

@Composable
private fun ReiAnixSettingsCategoryCard(
    category: ReiAnixSettingsCategoryUiModel,
    valueSummary: String,
    onClick: () -> Unit,
) {
    val description = if (valueSummary.isBlank()) {
        category.description
    } else {
        category.description + " • " + valueSummary
    }

    ReiAnixSettingsRow(
        icon = category.icon,
        title = category.label,
        description = description,
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(ReiAnixTokens.Dimensions.iconMedium)
                .rotate(180f),
        )
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
        "Biblioteca" -> listOfNotNull(
            settings["library.page_size"]?.takeIf { it.isNotBlank() }?.let { "$it itens/página" },
            if (settings["library.continue_watching"] == "true") "Continue Watching" else null,
        ).joinToString(" • ")
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
        "Artwork" -> if (settings["artwork.enabled"] == "true") "Artwork remoto ativo" else "Artwork remoto desativado"
        "Privacidade" -> "Dados locais"
        "Varredura" -> "ScanCoordinator existente"
        "Dados e Cache" -> "Preferências locais"
        "Backup e Restauração" -> "BackupService existente"
        "Diagnóstico" -> "Diagnóstico local"
        "Sobre" -> "ReiAnix"
        else -> ""
    }



private fun storageMediaAccessLabel(state: String): String = when (state.lowercase()) {
    "full" -> "Acesso concedido"
    "partial" -> "Acesso parcial"
    "denied" -> "Sem permissão"
    "unknown" -> "Verificando"
    else -> state.ifBlank { "Desconhecido" }
}

private fun storageBroadAccessLabel(state: String): String = when (state.lowercase()) {
    "available" -> "Disponível"
    "unavailable" -> "Indisponível"
    "unknown" -> "Verificando"
    else -> state.ifBlank { "Desconhecido" }
}

private fun storageApiLabel(api: Int?): String = api?.toString() ?: "desconhecida"

private fun com.reiflix.reiflix_local.ui.model.ReiAnixSettingsStorageUiState.lifecycleStateLabel(): String =
    lifecycleState.ifBlank { "desconhecido" }

private fun storageSummary(state: ReiAnixSettingsUiState): String {
    val storage = state.storage
    if (!storage.known) return "Verificando permissões"
    val media = when (storage.mediaReadState) {
        "full" -> "Vídeos permitidos"
        "partial" -> "Acesso parcial"
        "denied" -> "Vídeos sem permissão"
        else -> "Estado de vídeos desconhecido"
    }
    return buildString {
        append(media)
        if (storage.safRootCount > 0) append(" • " + storage.safRootCount + " SAF")
        if (storage.safSelectionPending) append(" • Seleção em andamento")
    }
}
