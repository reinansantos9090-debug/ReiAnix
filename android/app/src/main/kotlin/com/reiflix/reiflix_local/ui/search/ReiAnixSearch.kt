package com.reiflix.reiflix_local.ui.search

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixIconActionButton
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSearchField
import com.reiflix.reiflix_local.ui.ReiAnixScreenTitle
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryText
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixSurface
import com.reiflix.reiflix_local.ui.artwork.ReiAnixPoster
import com.reiflix.reiflix_local.ui.library.ReiAnixLibrarySort
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixSearchFilters
import com.reiflix.reiflix_local.ui.model.ReiAnixSearchUiState
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
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

private val ReiAnixFilterIcon: ImageVector = ImageVector.Builder(
    name = "ReiAnixFilter",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(androidx.compose.ui.graphics.Color.Black),
    ) {
        moveTo(4f, 6f)
        lineTo(20f, 6f)
        lineTo(14f, 13f)
        lineTo(14f, 19f)
        lineTo(10f, 21f)
        lineTo(10f, 13f)
        close()
    }
}.build()

@Composable
fun ReiAnixSearchRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
) {
    val libraryState by viewModel.libraryPresentationState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val genres by viewModel.libraryGenres.collectAsStateWithLifecycle()
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    if (showFilterSheet) {
        ReiAnixSearchFilterSheet(
            filters = searchState.filters,
            genres = genres,
            onGenreSelected = viewModel::setSearchGenreFilter,
            onToggleFavorites = viewModel::toggleSearchFavoritesFilter,
            onToggleWatching = viewModel::toggleSearchWatchingFilter,
            onToggleCompleted = viewModel::toggleSearchCompletedFilter,
            onSortSelected = viewModel::setSearchSort,
            onClear = viewModel::clearSearchFilters,
            onDismiss = { showFilterSheet = false },
        )
    }

    val previousRoute = navController.previousBackStackEntry
        ?.destination
        ?.route
    val showBackButton = previousRoute != null &&
        previousRoute !in setOf(
            ReiAnixRoutes.HOME,
            ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.SEARCH,
            ReiAnixRoutes.SETTINGS,
        )

    ReiAnixSearchScreen(
        libraryState = libraryState,
        searchQuery = searchQuery,
        searchState = searchState,
        genres = genres,
        showBackButton = showBackButton,
        onQueryChange = viewModel::setSearchQuery,
        onBack = { navController.popBackStack() },
        onRefresh = viewModel::refresh,
        onOpenFilters = { showFilterSheet = true },
        onClearFilters = viewModel::clearSearchFilters,
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
    libraryState: com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPresentationUiState,
    searchQuery: String,
    searchState: ReiAnixSearchUiState,
    genres: List<ReiAnixGenreUiModel> = emptyList(),
    showBackButton: Boolean = false,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenFilters: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    onOpenDetails: (Long) -> Unit = {},
) {
    ReiAnixResponsiveRoot {
    val listState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    top = ReiAnixTokens.Dimensions.screenTopPadding,
                    end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    bottom = ReiAnixTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBackButton) {
                ReiAnixIconActionButton(
                    icon = Icons.Filled.ArrowBack,
                    contentDescription = "Voltar da busca",
                    onClick = onBack,
                )
                Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.xs))
            }

            ReiAnixScreenTitle(
                title = "Buscar",
                modifier = Modifier.weight(1f),
            )

        }

        ReiAnixSearchField(
            value = searchQuery,
            onValueChange = onQueryChange,
            accessibilityLabel = "Pesquisar na biblioteca",
            modifier = Modifier.padding(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            ),
            placeholder = {
                Text("Digite o nome do anime, gênero ou estúdio...")
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.semantics {
                                contentDescription = "Limpar busca"
                                role = Role.Button
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = null,
                            )
                        }
                    }
                    IconButton(
                        onClick = onOpenFilters,
                        modifier = Modifier.semantics {
                            contentDescription = if (searchState.filters.hasAnyFilter) {
                                "Filtros ativos"
                            } else {
                                "Abrir filtros"
                            }
                            role = Role.Button
                        },
                    ) {
                        Icon(
                            imageVector = ReiAnixFilterIcon,
                            contentDescription = null,
                            tint = if (searchState.filters.hasAnyFilter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
            ),
        )

        if (searchState.filters.hasAnyFilter) {
            SearchActiveFilters(
                filters = searchState.filters,
                genres = genres,
                onClear = onClearFilters,
            )
        }

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

                ReiAnixLibraryLoadStatus.READY -> when {
                    searchState.error != null -> ReiAnixRecoverableErrorState(
                        title = "Erro na busca",
                        message = searchState.error,
                        onRetry = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                        retryLabel = "Tentar novamente",
                    )

                    searchState.query.isBlank() -> SearchEmptyQueryState(
                        librarySize = libraryState.animeCount,
                    )

                    searchState.results.isEmpty() -> SearchNoResultsState(
                        query = searchState.query,
                        filtersActive = searchState.filters.hasAnyFilter,
                    )

                    else -> SearchResults(
                        results = searchState.results,
                        listState = listState,
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
    listState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onOpenDetails: (Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(
            minSize = LocalReiAnixResponsiveMetrics.current.searchGridMinWidth,
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
        item(
            key = "search-results-header",
            contentType = "search-results-header",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = ReiAnixTokens.Spacing.xs,
                        bottom = ReiAnixTokens.Spacing.xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Resultados",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.sm))
                ReiAnixBadge(
                    text = results.size.toString(),
                    tone = ReiAnixBadgeTone.Neutral,
                )
            }
        }

        items(
            items = results,
            key = { anime -> anime.stableKey },
            contentType = { "search-result-anime-row" },
        ) { anime ->
            SearchResultRow(
                anime = anime,
                onClick = { onOpenDetails(anime.id) },
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    anime: ReiAnixAnimeUiModel,
    onClick: () -> Unit,
) {
    val metadata = buildList {
        anime.year?.let { add(it.toString()) }
        anime.genres
            .asSequence()
            .map { it.name.trim() }
            .filter(String::isNotEmpty)
            .take(3)
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }
            ?.let(::add)
    }
    val progressEpisode = anime.playbackTargetEpisodeId
        ?.let { id -> anime.contentEpisodes.firstOrNull { it.id == id } }
        ?: anime.contentEpisodes.firstOrNull {
            it.progressFraction > 0f && !it.isCompleted
        }
    val progress = progressEpisode
        ?.progressFraction
        ?.takeIf { it > 0f && it < 1f }

    ReiAnixSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = buildList {
                    add(anime.title)
                    metadata.joinToString(" • ").takeIf { it.isNotBlank() }?.let(::add)
                    progress?.let { add(((it.coerceIn(0f, 1f) * 100f).toInt()).toString() + "% assistido") }
                }.joinToString(", ").let { "Abrir " + it }
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
                contentDescription = null,
                identity = anime.stableKey,
                modifier = Modifier
                    .size(
                        width = ReiAnixTokens.Dimensions.myListPosterWidth,
                        height = ReiAnixTokens.Dimensions.myListPosterHeight,
                    )
                    .clip(ReiAnixTokens.Shapes.small),
                maxDimensionPx = 320,
            )

            Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.md))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = ReiAnixTokens.Spacing.xs)
                    .clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = anime.title,
                    style = ReiAnixTokens.TypographyTokens.cardTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metadata.isNotEmpty()) {
                    ReiAnixSecondaryText(
                        text = metadata.joinToString(" • "),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ReiAnixTokens.Dimensions.progressHeight),
                ) {
                    ReiAnixProgressIndicator(
                        progress = progress ?: 0f,
                        visible = progress != null,
                        announceProgress = false,
                    )
                }
            }

            Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.xs))

            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
            )
        }
    }
}

