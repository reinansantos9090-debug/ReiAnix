package com.reiflix.reiflix_local.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.flow.collect
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.reiflix.reiflix_local.ui.ReiAnixAnimeCard
import com.reiflix.reiflix_local.ui.ReiAnixIconActionButton
import com.reiflix.reiflix_local.ui.ReiAnixTopBar
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixCompactButton
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSearchField
import com.reiflix.reiflix_local.ui.ReiAnixScannerInProgressState
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.presentationStateLabel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixLibraryRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    cardSize: String = "medium",
    gridDensity: String = "medium",
    pageSize: Int = 36,
    defaultSortKey: String = "added_desc",
) {
    LaunchedEffect(pageSize) {
        viewModel.applyLibraryPageSize(pageSize)
    }
    LaunchedEffect(defaultSortKey) {
        viewModel.applyLibrarySortDefault(defaultSortKey)
    }

    val baseState by viewModel.libraryPresentationState.collectAsStateWithLifecycle()
    val pageState by viewModel.pagedLibraryState.collectAsStateWithLifecycle()
    val filters by viewModel.libraryFilters.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.filteredLibraryAnimes.collectAsStateWithLifecycle()
    val genres by viewModel.libraryGenres.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val state = baseState

    ReiAnixLibraryPresentationScreen(
        state = state,
        filters = filters,
        visibleAnimes = visibleAnimes,
        genres = genres,
        cardSize = cardSize,
        gridDensity = gridDensity,
        isRefreshing = isRefreshing,
        onQueryChange = viewModel::setLibrarySearchQuery,
        onGenreSelected = viewModel::setLibraryGenreFilter,
        onToggleFavorites = viewModel::toggleLibraryFavoritesFilter,
        onToggleWatching = viewModel::toggleLibraryWatchingFilter,
        onToggleCompleted = viewModel::toggleLibraryCompletedFilter,
        onSortSelected = viewModel::setLibrarySort,
        onClearFilters = viewModel::clearLibraryFilters,
        onRefresh = viewModel::refresh,
        onSelectSource = viewModel::selectSafTree,
        onSearch = {
            navController.navigateToTopLevel(ReiAnixRoutes.SEARCH)
        },
        onOpenStorage = {
            navController.navigateToTopLevel(ReiAnixRoutes.SETTINGS)
        },
        onOpenDetails = { animeId ->
            navController.navigateToDetails(
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.LIBRARY,
            )
        },
        onToggleFavorite = viewModel::toggleFavorite,
        hasMore = pageState.hasMore,
        isLoadingMore = pageState.isLoading && visibleAnimes.isNotEmpty(),
        onLoadMore = viewModel::loadNextLibraryPage,
    )
}

@Composable
fun ReiAnixLibraryRoute(
    viewModel: ReiAnixLibraryViewModel,
    onOpenDetails: (Long) -> Unit,
) {
    val baseState by viewModel.libraryPresentationState.collectAsStateWithLifecycle()
    val pageState by viewModel.pagedLibraryState.collectAsStateWithLifecycle()
    val filters by viewModel.libraryFilters.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.filteredLibraryAnimes.collectAsStateWithLifecycle()
    val genres by viewModel.libraryGenres.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val state = baseState

    ReiAnixLibraryPresentationScreen(
        state = state,
        filters = filters,
        visibleAnimes = visibleAnimes,
        genres = genres,
        isRefreshing = isRefreshing,
        onQueryChange = viewModel::setLibrarySearchQuery,
        onGenreSelected = viewModel::setLibraryGenreFilter,
        onToggleFavorites = viewModel::toggleLibraryFavoritesFilter,
        onToggleWatching = viewModel::toggleLibraryWatchingFilter,
        onToggleCompleted = viewModel::toggleLibraryCompletedFilter,
        onSortSelected = viewModel::setLibrarySort,
        onClearFilters = viewModel::clearLibraryFilters,
        onRefresh = viewModel::refresh,
        onSelectSource = viewModel::selectSafTree,
        onOpenDetails = onOpenDetails,
        onToggleFavorite = viewModel::toggleFavorite,
        hasMore = pageState.hasMore,
        isLoadingMore = pageState.isLoading && pageState.animes.isNotEmpty(),
        onLoadMore = viewModel::loadNextLibraryPage,
    )
}

