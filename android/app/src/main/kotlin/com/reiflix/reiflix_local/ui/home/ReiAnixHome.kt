package com.reiflix.reiflix_local.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixAnimeCard
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixMetadata
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSectionTitle
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.artwork.ReiAnixBackdrop
import com.reiflix.reiflix_local.ui.artwork.ReiAnixEpisodeThumbnail
import com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtwork
import com.reiflix.reiflix_local.ui.library.rememberReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.navigation.navigateToMyList
import com.reiflix.reiflix_local.ui.navigation.navigateToPlayer
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixHomeRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel = rememberReiAnixLibraryViewModel(),
) {
    val state by viewModel.homeState.collectAsStateWithLifecycle()
    val hasContinueWatching by viewModel.hasContinueWatching.collectAsStateWithLifecycle()

    ReiAnixHomeObservedScreen(
        state = state,
        showContinueWatching = hasContinueWatching,
        viewModel = viewModel,
        onSearch = {
            navController.navigateToTopLevel(ReiAnixRoutes.SEARCH)
        },
        onOpenDetails = { animeId ->
            navController.navigateToDetails(
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.HOME,
            )
        },
        onWatch = { episodeId, animeId ->
            navController.navigateToPlayer(
                episodeId = episodeId.toString(),
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.HOME,
            )
        },
        onToggleFavorite = viewModel::toggleFavorite,
        onOpenMyList = {
            navController.navigateToMyList()
        },
        onRefresh = viewModel::refresh,
    )
}

@Composable
private fun ReiAnixHomeObservedScreen(
    state: ReiAnixHomeLibraryUiState,
    showContinueWatching: Boolean,
    viewModel: ReiAnixLibraryViewModel,
    onSearch: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HomeHeader(
            sourceAvailable = state.sourceAvailable,
            onSearch = onSearch,
            onRefresh = onRefresh,
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> ReiAnixLoadingState(
                title = "Carregando biblioteca",
                message = "Lendo o catálogo local…",
                modifier = Modifier.fillMaxSize(),
            )
            ReiAnixLibraryLoadStatus.ERROR -> ReiAnixRecoverableErrorState(
                title = "Não foi possível carregar a biblioteca",
                message = state.error ?: "A biblioteca local retornou um erro.",
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
                message = "Nenhum conteúdo local disponível.",
                actionLabel = "Atualizar",
                onAction = onRefresh,
                modifier = Modifier.fillMaxSize(),
            )
            ReiAnixLibraryLoadStatus.READY -> HomeObservedContent(
                state = state,
                showContinueWatching = showContinueWatching,
                viewModel = viewModel,
                onOpenDetails = onOpenDetails,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onOpenMyList = onOpenMyList,
            )
        }
    }
}

@Composable
private fun ColumnScope.HomeObservedContent(
    state: ReiAnixHomeLibraryUiState,
    showContinueWatching: Boolean,
    viewModel: ReiAnixLibraryViewModel,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
) {
    val listState = rememberLazyListState()
    val renderAnimes = remember(state.animes) {
        state.animes.map(ReiAnixHomeAnimeUiModel::toHomeRenderData)
    }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(
            bottom = ReiAnixTokens.Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xxl),
    ) {
        if (renderAnimes.isNotEmpty()) {
            item(key = "home-hero") {
                HomeHero(
                    anime = selectFeaturedAnime(renderAnimes),
                    onWatch = onWatch,
                    onOpenDetails = onOpenDetails,
                    onToggleFavorite = onToggleFavorite,
                )
            }
            item(key = "home-categories") {
                HomeCategoryRow(animes = renderAnimes)
            }
        }

        if (showContinueWatching) {
            item(key = "home-section-continue") {
                HomeContinueWatchingObserved(
                    viewModel = viewModel,
                    onWatch = onWatch,
                )
            }
        }

        val favorites = renderAnimes.filter(HomeAnimeRenderData::favorite)
        if (favorites.isNotEmpty()) {
            item(key = "home-section-my-list") {
                HomeAnimeSection(
                    items = favorites,
                    onOpenDetails = onOpenDetails,
                    onSeeAll = onOpenMyList,
                )
            }
        }

        buildHomeGenreSections(renderAnimes).forEach { section ->
            item(key = "home-section-genre:" + section.key) {
                HomeMediaSection(
                    title = section.title,
                    items = section.items,
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home " + section.title,
                )
            }
        }

        val movies = renderAnimes.filter { it.mediaKind == ReiAnixMediaKind.MOVIE }
        if (movies.isNotEmpty()) {
            item(key = "home-section-movies") {
                HomeMediaSection(
                    title = "Filmes",
                    items = movies,
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Filmes",
                )
            }
        }
    }
}

