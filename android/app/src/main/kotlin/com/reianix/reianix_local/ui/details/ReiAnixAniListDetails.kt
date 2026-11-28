package com.reianix.reianix_local.ui.details

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.navigation.NavHostController
import com.reianix.reianix_local.data.anilist.AniListAnime
import com.reianix.reianix_local.ui.ReiAnixBadge
import com.reianix.reianix_local.ui.ReiAnixBadgeTone
import com.reianix.reianix_local.ui.ReiAnixLoadingState
import com.reianix.reianix_local.ui.ReiAnixPrimaryButton
import com.reianix.reianix_local.ui.ReiAnixRecoverableErrorState
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.artwork.ReiAnixPoster
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.navigation.navigateToDetails
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import com.reianix.reianix_local.viewmodel.ReiAnixAniListViewModel
import com.reianix.reianix_local.viewmodel.ReiAnixAniListViewModelFactory

/**
 * Read-only Details destination for a remote AniList result that has no local ID.
 * It intentionally does not create a SQLite anime, favorite, personal-list entry,
 * playback row, or AniList association.
 */
@Composable
fun ReiAnixAniListDetailsRoute(
    navController: NavHostController,
    anilistId: Int,
    origin: String = ReiAnixRoutes.SEARCH,
) {
    val aniListViewModel: ReiAnixAniListViewModel = composeViewModel(
        factory = ReiAnixAniListViewModelFactory(LocalContext.current),
    )
    val state by aniListViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(anilistId, aniListViewModel) {
        aniListViewModel.loadAniListDetails(anilistId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(reiAnixScreenBackground()),
    ) {
        ReiAnixTopBar(
            title = stringResource(R.string.more_anilist_details),
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
            navigationContentDescription = "Voltar para a busca",
            onNavigationClick = { navController.popBackStack() },
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.isDetailsLoading -> ReiAnixLoadingState(
                    title = stringResource(R.string.more_details_loading),
                    message = stringResource(R.string.more_anilist_reading),
                    modifier = Modifier.fillMaxSize(),
                )
                state.detailsError != null -> ReiAnixRecoverableErrorState(
                    title = stringResource(R.string.more_details_load_error),
                    message = state.detailsError ?: "Erro desconhecido ao carregar detalhes.",
                    onRetry = { aniListViewModel.loadAniListDetails(anilistId) },
                    modifier = Modifier.fillMaxSize(),
                    retryLabel = stringResource(R.string.ui_retry),
                )
                state.selectedAnime != null -> RemoteAniListDetailsContent(
                    anime = state.selectedAnime!!,
                    associatedLocalAnimeIds = state.associatedLocalAnimeIds,
                    localAssociationError = state.localAssociationError,
                    onOpenLocalDetails = { localAnimeId ->
                        navController.navigateToDetails(localAnimeId.toString(), origin)
                    },
                )
                else -> ReiAnixRecoverableErrorState(
                    title = stringResource(R.string.ui_details_unavailable),
                    message = stringResource(R.string.more_anilist_id_missing),
                    onRetry = { aniListViewModel.loadAniListDetails(anilistId) },
                    modifier = Modifier.fillMaxSize(),
                    retryLabel = stringResource(R.string.ui_retry),
                )
            }
        }
    }
}

@Composable
private fun RemoteAniListDetailsContent(
    anime: AniListAnime,
    associatedLocalAnimeIds: List<Long>,
    localAssociationError: String?,
    onOpenLocalDetails: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding)
            .padding(bottom = ReiAnixTokens.Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        Spacer(modifier = Modifier.height(ReiAnixTokens.Spacing.xs))
        ReiAnixPoster(
            localPath = null,
            contentDescription = "Capa de ${anime.preferredTitle}",
            modifier = Modifier.fillMaxWidth().height(300.dp),
            identity = "anilist:${anime.id}",
            externalUrl = anime.coverExtraLargeUrl ?: anime.coverLargeUrl,
        )
        Text(
            text = anime.preferredTitle.ifBlank { "Anime AniList #${anime.id}" },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val alternateTitles = listOfNotNull(anime.titleRomaji, anime.titleEnglish, anime.titleNative)
            .filter { it.isNotBlank() && it != anime.preferredTitle }
            .distinct()
        if (alternateTitles.isNotEmpty()) {
            Text(
                text = alternateTitles.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs)) {
            anime.seasonYear?.let { ReiAnixBadge(text = it.toString(), tone = ReiAnixBadgeTone.Neutral) }
            anime.format?.replace('_', ' ')?.let {
                ReiAnixBadge(text = it.lowercase().replaceFirstChar { ch -> ch.uppercase() }, tone = ReiAnixBadgeTone.Neutral)
            }
            anime.status?.let {
                ReiAnixBadge(text = it.lowercase().replace('_', ' ').replaceFirstChar { ch -> ch.uppercase() }, tone = ReiAnixBadgeTone.Neutral)
            }
        }
        if (anime.genres.isNotEmpty()) {
            Text(
                text = "Gêneros: " + anime.genres.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        anime.episodes?.let {
            Text("Episódios anunciados: $it", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        anime.studios.takeIf { it.isNotEmpty() }?.let {
            Text("Estúdio: " + it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        anime.averageScore?.let {
            Text("Nota AniList: $it/100", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        anime.description?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.ui_synopsis), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        associatedLocalAnimeIds.singleOrNull()?.let { localAnimeId ->
            ReiAnixPrimaryButton(
                text = stringResource(R.string.more_open_library_details),
                onClick = { onOpenLocalDetails(localAnimeId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (associatedLocalAnimeIds.size > 1) {
            Text(
                text = stringResource(R.string.more_multiple_local),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        localAssociationError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.more_remote_result),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