@Composable
private fun SearchEmptyQueryState(
    librarySize: Int,
) {
    ReiAnixEmptyState(
        title = "Pesquise na biblioteca",
        message = if (librarySize == 0) {
            "Digite o nome do anime, gênero ou estúdio para pesquisar no conteúdo local."
        } else {
            "Digite o nome do anime, gênero ou estúdio para pesquisar entre " +
                librarySize.toString() +
                if (librarySize == 1) " título local." else " títulos locais."
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding),
    )
}

@Composable
private fun SearchNoResultsState(
    query: String,
    filtersActive: Boolean,
) {
    ReiAnixEmptyState(
        title = "Nenhum resultado encontrado",
        message = if (filtersActive) {
            "Nenhum conteúdo local corresponde à busca: " +
                query.trim() +
                " com os filtros atuais."
        } else {
            "Tente pesquisar por outro nome, ano, gênero ou episódio."
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding),
    )
}

@Composable
private fun SearchActiveFilters(
    filters: ReiAnixSearchFilters,
    genres: List<ReiAnixGenreUiModel>,
    onClear: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ReiAnixTokens.Spacing.sm),
        contentPadding = PaddingValues(
            horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        if (filters.favoritesOnly) {
            item(key = "active-filter-favorites") {
                ReiAnixChip(
                    text = "Favoritos",
                    selected = true,
                    onClick = onClear,
                )
            }
        }
        if (filters.watchingOnly) {
            item(key = "active-filter-watching") {
                ReiAnixChip(
                    text = "Assistindo",
                    selected = true,
                    onClick = onClear,
                )
            }
        }
        if (filters.completedOnly) {
            item(key = "active-filter-completed") {
                ReiAnixChip(
                    text = "Completos",
                    selected = true,
                    onClick = onClear,
                )
            }
        }
        if (filters.selectedGenreKey != null) {
            item(key = "active-filter-genre") {
                ReiAnixChip(
                    text = genres.firstOrNull {
                        it.stableKey == filters.selectedGenreKey
                    }?.name ?: "Gênero selecionado",
                    selected = true,
                    onClick = onClear,
                )
            }
        }
        if (!filters.sort.isNullOrBlank()) {
            item(key = "active-filter-sort") {
                ReiAnixChip(
                    text = filters.sort.orEmpty(),
                    selected = true,
                    onClick = onClear,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReiAnixSearchFilterSheet(
    filters: ReiAnixSearchFilters,
    genres: List<ReiAnixGenreUiModel>,
    onGenreSelected: (String?) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleWatching: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSortSelected: (String?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    bottom = ReiAnixTokens.Spacing.huge,
                ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReiAnixScreenTitle(
                    title = "Filtros",
                    modifier = Modifier.weight(1f),
                )
                if (filters.hasAnyFilter) {
                    TextButton(onClick = onClear) {
                        Text("Limpar")
                    }
                }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                contentPadding = PaddingValues(end = ReiAnixTokens.Spacing.sm),
            ) {
                item(key = "search-filter-favorites") {
                    ReiAnixChip(
                        text = "Favoritos",
                        selected = filters.favoritesOnly,
                        onClick = onToggleFavorites,
                    )
                }
                item(key = "search-filter-watching") {
                    ReiAnixChip(
                        text = "Assistindo",
                        selected = filters.watchingOnly,
                        onClick = onToggleWatching,
                    )
                }
                item(key = "search-filter-completed") {
                    ReiAnixChip(
                        text = "Completos",
                        selected = filters.completedOnly,
                        onClick = onToggleCompleted,
                    )
                }
            }

            if (genres.isNotEmpty()) {
                Text(
                    text = "Gêneros",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                    contentPadding = PaddingValues(end = ReiAnixTokens.Spacing.sm),
                ) {
                    genres.forEach { genre ->
                        item(key = "search-genre-" + genre.stableKey) {
                            ReiAnixChip(
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
            }

            Box {
                ReiAnixChip(
                    text = filters.sort ?: "Ordenar",
                    selected = !filters.sort.isNullOrBlank(),
                    onClick = { sortMenuExpanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = if (filters.sort.isNullOrBlank()) {
                            "Ordenar resultados"
                        } else {
                            "Ordenação atual: " + filters.sort
                        }
                    },
                )
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Relevância") },
                        onClick = {
                            sortMenuExpanded = false
                            onSortSelected(null)
                        },
                    )
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

            Text(
                text = if (filters.hasAnyFilter) {
                    "Os filtros refinam os resultados locais da busca."
                } else {
                    "Use filtros somente quando precisar refinar a busca."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
