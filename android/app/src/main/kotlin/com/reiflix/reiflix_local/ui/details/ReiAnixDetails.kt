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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtwork
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiStateProjection
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import com.reiflix.reiflix_local.ui.navigation.navigateToPlayer
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
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

    ReiAnixDetailsScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onRetry = viewModel::refresh,
        onWatch = { episodeId ->
            navController.navigateToPlayer(
                episodeId = episodeId.toString(),
                animeId = canonicalId.toString(),
                origin = origin,
            )
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
    val anime = state.anime
    if (state.status == ReiAnixDetailsLoadStatus.READY && anime != null) {
        ReiAnixDetailsReady(
            anime = anime,
            onBack = onBack,
            onRefresh = onRetry,
            onWatch = onWatch,
            onToggleFavorite = onToggleFavorite,
            onSetEpisodeWatched = onSetEpisodeWatched,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DetailsStatusTopBar(
            enabled = anime != null,
            favorite = anime?.favorite == true,
            onBack = onBack,
            onRefresh = onRetry,
            onToggleFavorite = {
                anime?.id?.let(onToggleFavorite)
            },
        )

        when (state.status) {
            ReiAnixDetailsLoadStatus.LOADING -> {
                ReiAnixDetailsLoadingContent()
            }

            ReiAnixDetailsLoadStatus.READY -> {
                ReiAnixRecoverableErrorState(
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

@Composable
private fun DetailsStatusTopBar(
    enabled: Boolean,
    favorite: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ReiAnixTokens.Spacing.sm,
                vertical = ReiAnixTokens.Spacing.xs,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReiAnixIconActionButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Voltar",
            onClick = onBack,
        )
        Text(
            text = "Detalhes",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        ReiAnixIconActionButton(
            icon = Icons.Filled.Refresh,
            contentDescription = "Atualizar detalhes",
            onClick = onRefresh,
            enabled = enabled,
        )
        ReiAnixIconActionButton(
            icon = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (favorite) {
                "Remover da Minha Lista"
            } else {
                "Adicionar à Minha Lista"
            },
            onClick = onToggleFavorite,
            enabled = enabled,
        )
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
                        .height(330.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(
                    modifier = Modifier.padding(
                        horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        vertical = ReiAnixTokens.Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    DetailsSkeletonLine(width = 0.72f, height = 30.dp)
                    DetailsSkeletonLine(width = 0.46f, height = 18.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
                        DetailsSkeletonLine(width = 0.22f, height = 16.dp)
                        DetailsSkeletonLine(width = 0.20f, height = 16.dp)
                        DetailsSkeletonLine(width = 0.30f, height = 16.dp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                    ) {
                        DetailsSkeletonButton(modifier = Modifier.weight(1f))
                        DetailsSkeletonButton(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.md))
                    DetailsSkeletonLine(width = 0.35f, height = 22.dp)
                    DetailsSkeletonLine(width = 0.92f, height = 16.dp)
                    DetailsSkeletonLine(width = 0.86f, height = 16.dp)
                    DetailsSkeletonLine(width = 0.68f, height = 16.dp)
                }
            }
        }
        item(key = "details-loading-seasons", contentType = "details-loading-seasons") {
            Column(
                modifier = Modifier.padding(
                    horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                    vertical = ReiAnixTokens.Spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                DetailsSkeletonLine(width = 0.34f, height = 24.dp)
                DetailsSkeletonButton(modifier = Modifier.fillMaxWidth())
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
    val selectedSeason = anime.seasons.firstOrNull {
        it.stableKey == selectedSeasonKey
    } ?: anime.seasons.firstOrNull()

    val showSeasonSelector = anime.seasons.size > 1
    val listState = rememberLazyListState()
    val episodeAnchor = if (showSeasonSelector) 3 else 2

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
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
            )
        }

        item(
            key = "details-about:" + anime.stableKey,
            contentType = "details-about",
        ) {
            DetailsAboutSection(anime = anime)
        }

        if (showSeasonSelector) {
            item(
                key = "details-seasons:" + anime.stableKey,
                contentType = "details-seasons",
            ) {
                DetailsSeasonsSection(
                    anime = anime,
                    selectedSeason = selectedSeason,
                    selectedSeasonKey = selectedSeasonKey,
                    onSeasonSelected = { savedSeasonKey = it },
                    onViewEpisodes = {
                        if (listState.layoutInfo.totalItemsCount > episodeAnchor) {
                            listState.requestScrollToItem(episodeAnchor)
                        }
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
                    title = "Episódios",
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
                            horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
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
                        horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        vertical = ReiAnixTokens.Spacing.lg,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailsHero(
    anime: ReiAnixDetailsAnimeUiModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWatch: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ReiAnixTokens.Shapes.hero),
    ) {
        val availableWidth = maxWidth
        val heroHeight = (
            availableWidth * if (availableWidth >= 600.dp) 0.62f else 0.86f
        ).coerceIn(
            300.dp,
            ReiAnixTokens.Dimensions.detailsHeroMaxHeight,
        )
        val backdropPath = anime.artwork?.backdropLocalPath
            ?.takeIf { it.isNotBlank() }
            ?: anime.artwork?.localPath

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight),
        ) {
            ReiAnixLocalArtwork(
                localPath = backdropPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = "Sem backdrop",
                maxDimensionPx = 1024,
                shape = ReiAnixTokens.Shapes.hero,
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.10f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.10f),
                            ),
                        ),
                    ),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ReiAnixTokens.Spacing.md,
                        vertical = ReiAnixTokens.Spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailsHeroIcon(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = "Voltar",
                    onClick = onBack,
                )
                Spacer(modifier = Modifier.weight(1f))
                DetailsHeroIcon(
                    icon = Icons.Filled.Refresh,
                    label = "Atualizar detalhes",
                    onClick = onRefresh,
                )
                DetailsHeroIcon(
                    icon = if (anime.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (anime.favorite) {
                        "Remover da Minha Lista"
                    } else {
                        "Adicionar à Minha Lista"
                    },
                    onClick = { onToggleFavorite(anime.id) },
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        vertical = ReiAnixTokens.Spacing.lg,
                    ),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
            ) {
                if (availableWidth >= 500.dp && !anime.artwork?.localPath.isNullOrBlank()) {
                    ReiAnixLocalArtwork(
                        localPath = anime.artwork?.localPath,
                        contentDescription = "Poster de " + anime.title,
                        modifier = Modifier
                            .width(112.dp)
                            .height(160.dp),
                        contentScale = ContentScale.Crop,
                        placeholder = "Poster",
                        maxDimensionPx = 512,
                        shape = ReiAnixTokens.Shapes.artwork,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                anime.status?.let { rawStatus ->
                    DetailsStatusBadge(rawStatus)
                    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.xs))
                }

                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val alternate = anime.preferredAlternateTitle
                if (!alternate.isNullOrBlank() && !alternate.equals(anime.title, ignoreCase = true)) {
                    Text(
                        text = alternate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (anime.genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.xs))
                    Text(
                        text = anime.genres.joinToString(" • ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val metadata = buildList {
                    anime.score?.let {
                        add("Nota " + formatScore(it) + "/10")
                    }
                    anime.year?.let { add(it.toString()) }
                    anime.episodeCount?.let { count ->
                        add(if (count == 1) "1 episódio" else count.toString() + " episódios")
                    }
                    anime.durationMinutes?.let { add(it.toString() + " min/ep") }
                }
                if (metadata.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.sm))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        anime.score?.let {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconSmall),
                            )
                        }
                        metadata.forEachIndexed { index, text ->
                            if (index > 0 || anime.score == null) {
                                DetailsMetaText(text)
                            } else {
                                DetailsMetaText(text)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.md))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                ) {
                    val target = anime.playbackTargetEpisode
                    if (target != null && target.isPlayable) {
                        ReiAnixPrimaryButton(
                            text = target.playbackActionLabel,
                            onClick = { onWatch(target.id) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        ReiAnixSecondaryButton(
                            text = "Sem mídia disponível",
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    ReiAnixSecondaryButton(
                        text = if (anime.favorite) "Remover da Lista" else "+ Minha Lista",
                        onClick = { onToggleFavorite(anime.id) },
                        modifier = Modifier.weight(1f),
                    )
                }

                val target = anime.playbackTargetEpisode
                if (
                    target != null &&
                    target.progressFraction > 0f &&
                    target.consumptionState != ReiAnixConsumptionState.COMPLETED &&
                    target.consumptionState != ReiAnixConsumptionState.WATCHED
                ) {
                    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.sm))
                    Surface(
                        shape = ReiAnixTokens.Shapes.card,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(ReiAnixTokens.Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                        ) {
                            Text(
                                text = "Continuar " + formatEpisodeNumber(target.number),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = target.displayTitle + " • " + (target.progressPercent ?: 0) + "%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ReiAnixProgressIndicator(
                                progress = target.progressFraction,
                                visible = true,
                            )
                        }
                    }
                }
            }
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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), CircleShape),
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
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                vertical = ReiAnixTokens.Spacing.lg,
            ),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
    ) {
        if (!anime.description.isNullOrBlank()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = "Sobre",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = anime.description,
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
        }
        if (details.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
                Text(
                    text = "Informações",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                details.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (anime.genres.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                anime.genres.forEach { genre ->
                    ReiAnixBadge(
                        text = genre.name,
                        tone = ReiAnixBadgeTone.Neutral,
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
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
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
            if (selectedSeason != null) {
                Text(
                    text = "Ver todas",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onViewEpisodes)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Ver todos os episódios"
                        }
                        .padding(ReiAnixTokens.Spacing.xs),
                )
                Text(
                    text = "›",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            contentPadding = PaddingValues(end = ReiAnixTokens.Dimensions.screenHorizontalPadding),
        ) {
            items(
                items = anime.seasons,
                key = { season -> season.stableKey },
                contentType = { "details-season" },
            ) { season ->
                ReiAnixChip(
                    text = season.title,
                    selected = season.stableKey == selectedSeasonKey,
                    onClick = { onSeasonSelected(season.stableKey) },
                    modifier = Modifier.semantics {
                        contentDescription = "Selecionar " + season.title
                    },
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
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                top = ReiAnixTokens.Spacing.lg,
                bottom = ReiAnixTokens.Spacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
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
            horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
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
                            onSetEpisodeWatched(episode.id, !episode.isWatched)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun DetailsStatusBadge(status: String) {
    val tone = when (status.trim().uppercase(Locale.ROOT)) {
        "FINISHED", "COMPLETED" -> ReiAnixBadgeTone.Success
        "RELEASING", "ONGOING" -> ReiAnixBadgeTone.Primary
        "CANCELLED", "CANCELED" -> ReiAnixBadgeTone.Error
        "NOT_YET_RELEASED" -> ReiAnixBadgeTone.Warning
        else -> ReiAnixBadgeTone.Info
    }
    ReiAnixBadge(
        text = statusLabel(status),
        tone = tone,
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
    if (count == 1) "1 episódio" else count.toString() + " episódios"

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
}
