package com.reiflix.reiflix_local.ui.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixAnimeCard
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
import com.reiflix.reiflix_local.ui.artwork.ReiAnixPoster
import com.reiflix.reiflix_local.ui.library.rememberReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeAnimeUiModel
import com.reiflix.reiflix_local.ui.model.presentationStateLabel
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
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReiAnixHomeRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel = rememberReiAnixLibraryViewModel(),
    cardSize: String = "medium",
    showThumbnails: Boolean = true,
    onOpenCollector: () -> Unit = {},
) {
    val state by viewModel.homeState.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()

    ReiAnixHomeObservedScreen(
        state = state,
        continueWatching = continueWatching,
        cardSize = cardSize,
        showThumbnails = showThumbnails,
        onOpenCollector = onOpenCollector,
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
    cardSize: String = "medium",
    showThumbnails: Boolean = true,
    onOpenCollector: () -> Unit = {},
    onSearch: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSource: () -> Unit,
) {
    ReiAnixResponsiveRoot {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HomeHeader(
            onOpenCollector = onOpenCollector,
            onSearch = onSearch,
            onRefresh = onRefresh,
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> {
                if (state.animes.isNotEmpty()) {
                    HomeObservedContent(
                        state = state,
                        continueWatching = continueWatching,
                        cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        onOpenMyList = onOpenMyList,
                        onOpenLibrary = onOpenLibrary,
                    )
                } else {
                    ReiAnixLoadingState(
                        title = "Carregando biblioteca",
                        message = "Os primeiros itens aparecerão assim que estiverem disponíveis.",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ReiAnixLibraryLoadStatus.ERROR -> {
                // Never discard a usable catalog because a later projection failed.
                if (state.animes.isNotEmpty()) {
                    HomeObservedContent(
                        state = state,
                        continueWatching = continueWatching,
                        cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        onOpenMyList = onOpenMyList,
                        onOpenLibrary = onOpenLibrary,
                    )
                } else {
                    ReiAnixRecoverableErrorState(
                        title = "Não foi possível carregar a biblioteca",
                        message = state.error ?: "A biblioteca local retornou um erro.",
                        onRetry = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> {
                if (state.animes.isNotEmpty()) {
                    HomeObservedContent(
                        state = state,
                        continueWatching = continueWatching,
                        cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        onOpenMyList = onOpenMyList,
                        onOpenLibrary = onOpenLibrary,
                    )
                } else {
                    ReiAnixSourceUnavailableState(
                        title = "Biblioteca local indisponível",
                        message = "A fonte local configurada não está disponível agora.",
                        actionLabel = "Selecionar pasta",
                        onAction = onSelectSource,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ReiAnixLibraryLoadStatus.EMPTY -> {
                if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    ReiAnixEmptyLibraryState(
                        message = "Configure uma pasta da biblioteca para começar a usar a Home.",
                        actionLabel = "Selecionar pasta",
                        onAction = onSelectSource,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ReiAnixEmptyLibraryState(
                        message = "Nenhum vídeo compatível foi encontrado nas fontes configuradas.",
                        actionLabel = "Selecionar pasta",
                        onAction = onSelectSource,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ReiAnixLibraryLoadStatus.READY -> HomeObservedContent(
                state = state,
                continueWatching = continueWatching,
                cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onOpenMyList = onOpenMyList,
                onOpenLibrary = onOpenLibrary,
            )
        }
    }

    }
}

@Composable
private fun ColumnScope.HomeObservedContent(
    state: ReiAnixHomeLibraryUiState,
    continueWatching: List<ReiAnixContinueWatchingUiModel>,
    cardSize: String = "medium",
    showThumbnails: Boolean = true,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    HomeReadyContent(
        animes = state.animes,
        continueWatching = continueWatching,
        cardSize = cardSize,
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
    cardSize: String = "medium",
    showThumbnails: Boolean = true,
    onSearch: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onWatch: (episodeId: Long, animeId: Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onRefresh: () -> Unit,
    onOpenMyList: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onSelectSource: () -> Unit = onRefresh,
) {
    ReiAnixResponsiveRoot {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HomeHeader(
            onSearch = onSearch,
            onRefresh = onRefresh,
        )

        when (state.status) {
            ReiAnixLibraryLoadStatus.LOADING -> {
                if (state.animes.isNotEmpty()) {
                    HomeContent(
                        state = state,
                        cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        onOpenMyList = onOpenMyList,
                        onOpenLibrary = onOpenLibrary,
                    )
                } else {
                    HomeLoading()
                }
            }
            ReiAnixLibraryLoadStatus.ERROR -> {
                if (state.animes.isNotEmpty()) {
                    HomeContent(
                        state = state,
                        cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        onOpenMyList = onOpenMyList,
                        onOpenLibrary = onOpenLibrary,
                    )
                } else {
                    HomeError(
                        message = state.error ?: "Não foi possível carregar a biblioteca local.",
                        onRefresh = onRefresh,
                    )
                }
            }
            ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> {
                if (state.animes.isNotEmpty()) {
                    HomeContent(
                        state = state,
                        cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        onOpenMyList = onOpenMyList,
                        onOpenLibrary = onOpenLibrary,
                    )
                } else {
                    HomeMessage(
                        title = "Biblioteca local indisponível",
                        message = "A fonte local configurada não está disponível agora.",
                        actionLabel = "Selecionar pasta",
                        onAction = onSelectSource,
                    )
                }
            }
            ReiAnixLibraryLoadStatus.EMPTY -> HomeMessage(
                title = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    "Configure sua biblioteca"
                } else {
                    "Nenhum conteúdo disponível"
                },
                message = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    "Selecione uma pasta da biblioteca para começar."
                } else {
                    "Nenhum vídeo compatível foi encontrado nas fontes configuradas."
                },
                actionLabel = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    "Selecionar pasta"
                } else {
                    "Atualizar"
                },
                onAction = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    onSelectSource
                } else {
                    onRefresh
                },
            )
            ReiAnixLibraryLoadStatus.READY -> HomeContent(
                state = state,
                cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onOpenMyList = onOpenMyList,
                onOpenLibrary = onOpenLibrary,
            )
        }
    }

    }
}

@Composable
private fun HomeHeader(
    onOpenCollector: () -> Unit = {},
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    val menuExpanded = rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ReiAnix",
            style = ReiAnixTokens.TypographyTokens.brandTitle,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
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
        Box {
            IconButton(
                onClick = { menuExpanded.value = true },
                modifier = Modifier.semantics {
                    contentDescription = "Mais opções da Home"
                    role = Role.Button
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropdownMenu(
                expanded = menuExpanded.value,
                onDismissRequest = { menuExpanded.value = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = { Text("Atualizar biblioteca") },
                    onClick = {
                        menuExpanded.value = false
                        onRefresh()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Abrir Collector") },
                    onClick = {
                        menuExpanded.value = false
                        onOpenCollector()
                    },
                )
            }
        }
    }
}
@Composable
private fun ColumnScope.HomeContent(
    state: ReiAnixLibraryUiState,
    cardSize: String = "medium",
    showThumbnails: Boolean = true,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val homeState = remember(state) {
        ReiAnixHomeLibraryUiState.from(state)
    }

    HomeReadyContent(
        animes = homeState.animes,
        continueWatching = state.continueWatching,
        cardSize = cardSize,
                    onOpenDetails = onOpenDetails,
        onWatch = onWatch,
        onHeroSecondaryAction = { featured -> onOpenDetails(featured.id) },
        heroSecondaryLabel = { "Detalhes" },
        onOpenMyList = onOpenMyList,
        onOpenLibrary = onOpenLibrary,
    )
}

@Composable
private fun ColumnScope.HomeReadyContent(
    animes: List<ReiAnixHomeAnimeUiModel>,
    continueWatching: List<ReiAnixContinueWatchingUiModel>,
    cardSize: String = "medium",
    showThumbnails: Boolean = true,
    onOpenDetails: (Long) -> Unit,
    onWatch: (Long, Long) -> Unit,
    onHeroSecondaryAction: (ReiAnixHomeAnimeUiModel) -> Unit,
    heroSecondaryLabel: (ReiAnixHomeAnimeUiModel) -> String,
    onOpenMyList: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val responsive = LocalReiAnixResponsiveMetrics.current
    val recentlyAdded = remember(animes) { buildRecentlyAddedItems(animes) }
    val recentlyWatched = remember(animes) { buildRecentlyWatchedItems(animes) }
    val favorites = remember(animes) { animes.filter { it.favorite }.distinctBy { it.id } }
    val available = remember(animes) {
        animes
            .asSequence()
            .filter { it.availableContentCount > 0 }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<ReiAnixHomeAnimeUiModel> { it.isWatching }
                    .thenByDescending { it.lastPlayedAt ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.addedAt ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.title.trim().lowercase() },
            )
            .take(12)
            .toList()
    }
    val genreSections = remember(animes) { buildHomeGenreSections(animes) }
    val movies = remember(animes) {
        animes.asSequence()
            .filter { it.mediaKind == ReiAnixMediaKind.MOVIE }
            .distinctBy { it.id }
            .toList()
    }

    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .widthIn(max = responsive.contentMaxWidth)
            .fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xxl),
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
                    showThumbnails = showThumbnails,
                    onWatch = onWatch,
                    onSeeAll = onOpenLibrary,
                )
            }
        }
        if (recentlyAdded.isNotEmpty()) {
            item(key = "home-section-recently-added") {
                HomeMediaSection(
                    title = "Adicionados recentemente",
                    items = recentlyAdded,
                    cardWidth = homeAnimeCardWidth(cardSize, responsive),
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Adicionados recentemente",
                    onSeeAll = onOpenLibrary,
                )
            }
        }
        if (available.isNotEmpty()) {
            item(key = "home-section-available") {
                HomeMediaSection(
                    title = "Conteúdo disponível",
                    items = available,
                    cardWidth = homeAnimeCardWidth(cardSize, responsive),
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Conteúdo disponível",
                    onSeeAll = onOpenLibrary,
                )
            }
        }

        if (recentlyWatched.isNotEmpty()) {
            item(key = "home-section-recently-watched") {
                HomeMediaSection(
                    title = "Recentemente assistidos",
                    items = recentlyWatched,
                    cardWidth = homeAnimeCardWidth(cardSize, responsive),
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Recentemente assistidos",
                    onSeeAll = onOpenLibrary,
                )
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "home-section-my-list") {
                HomeMediaSection(
                    title = "Minha lista",
                    items = favorites,
                    cardWidth = homeAnimeCardWidth(cardSize, responsive),
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
                    cardWidth = homeAnimeCardWidth(cardSize, responsive),
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home " + section.title,
                )
            }
        }
        if (movies.isNotEmpty()) {
            item(key = "home-section-movies") {
                HomeMediaSection(
                    title = "Filmes",
                    items = movies,
                    cardWidth = homeAnimeCardWidth(cardSize, responsive),
                    onOpenDetails = onOpenDetails,
                    contentDescription = "Home Filmes",
                )
            }
        }
    }
}
@Composable
private fun HomeHero(
    anime: ReiAnixHomeAnimeUiModel,
    onWatch: (Long, Long) -> Unit,
    onSecondaryAction: (ReiAnixHomeAnimeUiModel) -> Unit,
    secondaryLabel: (ReiAnixHomeAnimeUiModel) -> String,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val heroHeight = LocalReiAnixResponsiveMetrics.current.homeHeroHeight()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .clip(ReiAnixTokens.Shapes.hero),
        ) {
            ReiAnixBackdrop(
                localPath = anime.artwork?.backdropLocalPath,
                fallbackLocalPath = anime.artwork?.localPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                identity = anime.stableKey,
                maxDimensionPx = 768,
                externalUrl = anime.artwork?.backdropExternalUrl,
                fallbackExternalUrl = anime.artwork?.externalUrl,
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
                        start = ReiAnixTokens.Spacing.xl,
                        end = ReiAnixTokens.Spacing.xl,
                        bottom = ReiAnixTokens.Spacing.xl,
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
                    style = ReiAnixTokens.TypographyTokens.heroTitle,
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
                            style = ReiAnixTokens.TypographyTokens.bodySecondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    anime.playbackTargetEpisodeId?.let { episodeId ->
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
    showThumbnails: Boolean = true,
    onWatch: (Long, Long) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        HomeSectionHeader(
            title = "Continuar assistindo",
            onSeeAll = onSeeAll,
        )

        LazyRow(
            modifier = Modifier,

            contentPadding = PaddingValues(
                end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            items(
                items = items,
                key = { it.stableKey },
                contentType = { "home-continue-episode" },
            ) { item ->
                HomeContinueCard(
                    item = item,
                    showThumbnails = showThumbnails,
                    onWatch = onWatch,
                )
            }
        }
    }
}

@Composable
private fun HomeContinueCard(
    item: ReiAnixContinueWatchingUiModel,
    showThumbnails: Boolean = true,
    onWatch: (Long, Long) -> Unit,
) {
    val progress = progressFraction(item.progressSeconds, item.durationSeconds)
    val progressText = progress?.let(::formatProgressPercent)
    val episodeText = episodeLabel(item.seasonNumber, item.number)
    val durationText = item.durationSeconds
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let(::formatDuration)
    val detailText = listOfNotNull(
        episodeText.takeIf { it.isNotBlank() },
        item.displayTitle.takeIf { it.isNotBlank() },
        durationText,
        progressText,
    ).joinToString(" • ")

    Card(
        modifier = Modifier
            .width(LocalReiAnixResponsiveMetrics.current.homeContinueCardWidth)
            .clickable(onClick = { onWatch(item.episodeId, item.animeId) })
            .semantics {
                role = Role.Button
                contentDescription =
                    listOfNotNull(
                        item.animeTitle,
                        episodeText.takeIf { it.isNotBlank() },
                        item.displayTitle.takeIf { it.isNotBlank() },
                        durationText,
                        progressText,
                    ).joinToString(" — ")
            },
        shape = ReiAnixTokens.Shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.background,
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
                    localPath = item.artwork?.localPath.takeIf { showThumbnails },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    identity = item.stableKey,
                    externalUrl = item.artwork?.externalUrl.takeIf { showThumbnails },
                )

                ReiAnixProgressIndicator(
                    progress = progress ?: 0f,
                    visible = progress != null,
                    announceProgress = false,
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
                    )
                    .clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = item.animeTitle,
                    style = ReiAnixTokens.TypographyTokens.cardTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
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

private fun homeAnimeCardWidth(
    preference: String,
    responsive: com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveMetrics,
): androidx.compose.ui.unit.Dp = responsive.homeCardWidth(preference)

@Composable
private fun HomeMediaSection(
    title: String,
    items: List<ReiAnixHomeAnimeUiModel>,
    cardWidth: androidx.compose.ui.unit.Dp = ReiAnixTokens.Dimensions.homeCardWidth,
    onOpenDetails: (Long) -> Unit,
    contentDescription: String,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        HomeSectionHeader(title = title, onSeeAll = onSeeAll)

        LazyRow(
            modifier = Modifier.semantics {
                this.contentDescription = contentDescription
            },
            contentPadding = PaddingValues(
                end = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
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
                    cardWidth = cardWidth,
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
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = "Ver tudo: " + title
                },
            ) {
                Text(
                    text = "Ver tudo  ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun HomeMediaCard(
    anime: ReiAnixHomeAnimeUiModel,
    cardWidth: androidx.compose.ui.unit.Dp = ReiAnixTokens.Dimensions.homeCardWidth,
    onClick: () -> Unit,
) {
    // Home, Biblioteca, Search and Minha Lista deliberately share the same
    // canonical anime card so artwork, state badges and progress never drift.
    ReiAnixAnimeCard(
        anime = anime,
        modifier = Modifier.width(cardWidth),
        onClick = onClick,
        maxDimensionPx = 512,
        bottomBadgeText = presentationStateLabel(anime.presentationState),
    )
}

private fun selectFeaturedAnime(items: List<ReiAnixHomeAnimeUiModel>): ReiAnixHomeAnimeUiModel =
    items.firstOrNull {
        it.playbackTargetEpisodeId != null && it.playbackActionLabel != "Indisponível"
    }
        ?: items.firstOrNull { it.isWatching }
        ?: items.firstOrNull { it.favorite }
        ?: items.firstOrNull { it.pinned }
        ?: items.firstOrNull()
        ?: error("Home hero requested without library data")

private fun buildRecentlyAddedItems(
    items: List<ReiAnixHomeAnimeUiModel>,
): List<ReiAnixHomeAnimeUiModel> =
    items
        .asSequence()
        .filter { it.addedAt?.isFinite() == true }
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<ReiAnixHomeAnimeUiModel> { it.addedAt ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.title.trim().lowercase() },
        )
        .take(12)
        .toList()

private fun buildRecentlyWatchedItems(
    items: List<ReiAnixHomeAnimeUiModel>,
): List<ReiAnixHomeAnimeUiModel> =
    items
        .asSequence()
        .filter { it.lastPlayedAt?.isFinite() == true && (it.lastPlayedAt ?: 0.0) > 0.0 }
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<ReiAnixHomeAnimeUiModel> { it.lastPlayedAt ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.title.trim().lowercase() },
        )
        .take(12)
        .toList()

private fun buildHomeGenreSections(
    items: List<ReiAnixHomeAnimeUiModel>,
): List<HomeGenreSection> {
    data class MutableSection(
        val key: String,
        val title: String,
        val items: MutableList<ReiAnixHomeAnimeUiModel>,
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
    val items: List<ReiAnixHomeAnimeUiModel>,
)

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

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds
        .coerceAtLeast(0.0)
        .toLong()
    val minutes = totalSeconds / 60
    val remainingSeconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, remainingMinutes, remainingSeconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }
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
