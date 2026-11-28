package com.reianix.reianix_local.ui.details

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import com.reianix.reianix_local.ui.ReiAnixPrimaryButton
import com.reianix.reianix_local.ui.artwork.ReiAnixPoster
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reianix.reianix_local.ReiAnixAppDependencies
import kotlinx.coroutines.CancellationException
import com.reianix.reianix_local.data.anilist.AniListResult
import com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter
import com.reianix.reianix_local.data.personal.ReiAnixLocalRecommendation
import com.reianix.reianix_local.data.personal.ReiAnixPersonalAnimeService
import com.reianix.reianix_local.data.personal.ReiAnixWatchStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reianix.reianix_local.ui.model.ReiAnixArtworkUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLibraryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

private const val DETAILS_RECOMMENDATION_LIMIT = 12

/** A Details back-stack entry retains this ViewModel while its player is on top.
 * In contrast, remember() inside the LazyColumn item is discarded when Details
 * leaves composition, which used to replace the suggestions with a loading label
 * and re-query SQLite for 1–2 seconds every time playback was closed.
 */
internal class ReiAnixRecommendationListViewModel : ViewModel() {
    private var loadedAnimeId by mutableStateOf<Long?>(null)
    private var loadJob: Job? = null

    private var recommendations by mutableStateOf<List<ReiAnixLocalRecommendation>?>(null)
    private var failed by mutableStateOf(false)

    fun recommendationsFor(animeId: Long): List<ReiAnixLocalRecommendation>? =
        recommendations.takeIf { loadedAnimeId == animeId }

    fun failedFor(animeId: Long): Boolean = loadedAnimeId == animeId && failed

    fun ensureLoaded(animeId: Long, service: ReiAnixPersonalAnimeService) {
        if (!shouldLoadRecommendations(
                loadedAnimeId = loadedAnimeId,
                requestedAnimeId = animeId,
                hasResult = recommendations != null,
                isLoading = loadJob?.isActive == true,
            )
        ) return

        loadJob?.cancel()
        if (loadedAnimeId != animeId) {
            recommendations = null
        }
        loadedAnimeId = animeId
        failed = false
        loadJob = viewModelScope.launch {
            try {
                val result = service.recommendLocal(animeId, limit = DETAILS_RECOMMENDATION_LIMIT)
                if (loadedAnimeId == animeId) {
                    recommendations = result
                    failed = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A transient SQLite problem must not erase covers already visible.
                if (loadedAnimeId == animeId && recommendations == null) failed = true
            }
        }
    }
}

internal fun shouldLoadRecommendations(
    loadedAnimeId: Long?,
    requestedAnimeId: Long,
    hasResult: Boolean,
    isLoading: Boolean,
): Boolean = loadedAnimeId != requestedAnimeId || (!hasResult && !isLoading)

/** Automatically loaded only in Details, with existing local covers. */
@Composable
fun ReiAnixPersonalExtras(
    animeId: Long,
    libraryState: StateFlow<ReiAnixLibraryUiState>? = null,
    onOpenAnime: (Long) -> Unit,
) {
    val context = LocalContext.current
    val service = remember(context) { ReiAnixAppDependencies.from(context).personalAnimeService }
    val cachedSuggestions = remember(service, animeId) {
        service.cachedRecommendations(animeId, limit = DETAILS_RECOMMENDATION_LIMIT)
    }
    // Scoped to the Details NavBackStackEntry, not to this disposable LazyColumn item.
    val recommendationState: ReiAnixRecommendationListViewModel = viewModel()
    var showAll by remember(animeId) { mutableStateOf(false) }

    LaunchedEffect(animeId, service) {
        recommendationState.ensureLoaded(animeId, service)
    }

    val suggestions = recommendationState.recommendationsFor(animeId) ?: cachedSuggestions
    val posterIds = remember(suggestions) { suggestions.orEmpty().map { it.animeId }.toSet() }
    val artworkById = if (libraryState != null && posterIds.isNotEmpty()) {
        val initialArtwork = remember(libraryState, posterIds) {
            recommendationArtwork(libraryState.value, posterIds)
        }
        val artworkFlow = remember(libraryState, posterIds) {
            libraryState.map { recommendationArtwork(it, posterIds) }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
        }
        key(posterIds) {
            artworkFlow.collectAsStateWithLifecycle(initialValue = initialArtwork).value
        }
    } else {
        emptyMap()
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.more_recommendations), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (!suggestions.isNullOrEmpty()) {
                IconButton(onClick = { showAll = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.profile_all_suggestions))
                }
            }
        }
        when {
            suggestions == null && recommendationState.failedFor(animeId) -> Text(stringResource(R.string.more_suggestions_error), style = MaterialTheme.typography.bodySmall)
            suggestions == null -> Text(stringResource(R.string.more_suggestions_loading), style = MaterialTheme.typography.bodySmall)
            suggestions.isEmpty() -> Text(stringResource(R.string.more_suggestions_insufficient), style = MaterialTheme.typography.bodySmall)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(suggestions.take(6), key = { it.animeId }) { suggestion ->
                    RecommendationPoster(suggestion, artworkById[suggestion.animeId]) { onOpenAnime(suggestion.animeId) }
                }
            }
        }
    }
    if (showAll && !suggestions.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { showAll = false },
            title = { Text(stringResource(R.string.more_suggestions)) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(92.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(suggestions, key = { it.animeId }) { suggestion ->
                        RecommendationPoster(suggestion, artworkById[suggestion.animeId], Modifier.fillMaxWidth()) {
                            showAll = false
                            onOpenAnime(suggestion.animeId)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAll = false }) { Text(stringResource(R.string.ui_close)) } },
        )
    }
}

