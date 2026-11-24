package com.reianix.reianix_local.ui.details

import com.reianix.reianix_local.data.tracemoe.ReiAnixTraceMoeConsent
import com.reianix.reianix_local.ui.privacy.TraceMoeConsentDialog

import com.reianix.reianix_local.ui.motion.reiAnixScreenFade
import com.reianix.reianix_local.ui.theme.reiAnixContentBackground

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.reianix.reianix_local.ui.ReiAnixBadge
import com.reianix.reianix_local.ui.ReiAnixBadgeTone
import com.reianix.reianix_local.ui.ReiAnixCompactButton
import com.reianix.reianix_local.ui.ReiAnixChip
import com.reianix.reianix_local.ui.ReiAnixEmptyLibraryState
import com.reianix.reianix_local.ui.ReiAnixEmptyState
import com.reianix.reianix_local.ui.ReiAnixEpisodeCard
import com.reianix.reianix_local.ui.ReiAnixIconActionButton
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.ReiAnixPrimaryButton
import com.reianix.reianix_local.ui.ReiAnixProgressIndicator
import com.reianix.reianix_local.ui.formatDurationLabel
import com.reianix.reianix_local.ui.ReiAnixRecoverableErrorState
import com.reianix.reianix_local.ui.ReiAnixSecondaryButton
import com.reianix.reianix_local.ui.ReiAnixSourceUnavailableState
import com.reianix.reianix_local.ui.artwork.ReiAnixBackdrop
import com.reianix.reianix_local.ui.discovery.ReiAnixMarathonDialog
import com.reianix.reianix_local.ui.artwork.ReiAnixEpisodeThumbnail
import com.reianix.reianix_local.ui.artwork.ReiAnixPoster
import com.reianix.reianix_local.ui.model.ReiAnixLibraryUiState
import kotlinx.coroutines.flow.StateFlow
import com.reianix.reianix_local.ui.model.ReiAnixConsumptionState
import com.reianix.reianix_local.ui.model.presentationStateLabel
import com.reianix.reianix_local.ui.model.ReiAnixDetailsAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixDetailsLoadStatus
import com.reianix.reianix_local.ui.model.ReiAnixDetailsUiState
import com.reianix.reianix_local.ui.model.ReiAnixDetailsUiStateProjection
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaKind
import com.reianix.reianix_local.ui.model.ReiAnixSeasonUiModel
import com.reianix.reianix_local.ui.motion.ReiAnixMotionPolicy
import com.reianix.reianix_local.ui.navigation.isActiveDetailsDestination
import com.reianix.reianix_local.ui.player.rememberEpisodeLauncher
import com.reianix.reianix_local.ui.navigation.navigateToDetails
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveBreakpoints
import androidx.compose.foundation.layout.widthIn
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import com.reianix.reianix_local.viewmodel.ReiAnixTraceMoeIdentificationStatus
import com.reianix.reianix_local.viewmodel.ReiAnixTraceMoeIdentificationUiState
import com.reianix.reianix_local.viewmodel.ReiAnixAniListUiState
import com.reianix.reianix_local.viewmodel.ReiAnixAniListViewModel
import com.reianix.reianix_local.viewmodel.ReiAnixAniListViewModelFactory
import com.reianix.reianix_local.data.anilist.AniListAnime
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.Locale

