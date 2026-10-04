package com.reiflix.reiflix_local.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtwork
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixSearchRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
) {
    val libraryState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()

    ReiAnixSearchScreen(
        libraryState = libraryState,
        query = searchState.query,
        results = searchState.results,
        showBackButton = navController.previousBackStackEntry != null,
        onQueryChange = viewModel::setSearchQuery,
        onBack = { navController.popBackStack() },
        onRefresh = viewModel::refresh,
        onOpenDetails = { animeId ->
            navController.navigateToDetails(
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.SEARCH,
            )
        },
    )
}

@Composable
fun ReiAnixSearchScreen(
    libraryState: ReiAnixLibraryUiState,
    query: String,
    results: List<ReiAnixAnimeUiModel>,
    showBackButton: Boolean = false,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenDetails: (Long) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                    vertical = ReiAnixTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBackButton) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics {
                        contentDescription = "Voltar da busca"
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = null,
                    )
                }
            }

            Text(
                text = "Buscar",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onRefresh,
                modifier = Modifier.semantics {
                    contentDescription = "Atualizar biblioteca local"
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding),
            singleLine = true,
            label = { Text("Pesquisar na biblioteca") },
            placeholder = { Text("Título, gênero, ano ou episódio") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.semantics {
                            contentDescription = "Limpar busca"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = null,
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (libraryState.status) {
            ReiAnixLibraryLoadStatus.LOADING -> ReiAnixLoadingState(
                title = "Carregando pesquisa",
                message = if (libraryState.scanInProgress) {
                    "Carregando enquanto a varredura continua…"
                } else {
                    "Lendo a biblioteca local…"
                },
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixLibraryLoadStatus.ERROR -> ReiAnixRecoverableErrorState(
                title = "Não foi possível pesquisar",
                message = libraryState.error ?: "A biblioteca local retornou um erro.",
                onRetry = onRefresh,
                modifier = Modifier.fillMaxSize(),
                retryLabel = "Atualizar",
            )

            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> ReiAnixSourceUnavailableState(
                title = "Biblioteca local indisponível",
                message = "A fonte local configurada não está disponível agora.",
                onAction = onRefresh,
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyLibraryState(
                message = "Nenhum conteúdo local disponível para pesquisa.",
                actionLabel = "Atualizar",
                onAction = onRefresh,
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixLibraryLoadStatus.READY -> {
                when {
                    query.isBlank() -> SearchEmptyQueryState(
                        librarySize = libraryState.animes.size,
                    )

                    results.isEmpty() -> SearchNoResultsState(query = query)

                    else -> SearchResults(
                        results = results,
                        onOpenDetails = onOpenDetails,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<ReiAnixAnimeUiModel>,
    onOpenDetails: (Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(top = ReiAnixTokens.Spacing.sm),
        contentPadding = PaddingValues(
            start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            top = ReiAnixTokens.Spacing.sm,
            bottom = ReiAnixTokens.Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        items(
            items = results,
            key = { anime -> anime.stableKey },
            contentType = { "search-result-anime" },
        ) { anime ->
            SearchResultCard(
                anime = anime,
                onClick = { onOpenDetails(anime.id) },
            )
        }
    }
}

@Composable
private fun SearchResultCard(
    anime: ReiAnixAnimeUiModel,
    onClick: () -> Unit,
) {
    val metadata = buildList {
        anime.year?.let { add(it.toString()) }
        when (anime.mediaKind) {
            ReiAnixMediaKind.SERIES -> add("Série")
            ReiAnixMediaKind.MOVIE -> add("Filme")
            ReiAnixMediaKind.UNKNOWN -> Unit
        }
        if (anime.genres.isNotEmpty()) {
            add(anime.genres.joinToString(" • ") { it.name })
        }
    }

    ReiAnixAnimeCard(
        title = anime.title,
        artworkPath = anime.artwork?.localPath,
        metadata = metadata,
        progress = anime.contentEpisodes
            .firstOrNull { it.progressFraction > 0f && !it.isCompleted }
            ?.progressFraction,
        favorite = anime.favorite,
        watched = anime.contentEpisodes.any { it.isWatched },
        completed = anime.isCompleted,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        maxDimensionPx = 512,
    )
}

@Composable
private fun SearchEmptyQueryState(
    librarySize: Int,
) {
    ReiAnixEmptyState(
        title = "Pesquise na biblioteca",
        message = if (librarySize == 1) {
            "1 título local disponível."
        } else {
            "$librarySize títulos locais disponíveis."
        },
    )
}

@Composable
private fun SearchNoResultsState(
    query: String,
) {
    ReiAnixEmptyState(
        title = "Nenhum resultado",
        message = "Nenhum conteúdo local corresponde a \"" + query.trim() + "\".",
    )
}

@Composable
private fun SearchLoading(
    scanInProgress: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
            modifier = Modifier.padding(ReiAnixTokens.Dimensions.screenHorizontalPadding),
        ) {
            CircularProgressIndicator()
            Text(
                text = if (scanInProgress) {
                    "Carregando biblioteca enquanto a varredura continua…"
                } else {
                    "Carregando biblioteca…"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchMessageState(
    title: String,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ReiAnixTokens.Dimensions.screenHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = ReiAnixTokens.Spacing.sm),
        )
        if (actionLabel != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = ReiAnixTokens.Spacing.lg),
            ) {
                Text(actionLabel)
            }
        }
    }
}
