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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.reiflix.reiflix_local.ui.ReiAnixAnimeCard
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSearchField
import com.reiflix.reiflix_local.ui.ReiAnixScannerInProgressState
import com.reiflix.reiflix_local.ui.ReiAnixScreenTitle
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixLibraryRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filters by viewModel.libraryFilters.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.filteredLibraryAnimes.collectAsStateWithLifecycle()
    val genres by viewModel.libraryGenres.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    ReiAnixLibraryScreen(
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
        onSearch = {
            navController.navigateToTopLevel(ReiAnixRoutes.SEARCH)
        },
        onOpenDetails = { animeId ->
            navController.navigateToDetails(
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.LIBRARY,
            )
        },
        onToggleFavorite = viewModel::toggleFavorite,
    )
}

@Composable
fun ReiAnixLibraryRoute(
    viewModel: ReiAnixLibraryViewModel,
    onOpenDetails: (Long) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filters by viewModel.libraryFilters.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.filteredLibraryAnimes.collectAsStateWithLifecycle()
    val genres by viewModel.libraryGenres.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    ReiAnixLibraryScreen(
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
        onOpenDetails = onOpenDetails,
        onToggleFavorite = viewModel::toggleFavorite,
    )
}

@Composable
fun ReiAnixLibraryScreen(
    state: ReiAnixLibraryUiState,
    filters: ReiAnixLibraryFilters,
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LibraryHeader(
            count = state.animes.size,
            onRefresh = onRefresh,
            onSearch = onSearch,
            isRefreshing = isRefreshing,
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> {
                if (state.scanInProgress) {
                    ReiAnixScannerInProgressState(
                        scanState = state.scanState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    LibraryLoadingGrid(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }

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
                message = "A fonte local configurada não está disponível agora.",
                onAction = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyLibraryState(
                message = "Nenhum conteúdo local está disponível.",
                actionLabel = "Atualizar",
                onAction = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ReiAnixLibraryLoadStatus.READY -> LibraryReadyContent(
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
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LibraryHeader(
    count: Int,
    onRefresh: () -> Unit,
    onSearch: (() -> Unit)?,
    isRefreshing: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                top = ReiAnixTokens.Dimensions.screenTopPadding,
                bottom = ReiAnixTokens.Spacing.md,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReiAnixScreenTitle(
                title = "Biblioteca",
                subtitle = libraryCountLabel(count),
                modifier = Modifier.weight(1f),
            )

            if (onSearch != null) {
                IconButton(
                    onClick = onSearch,
                    modifier = Modifier.semantics {
                        contentDescription = "Pesquisar na biblioteca"
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                }
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(ReiAnixTokens.Dimensions.loadingIndicatorSize),
                        strokeWidth = ReiAnixTokens.Dimensions.loadingIndicatorStroke,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LibraryReadyContent(
    state: ReiAnixLibraryUiState,
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
    modifier: Modifier = Modifier,
) {
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()
    val gridState = rememberSaveable(
        saver = LazyGridState.Saver,
    ) {
        LazyGridState()
    }

    PullToRefreshBox(
        modifier = modifier.fillMaxWidth(),
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
                containerColor = ReiAnixTokens.Colors.surfaceRaised,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(
                minSize = ReiAnixTokens.Dimensions.libraryGridMinWidth,
            ),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                top = ReiAnixTokens.Spacing.xs,
                bottom = ReiAnixTokens.Spacing.huge,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            item(
                key = "library-search",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "library-search",
            ) {
                ReiAnixSearchField(
                    value = filters.query,
                    onValueChange = onQueryChange,
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
                        item(key = "filter-title") {
                            ReiAnixBadge(
                                text = "Filtros",
                                tone = ReiAnixBadgeTone.Neutral,
                            )
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
                        genres.forEach { genre ->
                            item(key = "genre-" + genre.stableKey) {
                                LibraryFilterChip(
                                    text = genre.name,
                                    selected = filters.selectedGenreKey == genre.stableKey,
                                    onClick = {
                                        onGenreSelected(
                                            genre.stableKey.takeUnless {
                                                it == filters.selectedGenreKey
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Box {
                        ReiAnixChip(
                            text = "Ordenar",
                            selected = filters.sort != ReiAnixLibrarySort.DEFAULT.label,
                            onClick = { sortMenuExpanded = true },
                            modifier = Modifier.semantics {
                                contentDescription = "Ordenar: " + filters.sort
                            },
                        )
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
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
                            .padding(vertical = ReiAnixTokens.Spacing.xxl),
                    )
                }
            } else {
                items(
                    items = visibleAnimes,
                    key = { anime -> anime.stableKey },
                    contentType = { "library-anime-card" },
                ) { anime ->
                    LibraryAnimeCard(
                        anime = anime,
                        onClick = { onOpenDetails(anime.id) },
                        onToggleFavorite = { onToggleFavorite(anime.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryLoadingGrid(
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(
            minSize = ReiAnixTokens.Dimensions.libraryGridMinWidth,
        ),
        modifier = modifier,
        userScrollEnabled = false,
        contentPadding = PaddingValues(
            start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            top = ReiAnixTokens.Spacing.sm,
            bottom = ReiAnixTokens.Spacing.huge,
        ),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.lg),
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
        metadata = renderData.metadata,
        progress = renderData.progress,
        favorite = renderData.favorite,
        watching = renderData.watching,
        watched = renderData.watched,
        completed = renderData.completed,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        onFavoriteClick = onToggleFavorite,
        maxDimensionPx = 512,
    )
}

private data class LibraryCardRenderData(
    val metadata: List<String>,
    val progress: Float?,
    val favorite: Boolean,
    val watching: Boolean,
    val watched: Boolean,
    val completed: Boolean,
)

private fun libraryCountLabel(count: Int): String =
    count.toString() + " " + if (count == 1) "título" else "títulos"

private fun libraryResultLabel(count: Int): String =
    count.toString() + " " + if (count == 1) "resultado" else "resultados"
