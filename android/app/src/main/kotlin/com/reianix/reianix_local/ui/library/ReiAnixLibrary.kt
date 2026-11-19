package com.reianix.reianix_local.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.flow.collect
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.reianix.reianix_local.ui.ReiAnixAnimeCard
import com.reianix.reianix_local.ui.collections.ReiAnixCollectionsScreen
import com.reianix.reianix_local.ui.ReiAnixIconActionButton
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.scroll.ReiAnixScrollAwareHeader
import com.reianix.reianix_local.ui.scroll.ReiAnixScrollAwareHeaderState
import com.reianix.reianix_local.ui.scroll.rememberReiAnixScrollAwareHeaderState
import com.reianix.reianix_local.ui.ReiAnixBadge
import com.reianix.reianix_local.ui.ReiAnixBadgeTone
import com.reianix.reianix_local.ui.ReiAnixChip
import com.reianix.reianix_local.ui.ReiAnixCompactButton
import com.reianix.reianix_local.ui.ReiAnixEmptyLibraryState
import com.reianix.reianix_local.ui.ReiAnixRecoverableErrorState
import com.reianix.reianix_local.ui.ReiAnixSearchField
import com.reianix.reianix_local.ui.ReiAnixScannerInProgressState
import com.reianix.reianix_local.ui.ReiAnixSourceUnavailableState
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.presentationStateLabel
import com.reianix.reianix_local.ui.model.ReiAnixGenreUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reianix.reianix_local.ui.model.ReiAnixLibraryUiState
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.navigation.navigateToDetails
import com.reianix.reianix_local.ui.navigation.navigateToTopLevel
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.LocalReiAnixPosterSize
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import com.reianix.reianix_local.viewmodel.ReiAnixMissingArtworkRetryUiState

@Composable
fun ReiAnixLibraryRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    cardSize: String = "medium",
    pageSize: Int = 0,
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
    LaunchedEffect(Unit) { viewModel.refreshPersonalLibraryFilters() }
    val visibleAnimes by viewModel.filteredLibraryAnimes.collectAsStateWithLifecycle()
    val genres by viewModel.libraryGenres.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val missingArtworkRetryState by viewModel.missingArtworkRetryState.collectAsStateWithLifecycle()
    val state = baseState

    ReiAnixLibraryPresentationScreen(
        state = state,
        filters = filters,
        visibleAnimes = visibleAnimes,
        genres = genres,
        cardSize = cardSize,
        isRefreshing = isRefreshing,
        missingArtworkRetryState = missingArtworkRetryState,
        onRetryMissingArtwork = viewModel::retryMissingArtwork,
        onQueryChange = viewModel::setLibrarySearchQuery,
        onGenreSelected = viewModel::setLibraryGenreFilter,
        onToggleFavorites = viewModel::toggleLibraryFavoritesFilter,
        onToggleWatching = viewModel::toggleLibraryWatchingFilter,
        onToggleCompleted = viewModel::toggleLibraryCompletedFilter,
        onSortSelected = viewModel::setLibrarySort,
        onApplyFilters = viewModel::applyLibraryFilters,
        onClearFilters = viewModel::clearLibraryFilters,
        onRefresh = viewModel::refresh,
        onSelectSource = viewModel::selectSafTree,
        onSearch = {
            navController.navigateToTopLevel(ReiAnixRoutes.SEARCH)
        },
        onOpenStorage = {
            navController.navigate(ReiAnixRoutes.STORAGE) { launchSingleTop = true }
        },
        onOpenDetails = { animeId ->
            navController.navigateToDetails(
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.LIBRARY,
            )
        },
        onToggleFavorite = viewModel::toggleFavorite,
        onOpenCollections = { navController.navigate(ReiAnixRoutes.COLLECTIONS) { launchSingleTop = true } },
        hasMore = pageState.hasMore,
        isLoadingMore = pageState.isLoadingMore,
        onLoadMore = viewModel::loadNextLibraryPage,
    )
}

