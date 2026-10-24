package com.reiflix.reiflix_local.ui.details

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
import com.reiflix.reiflix_local.data.anilist.AniListAnime
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixTopBar
import com.reiflix.reiflix_local.ui.artwork.ReiAnixPoster
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixAniListViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixAniListViewModelFactory

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
            .background(MaterialTheme.colorScheme.background),
    ) {
        ReiAnixTopBar(
            title = "Detalhes do AniList",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
            navigationContentDescription = "Voltar para a busca",
            onNavigationClick = { navController.popBackStack() },
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.isDetailsLoading -> ReiAnixLoadingState(
                    title = "Carregando detalhes",
                    message = "Consultando o catálogo AniList…",
                    modifier = Modifier.fillMaxSize(),
                )
                state.detailsError != null -> ReiAnixRecoverableErrorState(
                    title = "Não foi possível carregar os detalhes",
                    message = state.detailsError ?: "Erro desconhecido ao carregar detalhes.",
                    onRetry = { aniListViewModel.loadAniListDetails(anilistId) },
                    modifier = Modifier.fillMaxSize(),
                    retryLabel = "Tentar novamente",
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
                    title = "Detalhes indisponíveis",
                    message = "O AniList não retornou dados para esse identificador.",
                    onRetry = { aniListViewModel.loadAniListDetails(anilistId) },
                    modifier = Modifier.fillMaxSize(),
                    retryLabel = "Tentar novamente",
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
            Text("Sinopse", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } ?: if (anime.descriptionTranslationPending) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sinopse", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Tradução para português brasileiro pendente. A sinopse será exibida após a validação da tradução.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Unit
        }
        associatedLocalAnimeIds.singleOrNull()?.let { localAnimeId ->
            ReiAnixPrimaryButton(
                text = "Abrir detalhes da biblioteca",
                onClick = { onOpenLocalDetails(localAnimeId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (associatedLocalAnimeIds.size > 1) {
            Text(
                text = "Há vários itens locais associados a esta obra. Nenhum foi selecionado automaticamente.",
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
            text = "Resultado do catálogo remoto. Esta busca não cria nem altera registros locais.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