@Composable
private fun HomeContinueWatchingObserved(
    viewModel: ReiAnixLibraryViewModel,
    onWatch: (Long, Long) -> Unit,
) {
    val items by viewModel.continueWatching.collectAsStateWithLifecycle()
    if (items.isNotEmpty()) {
        HomeContinueSection(
            items = items,
            onWatch = onWatch,
        )
    }
}

@Composable
fun ReiAnixHomeScreen(
    state: ReiAnixLibraryUiState,
    onSearch: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onWatch: (episodeId: Long, animeId: Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onRefresh: () -> Unit,
    onOpenMyList: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HomeHeader(
            sourceAvailable = state.sourceAvailable,
            onSearch = onSearch,
            onRefresh = onRefresh,
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> HomeLoading()
            ReiAnixLibraryLoadStatus.ERROR -> HomeError(
                message = state.error ?: "Não foi possível carregar a biblioteca local.",
                onRefresh = onRefresh,
            )
            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> HomeMessage(
                title = "Biblioteca local indisponível",
                message = "A fonte local configurada não está disponível agora.",
                onRefresh = onRefresh,
            )
            ReiAnixLibraryLoadStatus.EMPTY -> HomeMessage(
                title = "Biblioteca vazia",
                message = "Nenhum conteúdo local disponível.",
                onRefresh = onRefresh,
            )
            ReiAnixLibraryLoadStatus.READY -> HomeContent(
                state = state,
                onOpenDetails = onOpenDetails,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onOpenMyList = onOpenMyList,
            )
        }
    }
}

