package com.reiflix.reiflix_local.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
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
import com.reiflix.reiflix_local.ui.library.rememberReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.navigation.navigateToMyList
import com.reiflix.reiflix_local.ui.navigation.navigateToPlayer
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReiAnixHomeRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel = rememberReiAnixLibraryViewModel(),
) {
    val state by viewModel.homeState.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()

    ReiAnixHomeObservedScreen(
        state = state,
        continueWatching = continueWatching,
        onSearch = { navController.navigateToTopLevel(ReiAnixRoutes.SEARCH) },
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
        onOpenMyList = { navController.navigateToMyList() },
        onOpenLibrary = { navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY) },
        onRefresh = viewModel::refresh,
        onSelectSource = viewModel::selectSafTree,
    )
}

@Composable
private fun ReiAnixHomeObservedScreen(
    state: ReiAnixHomeLibraryUiState,
    continueWatching: List<ReiAnixContinueWatchingUiModel>,
    onSearch: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSource: () -> Unit,
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
                actionLabel = "Selecionar pasta",
                onAction = onSelectSource,
                modifier = Modifier.fillMaxSize(),
            )
            ReiAnixLibraryLoadStatus.EMPTY -> ReiAnixEmptyLibraryState(
                message = "Nenhum conteúdo local disponível.",
                actionLabel = "Selecionar pasta",
                onAction = onSelectSource,
                modifier = Modifier.fillMaxSize(),
            )
            ReiAnixLibraryLoadStatus.READY -> HomeObservedContent(
                state = state,
                continueWatching = continueWatching,
                onOpenDetails = onOpenDetails,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onOpenMyList = onOpenMyList,
                onOpenLibrary = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun ColumnScope.HomeObservedContent(
    state: ReiAnixHomeLibraryUiState,
    continueWatching: List<ReiAnixContinueWatchingUiModel>,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val renderAnimes = remember(state.animes) {
        state.animes.map(ReiAnixHomeAnimeUiModel::toHomeRenderData)
    }

    HomeReadyContent(
        animes = renderAnimes,
        continueWatching = continueWatching,
        onOpenDetails = onOpenDetails,
        onWatch = onWatch,
        onHeroSecondaryAction = { featured -> onToggleFavorite(featured.id) },
        heroSecondaryLabel = { anime ->
            if (anime.favorite) "✓ Na Minha Lista" else "+ Minha Lista"
        },
        onOpenMyList = onOpenMyList,
        onOpenLibrary = onOpenLibrary,
    )
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
    onOpenLibrary: () -> Unit = {},
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
                actionLabel = "Atualizar",
                onAction = onRefresh,
            )
            ReiAnixLibraryLoadStatus.EMPTY -> HomeMessage(
                title = "Biblioteca vazia",
                message = "Nenhum conteúdo local disponível.",
                actionLabel = "Atualizar",
                onAction = onRefresh,
            )
            ReiAnixLibraryLoadStatus.READY -> HomeContent(
                state = state,
                onOpenDetails = onOpenDetails,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onOpenMyList = onOpenMyList,
                onOpenLibrary = onOpenLibrary,
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
            color = MaterialTheme.colorScheme.primary,
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

@Composable
private fun ColumnScope.HomeContent(
    state: ReiAnixLibraryUiState,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val renderAnimes = remember(state.animes) {
        state.animes.map(ReiAnixAnimeUiModel::toHomeRenderData)
    }

    HomeReadyContent(
        animes = renderAnimes,
        continueWatching = state.continueWatching,
        onOpenDetails = onOpenDetails,
        onWatch = onWatch,
        onHeroSecondaryAction = { featured -> onOpenDetails(featured.id) },
        heroSecondaryLabel = { "Detalhes" },
        onOpenMyList = onOpenMyList,
        onOpenLibrary = onOpenLibrary,
    )
}

private data class HomeAnimeRenderData(
    val id: Long,
    val title: String,
    val year: Int?,
    val genres: List<com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel>,
    val favorite: Boolean,
    val mediaKind: ReiAnixMediaKind,
    val artworkPath: String?,
    val backdropLocalPath: String?,
    val playbackEpisodeId: Long?,
    val playbackActionLabel: String,
    val isWatching: Boolean,
    val availableContentCount: Int,
    val score: Double?,
    val addedAt: Double?,
    val lastPlayedAt: Double?,
    val pinned: Boolean,
    val description: String?,
    val status: String?,
    val format: String?,
    val studio: String?,
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
        mediaKind = mediaKind,
        artworkPath = artwork?.localPath,
        backdropLocalPath = artwork?.backdropLocalPath,
        playbackEpisodeId = playbackTargetEpisodeId,
        playbackActionLabel = playbackActionLabel,
        isWatching = isWatching,
        availableContentCount = availableContentCount,
        score = score,
        addedAt = addedAt,
        lastPlayedAt = lastPlayedAt,
        pinned = pinned,
        description = description,
        status = status,
        format = format,
        studio = studio,
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
        backdropLocalPath = artwork?.backdropLocalPath,
        playbackEpisodeId = playbackTargetEpisodeId,
        playbackActionLabel = playbackActionLabel,
        isWatching = isWatching,
        availableContentCount = availableContentCount,
        score = score,
        addedAt = addedAt,
        lastPlayedAt = lastPlayedAt,
        pinned = pinned,
        description = description,
        status = status,
        format = format,
        studio = studio,
    )

@Composable
private fun ColumnScope.HomeReadyContent(
    animes: List<HomeAnimeRenderData>,
    continueWatching: List<ReiAnixContinueWatchingUiModel>,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onHeroSecondaryAction: (HomeAnimeRenderData) -> Unit,
    heroSecondaryLabel: (HomeAnimeRenderData) -> String,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val trending = remember(animes) { buildTrendingItems(animes) }
    val favorites = remember(animes) { animes.filter { it.favorite } }
    val genreSections = remember(animes) { buildHomeGenreSections(animes) }
    val movies = remember(animes) { animes.filter { it.mediaKind == ReiAnixMediaKind.MOVIE } }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(
            bottom = ReiAnixTokens.Spacing.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.section),
    ) {
        if (animes.isNotEmpty()) {
            item(key = "home-hero") {
                HomeHero(
                    anime = selectFeaturedAnime(animes),
                    onWatch = onWatch,
                    onSecondaryAction = onHeroSecondaryAction,
                    secondaryLabel = heroSecondaryLabel,
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(key = "home-section-continue") {
                HomeContinueSection(
                    items = continueWatching,
                    onWatch = onWatch,
                    onSeeAll = onOpenLibrary,
                )
            }
        }

        if (trending.isNotEmpty()) {
            item(key = "home-section-trending") {
                HomeMediaSection(
                    title = "EM ALTA",
                    items = trending,
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Em Alta",
                    onSeeAll = onOpenLibrary,
                )
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "home-section-my-list") {
                HomeMediaSection(
                    title = "MINHA LISTA",
                    items = favorites,
                    onOpenDetails = onOpenDetails,
                    contentDescription = HOME_MY_LIST_CONTENT_DESCRIPTION,
                    onSeeAll = onOpenMyList,
                )
            }
        }

        genreSections.forEach { section ->
            item(key = "home-section-genre:" + section.key) {
                HomeMediaSection(
                    title = section.title,
                    items = section.items,
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home " + section.title,
                )
            }
        }

        if (movies.isNotEmpty()) {
            item(key = "home-section-movies") {
                HomeMediaSection(
                    title = "FILMES",
                    items = movies,
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Filmes",
                )
            }
        }
    }
}

@Composable
private fun HomeHero(
    anime: HomeAnimeRenderData,
    onWatch: (Long, Long) -> Unit,
    onSecondaryAction: (HomeAnimeRenderData) -> Unit,
    secondaryLabel: (HomeAnimeRenderData) -> String,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val heroHeight = (maxWidth / 1.48f).coerceIn(300.dp, 400.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .clip(ReiAnixTokens.Shapes.hero),
        ) {
            ReiAnixBackdrop(
                localPath = anime.backdropLocalPath,
                fallbackLocalPath = anime.artworkPath,
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
                            0.34f to Color.Transparent,
                            0.62f to MaterialTheme.colorScheme.background.copy(alpha = 0.16f),
                            0.82f to MaterialTheme.colorScheme.background.copy(alpha = 0.76f),
                            1f to MaterialTheme.colorScheme.background.copy(alpha = 0.99f),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0.68f),
                            0.44f to MaterialTheme.colorScheme.background.copy(alpha = 0.20f),
                            0.70f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    ),
            )

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
                Surface(
                    shape = ReiAnixTokens.Shapes.chip,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = "Em destaque",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(
                            horizontal = ReiAnixTokens.Spacing.md,
                            vertical = ReiAnixTokens.Spacing.xs,
                        ),
                    )
                }

                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val genres = anime.genres
                    .map { it.name.trim() }
                    .filter(String::isNotEmpty)
                    .take(3)
                if (genres.isNotEmpty()) {
                    Text(
                        text = genres.joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                anime.description?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                val metadata = listOfNotNull(
                    anime.year?.toString(),
                    anime.format?.trim()?.takeIf(String::isNotEmpty),
                    anime.studio?.trim()?.takeIf(String::isNotEmpty),
                    anime.score?.let(::formatScore),
                    anime.availableContentCount.takeIf { it > 0 }?.let {
                        if (it == 1) "1 episódio" else it.toString() + " episódios"
                    },
                )
                if (metadata.isNotEmpty()) {
                    ReiAnixMetadata(text = metadata.joinToString(" • "))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    anime.playbackEpisodeId?.let { episodeId ->
                        ReiAnixPrimaryButton(
                            text = anime.playbackActionLabel,
                            enabled = anime.playbackActionLabel != "Indisponível",
                            onClick = { onWatch(episodeId, anime.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    ReiAnixSecondaryButton(
                        text = secondaryLabel(anime),
                        onClick = { onSecondaryAction(anime) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeContinueSection(
    items: List<ReiAnixContinueWatchingUiModel>,
    onWatch: (Long, Long) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        HomeSectionHeader(
            title = "CONTINUAR ASSISTINDO",
            onSeeAll = onSeeAll,
        )

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
    val progressText = progress?.let(::formatProgressPercent)
    val episodeText = episodeLabel(item.seasonNumber, item.number)
    val detailText = listOfNotNull(
        episodeText.takeIf { it.isNotBlank() },
        item.displayTitle.takeIf { it.isNotBlank() },
        progressText,
    ).joinToString(" • ")

    Card(
        modifier = Modifier
            .width(ReiAnixTokens.Dimensions.homeContinueCardWidth)
            .clickable(onClick = { onWatch(item.episodeId, item.animeId) })
            .semantics {
                role = Role.Button
                contentDescription =
                    listOfNotNull(
                        item.animeTitle,
                        episodeText.takeIf { it.isNotBlank() },
                        item.displayTitle.takeIf { it.isNotBlank() },
                        progressText,
                    ).joinToString(" — ")
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
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val HOME_MY_LIST_CONTENT_DESCRIPTION = "Home Minha Lista"

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
        HomeSectionHeader(title = title, onSeeAll = onSeeAll)

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
                HomeMediaCard(anime = anime, onClick = { onOpenDetails(anime.id) })
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
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = "Ver tudo: " + title
                },
            ) {
                Text(
                    text = "Ver tudo  ›",
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
                ReiAnixBackdrop(
                    localPath = anime.backdropLocalPath,
                    fallbackLocalPath = anime.artworkPath,
                    contentDescription = anime.title,
                    modifier = Modifier.fillMaxSize(),
                    identity = anime.stableKey,
                    maxDimensionPx = 512,
                )

                if (anime.favorite || anime.pinned) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(ReiAnixTokens.Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                    ) {
                        if (anime.pinned) {
                            Surface(
                                shape = ReiAnixTokens.Shapes.chip,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            ) {
                                Text(
                                    text = "FIXADO",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(
                                        horizontal = ReiAnixTokens.Spacing.xs,
                                        vertical = 2.dp,
                                    ),
                                )
                            }
                        }

                        if (anime.favorite) {
                            Surface(
                                shape = ReiAnixTokens.Shapes.chip,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
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
                        if (it == 1) "1 episódio" else it.toString() + " episódios"
                    },
                )
                if (metadata.isNotEmpty()) {
                    ReiAnixMetadata(text = metadata.joinToString(" • "))
                }
            }
        }
    }
}

private fun selectFeaturedAnime(items: List<HomeAnimeRenderData>): HomeAnimeRenderData =
    items.firstOrNull {
        it.playbackEpisodeId != null && it.playbackActionLabel != "Indisponível"
    }
        ?: items.firstOrNull { it.isWatching }
        ?: items.firstOrNull { it.favorite }
        ?: items.firstOrNull { it.pinned }
        ?: items.firstOrNull()
        ?: error("Home hero requested without library data")

private fun buildTrendingItems(
    items: List<HomeAnimeRenderData>,
): List<HomeAnimeRenderData> =
    items.sortedWith(
        compareByDescending<HomeAnimeRenderData> { it.isWatching }
            .thenByDescending { it.lastPlayedAt ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { it.favorite }
            .thenByDescending { it.pinned }
            .thenByDescending { it.addedAt ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.title.trim().lowercase() },
    ).take(12)

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

private data class HomeGenreSection(
    val key: String,
    val title: String,
    val items: List<HomeAnimeRenderData>,
)

private fun formatScore(score: Double?): String? {
    val value = score ?: return null
    if (!value.isFinite()) return null
    val normalized = if (value > 10.0) value / 10.0 else value
    return String.format(Locale.getDefault(), "%.1f/10", normalized.coerceIn(0.0, 10.0))
}

private fun HomeLoading() {
    ReiAnixLoadingState(
        title = "Carregando biblioteca",
        message = "Lendo o catálogo local…",
        modifier = Modifier.fillMaxSize(),
    )
}

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

private fun HomeMessage(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    ReiAnixEmptyState(
        title = title,
        message = message,
        actionLabel = actionLabel,
        onAction = onAction,
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
    return (value.coerceAtLeast(0.0) / total).coerceIn(0.0, 1.0).toFloat()
}

private fun formatProgressPercent(progress: Float): String {
    val percentage = (progress.coerceIn(0f, 1f) * 100f)
    val roundedTenths = (percentage * 10f).roundToInt() / 10f
    return if (roundedTenths % 1f == 0f) {
        roundedTenths.toInt().toString() + "%"
    } else {
        String.format(Locale.getDefault(), "%.1f%%", roundedTenths)
    }
}

private fun episodeLabel(
    seasonNumber: Int?,
    number: Double?,
): String {
    val season = seasonNumber?.let { "T" + it }
    val episode = number?.let { formatEpisode(it) }?.let { "EP " + it }
    return listOfNotNull(season, episode).joinToString(" • ")
}

private fun formatEpisode(value: Double): String =
    if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        value.toString().trimEnd('0').trimEnd('.')
    }
