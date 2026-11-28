package com.reianix.reianix_local.ui.mylist

import com.reianix.reianix_local.ui.localizedUiLabel
import com.reianix.reianix_local.ui.localizedGenreLabel
import com.reianix.reianix_local.ui.localizedEpisodeCount

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.reianix.reianix_local.ui.ReiAnixAnimeCard
import com.reianix.reianix_local.ui.ReiAnixBadgeTone
import com.reianix.reianix_local.ui.ReiAnixChip
import com.reianix.reianix_local.ui.ReiAnixEmptyState
import com.reianix.reianix_local.ui.ReiAnixIconActionButton
import com.reianix.reianix_local.ui.ReiAnixLoadingState
import com.reianix.reianix_local.ui.ReiAnixProgressIndicator
import com.reianix.reianix_local.ui.ReiAnixRecoverableErrorState
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.ReiAnixSourceUnavailableState
import com.reianix.reianix_local.ui.ReiAnixSurface
import com.reianix.reianix_local.ui.artwork.ReiAnixPoster
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.navigation.navigateToDetails
import com.reianix.reianix_local.ui.navigation.navigateToTopLevel
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixMyListRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    cardSize: String = "medium",
) {
    ReiAnixResponsiveRoot {
    val state by viewModel.myListPresentationState.collectAsStateWithLifecycle()
    val filter by viewModel.myListFilter.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.myListAnimes.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val mutationError by viewModel.personalListMutationError.collectAsStateWithLifecycle()
    val totalSaved = state.favoriteCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(reiAnixScreenBackground()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ReiAnixTopBar(
            title = stringResource(R.string.ui_my_list_title),
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> ReiAnixLoadingState(
                title = stringResource(R.string.ui_my_list_loading),
                message = stringResource(R.string.ui_my_list_read),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ReiAnixLibraryLoadStatus.ERROR -> if (state.favoriteCount > 0) {
                ReiAnixMyListReadyContent(
                    visibleAnimes = visibleAnimes,
                    cardSize = cardSize,
                    totalSaved = totalSaved,
                    filter = filter,
                    isRefreshing = isRefreshing,
                    errorMessage = mutationError ?: state.error ?: stringResource(R.string.flow_library_return_error),
                    onFilterChange = viewModel::setMyListFilter,
                    onOpenDetails = { animeId ->
                        navController.navigateToDetails(
                            animeId = animeId.toString(),
                            origin = ReiAnixRoutes.MY_LIST,
                        )
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onOpenLibrary = {
                        navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY)
                    },
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ReiAnixRecoverableErrorState(
                    title = stringResource(R.string.ui_my_list_failed),
                    message = state.error ?: stringResource(R.string.flow_library_return_error),
                    onRetry = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> if (state.favoriteCount > 0) {
                ReiAnixMyListReadyContent(
                    visibleAnimes = visibleAnimes,
                    cardSize = cardSize,
                    totalSaved = totalSaved,
                    filter = filter,
                    isRefreshing = isRefreshing,
                    errorMessage = mutationError ?: stringResource(R.string.ui_source_unavailable),
                    onFilterChange = viewModel::setMyListFilter,
                    onOpenDetails = { animeId ->
                        navController.navigateToDetails(
                            animeId = animeId.toString(),
                            origin = ReiAnixRoutes.MY_LIST,
                        )
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onOpenLibrary = {
                        navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY)
                    },
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ReiAnixSourceUnavailableState(
                    title = stringResource(R.string.ui_my_list_unavailable),
                    message = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                        stringResource(R.string.flow_mylist_folder)
                    } else {
                        stringResource(R.string.ui_source_unavailable)
                    },
                    actionLabel = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                        stringResource(R.string.flow_select_folder)
                    } else {
                        stringResource(R.string.more_refresh)
                    },
                    onAction = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                        viewModel::selectSafTree
                    } else {
                        viewModel::refresh
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyState(
                title = stringResource(R.string.ui_my_list_empty),
                message = stringResource(R.string.ui_my_list_hint),
                actionLabel = stringResource(R.string.flow_open_library),
                onAction = { navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ReiAnixLibraryLoadStatus.READY -> ReiAnixMyListReadyContent(
                visibleAnimes = visibleAnimes,
                errorMessage = mutationError,
                cardSize = cardSize,
                totalSaved = totalSaved,
                filter = filter,
                isRefreshing = isRefreshing,
                onFilterChange = viewModel::setMyListFilter,
                onOpenDetails = { animeId ->
                    navController.navigateToDetails(
                        animeId = animeId.toString(),
                        origin = ReiAnixRoutes.MY_LIST,
                    )
                },
                onToggleFavorite = viewModel::toggleFavorite,
                onOpenLibrary = {
                    navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY)
                },
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            )
        }
    }

    }
}

@Composable
private fun ReiAnixMyListReadyContent(
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    cardSize: String,
    totalSaved: Int,
    filter: ReiAnixMyListFilter,
    isRefreshing: Boolean,
    errorMessage: String? = null,
    onFilterChange: (ReiAnixMyListFilter) -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The My List destination remains on the NavController back stack when
    // Details/Player are opened. Save the actual lazy-grid position so returning
    // to the source list does not jump to the first row.
    val listState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val refreshState = rememberPullToRefreshState()

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
                minSize = LocalReiAnixResponsiveMetrics.current.gridItemMinWidth(cardSize),
            ),
            state = listState,
            modifier = Modifier
                .widthIn(max = LocalReiAnixResponsiveMetrics.current.contentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                top = ReiAnixTokens.Spacing.sm,
                bottom = ReiAnixTokens.Spacing.huge,
            ),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                item(
                    key = "my-list-error",
                    contentType = "my-list-error",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    MyListInlineError(
                        message = message,
                        onRetry = onRefresh,
                    )
                }
            }

            item(
                key = "my-list-filters",
                contentType = "my-list-filters",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                MyListFilters(
                    selected = filter,
                    onSelected = onFilterChange,
                )
            }

            if (visibleAnimes.isEmpty()) {
                item(
                    key = "my-list-filter-empty",
                    contentType = "my-list-empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ReiAnixTokens.Dimensions.emptyStateMinHeight)
                            .padding(vertical = ReiAnixTokens.Spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        ReiAnixEmptyState(
                            title = if (totalSaved == 0) {
                                stringResource(R.string.ui_my_list_empty)
                            } else {
                                stringResource(R.string.flow_mylist_empty_filter)
                            },
                            message = if (totalSaved == 0) {
                                stringResource(R.string.ui_my_list_hint)
                            } else {
                                stringResource(R.string.flow_mylist_filter_none, localizedUiLabel(filter.label))
                            },
                            actionLabel = if (totalSaved == 0) stringResource(R.string.flow_open_library) else null,
                            onAction = if (totalSaved == 0) onOpenLibrary else null,
                        )
                    }
                }
            } else {
                items(
                    items = visibleAnimes,
                    key = { anime -> anime.stableKey },
                    contentType = { "my-list-anime" },
                ) { anime ->
                    ReiAnixMyListItem(
                        anime = anime,
                        onOpenDetails = { onOpenDetails(anime.id) },
                        onToggleFavorite = { onToggleFavorite(anime.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MyListFilters(
    selected: ReiAnixMyListFilter,
    onSelected: (ReiAnixMyListFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        contentPadding = PaddingValues(end = ReiAnixTokens.Spacing.sm),
    ) {
        ReiAnixMyListFilter.entries.forEach { option ->
            item(key = "filter-" + option.name) {
                ReiAnixChip(
                    text = localizedUiLabel(option.label),
                    selected = option == selected,
                    onClick = { onSelected(option) },
                )
            }
        }
    }
}

@Composable
private fun ReiAnixMyListItem(
    anime: ReiAnixAnimeUiModel,
    onOpenDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    // Minha Lista intentionally reuses the canonical anime card used by
    // Biblioteca/Home/Search. Favorite, progress and consumption state remain
    // driven by the same persisted catalog projection.
    ReiAnixAnimeCard(
        anime = anime,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        onClick = onOpenDetails,
        onFavoriteClick = onToggleFavorite,
    )
}

@Composable
private fun MyListInlineError(
    message: String,
    onRetry: () -> Unit,
) {
    ReiAnixRecoverableErrorState(
        title = stringResource(R.string.ui_my_list_partial_error),
        message = message,
        onRetry = onRetry,
        modifier = Modifier.fillMaxWidth(),
        retryLabel = stringResource(R.string.ui_retry),
    )
}

private fun myListCountLabel(count: Int): String =
    if (count == 1) "1 título salvo" else count.toString() + " títulos salvos"
