package com.reiflix.reiflix_local.ui.organize

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixAnimeCard
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixScannerInProgressState
import com.reiflix.reiflix_local.ui.ReiAnixSearchField
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixOrganizeRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    onBack: () -> Unit,
    onOpenStorageAccess: () -> Unit,
    onRequestMediaAccess: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filters by viewModel.organizeFilters.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.organizeVisibleAnimes.collectAsStateWithLifecycle()
    val categories by viewModel.organizeCategories.collectAsStateWithLifecycle()
    val genres by viewModel.organizeGenres.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    ReiAnixOrganizeScreen(
        state = state,
        filters = filters,
        visibleAnimes = visibleAnimes,
        categories = categories,
        genres = genres,
        isRefreshing = isRefreshing,
        onBack = onBack,
        onOpenStorageAccess = onOpenStorageAccess,
        onRequestMediaAccess = onRequestMediaAccess,
        onAddFolder = onAddFolder,
        onRemoveFolder = onRemoveFolder,
        onOpenSettings = onOpenSettings,
        onOpenOverview = viewModel::openOrganizeOverview,
        onQueryChange = viewModel::setOrganizeQuery,
        onStateSelected = viewModel::setOrganizeState,
        onGenreSelected = viewModel::setOrganizeGenre,
        onSortSelected = viewModel::setOrganizeSort,
        onClearFilters = viewModel::clearOrganizeFilters,
        onOpenCollection = viewModel::openOrganizeCollection,
        onOpenDetails = { animeId ->
            navController.navigateToDetails(
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.ORGANIZE,
            )
        },
        onRefresh = viewModel::refresh,
    )
}

