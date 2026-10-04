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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixIconActionButton
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixScreenTitle
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryText
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixSurface
import com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtwork
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixMyListRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.myListFilter.collectAsStateWithLifecycle()
    val visibleAnimes by viewModel.myListAnimes.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val totalSaved = state.animes.count { it.favorite }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ReiAnixScreenTitle(
            title = "Minha Lista",
            subtitle = myListCountLabel(totalSaved),
            modifier = Modifier.padding(
                start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
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

            ReiAnixLibraryLoadStatus.ERROR -> ReiAnixRecoverableErrorState(
                title = "Não foi possível carregar Minha Lista",
                message = state.error ?: "A biblioteca local retornou um erro.",
                onRetry = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> ReiAnixSourceUnavailableState(
                title = "Minha Lista indisponível",
                message = "A fonte local configurada não está disponível agora.",
                onAction = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

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

@Composable
private fun ReiAnixMyListReadyContent(
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    totalSaved: Int,
    filter: ReiAnixMyListFilter,
    isRefreshing: Boolean,
    onFilterChange: (ReiAnixMyListFilter) -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val refreshState = rememberPullToRefreshState()

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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                top = ReiAnixTokens.Spacing.sm,
                bottom = ReiAnixTokens.Spacing.huge,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            item(
                key = "my-list-filters",
                contentType = "my-list-filters",
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
    val contentEpisodes = anime.contentEpisodes
    val progressEpisode = anime.playbackTargetEpisodeId
        ?.let { targetId -> contentEpisodes.firstOrNull { it.id == targetId } }
        ?: contentEpisodes.firstOrNull {
            it.progressFraction > 0f && !it.isCompleted
        }
    val progress = progressEpisode
        ?.progressFraction
        ?.takeIf { it > 0f && it < 1f }
    val status = myListStatus(anime)

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
        color = ReiAnixTokens.Colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReiAnixLocalArtwork(
                localPath = anime.artwork?.localPath,
                contentDescription = anime.title,
                modifier = Modifier
                    .size(
                        width = ReiAnixTokens.Dimensions.myListPosterWidth,
                        height = ReiAnixTokens.Dimensions.myListPosterHeight,
                    )
                    .clip(ReiAnixTokens.Shapes.small),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                placeholder = "Sem arte",
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

                val metadata = buildList {
                    anime.availableContentCount
                        .takeIf { it > 0 }
                        ?.let { count ->
                            add(
                                if (count == 1) "1 episódio" else count.toString() + " episódios",
                            )
                        }
                    anime.year?.let { add(it.toString()) }
                }
                if (metadata.isNotEmpty()) {
                    ReiAnixSecondaryText(
                        text = metadata.joinToString(" • "),
                        maxLines = 1,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ReiAnixTokens.Dimensions.progressHeight),
                ) {
                    ReiAnixProgressIndicator(
                        progress = progress ?: 0f,
                        visible = progress != null,
                    )
                }
            }

            Spacer(Modifier.size(ReiAnixTokens.Spacing.sm))

            ReiAnixBadge(
                text = status.label,
                tone = status.tone,
                modifier = Modifier.semantics {
                    contentDescription = "Estado: " + status.label
                },
            )

            ReiAnixIconActionButton(
                icon = if (anime.favorite) {
                    Icons.Filled.Favorite
                } else {
                    Icons.Filled.FavoriteBorder
                },
                contentDescription = if (anime.favorite) {
                    "Remover " + anime.title + " da Minha Lista"
                } else {
                    "Adicionar " + anime.title + " à Minha Lista"
                },
                onClick = onToggleFavorite,
            )
        }
    }
}

private data class MyListStatus(
    val label: String,
    val tone: ReiAnixBadgeTone,
)

private fun myListStatus(anime: ReiAnixAnimeUiModel): MyListStatus = when {
    anime.isWatching -> MyListStatus("Assistindo", ReiAnixBadgeTone.Primary)
    anime.isCompleted -> MyListStatus("Concluído", ReiAnixBadgeTone.Success)
    anime.favorite -> MyListStatus("Favorito", ReiAnixBadgeTone.Neutral)
    else -> MyListStatus("Não salvo", ReiAnixBadgeTone.Neutral)
}

private fun myListCountLabel(count: Int): String =
    if (count == 1) "1 título salvo" else count.toString() + " títulos salvos"
