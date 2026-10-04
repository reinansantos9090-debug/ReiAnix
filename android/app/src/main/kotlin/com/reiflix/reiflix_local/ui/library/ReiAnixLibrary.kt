package com.reiflix.reiflix_local.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixScannerInProgressState
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtwork
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
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

    ReiAnixLibraryScreen(
        state = state,
        filters = filters,
        visibleAnimes = visibleAnimes,
        genres = genres,
        onQueryChange = viewModel::setLibrarySearchQuery,
        onGenreSelected = viewModel::setLibraryGenreFilter,
        onToggleFavorites = viewModel::toggleLibraryFavoritesFilter,
        onToggleWatching = viewModel::toggleLibraryWatchingFilter,
        onToggleCompleted = viewModel::toggleLibraryCompletedFilter,
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

    ReiAnixLibraryScreen(
        state = state,
        filters = filters,
        visibleAnimes = visibleAnimes,
        genres = genres,
        onQueryChange = viewModel::setLibrarySearchQuery,
        onGenreSelected = viewModel::setLibraryGenreFilter,
        onToggleFavorites = viewModel::toggleLibraryFavoritesFilter,
        onToggleWatching = viewModel::toggleLibraryWatchingFilter,
        onToggleCompleted = viewModel::toggleLibraryCompletedFilter,
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
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit = {},
    onSearch: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReiAnixTokens.Colors.background),
    ) {
        LibraryHeader(
            onRefresh = onRefresh,
            onSearch = onSearch,
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> if (state.scanInProgress) {
                ReiAnixScannerInProgressState(
                    scanState = state.scanState,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ReiAnixLoadingState(
                    title = "Carregando biblioteca",
                    message = "Lendo o catálogo local…",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ReiAnixLibraryLoadStatus.ERROR -> ReiAnixRecoverableErrorState(
                title = "Erro na biblioteca",
                message = state.error ?: "Não foi possível carregar a biblioteca local.",
                onRetry = onRefresh,
                modifier = Modifier.fillMaxSize(),
            )
            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> ReiAnixSourceUnavailableState(
                title = "Biblioteca local indisponível",
                message = "A fonte local configurada não está disponível agora.",
                onAction = onRefresh,
                modifier = Modifier.fillMaxSize(),
            )
            ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyLibraryState(
                message = "Nenhum conteúdo local está disponível.",
                actionLabel = "Atualizar",
                onAction = onRefresh,
                modifier = Modifier.fillMaxSize(),
            )
            ReiAnixLibraryLoadStatus.READY -> LibraryReadyContent(
                state = state,
                filters = filters,
                visibleAnimes = visibleAnimes,
                genres = genres,
                onQueryChange = onQueryChange,
                onGenreSelected = onGenreSelected,
                onToggleFavorites = onToggleFavorites,
                onToggleWatching = onToggleWatching,
                onToggleCompleted = onToggleCompleted,
                onClearFilters = onClearFilters,
                onOpenDetails = onOpenDetails,
                onToggleFavorite = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun LibraryHeader(
    onRefresh: () -> Unit,
    onSearch: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                vertical = ReiAnixTokens.Spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Biblioteca",
                style = MaterialTheme.typography.headlineSmall,
                color = ReiAnixTokens.Colors.text,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Seu conteúdo local",
                style = MaterialTheme.typography.bodyMedium,
                color = ReiAnixTokens.Colors.textMuted,
            )
        }
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
            modifier = Modifier.semantics {
                contentDescription = "Atualizar biblioteca"
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = ReiAnixTokens.Colors.text,
            )
        }
    }
}

@Composable
private fun ColumnScope.LibraryReadyContent(
    state: ReiAnixLibraryUiState,
    filters: ReiAnixLibraryFilters,
    visibleAnimes: List<ReiAnixAnimeUiModel>,
    genres: List<ReiAnixGenreUiModel>,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onClearFilters: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding),
            singleLine = true,
            label = { Text("Pesquisar na biblioteca") },
            placeholder = { Text("Título ou gênero") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (filters.query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Limpar pesquisa",
                        )
                    }
                }
            },
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                    top = ReiAnixTokens.Spacing.sm,
                    bottom = ReiAnixTokens.Spacing.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            contentPadding = PaddingValues(end = ReiAnixTokens.Dimensions.screenHorizontalPadding),
        ) {
            item(key = "filter-title") {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Filtros") },
                )
            }
            item(key = "filter-favorite") {
                LibraryFilterChip("Favoritos", filters.favoritesOnly, onToggleFavorites)
            }
            item(key = "filter-watching") {
                LibraryFilterChip("Assistindo", filters.watchingOnly, onToggleWatching)
            }
            item(key = "filter-completed") {
                LibraryFilterChip("Completos", filters.completedOnly, onToggleCompleted)
            }
            genres.forEach { genre ->
                item(key = "genre-" + genre.stableKey) {
                    LibraryFilterChip(
                        text = genre.name,
                        selected = filters.selectedGenreKey == genre.stableKey,
                        onClick = {
                            onGenreSelected(
                                genre.stableKey.takeUnless { it == filters.selectedGenreKey },
                            )
                        },
                    )
                }
            }
        }

        if (state.scanInProgress) {
            ReiAnixScannerInProgressState(
                scanState = state.scanState,
                compact = true,
                modifier = Modifier.padding(horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding),
            )
        }

        if (visibleAnimes.isEmpty()) {
            ReiAnixEmptyLibraryState(
                title = if (filters.hasAnyFilter) "Nenhum resultado" else "Nenhum conteúdo",
                message = if (filters.hasAnyFilter) {
                    "Nenhum título corresponde aos filtros atuais."
                } else {
                    "A biblioteca local ainda não possui conteúdo visível."
                },
                actionLabel = if (filters.hasAnyFilter) "Limpar filtros" else null,
                onAction = if (filters.hasAnyFilter) onClearFilters else null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
private fun LibraryFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) {
                ReiAnixTokens.Colors.primaryContainer
            } else {
                ReiAnixTokens.Colors.surfaceVariant
            },
            labelColor = ReiAnixTokens.Colors.text,
        ),
    )
}