@Composable
fun ReiAnixLibraryRoute(
    viewModel: ReiAnixLibraryViewModel,
    onOpenDetails: (Long) -> Unit,
) {
    var showCollections by rememberSaveable { mutableStateOf(false) }
    val baseState by viewModel.libraryPresentationState.collectAsStateWithLifecycle()
    val pageState by viewModel.pagedLibraryState.collectAsStateWithLifecycle()
    val filters by viewModel.libraryFilters.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshPersonalLibraryFilters() }
    val visibleAnimes by viewModel.filteredLibraryAnimes.collectAsStateWithLifecycle()
    val genres by viewModel.libraryGenres.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val missingArtworkRetryState by viewModel.missingArtworkRetryState.collectAsStateWithLifecycle()
    val state = baseState

    if (showCollections) {
        val catalogState by viewModel.uiState.collectAsStateWithLifecycle()
        ReiAnixCollectionsScreen(
            viewModel = viewModel,
            animes = catalogState.animes,
            onOpenAnime = onOpenDetails,
            onDismiss = { showCollections = false },
        )
        return
    }

    ReiAnixLibraryPresentationScreen(
        state = state,
        filters = filters,
        visibleAnimes = visibleAnimes,
        genres = genres,
        isRefreshing = isRefreshing,
        missingArtworkRetryState = missingArtworkRetryState,
        onRetryMissingArtwork = viewModel::retryMissingArtwork,
        onQueryChange = viewModel::setLibrarySearchQuery,
        onGenreSelected = viewModel::setLibraryGenreFilter,
        onToggleFavorites = viewModel::toggleLibraryFavoritesFilter,
        onToggleWatching = viewModel::toggleLibraryWatchingFilter,
        onToggleCompleted = viewModel::toggleLibraryCompletedFilter,
        onSortSelected = viewModel::setLibrarySort,
        onApplyFilters = viewModel::applyLibraryFilters,
        onClearFilters = viewModel::clearLibraryFilters,
        onRefresh = viewModel::refresh,
        onSelectSource = viewModel::selectSafTree,
        onOpenDetails = onOpenDetails,
        onToggleFavorite = viewModel::toggleFavorite,
        onOpenCollections = { showCollections = true },
        hasMore = pageState.hasMore,
        isLoadingMore = pageState.isLoadingMore,
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
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean = false,
    missingArtworkRetryState: ReiAnixMissingArtworkRetryUiState = ReiAnixMissingArtworkRetryUiState(),
    onRetryMissingArtwork: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSortSelected: (String) -> Unit = {},
    onApplyFilters: ((ReiAnixLibraryFilters) -> Unit)? = null,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    onOpenStorage: (() -> Unit)? = null,
    onOpenCollections: (() -> Unit)? = null,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onSelectSource: () -> Unit = onRefresh,
) {
    val presentation = com.reianix.reianix_local.ui.model.ReiAnixLibraryPresentationUiState(
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
        visibleAnimes = visibleAnimes,
        genres = genres,
        isRefreshing = isRefreshing,
        missingArtworkRetryState = missingArtworkRetryState,
        onRetryMissingArtwork = onRetryMissingArtwork,
        onQueryChange = onQueryChange,
        onGenreSelected = onGenreSelected,
        onToggleFavorites = onToggleFavorites,
        onToggleWatching = onToggleWatching,
        onToggleCompleted = onToggleCompleted,
        onSortSelected = onSortSelected,
        onApplyFilters = onApplyFilters,
        onClearFilters = onClearFilters,
        onRefresh = onRefresh,
        onSelectSource = onSelectSource,
        onOpenDetails = onOpenDetails,
        onToggleFavorite = onToggleFavorite,
        onSearch = onSearch,
        onOpenStorage = onOpenStorage,
        onOpenCollections = onOpenCollections,
    )
}