private fun recommendationArtwork(
    state: ReiAnixLibraryUiState,
    posterIds: Set<Long>,
): Map<Long, ReiAnixArtworkUiModel> = buildMap {
    for (anime in state.animes) {
        if (anime.id in posterIds) anime.artwork?.let { put(anime.id, it) }
    }
}

@Composable
private fun RecommendationPoster(
    suggestion: ReiAnixLocalRecommendation,
    artwork: ReiAnixArtworkUiModel?,
    modifier: Modifier = Modifier.width(108.dp),
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReiAnixPoster(
            localPath = artwork?.localPath?.takeIf { it.isNotBlank() } ?: suggestion.posterPath,
            fallbackLocalPath = suggestion.posterPath,
            externalUrl = artwork?.externalUrl,
            sourceRevisions = artwork?.sourceRevisions.orEmpty(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            identity = "anime:" + suggestion.animeId + ":poster",
            maxDimensionPx = 320,
        )
        Text(suggestion.title, style = MaterialTheme.typography.bodySmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Written note and optional numerical rating share one editor; existing stores remain intact. */
@Composable
internal fun ReiAnixPersonalNoteDialog(
    animeId: Long,
    initialNote: String?,
    onSaveNote: suspend (Long, String?) -> String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val service = remember(dependencies) {
        ReiAnixPersonalAnimeService(dependencies.sqliteSettingsRepository, dependencies.sqliteLibraryRepository)
    }
    val scope = rememberCoroutineScope()
    var note by rememberSaveable(animeId) { mutableStateOf(initialNote.orEmpty()) }
    var rating by rememberSaveable(animeId) { mutableStateOf<Int?>(null) }
    var originalRating by rememberSaveable(animeId) { mutableStateOf<Int?>(null) }
    var loaded by remember(animeId) { mutableStateOf(false) }
    var saving by remember(animeId) { mutableStateOf(false) }
    var error by remember(animeId) { mutableStateOf<String?>(null) }

    LaunchedEffect(animeId) {
        try {
            val state = service.load(animeId)
            originalRating = state.ratingTenths
            rating = state.ratingTenths
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = context.getString(R.string.profile_rating_load_error)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.ui_personal_note)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 2000) note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.more_private_note)) },
                    minLines = 3,
                    maxLines = 5,
                    enabled = !saving,
                    supportingText = {
                        Text(note.length.toString() + "/2000")
                    },
                )
                HorizontalDivider()
                Text(stringResource(R.string.more_my_rating), style = MaterialTheme.typography.titleSmall)
                Text(rating?.let { String.format(Locale.forLanguageTag("pt-BR"), "%.1f / 10", it / 10.0) }
                    ?: stringResource(R.string.profile_no_rating), color = MaterialTheme.colorScheme.primary)
                Slider(
                    value = (rating ?: 0).toFloat(),
                    onValueChange = { rating = (it / 5f).roundToInt().times(5).coerceIn(0, 100) },
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = loaded && !saving,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0", style = MaterialTheme.typography.labelSmall)
                    Text("5", style = MaterialTheme.typography.labelSmall)
                    Text("10", style = MaterialTheme.typography.labelSmall)
                }
                if (rating != null) {
                    TextButton(onClick = { rating = null }, enabled = loaded && !saving) {
                        Text(stringResource(R.string.more_remove_rating))
                    }
                } else {
                    TextButton(onClick = { rating = 0 }, enabled = loaded && !saving) {
                        Text(stringResource(R.string.more_rate_zero))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            ReiAnixPrimaryButton(
                text = if (saving) stringResource(R.string.flow_saving) else stringResource(R.string.pref_save),
                enabled = !saving && loaded,
                onClick = {
                    if (!saving && loaded) {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                if (note.trim() != initialNote.orEmpty().trim()) {
                                    onSaveNote(animeId, note.trim().takeIf { it.isNotEmpty() })
                                }
                                if (rating != originalRating) {
                                    service.setRating(animeId, rating)
                                    originalRating = rating
                                }
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = context.getString(R.string.profile_rating_save_error)
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.locale_cancel)) }
                if (note.isNotBlank()) {
                    TextButton(onClick = { note = "" }, enabled = !saving) { Text(stringResource(R.string.more_clear_text)) }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}


/**
 * Five consistent outline silhouettes based on the Details shortcut reference.
 * Each vector shares a 24-unit viewport, round caps/joins and a 2-unit stroke.
 */
internal object ReiAnixDetailsShortcutIcons {
    private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw)
        }.build()

    // Diagonal link fills the same optical area as the heart, note and tag.
    // Use the shared 24 dp viewport and rounded 2-unit stroke, not Material's
    // shorter horizontal chain silhouette.
    val AniList = outline("DetailsAniListLink") {
        moveTo(10f, 13f)
        curveTo(11.9f, 15.5f, 15.3f, 15.7f, 17.5f, 13.5f)
        lineTo(20.5f, 10.5f)
        curveTo(22.5f, 8.5f, 22.5f, 5.5f, 20.5f, 3.5f)
        curveTo(18.5f, 1.5f, 15.5f, 1.5f, 13.5f, 3.5f)
        lineTo(11.8f, 5.2f)
        moveTo(14f, 11f)
        curveTo(12.1f, 8.5f, 8.7f, 8.3f, 6.5f, 10.5f)
        lineTo(3.5f, 13.5f)
        curveTo(1.5f, 15.5f, 1.5f, 18.5f, 3.5f, 20.5f)
        curveTo(5.5f, 22.5f, 8.5f, 22.5f, 10.5f, 20.5f)
        lineTo(12.2f, 18.8f)
    }

    val Favorite = outline("DetailsHeart") {
        moveTo(20.8f, 4.6f)
        curveTo(18.7f, 2.4f, 15.2f, 2.4f, 13.0f, 4.6f)
        lineTo(12f, 5.7f)
        lineTo(10.9f, 4.6f)
        curveTo(8.8f, 2.4f, 5.3f, 2.4f, 3.2f, 4.6f)
        curveTo(1f, 6.8f, 1f, 10.3f, 3.2f, 12.5f)
        lineTo(12f, 21f)
        lineTo(20.8f, 12.5f)
        curveTo(23f, 10.3f, 23f, 6.8f, 20.8f, 4.6f)
        close()
    }
    val Note = outline("DetailsNotebookPen") {
        moveTo(12f, 21f)
        lineTo(6f, 21f)
        curveTo(4.9f, 21f, 4f, 20.1f, 4f, 19f)
        lineTo(4f, 5f)
        curveTo(4f, 3.9f, 4.9f, 3f, 6f, 3f)
        lineTo(17f, 3f)
        curveTo(18.1f, 3f, 19f, 3.9f, 19f, 5f)
        lineTo(19f, 9f)
        moveTo(8f, 2f); lineTo(8f, 5f)
        moveTo(12f, 2f); lineTo(12f, 5f)
        moveTo(16f, 2f); lineTo(16f, 5f)
        moveTo(8f, 9f); lineTo(15f, 9f)
        moveTo(8f, 13f); lineTo(12f, 13f)
        moveTo(14f, 17.5f)
        lineTo(19.5f, 12f)
        curveTo(20.1f, 11.4f, 21f, 11.4f, 21.6f, 12f)
        lineTo(22f, 12.4f)
        curveTo(22.6f, 13f, 22.6f, 13.9f, 22f, 14.5f)
        lineTo(16.5f, 20f)
        lineTo(13f, 21f)
        close()
    }
    val Tag = outline("DetailsTag") {
        moveTo(3f, 3f)
        lineTo(10.5f, 3f)
        curveTo(11f, 3f, 11.5f, 3.3f, 12f, 3.8f)
        lineTo(21f, 12.8f)
        curveTo(21.8f, 13.6f, 21.8f, 14.9f, 21f, 15.7f)
        lineTo(15.7f, 21f)
        curveTo(14.9f, 21.8f, 13.6f, 21.8f, 12.8f, 21f)
        lineTo(3.8f, 12f)
        curveTo(3.3f, 11.5f, 3f, 11f, 3f, 10.5f)
        lineTo(3f, 3f)
        close()
        moveTo(9.5f, 8f)
        curveTo(9.5f, 10f, 6.5f, 10f, 6.5f, 8f)
        curveTo(6.5f, 6f, 9.5f, 6f, 9.5f, 8f)
        close()
    }
    val Bookmark = outline("DetailsBookmark") {
        moveTo(6f, 3f)
        lineTo(18f, 3f)
        curveTo(19.1f, 3f, 20f, 3.9f, 20f, 5f)
        lineTo(20f, 21f)
        lineTo(12f, 16f)
        lineTo(4f, 21f)
        lineTo(4f, 5f)
        curveTo(4f, 3.9f, 4.9f, 3f, 6f, 3f)
        close()
    }
}

