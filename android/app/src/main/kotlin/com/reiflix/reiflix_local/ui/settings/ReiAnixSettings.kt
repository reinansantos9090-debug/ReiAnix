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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
import com.reiflix.reiflix_local.ui.ReiAnixIconActionButton
import com.reiflix.reiflix_local.ui.ReiAnixTopBar
import com.reiflix.reiflix_local.ui.ReiAnixTextField
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsOperationState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixManualDiagnostics
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.widthIn
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics

data class ReiAnixSettingsCategoryUiModel(
    val label: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    companion object {
        fun defaultCategories(): List<ReiAnixSettingsCategoryUiModel> = listOf(
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
    SettingChoice("landscape", "Somente em paisagem"),
    SettingChoice("never", "Nunca"),
)

private val rotationChoices = listOf(
    SettingChoice("auto", "Automática"),
    SettingChoice("portrait", "Retrato"),
    SettingChoice("landscape", "Paisagem"),
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
    onOpenStorage: () -> Unit = {},
    scanInProgress: Boolean = false,
    scanState: String = "IDLE",
    lastScanStatus: String? = null,
    libraryFoldersContent: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedDestination by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var selectedItemIndex by rememberSaveable { androidx.compose.runtime.mutableStateOf(0) }
    val rootListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val rootStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Restore saved navigation from APKs that still had these intermediate pages.
    val destination = when (selectedDestination) {
        "Conta" -> null
        "general_behavior", "general_privacy" -> "Geral"
        "library_storage", "library_scan", "library_organization" -> "Biblioteca"
        "player_advanced" -> "Player"
        "metadata_identification", "metadata_artwork" -> "Metadados e Capas"
        "data_cache" -> "Dados e Backup"
        "about_app" -> "Sobre e Diagnóstico"
        else -> selectedDestination
    }
    fun goBack() {
        selectedDestination = settingsDestination(destination)?.parent?.takeIf { it.isNotBlank() }
        selectedItemIndex = 0
    }
    BackHandler(enabled = destination != null) { goBack() }
    when {
        destination != null -> androidx.compose.runtime.key(destination, selectedItemIndex) {
            ReiAnixComposeSettingsCategoryScreen(
                category = destination,
                initialItemIndex = selectedItemIndex,
                onOpenSubmenu = { selectedDestination = it; selectedItemIndex = 0 },
                state = state,
                onBack = ::goBack,
                onUpdateSetting = viewModel::setSetting,
                    onAction = viewModel::requestAction,
                onOpenStorage = onOpenStorage,
                libraryFoldersContent = libraryFoldersContent,
                scanInProgress = scanInProgress,
                scanState = scanState,
                lastScanStatus = lastScanStatus,
                onRetry = viewModel::refresh,
            )
        }
        else -> rootStateHolder.SaveableStateProvider("settings-root") {
            ReiAnixSettingsScreen(
                state = state,
                listState = rootListState,
                onBack = onBack,
                onOpenCategory = { label ->
                    keyboard?.hide()
                    val route = label.substringBefore('#')
                    val itemKey = label.substringAfter('#', "")
                    val submenu = settingsDestination(route)
                    if (submenu != null || SettingsRootCategories.any { it.label == route }) {
                        selectedDestination = route
                        selectedItemIndex = submenu?.entries?.indexOfFirst { it.key == itemKey }
                            ?.takeIf { it >= 0 }?.let { it + 2 + SettingsDestinations.count { child -> child.parent == route } } ?: 0
                    } else {
                        onOpenCategory(label)
                    }
                },
                onRetry = viewModel::refresh,
            )
        }
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
    val accountContext = androidx.compose.ui.platform.LocalContext.current
    val account by com.reiflix.reiflix_local.ReiAnixAppDependencies.from(accountContext)
        .aniListAccountService.state.collectAsStateWithLifecycle()
    var searchVisible by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var searchQuery by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val searchResults = searchSettings(searchQuery)
    ReiAnixSettingsRoot {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth)
                    .fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = 0.dp,
                    end = 0.dp,
                    top = ReiAnixTokens.Spacing.sm,
                    bottom = ReiAnixTokens.Spacing.xl,
                ),
                verticalArrangement = Arrangement.Top,
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
                        item(key = "settings:top-bar") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (account.connected) {
                                    com.reiflix.reiflix_local.ui.account.ReiAnixAccountAvatar(
                                        pictureUrl = account.avatarUrl,
                                        contentDescription = "Foto de ${account.name}",
                                        modifier = Modifier.size(50.dp),
                                    )
                                }
                                Text(
                                    text = if (account.connected) account.name else "Configurações",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                )
                                IconButton(onClick = { searchVisible = !searchVisible; searchQuery = "" }) {
                                    Icon(Icons.Filled.Search, contentDescription = "Pesquisar configurações")
                                }
                            }
                        }

                        if (searchVisible) {
                            item(key = "settings:search") {
                                androidx.compose.material3.TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Pesquisar configurações") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (searchQuery.isBlank() || listOf("anilist", "contas").any {
                            it.contains(searchQuery.trim(), ignoreCase = true)
                        }) {
                            item(key = "settings:anilist-account") {
                                SettingsFlatCategoryRow(
                                    ReiAnixSettingsCategoryUiModel("Contas", "AniList", Icons.Filled.AccountCircle),
                                    onClick = { accountContext.startActivity(android.content.Intent(accountContext,
                                        com.reiflix.reiflix_local.AniListAccountActivity::class.java)) },
                                )
                            }
                        }
                        if (searchQuery.isBlank()) {
                            items(SettingsRootCategories, key = { "settings-category:${it.label}" }) { category ->
                                SettingsFlatCategoryRow(category = category, onClick = { onOpenCategory(category.label) })
                            }
                        } else {
                            if (searchResults.isEmpty()) {
                                item(key = "settings:no-results") { Text("Nenhuma configuração encontrada.") }
                            }
                            items(searchResults, key = { "settings-search:${it.destination.id}:${it.entry.key}" }) { result ->
                                SettingsFlatCategoryRow(
                                    category = ReiAnixSettingsCategoryUiModel(
                                        result.entry.title,
                                        result.destination.parent.takeIf { it.isNotBlank() }?.let { "$it → ${result.destination.title}" } ?: result.destination.title,
                                        SettingsRootCategories.first { it.label == result.destination.parent.ifBlank { result.destination.title } }.icon,
                                    ),
                                    onClick = { onOpenCategory("${result.destination.id}#${result.entry.key}") },
                                    showDescription = true,
                                )
                            }
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
                                    text = "ReiAnix  •  ${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelMedium,
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
private fun SettingsFlatCategoryRow(
    category: ReiAnixSettingsCategoryUiModel,
    onClick: () -> Unit,
    showDescription: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = if (showDescription) 12.dp else 0.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${category.label}. ${category.description}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(category.label, style = MaterialTheme.typography.bodyMedium)
            if (showDescription) Text(category.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ReiAnixComposeSettingsCategoryScreen(
    category: String,
    initialItemIndex: Int = 0,
    onOpenSubmenu: (String) -> Unit,
    state: ReiAnixSettingsUiState,
    onBack: () -> Unit,
    onUpdateSetting: (String, String) -> Unit,
    onAction: (String) -> Unit,
    onOpenStorage: () -> Unit,
    libraryFoldersContent: (@Composable () -> Unit)?,
    scanInProgress: Boolean = false,
    scanState: String = "IDLE",
    lastScanStatus: String? = null,
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
    val diagnosticEvents by ReiAnixDiagnosticTimeline.events.collectAsStateWithLifecycle()
    val diagnosticContext = androidx.compose.ui.platform.LocalContext.current
    val diagnosticWorkRunning = state.operations.values.any {
        it.action.startsWith("diagnostic_") && it.action != "diagnostic_cancel" &&
            it.state in setOf(
                ReiAnixSettingsOperationState.QUEUED,
                ReiAnixSettingsOperationState.RUNNING,
            )
    }
    val diagnosticCollectionRunning = state.operations.values.any {
        it.action.startsWith("diagnostic_run_") &&
            it.state in setOf(
                ReiAnixSettingsOperationState.QUEUED,
                ReiAnixSettingsOperationState.RUNNING,
            )
    }

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

    ReiAnixSettingsRoot {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        pendingConfirmationAction?.let { action ->
            val (title, message, confirmLabel) = when (action) {
                "clear_anilist_cache" -> Triple(
                    "Limpar cache de capas?",
                    "A biblioteca, progresso, favoritos, notas, pins e arquivos não serão apagados.",
                    "Limpar",
                )
                "reset_all_settings" -> Triple(
                    "Restaurar todas as configurações?",
                    "Somente as preferências do ReiAnix serão restauradas. Biblioteca, consumo, metadata manual, artwork, arquivos e permissões permanecem intactos.",
                    "Restaurar",
                )
                "reset_player" -> Triple(
                    "Restaurar configurações do Player?",
                    "Somente as preferências do Player voltarão aos valores padrão. A biblioteca, o progresso salvo e as demais configurações serão preservados.",
                    "Restaurar",
                )
                "backup_restore" -> Triple(
                    "Restaurar backup?",
                    "O BackupService existente validará o arquivo antes de alterar o estado lógico local.",
                    "Continuar",
                )
                "diagnostic_clear" -> Triple(
                    "Limpar dados de diagnóstico?",
                    "Serão removidos os resultados coletados, o histórico local e a timeline em memória. A biblioteca e o banco SQLite canônico não serão alterados.",
                    "Limpar dados",
                )
                else -> Triple(
                    "Confirmar ação",
                    "Confirme para continuar.",
                    "Confirmar",
                )
            }
            AlertDialog(
                onDismissRequest = { pendingConfirmationAction = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    SettingsDialogButton(
                        text = confirmLabel,
                        onClick = {
                            pendingConfirmationAction = null
                            onAction(action)
                        },
                        enabled = !actionBusy(action),
                    )
                },
                dismissButton = {
                    SettingsDialogButton(
                        text = "Cancelar",
                        onClick = { pendingConfirmationAction = null },
                    )
                },
            )
        }

        LazyColumn(
            state = rememberSaveable(category, initialItemIndex, saver = LazyListState.Saver) { LazyListState(initialItemIndex) },
            modifier = Modifier
                .widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = 0.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.Top,
        ) {
            item(key = "header:" + category) {
                SettingsHeader(
                    title = settingsDestination(category)?.title ?: category,
                    subtitle = settingsDestination(category)?.parent?.takeIf { it.isNotBlank() } ?: settingsCategoryDescription(category),
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
                items(SettingsDestinations.filter { it.parent == category }, key = { "submenu:" + it.id }) { submenu ->
                    SettingsFlatCategoryRow(
                        category = ReiAnixSettingsCategoryUiModel(submenu.title, submenu.entries.joinToString(" · ") { it.title }, SettingsRootCategories.first { it.label == category }.icon),
                        onClick = { onOpenSubmenu(submenu.id) },
                    )
                }
                when (category) {
                "Biblioteca" -> {
                    item(key = "setting:library.sort_default") {
                        ChoiceSettingRow(
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
                    item(key = "setting:library.page_size") {
                        ChoiceSettingRow(
                            keyName = "library.page_size",
                            title = "Itens por página",
                            description = "Define quantos animes são exibidos; Tudo mostra a biblioteca completa.",
                            selectedValue = state.settings["library.page_size"],
                            choices = listOf(
                                SettingChoice("0", "Tudo"),
                                SettingChoice("24", "24"),
                                SettingChoice("36", "36"),
                                SettingChoice("48", "48"),
                                SettingChoice("72", "72"),
                            ),
                            onSelected = { onUpdateSetting("library.page_size", it) },
                        )
                    }
                    item(key = "folders") {
                        if (libraryFoldersContent != null) {
                            libraryFoldersContent()
                        } else {
                            SettingsActionRow(
                                text = "Pasta da biblioteca",
                                onClick = onOpenStorage,
                                modifier = Modifier.fillMaxWidth().padding(top = ReiAnixTokens.Spacing.md),
                            )
                        }
                    }
                }
                "Geral" -> {
                    item(key = "setting:app.confirm_destructive") {
                        BooleanSettingRow(
                            keyName = "app.confirm_destructive",
                            title = "Confirmar ações destrutivas",
                            description = "Pede confirmação antes de ações como limpar cache e restaurar configurações.",
                            checked = state.settings["app.confirm_destructive"] == "true",
                            onCheckedChange = { onUpdateSetting("app.confirm_destructive", it.toString()) },
                        )
                    }
                }

                "Player" -> {
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
                                    text = "Restaurar configurações do player",
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
                                SettingsActionRow(
                                    text = "Restaurar",
                                    onClick = { requestDestructiveAction("reset_player") },
                                    enabled = !actionBusy("reset_player"),
                                    modifier = Modifier.align(Alignment.End),
                                )
                            }
                        }
                    }
                }

                "Metadados e Capas" -> {
                    item(key = "setting:metadata.anilist_enabled") {
                        BooleanSettingRow(
                            keyName = "metadata.anilist_enabled",
                            title = "Usar AniList",
                            description = "Permite ou bloqueia chamadas remotas do cliente AniList.",
                            checked = state.settings["metadata.anilist_enabled"] == "true",
                            onCheckedChange = { onUpdateSetting("metadata.anilist_enabled", it.toString()) },
                        )
                    }
                    item(key = "setting:metadata.auto_match") {
                        BooleanSettingRow(
                            keyName = "metadata.auto_match",
                            title = "Identificar automaticamente pelo AniList",
                            description = "Controla a associação automática de novos itens.",
                            checked = state.settings["metadata.auto_match"] == "true",
                            onCheckedChange = { onUpdateSetting("metadata.auto_match", it.toString()) },
                        )
                    }
                    item(key = "setting:artwork.enabled") {
                        BooleanSettingRow(
                            keyName = "artwork.enabled",
                            title = "Baixar capas pela internet",
                            description = "Permite downloads remotos de capas pelo Artwork Engine existente.",
                            checked = state.settings["artwork.enabled"] == "true",
                            onCheckedChange = { onUpdateSetting("artwork.enabled", it.toString()) },
                        )
                    }
                    item(key = "setting:artwork.cache_limit_mb") {
                        ChoiceSettingRow(
                            keyName = "artwork.cache_limit_mb",
                            title = "Limite do cache de capas",
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
                }

                "Dados e Backup" -> {
                    item(key = "data-cache:artwork-cache") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                Text(
                                    text = "Cache de capas e miniaturas",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Remove as capas e miniaturas temporárias do aplicativo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SettingsActionRow(
                                    text = "Limpar cache de capas",
                                    onClick = { requestDestructiveAction("clear_anilist_cache") },
                                    enabled = !actionBusy("clear_anilist_cache"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                "Sobre e Diagnóstico" -> {
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

                "appearance_theme" -> {
                    item(key = "setting:appearance.theme") {
                        ChoiceSettingRow(
                            keyName = "appearance.theme",
                            title = "Tema",
                            description = "Aplica o tema da interface Compose imediatamente, sem reiniciar a Activity.",
                            selectedValue = state.settings["appearance.theme"],
                            choices = themeChoices,
                            onSelected = { onUpdateSetting("appearance.theme", it) },
                        )
                    }
                }
                "appearance_library" -> {
                    item(key = "setting:appearance.card_size") {
                        ChoiceSettingRow(
                            keyName = "appearance.card_size",
                            title = "Tamanho dos cards",
                            description = "Controla o tamanho visual dos cards da biblioteca/Home.",
                            selectedValue = state.settings["appearance.card_size"],
                            choices = cardSizeChoices,
                            onSelected = { onUpdateSetting("appearance.card_size", it) },
                        )
                    }
                    item(key = "setting:library.grid_density") {
                        ChoiceSettingRow(
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
                    item(key = "setting:appearance.poster_size") {
                        PosterSizeSettingRow(
                            selectedValue = state.settings["appearance.poster_size"],
                            onSelected = { onUpdateSetting("appearance.poster_size", it.toString()) },
                        )
                    }
                    item(key = "setting:appearance.show_thumbnails") {
                        BooleanSettingRow(
                            keyName = "appearance.show_thumbnails",
                            title = "Mostrar miniaturas",
                            description = "Quando desativado, a Home mantém o espaço do card, mas não carrega imagens.",
                            checked = state.settings["appearance.show_thumbnails"] == "true",
                            onCheckedChange = { onUpdateSetting("appearance.show_thumbnails", it.toString()) },
                        )
                    }
                    item(key = "setting:library.continue_watching") {
                        BooleanSettingRow(
                            keyName = "library.continue_watching",
                            title = "Continuar assistindo",
                            description = "Controla a preferência global dessa seção.",
                            checked = state.settings["library.continue_watching"] == "true",
                            onCheckedChange = { onUpdateSetting("library.continue_watching", it.toString()) },
                        )
                    }
                    item(key = "setting:library.continue_watching_limit") {
                        ChoiceSettingRow(
                            keyName = "library.continue_watching_limit",
                            title = "Limite de continuar assistindo",
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
                "player_playback" -> {
                    item(key = "setting:player.autoplay_next") {
                        BooleanSettingRow(
                            keyName = "player.autoplay_next",
                            title = "Reprodução automática do próximo episódio",
                            description = "Permite o avanço automático no player local.",
                            checked = state.settings["player.autoplay_next"] == "true",
                            onCheckedChange = { onUpdateSetting("player.autoplay_next", it.toString()) },
                        )
                    }
                    item(key = "setting:player.resume") {
                        BooleanSettingRow(
                            keyName = "player.resume",
                            title = "Continuar reprodução",
                            description = "Usa a posição de progresso já salva; desligar não apaga o progresso.",
                            checked = state.settings["player.resume"] == "true",
                            onCheckedChange = { onUpdateSetting("player.resume", it.toString()) },
                        )
                    }
                    item(key = "setting:player.default_speed") {
                        ChoiceSettingRow(
                            keyName = "player.default_speed",
                            title = "Velocidade padrão",
                            description = "Aplicada quando um episódio é aberto.",
                            selectedValue = state.settings["player.default_speed"],
                            choices = defaultSpeedChoices,
                            onSelected = { onUpdateSetting("player.default_speed", it) },
                        )
                    }
                }
                "player_video" -> {
                    item(key = "setting:player.aspect_ratio") {
                        ChoiceSettingRow(
                            keyName = "player.aspect_ratio",
                            title = "Modo de vídeo",
                            description = "Ajustar preserva toda a imagem; Preencher ocupa a tela cortando somente o excedente.",
                            selectedValue = state.settings["player.aspect_ratio"],
                            choices = aspectRatioChoices,
                            onSelected = { onUpdateSetting("player.aspect_ratio", it) },
                        )
                    }
                    item(key = "setting:player.max_video_resolution") {
                        ChoiceSettingRow(
                            keyName = "player.max_video_resolution",
                            title = "Resolução máxima",
                            description = "Limita a faixa de vídeo selecionada pelo Media3 quando o arquivo oferece múltiplas tracks.",
                            selectedValue = state.settings["player.max_video_resolution"],
                            choices = maxVideoResolutionChoices,
                            onSelected = { onUpdateSetting("player.max_video_resolution", it) },
                        )
                    }
                    item(key = "setting:player.max_video_frame_rate") {
                        ChoiceSettingRow(
                            keyName = "player.max_video_frame_rate",
                            title = "FPS máximo",
                            description = "Limita a taxa de frames da track de vídeo selecionada.",
                            selectedValue = state.settings["player.max_video_frame_rate"],
                            choices = maxVideoFrameRateChoices,
                            onSelected = { onUpdateSetting("player.max_video_frame_rate", it) },
                        )
                    }
                }
                "player_controls" -> {
                    item(key = "setting:player.zoom_enabled") {
                        BooleanSettingRow(
                            keyName = "player.zoom_enabled",
                            title = "Zoom por gesto",
                            description = "Permite ampliar e mover o vídeo com gesto de pinça.",
                            checked = state.settings["player.zoom_enabled"] == "true",
                            onCheckedChange = { onUpdateSetting("player.zoom_enabled", it.toString()) },
                        )
                    }
                    item(key = "setting:player.lock_mode") {
                        BooleanSettingRow(
                            keyName = "player.lock_mode",
                            title = "Bloqueio dos controles",
                            description = "Define o estado persistido de bloqueio dos controles ao abrir o player.",
                            checked = state.settings["player.lock_mode"] == "true",
                            onCheckedChange = { onUpdateSetting("player.lock_mode", it.toString()) },
                        )
                    }
                    item(key = "setting:player.double_tap_seek_seconds") {
                        ChoiceSettingRow(
                            keyName = "player.double_tap_seek_seconds",
                            title = "Salto com toque duplo",
                            description = "Define quantos segundos são avançados ou retrocedidos pelo double tap.",
                            selectedValue = state.settings["player.double_tap_seek_seconds"],
                            choices = doubleTapSeekChoices,
                            onSelected = { onUpdateSetting("player.double_tap_seek_seconds", it) },
                        )
                    }
                    item(key = "setting:player.long_press_speed") {
                        ChoiceSettingRow(
                            keyName = "player.long_press_speed",
                            title = "Velocidade da pressão longa",
                            description = "Velocidade temporária aplicada enquanto a pressão longa estiver ativa.",
                            selectedValue = state.settings["player.long_press_speed"],
                            choices = longPressSpeedChoices,
                            onSelected = { onUpdateSetting("player.long_press_speed", it) },
                        )
                    }
                    item(key = "setting:gestures.volume") {
                        BooleanSettingRow(
                            keyName = "gestures.volume",
                            title = "Gestos de volume",
                            description = "Swipe vertical no lado direito ajusta o volume quando ativado.",
                            checked = state.settings["gestures.volume"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.volume", it.toString()) },
                        )
                    }
                    item(key = "setting:gestures.brightness") {
                        BooleanSettingRow(
                            keyName = "gestures.brightness",
                            title = "Gestos de brilho",
                            description = "Swipe vertical no lado esquerdo ajusta o brilho quando ativado.",
                            checked = state.settings["gestures.brightness"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.brightness", it.toString()) },
                        )
                    }
                    item(key = "setting:gestures.horizontal_seek") {
                        BooleanSettingRow(
                            keyName = "gestures.horizontal_seek",
                            title = "Gestos horizontais",
                            description = "Permite avançar ou retroceder no vídeo deslizando horizontalmente.",
                            checked = state.settings["gestures.horizontal_seek"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.horizontal_seek", it.toString()) },
                        )
                    }
                    item(key = "setting:gestures.double_tap") {
                        BooleanSettingRow(
                            keyName = "gestures.double_tap",
                            title = "Toque duplo para avançar ou retroceder",
                            description = "Controla o double tap existente.",
                            checked = state.settings["gestures.double_tap"]?.toBooleanStrictOrNull() ?: true,
                            onCheckedChange = { onUpdateSetting("gestures.double_tap", it.toString()) },
                        )
                    }
                    item(key = "setting:gestures.long_press") {
                        BooleanSettingRow(
                            keyName = "gestures.long_press",
                            title = "Ativar pressão longa",
                            description = "Controla a ação de long press existente.",
                            checked = state.settings["gestures.long_press"] == "true",
                            onCheckedChange = { onUpdateSetting("gestures.long_press", it.toString()) },
                        )
                    }
                }
                "player_audio" -> {
                    item(key = "setting:player.max_audio_channels") {
                        ChoiceSettingRow(
                            keyName = "player.max_audio_channels",
                            title = "Canais de áudio máximos",
                            description = "Limita a seleção de áudio sem criar um mixer ou decoder alternativo.",
                            selectedValue = state.settings["player.max_audio_channels"],
                            choices = maxAudioChannelsChoices,
                            onSelected = { onUpdateSetting("player.max_audio_channels", it) },
                        )
                    }
                    item(key = "setting:audio.preferred_language") {
                        LanguageSettingRow(
                            keyName = "audio.preferred_language",
                            title = "Idioma de áudio",
                            description = "Use uma tag BCP-47 como pt-BR, en ou ja. O Media3 usa fallback seguro se não existir.",
                            selectedValue = state.settings["audio.preferred_language"].orEmpty(),
                            onSave = { onUpdateSetting("audio.preferred_language", it) },
                        )
                    }
                    item(key = "setting:audio.preferred_subtitle_language") {
                        LanguageSettingRow(
                            keyName = "audio.preferred_subtitle_language",
                            title = "Idioma da legenda",
                            description = "Use uma tag BCP-47. A seleção ocorre somente entre tracks existentes no arquivo.",
                            selectedValue = state.settings["audio.preferred_subtitle_language"].orEmpty(),
                            onSave = { onUpdateSetting("audio.preferred_subtitle_language", it) },
                        )
                    }
                    item(key = "setting:audio.subtitle_scale") {
                        ChoiceSettingRow(
                            keyName = "audio.subtitle_scale",
                            title = "Escala da legenda",
                            description = "Aplica o tamanho relativo usando o SubtitleView do Media3.",
                            selectedValue = state.settings["audio.subtitle_scale"],
                            choices = subtitleScaleChoices,
                            onSelected = { onUpdateSetting("audio.subtitle_scale", it) },
                        )
                    }
                    item(key = "setting:audio.subtitle_bottom_padding") {
                        ChoiceSettingRow(
                            keyName = "audio.subtitle_bottom_padding",
                            title = "Margem inferior da legenda",
                            description = "Controla a margem inferior quando a cue não especifica uma linha fixa.",
                            selectedValue = state.settings["audio.subtitle_bottom_padding"],
                            choices = subtitlePaddingChoices,
                            onSelected = { onUpdateSetting("audio.subtitle_bottom_padding", it) },
                        )
                    }
                    item(key = "setting:audio.subtitle_embedded_style") {
                        BooleanSettingRow(
                            keyName = "audio.subtitle_embedded_style",
                            title = "Estilo embutido da legenda",
                            description = "Permite que o estilo declarado pela própria faixa seja aplicado.",
                            checked = state.settings["audio.subtitle_embedded_style"] == "true",
                            onCheckedChange = { onUpdateSetting("audio.subtitle_embedded_style", it.toString()) },
                        )
                    }
                    item(key = "setting:audio.subtitles") {
                        ChoiceSettingRow(
                            keyName = "audio.subtitles",
                            title = "Legendas",
                            description = "Automático respeita as preferências do arquivo; Sempre tenta selecionar uma legenda; Nunca desativa a track de texto.",
                            selectedValue = state.settings["audio.subtitles"],
                            choices = subtitleModeChoices,
                            onSelected = { onUpdateSetting("audio.subtitles", it) },
                        )
                    }
                }
                "player_display" -> {
                    item(key = "setting:player.immersive") {
                        ChoiceSettingRow(
                            keyName = "player.immersive",
                            title = "Modo imersivo",
                            description = "Controla as barras do sistema somente no player.",
                            selectedValue = state.settings["player.immersive"],
                            choices = immersiveChoices,
                            onSelected = { onUpdateSetting("player.immersive", it) },
                        )
                    }
                    item(key = "setting:player.rotation") {
                        ChoiceSettingRow(
                            keyName = "player.rotation",
                            title = "Rotação",
                            description = "Define a orientação do player sem forçar o aplicativo inteiro.",
                            selectedValue = state.settings["player.rotation"],
                            choices = rotationChoices,
                            onSelected = { onUpdateSetting("player.rotation", it) },
                        )
                    }
                    item(key = "setting:player.pip") {
                        BooleanSettingRow(
                            keyName = "player.pip",
                            title = "Picture-in-Picture",
                            description = "Permite PiP quando suportado pelo Android.",
                            checked = state.settings["player.pip"] == "true",
                            onCheckedChange = { onUpdateSetting("player.pip", it.toString()) },
                        )
                    }
                    item(key = "setting:player.auto_hide_seconds") {
                        ChoiceSettingRow(
                            keyName = "player.auto_hide_seconds",
                            title = "Ocultar controles automaticamente",
                            description = "0 significa nunca.",
                            selectedValue = state.settings["player.auto_hide_seconds"],
                            choices = autoHideChoices,
                            onSelected = { onUpdateSetting("player.auto_hide_seconds", it) },
                        )
                    }
                }
                "data_settings" -> {
                    item(key = "data-cache:actions") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                SettingsActionRow(
                                    text = "Exportar configurações",
                                    onClick = { onAction("settings_export") },
                                    enabled = !actionBusy("settings_export"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsActionRow(
                                    text = "Importar configurações",
                                    onClick = { onAction("settings_import") },
                                    enabled = !actionBusy("settings_import"),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                SettingsActionRow(
                                    text = "Restaurar configurações",
                                    onClick = { requestDestructiveAction("reset_all_settings") },
                                    enabled = !actionBusy("reset_all_settings"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                "data_backup" -> {
                    item(key = "backup:actions") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                SettingsActionRow(
                                    text = "Fazer backup",
                                    onClick = { onAction("backup_create") },
                                    enabled = !actionBusy("backup_create"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsActionRow(
                                    text = "Restaurar backup",
                                    onClick = { requestDestructiveAction("backup_restore") },
                                    enabled = !actionBusy("backup_restore"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsActionRow(
                                    text = "Verificar integridade",
                                    onClick = { onAction("backup_integrity") },
                                    enabled = !actionBusy("backup_integrity"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsActionRow(
                                    text = "Reconciliar arquivos",
                                    onClick = { onAction("backup_reconcile") },
                                    enabled = !actionBusy("backup_reconcile"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                "about_diagnostics" -> {
                    val diagnosticStorage = state.storage
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
                                    text = "O backup reúne os dados locais e as configurações do aplicativo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
                                Text(
                                    text = "Nenhuma verificação é iniciada ao abrir esta tela. Escolha explicitamente uma categoria; a coleta geral executa todas as categorias abaixo. Não há varredura de biblioteca, limpeza de cache ou envio remoto automático.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SettingsActionRow(
                                    text = "Executar diagnóstico geral",
                                    onClick = { onAction("diagnostic_run_general") },
                                    enabled = !diagnosticWorkRunning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsSectionLabel("Verificações individuais")
                                val diagnosticActions = listOf(
                                    "Sistema Android" to "diagnostic_run_system",
                                    "Logcat disponível" to "diagnostic_run_logs",
                                    "Armazenamento e permissões" to "diagnostic_run_storage",
                                    "Scanner e indexação" to "diagnostic_run_scanner",
                                    "SQLite e biblioteca" to "diagnostic_run_sqlite",
                                    "Thumbnails, capas e cache" to "diagnostic_run_thumbnails",
                                    "Ponte e comunicação interna" to "diagnostic_run_bridge",
                                    "Desempenho e estabilidade" to "diagnostic_run_performance",
                                )
                                diagnosticActions.forEach { (label, action) ->
                                    SettingsActionRow(
                                        text = label,
                                        onClick = { onAction(action) },
                                        enabled = !diagnosticWorkRunning,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
                "about_reports" -> {
                    val latestDiagnosticOperation = state.operations.values
                        .filter { it.action.startsWith("diagnostic_") && it.action != "diagnostic_cancel" }
                        .maxByOrNull { it.timestampMs }
                    item(key = "diagnostic:reports") {
                        ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                            ) {
                                SettingsActionRow(
                                    text = "Importar logcat (ADB)",
                                    onClick = { onAction("diagnostic_import_logcat") },
                                    enabled = !diagnosticWorkRunning && !actionBusy("diagnostic_import_logcat"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsActionRow(
                                    text = "Cancelar coleta atual",
                                    onClick = { onAction("diagnostic_cancel") },
                                    enabled = diagnosticCollectionRunning,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsActionRow(
                                    text = "Exportar relatório coletado",
                                    onClick = { onAction("diagnostic_export") },
                                    enabled = !diagnosticWorkRunning && !actionBusy("diagnostic_export"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SettingsActionRow(
                                    text = "Limpar resultados e histórico",
                                    onClick = { pendingConfirmationAction = "diagnostic_clear" },
                                    enabled = !diagnosticWorkRunning && !actionBusy("diagnostic_clear"),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = latestDiagnosticOperation?.let { operation ->
                                        val stateLabel = when (operation.state) {
                                            ReiAnixSettingsOperationState.QUEUED -> "AGUARDANDO"
                                            ReiAnixSettingsOperationState.RUNNING -> "EXECUTANDO"
                                            ReiAnixSettingsOperationState.SUCCESS -> "CONCLUÍDA"
                                            ReiAnixSettingsOperationState.CANCELLED -> "CANCELADA"
                                            ReiAnixSettingsOperationState.ERROR -> "FALHOU"
                                            ReiAnixSettingsOperationState.IDLE -> "AGUARDANDO"
                                        }
                                        "Estado da operação: " + stateLabel + " • " +
                                            (operation.message ?: operation.error ?: operation.action)
                                    } ?: "Estado da operação: aguardando ação manual.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Resultados da última coleta:\n" +
                                        ReiAnixManualDiagnostics.resultSummary(diagnosticContext),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Histórico local:\n" +
                                        ReiAnixManualDiagnostics.historySummary(diagnosticContext),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item(key = "diagnostic:timeline:heading") {
                        SettingsSectionLabel("Eventos nativos recentes")
                    }
                    if (diagnosticEvents.isEmpty()) {
                        item(key = "diagnostic:timeline:empty") {
                            ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Ainda não há eventos nativos nesta sessão. Os eventos ficam limitados à memória do processo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(ReiAnixTokens.Spacing.lg),
                                )
                            }
                        }
                    } else {
                        items(
                            diagnosticEvents.take(20),
                            key = { event -> "diagnostic:event:" + event.id },
                        ) { event ->
                            ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(ReiAnixTokens.Spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = event.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = event.severity,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (event.severity == "ERROR") MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = ReiAnixTokens.Spacing.sm),
                                        )
                                    }
                                    Text(
                                        text = (event.source ?: "Android") + " • " +
                                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.timestampMs)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val details = listOfNotNull(
                                        event.result?.let { "Resultado: " + it },
                                        event.requestId?.takeIf { it != "<redacted-ref>" }?.let { "Solicitação: " + it },
                                        event.sessionId?.takeIf { it != "<redacted-ref>" }?.let { "Sessão: " + it },
                                        event.error?.let { "Falha: " + it },
                                    )
                                    details.forEach { detail ->
                                        Text(
                                            text = detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (detail.startsWith("Falha:")) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
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
    
    }
}

@Composable
private fun SettingsSectionLabel(
    title: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 24.dp,
                bottom = 8.dp,
                start = 20.dp,
            )
            .semantics { heading() },
    )
}

/**
 * Flat content wrapper shared by Settings and Storage.
 *
 * Keep the public name for existing call sites, but intentionally avoid a
 * decorative Material surface: no separate background, shape or elevation.
 * The screen's canonical background and content color remain theme-driven.
 */
@Composable
fun ReiAnixSettingsSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
}

@Composable
fun ReiAnixSettingsRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = false,
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
                    horizontal = 20.dp,
                    vertical = 16.dp,
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
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
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
        if (showDivider) {
            HorizontalDivider(
                thickness = ReiAnixTokens.Dimensions.dividerHeight,
                color = MaterialTheme.colorScheme.outlineVariant.copy(
                    alpha = ReiAnixTokens.Colors.dividerAlpha,
                ),
            )
        }
    }
}
@Composable
fun SettingsHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    backContentDescription: String = "Voltar das configurações",
) {
    ReiAnixTopBar(
        title = title,
        subtitle = null,
        navigationIcon = Icons.Filled.ArrowBack,
        navigationContentDescription = backContentDescription,
        onNavigationClick = onBack,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        titleStyle = MaterialTheme.typography.titleLarge,
        titleColor = MaterialTheme.colorScheme.onSurface,
        applyResponsiveHorizontalPadding = false,
    )
}


private fun settingsCategoryDescription(category: String): String =
    ReiAnixSettingsCategoryUiModel.defaultCategories()
        .firstOrNull { it.label == category }
        ?.description
        .orEmpty()
        .ifBlank { "Preferências do ReiAnix" }

@Composable
private fun SettingsPreferenceText(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BooleanSettingRow(
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
                    horizontal = 20.dp,
                    vertical = 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            SettingsPreferenceText(
                title = title,
                description = description,
                modifier = Modifier.weight(1f),
            )
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
                    .semantics {
                        stateDescription = if (checked) "Ativado" else "Desativado"
                    },
            )
        }

    }
}

@Composable
private fun LanguageSettingRow(
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
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsPreferenceText(
                title = title,
                description = description,
                modifier = Modifier.weight(1f),
            )
            SettingsDialogButton(
                text = "Salvar",
                onClick = { onSave(draftValue.trim()) },
                modifier = Modifier.semantics { contentDescription = "Salvar " + title },
            )
        }
        ReiAnixTextField(
            value = draftValue,
            onValueChange = { draftValue = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = ReiAnixTokens.Spacing.sm)
                .semantics { contentDescription = title },
        )

    }
}

@Composable
private fun ChoiceSettingRow(
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            title = { Text(title) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ReiAnixTokens.Dimensions.settingsChoiceDialogMaxHeight),
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
                SettingsDialogButton(
                    text = "Fechar",
                    onClick = { dialogOpen = false },
                )
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
                    horizontal = 20.dp,
                    vertical = 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            SettingsPreferenceText(
                title = title,
                description = if (description.isBlank()) selectedLabel else "$selectedLabel\n$description",
                modifier = Modifier.weight(1f),
            )

        }

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
            if (settings["library.continue_watching"] == "true") "Continuar assistindo" else null,
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
        "Artwork" -> if (settings["artwork.enabled"] == "true") "Baixar capas pela internet ativo" else "Baixar capas pela internet desativado"
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


@Composable
private fun PosterSizeSettingRow(
    selectedValue: String?,
    onSelected: (Int) -> Unit,
) {
    val persistedValue = selectedValue?.toIntOrNull()?.coerceIn(0, 16) ?: 5
    var sliderValue by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableFloatStateOf(persistedValue.toFloat())
    }
    androidx.compose.runtime.LaunchedEffect(persistedValue) {
        sliderValue = persistedValue.toFloat()
    }
    val displayedValue = sliderValue.toInt().coerceIn(0, 16)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 20.dp,
                vertical = 16.dp,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Tamanho dos pôsteres"
                stateDescription = "$displayedValue de 16"
            },
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsPreferenceText(
                title = "Tamanho dos pôsteres",
                description = "Ajusta as capas da Home e da Biblioteca. 0 é o menor tamanho; 16 é o maior.",
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$displayedValue / 16",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it.coerceIn(0f, 16f) },
            valueRange = 0f..16f,
            steps = 15,
            onValueChangeFinished = {
                onSelected(sliderValue.toInt().coerceIn(0, 16))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0 · Menor",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "16 · Maior",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 50.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics { stateDescription = if (enabled) "Habilitado" else "Desabilitado" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

@Composable
private fun SettingsDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
