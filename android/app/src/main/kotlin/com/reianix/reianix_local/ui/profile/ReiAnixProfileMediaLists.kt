package com.reianix.reianix_local.ui.profile

import com.reianix.reianix_local.data.artwork.ReiAnixAdultArtworkPolicy

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.data.sqlite.ReiAnixAnimeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixCollectorEpisodeRow
import com.reianix.reianix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.artwork.ReiAnixEpisodeThumbnail
import com.reianix.reianix_local.ui.artwork.ReiAnixPoster
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.theme.reiAnixContentBackground
import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Separate, bounded, local-only preview cache. No writes to the artwork catalog,
// no background library scan and no remote image requests.
private object ProfileFrameCache {
    private val frames = LruCache<String, Bitmap>(32)
    private val mutex = Mutex()

    suspend fun load(context: Context, media: String, ms: Long, revision: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$media@$ms:$revision"
            mutex.withLock {
                frames.get(key)?.let { return@withLock it }
                val frame = try {
                    val uri = Uri.parse(media)
                    val retriever = MediaMetadataRetriever()
                    try {
                        when {
                            uri.scheme == "content" -> retriever.setDataSource(context.applicationContext, uri)
                            uri.scheme == "file" -> retriever.setDataSource(requireNotNull(uri.path))
                            uri.scheme == null && media.startsWith("/") -> retriever.setDataSource(media)
                            else -> return@withLock null
                        }
                        val timeUs = ms.coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L
                        if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { bitmap ->
                                val scaled = Bitmap.createScaledBitmap(bitmap, 320, 180, true)
                                if (scaled !== bitmap) bitmap.recycle()
                                scaled
                            }
                        }
                    } finally { retriever.release() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                frame?.also { frames.put(key, it) }
            }
        }
}

@Composable
internal fun ProfileVideoFrame(
    media: String?, positionMs: Long, revision: String, modifier: Modifier = Modifier,
    cachedThumbnail: String? = null, exactScene: Boolean = false,
) {
    val context = LocalContext.current
    val frame by produceState<Bitmap?>(null, media, positionMs, revision, cachedThumbnail, exactScene) {
        value = null
        if (!media.isNullOrBlank() && (exactScene || cachedThumbnail.isNullOrBlank())) {
            value = ProfileFrameCache.load(context, media, positionMs, revision)
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        when {
            frame != null -> Image(requireNotNull(frame).asImageBitmap(), "Miniatura do vídeo",
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !exactScene && !cachedThumbnail.isNullOrBlank() ->
                ReiAnixEpisodeThumbnail(cachedThumbnail, "Miniatura do episódio", Modifier.fillMaxSize())
            else -> Icon(Icons.Outlined.Movie, "Miniatura indisponível", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun profilePlaybackTime(seconds: Double?): String {
    val sec = (seconds?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0).toLong()
    return if (sec >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
    else String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60)
}

internal fun profileHistoryCompleted(row: ReiAnixCollectorEpisodeRow): Boolean {
    val duration = row.duration?.takeIf { it.isFinite() && it > 0 } ?: return row.watched == true
    return row.watched == true || (row.progress?.takeIf { it.isFinite() } ?: 0.0) >= duration * .9
}

internal fun profileEpisodeLabel(number: Double?, title: String?, fileName: String?): String {
    val numberLabel = number?.takeIf { it.isFinite() && it >= 0 }?.let {
        "Episódio " + if (it == it.toLong().toDouble()) it.toLong().toString().padStart(2, '0') else it.toString()
    }
    val actualTitle = title?.takeIf { it.isNotBlank() }
    return listOfNotNull(numberLabel, actualTitle?.takeUnless { it.equals(numberLabel, true) })
        .joinToString(" · ").ifBlank { fileName.orEmpty().ifBlank { "Episódio local" } }
}

internal fun historyGroup(timestamp: Double, now: Calendar): String {
    val day = Calendar.getInstance().apply { timeInMillis = (timestamp * 1000).toLong() }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        same(day, now) -> "Hoje"
        same(day, yesterday) -> "Ontem"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(day.time)
    }
}

@Composable
internal fun ProfileMediaSearch(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(query, onQuery, singleLine = true, placeholder = { Text("Pesquisar") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Limpar pesquisa") } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun profileMediaCardColors() = CardDefaults.cardColors(
    containerColor = reiAnixContentBackground(MaterialTheme.colorScheme.surfaceContainer),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
internal fun ProfileFavorites(
    records: List<ReiAnixAnimeRecord>, animes: List<ReiAnixAnimeUiModel>,
    episodes: List<ReiAnixCollectorEpisodeRow>, loading: Boolean, onOpen: (Long) -> Unit, onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("az") }
    var sortOpen by remember { mutableStateOf(false) }
    val catalog = remember(animes) { animes.associateBy { it.id } }
    val counts = remember(episodes) { episodes.filter { it.missing != true }.distinctBy { it.id }.groupingBy { it.animeId }.eachCount() }
    val favorites = remember(records) { records.filter { it.favorite == true } }
    val visible = remember(favorites, query, sort) {
        favorites.filter { it.title.contains(query.trim(), true) }.let { list ->
            when (sort) {
                "recent" -> list.sortedByDescending { it.addedAt }
                "za" -> list.sortedByDescending { it.title.lowercase(Locale.ROOT) }
                else -> list.sortedBy { it.title.lowercase(Locale.ROOT) }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(reiAnixScreenBackground()).navigationBarsPadding()) {
        ReiAnixTopBar("Favoritos", subtitle = "${favorites.size} animes", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavigationClick = onBack, actions = {
                IconButton(onClick = { search = !search; if (!search) query = "" }) { Icon(Icons.Outlined.Search, "Pesquisar favoritos") }
                Box {
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.Outlined.Sort, "Ordenar favoritos") }
                    DropdownMenu(sortOpen, { sortOpen = false }) {
                        listOf("az" to "Nome (A–Z)", "za" to "Nome (Z–A)", "recent" to "Adicionados recentemente").forEach { (key, title) ->
                            DropdownMenuItem(text = { Text(title) }, onClick = { sort = key; sortOpen = false })
                        }
                    }
                }
            })
        if (search) ProfileMediaSearch(query) { query = it }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && visible.isEmpty()) Text("Nenhum anime encontrado.", Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visible, key = { it.id }) { anime ->
                val model = catalog[anime.id]
                val genres = remember(anime.genresJson, model) {
                    model?.genres?.map { it.name }?.takeIf { it.isNotEmpty() } ?: runCatching {
                        val array = JSONArray(anime.genresJson ?: "[]")
                        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                    }.getOrDefault(emptyList())
                }
                val adultArtwork = ReiAnixAdultArtworkPolicy.blocked(genres) || ReiAnixAdultArtworkPolicy.blockedJson(anime.genresJson)
                Card(Modifier.fillMaxWidth().clickable { onOpen(anime.id) }, colors = profileMediaCardColors()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReiAnixPoster((model?.artwork?.localPath ?: anime.coverCache).takeUnless { adultArtwork }, "Capa de " + anime.title,
                            Modifier.size(64.dp, 96.dp), identity = "anime:" + anime.id,
                            fallbackLocalPath = anime.coverCache.takeUnless { adultArtwork })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(model?.title ?: anime.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (genres.isNotEmpty()) Text(genres.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${counts[anime.id] ?: model?.availableContentCount ?: 0} episódios disponíveis", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Outlined.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfileHistory(
    episodes: List<ReiAnixCollectorEpisodeRow>, animes: List<ReiAnixAnimeUiModel>, loading: Boolean,
    library: ReiAnixSqliteLibraryRepository, onPlay: (Long, Long) -> Unit, onBack: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val models = remember(animes) { animes.flatMap { it.contentEpisodes }.associateBy { it.id } }
    val history = remember(episodes, models, query) {
        episodes.distinctBy { it.id }.filter { row ->
            row.lastPlayedAt?.let { it.isFinite() && it > 0.0 } == true &&
                (row.animeTitle.orEmpty().contains(query.trim(), true) ||
                    models[row.id]?.let { profileEpisodeLabel(it.number, it.title, it.fileName).contains(query.trim(), true) } == true)
        }.sortedByDescending { it.lastPlayedAt }
    }
    val now = remember { Calendar.getInstance() }
    val groups = remember(history, now) { history.groupBy { historyGroup(requireNotNull(it.lastPlayedAt), now) } }
    Column(Modifier.fillMaxSize().background(reiAnixScreenBackground()).navigationBarsPadding()) {
        ReiAnixTopBar("Histórico", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack,
            actions = { IconButton(onClick = { search = !search; if (!search) query = "" }) { Icon(Icons.Outlined.Search, "Pesquisar histórico") } })
        if (search) ProfileMediaSearch(query) { query = it }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && history.isEmpty()) Text("Nenhuma reprodução encontrada.", Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { (day, rows) ->
                item(key = "day:$day") { Text(day, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
                items(rows, key = { it.id }) { row ->
                    val model = models[row.id]
                    val fallback by produceState<ReiAnixEpisodeRecord?>(null, row.id, model == null) {
                        value = if (model == null) {
                            try { library.getEpisode(row.id) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        } else null
                    }
                    val completed = profileHistoryCompleted(row)
                    val duration = row.duration?.takeIf { it.isFinite() && it > 0 }
                    val progress = row.progress?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
                    val fraction = if (completed) 1f else duration?.let { (progress / it).toFloat().coerceIn(0f, 1f) } ?: 0f
                    val tint = if (completed) Color(0xFF40C98A) else MaterialTheme.colorScheme.primary
                    val playable = row.missing != true && (model?.isPlayable ?: (fallback != null && fallback?.missing != true))
                    Card(Modifier.fillMaxWidth().clickable(enabled = playable) { onPlay(row.id, row.animeId) }, colors = profileMediaCardColors()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.width(116.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ProfileVideoFrame(if (playable) model?.media?.reference ?: fallback?.path else null,
                                    ((duration ?: 0.0) * 250).toLong().coerceAtLeast(0L),
                                    (model?.modifiedAt ?: fallback?.modifiedAt).toString(),
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f), cachedThumbnail = model?.artwork?.localPath)
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp), color = tint)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.animeTitle ?: "Anime", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(profileEpisodeLabel(model?.number ?: fallback?.number, model?.title ?: fallback?.episodeTitle, model?.fileName ?: fallback?.fileName),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (completed) "Concluído" else profilePlaybackTime(progress) + (duration?.let { " / " + profilePlaybackTime(it) } ?: ""),
                                    style = MaterialTheme.typography.labelMedium, color = tint)
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(requireNotNull(row.lastPlayedAt).times(1000).toLong())),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!playable) Text("Arquivo indisponível", style = MaterialTheme.typography.labelSmall)
                            }
                            Icon(if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.PlayCircleOutline, null, tint = tint)
                        }
                    }
                }
            }
        }
    }
}
