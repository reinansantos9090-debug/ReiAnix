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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
) {
    ReiAnixResponsiveRoot {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.myListFilter.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.myListAnimes.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val totalSaved = state.animes.count { it.favorite }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ReiAnixScreenTitle(
            title = "Minha Lista",
            subtitle = myListCountLabel(totalSaved),
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

            ReiAnixLibraryLoadStatus.ERROR -> if (state.animes.isNotEmpty()) {
                ReiAnixMyListReadyContent(
                    visibleAnimes = visibleAnimes,
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

            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> if (state.animes.isNotEmpty()) {
                ReiAnixMyListReadyContent(
                    visibleAnimes = visibleAnimes,
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
            .fillMaxWidth()
            .widthIn(max = LocalReiAnixResponsiveMetrics.current.contentMaxWidth),
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
                minSize = LocalReiAnixResponsiveMetrics.current.myListGridMinWidth,
            ),
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = LocalReiAnixResponsiveMetrics.current.contentMaxWidth),
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
                            .heightIn(min = 280.dp)
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
    val renderData = remember(anime) {
        val episodes = anime.contentEpisodes
        val progressEpisode = anime.playbackTargetEpisodeId
            ?.let { targetId -> episodes.firstOrNull { it.id == targetId } }
            ?: episodes.firstOrNull {
                it.progressFraction > 0f && !it.isCompleted
            }
        val progress = progressEpisode
            ?.progressFraction
            ?.takeIf { it > 0f && it < 1f }
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
        MyListCardRenderData(
            availableCount = episodes.count {
                it.media.availability == com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability.AVAILABLE
            },
            progress = progress,
            status = myListStatus(
                anime = anime,
                isWatching = isWatching,
                isCompleted = isCompleted,
            ),
        )
    }
    var menuExpanded by rememberSaveable(anime.id) {
        mutableStateOf(false)
    }

    ReiAnixSurface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.myListRowMinHeight)
            .clickable(onClick = onOpenDetails)
            .semantics {
                role = Role.Button
                contentDescription = "Abrir " + anime.title
            },
        shape = ReiAnixTokens.Shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReiAnixPoster(
                localPath = anime.artwork?.localPath,
                contentDescription = anime.title,
                identity = anime.stableKey,
                modifier = Modifier
                    .size(
                        width = ReiAnixTokens.Dimensions.myListPosterWidth,
                        height = ReiAnixTokens.Dimensions.myListPosterHeight,
                    )
                    .clip(ReiAnixTokens.Shapes.small),
                maxDimensionPx = 320,
            )

            Spacer(Modifier.size(ReiAnixTokens.Spacing.md))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                androidx.compose.material3.Text(
                    text = anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )

                androidx.compose.material3.Text(
                    text = if (renderData.availableCount == 0) {
                        "Sem episódios disponíveis"
                    } else if (renderData.availableCount == 1) {
                        "1 episódio"
                    } else {
                        renderData.availableCount.toString() + " episódios"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )

                if (renderData.progress != null) {
                    ReiAnixProgressIndicator(
                        progress = renderData.progress,
                        visible = true,
                    )
                }
            }

            Spacer(Modifier.size(ReiAnixTokens.Spacing.sm))

            MyListStatusPill(
                status = renderData.status,
            )

            Box {
                ReiAnixIconActionButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Mais opções para " + anime.title,
                    onClick = { menuExpanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Mais opções para " + anime.title
                    },
                )

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { androidx.compose.material3.Text("Remover da Minha Lista") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.FavoriteBorder,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleFavorite()
                        },
                    )
                }
            }
        }
    }
}

private data class MyListCardRenderData(
    val availableCount: Int,
    val progress: Float?,
    val status: MyListStatus,
)

private data class MyListStatus(
    val label: String,
    val tone: ReiAnixBadgeTone,
    val icon: ImageVector,
)

@Composable
private fun MyListInlineError(
    message: String,
    onRetry: () -> Unit,
) {
    ReiAnixSurface(
        color = ReiAnixTokens.Colors.errorContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Erro ao atualizar Minha Lista: " + message
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                androidx.compose.material3.Text(
                    text = "Dados anteriores mantidos",
                    style = MaterialTheme.typography.labelLarge,
                    color = ReiAnixTokens.Colors.onErrorContainer,
                )
                androidx.compose.material3.Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = ReiAnixTokens.Colors.onErrorContainer,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            androidx.compose.material3.TextButton(
                onClick = onRetry,
            ) {
                androidx.compose.material3.Text(
                    text = "Tentar novamente",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun myListStatus(
    anime: ReiAnixAnimeUiModel,
    isWatching: Boolean,
    isCompleted: Boolean,
): MyListStatus = when {
    isWatching -> MyListStatus(
        label = "Assistindo",
        tone = ReiAnixBadgeTone.Primary,
        icon = Icons.Filled.PlayArrow,
    )
    isCompleted -> MyListStatus(
        label = "Concluído",
        tone = ReiAnixBadgeTone.Success,
        icon = Icons.Filled.Check,
    )
    anime.favorite -> MyListStatus(
        label = "Favorito",
        tone = ReiAnixBadgeTone.Error,
        icon = Icons.Filled.Favorite,
    )
    else -> MyListStatus(
        label = "Favorito",
        tone = ReiAnixBadgeTone.Error,
        icon = Icons.Filled.Favorite,
    )
}

@Composable
private fun MyListStatusPill(
    status: MyListStatus,
) {
    val tint = when (status.tone) {
        ReiAnixBadgeTone.Primary -> MaterialTheme.colorScheme.primary
        ReiAnixBadgeTone.Success -> ReiAnixTokens.Colors.success
        ReiAnixBadgeTone.Warning -> ReiAnixTokens.Colors.warning
        ReiAnixBadgeTone.Error -> MaterialTheme.colorScheme.error
        ReiAnixBadgeTone.Info -> MaterialTheme.colorScheme.secondary
        ReiAnixBadgeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = ReiAnixTokens.Shapes.chip,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
        contentColor = tint,
        border = androidx.compose.foundation.BorderStroke(
            width = ReiAnixTokens.Dimensions.borderWidth,
            color = tint.copy(alpha = 0.75f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = ReiAnixTokens.Spacing.sm,
                vertical = ReiAnixTokens.Spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            Icon(
                imageVector = status.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconSmall),
            )
            androidx.compose.material3.Text(
                text = status.label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

private fun myListCountLabel(count: Int): String =
    if (count == 1) "1 título salvo" else count.toString() + " títulos salvos"
