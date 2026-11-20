package com.reianix.reianix_local.ui.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.reianix.reianix_local.ReiAnixAppDependencies
import com.reianix.reianix_local.data.personal.ReiAnixWatchStatus
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.theme.ReiAnixResponsiveRoot
import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground
import com.reianix.reianix_local.ui.theme.reiAnixContentBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class TrackingAnime(
    val id: Long, val title: String, val coverCache: String?, val coverUrl: String?,
    val status: ReiAnixWatchStatus, val available: Int, val completed: Int,
)

/** Read-only categories edited on the anime details screen. */
@Composable
fun ReiAnixWatchStatusCollections(
    animes: List<ReiAnixAnimeUiModel>,
    onOpenAnime: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val settings = dependencies.sqliteSettingsRepository
    val library = dependencies.sqliteLibraryRepository
    var tracked by remember { mutableStateOf<List<TrackingAnime>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    // Keep this read independent of library artwork changes and scroll events.
    LaunchedEffect(settings, library, retry) {
        loading = true
        error = null
        try {
            tracked = withContext(Dispatchers.IO) {
                val saved = settings.allPreferences().mapNotNull { (key, value) ->
                    if (!key.startsWith("reianix.personal.status.")) return@mapNotNull null
                    val id = key.removePrefix("reianix.personal.status.").toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                    val status = ReiAnixWatchStatus.entries.firstOrNull { it.name == value } ?: return@mapNotNull null
                    id to status
                }.toMap()
                if (saved.isEmpty()) return@withContext emptyList()
                val episodes = library.collectorSnapshot().filter { it.animeId in saved && it.missing != true }
                    .distinctBy { it.id }.groupBy { it.animeId }
                buildList {
                    var offset = 0
                    while (true) {
                        val page = library.listAnime(200, offset)
                        page.forEach { anime ->
                            val status = saved[anime.id] ?: return@forEach
                            val rows = episodes[anime.id].orEmpty()
                            val done = rows.count { row ->
                                val duration = row.duration ?: 0.0
                                row.watched == true || (duration.isFinite() && duration > 0 && (row.progress ?: 0.0) >= duration * .9)
                            }
                            add(TrackingAnime(anime.id, anime.title, anime.coverCache, anime.coverUrl, status, rows.size, done))
                        }
                        offset += page.size
                        if (page.size < 200) break
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Não foi possível carregar suas listas." }
        finally { loading = false }
    }
    // Prefer the same materialized artwork already exposed by the library.
    // Never launch a separate remote cover request from a tracking list.
    val visibleArtwork = remember(tracked, animes) {
        val artworkById = animes.associateBy { it.id }
        tracked.map { item ->
            item.copy(coverCache = artworkById[item.id]?.artwork?.localPath ?: item.coverCache)
        }
    }
    ReiAnixResponsiveRoot {
        TrackingCollectionsContent(visibleArtwork, loading, error, { retry++ }, onOpenAnime, onBack)
    }
}

@Composable
internal fun TrackingCollectionsContent(
    tracked: List<TrackingAnime>, loading: Boolean, error: String?, onRetry: () -> Unit,
    onOpenAnime: (Long) -> Unit, onBack: () -> Unit,
) {
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = ReiAnixWatchStatus.entries.firstOrNull { it.name == selectedName }
    fun back() { if (selected != null) selectedName = null else onBack() }
    BackHandler(enabled = selected != null) { back() }
    Column(Modifier.fillMaxSize().background(reiAnixScreenBackground()).navigationBarsPadding()) {
        ReiAnixTopBar(title = selected?.label ?: "Listas de acompanhamento",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { back() })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            TextButton(onClick = onRetry) { Text("Tentar novamente") }
        }
        if (selected == null) {
            val categories = listOf(
                Triple(ReiAnixWatchStatus.PLANNING, Icons.Outlined.BookmarkBorder, MaterialTheme.colorScheme.onSurfaceVariant),
                Triple(ReiAnixWatchStatus.WATCHING, Icons.Outlined.PlayCircleOutline, MaterialTheme.colorScheme.primary),
                Triple(ReiAnixWatchStatus.COMPLETED, Icons.Outlined.CheckCircle, Color(0xFF4CCC94)),
                Triple(ReiAnixWatchStatus.PAUSED, Icons.Outlined.PauseCircleOutline, Color(0xFFFFC768)),
                Triple(ReiAnixWatchStatus.DROPPED, Icons.Outlined.Cancel, Color(0xFFF16B70)),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                categories.forEach { (status, icon, tint) ->
                    val count = tracked.count { it.status == status }
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { selectedName = status.name }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(status.label, style = MaterialTheme.typography.bodyLarge)
                            Text(if (loading) "Carregando…" else if (count == 1) "1 anime" else "$count animes",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            val visible = tracked.filter { it.status == selected }
            if (!loading && error == null && visible.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.BookmarkBorder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Nenhum anime nesta categoria")
                    Text("Use a página de detalhes para adicionar.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible, key = { it.id }) { anime ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenAnime(anime.id) }, colors = CardDefaults.cardColors(
                            containerColor = reiAnixContentBackground(MaterialTheme.colorScheme.surfaceContainer))) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(60.dp, 88.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Movie, null)
                                    val model = anime.coverCache?.takeIf { it.isNotBlank() }?.let(::File)
                                    AsyncImage(model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(anime.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${anime.available} episódios disponíveis", style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(progress = { if (anime.available > 0) (anime.completed.toFloat() / anime.available).coerceIn(0f, 1f) else 0f },
                                        modifier = Modifier.fillMaxWidth())
                                    Text("${anime.completed}/${anime.available} concluídos", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        }
                    }
                }
            }
        }
    }
}