/**
 * Compatibility overload for existing callers/tests that still provide the
 * canonical full library state. Production navigation uses the compact
 * presentation projection above so progress-only catalog changes do not force
 * unrelated source-state UI to observe the full snapshot.
 */
@Composable
fun ReiAnixLibraryScreen(
    state: ReiAnixLibraryUiState,
    filters: ReiAnixLibraryFilters,
    cardSize: String = "medium",
    gridDensity: String = "medium",
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean = false,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSortSelected: (String) -> Unit = {},
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    onOpenStorage: (() -> Unit)? = null,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onSelectSource: () -> Unit = onRefresh,
) {
    val presentation = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPresentationUiState(
        status = state.status,
        sourceAvailable = state.sourceAvailable,
        sourceState = state.sourceState,
        scanInProgress = state.scanInProgress,
        scanState = state.scanState,
        error = state.error,
        animeCount = state.animes.size,
        availableEpisodeCount = state.animes.sumOf { it.availableContentCount },
        favoriteCount = state.animes.count { it.favorite },
    )
    ReiAnixLibraryPresentationScreen(
        state = presentation,
        filters = filters,
        cardSize = cardSize,
        gridDensity = gridDensity,
        visibleAnimes = visibleAnimes,
        genres = genres,
        isRefreshing = isRefreshing,
        onQueryChange = onQueryChange,
        onGenreSelected = onGenreSelected,
        onToggleFavorites = onToggleFavorites,
        onToggleWatching = onToggleWatching,
        onToggleCompleted = onToggleCompleted,
        onSortSelected = onSortSelected,
        onClearFilters = onClearFilters,
        onRefresh = onRefresh,
        onSelectSource = onSelectSource,
        onOpenDetails = onOpenDetails,
        onToggleFavorite = onToggleFavorite,
        onSearch = onSearch,
        onOpenStorage = onOpenStorage,
    )
}

@Composable
private fun ReiAnixLibraryPresentationScreen(
    state: com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPresentationUiState,
    filters: ReiAnixLibraryFilters,
    cardSize: String = "medium",
    gridDensity: String = "medium",
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean = false,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSortSelected: (String) -> Unit = {},
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    onOpenStorage: (() -> Unit)? = null,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onSelectSource: () -> Unit = onRefresh,
) {
    ReiAnixResponsiveRoot {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LibraryHeader(
            sourceAvailable = state.sourceAvailable,
            onSearch = onSearch,
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
            onOpenStorage = onOpenStorage,
        )

        val renderReadyContent: @Composable () -> Unit = {
            LibraryReadyContent(
                state = state,
                filters = filters,
                visibleAnimes = visibleAnimes,
                genres = genres,
                isRefreshing = isRefreshing,
                onQueryChange = onQueryChange,
                onGenreSelected = onGenreSelected,
                onToggleFavorites = onToggleFavorites,
                onToggleWatching = onToggleWatching,
                onToggleCompleted = onToggleCompleted,
                onSortSelected = onSortSelected,
                onClearFilters = onClearFilters,
                onOpenDetails = onOpenDetails,
                onToggleFavorite = onToggleFavorite,
                onRefresh = onRefresh,
                hasMore = hasMore,
                isLoadingMore = isLoadingMore,
                onLoadMore = onLoadMore,
                errorMessage = state.error,
                modifier = Modifier.weight(1f),
            )
        }

        // A non-empty canonical snapshot is usable even while the source is
        // being checked or the scanner is reconciling it. Never replace that
        // catalog with a full-screen loading state.
        if (state.animeCount > 0) {
            renderReadyContent()
        } else if (state.scanInProgress) {
            // An initial scan is not an empty library. Keep the state compact
            // and never claim the catalog is empty until reconciliation settles.
            ReiAnixScannerInProgressState(
                scanState = state.scanState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            when (state.status) {
                ReiAnixLibraryLoadStatus.LOADING -> LibraryLoadingGrid(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                ReiAnixLibraryLoadStatus.ERROR -> ReiAnixRecoverableErrorState(
                    title = "Erro na biblioteca",
                    message = state.error ?: "Não foi possível carregar a biblioteca local.",
                    onRetry = onRefresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> ReiAnixSourceUnavailableState(
                    title = "Biblioteca local indisponível",
                    message = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                        "Configure uma pasta local para acessar sua biblioteca."
                    } else {
                        "A fonte local configurada não está disponível agora."
                    },
                    actionLabel = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                        "Selecionar pasta"
                    } else {
                        "Atualizar"
                    },
                    onAction = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                        onSelectSource
                    } else {
                        onRefresh
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyLibraryState(
                    title = "Biblioteca vazia",
                    message = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                        "Configure uma pasta de mídia para começar."
                    } else {
                        "Adicione uma pasta de mídia para começar."
                    },
                    actionLabel = when {
                        state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true) -> "Selecionar pasta"
                        onOpenStorage != null -> "Adicionar pasta"
                        else -> "Atualizar biblioteca"
                    },
                    onAction = when {
                        state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true) -> onSelectSource
                        else -> onOpenStorage ?: onRefresh
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                ReiAnixLibraryLoadStatus.READY -> renderReadyContent()
            }
        }
    }

    }
}

