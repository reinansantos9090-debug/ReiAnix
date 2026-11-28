package com.reianix.reianix_local.ui.collections

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.ui.motion.reiAnixScreenFade
import com.reianix.reianix_local.ui.theme.reiAnixContentBackground

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
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
    val openAnime: (Long) -> Unit = { animeId ->
        navController.navigateToDetails(
            animeId = animeId.toString(),
            origin = ReiAnixRoutes.COLLECTIONS,
        )
    }
    ReiAnixCollectionsScreen(
        viewModel = viewModel,
        animes = catalog.animes,
        onOpenAnime = openAnime,
        onDismiss = { navController.popBackStack() },
        onAddFolder = { navController.navigate(ReiAnixRoutes.STORAGE) { launchSingleTop = true } },
    )
}

@Composable
fun ReiAnixCollectionsScreen(
    viewModel: ReiAnixLibraryViewModel,
    animes: List<ReiAnixAnimeUiModel>,
    onOpenAnime: (Long) -> Unit,
    onDismiss: () -> Unit,
    onOpenTracking: () -> Unit = {},
    onAddFolder: () -> Unit = viewModel::selectSafTree,
) {
    val repository = viewModel.personalCollectionsRepository
    val scope = rememberCoroutineScope()
    var collections by remember(repository) { mutableStateOf<List<ReiAnixPersonalCollection>>(emptyList()) }
    var loading by remember(repository) { mutableStateOf(true) }
    var busy by remember(repository) { mutableStateOf(false) }
    // Per-row optimistic membership: never disable or repaint the entire list for a one-row save.
    var pendingMembership by remember(repository) { mutableStateOf<Map<Pair<String, Long>, Boolean>>(emptyMap()) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var retryToken by remember(repository) { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNameId by rememberSaveable { mutableStateOf<String?>(null) }
    var showNameEditor by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
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

    fun toggleMember(collectionId: String, animeId: Long) {
        val key = collectionId to animeId
        if (loading || busy || pendingMembership.containsKey(key)) return
        val current = collections.firstOrNull { it.id == collectionId } ?: return
        // Display the intended checkbox state immediately, without a global loading frame.
        pendingMembership = pendingMembership + (key to (animeId !in current.animeIds))
        scope.launch {
            try {
                val saved = repository.toggleAnime(collectionId, animeId)
                collections = saved
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Removing the optimistic state restores the last confirmed checkbox.
                error = failure.message ?: "Não foi possível salvar o anime na coleção."
            } finally {
                pendingMembership = pendingMembership - key
            }
        }
    }

    val goBack: () -> Unit = {
        when {
            showNameEditor -> {
                if (!busy) {
                    showNameEditor = false
                    error = null
                }
            }
            adding -> { adding = false; search = "" }
            selectedId != null -> { selectedId = null; search = "" }
            else -> onDismiss()
        }
    }
    BackHandler(enabled = showNameEditor || selectedId != null || adding) { goBack() }

    if (showNameEditor) {
        ReiAnixResponsiveRoot {
            CollectionNameScreen(
                name = nameDraft,
                onNameChange = { nameDraft = it.take(64) },
                editing = editingNameId != null,
                busy = busy || loading,
                error = error,
                onBack = goBack,
                onSave = {
                    val name = nameDraft
                    val id = editingNameId
                    var createdId: String? = null
                    applyEdit(
                        change = {
                            if (id == null) {
                                val created = repository.create(name)
                                createdId = created.id
                                repository.load()
                            } else repository.rename(id, name)
                        },
                        onSaved = {
                            showNameEditor = false
                            createdId?.let { newId ->
                                selectedId = newId
                                adding = true
                                search = ""
                            }
                        },
                    )
                },
            )
        }
        return
    }

    ReiAnixResponsiveRoot {
        Column(
            modifier = Modifier.fillMaxSize().background(reiAnixScreenBackground()).reiAnixScreenFade(selectedId),
        ) {
            ReiAnixTopBar(
                title = if (selected == null) "Coleções" else selected.name,
                subtitle = if (selected == null) "Organize seus animes" else "${selected.animeIds.size} anime(s)",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = goBack,
                actions = {
                    if (selected != null) {
                        CollectionOptionsMenu(
                            collectionId = selected.id,
                            enabled = !busy && !loading && pendingMembership.isEmpty(),
                            onRename = {
                                editingNameId = selected.id
                                nameDraft = selected.name
                                error = null
                                showNameEditor = true
                            },
                            onDelete = { pendingDeleteId = selected.id },
                        )
                    }
                },
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(
                    horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                ).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loading) Text(stringResource(R.string.more_collections_loading))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (selected == null) {
                    if (!loading && error != null) {
                        TextButton(onClick = { retryToken++ }) { Text(stringResource(R.string.ui_retry)) }
                    }
                    val createCollection = {
                        editingNameId = null
                        nameDraft = ""
                        error = null
                        showNameEditor = true
                    }
                    if (collections.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 480.dp)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.FolderCopy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(64.dp),
                                )
                                Text(stringResource(R.string.more_collections_yours), style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    stringResource(R.string.more_collections_organize),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(4.dp))
                                CollectionsPrimaryActions(
                                    enabled = !busy && !loading && error == null,
                                    onCreate = createCollection,
                                    onAddFolder = onAddFolder,
                                )
                            }
                        }
                    } else {
                        val collectionsById = remember(collections) { collections.associateBy { it.id } }
                        val collectionIds = remember(collections) { collections.map { it.id } }
                        val collectionShape = RoundedCornerShape(18.dp)
                        ReiAnixReorderableCollectionList(
                            listKey = "personal-collections",
                            itemIds = collectionIds,
                            busy = busy || loading || pendingMembership.isNotEmpty(),
                            onReorder = { order -> applyEdit { repository.reorderCollections(order) } },
                            rowShape = collectionShape,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            rowSpacing = 12.dp,
                            contentPadding = PaddingValues(bottom = 24.dp),
                            leadingContent = {
                                item(key = "collection-actions") {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                                            CollectionsPrimaryActions(
                                                enabled = !busy && !loading && error == null,
                                                onCreate = createCollection,
                                                onAddFolder = onAddFolder,
                                            )
                                        }
                                    }
                                }
                                item(key = "collection-heading") {
                                    Text(
                                        stringResource(R.string.more_collections_yours),
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }
                            },
                        ) { collectionId, dragging, actionsDisabled ->
                            val collection = collectionsById[collectionId] ?: return@ReiAnixReorderableCollectionList
                            val cardColor = collectionDragColor(
                                reiAnixContentBackground(MaterialTheme.colorScheme.surface),
                                dragging,
                            )
                            val cover = collection.effectiveCoverId?.let(animeById::get)
                            Surface(
                                onClick = {
                                    selectedId = collection.id
                                    adding = false
                                    search = ""
                                },
                                enabled = !actionsDisabled,
                                modifier = Modifier.fillMaxWidth(),
                                shape = collectionShape,
                                color = cardColor,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    if (cover != null) {
                                        CollectionPoster(cover, modifier = Modifier.size(52.dp, 74.dp))
                                    } else {
                                        Icon(
                                            Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(52.dp, 74.dp),
                                        )
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(collection.name, style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${collection.animeIds.size} anime(s)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { adding = !adding; search = "" },
                            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.78f)
                                .heightIn(min = 48.dp),
                        ) {
                            Icon(
                                if (adding) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                if (adding) "Voltar aos animes" else "Adicionar animes",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    if (adding) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it.take(80) },
                            label = { Text(stringResource(R.string.more_collections_search)) },
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
                                val included = pendingMembership[selected.id to anime.id]
                                    ?: (anime.id in selected.animeIds)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = !busy,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            toggleMember(selected.id, anime.id)
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
                                            toggleMember(selected.id, anime.id)
                                        },
                                        enabled = !busy,
                                    )
                                }
                            }
                        }
                    } else {
                        if (selected.animeIds.isEmpty()) {
                            Text(stringResource(R.string.more_collection_empty))
                        } else {
                            ReiAnixCollectionReorderList(
                                collection = selected,
                                animeById = animeById,
                                busy = busy || pendingMembership.isNotEmpty(),
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

    if (pendingDeleteId != null) {
        val id = pendingDeleteId!!
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDeleteId = null },
            title = { Text(stringResource(R.string.more_collection_delete)) },
            text = { Text(stringResource(R.string.more_collection_delete_desc)) },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        applyEdit(
                            change = { repository.delete(id) },
                            onSaved = { selectedId = null; pendingDeleteId = null },
                        )
                    },
                ) { Text(stringResource(R.string.ui_delete)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.locale_cancel)) }
            },
        )
    }
}

