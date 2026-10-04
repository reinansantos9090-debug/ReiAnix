package com.reiflix.reiflix_local.ui.mylist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixAnimeCard
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixScreenTitle
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
    val favorites = state.animes
        .asSequence()
        .filter { it.favorite }
        .sortedBy { it.title.lowercase() }
        .toList()

    when (state.status) {
        ReiAnixLibraryLoadStatus.LOADING -> ReiAnixLoadingState(
            title = "Carregando Minha Lista",
            message = "Lendo os títulos favoritos da biblioteca local…",
            modifier = Modifier.fillMaxSize(),
        )

        ReiAnixLibraryLoadStatus.ERROR -> ReiAnixRecoverableErrorState(
            title = "Não foi possível carregar Minha Lista",
            message = state.error ?: "A biblioteca local retornou um erro.",
            onRetry = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        )

        ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> ReiAnixRecoverableErrorState(
            title = "Minha Lista indisponível",
            message = "A fonte local configurada não está disponível agora.",
            onRetry = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        )

        ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyState(
            title = "Minha Lista está vazia",
            message = "Adicione títulos à Minha Lista para encontrá-los aqui.",
            actionLabel = "Abrir Biblioteca",
            onAction = { navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY) },
            modifier = Modifier.fillMaxSize(),
        )

        ReiAnixLibraryLoadStatus.READY -> ReiAnixMyListContent(
            favorites = favorites,
            onOpenDetails = { animeId ->
                navController.navigateToDetails(
                    animeId = animeId.toString(),
                    origin = ReiAnixRoutes.MY_LIST,
                )
            },
            onToggleFavorite = viewModel::toggleFavorite,
        )
    }
}

@Composable
private fun ReiAnixMyListContent(
    favorites: List<ReiAnixAnimeUiModel>,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = ReiAnixTokens.Dimensions.screenTopPadding),
    ) {
        ReiAnixScreenTitle(
            title = "Minha Lista",
            subtitle = favorites.size.toString() + " " +
                if (favorites.size == 1) "título salvo" else "títulos salvos",
            modifier = Modifier.padding(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            ),
        )

        if (favorites.isEmpty()) {
            ReiAnixEmptyState(
                title = "Nenhum título salvo",
                message = "Os títulos marcados como favoritos aparecerão aqui.",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(
                    minSize = ReiAnixTokens.Dimensions.libraryGridMinWidth,
                ),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                    end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                    top = ReiAnixTokens.Spacing.lg,
                    bottom = ReiAnixTokens.Spacing.huge,
                ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
            ) {
                items(
                    items = favorites,
                    key = { anime -> anime.stableKey },
                    contentType = { "my-list-anime" },
                ) { anime ->
                    ReiAnixAnimeCard(
                        title = anime.title,
                        artworkPath = anime.artwork?.localPath,
                        metadata = buildList {
                            anime.year?.toString()?.let(::add)
                            anime.episodeCountLabel.takeIf {
                                anime.availableContentCount > 0
                            }?.let(::add)
                        },
                        progress = anime.contentEpisodes
                            .firstOrNull {
                                it.progressFraction > 0f && !it.isCompleted
                            }
                            ?.progressFraction,
                        favorite = true,
                        watching = anime.isWatching,
                        watched = anime.contentEpisodes.any { it.isWatched },
                        completed = anime.isCompleted,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenDetails(anime.id) },
                        onFavoriteClick = { onToggleFavorite(anime.id) },
                    )
                }
            }
        }
    }
}
