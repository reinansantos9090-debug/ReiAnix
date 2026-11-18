package com.reianix.reianix_local.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reianix.reianix_local.ReiAnixAppDependencies
import com.reianix.reianix_local.data.anilist.AniListResult
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
 * Ratings/status use two local preference rows; characters and recommendations
 * load only after an explicit tap. No new cover/metadata search is triggered.
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
    val charactersRepository = remember(dependencies) {
        ReiAnixCharacterCache(context, dependencies.aniListClient)
    }
    val scope = rememberCoroutineScope()
    var personal by remember(animeId) { mutableStateOf(ReiAnixPersonalAnimeState()) }
    var ratingSlider by remember(animeId) { mutableFloatStateOf(0f) }
    var isLoaded by remember(animeId) { mutableStateOf(false) }
    var saving by remember(animeId) { mutableStateOf(false) }
    var statusMenu by remember(animeId) { mutableStateOf(false) }
    var error by remember(animeId) { mutableStateOf<String?>(null) }
    var characters by remember(animeId) { mutableStateOf<List<ReiAnixAnimeCharacter>?>(null) }
    var charactersBusy by remember(animeId) { mutableStateOf(false) }
    var recommendations by remember(animeId) { mutableStateOf<List<ReiAnixLocalRecommendation>?>(null) }
    var recommendationsBusy by remember(animeId) { mutableStateOf(false) }

    LaunchedEffect(animeId) {
        runCatching { service.load(animeId) }.onSuccess {
            personal = it
            ratingSlider = (it.ratingTenths ?: 0).toFloat()
        }.onFailure { error = "Não foi possível ler suas preferências." }
        isLoaded = true
    }

    fun saveStatus(value: ReiAnixWatchStatus?) {
        if (saving) return
        saving = true
        scope.launch {
            try { personal = service.setStatus(animeId, value); error = null }
            catch (_: Exception) { error = "Não foi possível salvar a lista." }
            finally { saving = false }
        }
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
        Text("Listas e avaliações armazenadas somente neste dispositivo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                OutlinedButton(onClick = { statusMenu = true }, enabled = isLoaded && !saving) {
                    Text(personal.status?.label ?: "Adicionar à lista")
                }
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    ReiAnixWatchStatus.entries.forEach { status ->
                        DropdownMenuItem(text = { Text(status.label) }, onClick = {
                            statusMenu = false
                            saveStatus(status)
                        })
                    }
                    DropdownMenuItem(text = { Text("Remover da lista") }, onClick = {
                        statusMenu = false
                        saveStatus(null)
                    })
                }
            }
        }
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

        Text("Personagens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = {
                if (charactersBusy) return@OutlinedButton
                charactersBusy = true
                error = null
                scope.launch {
                    try {
                        val aniListId = dependencies.sqliteLibraryRepository.getAnime(animeId)
                            ?.anilistId?.toInt()?.takeIf { it > 0 }
                        characters = if (aniListId == null) {
                            error = "Este anime ainda não possui um ID confirmado no AniList."
                            emptyList()
                        } else when (val result = charactersRepository.load(aniListId)) {
                            is AniListResult.Success -> result.value
                            is AniListResult.Failure -> {
                                error = "Não foi possível carregar os personagens. Tente novamente."
                                null
                            }
                        }
                    } catch (_: Exception) {
                        error = "Não foi possível carregar os personagens."
                    } finally { charactersBusy = false }
                }
            },
            enabled = !charactersBusy,
        ) {
            Text(if (charactersBusy) "Carregando…" else "Ver personagens")
        }
        characters?.let { loaded ->
            if (loaded.isEmpty()) {
                Text("Nenhum personagem disponível.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(loaded, key = { it.id }) { character ->
                        Card(modifier = Modifier.width(112.dp)) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AsyncImage(
                                    model = character.imageUrl,
                                    contentDescription = character.name,
                                    modifier = Modifier.fillMaxWidth().height(132.dp),
                                )
                                Text(character.name, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(character.role, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

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