@Composable
internal fun CollectionOptionsMenu(
    collectionId: String,
    enabled: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(collectionId) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Opções da coleção")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.more_rename)) },
                enabled = enabled,
                onClick = { expanded = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_delete)) },
                enabled = enabled,
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
internal fun CollectionsPrimaryActions(
    enabled: Boolean,
    onCreate: () -> Unit,
    onAddFolder: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CollectionActionCard(
            title = stringResource(R.string.more_collection_new),
            description = stringResource(R.string.more_collection_group),
            icon = Icons.Outlined.CreateNewFolder,
            enabled = enabled,
            onClick = onCreate,
        )
        CollectionActionCard(
            title = stringResource(R.string.extra_storage_add),
            description = stringResource(R.string.more_collection_import),
            icon = Icons.Outlined.FolderOpen,
            enabled = true,
            onClick = onAddFolder,
        )
    }
}

@Composable
private fun CollectionActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = reiAnixContentBackground(MaterialTheme.colorScheme.surface),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CollectionNameScreen(
    name: String,
    onNameChange: (String) -> Unit,
    editing: Boolean,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val canSave = !busy && name.isNotBlank()
    Column(
        modifier = Modifier.fillMaxSize()
            .background(reiAnixScreenBackground())
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ReiAnixTopBar(
            title = if (editing) "Renomear coleção" else "Nova coleção",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavigationClick = onBack,
        )
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Icon(
                    Icons.Outlined.CreateNewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    if (editing) "Renomeie sua coleção" else "Crie sua coleção",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (editing) "Escolha um novo nome." else "Organize seus animes em um só lugar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { onNameChange(it.take(64)) },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.more_collection_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onSave() }),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    enabled = canSave,
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(if (editing) "Salvar nome" else "Criar coleção",
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
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
                    stringResource(R.string.more_roulette_hint),
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
                            DropdownMenuItem(text = { Text(stringResource(R.string.more_all_m)) }, onClick = {
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
                        DropdownMenuItem(text = { Text(stringResource(R.string.more_all_f)) }, onClick = {
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
                        stringResource(R.string.more_unwatched_only),
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
                                Button(onClick = { onWatch(target.id, selectedAnime.id) }) { Text(stringResource(R.string.more_watch)) }
                            }
                            TextButton(onClick = { onOpenAnime(selectedAnime.id) }) { Text(stringResource(R.string.more_view_details)) }
                        }
                    }
                } else if (candidates.isEmpty()) {
                    Text(stringResource(R.string.more_roulette_no_match))
                } else {
                    Text(stringResource(R.string.more_roulette_start))
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
            Text(stringResource(R.string.more_roulette), style = MaterialTheme.typography.titleMedium)
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
            title = { Text(stringResource(R.string.more_roulette)) },
            text = rouletteBody,
            confirmButton = {
                Button(
                    enabled = candidates.isNotEmpty(),
                    onClick = onSort,
                ) { Text(if (selectedAnime == null) "Sortear" else "Sortear novamente") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}