@Composable
private fun ReiAnixLibraryPresentationScreen(
    state: com.reianix.reianix_local.ui.model.ReiAnixLibraryPresentationUiState,
    filters: ReiAnixLibraryFilters,
    cardSize: String = "medium",
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean = false,
    missingArtworkRetryState: ReiAnixMissingArtworkRetryUiState = ReiAnixMissingArtworkRetryUiState(),
    onRetryMissingArtwork: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSortSelected: (String) -> Unit = {},
    onApplyFilters: ((ReiAnixLibraryFilters) -> Unit)? = null,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    onOpenStorage: (() -> Unit)? = null,
    onOpenCollections: (() -> Unit)? = null,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onSelectSource: () -> Unit = onRefresh,
) {
    ReiAnixResponsiveRoot {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    if (showFilters) {
        LibraryFiltersDialog(
            filters = filters,
            genres = genres,
            onDismiss = { showFilters = false },
            onSelection = { draft ->
                if (onApplyFilters != null) onApplyFilters(draft) else {
                    onSortSelected(draft.sort)
                    onGenreSelected(draft.selectedGenreKey)
                }
            },
            onRefresh = { showFilters = false; onRefresh() },
            isRefreshing = isRefreshing,
        )
    }
    val scrollHeaderState = rememberReiAnixScrollAwareHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollHeaderState.nestedScrollConnection)
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ReiAnixScrollAwareHeader(state = scrollHeaderState) {
            Box(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            ) {
                LibraryHeader(
                    sourceAvailable = state.sourceAvailable,
                    onSearch = onSearch,
                    onRefresh = onRefresh,
                    isRefreshing = isRefreshing,
                    onOpenCollections = onOpenCollections,
                    missingArtworkRetryState = missingArtworkRetryState,
                    onRetryMissingArtwork = onRetryMissingArtwork,
                    onFilters = { showFilters = true },
                )
            }
        }

        val renderReadyContent: @Composable () -> Unit = {
            LibraryReadyContent(
                state = state,
                scrollHeaderState = scrollHeaderState,
                filters = filters,
                visibleAnimes = visibleAnimes,
                genres = genres,
                isRefreshing = isRefreshing,
                missingArtworkRetryState = missingArtworkRetryState,
                onRetryMissingArtwork = onRetryMissingArtwork,
                onOpenStorage = onOpenStorage,
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
    onOpenCollections: (() -> Unit)? = null,
    missingArtworkRetryState: ReiAnixMissingArtworkRetryUiState,
    onRetryMissingArtwork: () -> Unit,
    onFilters: () -> Unit,
) {
    ReiAnixTopBar(
        title = "Biblioteca",
        actions = {
            IconButton(
                onClick = onRetryMissingArtwork,
                enabled = !missingArtworkRetryState.isRunning,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget).semantics {
                    contentDescription = "Baixar capas que faltam"
                },
            ) {
                if (missingArtworkRetryState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Canvas(Modifier.size(ReiAnixTokens.Dimensions.iconMedium)) {
                        val u = size.width / 32f
                        val stroke = 2f * u
                        val white = Color.White
                        drawRoundRect(white, Offset(3*u,4*u), Size(24*u,22*u),
                            cornerRadius = CornerRadius(2*u), style = Stroke(stroke))
                        drawLine(white, Offset(3*u,21*u), Offset(11*u,13*u), stroke, StrokeCap.Round)
                        drawLine(white, Offset(11*u,13*u), Offset(17*u,19*u), stroke, StrokeCap.Round)
                        drawCircle(white, 2*u, Offset(20*u,10*u), style = Stroke(stroke))
                        drawRoundRect(Color.Black, Offset(16*u,16*u), Size(16*u,16*u), CornerRadius(2*u))
                        drawLine(white, Offset(24*u,18*u), Offset(24*u,29*u), stroke, StrokeCap.Round)
                        drawLine(white, Offset(19*u,24*u), Offset(24*u,29*u), stroke, StrokeCap.Round)
                        drawLine(white, Offset(29*u,24*u), Offset(24*u,29*u), stroke, StrokeCap.Round)
                    }
                }
            }
            if (onOpenCollections != null) {
                LibraryWhiteIconButton(Icons.Outlined.Folder, "Coleções", onOpenCollections)
            }
            if (onSearch != null) {
                LibraryWhiteIconButton(Icons.Filled.Search, "Pesquisar na biblioteca", onSearch)
            }
            IconButton(
                onClick = onFilters,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget).semantics {
                    contentDescription = "Filtros da biblioteca"
                },
            ) {
                Canvas(Modifier.size(ReiAnixTokens.Dimensions.iconMedium)) {
                    val u = size.width / 48f
                    val stroke = 3.5f * u
                    drawLine(Color.White, Offset(8*u,12*u), Offset(40*u,12*u), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(14*u,24*u), Offset(34*u,24*u), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(21*u,36*u), Offset(27*u,36*u), stroke, StrokeCap.Round)
                }
            }
        },
    )
    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.sm))
}

