package com.reiflix.reiflix_local.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reiflix.reiflix_local.data.library.ReiAnixGachaService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixCatalogFilters
import com.reiflix.reiflix_local.ui.artwork.ReiAnixPoster
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.CancellationException

private val REIANIX_GACHA_STATES = listOf(
    "Todos",
    "Favoritos",
    "Fixados",
    "Não assistidos",
    "Em andamento",
    "Concluídos",
    "Assistidos",
)

private val REIANIX_GACHA_SORTS = listOf(
    "Mais recentes",
    "Assistidos recentemente",
    "Progresso",
    "Episódio",
    "Temporada + episódio",
    "Modificação",
    "Duração",
    "Tamanho",
    "Favoritos primeiro",
    "Fixados primeiro",
    "Nome A-Z",
    "Nome Z-A",
)

private val REIANIX_GACHA_MEDIA_TYPES =
    listOf("Todos", "Série/Anime", "Filme", "Especial", "Episódio")
private val REIANIX_GACHA_AVAILABILITY =
    listOf("Todos", "Disponível", "Com missing", "Sem missing")
private val REIANIX_GACHA_METADATA = listOf("Todos", "Disponível", "Ausente")
private val REIANIX_GACHA_ARTWORK = listOf("Todos", "Disponível", "Ausente")

/**
 * Native, read-only Home Gacha dialog. Every draw delegates to the existing
 * ViewModel/domain service and canonical SQLite repository. This dialog owns
 * only transient filter and presentation state; it does not persist catalog
 * data, create an artwork cache, or write playback progress.
 */
