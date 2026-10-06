package com.reiflix.reiflix_local.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixChip
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixEpisodeCard
import com.reiflix.reiflix_local.ui.ReiAnixIconActionButton
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixProgressIndicator
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.artwork.ReiAnixBackdrop
import com.reiflix.reiflix_local.ui.artwork.ReiAnixEpisodeThumbnail
import com.reiflix.reiflix_local.ui.artwork.ReiAnixPoster
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiStateProjection
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import com.reiflix.reiflix_local.ui.motion.ReiAnixMotionPolicy
import com.reiflix.reiflix_local.ui.navigation.navigateToPlayer
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.widthIn
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ReiAnixDetailsRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    animeId: String,
    origin: String,
) {
    val canonicalId = animeId.trim().toLongOrNull()

    if (canonicalId == null) {
        ReiAnixDetailsScreen(
            state = ReiAnixDetailsUiStateProjection.invalidAnimeId(),
            onBack = { navController.popBackStack() },
            onRetry = {},
            onWatch = {},
            onToggleFavorite = {},
        )
        return
    }

    val detailsStateFlow = remember(viewModel, canonicalId) {
        viewModel.detailsState(canonicalId)
    }
    val state by detailsStateFlow.collectAsStateWithLifecycle(
        initialValue = ReiAnixDetailsUiState(),
    )

    // Guard only the short navigation hand-off. NavHost/launchSingleTop remains
    // the canonical navigation policy, while this prevents a double tap from
    // issuing two player navigations before the destination is committed.
    var playerLaunchInFlight by remember(canonicalId) {
        mutableStateOf(false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    // The guard covers only the navigation hand-off. A resume releases it only
    // after this Details destination has first left the RESUMED state.
    var detailsWasPaused by remember(canonicalId) {
        mutableStateOf(false)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    detailsWasPaused = true
                }

                Lifecycle.Event.ON_RESUME -> {
                    if (detailsWasPaused) {
                        playerLaunchInFlight = false
                        detailsWasPaused = false
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ReiAnixDetailsScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onRetry = viewModel::refresh,
        onWatch = { episodeId ->
            if (!playerLaunchInFlight) {
                playerLaunchInFlight = true
                navController.navigateToPlayer(
                    episodeId = episodeId.toString(),
                    animeId = canonicalId.toString(),
                    origin = origin,
                )
            }
        },
        onToggleFavorite = viewModel::toggleFavorite,
        onSetEpisodeWatched = viewModel::setEpisodeWatched,
    )
}

@Composable
fun ReiAnixDetailsScreen(
    state: ReiAnixDetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onWatch: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit = { _, _ -> },
) {
    ReiAnixResponsiveRoot {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state.status) {
            ReiAnixDetailsLoadStatus.LOADING -> ReiAnixDetailsLoadingContent()
            ReiAnixDetailsLoadStatus.READY -> {
                state.anime?.let { anime ->
                    ReiAnixDetailsReady(
                        anime = anime,
                        onBack = onBack,
                        onRefresh = onRetry,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        onSetEpisodeWatched = onSetEpisodeWatched,
                    )
                } ?: ReiAnixRecoverableErrorState(
                    title = "Detalhes indisponíveis",
                    message = "Os dados do conteúdo não estão disponíveis.",
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            ReiAnixDetailsLoadStatus.EMPTY -> ReiAnixEmptyLibraryState(
                message = "Nenhum anime local está disponível.",
                actionLabel = "Atualizar",
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixDetailsLoadStatus.SOURCE_UNAVAILABLE -> ReiAnixSourceUnavailableState(
                title = "Biblioteca local indisponível",
                message = state.error ?: "A fonte local não está disponível agora.",
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixDetailsLoadStatus.NOT_FOUND -> ReiAnixEmptyState(
                title = "Conteúdo não encontrado",
                message = state.error ?: "O conteúdo não está presente na biblioteca local.",
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixDetailsLoadStatus.ERROR -> ReiAnixRecoverableErrorState(
                title = "Erro nos detalhes",
                message = state.error ?: "Não foi possível carregar os detalhes.",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    }
}

@Composable
private fun ReiAnixDetailsLoadingContent() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("details-loading"),
        contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xxxl),
    ) {
        item(key = "details-loading-hero", contentType = "details-loading-hero") {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ReiAnixTokens.Dimensions.detailsHeroMaxHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(
                    modifier = Modifier.padding(
                        horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                        vertical = ReiAnixTokens.Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    DetailsSkeletonLine(width = 0.72f, height = ReiAnixTokens.Spacing.xxl)
                    DetailsSkeletonLine(width = 0.46f, height = ReiAnixTokens.Spacing.lg)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                    ) {
                        DetailsSkeletonButton(modifier = Modifier.weight(1f))
                        DetailsSkeletonButton(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.md))
                    DetailsSkeletonLine(width = 0.35f, height = ReiAnixTokens.Spacing.xxl)
                    DetailsSkeletonLine(width = 0.92f, height = ReiAnixTokens.Spacing.md)
                    DetailsSkeletonLine(width = 0.86f, height = ReiAnixTokens.Spacing.md)
                }
            }
        }
        item(key = "details-loading-seasons", contentType = "details-loading-seasons") {
            Column(
                modifier = Modifier.padding(
                    horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                    vertical = ReiAnixTokens.Spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                DetailsSkeletonLine(width = 0.34f, height = ReiAnixTokens.Spacing.xxl)
                DetailsSkeletonButton(modifier = Modifier.fillMaxWidth())
                DetailsSkeletonButton(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DetailsSkeletonLine(width: Float, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(width.coerceIn(0.08f, 1f))
            .height(height)
            .clip(ReiAnixTokens.Shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun DetailsSkeletonButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(ReiAnixTokens.Dimensions.buttonMinHeight)
            .clip(ReiAnixTokens.Shapes.button)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun ColumnScope.ReiAnixDetailsReady(
    anime: ReiAnixDetailsAnimeUiModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWatch: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit,
) {
    val firstSeasonKey = anime.seasons.firstOrNull()?.stableKey
    var savedSeasonKey by rememberSaveable(anime.id) {
        mutableStateOf(firstSeasonKey)
    }
    val selectedSeasonKey = savedSeasonKey
        ?.takeIf { key -> anime.seasons.any { it.stableKey == key } }
        ?: firstSeasonKey
    val selectedSeason = anime.seasons.firstOrNull { it.stableKey == selectedSeasonKey }
        ?: anime.seasons.firstOrNull()

    var selectedSection by rememberSaveable(anime.id) {
        mutableStateOf(DetailsSection.ABOUT)
    }
    val listState = rememberSaveable(
        saver = LazyListState.Saver,
    ) {
        LazyListState()
    }
    val coroutineScope = rememberCoroutineScope()
    val hasSeasons = anime.seasons.isNotEmpty()
    val aboutIndex = 2
    val episodeHeadingIndex = if (hasSeasons) 4 else 3

    fun scrollToDetailsSection(index: Int) {
        coroutineScope.launch {
            if (ReiAnixMotionPolicy.systemAnimationsEnabled()) {
                listState.animateScrollToItem(index)
            } else {
                listState.scrollToItem(index)
            }
        }
    }

    fun selectSeason(seasonKey: String) {
        if (anime.seasons.none { it.stableKey == seasonKey }) return
        if (seasonKey == selectedSeasonKey) return
        savedSeasonKey = seasonKey
        scrollToDetailsSection(episodeHeadingIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .widthIn(max = LocalReiAnixResponsiveMetrics.current.contentMaxWidth)
            .fillMaxWidth()
            .testTag("details-episode-list"),
        contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xxxl),
    ) {
        item(
            key = "details-hero:" + anime.stableKey,
            contentType = "details-hero",
        ) {
            DetailsHero(
                anime = anime,
                onBack = onBack,
                onRefresh = onRefresh,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onSetEpisodeWatched = onSetEpisodeWatched,
            )
        }

        item(
            key = "details-tabs:" + anime.stableKey,
            contentType = "details-tabs",
        ) {
            DetailsSectionTabs(
                selectedSection = selectedSection,
                onAbout = {
                    selectedSection = DetailsSection.ABOUT
                    scrollToDetailsSection(aboutIndex)
                },
                onEpisodes = {
                    selectedSection = DetailsSection.EPISODES
                    scrollToDetailsSection(episodeHeadingIndex)
                },
            )
        }

        item(
            key = "details-about:" + anime.stableKey,
            contentType = "details-about",
        ) {
            DetailsAboutSection(anime = anime)
        }

        if (hasSeasons) {
            item(
                key = "details-seasons:" + anime.stableKey,
                contentType = "details-seasons",
            ) {
                DetailsSeasonsSection(
                    anime = anime,
                    selectedSeason = selectedSeason,
                    selectedSeasonKey = selectedSeasonKey,
                    onSeasonSelected = ::selectSeason,
                    onViewEpisodes = {
                        selectedSection = DetailsSection.EPISODES
                        scrollToDetailsSection(episodeHeadingIndex)
                    },
                )
            }
        }

        selectedSeason?.let { season ->
            item(
                key = "details-episode-heading:" + season.stableKey,
                contentType = "details-episode-heading",
            ) {
                DetailsSectionHeader(
                    title = if (anime.mediaKind == ReiAnixMediaKind.MOVIE) "Conteúdo" else "Episódios",
                    subtitle = episodeCountLabel(season.episodes.size),
                )
            }

            if (season.episodes.isEmpty()) {
                item(
                    key = "details-empty-season:" + season.stableKey,
                    contentType = "details-empty-season",
                ) {
                    ReiAnixEmptyState(
                        title = "Nenhum episódio nesta temporada",
                        message = "Esta temporada não possui episódios locais disponíveis.",
                        modifier = Modifier.padding(
                            horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                            vertical = ReiAnixTokens.Spacing.lg,
                        ),
                    )
                }
            }

            items(
                items = season.episodes,
                key = { episode -> episode.stableKey },
                contentType = { "details-episode" },
            ) { episode ->
                DetailsEpisodeItem(
                    episode = episode,
                    onWatch = onWatch,
                    onSetEpisodeWatched = onSetEpisodeWatched,
                )
            }
        }

        if (anime.mediaFiles.isNotEmpty()) {
            item(
                key = "details-media-files-heading:" + anime.stableKey,
                contentType = "details-media-files-heading",
            ) {
                DetailsSectionHeader(
                    title = if (anime.mediaKind == ReiAnixMediaKind.MOVIE) "Filmes" else "Conteúdo",
                    subtitle = episodeCountLabel(anime.mediaFiles.size),
                )
            }
            items(
                items = anime.mediaFiles,
                key = { episode -> "movie:" + episode.id },
                contentType = { "details-movie" },
            ) { episode ->
                DetailsEpisodeItem(
                    episode = episode,
                    onWatch = onWatch,
                    onSetEpisodeWatched = onSetEpisodeWatched,
                )
            }
        }

        if (anime.specials.isNotEmpty()) {
            item(
                key = "details-specials-heading:" + anime.stableKey,
                contentType = "details-specials-heading",
            ) {
                DetailsSectionHeader(
                    title = "Especiais",
                    subtitle = episodeCountLabel(anime.specials.size),
                )
            }
            items(
                items = anime.specials,
                key = { episode -> "special:" + episode.id },
                contentType = { "details-special" },
            ) { episode ->
                DetailsEpisodeItem(
                    episode = episode,
                    onWatch = onWatch,
                    onSetEpisodeWatched = onSetEpisodeWatched,
                )
            }
        }

        if (anime.seasons.isEmpty() && anime.specials.isEmpty() && anime.mediaFiles.isEmpty()) {
            item(
                key = "details-empty-episodes:" + anime.stableKey,
                contentType = "details-empty-episodes",
            ) {
                ReiAnixEmptyState(
                    title = "Nenhum episódio local",
                    message = "Este conteúdo não possui episódios ou arquivos disponíveis.",
                    modifier = Modifier.padding(
                        horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                        vertical = ReiAnixTokens.Spacing.lg,
                    ),
                )
            }
        }
    }
}

private enum class DetailsSection {
    ABOUT,
    EPISODES,
}

@Composable
private fun DetailsSectionTabs(
    selectedSection: DetailsSection,
    onAbout: () -> Unit,
    onEpisodes: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        ReiAnixChip(
            text = "Sobre",
            selected = selectedSection == DetailsSection.ABOUT,
            onClick = onAbout,
            modifier = Modifier.semantics {
                contentDescription = "Abrir seção Sobre"
            },
        )
        ReiAnixChip(
            text = "Episódios",
            selected = selectedSection == DetailsSection.EPISODES,
            onClick = onEpisodes,
            modifier = Modifier.semantics {
                contentDescription = "Abrir seção Episódios"
            },
        )
        ReiAnixChip(
            text = "Personagens",
            enabled = false,
            onClick = {},
            modifier = Modifier.semantics {
                contentDescription = "Personagens indisponíveis"
            },
        )
        ReiAnixChip(
            text = "Relacionados",
            enabled = false,
            onClick = {},
            modifier = Modifier.semantics {
                contentDescription = "Relacionados indisponíveis"
            },
        )
    }
}

@Composable
private fun DetailsHero(
    anime: ReiAnixDetailsAnimeUiModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWatch: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit,
) {
    val menuExpanded = rememberSaveable(anime.id) { mutableStateOf(false) }
    val responsive = LocalReiAnixResponsiveMetrics.current
    val heroHeight = responsive.detailsHeroHeight()
    val posterOverlap = ReiAnixTokens.Dimensions.detailsHeroPosterOverlap
    val backdropPath = anime.artwork?.backdropLocalPath?.takeIf { it.isNotBlank() }
    val posterPath = anime.artwork?.localPath?.takeIf { it.isNotBlank() }
    val target = anime.playbackTargetEpisode
    val metadata = buildList {
        anime.year?.let { add(it.toString()) }
        anime.episodeCount?.let { count ->
            add(if (count == 1) "1 episódio" else "$count eps")
        }
        anime.score?.let { add("Nota " + formatScore(it)) }
    }.joinToString(" • ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReiAnixTokens.Dimensions.topBarMinHeight)
                .padding(horizontal = responsive.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReiAnixIconActionButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Voltar",
                onClick = onBack,
            )

            Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.xs))

            Text(
                text = anime.title,
                style = ReiAnixTokens.TypographyTokens.screenTitle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Box {
                ReiAnixIconActionButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Mais opções",
                    onClick = { menuExpanded.value = true },
                )
                DropdownMenu(
                    expanded = menuExpanded.value,
                    onDismissRequest = { menuExpanded.value = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Atualizar detalhes") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded.value = false
                            onRefresh()
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight),
        ) {
            ReiAnixBackdrop(
                localPath = backdropPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                identity = anime.stableKey + ":backdrop",
                fallbackLocalPath = posterPath,
                maxDimensionPx = 1024,
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.06f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.70f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = responsive.horizontalPadding)
                .height(ReiAnixTokens.Dimensions.detailsHeroPosterHeight),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.offset(y = -posterOverlap),
            ) {
                ReiAnixPoster(
                    localPath = posterPath,
                    contentDescription = null,
                    modifier = Modifier
                        .width(ReiAnixTokens.Dimensions.detailsHeroPosterWidth)
                        .aspectRatio(ReiAnixTokens.Dimensions.posterAspectRatio)
                        .clip(ReiAnixTokens.Shapes.artwork),
                    identity = anime.stableKey + ":poster",
                    maxDimensionPx = 512,
                )
            }

            Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.md))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = ReiAnixTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = anime.title,
                    style = ReiAnixTokens.TypographyTokens.heroTitle,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (anime.genres.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = responsive.horizontalPadding,
                    ),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                anime.genres.take(4).forEach { genre ->
                    ReiAnixChip(
                        text = genre.name,
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        anime.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = responsive.horizontalPadding,
                            end = responsive.horizontalPadding,
                            top = ReiAnixTokens.Spacing.sm,
                        ),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = responsive.horizontalPadding,
                    vertical = ReiAnixTokens.Spacing.md,
                ),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            if (target?.isPlayable == true) {
                ReiAnixPrimaryButton(
                    text = target.playbackActionLabel,
                    onClick = { onWatch(target.id) },
                    modifier = Modifier.weight(1.7f),
                    leadingIcon = Icons.Filled.PlayArrow,
                )
            } else {
                ReiAnixPrimaryButton(
                    text = "Reproduzir indisponível",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1.7f),
                    leadingIcon = Icons.Filled.PlayArrow,
                )
            }

            ReiAnixSecondaryButton(
                text = if (anime.favorite) "Favoritado" else "Favoritar",
                onClick = { onToggleFavorite(anime.id) },
                modifier = Modifier.weight(1f),
                leadingIcon = if (anime.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            )

            val watched = target?.isWatched == true || target?.consumptionState == ReiAnixConsumptionState.COMPLETED
            ReiAnixSecondaryButton(
                text = if (watched) "Desmarcar" else "Marcar visto",
                onClick = {
                    target?.let { onSetEpisodeWatched(it.id, !watched) }
                },
                enabled = target != null,
                modifier = Modifier.weight(1f),
                leadingIcon = if (watched) Icons.Filled.Check else Icons.Filled.Check,
            )
        }
    }
}

@Composable
private fun DetailsHeroIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ReiAnixIconActionButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.surface.copy(
                    alpha = ReiAnixTokens.Colors.surfaceOverlayAlpha,
                ),
                CircleShape,
            ),
    )
}

@Composable
private fun DetailsAboutSection(
    anime: ReiAnixDetailsAnimeUiModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                vertical = ReiAnixTokens.Spacing.lg,
            ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.lg),
    ) {
        anime.description
            ?.takeIf { it.isNotBlank() }
            ?.let { description ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    Text(
                        text = "Sinopse",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        val details = buildList {
            anime.format?.let { add("Formato" to formatLabel(it)) }
            anime.status?.let { add("Status" to statusLabel(it)) }
            anime.studio?.let { add("Estúdio" to it) }
            anime.seasonLabel?.let { add("Temporada" to seasonLabel(it)) }
            anime.durationMinutes?.let { add("Duração" to "$it min/ep") }
        }

        if (details.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                Text(
                    text = "Informações",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                details.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.lg),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(ReiAnixTokens.Dimensions.detailsInfoLabelWidth),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (anime.genres.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                anime.genres.forEach { genre ->
                    ReiAnixBadge(
                        text = genre.name,
                        tone = ReiAnixBadgeTone.Primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailsSeasonsSection(
    anime: ReiAnixDetailsAnimeUiModel,
    selectedSeason: ReiAnixSeasonUiModel?,
    selectedSeasonKey: String?,
    onSeasonSelected: (String) -> Unit,
    onViewEpisodes: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Temporadas",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (selectedSeason != null && anime.seasons.size > 1) {
                Text(
                    text = "Ver todas  ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onViewEpisodes)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Ver todos os episódios"
                        }
                        .padding(ReiAnixTokens.Spacing.sm),
                )
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            contentPadding = PaddingValues(end = LocalReiAnixResponsiveMetrics.current.horizontalPadding),
        ) {
            items(
                items = anime.seasons,
                key = { season -> season.stableKey },
                contentType = { "details-season-card" },
            ) { season ->
                DetailsSeasonCard(
                    season = season,
                    selected = season.stableKey == selectedSeasonKey,
                    onClick = { onSeasonSelected(season.stableKey) },
                )
            }
        }
    }
}

@Composable
private fun DetailsSeasonCard(
    season: ReiAnixSeasonUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val preview = season.episodes.firstOrNull()?.artwork?.localPath
    val responsive = LocalReiAnixResponsiveMetrics.current
    val availableWidth = (
        responsive.maxWidth -
            responsive.horizontalPadding -
            responsive.horizontalPadding
        ).coerceAtLeast(240.dp)
    val title = season.title.ifBlank {
        season.number?.let { "Temporada $it" } ?: "Temporada"
    }
    val subtitle = buildString {
        season.number?.let { append(it) }
        if (season.number != null && season.episodes.isNotEmpty()) append(" • ")
        append(episodeCountLabel(season.episodes.size))
    }

    Surface(
        modifier = Modifier
            .width(minOf(ReiAnixTokens.Dimensions.detailsSeasonCardWidth, availableWidth))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = title + ", " + episodeCountLabel(season.episodes.size)
                stateDescription = if (selected) "Selecionada" else "Não selecionada"
            },
        shape = ReiAnixTokens.Shapes.card,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .width(ReiAnixTokens.Dimensions.detailsSeasonPreviewWidth)
                    .height(ReiAnixTokens.Dimensions.detailsSeasonPreviewHeight)
                    .clip(ReiAnixTokens.Shapes.small),
            ) {
                ReiAnixEpisodeThumbnail(
                    localPath = preview,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    identity = season.stableKey + ":preview",
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DetailsSectionHeader(
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                top = ReiAnixTokens.Spacing.lg,
                bottom = ReiAnixTokens.Spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailsEpisodeItem(
    episode: ReiAnixEpisodeUiModel,
    onWatch: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit,
) {
    var menuExpanded by remember(episode.stableKey) { mutableStateOf(false) }

    ReiAnixEpisodeCard(
        episode = episode,
        modifier = Modifier.padding(
            horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            vertical = ReiAnixTokens.Spacing.xs,
        ),
        onPlay = { onWatch(episode.id) },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Ações do episódio " + episode.displayTitle
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (episode.isWatched) {
                                    "Marcar como não visto"
                                } else {
                                    "Marcar como visto"
                                },
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSetEpisodeWatched(
                                episode.id,
                                !episode.isWatched,
                            )
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun DetailsMetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun episodeCountLabel(count: Int): String =
    if (count == 1) "1 episódio" else "$count episódios"

private fun formatEpisodeNumber(number: Double?): String {
    val value = number?.takeIf { it.isFinite() } ?: return "Episódio"
    return if (value % 1.0 == 0.0) {
        "E" + value.toInt().toString().padStart(2, '0')
    } else {
        "E" + String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}

private fun formatScore(value: Double): String =
    String.format(
        Locale.ROOT,
        "%.1f",
        (value / 10.0).coerceIn(0.0, 10.0),
    )

private fun formatLabel(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV curta"
    "MOVIE" -> "Filme"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "SPECIAL" -> "Especial"
    else -> raw.trim()
}

private fun statusLabel(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "FINISHED" -> "Concluído"
    "RELEASING", "ONGOING" -> "Em exibição"
    "NOT_YET_RELEASED" -> "Ainda não lançado"
    "CANCELLED", "CANCELED" -> "Cancelado"
    else -> raw.trim()
}

private fun seasonLabel(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "WINTER" -> "Inverno"
    "SPRING" -> "Primavera"
    "SUMMER" -> "Verão"
    "FALL" -> "Outono"
    else -> raw.trim()
}
