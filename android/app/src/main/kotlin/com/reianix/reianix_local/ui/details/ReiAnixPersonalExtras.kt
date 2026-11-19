package com.reianix.reianix_local.ui.details

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.reianix.reianix_local.data.anilist.ReiAnixAniListClient
import com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter
import com.reianix.reianix_local.data.personal.ReiAnixCharacterCache
import com.reianix.reianix_local.data.personal.ReiAnixLocalRecommendation
import com.reianix.reianix_local.data.personal.ReiAnixPersonalAnimeService
import com.reianix.reianix_local.data.personal.ReiAnixWatchStatus
import kotlinx.coroutines.launch
import java.util.Locale

/** Automatically loaded only in Details, with existing local covers. */
@Composable
fun ReiAnixPersonalExtras(
    animeId: Long,
    onOpenAnime: (Long) -> Unit,
) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val service = remember(dependencies) {
        ReiAnixPersonalAnimeService(dependencies.sqliteSettingsRepository, dependencies.sqliteLibraryRepository)
    }
    var recommendations by remember(animeId) { mutableStateOf<List<ReiAnixLocalRecommendation>?>(null) }
    var failed by remember(animeId) { mutableStateOf(false) }
    var showAll by remember(animeId) { mutableStateOf(false) }

    LaunchedEffect(animeId) {
        try {
            recommendations = service.recommendLocal(animeId, limit = 12)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    val suggestions = recommendations
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Recomendações para você", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (!suggestions.isNullOrEmpty()) {
                IconButton(onClick = { showAll = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Todas as sugestões")
                }
            }
        }
        when {
            failed -> Text("Não foi possível carregar as sugestões.", style = MaterialTheme.typography.bodySmall)
            suggestions == null -> Text("Carregando sugestões…", style = MaterialTheme.typography.bodySmall)
            suggestions.isEmpty() -> Text("Sem sugestões suficientes na biblioteca.", style = MaterialTheme.typography.bodySmall)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(suggestions.take(6), key = { it.animeId }) { suggestion ->
                    RecommendationPoster(suggestion) { onOpenAnime(suggestion.animeId) }
                }
            }
        }
    }
    if (showAll && !suggestions.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { showAll = false },
            title = { Text("Sugestões") },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(92.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(suggestions, key = { it.animeId }) { suggestion ->
                        RecommendationPoster(suggestion, Modifier.fillMaxWidth()) {
                            showAll = false
                            onOpenAnime(suggestion.animeId)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAll = false }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun RecommendationPoster(
    suggestion: ReiAnixLocalRecommendation,
    modifier: Modifier = Modifier.width(108.dp),
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReiAnixPoster(
            localPath = suggestion.posterPath,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            identity = "recommendation:" + suggestion.animeId,
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
            error = "Não foi possível ler a avaliação salva."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Nota pessoal") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 2000) note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nota privada") },
                    minLines = 3,
                    maxLines = 5,
                    enabled = !saving,
                    supportingText = {
                        Text(note.length.toString() + "/2000")
                    },
                )
                HorizontalDivider()
                Text("Minha avaliação", style = MaterialTheme.typography.titleSmall)
                Text(rating?.let { String.format(Locale.forLanguageTag("pt-BR"), "%.1f / 10", it / 10.0) }
                    ?: "Sem avaliação", color = MaterialTheme.colorScheme.primary)
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
                        Text("Remover avaliação")
                    }
                } else {
                    TextButton(onClick = { rating = 0 }, enabled = loaded && !saving) {
                        Text("Avaliar com 0")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            ReiAnixPrimaryButton(
                text = if (saving) "Salvando…" else "Salvar",
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
                                error = "Não foi possível concluir o salvamento. Tente novamente."
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
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancelar") }
                if (note.isNotBlank()) {
                    TextButton(onClick = { note = "" }, enabled = !saving) { Text("Apagar texto") }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}


/**
 * Four consistent outline silhouettes based on the Details shortcut reference.
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

/** Borderless, evenly sized header action shared by the four Details shortcuts. */
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
                Toast.makeText(context, "Não foi possível atualizar sua lista.", Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    Box(modifier = modifier.semantics {
        stateDescription = selected?.label ?: "Fora das listas de acompanhamento"
    }) {
        ReiAnixDetailsQuickAction(
            label = "Adicionar à lista",
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
                    text = { Text("Remover da lista") },
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
    animeId: Long,
    metadataRevision: String,
) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val charactersRepository = remember(dependencies) {
        ReiAnixCharacterCache(context, ReiAnixAniListClient())
    }
    var characters by remember(animeId) { mutableStateOf<List<ReiAnixAnimeCharacter>>(emptyList()) }

    LaunchedEffect(animeId, metadataRevision) {
        try {
            val associatedId = dependencies.sqliteLibraryRepository.getAnime(animeId)?.anilistId
                ?.takeIf { it > 0 && it <= Int.MAX_VALUE.toLong() }?.toInt()
            if (associatedId != null) {
                when (val result = charactersRepository.load(associatedId)) {
                    is AniListResult.Success -> characters = result.value
                    is AniListResult.Failure -> Unit // do not impact Details when AniList is unavailable
                }
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
            modifier = Modifier.fillMaxWidth(),
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