@Composable
private fun HomeHeader(
    sourceAvailable: Boolean,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
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
        Text(
            text = "ReiAnix",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )

        Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.sm))

        Surface(
            shape = ReiAnixTokens.Shapes.chip,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = if (sourceAvailable) "Offline" else "Indisponível",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(
                    horizontal = ReiAnixTokens.Spacing.md,
                    vertical = ReiAnixTokens.Spacing.xs,
                ),
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.semantics {
                    contentDescription = "Atualizar biblioteca local"
                    role = Role.Button
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onSearch,
                modifier = Modifier.semantics {
                    contentDescription = "Pesquisar na biblioteca"
                    role = Role.Button
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.HomeContent(
    state: ReiAnixLibraryUiState,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
) {
    val listState = rememberLazyListState()
    val renderAnimes = remember(state.animes) {
        state.animes.map(ReiAnixAnimeUiModel::toHomeRenderData)
    }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(
            bottom = ReiAnixTokens.Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xxl),
    ) {
        if (renderAnimes.isNotEmpty()) {
            item(key = "home-hero") {
                HomeHero(
                    anime = selectFeaturedAnime(renderAnimes),
                    onWatch = onWatch,
                    onOpenDetails = onOpenDetails,
                    onToggleFavorite = onToggleFavorite,
                )
            }
            item(key = "home-categories") {
                HomeCategoryRow(animes = renderAnimes)
            }
        }

        val continueWatching = state.continueWatching
        if (continueWatching.isNotEmpty()) {
            item(key = "home-section-continue") {
                HomeContinueSection(
                    items = continueWatching,
                    onWatch = onWatch,
                )
            }
        }

        val favorites = renderAnimes.filter(HomeAnimeRenderData::favorite)
        if (favorites.isNotEmpty()) {
            item(key = "home-section-my-list") {
                HomeAnimeSection(
                    items = favorites,
                    onOpenDetails = onOpenDetails,
                    onSeeAll = onOpenMyList,
                )
            }
        }

        buildHomeGenreSections(renderAnimes).forEach { section ->
            item(key = "home-section-genre:" + section.key) {
                HomeMediaSection(
                    title = section.title,
                    items = section.items,
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home " + section.title,
                )
            }
        }

        val movies = renderAnimes.filter { it.mediaKind == ReiAnixMediaKind.MOVIE }
        if (movies.isNotEmpty()) {
            item(key = "home-section-movies") {
                HomeMediaSection(
                    title = "Filmes",
                    items = movies,
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Filmes",
                )
            }
        }
    }
}

private data class HomeAnimeRenderData(
    val id: Long,
    val title: String,
    val year: Int?,
    val genres: List<com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel>,
    val favorite: Boolean,
    val mediaKind: ReiAnixMediaKind,
    val artworkPath: String?,
    val playbackEpisodeId: Long?,
    val availableContentCount: Int,
) {
    val stableKey: String
        get() = "anime:" + id
}

private data class HomeGenreSection(
    val key: String,
    val title: String,
    val items: List<HomeAnimeRenderData>,
)

private fun ReiAnixAnimeUiModel.toHomeRenderData(): HomeAnimeRenderData =
    HomeAnimeRenderData(
        id = id,
        title = title,
        year = year,
        genres = genres,
        favorite = favorite,
        mediaKind = mediaKind,
        artworkPath = artwork?.localPath,
        playbackEpisodeId = playbackTargetEpisodeId,
        availableContentCount = availableContentCount(this),
    )

private fun ReiAnixHomeAnimeUiModel.toHomeRenderData(): HomeAnimeRenderData =
    HomeAnimeRenderData(
        id = id,
        title = title,
        year = year,
        genres = genres,
        favorite = favorite,
        mediaKind = mediaKind,
        artworkPath = artwork?.localPath,
        playbackEpisodeId = playbackTargetEpisodeId,
        availableContentCount = availableContentCount,
    )

private fun selectFeaturedAnime(items: List<HomeAnimeRenderData>): HomeAnimeRenderData =
    items.firstOrNull { it.playbackEpisodeId != null }
        ?: items.firstOrNull { it.favorite }
        ?: items.firstOrNull()
        ?: error("Home hero requested without library data")

private fun buildHomeCategoryNames(
    items: List<HomeAnimeRenderData>,
): List<String> =
    items.asSequence()
        .flatMap { anime -> anime.genres.asSequence().map { it.name } }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase() }
        .take(5)
        .toList()

private fun buildHomeGenreSections(
    items: List<HomeAnimeRenderData>,
): List<HomeGenreSection> {
    data class MutableSection(
        val key: String,
        val title: String,
        val items: MutableList<HomeAnimeRenderData>,
    )

    val sections = linkedMapOf<String, MutableSection>()
    items.forEach { anime ->
        anime.genres.forEach { genre ->
            val title = genre.name.trim()
            if (title.isNotEmpty()) {
                val key = genre.stableKey
                val section = sections.getOrPut(key) {
                    MutableSection(
                        key = key,
                        title = title,
                        items = mutableListOf(),
                    )
                }
                if (section.items.none { it.id == anime.id }) {
                    section.items += anime
                }
            }
        }
    }

    return sections.values
        .filter { it.items.size >= 2 }
        .sortedBy { it.title.lowercase() }
        .take(4)
        .map { HomeGenreSection(it.key, it.title, it.items.toList()) }
}

@Composable
private fun HomeHero(
    anime: HomeAnimeRenderData,
    onWatch: (Long, Long) -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ReiAnixTokens.Dimensions.homeHeroHeight)
            .clip(ReiAnixTokens.Shapes.hero),
    ) {
        ReiAnixBackdrop(
            localPath = anime.artworkPath,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            identity = anime.stableKey,
            maxDimensionPx = 768,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.38f to Color.Transparent,
                        0.72f to MaterialTheme.colorScheme.background.copy(alpha = 0.18f),
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.26f),
                        0.55f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )

        IconButton(
            onClick = { onToggleFavorite(anime.id) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(ReiAnixTokens.Spacing.sm)
                .size(ReiAnixTokens.Dimensions.touchTarget)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
                    shape = ReiAnixTokens.Shapes.chip,
                )
                .semantics {
                    contentDescription = if (anime.favorite) {
                        "Remover " + anime.title + " da Minha Lista"
                    } else {
                        "Adicionar " + anime.title + " à Minha Lista"
                    }
                    role = Role.Button
                },
        ) {
            Icon(
                imageVector = if (anime.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (anime.favorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = ReiAnixTokens.Spacing.xxl,
                    end = ReiAnixTokens.Spacing.xxl,
                    bottom = ReiAnixTokens.Spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            Text(
                text = anime.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val genres = anime.genres
                .map { it.name.trim() }
                .filter(String::isNotEmpty)
                .take(2)
            if (genres.isNotEmpty()) {
                Text(
                    text = genres.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val meta = listOfNotNull(
                anime.year?.toString(),
                anime.availableContentCount.takeIf { it > 0 }?.let {
                    if (it == 1) "1 episódio" else "$it episódios"
                },
            )
            if (meta.isNotEmpty()) {
                ReiAnixMetadata(text = meta.joinToString(" • "))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                anime.playbackEpisodeId?.let { episodeId ->
                    ReiAnixPrimaryButton(
                        text = "Assistir",
                        onClick = { onWatch(episodeId, anime.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                ReiAnixSecondaryButton(
                    text = "Detalhes",
                    onClick = { onOpenDetails(anime.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HomeCategoryRow(
    animes: List<HomeAnimeRenderData>,
) {
    val categories = buildHomeCategoryNames(animes)

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Home categorias"
            },
        contentPadding = PaddingValues(
            horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        item(key = "home-category-all") {
            HomeCategoryChip(label = "Todos", selected = true)
        }
        categories.forEach { category ->
            item(key = "home-category:" + category) {
                HomeCategoryChip(label = category, selected = false)
            }
        }
    }
}

@Composable
private fun HomeCategoryChip(
    label: String,
    selected: Boolean,
) {
    Surface(
        shape = ReiAnixTokens.Shapes.chip,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = ReiAnixTokens.Spacing.lg,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
        )
    }
}

@Composable
private fun HomeContinueSection(
    items: List<ReiAnixContinueWatchingUiModel>,
    onWatch: (Long, Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        HomeSectionHeader(title = "CONTINUAR ASSISTINDO")
        LazyRow(
            modifier = Modifier.semantics {
                contentDescription = "Home Continuar Assistindo"
            },
            contentPadding = PaddingValues(
                end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            items(
                items = items,
                key = { it.stableKey },
                contentType = { "home-continue-episode" },
            ) { item ->
                HomeContinueCard(
                    item = item,
                    onWatch = onWatch,
                )
            }
        }
    }
}

@Composable
private fun HomeContinueCard(
    item: ReiAnixContinueWatchingUiModel,
    onWatch: (Long, Long) -> Unit,
) {
    val progress = progressFraction(item.progressSeconds, item.durationSeconds)

    Card(
        modifier = Modifier
            .width(ReiAnixTokens.Dimensions.homeContinueCardWidth)
            .clickable(
                onClick = { onWatch(item.episodeId, item.animeId) },
            )
            .semantics {
                role = Role.Button
                contentDescription =
                    item.animeTitle + " — " +
                        episodeLabel(item.seasonNumber, item.number) + " — " +
                        item.displayTitle
            },
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ReiAnixTokens.Elevation.card),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ReiAnixTokens.Dimensions.homeLandscapeArtworkAspectRatio)
                    .clip(ReiAnixTokens.Shapes.artwork),
            ) {
                ReiAnixEpisodeThumbnail(
                    localPath = item.artwork?.localPath,
                    contentDescription = item.animeTitle,
                    modifier = Modifier.fillMaxSize(),
                    identity = item.stableKey,
                )
                ReiAnixProgressIndicator(
                    progress = progress ?: 0f,
                    visible = progress != null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = ReiAnixTokens.Spacing.sm),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ReiAnixTokens.Spacing.sm,
                        vertical = ReiAnixTokens.Spacing.sm,
                    ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = item.animeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episodeLabel(item.seasonNumber, item.number),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeAnimeSection(
    items: List<HomeAnimeRenderData>,
    onOpenDetails: (Long) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    HomeMediaSection(
        title = "Minha Lista",
        items = items,
        onOpenDetails = onOpenDetails,
        contentDescription = "Home Minha Lista",
        onSeeAll = onSeeAll,
    )
}

@Composable
private fun HomeMediaSection(
    title: String,
    items: List<HomeAnimeRenderData>,
    onOpenDetails: (Long) -> Unit,
    contentDescription: String,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        HomeSectionHeader(
            title = title,
            onSeeAll = onSeeAll,
        )

        LazyRow(
            modifier = Modifier.semantics {
                this.contentDescription = contentDescription
            },
            contentPadding = PaddingValues(
                end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            items(
                items = items,
                key = { it.stableKey },
                contentType = { "home-media-anime" },
            ) { anime ->
                HomeMediaCard(
                    anime = anime,
                    onClick = { onOpenDetails(anime.id) },
                )
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReiAnixSectionTitle(
            title = title,
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) {
            androidx.compose.material3.TextButton(
                onClick = onSeeAll,
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = "Ver tudo: " + title
                },
            ) {
                Text(
                    text = "Ver tudo",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun HomeMediaCard(
    anime: HomeAnimeRenderData,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(ReiAnixTokens.Dimensions.homeCardWidth)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Abrir " + anime.title
            },
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ReiAnixTokens.Elevation.card),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ReiAnixTokens.Dimensions.homeLandscapeArtworkAspectRatio)
                    .clip(ReiAnixTokens.Shapes.artwork),
            ) {
                ReiAnixLocalArtwork(
                    localPath = anime.artworkPath,
                    contentDescription = anime.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = "Sem arte",
                    maxDimensionPx = 512,
                    shape = ReiAnixTokens.Shapes.artwork,
                    identity = anime.stableKey,
                )
                if (anime.favorite) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(ReiAnixTokens.Spacing.xs),
                        shape = ReiAnixTokens.Shapes.chip,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Na Minha Lista",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(ReiAnixTokens.Spacing.xs),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ReiAnixTokens.Spacing.sm,
                        vertical = ReiAnixTokens.Spacing.sm,
                    ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = listOfNotNull(
                    anime.year?.toString(),
                    anime.availableContentCount.takeIf { it > 0 }?.let {
                        if (it == 1) "1 episódio" else "$it episódios"
                    },
                )
                if (metadata.isNotEmpty()) {
                    ReiAnixMetadata(text = metadata.joinToString(" • "))
                }
            }
        }
    }
}

@Composable
private fun HomeLoading() {
    ReiAnixLoadingState(
        title = "Carregando biblioteca",
        message = "Lendo o catálogo local…",
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun HomeError(
    message: String,
    onRefresh: () -> Unit,
) {
    ReiAnixRecoverableErrorState(
        title = "Não foi possível carregar a biblioteca",
        message = message,
        onRetry = onRefresh,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun HomeMessage(
    title: String,
    message: String,
    onRefresh: () -> Unit,
) {
    ReiAnixEmptyState(
        title = title,
        message = message,
        actionLabel = "Atualizar",
        onAction = onRefresh,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun progressFraction(
    progress: Double?,
    duration: Double?,
): Float? {
    val value = progress ?: return null
    val total = duration ?: return null
    if (!value.isFinite() || !total.isFinite() || total <= 0.0) return null
    return (value / total).toFloat().coerceIn(0f, 1f)
}

private fun episodeLabel(
    seasonNumber: Int?,
    number: Double?,
): String {
    val season = seasonNumber?.let { "T" + it }
    val episode = number?.let { formatEpisode(it) }?.let { "EP " + it }
    return listOfNotNull(season, episode)
        .joinToString(" • ")
        .ifBlank { "Episódio" }
}

private fun formatEpisode(value: Double): String =
    if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        value.toString()
    }

private fun availableContentCount(
    anime: ReiAnixAnimeUiModel,
): Int =
    anime.seasons.sumOf { season ->
        season.episodes.count {
            it.media.availability != ReiAnixMediaAvailability.MISSING
        }
    } +
        anime.specials.count {
            it.media.availability != ReiAnixMediaAvailability.MISSING
        } +
        anime.mediaFiles.count {
            it.media.availability != ReiAnixMediaAvailability.MISSING
        }