/** Borderless, evenly sized header action shared by the five Details shortcuts. */
@Composable
internal fun ReiAnixDetailsQuickAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** List shortcut in the header, backed by the original local watch-state service. */
@Composable
fun ReiAnixPersonalListButton(animeId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val service = remember(dependencies) {
        ReiAnixPersonalAnimeService(
            dependencies.sqliteSettingsRepository,
            dependencies.sqliteLibraryRepository,
        )
    }
    val scope = rememberCoroutineScope()
    var selected by remember(animeId) { mutableStateOf<ReiAnixWatchStatus?>(null) }
    var loading by remember(animeId) { mutableStateOf(true) }
    var saving by remember(animeId) { mutableStateOf(false) }
    var expanded by remember(animeId) { mutableStateOf(false) }

    LaunchedEffect(animeId) {
        loading = true
        try {
            selected = service.load(animeId).status
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unavailable preference must not interfere with the normal Details hero.
        } finally {
            loading = false
        }
    }

    fun save(value: ReiAnixWatchStatus?) {
        if (loading || saving) return
        expanded = false
        saving = true
        scope.launch {
            try {
                selected = service.setStatus(animeId, value).status
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.profile_list_save_error), Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    Box(modifier = modifier.semantics {
        stateDescription = selected?.label ?: context.getString(R.string.profile_list_outside)
    }) {
        ReiAnixDetailsQuickAction(
            label = stringResource(R.string.more_add_list),
            icon = ReiAnixDetailsShortcutIcons.Bookmark,
            onClick = { expanded = true },
            enabled = !loading && !saving,
            active = selected != null,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ReiAnixWatchStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(if (selected == status) "✓ " + status.label else status.label) },
                    onClick = { save(status) },
                )
            }
            if (selected != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.more_remove_list)) },
                    onClick = { save(null) },
                )
            }
        }
    }
}