@Composable
private fun LibraryAnimeCard(
    anime: ReiAnixAnimeUiModel,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ReiAnixTokens.Shapes.card)
            .background(ReiAnixTokens.Colors.surface)
            .clickable(onClick = onClick)
            .padding(ReiAnixTokens.Spacing.sm)
            .semantics {
                contentDescription = "Abrir " + anime.title
            },
    ) {
        ReiAnixLocalArtwork(
            localPath = anime.artwork?.localPath,
            contentDescription = anime.title + " artwork",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(ReiAnixTokens.Shapes.artwork),
            placeholder = "Sem arte",
            maxDimensionPx = 512,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ReiAnixTokens.Spacing.xs,
                    top = ReiAnixTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ReiAnixTokens.Colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = anime.episodeCountLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = ReiAnixTokens.Colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.semantics {
                    contentDescription = if (anime.favorite) {
                        "Remover da Minha Lista"
                    } else {
                        "Adicionar à Minha Lista"
                    }
                },
            ) {
                Icon(
                    imageVector = if (anime.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (anime.favorite) {
                        ReiAnixTokens.Colors.warning
                    } else {
                        ReiAnixTokens.Colors.textMuted
                    },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ReiAnixTokens.Spacing.xs,
                    top = ReiAnixTokens.Spacing.xs,
                    end = ReiAnixTokens.Spacing.xs,
                ),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            if (anime.isWatching) {
                LibraryStateBadge("Assistindo")
            }
            if (anime.isCompleted) {
                LibraryStateBadge("Concluído")
            }
            if (anime.favorite) {
                LibraryStateBadge("Na lista")
            }
        }
    }
}

@Composable
private fun LibraryStateBadge(
    text: String,
) {
    Surface(
        shape = ReiAnixTokens.Shapes.chip,
        color = ReiAnixTokens.Colors.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = ReiAnixTokens.Colors.text,
            modifier = Modifier.padding(
                horizontal = ReiAnixTokens.Spacing.sm,
                vertical = ReiAnixTokens.Spacing.xs,
            ),
        )
    }
}

@Composable
private fun LibraryScanBanner(
    scanState: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ReiAnixTokens.Shapes.small)
            .background(ReiAnixTokens.Colors.surfaceVariant)
            .padding(ReiAnixTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Varredura em andamento",
                style = MaterialTheme.typography.titleMedium,
                color = ReiAnixTokens.Colors.text,
            )
            Text(
                text = scanState.ifBlank { "SCANNING" },
                style = MaterialTheme.typography.bodySmall,
                color = ReiAnixTokens.Colors.textMuted,
            )
        }
    }
}

@Composable
private fun LibraryLoading(
    scanInProgress: Boolean,
    scanState: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(ReiAnixTokens.Dimensions.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            CircularProgressIndicator()
            Text(
                text = if (scanInProgress) {
                    "Carregando enquanto a biblioteca é atualizada…"
                } else {
                    "Carregando biblioteca…"
                },
                color = ReiAnixTokens.Colors.textMuted,
            )
            if (scanInProgress) {
                Text(
                    text = "Varredura em andamento" + scanState.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty(),
                    color = ReiAnixTokens.Colors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LibraryMessageState(
    title: String,
    message: String,
    actionLabel: String,
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
            color = ReiAnixTokens.Colors.text,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = ReiAnixTokens.Colors.textMuted,
            modifier = Modifier.padding(top = ReiAnixTokens.Spacing.sm),
        )
        Button(
            onClick = onAction,
            modifier = Modifier.padding(top = ReiAnixTokens.Spacing.lg),
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun LibraryFilteredEmptyState(
    filters: ReiAnixLibraryFilters,
    onClearFilters: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                vertical = ReiAnixTokens.Spacing.xxxl,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        Text(
            text = if (filters.hasAnyFilter) "Nenhum resultado" else "Nenhum conteúdo",
            style = MaterialTheme.typography.titleLarge,
            color = ReiAnixTokens.Colors.text,
        )
        if (filters.hasAnyFilter) {
            Text(
                text = "Nenhum título corresponde aos filtros atuais.",
                color = ReiAnixTokens.Colors.textMuted,
            )
            OutlinedButton(onClick = onClearFilters) {
                Text("Limpar filtros")
            }
        }
    }
}
