package com.reianix.reianix_local.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.navigation.navigateToDetails
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot
import com.reianix.reianix_local.data.collections.ReiAnixPersonalCollection
import com.reianix.reianix_local.data.collections.ReiAnixPersonalCollectionsRepository
import com.reianix.reianix_local.ui.artwork.ReiAnixPoster
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Collections live in the existing SQLite preferences table; this view only
 * uses the already-materialized canonical catalog and its local posters.
 */
/** Dedicated navigation destination: collections replace the Library content, not a modal. */
@Composable
fun ReiAnixCollectionsRoute(
    navController: NavHostController,
    viewModel: ReiAnixLibraryViewModel,
) {
    val catalog by viewModel.uiState.collectAsStateWithLifecycle()
    ReiAnixCollectionsScreen(
        viewModel = viewModel,
        animes = catalog.animes,
        onOpenAnime = { animeId ->
            navController.navigateToDetails(
                animeId = animeId.toString(),
                origin = ReiAnixRoutes.COLLECTIONS,
            )
        },
        onDismiss = { navController.popBackStack() },
    )
}

@Composable
fun ReiAnixCollectionsScreen(
    viewModel: ReiAnixLibraryViewModel,
    animes: List<ReiAnixAnimeUiModel>,
    onOpenAnime: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val repository = viewModel.personalCollectionsRepository
    val scope = rememberCoroutineScope()
    var collections by remember(repository) { mutableStateOf<List<ReiAnixPersonalCollection>>(emptyList()) }
    var loading by remember(repository) { mutableStateOf(true) }
    var busy by remember(repository) { mutableStateOf(false) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var retryToken by remember(repository) { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNameId by remember { mutableStateOf<String?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val animeById = remember(animes) { animes.associateBy(ReiAnixAnimeUiModel::id) }
    val selected = collections.firstOrNull { it.id == selectedId }

    LaunchedEffect(repository, retryToken) {
        loading = true
        error = null
        try {
            collections = repository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Falha ao carregar coleções."
        } finally {
            loading = false
        }
    }

    fun applyEdit(
        onSaved: () -> Unit = {},
        change: suspend () -> List<ReiAnixPersonalCollection>,
    ) {
        if (busy || loading) return
        busy = true
        scope.launch {
            try {
                val updated = change()
                collections = updated
                error = null
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Não foi possível salvar a coleção."
            } finally {
                busy = false
            }
        }
    }

    val goBack: () -> Unit = {
        when {
            adding -> { adding = false; search = "" }
            selectedId != null -> { selectedId = null; search = "" }
            else -> onDismiss()
        }
    }
    BackHandler(enabled = selected != null || adding) { goBack() }

    ReiAnixResponsiveRoot {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            ReiAnixTopBar(
                title = if (selected == null) "Coleções" else selected.name,
                subtitle = if (selected == null) "Organize seus animes" else "${selected.animeIds.size} anime(s)",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = goBack,
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(
                    horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                ).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loading) Text("Carregando coleções…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (selected == null) {
                    Button(
                        onClick = { editingNameId = null; nameDraft = ""; showNameDialog = true },
                        enabled = !busy && !loading,
                    ) { Text("Nova coleção") }
                    if (!loading && error != null) {
                        TextButton(onClick = { retryToken++ }) { Text("Tentar novamente") }
                    }
                    if (!loading && collections.isEmpty() && error == null) {
                        Text("Crie sua primeira coleção, como Obras-primas ou Quero reassistir.")
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(collections, key = { it.id }) { collection ->
                            val cover = collection.effectiveCoverId?.let(animeById::get)
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedId = collection.id
                                    adding = false
                                    search = ""
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CollectionPoster(cover, modifier = Modifier.size(44.dp, 62.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(collection.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${collection.animeIds.size} anime(s)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                editingNameId = selected.id
                                nameDraft = selected.name
                                showNameDialog = true
                            },
                        ) { Text("Renomear") }
                        TextButton(
                            enabled = !busy,
                            onClick = { pendingDeleteId = selected.id },
                        ) { Text("Excluir") }
                    }
                    Text(
                        "Segure um anime e arraste para mudar sua posição. A capa da coleção é a do anime escolhido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { adding = !adding; search = "" },
                    ) { Text(if (adding) "Voltar aos animes" else "Adicionar animes") }
                    if (adding) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it.take(80) },
                            label = { Text("Buscar na biblioteca") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val available = remember(animes, search) {
                            animes.filter { it.title.contains(search.trim(), ignoreCase = true) }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            items(available, key = { it.id }) { anime ->
                                val included = anime.id in selected.animeIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !busy) {
                                            applyEdit { repository.toggleAnime(selected.id, anime.id) }
                                        }
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CollectionPoster(anime, modifier = Modifier.size(38.dp, 54.dp))
                                    Text(
                                        anime.title,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Checkbox(
                                        checked = included,
                                        onCheckedChange = {
                                            applyEdit { repository.toggleAnime(selected.id, anime.id) }
                                        },
                                        enabled = !busy,
                                    )
                                }
                            }
                        }
                    } else {
                        if (selected.animeIds.isEmpty()) {
                            Text("Esta coleção está vazia. Toque em Adicionar animes.")
                        } else {
                            ReiAnixCollectionReorderList(
                                collection = selected,
                                animeById = animeById,
                                busy = busy,
                                modifier = Modifier.weight(1f),
                                onOpenAnime = onOpenAnime,
                                onSetCover = { id ->
                                    applyEdit { repository.setCover(selected.id, id) }
                                },
                                onRemove = { id ->
                                    applyEdit { repository.toggleAnime(selected.id, id) }
                                },
                                onReorder = { order ->
                                    applyEdit { repository.reorderAnimes(selected.id, order) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showNameDialog = false },
            title = { Text(if (editingNameId == null) "Nova coleção" else "Renomear coleção") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(64) },
                    label = { Text("Nome da coleção") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy && nameDraft.isNotBlank(),
                    onClick = {
                        val name = nameDraft
                        val id = editingNameId
                        var createdId: String? = null
                        applyEdit(
                            change = {
                                if (id == null) {
                                    createdId = repository.create(name).id
                                    repository.load()
                                } else repository.rename(id, name)
                            },
                            onSaved = {
                                showNameDialog = false
                                if (createdId != null) selectedId = createdId
                            },
                        )
                    },
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showNameDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (pendingDeleteId != null) {
        val id = pendingDeleteId!!
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDeleteId = null },
            title = { Text("Excluir coleção?") },
            text = { Text("A coleção será apagada, mas nenhum vídeo ou anime será excluído.") },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        applyEdit(
                            change = { repository.delete(id) },
                            onSaved = { selectedId = null; pendingDeleteId = null },
                        )
                    },
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingDeleteId = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
internal fun CollectionPoster(anime: ReiAnixAnimeUiModel?, modifier: Modifier = Modifier) {
    ReiAnixPoster(
        localPath = anime?.artwork?.localPath,
        contentDescription = anime?.title?.let { "Capa de $it" },
        identity = anime?.stableKey,
        sourceRevisions = anime?.artwork?.sourceRevisions.orEmpty(),
        maxDimensionPx = 160,
        externalUrl = null,
        modifier = modifier,
    )
}

/** Previous modal entry point kept for existing consumers and previews. */
@Composable
fun ReiAnixRouletteDialog(
    viewModel: ReiAnixLibraryViewModel,
    animes: List<ReiAnixAnimeUiModel>,
    onWatch: (Long, Long) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ReiAnixRouletteContent(
        viewModel = viewModel,
        animes = animes,
        onWatch = onWatch,
        onOpenAnime = onOpenAnime,
        onDismiss = onDismiss,
        embedded = false,
    )
}

/** Inline roulette on the full-screen Timeline; no AlertDialog overlay. */
@Composable
fun ReiAnixRouletteTab(
    viewModel: ReiAnixLibraryViewModel,
    animes: List<ReiAnixAnimeUiModel>,
    onWatch: (Long, Long) -> Unit,
    onOpenAnime: (Long) -> Unit,
) {
    ReiAnixRouletteContent(
        viewModel = viewModel,
        animes = animes,
        onWatch = onWatch,
        onOpenAnime = onOpenAnime,
        onDismiss = {},
        embedded = true,
    )
}

/** Roulette uses the already-loaded canonical catalog and current collections. */
@Composable
private fun ReiAnixRouletteContent(
    viewModel: ReiAnixLibraryViewModel,
    animes: List<ReiAnixAnimeUiModel>,
    onWatch: (Long, Long) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onDismiss: () -> Unit,
    embedded: Boolean,
) {
    val repository = viewModel.personalCollectionsRepository
    var collections by remember(repository) { mutableStateOf<List<ReiAnixPersonalCollection>>(emptyList()) }
    var collectionsError by remember(repository) { mutableStateOf<String?>(null) }
    var genre by remember { mutableStateOf<String?>(null) }
    var genreExpanded by remember { mutableStateOf(false) }
    var collectionId by remember { mutableStateOf<String?>(null) }
    var collectionExpanded by remember { mutableStateOf(false) }
    var onlyUnwatched by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(repository) {
        try {
            collections = repository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            collectionsError = failure.message ?: "Não foi possível carregar coleções."
        }
    }
    val genres = remember(animes) {
        animes.flatMap { anime -> anime.genres.map { it.name } }
            .filter(String::isNotBlank)
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val candidates = remember(animes, genre, collectionId, onlyUnwatched, collections) {
        val selectedCollection = collectionId?.let { id -> collections.firstOrNull { it.id == id } }
        val allowedIds = selectedCollection?.animeIds?.toHashSet()
        animes.filter { anime ->
            anime.availableContentCount > 0 &&
                anime.contentEpisodes.any { it.isPlayable } &&
                (genre == null || anime.genres.any { it.name.equals(genre, ignoreCase = true) }) &&
                (!onlyUnwatched || !anime.hasWatchedEpisode) &&
                (collectionId == null || (allowedIds != null && anime.id in allowedIds))
        }
    }
    val selectedAnime = candidates.firstOrNull { it.id == selectedId }

    val rouletteBody: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Não sabe o que assistir? Sorteie um anime da sua biblioteca.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        TextButton(onClick = { genreExpanded = true }) {
                            Text("Gênero: ${genre ?: "Todos"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(
                            expanded = genreExpanded,
                            onDismissRequest = { genreExpanded = false },
                        ) {
                            DropdownMenuItem(text = { Text("Todos") }, onClick = {
                                genre = null; selectedId = null; genreExpanded = false
                            })
                            genres.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    genre = option; selectedId = null; genreExpanded = false
                                })
                            }
                        }
                    }
                }
                Column {
                    TextButton(onClick = { collectionExpanded = true }) {
                        Text(
                            "Coleção: ${collections.firstOrNull { it.id == collectionId }?.name ?: "Todas"}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = collectionExpanded,
                        onDismissRequest = { collectionExpanded = false },
                    ) {
                        DropdownMenuItem(text = { Text("Todas") }, onClick = {
                            collectionId = null; selectedId = null; collectionExpanded = false
                        })
                        collections.forEach { collection ->
                            DropdownMenuItem(text = { Text(collection.name) }, onClick = {
                                collectionId = collection.id; selectedId = null; collectionExpanded = false
                            })
                        }
                    }
                }
                collectionsError?.let {
                    Text("Coleções indisponíveis: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = onlyUnwatched,
                        onCheckedChange = { onlyUnwatched = it; selectedId = null },
                    )
                    Text(
                        "Só animes não assistidos",
                        modifier = Modifier.clickable { onlyUnwatched = !onlyUnwatched; selectedId = null },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "${candidates.size} anime(s) disponíveis para sorteio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selectedAnime != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CollectionPoster(selectedAnime, modifier = Modifier.size(76.dp, 106.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selectedAnime.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val target = selectedAnime.contentEpisodes.firstOrNull {
                                it.id == selectedAnime.playbackTargetEpisodeId && it.isPlayable
                            } ?: selectedAnime.contentEpisodes.firstOrNull { it.isPlayable }
                            if (target != null) {
                                Button(onClick = { onWatch(target.id, selectedAnime.id) }) { Text("Assistir") }
                            }
                            TextButton(onClick = { onOpenAnime(selectedAnime.id) }) { Text("Ver detalhes") }
                        }
                    }
                } else if (candidates.isEmpty()) {
                    Text("Nenhum anime corresponde aos filtros escolhidos.")
                } else {
                    Text("Toque em Sortear para escolher um anime.")
                }
            }
        }
    val onSort: () -> Unit = {
        val pool = if (candidates.size > 1) candidates.filterNot { it.id == selectedId }
        else candidates
        if (pool.isNotEmpty()) selectedId = pool[Random.nextInt(pool.size)].id
    }
    if (embedded) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Roleta de animes", style = MaterialTheme.typography.titleMedium)
            rouletteBody()
            Button(
                enabled = candidates.isNotEmpty(),
                onClick = onSort,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (selectedAnime == null) "Sortear" else "Sortear novamente") }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Roleta de animes") },
            text = rouletteBody,
            confirmButton = {
                Button(
                    enabled = candidates.isNotEmpty(),
                    onClick = onSort,
                ) { Text(if (selectedAnime == null) "Sortear" else "Sortear novamente") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}