@Composable
fun ReiAnixDetailsRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
    animeId: String,
    origin: String,
    showThumbnails: Boolean = true,
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
    // The underlying catalog is already in memory when returning from the player.
    // Starting with a synthetic LOADING state flashes the entire Details content
    // until the asynchronous flow emits, even though the anime is available.
    val initialDetailsState = remember(viewModel, canonicalId) {
        ReiAnixLibraryViewModel.projectDetailsState(viewModel.uiState.value, canonicalId)
    }
    val state by detailsStateFlow.collectAsStateWithLifecycle(
        initialValue = initialDetailsState,
    )
    val traceMoeStates by viewModel.traceMoeIdentificationStates.collectAsStateWithLifecycle()
    val traceMoeState = traceMoeStates[canonicalId] ?: ReiAnixTraceMoeIdentificationUiState()
    val aniListViewModel: ReiAnixAniListViewModel = composeViewModel(
        factory = ReiAnixAniListViewModelFactory(LocalContext.current),
    )
    val aniListState by aniListViewModel.uiState.collectAsStateWithLifecycle()
    val repairContext = LocalContext.current
    LaunchedEffect(canonicalId, aniListState.repairRevision) {
        aniListViewModel.takeRepairNotice(canonicalId, aniListState.repairRevision)?.let { message ->
            android.widget.Toast.makeText(repairContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var showMarathonDialog by rememberSaveable(canonicalId) { mutableStateOf(false) }
    var showManualAssociation by rememberSaveable(canonicalId) { mutableStateOf(false) }
    var showTraceMoeConsent by rememberSaveable(canonicalId) { mutableStateOf(false) }
    // The Details status can remain READY while the asynchronous local-library
    // projection gains a media URI, corrected availability/duration, or a newly
    // published thumbnail. Include those source facts in a stable effect key so a
    // first request made before local media becomes resolvable is not the only one.
    val thumbnailRequestRevision = remember(state.anime) {
        state.anime?.let { anime ->
            (anime.seasons.flatMap { it.episodes } + anime.specials + anime.mediaFiles)
                .distinctBy { it.id }
                .sortedBy { it.id }
                .joinToString(separator = "|") { episode ->
                    listOf(
                        episode.id.toString(),
                        episode.media.reference.orEmpty(),
                        episode.media.uri.orEmpty(),
                        episode.media.path.orEmpty(),
                        episode.media.mediaIdentity.orEmpty(),
                        episode.media.availability.name,
                        episode.media.sourceAvailabilityState.orEmpty(),
                        episode.fileSizeBytes?.toString().orEmpty(),
                        episode.modifiedAt?.toString().orEmpty(),
                        episode.durationSeconds?.toString().orEmpty(),
                        episode.artwork?.localPath.orEmpty(),
                    ).joinToString(separator = "\u001f")
                }
        }.orEmpty()
    }

    LaunchedEffect(canonicalId, state.status) {
        if (state.status == ReiAnixDetailsLoadStatus.READY) {
            aniListViewModel.autoMatchAniList(canonicalId)
        }
    }

    LaunchedEffect(canonicalId, state.status, thumbnailRequestRevision) {
        if (state.status == ReiAnixDetailsLoadStatus.READY) {
            viewModel.requestMissingEpisodeThumbnails(canonicalId)
        }
    }

    LaunchedEffect(aniListState.associationRevision) {
        if (aniListState.associationRevision > 0L) viewModel.refreshDetails(canonicalId)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val playEpisode = rememberEpisodeLauncher(viewModel)
    val detailsEntryId = (lifecycleOwner as? NavBackStackEntry)?.id

    fun isForegroundDetails(): Boolean = isActiveDetailsDestination(
        detailsEntryId = detailsEntryId,
        currentEntryId = navController.currentBackStackEntry?.id,
        currentRoute = navController.currentDestination?.route,
    )



    ReiAnixDetailsScreen(
        state = state,
        associationRevision = aniListState.associationRevision,
        showThumbnails = showThumbnails,
        onBack = {
            if (isForegroundDetails()) navController.popBackStack()
        },
        onRetry = { viewModel.refreshDetails(canonicalId) },
        onWatch = { episodeId ->
            if (isForegroundDetails()) playEpisode(episodeId, canonicalId)
        },
        onToggleFavorite = viewModel::toggleFavorite,
        recommendationLibraryState = viewModel.uiState,
        onOpenRecommendedAnime = { recommendedId ->
            if (isForegroundDetails()) navController.navigateToDetails(recommendedId.toString(), origin)
        },
        onSetEpisodeWatched = viewModel::setEpisodeWatched,
        onSelectSource = viewModel::selectSafTree,
        onManageAniList = { aniListViewModel.repairAniListMetadata(canonicalId) },
        onManualAniList = {
            aniListViewModel.clearAniListMessage()
            aniListViewModel.searchAniList("")
            showManualAssociation = true
        },
        aniListRepairRunning = aniListState.repairRunning,
        onOpenMarathon = { showMarathonDialog = true },
        onTogglePinned = viewModel::toggleAnimePinnedNative,
        onSavePersonalNote = viewModel::saveAnimePersonalNoteNative,
        onSaveUserTags = viewModel::saveAnimeUserTagsNative,
        onSetEpisodeIdentification = viewModel::setEpisodeManualIdentificationNative,
        traceMoeState = traceMoeState,
        onIdentifyWithTraceMoe = { requestedId ->
            if (requestedId == canonicalId && isForegroundDetails()) {
                if (ReiAnixTraceMoeConsent.allowed(repairContext)) {
                    viewModel.identifyAnimeWithTraceMoe(canonicalId)
                } else {
                    showTraceMoeConsent = true
                }
            }
        },
        onCancelTraceMoe = viewModel::cancelTraceMoeIdentification,
    )
    if (showTraceMoeConsent && isForegroundDetails()) {
        TraceMoeConsentDialog(
            onAuthorized = {
                showTraceMoeConsent = false
                if (isForegroundDetails()) viewModel.identifyAnimeWithTraceMoe(canonicalId)
            },
            onDismiss = { showTraceMoeConsent = false },
        )
    }
    if (showManualAssociation) {
        AniListAssociationDialog(
            state = aniListState,
            initialQuery = state.anime?.title.orEmpty(),
            onSearch = aniListViewModel::searchAniList,
            onAssociate = { remoteId -> aniListViewModel.associateAniListManually(canonicalId, remoteId) },
            onDismiss = { showManualAssociation = false },
        )
    }
    if (showMarathonDialog) {
        ReiAnixMarathonDialog(
            viewModel = viewModel,
            animeId = canonicalId,
            currentPath = state.anime?.playbackTargetEpisode?.media?.reference,
            onDismiss = { showMarathonDialog = false },
        )
    }
}

@Composable
fun ReiAnixDetailsScreen(
    state: ReiAnixDetailsUiState,
    associationRevision: Long = 0L,
    showThumbnails: Boolean = true,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onWatch: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    recommendationLibraryState: StateFlow<ReiAnixLibraryUiState>? = null,
    onOpenRecommendedAnime: (Long) -> Unit = {},
    onSetEpisodeWatched: (Long, Boolean) -> Unit = { _, _ -> },
    onSelectSource: () -> Unit = onRetry,
    onManageAniList: () -> Unit = {},
    aniListRepairRunning: Boolean = false,
    onOpenMarathon: () -> Unit = {},
    onTogglePinned: suspend (Long) -> Boolean = { false },
    onSavePersonalNote: suspend (Long, String?) -> String? = { _, note -> note?.trim()?.takeIf { it.isNotEmpty() } },
    onSaveUserTags: suspend (Long, List<String>) -> List<String> = { _, tags -> tags },
    onSetEpisodeIdentification: suspend (Long, Int?, Double?, String, String?) -> Unit = { _, _, _, _, _ -> },
    traceMoeState: ReiAnixTraceMoeIdentificationUiState = ReiAnixTraceMoeIdentificationUiState(),
    onIdentifyWithTraceMoe: (Long) -> Unit = {},
    onCancelTraceMoe: (Long) -> Unit = {},
    onManualAniList: (() -> Unit)? = null,
) {
    ReiAnixResponsiveRoot {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(reiAnixScreenBackground())
            .then(
                if (state.status == ReiAnixDetailsLoadStatus.READY && state.anime != null) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state.status) {
            ReiAnixDetailsLoadStatus.LOADING -> ReiAnixDetailsLoadingContent()
            ReiAnixDetailsLoadStatus.READY -> {
                state.anime?.let { anime ->
                    ReiAnixDetailsReady(
                        anime = anime,
                        associationRevision = associationRevision,
                        showThumbnails = showThumbnails,
                        onBack = onBack,
                        onRefresh = onRetry,
                        onWatch = onWatch,
                        onToggleFavorite = onToggleFavorite,
                        recommendationLibraryState = recommendationLibraryState,
                        onOpenRecommendedAnime = onOpenRecommendedAnime,
                        onSetEpisodeWatched = onSetEpisodeWatched,
                        onManageAniList = onManageAniList,
                        onManualAniList = onManualAniList,
                        aniListRepairRunning = aniListRepairRunning,
                        onOpenMarathon = onOpenMarathon,
                        onTogglePinned = onTogglePinned,
                        onSavePersonalNote = onSavePersonalNote,
                        onSaveUserTags = onSaveUserTags,
                        onSetEpisodeIdentification = onSetEpisodeIdentification,
                        traceMoeState = traceMoeState,
                        onIdentifyWithTraceMoe = onIdentifyWithTraceMoe,
                        onCancelTraceMoe = onCancelTraceMoe,
                    )
                } ?: ReiAnixRecoverableErrorState(
                    title = "Detalhes indisponíveis",
                    message = "Os dados do conteúdo não estão disponíveis.",
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            ReiAnixDetailsLoadStatus.EMPTY -> ReiAnixEmptyLibraryState(
                message = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    "Configure uma pasta local para acessar os detalhes."
                } else {
                    "Nenhum anime local está disponível."
                },
                actionLabel = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    "Selecionar pasta"
                } else {
                    "Atualizar"
                },
                onAction = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    onSelectSource
                } else {
                    onRetry
                },
                modifier = Modifier.fillMaxSize(),
            )

            ReiAnixDetailsLoadStatus.SOURCE_UNAVAILABLE -> ReiAnixSourceUnavailableState(
                title = "Biblioteca local indisponível",
                message = state.error ?: if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    "Configure uma pasta local para acessar os detalhes."
                } else {
                    "A fonte local não está disponível agora."
                },
                actionLabel = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    "Selecionar pasta"
                } else {
                    "Atualizar"
                },
                onAction = if (state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)) {
                    onSelectSource
                } else {
                    onRetry
                },
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
    associationRevision: Long,
    showThumbnails: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWatch: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    recommendationLibraryState: StateFlow<ReiAnixLibraryUiState>?,
    onOpenRecommendedAnime: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit,
    onManageAniList: () -> Unit,
    onManualAniList: (() -> Unit)?,
    aniListRepairRunning: Boolean,
    onOpenMarathon: () -> Unit,
    onTogglePinned: suspend (Long) -> Boolean,
    onSavePersonalNote: suspend (Long, String?) -> String?,
    onSaveUserTags: suspend (Long, List<String>) -> List<String>,
    onSetEpisodeIdentification: suspend (Long, Int?, Double?, String, String?) -> Unit,
    traceMoeState: ReiAnixTraceMoeIdentificationUiState,
    onIdentifyWithTraceMoe: (Long) -> Unit,
    onCancelTraceMoe: (Long) -> Unit,
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

    val regularEpisodes = remember(anime.seasons) {
        anime.seasons.flatMap { it.episodes }
    }
    val currentEpisode = anime.playbackTargetEpisode
        ?.takeIf { it.consumptionState == ReiAnixConsumptionState.IN_PROGRESS }
    val nextEpisode = remember(anime.seasons, currentEpisode?.id, anime.playbackTargetEpisode?.id) {
        currentEpisode?.let { current ->
            val currentIndex = regularEpisodes.indexOfFirst { it.id == current.id }
            if (currentIndex >= 0) {
                regularEpisodes
                    .asSequence()
                    .drop(currentIndex + 1)
                    .firstOrNull { it.isPlayable }
            } else {
                null
            }
        } ?: anime.playbackTargetEpisode?.takeIf {
            it.isPlayable && !it.isCompleted
        }
    }

    val listState = rememberSaveable(
        anime.id,
        saver = LazyListState.Saver,
    ) {
        LazyListState()
    }
    val coroutineScope = rememberCoroutineScope()
    val hasSeasons = anime.seasons.isNotEmpty()
    // Hero and recommendations precede the season/episode list.
    val episodeHeadingIndex = if (hasSeasons) 3 else 2

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
            .testTag("details-episode-list")
            .reiAnixScreenFade(selectedSeasonKey),
        contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xxxl),
    ) {
        item(
            key = "details-hero:" + anime.stableKey,
            contentType = "details-hero",
        ) {
            DetailsHero(
                anime = anime,
                associationRevision = associationRevision,
                onBack = onBack,
                onRefresh = onRefresh,
                onWatch = onWatch,
                onToggleFavorite = onToggleFavorite,
                onManageAniList = onManageAniList,
                onManualAniList = onManualAniList,
                aniListRepairRunning = aniListRepairRunning,
                onOpenMarathon = onOpenMarathon,
                onTogglePinned = onTogglePinned,
                onSavePersonalNote = onSavePersonalNote,
                onSaveUserTags = onSaveUserTags,
                traceMoeState = traceMoeState,
                onIdentifyWithTraceMoe = onIdentifyWithTraceMoe,
                onCancelTraceMoe = onCancelTraceMoe,
            )
        }

        // Recommendations replace Sobre/Episódios and Informações, just below playback.
        item(
            key = "details-recommendations:" + anime.stableKey,
            contentType = "details-recommendations",
        ) {
            Box(modifier = Modifier.fillMaxWidth().testTag("details-recommendations")) {
                ReiAnixPersonalExtras(
                    animeId = anime.id,
                    libraryState = recommendationLibraryState,
                    onOpenAnime = onOpenRecommendedAnime,
                )
            }
        }

        if (hasSeasons) {
            item(
                key = "details-seasons:" + anime.stableKey,
                contentType = "details-seasons",
            ) {
                DetailsSeasonsSection(
                    anime = anime,
                    showThumbnails = showThumbnails,
                    selectedSeason = selectedSeason,
                    selectedSeasonKey = selectedSeasonKey,
                    onSeasonSelected = ::selectSeason,
                    onViewEpisodes = {
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
                    showThumbnail = showThumbnails,
                    isCurrent = episode.id == currentEpisode?.id,
                    isNext = episode.id == nextEpisode?.id,
                    onWatch = onWatch,
                    onSetEpisodeWatched = onSetEpisodeWatched,
                    onSetEpisodeIdentification = onSetEpisodeIdentification,
                    allowEpisodeIdentification = anime.mediaKind != ReiAnixMediaKind.MOVIE,
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
                    showThumbnail = showThumbnails,
                    isCurrent = episode.id == currentEpisode?.id,
                    isNext = episode.id == nextEpisode?.id,
                    onWatch = onWatch,
                    onSetEpisodeWatched = onSetEpisodeWatched,
                    onSetEpisodeIdentification = onSetEpisodeIdentification,
                    allowEpisodeIdentification = anime.mediaKind != ReiAnixMediaKind.MOVIE,
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
                    showThumbnail = showThumbnails,
                    isCurrent = episode.id == currentEpisode?.id,
                    isNext = episode.id == nextEpisode?.id,
                    onWatch = onWatch,
                    onSetEpisodeWatched = onSetEpisodeWatched,
                    onSetEpisodeIdentification = onSetEpisodeIdentification,
                    allowEpisodeIdentification = anime.mediaKind != ReiAnixMediaKind.MOVIE,
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

/** Secondary header actions; canonical pin and identification states remain owned by the caller. */
@Composable
internal fun DetailsOverflowActions(
    pinned: Boolean,
    pinEnabled: Boolean,
    traceMoeStatus: ReiAnixTraceMoeIdentificationStatus,
    onTogglePinned: () -> Unit,
    onIdentifyOrCancel: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val running = traceMoeStatus == ReiAnixTraceMoeIdentificationStatus.RUNNING
    val identifyLabel = when (traceMoeStatus) {
        ReiAnixTraceMoeIdentificationStatus.RUNNING -> "Cancelar identificação"
        ReiAnixTraceMoeIdentificationStatus.IDLE -> "Identificar capa"
        ReiAnixTraceMoeIdentificationStatus.SUCCESS -> "Identificar novamente"
        ReiAnixTraceMoeIdentificationStatus.NO_MATCH,
        ReiAnixTraceMoeIdentificationStatus.FAILED,
        ReiAnixTraceMoeIdentificationStatus.CANCELLED -> "Tentar novamente"
    }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget)
                .testTag("details-overflow"),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Mais opções",
                modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Desafixar" else "Fixar") },
                leadingIcon = {
                    Icon(
                        imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.testTag("details-pin").semantics {
                    selected = pinned
                    stateDescription = if (pinned) "Fixado" else "Não fixado"
                },
                enabled = pinEnabled,
                onClick = {
                    expanded = false
                    onTogglePinned()
                },
            )
            DropdownMenuItem(
                text = { Text(identifyLabel) },
                leadingIcon = {
                    Icon(
                        imageVector = if (running) Icons.Filled.Close else Icons.Filled.ImageSearch,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.testTag("details-tracemoe-identify-button"),
                onClick = {
                    expanded = false
                    onIdentifyOrCancel()
                },
            )
        }
    }
}

/** Independent manual lookup; the existing automatic repair remains unchanged. */
@Composable
internal fun DetailsManualAniListAction(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget).testTag("details-manual-anilist"),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = "Pesquisar e associar anime ao AniList",
            modifier = Modifier.size(ReiAnixTokens.Dimensions.iconMedium),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One tap triggers the explicit repair; no intermediate menu or dialog. */
@Composable
internal fun DetailsAniListAction(
    running: Boolean,
    onRepair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReiAnixDetailsQuickAction(
        label = if (running) "Verificando…" else "AniList",
        icon = ReiAnixDetailsShortcutIcons.AniList,
        onClick = onRepair,
        enabled = !running,
        modifier = modifier.semantics {
            stateDescription = if (running) "Verificando capa e metadados" else "Verificar capa e metadados"
        },
    )
}

@Composable
private fun DetailsHero(
    anime: ReiAnixDetailsAnimeUiModel,
    associationRevision: Long,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onWatch: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onManageAniList: () -> Unit,
    onManualAniList: (() -> Unit)?,
    aniListRepairRunning: Boolean,
    onOpenMarathon: () -> Unit,
    onTogglePinned: suspend (Long) -> Boolean,
    onSavePersonalNote: suspend (Long, String?) -> String?,
    onSaveUserTags: suspend (Long, List<String>) -> List<String>,
    traceMoeState: ReiAnixTraceMoeIdentificationUiState,
    onIdentifyWithTraceMoe: (Long) -> Unit,
    onCancelTraceMoe: (Long) -> Unit,
) {
    var pinSaving by remember(anime.id) { mutableStateOf(false) }
    var showNoteEditor by rememberSaveable(anime.id) { mutableStateOf(false) }
    var actionError by rememberSaveable(anime.id) { mutableStateOf<String?>(null) }
    var showTagsEditor by rememberSaveable(anime.id) { mutableStateOf(false) }
    var tagsDraft by rememberSaveable(anime.id) { mutableStateOf(anime.userTags.joinToString(", ")) }
    var tagsSaving by rememberSaveable(anime.id) { mutableStateOf(false) }
    var tagsError by rememberSaveable(anime.id) { mutableStateOf<String?>(null) }
    val actionScope = rememberCoroutineScope()
    val responsive = LocalReiAnixResponsiveMetrics.current
    val heroHeight = responsive.detailsHeroHeight()
    val posterOverlap = ReiAnixTokens.Dimensions.detailsHeroPosterOverlap
    val backdropPath = anime.artwork?.backdropLocalPath?.takeIf { it.isNotBlank() }
    val posterPath = anime.artwork?.localPath?.takeIf { it.isNotBlank() }
    val target = anime.playbackTargetEpisode

    val metadata = buildList {
        anime.year?.let { add(it.toString()) }
        anime.status?.trim()?.takeIf { it.isNotBlank() }?.let { add(statusLabel(it)) }
        anime.format?.trim()?.takeIf { it.isNotBlank() }?.let { add(formatLabel(it)) }
        anime.episodeCount?.takeIf { it > 0 }?.let { count ->
            add(if (count == 1) "Biblioteca: 1 episódio local" else "Biblioteca: $count episódios locais")
        }
        anime.announcedEpisodeCount?.takeIf { it > 0 }?.let { count ->
            add(if (count == 1) "AniList: 1 episódio esperado" else "AniList: $count episódios esperados")
        }
        anime.seasons.size.takeIf { it > 0 }?.let { count ->
            add(if (count == 1) "1 temporada" else "$count temporadas")
        }
        anime.score?.let { add("Nota " + formatScore(it)) }
        anime.durationMinutes?.takeIf { it > 0 }?.let { add("$it min/ep") }
    }.joinToString(" • ")

    val hasMarathonContent = anime.seasons.any { it.episodes.isNotEmpty() }

    Column(modifier = Modifier.fillMaxWidth().background(reiAnixScreenBackground())) {
        Box(modifier = Modifier.fillMaxWidth().height(heroHeight)) {
            ReiAnixBackdrop(
                localPath = backdropPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                identity = anime.stableKey + ":backdrop",
                fallbackLocalPath = posterPath,
                maxDimensionPx = 1024,
                externalUrl = anime.artwork?.backdropExternalUrl,
                fallbackExternalUrl = anime.artwork?.externalUrl,
                sourceRevisions = anime.artwork?.sourceRevisions.orEmpty(),
            )
            Box(
                modifier = Modifier.matchParentSize().background(
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
            ReiAnixTopBar(
                title = "Detalhes",
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                // A minimal scrim keeps controls readable while
                                // preserving the original brightness of the banner.
                                MaterialTheme.colorScheme.background.copy(alpha = 0.16f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                navigationContentDescription = "Voltar",
                onNavigationClick = onBack,
                actions = {
                    if (onManualAniList != null) {
                        DetailsManualAniListAction(onClick = onManualAniList)
                        Spacer(Modifier.width(8.dp))
                    }
                    DetailsOverflowActions(
                        pinned = anime.pinned,
                        pinEnabled = !pinSaving,
                        traceMoeStatus = traceMoeState.status,
                        onIdentifyOrCancel = {
                            if (traceMoeState.status == ReiAnixTraceMoeIdentificationStatus.RUNNING) {
                                onCancelTraceMoe(anime.id)
                            } else {
                                onIdentifyWithTraceMoe(anime.id)
                            }
                        },
                        onTogglePinned = {
                            if (!pinSaving) {
                                pinSaving = true
                                actionError = null
                                actionScope.launch {
                                    try {
                                        onTogglePinned(anime.id)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        actionError = failure.message?.takeIf(String::isNotBlank)
                                            ?: "Não foi possível fixar ou desafixar o anime."
                                    } finally {
                                        pinSaving = false
                                    }
                                }
                            }
                        },
                    )
                },
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = responsive.horizontalPadding)) {
            val stacked = maxWidth < ReiAnixResponsiveBreakpoints.mediumWidth
            if (stacked) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
                    ReiAnixPoster(localPath = posterPath, contentDescription = null,
                        modifier = Modifier.width(ReiAnixTokens.Dimensions.detailsHeroPosterWidth).aspectRatio(ReiAnixTokens.Dimensions.posterAspectRatio)
                            .clip(ReiAnixTokens.Shapes.artwork).offset(y = -posterOverlap),
                        identity = anime.stableKey + ":poster",
                        maxDimensionPx = 512,
                        externalUrl = anime.artwork?.externalUrl,
                        sourceRevisions = anime.artwork?.sourceRevisions.orEmpty())
                    Text(text = anime.title, style = ReiAnixTokens.TypographyTokens.heroTitle, color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    anime.preferredAlternateTitle?.takeIf { it.isNotBlank() && !it.equals(anime.title, ignoreCase = true) }?.let { alternate ->
                        Text(text = alternate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    if (metadata.isNotBlank()) Text(text = metadata, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    // Reserve only the visible part of the overlapping poster; the
                    // old fixed-height row left a phantom gap under the artwork.
                    Box(modifier = Modifier.layout { measurable, constraints ->
                        val poster = measurable.measure(constraints)
                        val overlap = posterOverlap.roundToPx().coerceIn(0, poster.height)
                        val height = (poster.height - overlap).coerceAtLeast(constraints.minHeight)
                        layout(poster.width, height) {
                            poster.placeRelative(0, -overlap)
                        }
                    }) {
                        ReiAnixPoster(localPath = posterPath, contentDescription = null,
                            modifier = Modifier.width(ReiAnixTokens.Dimensions.detailsHeroPosterWidth).aspectRatio(ReiAnixTokens.Dimensions.posterAspectRatio).clip(ReiAnixTokens.Shapes.artwork),
                            identity = anime.stableKey + ":poster",
                            maxDimensionPx = 512,
                            externalUrl = anime.artwork?.externalUrl,
                            sourceRevisions = anime.artwork?.sourceRevisions.orEmpty())
                    }
                    Spacer(modifier = Modifier.width(ReiAnixTokens.Spacing.md))
                    Column(modifier = Modifier.weight(1f).padding(top = ReiAnixTokens.Spacing.sm), verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs)) {
                        Text(text = anime.title, style = ReiAnixTokens.TypographyTokens.heroTitle, color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        anime.preferredAlternateTitle?.takeIf { it.isNotBlank() && !it.equals(anime.title, ignoreCase = true) }?.let { alternate ->
                            Text(text = alternate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (metadata.isNotBlank()) Text(text = metadata, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        presentationStateLabel(anime.presentationState)?.let { stateLabel ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = responsive.horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
            ) {
                ReiAnixBadge(
                    text = stateLabel,
                    tone = when (anime.presentationState) {
                        com.reianix.reianix_local.ui.model.ReiAnixPresentationState.ARTWORK_FAILED ->
                            ReiAnixBadgeTone.Warning
                        com.reianix.reianix_local.ui.model.ReiAnixPresentationState.METADATA_MISSING ->
                            ReiAnixBadgeTone.Warning
                        else -> ReiAnixBadgeTone.Primary
                    },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Move all five equal-width slots slightly toward the start
                // without changing their shared center-to-center distance.
                .padding(
                    start = responsive.horizontalPadding - minOf(4.dp, responsive.horizontalPadding),
                    end = responsive.horizontalPadding + minOf(4.dp, responsive.horizontalPadding),
                    top = 16.dp,
                    bottom = ReiAnixTokens.Spacing.xs,
                )
                .testTag("details-quick-actions"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DetailsAniListAction(
                running = aniListRepairRunning,
                onRepair = onManageAniList,
                modifier = Modifier.weight(1f).testTag("details-quick-anilist"),
            )
            ReiAnixDetailsQuickAction(
                label = "Favoritos",
                icon = ReiAnixDetailsShortcutIcons.Favorite,
                onClick = { onToggleFavorite(anime.id) },
                active = anime.favorite,
                modifier = Modifier.weight(1f).testTag("details-quick-favorite").semantics {
                    stateDescription = if (anime.favorite) "Favoritado" else "Não favoritado"
                },
            )
            ReiAnixDetailsQuickAction(
                label = "Nota pessoal",
                icon = ReiAnixDetailsShortcutIcons.Note,
                onClick = {
                    showNoteEditor = true
                },
                active = !anime.personalNote.isNullOrBlank(),
                modifier = Modifier.weight(1f).testTag("details-quick-note"),
            )
            ReiAnixDetailsQuickAction(
                label = "Adicionar tag",
                icon = ReiAnixDetailsShortcutIcons.Tag,
                onClick = {
                    tagsDraft = anime.userTags.joinToString(", ")
                    tagsError = null
                    showTagsEditor = true
                },
                active = anime.userTags.isNotEmpty(),
                modifier = Modifier.weight(1f).testTag("details-quick-tags"),
            )
            ReiAnixPersonalListButton(
                animeId = anime.id,
                modifier = Modifier.weight(1f).testTag("details-quick-list"),
            )
        }
        // Synopsis and character strip stay before genres and identification feedback.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = responsive.horizontalPadding)
                .testTag("details-header-metadata"),
        ) {
            anime.description?.trim()?.takeIf { it.isNotBlank() }?.let { description ->
                DetailsExpandableSynopsis(description)
            }
            ReiAnixMetadataCharacters(
                aniListId = anime.aniListId,
                associationRevision = associationRevision,
            )
        }
        if (anime.genres.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = responsive.horizontalPadding,
                        end = responsive.horizontalPadding,
                        top = ReiAnixTokens.Spacing.xl,
                        bottom = ReiAnixTokens.Spacing.sm,
                    )
                    .testTag("details-genres"),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                anime.genres.forEach { genre ->
                    Text(
                        text = genre.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val isTraceMoeRunning = traceMoeState.status == ReiAnixTraceMoeIdentificationStatus.RUNNING
        val identifyFeedback = when (traceMoeState.status) {
            ReiAnixTraceMoeIdentificationStatus.RUNNING ->
                traceMoeState.message ?: "Preparando a próxima thumbnail..."
            ReiAnixTraceMoeIdentificationStatus.SUCCESS,
            ReiAnixTraceMoeIdentificationStatus.NO_MATCH,
            ReiAnixTraceMoeIdentificationStatus.FAILED,
            ReiAnixTraceMoeIdentificationStatus.CANCELLED -> traceMoeState.message
            ReiAnixTraceMoeIdentificationStatus.IDLE -> null
        }
        if (isTraceMoeRunning || identifyFeedback != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = responsive.horizontalPadding,
                        top = ReiAnixTokens.Spacing.md,
                        end = responsive.horizontalPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                if (isTraceMoeRunning && !traceMoeState.currentThumbnailPath.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = ReiAnixTokens.Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(104.dp)
                                .aspectRatio(16f / 9f)
                                .clip(ReiAnixTokens.Shapes.small),
                        ) {
                            ReiAnixEpisodeThumbnail(
                                localPath = traceMoeState.currentThumbnailPath,
                                sourceRevisions = anime.seasons.asSequence()
                                    .flatMap { it.episodes.asSequence() }
                                    .plus(anime.specials.asSequence())
                                    .plus(anime.mediaFiles.asSequence())
                                    .firstOrNull { it.id == traceMoeState.currentEpisodeId }
                                    ?.artwork?.sourceRevisions.orEmpty(),
                                contentDescription = traceMoeState.currentEpisodeLabel ?: "Thumbnail em análise",
                                modifier = Modifier.fillMaxSize(),
                                identity = "${anime.stableKey}:trace-moe:${traceMoeState.currentEpisodeId}",
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                        ) {
                            Text(
                                text = traceMoeState.currentEpisodeLabel ?: "Thumbnail atual",
                                style = ReiAnixTokens.TypographyTokens.itemTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${traceMoeState.validResponses.coerceIn(0, 3)} de 3 respostas válidas",
                                style = ReiAnixTokens.TypographyTokens.metadata,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                identifyFeedback?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (anime.userTags.isNotEmpty()) {
            Text(
                text = "Tags: " + anime.userTags.joinToString(" • "),
                modifier = Modifier.fillMaxWidth().padding(horizontal = responsive.horizontalPadding, vertical = ReiAnixTokens.Spacing.xs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        anime.personalNote?.takeIf(String::isNotBlank)?.let { note ->
            Text(
                text = note,
                modifier = Modifier.fillMaxWidth().padding(horizontal = responsive.horizontalPadding, vertical = ReiAnixTokens.Spacing.xs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = responsive.horizontalPadding, vertical = ReiAnixTokens.Spacing.md)
                .height(IntrinsicSize.Min)
                .testTag("details-playback-actions"),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            ReiAnixPrimaryButton(
                text = if (target?.isPlayable == true) target.playbackActionLabel else "Reprodução indisponível",
                onClick = { target?.takeIf { it.isPlayable }?.let { onWatch(it.id) } },
                enabled = target?.isPlayable == true,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("details-play-action"),
                leadingIcon = Icons.Filled.PlayArrow,
            )
            ReiAnixSecondaryButton(
                text = "Maratona",
                onClick = onOpenMarathon,
                enabled = hasMarathonContent,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("details-marathon-action"),
            )
        }
        if (showTagsEditor) {
            AlertDialog(
                onDismissRequest = { if (!tagsSaving) showTagsEditor = false },
                title = { Text("Tags pessoais") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
                        OutlinedTextField(
                            value = tagsDraft,
                            onValueChange = { value -> if (value.length <= 2000) tagsDraft = value },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Tags separadas por vírgula") },
                            minLines = 2,
                            maxLines = 5,
                            enabled = !tagsSaving,
                            isError = tagsError != null,
                        )
                        tagsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    ReiAnixPrimaryButton(
                        text = if (tagsSaving) "Salvando…" else "Salvar",
                        enabled = !tagsSaving,
                        onClick = {
                            if (!tagsSaving) {
                                tagsSaving = true
                                tagsError = null
                                val tags = tagsDraft.split(Regex("[,\\n]")).map(String::trim).filter(String::isNotEmpty)
                                actionScope.launch {
                                    try {
                                        onSaveUserTags(anime.id, tags)
                                        showTagsEditor = false
                                    } catch (failure: Exception) {
                                        tagsError = failure.message?.takeIf(String::isNotBlank)
                                            ?: "Não foi possível salvar as tags."
                                    } finally {
                                        tagsSaving = false
                                    }
                                }
                            }
                        },
                    )
                },
                dismissButton = {
                    ReiAnixCompactButton(
                        text = "Cancelar",
                        onClick = { if (!tagsSaving) showTagsEditor = false },
                    )
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        if (showNoteEditor) {
            ReiAnixPersonalNoteDialog(
                animeId = anime.id,
                initialNote = anime.personalNote,
                onSaveNote = onSavePersonalNote,
                onDismiss = { showNoteEditor = false },
            )
        }
        actionError?.let { message ->
            AlertDialog(
                onDismissRequest = { actionError = null },
                title = { Text("Não foi possível concluir a ação") },
                text = { Text(message) },
                confirmButton = { ReiAnixCompactButton(text = "Fechar", onClick = { actionError = null }) },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
    ReiAnixIconActionButton(icon = icon, contentDescription = label, onClick = onClick, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = ReiAnixTokens.Colors.surfaceOverlayAlpha), CircleShape))
}

@Composable
private fun DetailsExpandableSynopsis(description: String) {
    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    var truncated by remember(description) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = ReiAnixTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        Text(
            text = "Sinopse",
            style = ReiAnixTokens.TypographyTokens.sectionTitle,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) truncated = result.hasVisualOverflow
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("details-synopsis-text")
                .then(
                    if (expanded || truncated) {
                        Modifier.clickable(role = Role.Button) { expanded = !expanded }
                            .semantics {
                                stateDescription = if (expanded) "Sinopse completa" else "Sinopse resumida"
                            }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
private fun DetailsSeasonsSection(
    anime: ReiAnixDetailsAnimeUiModel,
    showThumbnails: Boolean,
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
                style = ReiAnixTokens.TypographyTokens.sectionTitle,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (selectedSeason != null && anime.seasons.size > 1) {
                ReiAnixCompactButton(
                    text = "Ver todas  ›",
                    onClick = onViewEpisodes,
                    modifier = Modifier.semantics {
                        contentDescription = "Ver todos os episódios"
                    },
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
                    showThumbnail = showThumbnails,
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
    showThumbnail: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val preview = season.episodes.firstOrNull()?.artwork?.localPath
    val responsive = LocalReiAnixResponsiveMetrics.current
    val availableWidth = (
        responsive.maxWidth -
            responsive.horizontalPadding -
            responsive.horizontalPadding
        ).coerceAtLeast(ReiAnixTokens.Dimensions.detailsSeasonCardMinWidth)
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
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            reiAnixContentBackground(MaterialTheme.colorScheme.surface)
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
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
                if (showThumbnail) {
                    val previewExternal = season.episodes.firstOrNull()?.artwork?.externalUrl
                    ReiAnixEpisodeThumbnail(
                        localPath = preview,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        identity = season.stableKey + ":preview",
                        externalUrl = previewExternal,
                        sourceRevisions = season.episodes.firstOrNull()?.artwork?.sourceRevisions.orEmpty(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = ReiAnixTokens.TypographyTokens.itemTitle,
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
            style = ReiAnixTokens.TypographyTokens.sectionTitle,
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
    showThumbnail: Boolean,
    isCurrent: Boolean,
    isNext: Boolean,
    onWatch: (Long) -> Unit,
    onSetEpisodeWatched: (Long, Boolean) -> Unit,
    onSetEpisodeIdentification: suspend (Long, Int?, Double?, String, String?) -> Unit = { _, _, _, _, _ -> },
    allowEpisodeIdentification: Boolean = true,
) {
    var menuExpanded by remember(episode.stableKey) { mutableStateOf(false) }
    var showIdentificationEditor by rememberSaveable(episode.stableKey) { mutableStateOf(false) }
    var seasonInput by rememberSaveable(episode.stableKey) { mutableStateOf(episode.seasonNumber?.toString().orEmpty()) }
    var numberInput by rememberSaveable(episode.stableKey) { mutableStateOf(episode.number?.toString().orEmpty()) }
    var titleInput by rememberSaveable(episode.stableKey) { mutableStateOf(episode.title.orEmpty()) }
    var typeInput by rememberSaveable(episode.stableKey) { mutableStateOf(episode.episodeType?.takeIf(String::isNotBlank) ?: "regular") }
    var typeMenuExpanded by rememberSaveable(episode.stableKey) { mutableStateOf(false) }
    var identificationSaving by rememberSaveable(episode.stableKey) { mutableStateOf(false) }
    var identificationError by rememberSaveable(episode.stableKey) { mutableStateOf<String?>(null) }
    val editScope = rememberCoroutineScope()

    ReiAnixEpisodeCard(episode = episode, showThumbnail = showThumbnail, isCurrent = isCurrent, isNext = isNext,
        modifier = Modifier.padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding, vertical = ReiAnixTokens.Spacing.xs),
        onPlay = { onWatch(episode.id) }, trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics { contentDescription = "Ações do episódio " + episode.displayTitle }) {
                    Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    DropdownMenuItem(text = { Text(if (episode.isWatched) "Marcar como não visto" else "Marcar como visto") },
                        onClick = { menuExpanded = false; onSetEpisodeWatched(episode.id, !episode.isWatched) })
                    if (allowEpisodeIdentification) {
                        DropdownMenuItem(
                            text = { Text("Corrigir identificação") },
                            onClick = {
                                menuExpanded = false
                                seasonInput = episode.seasonNumber?.toString().orEmpty()
                                numberInput = episode.number?.toString().orEmpty()
                                titleInput = episode.title.orEmpty()
                                typeInput = episode.episodeType?.takeIf(String::isNotBlank) ?: "regular"
                                identificationError = null
                                showIdentificationEditor = true
                            },
                        )
                    }
                }
            }
        })
    if (showIdentificationEditor) {
        AlertDialog(
            onDismissRequest = { if (!identificationSaving) showIdentificationEditor = false },
            title = { Text("Corrigir identificação") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
                    OutlinedTextField(
                        value = seasonInput,
                        onValueChange = { seasonInput = it },
                        label = { Text("Temporada") },
                        singleLine = true,
                        enabled = !identificationSaving,
                    )
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = { numberInput = it },
                        label = { Text("Número do episódio") },
                        singleLine = true,
                        enabled = !identificationSaving,
                    )
                    Box {
                        ReiAnixSecondaryButton(
                            text = "Tipo: $typeInput",
                            onClick = { typeMenuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = typeMenuExpanded,
                            onDismissRequest = { typeMenuExpanded = false },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            listOf("regular", "special", "ova", "oad", "ona", "extra", "movie", "unknown").forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(value) },
                                    onClick = { typeInput = value; typeMenuExpanded = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Título do episódio") },
                        singleLine = true,
                        enabled = !identificationSaving,
                    )
                    identificationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                ReiAnixPrimaryButton(
                    text = if (identificationSaving) "Salvando…" else "Salvar",
                    enabled = !identificationSaving,
                    onClick = {
                        if (!identificationSaving) {
                            val seasonValue = if (seasonInput.isBlank()) null else seasonInput.trim().toIntOrNull()
                            val numberValue = if (numberInput.isBlank()) null else numberInput.trim().toDoubleOrNull()
                            when {
                                seasonInput.isNotBlank() && (seasonValue == null || seasonValue < 0) ->
                                    identificationError = "Temporada inválida."
                                numberInput.isNotBlank() && (numberValue == null || !numberValue.isFinite() || numberValue < 0.0) ->
                                    identificationError = "Número do episódio inválido."
                                else -> {
                                    identificationSaving = true
                                    identificationError = null
                                    editScope.launch {
                                        try {
                                            onSetEpisodeIdentification(
                                                episode.id,
                                                seasonValue,
                                                numberValue,
                                                typeInput,
                                                titleInput.trim().ifBlank { null },
                                            )
                                            showIdentificationEditor = false
                                        } catch (failure: Exception) {
                                            identificationError = failure.message?.takeIf(String::isNotBlank)
                                                ?: "Não foi possível salvar a identificação."
                                        } finally {
                                            identificationSaving = false
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            },
            dismissButton = {
                ReiAnixCompactButton(
                    text = "Cancelar",
                    onClick = { if (!identificationSaving) showIdentificationEditor = false },
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
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

@Composable
internal fun AniListAssociationDialog(
    state: ReiAnixAniListUiState,
    initialQuery: String,
    onSearch: (String) -> Unit,
    onAssociate: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    val saving = state.isManualAssociationLoading
    val searching = state.isSearchLoading
    val selection = state.results.firstOrNull { it.id == selectedId }
        ?.takeIf { state.query == query.trim() }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Associar ao AniList") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; selectedId = null },
                    label = { Text("Título do anime") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReiAnixCompactButton(
                        text = "Pesquisar",
                        enabled = query.isNotBlank() && !saving && !searching,
                        onClick = { selectedId = null; onSearch(query) },
                    )
                    if (searching || saving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.lastAssociationMessage == "Associação AniList salva.") {
                    Text(state.lastAssociationMessage, color = MaterialTheme.colorScheme.primary)
                }
                if (!searching && state.query == query.trim() && state.query.isNotBlank() &&
                    state.results.isEmpty() && state.error == null) Text("Nenhum resultado encontrado.")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.query == query.trim()) {
                        items(state.results, key = { it.id }) { anime ->
                            AniListCandidateRow(anime, selectedId == anime.id, !saving && !searching) {
                                selectedId = anime.id
                            }
                        }
                    }
                }
                if (selection != null) {
                    Text("A capa e os metadados serão atualizados. Os episódios locais serão preservados.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            ReiAnixPrimaryButton(
                text = if (saving) "Associando…" else "Associar anime",
                enabled = selection != null && !saving && !searching,
                onClick = { selection?.let { onAssociate(it.id) } },
            )
        },
        dismissButton = {
            ReiAnixSecondaryButton(text = "Fechar", enabled = !saving, onClick = onDismiss)
        },
    )
}

@Composable
private fun AniListCandidateRow(
    anime: AniListAnime,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else reiAnixContentBackground(MaterialTheme.colorScheme.surfaceContainer),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = ReiAnixTokens.Shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReiAnixPoster(
                localPath = null,
                contentDescription = null,
                modifier = Modifier.width(48.dp).aspectRatio(2f / 3f),
                identity = "manual-anilist:" + anime.id,
                maxDimensionPx = 192,
                externalUrl = anime.coverLargeUrl ?: anime.coverExtraLargeUrl,
                fallbackExternalUrl = anime.coverExtraLargeUrl,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(anime.preferredTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(anime.seasonYear?.toString(), anime.format, anime.episodes?.let { "$it episódios" })
                if (facts.isNotEmpty()) Text(facts.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                Text("AniList ID: ${anime.id}", style = MaterialTheme.typography.labelSmall)
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