/**
 * Display characters directly after the synopsis with other metadata.
 * This is an independent, lazily composed Details-only effect, never part of
 * library scanning, cover discovery or the existing AniList metadata client mutex.
 */
@Composable
fun ReiAnixMetadataCharacters(
    aniListId: Int?,
    associationRevision: Long,
) {
    if (aniListId == null) return

    val context = LocalContext.current
    val charactersRepository = remember(context) { ReiAnixAppDependencies.from(context).characterCache }
    var characters by remember(charactersRepository, aniListId) {
        mutableStateOf<List<ReiAnixAnimeCharacter>>(charactersRepository.peek(aniListId).orEmpty())
    }

    LaunchedEffect(charactersRepository, aniListId, associationRevision) {
        try {
            when (val result = charactersRepository.load(aniListId)) {
                is AniListResult.Success -> characters = result.value
                is AniListResult.Failure -> Unit // do not impact Details when AniList is unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // This metadata enrichment is optional, never blocks the synopsis.
        }
    }

    if (characters.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // Synopsis already has 4.dp bottom padding; another 16.dp here
            // matches the 20.dp gap between characters and genres.
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            items(characters, key = { it.id }) { character ->
                Column(
                    modifier = Modifier.width(85.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AsyncImage(
                        model = character.imageUrl,
                        contentDescription = "Personagem: " + character.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(70.dp).clip(CircleShape),
                    )
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = character.role,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