@Composable
fun ReiAnixOrganizeScreen(
    state: ReiAnixLibraryUiState,
    filters: ReiAnixOrganizeFilters,
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    categories: List<ReiAnixOrganizeCategory>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onOpenStorageAccess: () -> Unit,
    onRequestMediaAccess: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOverview: () -> Unit,
    onQueryChange: (String) -> Unit,
    onStateSelected: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onSortSelected: (String) -> Unit,
    onClearFilters: () -> Unit,
    onOpenCollection: (String) -> Unit,
    onOpenDetails: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    var pendingSourceRemoval by rememberSaveable { mutableStateOf<String?>(null) }

    ReiAnixResponsiveRoot {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OrganizeTopBar(
            sourceAvailable = state.sourceAvailable,
            isRefreshing = isRefreshing,
            onBack = onBack,
            onOpenStorageAccess = onOpenStorageAccess,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )

        OrganizeSourcesAndScanPanel(
            state = state,
            isRefreshing = isRefreshing,
            onAddFolder = onAddFolder,
            onRemoveFolder = { pendingSourceRemoval = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    vertical = ReiAnixTokens.Spacing.xs,
                ),
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> {
                if (state.scanInProgress) {
                    ReiAnixScannerInProgressState(
                        scanState = state.scanState,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ReiAnixLoadingState(
                        title = "Carregando Organizar",
                        message = "Lendo o catálogo local…",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> {
                ReiAnixSourceUnavailableState(
                    title = "Biblioteca local indisponível",
                    message = "O acesso configurado ao armazenamento não está disponível agora. Isso não significa que a biblioteca esteja vazia.",
                    actionLabel = "Gerenciar armazenamento",
                    onAction = onOpenStorageAccess,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            ReiAnixLibraryLoadStatus.ERROR -> {
                if (state.animes.isEmpty()) {
                    ReiAnixRecoverableErrorState(
                        title = "Não foi possível carregar Organizar",
                        message = state.error ?: state.lastCommandError
                            ?: "A biblioteca local retornou um erro.",
                        onRetry = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    OrganizeCollectionContent(
                        state = state,
                        filters = filters,
                        visibleAnimes = visibleAnimes,
                        genres = genres,
                        isRefreshing = isRefreshing,
                        onQueryChange = onQueryChange,
                        onStateSelected = onStateSelected,
                        onGenreSelected = onGenreSelected,
                        onSortSelected = onSortSelected,
                        onClearFilters = onClearFilters,
                        onOpenOverview = onOpenOverview,
                        onOpenDetails = onOpenDetails,
                        onRefresh = onRefresh,
                        modifier = Modifier.weight(1f),
                        showCatalogError = state.error ?: state.lastCommandError,
                    )
                }
            }

            ReiAnixLibraryLoadStatus.EMPTY -> {
                EmptyOrganizeContent(
                    onRequestMediaAccess = onRequestMediaAccess,
                    onOpenStorageAccess = onOpenStorageAccess,
                    onAddFolder = onAddFolder,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            ReiAnixLibraryLoadStatus.READY -> {
                if (filters.mode == ReiAnixOrganizeMode.OVERVIEW) {
                    OrganizeOverview(
                        state = state,
                        categories = categories,
                        genres = genres,
                        isRefreshing = isRefreshing,
                        onOpenCollection = onOpenCollection,
                        modifier = Modifier.weight(1f),
                        statusMessage = state.lastCommandError,
                    )
                } else {
                    OrganizeCollectionContent(
                        state = state,
                        filters = filters,
                        visibleAnimes = visibleAnimes,
                        genres = genres,
                        isRefreshing = isRefreshing,
                        onQueryChange = onQueryChange,
                        onStateSelected = onStateSelected,
                        onGenreSelected = onGenreSelected,
                        onSortSelected = onSortSelected,
                        onClearFilters = onClearFilters,
                        onOpenOverview = onOpenOverview,
                        onOpenDetails = onOpenDetails,
                        onRefresh = onRefresh,
                        modifier = Modifier.weight(1f),
                        showCatalogError = state.lastCommandError,
                    )
                }
            }
        }
    }

        pendingSourceRemoval?.let { reference ->
            val sourceName = state.storage.configuredSources
                .firstOrNull { it.reference == reference }
                ?.name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "esta pasta"
            AlertDialog(
                onDismissRequest = { pendingSourceRemoval = null },
                title = { Text("Remover fonte da biblioteca?") },
                text = {
                    Text(
                        "“$sourceName” será removida somente da configuração da Biblioteca. " +
                            "Os arquivos e vídeos físicos não serão apagados. " +
                            "Se houver outra fonte contendo o mesmo conteúdo, ele continuará disponível."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingSourceRemoval = null
                            onRemoveFolder(reference)
                        },
                    ) {
                        Text("Remover")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingSourceRemoval = null }) {
                        Text("Cancelar")
                    }
                },
            )
        }

    }
    }
}

@Composable
private fun OrganizeSourcesAndScanPanel(
    state: ReiAnixLibraryUiState,
    isRefreshing: Boolean,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources = state.storage.configuredSources
    val availableEpisodes = state.animes.sumOf { it.availableContentCount }
    val scanStatus = organizeScanStatus(state)

    Surface(
        modifier = modifier,
        shape = ReiAnixTokens.Shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(ReiAnixTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Fontes",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (sources.size) {
                            0 -> "Nenhuma pasta configurada"
                            1 -> "1 pasta configurada"
                            else -> "\${sources.size} pastas configuradas"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    onClick = onAddFolder,
                    enabled = !state.storage.safSelectionPending,
                    shape = ReiAnixTokens.Shapes.button,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.semantics {
                        contentDescription = "Adicionar pasta"
                        role = Role.Button
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = ReiAnixTokens.Spacing.md,
                            vertical = ReiAnixTokens.Spacing.sm,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Text("Adicionar pasta", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Surface(
                shape = ReiAnixTokens.Shapes.card,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(ReiAnixTokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    val icon = when (scanStatus.tone) {
                        ReiAnixBadgeTone.Success -> Icons.Filled.CheckCircle,
                        ReiAnixBadgeTone.Error, ReiAnixBadgeTone.Warning -> Icons.Filled.Warning,
                        else -> Icons.Filled.Info,
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = when (scanStatus.tone) {
                            ReiAnixBadgeTone.Success -> ReiAnixTokens.Colors.success,
                            ReiAnixBadgeTone.Error -> MaterialTheme.colorScheme.error,
                            ReiAnixBadgeTone.Warning -> ReiAnixTokens.Colors.warning,
                            else -> MaterialTheme.colorScheme.primary,
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = scanStatus.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = buildString {
                                append(state.animes.size)
                                append(if (state.animes.size == 1) " anime" else " animes")
                                append(" • ")
                                append(availableEpisodes)
                                append(if (availableEpisodes == 1) " vídeo disponível" else " vídeos disponíveis")
                                if (isRefreshing) append(" • Atualizando")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ReiAnixBadge(text = scanStatus.badge, tone = scanStatus.tone)
                }
            }

            if (sources.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                ) {
                    sources.forEach { source ->
                        key(source.stableKey) {
                            val sourceState = state.storage.sourceState(source)
                            OrganizeSourceRow(
                                sourceName = sourceDisplayName(source.name, source.reference),
                                location = source.reference,
                                stateLabel = organizeSourceStateLabel(sourceState),
                                stateTone = organizeSourceStateTone(sourceState),
                                available = sourceState == "available",
                                removeEnabled = !state.scanInProgress &&
                                    !state.storage.safSelectionPending &&
                                    !state.lastCommandAction.equals("remove_saf", ignoreCase = true),
                                onReauthorize = onAddFolder,
                                onRemove = { onRemoveFolder(source.reference) },
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Adicione uma pasta para alimentar a Biblioteca. A tela permanece disponível enquanto a varredura é executada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class OrganizeScanStatus(
    val label: String,
    val badge: String,
    val tone: ReiAnixBadgeTone,
)

private fun organizeScanStatus(state: ReiAnixLibraryUiState): OrganizeScanStatus {
    val configuredSourcesHaveLostAccess = state.storage.configuredSources.any {
        state.storage.sourceState(it) in setOf("revoked", "unavailable", "error")
    }
    if (configuredSourcesHaveLostAccess && !state.sourceAvailable) {
        return OrganizeScanStatus("Permissão necessária", "Atenção", ReiAnixBadgeTone.Warning)
    }
    if (state.scanInProgress) {
        return OrganizeScanStatus("Processando", "Ativo", ReiAnixBadgeTone.Info)
    }
    return when (state.scanState.uppercase()) {
        "COMPLETED", "EMPTY_COMPLETE" ->
            OrganizeScanStatus("Concluído", "OK", ReiAnixBadgeTone.Success)
        "PARTIAL" ->
            OrganizeScanStatus("Concluído parcialmente", "Parcial", ReiAnixBadgeTone.Warning)
        "FAILED", "ERROR" ->
            OrganizeScanStatus("Erro", "Erro", ReiAnixBadgeTone.Error)
        "CANCELLED" ->
            OrganizeScanStatus("Cancelado", "Cancelado", ReiAnixBadgeTone.Warning)
        else ->
            OrganizeScanStatus("Aguardando", "Pronto", ReiAnixBadgeTone.Info)
    }
}

@Composable
private fun OrganizeSourceRow(
    sourceName: String,
    location: String,
    stateLabel: String,
    stateTone: ReiAnixBadgeTone,
    available: Boolean,
    removeEnabled: Boolean,
    onReauthorize: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = ReiAnixTokens.Shapes.card,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            Icon(
                imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (available) ReiAnixTokens.Colors.success else ReiAnixTokens.Colors.warning,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!location.startsWith("content://", ignoreCase = true) && location.isNotBlank()) {
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (!available) {
                    Text(
                        text = "Acesso da pasta não está disponível.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            ReiAnixBadge(text = stateLabel, tone = stateTone)
            IconButton(
                onClick = onReauthorize,
                modifier = Modifier.semantics {
                    contentDescription = if (available) "Adicionar outra pasta" else "Reautorizar $sourceName"
                },
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
            IconButton(
                onClick = onRemove,
                enabled = removeEnabled,
                modifier = Modifier.semantics {
                    contentDescription = "Remover $sourceName da Biblioteca"
                },
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
            }
        }
    }
}

private fun sourceDisplayName(name: String, reference: String): String {
    val normalized = name.trim()
    if (
        normalized.isNotEmpty() &&
        !normalized.startsWith("content://", ignoreCase = true) &&
        !normalized.startsWith("file://", ignoreCase = true)
    ) return normalized
    val fallback = reference.substringAfterLast('/')
        .replace("%3A", ":", ignoreCase = true)
        .replace("%20", " ")
        .trim()
    return fallback.ifBlank { "Pasta da biblioteca" }
}

private fun organizeSourceStateLabel(state: String): String = when (state.lowercase()) {
    "available", "granted", "full" -> "Acesso concedido"
    "partial" -> "Acesso parcial"
    "revoked" -> "Permissão necessária"
    "unavailable", "error" -> "Indisponível"
    else -> "Verificando"
}

private fun organizeSourceStateTone(state: String): ReiAnixBadgeTone = when (state.lowercase()) {
    "available", "granted", "full" -> ReiAnixBadgeTone.Success
    "partial", "revoked", "unavailable", "error" -> ReiAnixBadgeTone.Warning
    else -> ReiAnixBadgeTone.Info
}

@Composable
private fun OrganizeTopBar(
    sourceAvailable: Boolean,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onOpenStorageAccess: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.topBarMinHeight)
            .padding(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                vertical = ReiAnixTokens.Spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics {
                contentDescription = "Voltar"
                role = Role.Button
            },
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = null)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Organizar",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = ReiAnixTokens.Shapes.chip,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = if (sourceAvailable) "Offline" else "Indisponível",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = ReiAnixTokens.Spacing.sm,
                            vertical = ReiAnixTokens.Spacing.xs,
                        ),
                    )
                }
                Text(
                    text = "Biblioteca local",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        IconButton(
            onClick = onOpenStorageAccess,
            modifier = Modifier.semantics {
                contentDescription = "Gerenciar acesso ao armazenamento"
            },
        ) {
            Icon(Icons.Filled.Info, contentDescription = null)
        }
        IconButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
            modifier = Modifier.semantics {
                contentDescription = if (isRefreshing) {
                    "Atualizando biblioteca"
                } else {
                    "Atualizar biblioteca"
                }
            },
        ) {
            if (isRefreshing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(ReiAnixTokens.Dimensions.loadingIndicatorSize),
                    strokeWidth = ReiAnixTokens.Dimensions.loadingIndicatorStroke,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.semantics {
                contentDescription = "Abrir configurações"
            },
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
        }
    }
}

@Composable
private fun OrganizeOverview(
    state: ReiAnixLibraryUiState,
    categories: List<ReiAnixOrganizeCategory>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
    statusMessage: String?,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            vertical = ReiAnixTokens.Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.lg),
    ) {
        if (state.scanInProgress) {
            item(key = "scan-status") {
                ReiAnixScannerInProgressState(
                    scanState = state.scanState,
                    compact = true,
                )
            }
        } else if (state.scanState.equals("COMPLETED", ignoreCase = true)) {
            item(key = "scan-completed") {
                OrganizeStatusBanner(
                    icon = Icons.Filled.CheckCircle,
                    message = "Atualização da biblioteca concluída.",
                    tone = ReiAnixBadgeTone.Success,
                )
            }
        } else if (state.scanState.equals("PARTIAL", ignoreCase = true)) {
            item(key = "scan-partial") {
                OrganizeStatusBanner(
                    icon = Icons.Filled.Warning,
                    message = "A atualização terminou parcialmente; a biblioteca exibida permanece na fonte de verdade local.",
                    tone = ReiAnixBadgeTone.Warning,
                )
            }
        } else if (state.scanState.equals("FAILED", ignoreCase = true)) {
            item(key = "scan-failed") {
                OrganizeStatusBanner(
                    icon = Icons.Filled.Warning,
                    message = state.lastCommandError ?: "A atualização da biblioteca falhou.",
                    tone = ReiAnixBadgeTone.Error,
                )
            }
        }

        statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
            item(key = "command-error") {
                OrganizeStatusBanner(
                    icon = Icons.Filled.Warning,
                    message = message,
                    tone = ReiAnixBadgeTone.Error,
                )
            }
        }

        item(key = "categories-title") {
            OrganizeSectionHeader(
                title = "Categorias",
                subtitle = "Estados já existentes no catálogo local",
                icon = Icons.Filled.Settings,
            )
        }

        item(key = "categories-row") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                contentPadding = PaddingValues(horizontal = ReiAnixTokens.Spacing.sm),
            ) {
                items(
                    items = categories,
                    key = { "category:" + it.label },
                    contentType = { "organize-category" },
                ) { category ->
                    OrganizeCategoryCard(
                        category = category,
                        onClick = { onOpenCollection(category.label) },
                    )
                }
            }
        }

        if (genres.isNotEmpty()) {
            item(key = "genres-title") {
                OrganizeSectionHeader(
                    title = "Gêneros",
                    subtitle = "Agrupamentos vindos do catálogo real",
                    icon = Icons.Filled.Info,
                )
            }
            item(key = "genres-row") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                    contentPadding = PaddingValues(end = ReiAnixTokens.Spacing.sm),
                ) {
                    items(
                        items = genres,
                        key = { "genre:" + it.stableKey },
                        contentType = { "organize-genre" },
                    ) { genre ->
                        Surface(
                            modifier = Modifier
                                .width(ReiAnixTokens.Dimensions.organizeGenreCardWidth)
                                .semantics {
                                    contentDescription = "Gênero " + genre.name
                                },
                            shape = ReiAnixTokens.Shapes.card,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            onClick = { onOpenCollection(genre.stableKey) },
                        ) {
                            Column(
                                modifier = Modifier.padding(ReiAnixTokens.Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                            ) {
                                Text(
                                    text = genre.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Filtrar biblioteca local",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "overview-total") {
            Surface(
                shape = ReiAnixTokens.Shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(ReiAnixTokens.Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Catálogo local",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = categories
                                .firstOrNull { it.label == ReiAnixOrganizeFilters.DEFAULT_STATE }
                                ?.let { if (it.count == 1) "1 anime disponível" else it.count.toString() + " animes disponíveis" }
                                ?: "Catálogo local carregado",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReiAnixBadge(
                        text = if (isRefreshing) "Atualizando" else "Pronto",
                        tone = if (isRefreshing) ReiAnixBadgeTone.Info else ReiAnixBadgeTone.Success,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrganizeCollectionContent(
    state: ReiAnixLibraryUiState,
    filters: ReiAnixOrganizeFilters,
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean,
    onQueryChange: (String) -> Unit,
    onStateSelected: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onSortSelected: (String) -> Unit,
    onClearFilters: () -> Unit,
    onOpenOverview: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    showCatalogError: String?,
) {
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var genreMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = LocalReiAnixResponsiveMetrics.current.libraryGridMinWidth("medium")),
        state = gridState,
        modifier = modifier
            .widthIn(max = LocalReiAnixResponsiveMetrics.current.contentMaxWidth)
            .fillMaxWidth()
            .imePadding(),
        contentPadding = PaddingValues(
            horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            vertical = ReiAnixTokens.Spacing.sm,
        ),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.lg),
    ) {
        item(
            key = "organize-search",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                ReiAnixSearchField(
                    value = filters.query,
                    onValueChange = onQueryChange,
                    accessibilityLabel = "Pesquisar na organização",
                    placeholder = {
                        Text("Buscar título, gênero, alias ou episódio")
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (filters.query.isNotBlank()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.semantics {
                                    contentDescription = "Limpar busca"
                                },
                            ) {
                                Icon(Icons.Filled.Clear, contentDescription = null)
                            }
                        }
                    },
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                    contentPadding = PaddingValues(end = ReiAnixTokens.Spacing.sm),
                ) {
                    items(
                        items = ReiAnixOrganizeFilters.states,
                        key = { "state:" + it },
                        contentType = { "organize-state-chip" },
                    ) { stateLabel ->
                        ReiAnixChip(
                            text = stateLabel,
                            selected = stateLabel == filters.state,
                            onClick = { onStateSelected(stateLabel) },
                            modifier = Modifier.semantics {
                                contentDescription = "Filtro " + stateLabel
                            },
                        )
                    }
                }
            }
        }

        item(
            key = "organize-filter-row",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReiAnixChip(
                    text = "Visão geral",
                    onClick = onOpenOverview,
                    modifier = Modifier.semantics {
                        contentDescription = "Voltar para visão geral"
                    },
                )
                Box {
                    ReiAnixChip(
                        text = filters.genreKey?.let { key ->
                            genres.firstOrNull { it.stableKey == key }?.name ?: "Gênero"
                        } ?: "Gênero",
                        selected = filters.genreKey != null,
                        onClick = { genreMenuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Selecionar gênero"
                        },
                    )
                    DropdownMenu(
                        expanded = genreMenuExpanded,
                        onDismissRequest = { genreMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Todos") },
                            onClick = {
                                genreMenuExpanded = false
                                onGenreSelected(null)
                            },
                        )
                        genres.forEach { genre ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        genre.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = {
                                    genreMenuExpanded = false
                                    onGenreSelected(genre.stableKey)
                                },
                            )
                        }
                    }
                }

                Box {
                    ReiAnixChip(
                        text = filters.sort,
                        onClick = { sortMenuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Ordenar. Opção atual: " + filters.sort
                        },
                    )
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        com.reiflix.reiflix_local.ui.library.ReiAnixLibrarySort.OPTIONS.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label) },
                                onClick = {
                                    sortMenuExpanded = false
                                    onSortSelected(sort.label)
                                },
                            )
                        }
                    }
                }

                if (
                    filters.query.isNotBlank() ||
                    filters.state != ReiAnixOrganizeFilters.DEFAULT_STATE ||
                    filters.genreKey != null ||
                    filters.sort != ReiAnixOrganizeFilters.DEFAULT_SORT
                ) {
                    ReiAnixChip(
                        text = "Limpar",
                        onClick = onClearFilters,
                    )
                }
            }
        }

        if (state.scanInProgress) {
            item(
                key = "organize-scan",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                ReiAnixScannerInProgressState(
                    scanState = state.scanState,
                    compact = true,
                )
            }
        } else if (state.scanState.equals("PARTIAL", ignoreCase = true)) {
            item(
                key = "organize-partial",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                OrganizeStatusBanner(
                    icon = Icons.Filled.Warning,
                    message = "A atualização terminou parcialmente.",
                    tone = ReiAnixBadgeTone.Warning,
                )
            }
        } else if (state.scanState.equals("FAILED", ignoreCase = true)) {
            item(
                key = "organize-scan-error",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                OrganizeStatusBanner(
                    icon = Icons.Filled.Warning,
                    message = state.lastCommandError ?: "A atualização da biblioteca falhou.",
                    tone = ReiAnixBadgeTone.Error,
                )
            }
        }

        item(
            key = "organize-summary",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            val selectedGenreName = filters.genreKey
                ?.let { key -> genres.firstOrNull { it.stableKey == key }?.name }
            Text(
                text = buildString {
                    append(
                        if (visibleAnimes.size == 1) "1 resultado" else visibleAnimes.size.toString() + " resultados",
                    )
                    filters.state.takeIf { it != ReiAnixOrganizeFilters.DEFAULT_STATE }
                        ?.let { append(" • "); append(it) }
                    selectedGenreName?.let { append(" • "); append(it) }
                    filters.sort.takeIf { it != ReiAnixOrganizeFilters.DEFAULT_SORT }
                        ?.let { append(" • "); append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "Resumo da lista de organização"
                },
            )
        }

        if (showCatalogError?.isNotBlank() == true) {
            item(
                key = "organize-error",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                OrganizeStatusBanner(
                    icon = Icons.Filled.Warning,
                    message = showCatalogError,
                    tone = ReiAnixBadgeTone.Error,
                )
            }
        }

        if (visibleAnimes.isEmpty()) {
            item(
                key = "organize-filter-empty",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                ReiAnixEmptyState(
                    title = "Nenhum item corresponde aos filtros",
                    message = "A biblioteca local possui dados, mas nenhuma obra corresponde à combinação atual.",
                    actionLabel = "Limpar filtros",
                    onAction = onClearFilters,
                    icon = Icons.Filled.Search,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            items(
                items = visibleAnimes,
                key = { it.stableKey },
                contentType = { "organize-anime-card" },
            ) { anime ->
                ReiAnixAnimeCard(
                    anime = anime,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenDetails(anime.id) },
                    maxDimensionPx = 320,
                )
            }
        }
    }
}

@Composable
private fun EmptyOrganizeContent(
    onRequestMediaAccess: () -> Unit,
    onOpenStorageAccess: () -> Unit,
    onAddFolder: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LocalReiAnixResponsiveMetrics.current.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        ReiAnixEmptyLibraryState(
            title = "Seu catálogo está vazio",
            message = "Nenhum conteúdo local está disponível neste momento. Isso é diferente de uma falha de acesso.",
            actionLabel = "Selecionar pasta",
            onAction = onAddFolder,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            EmptyAction(
                label = "Permitir vídeos",
                onClick = onRequestMediaAccess,
                icon = Icons.Filled.Info,
                modifier = Modifier.weight(1f),
            )
            EmptyAction(
                label = "Acesso amplo",
                onClick = onOpenStorageAccess,
                icon = Icons.Filled.Info,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ReiAnixTokens.Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            EmptyAction(
                label = "Atualizar",
                onClick = onRefresh,
                icon = Icons.Filled.Refresh,
                modifier = Modifier.weight(1f),
            )
            EmptyAction(
                label = "Configurações",
                onClick = onOpenSettings,
                icon = Icons.Filled.Settings,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmptyAction(
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = ReiAnixTokens.Dimensions.buttonMinHeight)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        onClick = onClick,
        shape = ReiAnixTokens.Shapes.button,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.Spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(ReiAnixTokens.Spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OrganizeCategoryCard(
    category: ReiAnixOrganizeCategory,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(ReiAnixTokens.Dimensions.organizeCategoryCardWidth)
            .semantics {
                contentDescription = category.label + ": " + category.count + " animes"
                role = Role.Button
            },
        onClick = onClick,
        shape = ReiAnixTokens.Shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(ReiAnixTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            Text(
                text = category.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (category.count == 1) "1 anime" else category.count.toString() + " animes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OrganizeSectionHeader(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OrganizeStatusBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    tone: ReiAnixBadgeTone,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ReiAnixTokens.Shapes.large,
        color = when (tone) {
            ReiAnixBadgeTone.Error -> MaterialTheme.colorScheme.errorContainer
            ReiAnixBadgeTone.Warning -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(ReiAnixTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = when (tone) {
                    ReiAnixBadgeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
                    ReiAnixBadgeTone.Warning -> ReiAnixTokens.Colors.warning
                    ReiAnixBadgeTone.Success -> ReiAnixTokens.Colors.success
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
            )
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
