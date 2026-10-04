package com.reiflix.reiflix_local.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reiflix.reiflix_local.ui.ReiAnixEmptyLibraryState
import com.reiflix.reiflix_local.ui.ReiAnixEmptyState
import com.reiflix.reiflix_local.ui.ReiAnixFileUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.artwork.ReiAnixLocalArtwork
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiStateProjection
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import com.reiflix.reiflix_local.ui.navigation.navigateToPlayer
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

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
    val state by detailsStateFlow.collectAsStateWithLifecycle()

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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReiAnixTokens.Colors.background),
    ) {
        ReiAnixDetailsTopBar(
            favorite = state.anime?.favorite ?: false,
            enabled = state.anime != null,
            onBack = onBack,
            onToggleFavorite = {
                state.anime?.id?.let(onToggleFavorite)
            },
        )

        when (state.status) {
            ReiAnixDetailsLoadStatus.LOADING -> ReiAnixLoadingState(
                title = "Carregando detalhes",
                message = "Lendo os dados da biblioteca local…",
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixDetailsLoadStatus.READY -> {
                val anime = state.anime
                if (anime != null) {
                    ReiAnixDetailsReady(
                        anime = anime,
                        onWatch = onWatch,
                        onSetEpisodeWatched = onSetEpisodeWatched,
                    )
                } else {
                    ReiAnixRecoverableErrorState(
                        title = "Detalhes indisponíveis",
                        message = "Os dados do anime não estão disponíveis.",
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
                title = "Anime não encontrado",
                message = state.error ?: "O anime não está presente na biblioteca local.",
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
private fun ReiAnixDetailsTopBar(
    favorite: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
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
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics {
                contentDescription = "Voltar"
            },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = ReiAnixTokens.Colors.text,
            )
        }
        Text(
            text = "Detalhes",
            style = MaterialTheme.typography.titleLarge,
            color = ReiAnixTokens.Colors.text,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            enabled = enabled,
            onClick = onToggleFavorite,
            modifier = Modifier.semantics {
                contentDescription = if (favorite) {
                    "Remover da Minha Lista"
                } else {
                    "Adicionar à Minha Lista"
                }
            },
        ) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (favorite) {
                    ReiAnixTokens.Colors.warning
                } else {
                    ReiAnixTokens.Colors.text
                },
            )
        }
    }
}

@Composable
private fun ReiAnixDetailsReady(
    anime: ReiAnixDetailsAnimeUiModel,
    onWatch: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit,
) {
    var selectedSeasonKey by rememberSaveable(anime.id) {
        mutableStateOf(anime.seasons.firstOrNull()?.stableKey)
    }
    val selectedSeason = anime.seasons.firstOrNull { it.stableKey == selectedSeasonKey }
        ?: anime.seasons.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("details-episode-list"),
        contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xxxl),
    ) {
        item(key = "details-hero:" + anime.stableKey, contentType = "details-hero") {
            Column {
                ReiAnixLocalArtwork(
                    localPath = anime.artwork?.localPath,
                    contentDescription = anime.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .padding(horizontal = ReiAnixTokens.Spacing.lg),
                    contentScale = ContentScale.Crop,
                    placeholder = "Sem capa",
                    maxDimensionPx = 1024,
                )

                Column(
                    modifier = Modifier.padding(
                        horizontal = ReiAnixTokens.Spacing.lg,
                        vertical = ReiAnixTokens.Spacing.lg,
                    ),
                ) {
                    Text(
                        text = anime.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = ReiAnixTokens.Colors.text,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val facts = buildList {
                        anime.year?.let { add(it.toString()) }
                        anime.score?.let { add("Nota " + formatScore(it) + "/10") }
                        anime.episodeCount?.let { add(it.toString() + " episódios") }
                    }
                    if (facts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.sm))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                        ) {
                            facts.forEach { fact ->
                                DetailsFactChip(fact)
                            }
                        }
                    }

                    if (anime.genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.md))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                        ) {
                            anime.genres.forEach { genre ->
                                DetailsFactChip(genre.name)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.lg))

                    val targetId = anime.playbackTargetEpisodeId
                    if (targetId != null) {
                        ReiAnixPrimaryButton(
                            text = if (anime.shouldContinue) "Continuar" else "Assistir",
                            onClick = { onWatch(targetId) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = "Nenhuma mídia local disponível para reprodução.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReiAnixTokens.Colors.textMuted,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (anime.seasons.size > 1) {
            item(key = "details-season-selector", contentType = "details-season-selector") {
                DetailsSeasonSelector(
                    seasons = anime.seasons,
                    selectedSeasonKey = selectedSeason?.stableKey,
                    onSeasonSelected = { selectedSeasonKey = it },
                )
            }
        }

        selectedSeason?.let { season ->
            item(
                key = "details-season-heading:" + season.stableKey,
                contentType = "details-season-heading",
            ) {
                Text(
                    text = season.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = ReiAnixTokens.Colors.text,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        top = ReiAnixTokens.Spacing.lg,
                        bottom = ReiAnixTokens.Spacing.sm,
                    ),
                )
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

        if (anime.specials.isNotEmpty()) {
            item(key = "details-specials-heading", contentType = "details-specials-heading") {
                Text(
                    text = "Especiais",
                    style = MaterialTheme.typography.titleLarge,
                    color = ReiAnixTokens.Colors.text,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        start = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        end = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        top = ReiAnixTokens.Spacing.lg,
                        bottom = ReiAnixTokens.Spacing.sm,
                    ),
                )
            }
            items(
                items = anime.specials,
                key = { episode -> episode.stableKey },
                contentType = { "details-special" },
            ) { episode ->
                DetailsEpisodeItem(
                    episode = episode,
                    onWatch = onWatch,
                    onSetEpisodeWatched = onSetEpisodeWatched,
                )
            }
        }
    }
}

