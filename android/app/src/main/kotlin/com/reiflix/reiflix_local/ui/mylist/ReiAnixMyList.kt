package com.reiflix.reiflix_local.ui.mylist

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
import androidx.compose.material.icons.filled.PlayArrow
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
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixIconActionButton
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixScreenTitle
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixSurface
import com.reiflix.reiflix_local.ui.artwork.ReiAnixPoster
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

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
    val totalSaved = state.favoriteCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ReiAnixScreenTitle(
            title = "Minha Lista",
            modifier = Modifier.padding(
                start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                top = ReiAnixTokens.Dimensions.screenTopPadding,
            ),
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> ReiAnixLoadingState(
                title = "Carregando Minha Lista",
                message = "Lendo os títulos salvos da biblioteca local…",
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
                    errorMessage = state.error ?: "A biblioteca local retornou um erro.",
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
                    title = "Não foi possível carregar Minha Lista",
                    message = state.error ?: "A biblioteca local retornou um erro.",
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
                    errorMessage = "A fonte local configurada não está disponível agora.",
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
                    title = "Minha Lista indisponível",
                    message = "A fonte local configurada não está disponível agora.",
                    onAction = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyState(
                title = "Minha Lista está vazia",
                message = "Adicione títulos à Minha Lista para encontrá-los aqui.",
                actionLabel = "Abrir Biblioteca",
                onAction = { navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ReiAnixLibraryLoadStatus.READY -> ReiAnixMyListReadyContent(
                visibleAnimes = visibleAnimes,
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
                                "Minha Lista está vazia"
                            } else {
                                "Nenhum título neste filtro"
                            },
                            message = if (totalSaved == 0) {
                                "Adicione títulos à Minha Lista para encontrá-los aqui."
                            } else {
                                "Nenhum título salvo corresponde a \"" + filter.label + "\"."
                            },
                            actionLabel = if (totalSaved == 0) "Abrir Biblioteca" else null,
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
                    text = option.label,
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
    val episodes = anime.contentEpisodes
    val currentEpisode = anime.playbackTargetEpisodeId
        ?.let { targetId -> episodes.firstOrNull { it.id == targetId } }
        ?: episodes.firstOrNull {
            it.consumptionState == com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState.IN_PROGRESS
        }
    val progress = currentEpisode
        ?.takeIf { !it.isCompleted && it.progressFraction > 0f }
        ?.progressFraction
        ?: episodes.firstOrNull {
            it.progressFraction > 0f && !it.isCompleted
        }?.progressFraction
    val isWatching = episodes.any {
        it.consumptionState == com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState.IN_PROGRESS
    }
    val isCompleted = episodes.isNotEmpty() && episodes.all { episode ->
        episode.media.availability !in setOf(
            com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability.MISSING,
            com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability.SCOPE_UNAVAILABLE,
            com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability.VOLUME_UNAVAILABLE,
        ) && episode.isCompleted
    }
    val availableCount = episodes.count {
        it.media.availability == com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability.AVAILABLE
    }
    val currentEpisodeLabel = currentEpisode?.let { episode ->
        val number = episode.number?.let { value ->
            if (value % 1.0 == 0.0) {
                "E" + value.toInt().toString().padStart(2, '0')
            } else {
                "E" + value.toString()
            }
        }
        val title = episode.displayTitle.trim().takeIf { it.isNotEmpty() }
        listOfNotNull(number, title).joinToString(" • ").takeIf { it.isNotBlank() }
    }

    val metadata = buildList {
        anime.year?.let { add(it.toString()) }
        if (availableCount > 0) {
            add(
                if (availableCount == 1) "1 episódio"
                else "$availableCount episódios",
            )
        }
        currentEpisodeLabel?.let { add(it) }
    }

    ReiAnixAnimeCard(
        anime = anime,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        onClick = onOpenDetails,
        bottomBadgeText = when {
            isWatching -> "Assistindo"
            isCompleted -> "Concluído"
            else -> null
        },
        onFavoriteClick = onToggleFavorite,
    )
}

private fun myListCountLabel(count: Int): String =
    if (count == 1) "1 título salvo" else count.toString() + " títulos salvos"

private fun myListCountLabel(count: Int): String =
    if (count == 1) "1 título salvo" else count.toString() + " títulos salvos"