@Composable
private fun LibraryHeader(
    sourceAvailable: Boolean,
    onSearch: (() -> Unit)?,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    onOpenStorage: (() -> Unit)?,
) {
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }
    ReiAnixTopBar(
        title = "Biblioteca",
        actions = {
            if (onSearch != null) {
                ReiAnixIconActionButton(
                    icon = Icons.Filled.Search,
                    contentDescription = "Pesquisar na biblioteca",
                    onClick = onSearch,
                )
            }
            Box {
                ReiAnixIconActionButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Mais ações da biblioteca",
                    onClick = { actionsExpanded = true },
                )
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isRefreshing) "Atualizando biblioteca…" else "Atualizar biblioteca",
                            )
                        },
                        enabled = !isRefreshing,
                        onClick = {
                            actionsExpanded = false
                            onRefresh()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                            )
                        },
                    )
                    if (onOpenStorage != null) {
                        DropdownMenuItem(
                            text = { Text("Adicionar ou alterar pasta") },
                            onClick = {
                                actionsExpanded = false
                                onOpenStorage()
                            },
                        )
                    }
                }
            }
        },
    )
    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.sm))
}

@Composable
private fun ColumnScope.LibraryReadyContent(
    state: com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPresentationUiState,
    cardSize: String = "medium",
    gridDensity: String = "medium",
    filters: ReiAnixLibraryFilters,
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSortSelected: (String) -> Unit,
    onClearFilters: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onRefresh: () -> Unit,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var genreMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()
    val availableEpisodeCount = state.availableEpisodeCount
    val gridState = rememberSaveable(
        saver = LazyGridState.Saver,
    ) {
        LazyGridState()
    }

    LaunchedEffect(gridState, visibleAnimes.size, hasMore, isLoadingMore) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        }.collect { lastVisibleIndex ->
            val total = gridState.layoutInfo.totalItemsCount
            if (
                hasMore &&
                !isLoadingMore &&
                lastVisibleIndex != null &&
                total > 0 &&
                lastVisibleIndex >= total - 6
            ) {
                onLoadMore()
            }
        }
    }

    PullToRefreshBox(
        modifier = modifier
            .widthIn(max = LocalReiAnixResponsiveMetrics.current.contentMaxWidth)
            .fillMaxWidth(),
        state = refreshState,
        isRefreshing = isRefreshing,
        onRefresh = {
            if (!isRefreshing) onRefresh()
        },
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter),
                state = refreshState,
                isRefreshing = isRefreshing,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(
                minSize = LocalReiAnixResponsiveMetrics.current.libraryGridMinWidth(cardSize),
            ),
            state = gridState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                top = ReiAnixTokens.Spacing.xs,
                bottom = ReiAnixTokens.Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(libraryGridSpacing(gridDensity)),
            horizontalArrangement = Arrangement.spacedBy(libraryGridSpacing(gridDensity)),
        ) {
            if (!errorMessage.isNullOrBlank()) {
                item(
                    key = "library-error-banner",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "library-error-banner",
                ) {
                    ReiAnixRecoverableErrorState(
                        title = "Atualização indisponível",
                        message = errorMessage,
                        onRetry = onRefresh,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(
                key = "library-search",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "library-search",
            ) {
                ReiAnixSearchField(
                    value = filters.query,
                    onValueChange = onQueryChange,
                    accessibilityLabel = "Pesquisar na biblioteca",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Título ou gênero") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (filters.query.isNotBlank()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.semantics {
                                    contentDescription = "Limpar pesquisa"
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                )
            }

            item(
                key = "library-filters",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "library-filters",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                        contentPadding = PaddingValues(end = ReiAnixTokens.Spacing.sm),
                    ) {
                        item(key = "filter-genre") {
                            Box {
                                ReiAnixChip(
                                    text = "Gêneros",
                                    selected = filters.selectedGenreKey != null,
                                    enabled = genres.isNotEmpty(),
                                    onClick = { genreMenuExpanded = true },
                                    modifier = Modifier.semantics {
                                        contentDescription = if (filters.selectedGenreKey == null) {
                                            "Gêneros"
                                        } else {
                                            "Gênero selecionado"
                                        }
                                    },
                                )
                                if (genreMenuExpanded) {
                                    androidx.compose.material3.AlertDialog(
                                        onDismissRequest = { genreMenuExpanded = false },
                                        title = { Text("Filtrar por gênero") },
                                        text = {
                                            androidx.compose.foundation.lazy.LazyColumn(
                                                modifier = Modifier.heightIn(
                                                    max = ReiAnixTokens.Dimensions.libraryGenrePickerMaxHeight,
                                                ),
                                            ) {
                                                item(key = "genre-all") {
                                                    DropdownMenuItem(
                                                        text = { Text("Todos os gêneros") },
                                                        onClick = {
                                                            genreMenuExpanded = false
                                                            onGenreSelected(null)
                                                        },
                                                        trailingIcon = if (filters.selectedGenreKey == null) {
                                                            {
                                                                Text(
                                                                    text = "✓",
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    style = MaterialTheme.typography.labelLarge,
                                                                )
                                                            }
                                                        } else {
                                                            null
                                                        },
                                                    )
                                                }
                                                items(
                                                    items = genres,
                                                    key = { genre -> genre.stableKey },
                                                ) { genre ->
                                                    DropdownMenuItem(
                                                        text = { Text(genre.name) },
                                                        onClick = {
                                                            genreMenuExpanded = false
                                                            onGenreSelected(genre.stableKey)
                                                        },
                                                        trailingIcon = if (filters.selectedGenreKey == genre.stableKey) {
                                                            {
                                                                Text(
                                                                    text = "✓",
                                                                    color = MaterialTheme.colorScheme.primary,
                                                                    style = MaterialTheme.typography.labelLarge,
                                                                )
                                                            }
                                                        } else {
                                                            null
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            ReiAnixCompactButton(
                                                text = "Fechar",
                                                onClick = { genreMenuExpanded = false },
                                            )
                                        },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    )
                                }
                            }
                        }
                        item(key = "filter-favorite") {
                            LibraryFilterChip(
                                text = "Favoritos",
                                selected = filters.favoritesOnly,
                                onClick = onToggleFavorites,
                            )
                        }
                        item(key = "filter-watching") {
                            LibraryFilterChip(
                                text = "Assistindo",
                                selected = filters.watchingOnly,
                                onClick = onToggleWatching,
                            )
                        }
                        item(key = "filter-completed") {
                            LibraryFilterChip(
                                text = "Completos",
                                selected = filters.completedOnly,
                                onClick = onToggleCompleted,
                            )
                        }
                    }

                    Box {
                        ReiAnixChip(
                            text = "Ordenar",
                            selected = filters.sort != ReiAnixLibrarySort.DEFAULT.label,
                            onClick = { sortMenuExpanded = true },
                            modifier = Modifier.semantics {
                                contentDescription = "Ordenar. Opção atual: " + filters.sort
                            },
                        )
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            ReiAnixLibrarySort.OPTIONS.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        sortMenuExpanded = false
                                        onSortSelected(option.label)
                                    },
                                    trailingIcon = if (filters.sort == option.label) {
                                        {
                                            Text(
                                                text = "✓",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (filters.hasAnyFilter) {
                item(
                    key = "library-filter-summary",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "library-filter-summary",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = libraryResultLabel(visibleAnimes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.xs))
                        ReiAnixChip(
                            text = "Limpar",
                            onClick = onClearFilters,
                            modifier = Modifier.semantics {
                                contentDescription = "Limpar filtros"
                            },
                        )
                    }
                }
            }

            if (state.scanInProgress) {
                item(
                    key = "library-scanner",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "library-scanner",
                ) {
                    ReiAnixScannerInProgressState(
                        scanState = state.scanState,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (visibleAnimes.isEmpty()) {
                item(
                    key = "library-empty-results",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "library-empty-results",
                ) {
                    ReiAnixEmptyLibraryState(
                        title = if (filters.hasAnyFilter) {
                            "Nenhum resultado"
                        } else {
                            "Nenhum conteúdo"
                        },
                        message = if (filters.hasAnyFilter) {
                            "Nenhum título corresponde aos filtros atuais."
                        } else {
                            "A biblioteca local ainda não possui conteúdo visível."
                        },
                        actionLabel = if (filters.hasAnyFilter) "Limpar filtros" else null,
                        onAction = if (filters.hasAnyFilter) onClearFilters else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = ReiAnixTokens.Spacing.lg),
                    )
                }
            } else {
                items(
                    items = visibleAnimes,
                    key = { anime -> anime.stableKey },
                    contentType = { "library-anime-card" },
                ) { anime ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LibraryAnimeCard(
                            anime = anime,
                            onClick = { onOpenDetails(anime.id) },
                            onToggleFavorite = { onToggleFavorite(anime.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = ReiAnixTokens.Dimensions.gridMaxItemWidth),
                        )
                    }
                }
                if (isLoadingMore && visibleAnimes.isNotEmpty()) {
                    item(
                        key = "library-loading-more",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "library-loading-more",
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = ReiAnixTokens.Spacing.lg),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.reiflix.reiflix_local.ui.ReiAnixLoadingIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun libraryGridMinWidth(preference: String): androidx.compose.ui.unit.Dp =
    LocalReiAnixResponsiveMetrics.current.libraryGridMinWidth(preference)

private fun libraryGridSpacing(preference: String): androidx.compose.ui.unit.Dp =
    when (preference.trim().lowercase()) {
        "small" -> ReiAnixTokens.Dimensions.libraryGridSpacingRelaxed
        "large" -> ReiAnixTokens.Dimensions.libraryGridSpacingDense
        else -> ReiAnixTokens.Dimensions.libraryGridSpacingBalanced
    }

@Composable
private fun LibrarySourceSummaryCard(
    animeCount: Int,
    episodeCount: Int,
    sourceAvailable: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ReiAnixTokens.Shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Surface(
                shape = ReiAnixTokens.Shapes.card,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(ReiAnixTokens.Dimensions.touchTarget)
                        .padding(ReiAnixTokens.Spacing.sm),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = "Capas sincronizadas",
                    style = ReiAnixTokens.TypographyTokens.sectionTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = librarySourceCountLabel(animeCount, episodeCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                ReiAnixBadge(
                    text = if (sourceAvailable) "Funciona offline" else "Fonte indisponível",
                    tone = if (sourceAvailable) ReiAnixBadgeTone.Success else ReiAnixBadgeTone.Warning,
                )
            }

            Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
            )
        }
    }
}

private fun librarySourceCountLabel(animeCount: Int, episodeCount: Int): String =
    animeCount.toString() + " " + if (animeCount == 1) "anime" else "animes" +
        " • " + episodeCount.toString() + " " +
        if (episodeCount == 1) "episódio" else "episódios"

@Composable
private fun LibraryLoadingGrid(
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(
            minSize = LocalReiAnixResponsiveMetrics.current.libraryGridMinWidth("medium"),
        ),
        modifier = modifier
            .widthIn(max = LocalReiAnixResponsiveMetrics.current.contentMaxWidth)
            .fillMaxWidth(),
        userScrollEnabled = false,
        contentPadding = PaddingValues(
            start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            top = ReiAnixTokens.Spacing.sm,
            bottom = ReiAnixTokens.Spacing.xxl,
        ),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        items(
            count = 6,
            key = { index -> "library-skeleton-" + index },
            contentType = { "library-skeleton" },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ReiAnixTokens.Dimensions.posterAspectRatio)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = ReiAnixTokens.Shapes.artwork,
                        ),
                )
                Box(
                    modifier = Modifier
                        .padding(top = ReiAnixTokens.Spacing.sm)
                        .fillMaxWidth(0.78f)
                        .height(ReiAnixTokens.Spacing.md)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = ReiAnixTokens.Shapes.small,
                        ),
                )
                Box(
                    modifier = Modifier
                        .padding(top = ReiAnixTokens.Spacing.xs)
                        .fillMaxWidth(0.48f)
                        .height(ReiAnixTokens.Spacing.sm)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = ReiAnixTokens.Shapes.small,
                        ),
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ReiAnixChip(
        text = text,
        onClick = onClick,
        selected = selected,
        modifier = Modifier.semantics {
            contentDescription = "Filtro $text"
        },
    )
}

@Composable
private fun LibraryAnimeCard(
    anime: ReiAnixAnimeUiModel,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val renderData = remember(anime) {
        val episodes = anime.contentEpisodes
        val availableCount = episodes.count {
            it.media.availability == ReiAnixMediaAvailability.AVAILABLE
        }
        val completed = episodes.isNotEmpty() && episodes.all {
            it.media.availability !in setOf(
                ReiAnixMediaAvailability.MISSING,
                ReiAnixMediaAvailability.SCOPE_UNAVAILABLE,
                ReiAnixMediaAvailability.VOLUME_UNAVAILABLE,
            ) && it.isCompleted
        }
        LibraryCardRenderData(
            availableCount = availableCount,
            metadata = buildList {
                anime.year?.let { add(it.toString()) }
                if (availableCount > 0) {
                    add(
                        if (availableCount == 1) {
                            "1 episódio"
                        } else {
                            availableCount.toString() + " episódios"
                        },
                    )
                }
            },
            progress = episodes.firstOrNull {
                it.progressFraction > 0f && !it.isCompleted
            }?.progressFraction,
            favorite = anime.favorite,
            watching = episodes.any {
                it.consumptionState == com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState.IN_PROGRESS
            },
            watched = episodes.any { it.isWatched },
            completed = completed,
        )
    }

    ReiAnixAnimeCard(
        title = anime.title,
        artworkPath = anime.artwork?.localPath,
        artworkExternalUrl = anime.artwork?.externalUrl,
        metadata = renderData.metadata,
        progress = renderData.progress,
        favorite = renderData.favorite,
        watching = renderData.watching,
        watched = renderData.watched,
        completed = renderData.completed,
        modifier = modifier,
        onClick = onClick,
        bottomBadgeText = presentationStateLabel(anime.presentationState) ?: when {
            anime.mediaKind == com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind.MOVIE -> "Filme"
            renderData.availableCount > 0 -> renderData.availableCount.toString() + if (renderData.availableCount == 1) " episódio" else " episódios"
            else -> null
        },
        onFavoriteClick = onToggleFavorite,
        maxDimensionPx = 512,
    )
}

private data class LibraryCardRenderData(
    val availableCount: Int,
    val metadata: List<String>,
    val progress: Float?,
    val favorite: Boolean,
    val watching: Boolean,
    val watched: Boolean,
    val completed: Boolean,
)

private fun libraryResultLabel(count: Int): String =
    count.toString() + " " + if (count == 1) "resultado" else "resultados"
