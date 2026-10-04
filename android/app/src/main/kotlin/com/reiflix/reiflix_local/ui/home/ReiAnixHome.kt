package com.reiflix.reiflix_local.ui.home

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixSectionTitle
import com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtwork
import com.reiflix.reiflix_local.ui.library.rememberReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.navigation.navigateToPlayer
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixHomeRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel = rememberReiAnixLibraryViewModel(),
) {
    // Home deliberately observes two independent projections. Playback progress
    // can therefore invalidate only the Continue Watching subtree; catalog,
    // hero, and favorites do not observe episode-level progress changes.
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
    onWatch: (episodeId: Long, animeId: Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReiAnixTokens.Colors.background),
    ) {
        HomeHeader(
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
            )
        }
    }
}

@Composable
private fun HomeObservedContent(
    state: ReiAnixHomeLibraryUiState,
    showContinueWatching: Boolean,
    viewModel: ReiAnixLibraryViewModel,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
) {
    val renderAnimes = state.animes.map(ReiAnixHomeAnimeUiModel::toHomeRenderData)
    val featured = renderAnimes.firstOrNull()
    val favorites = renderAnimes.filter(HomeAnimeRenderData::favorite)

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            top = ReiAnixTokens.Spacing.sm,
            bottom = ReiAnixTokens.Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Dimensions.sectionGap),
    ) {
        featured?.let { anime ->
            item(key = "home-hero") {
                HomeHero(
                    anime = anime,
                    onWatch = onWatch,
                    onOpenDetails = onOpenDetails,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }

        // The parent observes only whether the section exists. The child observes
        // the actual episode list, so normal progress updates stay localized.
        if (showContinueWatching) {
            item(key = "home-section-continue") {
                HomeContinueWatchingObserved(
                    viewModel = viewModel,
                    onWatch = onWatch,
                )
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "home-section-my-list") {
                HomeAnimeSection(
                    items = favorites,
                    onOpenDetails = onOpenDetails,
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReiAnixTokens.Colors.background),
    ) {
        HomeHeader(
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
            )
        }
    }
}

@Composable
private fun HomeHeader(
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
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "ReiAnix",
            style = MaterialTheme.typography.headlineSmall,
            color = ReiAnixTokens.Colors.text,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onSearch,
                modifier = Modifier.semantics {
                    contentDescription = "Pesquisar na biblioteca"
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = ReiAnixTokens.Colors.text,
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.semantics {
                    contentDescription = "Atualizar biblioteca local"
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
}

@Composable
private fun HomeContent(
    state: ReiAnixLibraryUiState,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
) {
    val renderAnimes = state.animes.map(ReiAnixAnimeUiModel::toHomeRenderData)
    val featured = renderAnimes.firstOrNull()
    val favorites = renderAnimes.filter(HomeAnimeRenderData::favorite)
    val continueWatching = state.continueWatching

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
            top = ReiAnixTokens.Spacing.sm,
            bottom = ReiAnixTokens.Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Dimensions.sectionGap),
    ) {
        featured?.let { anime ->
            item(key = "home-hero") {
                HomeHero(
                    anime = anime,
                    onWatch = onWatch,
                    onOpenDetails = onOpenDetails,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(key = "home-section-continue") {
                HomeContinueSection(
                    items = continueWatching,
                    onWatch = onWatch,
                )
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "home-section-my-list") {
                HomeAnimeSection(
                    items = favorites,
                    onOpenDetails = onOpenDetails,
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
    val artworkPath: String?,
    val playbackEpisodeId: Long?,
    val availableContentCount: Int,
) {
    val stableKey: String
        get() = "anime:" + id
}

private fun ReiAnixAnimeUiModel.toHomeRenderData(): HomeAnimeRenderData =
    HomeAnimeRenderData(
        id = id,
        title = title,
        year = year,
        genres = genres,
        favorite = favorite,
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
        artworkPath = artwork?.localPath,
        playbackEpisodeId = playbackTargetEpisodeId,
        availableContentCount = availableContentCount,
    )

@Composable
private fun HomeHero(
    anime: HomeAnimeRenderData,
    onWatch: (Long, Long) -> Unit,
    onOpenDetails: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
) {
    val artworkPath = anime.artworkPath
    val playbackEpisodeId = anime.playbackEpisodeId

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(ReiAnixTokens.Shapes.large),
    ) {
        ReiAnixLocalArtwork(
            localPath = artworkPath,
            contentDescription = anime.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = "Sem capa",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.Color.Transparent,
                        0.45f to ReiAnixTokens.Colors.overlay.copy(alpha = 0.14f),
                        1f to ReiAnixTokens.Colors.background.copy(alpha = 0.98f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            Text(
                text = anime.title,
                style = MaterialTheme.typography.headlineSmall,
                color = ReiAnixTokens.Colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val heroMeta = listOfNotNull(
                anime.year?.toString(),
                anime.availableContentCount.takeIf { it > 0 }?.let { "$it episódios" },
                anime.genres.firstOrNull()?.name,
            )
            if (heroMeta.isNotEmpty()) {
                Text(
                    text = heroMeta.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReiAnixTokens.Colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                if (playbackEpisodeId != null) {
                    ReiAnixPrimaryButton(
                        text = "Assistir",
                        onClick = { onWatch(playbackEpisodeId, anime.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                ReiAnixSecondaryButton(
                    text = "Detalhes",
                    onClick = { onOpenDetails(anime.id) },
                    modifier = Modifier.weight(1f),
                )
            }
            ReiAnixSecondaryButton(
                text = if (anime.favorite) "Na Minha Lista" else "Minha Lista",
                onClick = { onToggleFavorite(anime.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HomeContinueSection(
    items: List<ReiAnixContinueWatchingUiModel>,
    onWatch: (Long, Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
        ReiAnixSectionTitle(title = "CONTINUAR ASSISTINDO")
        LazyRow(
            modifier = Modifier.semantics {
                contentDescription = "Home Continuar Assistindo"
            },
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            items(
                items = items,
                key = { it.stableKey },
                contentType = { "home-continue-episode" },
            ) { item ->
                HomeContinueCard(item = item, onWatch = onWatch)
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
            .width(250.dp)
            .clickable { onWatch(item.episodeId, item.animeId) },
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
    ) {
        Row(
            modifier = Modifier.padding(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReiAnixLocalArtwork(
                localPath = item.artwork?.localPath,
                contentDescription = item.animeTitle,
                modifier = Modifier.size(width = 76.dp, height = 108.dp),
                placeholder = "Sem arte",
            )
            Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = item.animeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = ReiAnixTokens.Colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episodeLabel(item.seasonNumber, item.number),
                    style = MaterialTheme.typography.bodySmall,
                    color = ReiAnixTokens.Colors.textMuted,
                )
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ReiAnixTokens.Colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                progress?.let { value ->
                    ReiAnixProgressIndicator(progress = value)
                }
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = ReiAnixTokens.Colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeAnimeSection(
    items: List<HomeAnimeRenderData>,
    onOpenDetails: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
        ReiAnixSectionTitle(title = "MINHA LISTA")
        LazyRow(
            modifier = Modifier.semantics {
                contentDescription = "Home Minha Lista"
            },
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            items(
                items = items,
                key = { it.stableKey },
                contentType = { "home-my-list-anime" },
            ) { anime ->
                HomeAnimeCard(
                    title = anime.title,
                    year = anime.year,
                    artworkPath = anime.artworkPath,
                    episodeCount = anime.availableContentCount,
                    onClick = { onOpenDetails(anime.id) },
                )
            }
        }
    }
}

@Composable
private fun HomeAnimeCard(
    title: String,
    year: Int?,
    artworkPath: String?,
    episodeCount: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(154.dp)
            .clickable(onClick = onClick),
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
    ) {
        Column {
            ReiAnixLocalArtwork(
                localPath = artworkPath,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                placeholder = "Sem capa",
            )
            Column(
                modifier = Modifier.padding(ReiAnixTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ReiAnixTokens.Colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = listOfNotNull(
                    year?.toString(),
                    episodeCount.takeIf { it > 0 }?.let { "$it episódios" },
                )
                if (metadata.isNotEmpty()) {
                    Text(
                        text = metadata.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ReiAnixTokens.Colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

private fun progressFraction(progress: Double?, duration: Double?): Float? {
    val value = progress ?: return null
    val total = duration ?: return null
    if (total <= 0.0) return null
    return (value / total).toFloat().coerceIn(0f, 1f)
}

private fun episodeLabel(seasonNumber: Int?, number: Double?): String {
    val season = seasonNumber?.let { "T$it" }
    val episode = number?.let { formatEpisode(it) }?.let { "EP $it" }
    return listOfNotNull(season, episode).joinToString(" • ").ifBlank { "Episódio" }
}

private fun formatEpisode(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun availableContentCount(anime: ReiAnixAnimeUiModel): Int =
    anime.seasons.sumOf { season ->
        season.episodes.count { it.media.availability != ReiAnixMediaAvailability.MISSING }
    } +
        anime.specials.count { it.media.availability != ReiAnixMediaAvailability.MISSING } +
        anime.mediaFiles.count { it.media.availability != ReiAnixMediaAvailability.MISSING }