@Composable
private fun DetailsSeasonSelector(
    seasons: List<ReiAnixSeasonUiModel>,
    selectedSeasonKey: String?,
    onSeasonSelected: (String) -> Unit,
) {
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
        items(
            items = seasons,
            key = { season -> season.stableKey },
            contentType = { "details-season" },
        ) { season ->
            val selected = season.stableKey == selectedSeasonKey
            AssistChip(
                onClick = { onSeasonSelected(season.stableKey) },
                label = { Text(season.title) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (selected) {
                        ReiAnixTokens.Colors.primaryContainer
                    } else {
                        ReiAnixTokens.Colors.surfaceVariant
                    },
                    labelColor = ReiAnixTokens.Colors.text,
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Selecionar " + season.title
                },
            )
        }
    }
}

@Composable
private fun DetailsEpisodeItem(
    episode: ReiAnixEpisodeUiModel,
    onWatch: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                vertical = ReiAnixTokens.Spacing.xs,
            ),
        shape = ReiAnixTokens.Shapes.card,
        color = ReiAnixTokens.Colors.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ReiAnixTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReiAnixLocalArtwork(
                localPath = episode.artwork?.localPath,
                contentDescription = episode.displayTitle,
                modifier = Modifier
                    .height(64.dp)
                    .fillMaxWidth(0.27f),
                contentScale = ContentScale.Crop,
                placeholder = "Sem thumbnail",
                maxDimensionPx = 320,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = ReiAnixTokens.Spacing.sm)
                    .semantics {
                        contentDescription = "Abrir " + episode.displayTitle
                    }
                    .then(
                        if (episode.isPlayable) {
                            Modifier.clickable { onWatch(episode.id) }
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = formatEpisodeNumber(episode.number) + " • " + episode.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = ReiAnixTokens.Colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        formatDuration(episode.durationSeconds)?.let {
                            append(it)
                        }
                        episode.progressPercent?.let {
                            if (isNotEmpty()) append(" • ")
                            append(it)
                            append("% assistido")
                        }
                        if (isEmpty()) append("Duração indisponível")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ReiAnixTokens.Colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { episode.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
                if (!episode.isPlayable) {
                    ReiAnixFileUnavailableState(
                        message = "Restaure o arquivo na fonte autorizada e atualize a biblioteca.",
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                    )
                }
            }

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
                        tint = ReiAnixTokens.Colors.textMuted,
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
        }
    }
}

private fun formatEpisodeNumber(number: Double?): String {
    val value = number?.takeIf { it.isFinite() } ?: return "Episódio"
    return if (value % 1.0 == 0.0) {
        "E" + value.toInt().toString().padStart(2, '0')
    } else {
        "E" + String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}

private fun formatDuration(seconds: Double?): String? {
    val total = seconds?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: return null
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainingSeconds = total % 60
    return if (hours > 0) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d", minutes, remainingSeconds)
    }
}

@Composable
private fun DetailsFactChip(
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


private fun formatScore(value: Double): String =
    String.format(java.util.Locale.ROOT, "%.1f", (value / 10.0).coerceIn(0.0, 10.0))