@Composable
fun ReiAnixGachaDialog(
    viewModel: ReiAnixLibraryViewModel,
    libraryState: ReiAnixLibraryUiState,
    onDismiss: () -> Unit,
    onOpenAnime: (Long) -> Unit,
) {
    val animes = libraryState.animes
    val genres = remember(animes) {
        animes.asSequence()
            .flatMap { it.genres.asSequence() }
            .map { it.name.trim() }
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }
            .sortedBy(String::lowercase)
            .toList()
    }
    val tags = remember(animes) {
        animes.asSequence()
            .flatMap { it.userTags.asSequence() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }
            .sortedBy(String::lowercase)
            .toList()
    }
    val seasons = remember(animes) {
        animes.asSequence()
            .flatMap { it.contentEpisodes.asSequence() }
            .mapNotNull { it.seasonNumber }
            .distinct()
            .sorted()
            .map(Int::toString)
            .toList()
    }
    val episodeTypes = remember(animes) {
        animes.asSequence()
            .flatMap { it.contentEpisodes.asSequence() }
            .mapNotNull { it.episodeType?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase() }
            .sortedBy(String::lowercase)
            .toList()
    }

    var query by rememberSaveable { mutableStateOf("") }
    var selectedState by rememberSaveable { mutableStateOf("Todos") }
    var selectedGenre by rememberSaveable { mutableStateOf("Todos") }
    var selectedSort by rememberSaveable { mutableStateOf("Mais recentes") }
    var selectedTag by rememberSaveable { mutableStateOf("Todos") }
    var selectedMediaType by rememberSaveable { mutableStateOf("Todos") }
    var selectedSeason by rememberSaveable { mutableStateOf("Todos") }
    var selectedEpisodeType by rememberSaveable { mutableStateOf("Todos") }
    var selectedAvailability by rememberSaveable { mutableStateOf("Todos") }
    var selectedMetadata by rememberSaveable { mutableStateOf("Todos") }
    var selectedArtwork by rememberSaveable { mutableStateOf("Todos") }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var includeEpisode by rememberSaveable { mutableStateOf(false) }
    var drawRequest by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<ReiAnixGachaService.Result?>(null) }
    var lastDrawnAnimeId by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var noMatch by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val filters = ReiAnixCatalogFilters(
        query = query,
        state = selectedState,
        genre = selectedGenre,
        sort = selectedSort,
        tag = selectedTag,
        mediaType = selectedMediaType,
        season = selectedSeason,
        episodeType = selectedEpisodeType,
        availability = selectedAvailability,
        metadata = selectedMetadata,
        artwork = selectedArtwork,
    )
    val activeFilterCount = listOf(
        query.isNotBlank(),
        selectedState != "Todos",
        selectedGenre != "Todos",
        selectedSort != "Mais recentes",
        selectedTag != "Todos",
        selectedMediaType != "Todos",
        selectedSeason != "Todos",
        selectedEpisodeType != "Todos",
        selectedAvailability != "Todos",
        selectedMetadata != "Todos",
        selectedArtwork != "Todos",
    ).count { it }

    LaunchedEffect(drawRequest) {
        loading = true
        noMatch = false
        error = null
        result = null
        try {
            val drawn = viewModel.drawNativeGacha(
                filters = filters,
                excludeAnimeId = lastDrawnAnimeId,
                includeEpisode = includeEpisode,
            )
            result = drawn
            if (drawn != null) lastDrawnAnimeId = drawn.anime.id
            noMatch = drawn == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.localizedMessage
                ?.takeIf(String::isNotBlank)
                ?.let { "Não foi possível sortear do acervo: " + it }
                ?: "Não foi possível consultar o acervo. Tente novamente."
        } finally {
            loading = false
        }
    }

    val selected = result
    val projectedAnime = selected?.let { draw ->
        animes.firstOrNull { it.id == draw.anime.id }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎲 Gacha do acervo") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Sorteando do acervo local…", textAlign = TextAlign.Center)
                    }
                    error != null -> {
                        Text(
                            text = error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { drawRequest++ }) {
                            Text("Tentar novamente")
                        }
                    }
                    noMatch -> Text(
                        text = "Não há itens que correspondam aos filtros atuais.",
                        textAlign = TextAlign.Center,
                    )
                    selected != null -> {
                        Text(
                            "Resultado local",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ReiAnixPoster(
                            localPath = projectedAnime?.artwork?.localPath,
                            externalUrl = projectedAnime?.artwork?.externalUrl,
                            contentDescription = "Capa de " + selected.anime.title,
                            identity = "gacha-anime:" + selected.anime.id,
                            modifier = Modifier.size(width = 140.dp, height = 210.dp),
                        )
                        Text(
                            text = selected.anime.title.trim()
                                .ifEmpty { selected.anime.lookupTitle },
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (selected.episode != null) {
                            val episode = selected.episode
                            Text(
                                text = "T" + episode.season + " • E" + formatGachaEpisodeNumber(episode.number),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = episode.episodeTitle?.takeIf(String::isNotBlank)
                                    ?: episode.fileName,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            selected.durationLabel?.let { duration ->
                                Text(
                                    duration,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else if (selected.episodeDrawRequested) {
                            Text(
                                "Este item não possui episódios regulares disponíveis para sorteio.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "O sorteio não altera consumo, progresso ou histórico.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            includeEpisode = true
                            drawRequest++
                        },
                        enabled = !loading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sortear episódio") }
                    TextButton(
                        onClick = { drawRequest++ },
                        enabled = !loading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sortear novamente") }
                }

                TextButton(
                    onClick = { filtersExpanded = !filtersExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (filtersExpanded) "Ocultar filtros (" + activeFilterCount + ")"
                        else "Filtros do sorteio (" + activeFilterCount + ")",
                    )
                }

                if (filtersExpanded) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Pesquisa") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(2.dp))
                    GachaFilterChoice("Estado", selectedState, REIANIX_GACHA_STATES) {
                        selectedState = it
                    }
                    GachaFilterChoice("Gênero", selectedGenre, listOf("Todos") + genres) {
                        selectedGenre = it
                    }
                    GachaFilterChoice("Ordenação", selectedSort, REIANIX_GACHA_SORTS) {
                        selectedSort = it
                    }
                    GachaFilterChoice(
                        "Etiqueta",
                        selectedTag,
                        listOf("Todos", "Sem etiqueta") + tags,
                    ) { selectedTag = it }
                    GachaFilterChoice("Tipo de mídia", selectedMediaType, REIANIX_GACHA_MEDIA_TYPES) {
                        selectedMediaType = it
                    }
                    GachaFilterChoice("Temporada", selectedSeason, listOf("Todos") + seasons) {
                        selectedSeason = it
                    }
                    GachaFilterChoice(
                        "Tipo de episódio",
                        selectedEpisodeType,
                        listOf("Todos") + episodeTypes,
                    ) { selectedEpisodeType = it }
                    GachaFilterChoice(
                        "Disponibilidade",
                        selectedAvailability,
                        REIANIX_GACHA_AVAILABILITY,
                    ) { selectedAvailability = it }
                    GachaFilterChoice("Metadata", selectedMetadata, REIANIX_GACHA_METADATA) {
                        selectedMetadata = it
                    }
                    GachaFilterChoice("Capa", selectedArtwork, REIANIX_GACHA_ARTWORK) {
                        selectedArtwork = it
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                query = ""
                                selectedState = "Todos"
                                selectedGenre = "Todos"
                                selectedSort = "Mais recentes"
                                selectedTag = "Todos"
                                selectedMediaType = "Todos"
                                selectedSeason = "Todos"
                                selectedEpisodeType = "Todos"
                                selectedAvailability = "Todos"
                                selectedMetadata = "Todos"
                                selectedArtwork = "Todos"
                                includeEpisode = false
                                drawRequest++
                            },
                            enabled = !loading,
                        ) { Text("Limpar filtros") }
                        Button(
                            onClick = { drawRequest++ },
                            enabled = !loading,
                        ) { Text("Aplicar e sortear") }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selected?.let { draw -> onOpenAnime(draw.anime.id) }
                },
                enabled = selected != null && !loading,
            ) { Text("Abrir detalhes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}

@Composable
private fun GachaFilterChoice(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        selected,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("▾", style = MaterialTheme.typography.titleMedium)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

private fun formatGachaEpisodeNumber(number: Double?): String {
    if (number == null || !number.isFinite()) return "—"
    return number.toLong().takeIf { it.toDouble() == number }?.toString() ?: number.toString()
}
