package com.reianix.reianix_local.ui.details

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.reianix.reianix_local.data.personal.ReiAnixPersonalAnimeState
import com.reianix.reianix_local.data.personal.ReiAnixWatchStatus
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Supplementary content is absent from the initial library projection.
 * Ratings stay locally persisted; recommendations load only after an explicit tap.
 * Character imagery is rendered with other anime metadata, not in this panel.
 */
@Composable
fun ReiAnixPersonalExtras(
    animeId: Long,
    onOpenAnime: (Long) -> Unit,
) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val service = remember(dependencies) {
        ReiAnixPersonalAnimeService(
            dependencies.sqliteSettingsRepository,
            dependencies.sqliteLibraryRepository,
        )
    }
    val scope = rememberCoroutineScope()
    var personal by remember(animeId) { mutableStateOf(ReiAnixPersonalAnimeState()) }
    var ratingSlider by remember(animeId) { mutableFloatStateOf(0f) }
    var isLoaded by remember(animeId) { mutableStateOf(false) }
    var saving by remember(animeId) { mutableStateOf(false) }
    var error by remember(animeId) { mutableStateOf<String?>(null) }
    var recommendations by remember(animeId) { mutableStateOf<List<ReiAnixLocalRecommendation>?>(null) }
    var recommendationsBusy by remember(animeId) { mutableStateOf(false) }

    LaunchedEffect(animeId) {
        runCatching { service.load(animeId) }.onSuccess {
            personal = it
            ratingSlider = (it.ratingTenths ?: 0).toFloat()
        }.onFailure { error = "Não foi possível ler suas preferências." }
        isLoaded = true
    }

    fun saveRating(value: Int?) {
        if (saving) return
        saving = true
        scope.launch {
            try { personal = service.setRating(animeId, value); error = null }
            catch (_: Exception) { error = "Não foi possível salvar sua nota." }
            finally { saving = false }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text("Minha experiência", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Avaliações pessoais armazenadas somente neste dispositivo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Minha nota: " + (personal.ratingTenths?.let {
            String.format(Locale.forLanguageTag("pt-BR"), "%.1f / 10", it / 10.0)
        } ?: "Sem avaliação"))
        Slider(
            value = ratingSlider,
            onValueChange = { ratingSlider = it },
            onValueChangeFinished = {
                saveRating(((ratingSlider / 5f).toInt() * 5).coerceIn(0, 100))
            },
            valueRange = 0f..100f,
            steps = 19,
            enabled = isLoaded && !saving,
        )
        OutlinedButton(onClick = {
            ratingSlider = 0f
            saveRating(null)
        }, enabled = isLoaded && !saving && personal.ratingTenths != null) {
            Text("Remover minha nota")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()

        Text("Recomendações para você", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Sugestões da sua biblioteca, calculadas localmente por gêneros e favoritos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            enabled = !recommendationsBusy,
            onClick = {
                recommendationsBusy = true
                scope.launch {
                    try {
                        recommendations = service.recommendLocal(animeId)
                        error = null
                    } catch (_: Exception) {
                        error = "Não foi possível calcular recomendações."
                    } finally { recommendationsBusy = false }
                }
            },
        ) { Text(if (recommendationsBusy) "Analisando…" else "Ver recomendações") }
        recommendations?.let { local ->
            if (local.isEmpty()) Text("Sem sugestões suficientes na biblioteca por enquanto.")
            local.forEach { suggestion ->
                OutlinedButton(onClick = { onOpenAnime(suggestion.animeId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(suggestion.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
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