@Composable
private fun LibraryWhiteIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick,
        modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget).semantics {
            contentDescription = description
        },
    ) {
        Icon(icon, contentDescription = null, tint = Color.White,
            modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium))
    }
}

/**
 * Single modal sheet with four tabs; selections apply immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFiltersDialog(
    filters: ReiAnixLibraryFilters,
    genres: List<ReiAnixGenreUiModel>,
    onDismiss: () -> Unit,
    onSelection: (ReiAnixLibraryFilters) -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val tabs = listOf("Ordenar", "Gêneros", "Estado", "Etiqueta")
    var tab by rememberSaveable { mutableStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(filters.sort) }
    var genre by rememberSaveable { mutableStateOf(filters.selectedGenreKey) }
    var state by rememberSaveable { mutableStateOf(filters.watchState) }
    var label by rememberSaveable { mutableStateOf(filters.labelFilter) }
    var closing by remember { mutableStateOf(false) }

    fun closeThen(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) action()
            } finally {
                closing = false
            }
        }
    }

    val options: List<Pair<String?, String>> = when (tab) {
        0 -> ReiAnixLibrarySort.OPTIONS.map { it.label to it.label }
        1 -> listOf(null to "Todos") + genres.map { it.stableKey to it.name }
        2 -> ReiAnixLibraryFilters.WATCH_STATES
        else -> ReiAnixLibraryFilters.LABEL_FILTERS
    }
    val selectedKey = when (tab) {
        0 -> sort
        1 -> genre
        2 -> state
        else -> label
    }
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Leave the sheet's outer constraints at full window height so its
        // expanded anchor is measured from the actual bottom of the window.
        modifier = Modifier.testTag("library-filter-sheet"),
        sheetMaxWidth = Dp.Unspecified,
        sheetGesturesEnabled = false,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Size the content, not the anchor's parent constraints.
                .fillMaxHeight(0.48f)
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Filtros da biblioteca",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = !isRefreshing && !closing,
                    onClick = { closeThen(onRefresh) },
                    modifier = Modifier.semantics { contentDescription = "Atualizar biblioteca" },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
            }

            Row(Modifier.fillMaxWidth().selectableGroup()) {
                tabs.forEachIndexed { index, title ->
                    val active = index == tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = active,
                                enabled = !closing,
                                role = Role.Tab,
                                onClick = { tab = index },
                            )
                            .semantics { contentDescription = title },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.heightIn(min = 44.dp).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) accent else colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().height(3.dp)
                                .background(if (active) accent else colors.outlineVariant, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }

            // One bounded scrolling surface; heading and tabs remain fixed.
            key(tab) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).selectableGroup()
                        .testTag("library-filter-options"),
                    // Disable the Android stretch/bounce at either scroll boundary
                    // for this list only. The library grid keeps its own behavior.
                    overscrollEffect = null,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(options, key = { it.first ?: "__all_genres__" }) { (value, title) ->
                        val selected = value == selectedKey
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    enabled = !closing,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (!selected) {
                                            when (tab) {
                                                0 -> sort = value ?: ReiAnixLibrarySort.DEFAULT.label
                                                1 -> genre = value
                                                2 -> state = value ?: "ALL"
                                                else -> label = value ?: "ALL"
                                            }
                                            // Apply each selection immediately, preserving other
                                            // tabs and the existing search/quick filters.
                                            onSelection(filters.copy(
                                                sort = sort,
                                                selectedGenreKey = genre,
                                                watchState = state,
                                                labelFilter = label,
                                            ))
                                        }
                                    },
                                )
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 0.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // The empty leading slot keeps every label aligned; only the
                            // selected option draws the lightweight checkmark.
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = colors.onBackground,
                                        modifier = Modifier.size(22.dp).testTag("library-filter-check"),
                                    )
                                }
                            }
                            Text(
                                title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.onBackground else colors.onBackground.copy(alpha = 0.65f),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LibraryReadyContent(
    state: com.reianix.reianix_local.ui.model.ReiAnixLibraryPresentationUiState,
    cardSize: String = "medium",
    filters: ReiAnixLibraryFilters,
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    isRefreshing: Boolean,
    missingArtworkRetryState: ReiAnixMissingArtworkRetryUiState,
    onOpenStorage: (() -> Unit)?,
    onRetryMissingArtwork: () -> Unit,
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
    scrollHeaderState: ReiAnixScrollAwareHeaderState? = null,
) {
    val refreshState = rememberPullToRefreshState()
    val availableEpisodeCount = state.availableEpisodeCount
    val responsive = LocalReiAnixResponsiveMetrics.current
    val posterSize = LocalReiAnixPosterSize.current
    val posterGridMinimum = responsive.posterCardWidth(posterSize)
    val posterRequestDimensionPx = with(LocalDensity.current) {
        (posterGridMinimum / ReiAnixTokens.Dimensions.posterAspectRatio).roundToPx().coerceAtLeast(1)
    }
    val posterGridPadding = responsive.posterRailPadding(posterSize)
    val posterGridSpacing = responsive.posterGridSpacing(posterSize) + 1.dp
    val gridState = rememberSaveable(
        saver = LazyGridState.Saver,
    ) {
        LazyGridState()
    }

    LaunchedEffect(gridState, scrollHeaderState) {
        val headerState = scrollHeaderState ?: return@LaunchedEffect
        snapshotFlow {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }.collect { isAtTop ->
            headerState.updateContentAtTop(isAtTop)
        }
    }

    // Scroll observation should not restart its coroutine whenever the page
    // count or background scan state changes. Read the latest callback/flags
    // from snapshot state, while preserving the exact prefetch threshold.
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentCanLoadMore by rememberUpdatedState(hasMore && !isLoadingMore)
    LaunchedEffect(gridState) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            Triple(
                layout.visibleItemsInfo.lastOrNull()?.index,
                layout.totalItemsCount,
                currentCanLoadMore,
            )
        }.collect { (lastVisibleIndex, total, canLoadMore) ->
            if (
                canLoadMore &&
                lastVisibleIndex != null &&
                total > 0 &&
                lastVisibleIndex >= total - 6
            ) {
                currentOnLoadMore()
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
            columns = GridCells.Adaptive(minSize = posterGridMinimum),
            state = gridState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = posterGridPadding,
                end = posterGridPadding,
                top = ReiAnixTokens.Spacing.xs,
                bottom = ReiAnixTokens.Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(posterGridSpacing),
            horizontalArrangement = Arrangement.spacedBy(posterGridSpacing),
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


                }
            }

            if (!missingArtworkRetryState.message.isNullOrBlank()) {
                item(
                    key = "library-missing-artwork-retry-status",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "library-missing-artwork-retry-status",
                ) {
                    Text(
                        text = missingArtworkRetryState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missingArtworkRetryState.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ReiAnixTokens.Spacing.xs),
                    )
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
                            maxDimensionPx = posterRequestDimensionPx,
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
                            com.reianix.reianix_local.ui.ReiAnixLoadingIndicator()
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
    maxDimensionPx: Int,
    modifier: Modifier = Modifier,
) {
    val renderData = remember(anime) {
        // AnimeUiModel keeps this summary lazy and cached for its immutable snapshot.
        // Grid scrolling can create many cards at once, so never walk each anime's
        // episode list again from the card composition path.
        val availableCount = anime.availableContentCount
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
            progress = anime.resumeProgressFraction,
            favorite = anime.favorite,
            watching = anime.isWatching,
            watched = anime.hasWatchedEpisode,
            completed = anime.isCompleted,
        )
    }

    ReiAnixAnimeCard(
        title = anime.title,
        artworkPath = anime.artwork?.localPath,
        artworkExternalUrl = anime.artwork?.externalUrl,
        artworkIdentity = anime.stableKey,
        artworkSourceRevisions = anime.artwork?.sourceRevisions.orEmpty(),
        metadata = renderData.metadata,
        progress = renderData.progress,
        favorite = renderData.favorite,
        watching = renderData.watching,
        watched = renderData.watched,
        completed = renderData.completed,
        modifier = modifier,
        onClick = onClick,
        bottomBadgeText = presentationStateLabel(anime.presentationState) ?: when {
            anime.mediaKind == com.reianix.reianix_local.ui.model.ReiAnixMediaKind.MOVIE -> "Filme"
            renderData.availableCount > 0 -> renderData.availableCount.toString() + if (renderData.availableCount == 1) " episódio" else " episódios"
            else -> null
        },
        onFavoriteClick = onToggleFavorite,
        maxDimensionPx = maxDimensionPx,
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
