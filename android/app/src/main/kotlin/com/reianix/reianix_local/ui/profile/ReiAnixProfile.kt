package com.reianix.reianix_local.ui.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.reianix.reianix_local.ReiAnixAppDependencies
import com.reianix.reianix_local.data.profile.ReiAnixProfile
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import com.reianix.reianix_local.data.sqlite.ReiAnixAnimeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixCollectorEpisodeRow
import com.reianix.reianix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reianix.reianix_local.player.PlayerLocalMetadataStore
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.collections.ReiAnixWatchStatusCollections
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.navigation.navigateToDetails
import com.reianix.reianix_local.ui.navigation.navigateToPlayer
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class AvatarPreset(val id: String, val label: String, val icon: ImageVector, val color: Color)
private val presets = listOf(
    AvatarPreset("person", "Clássico", Icons.Outlined.Person, Color(0xFF607D8B)),
    AvatarPreset("moon", "Lua", Icons.Outlined.DarkMode, Color(0xFF4656B0)),
    AvatarPreset("star", "Estrela", Icons.Outlined.Star, Color(0xFFAB7333)),
    AvatarPreset("bolt", "Energia", Icons.Outlined.Bolt, Color(0xFF2478D2)),
    AvatarPreset("heart", "Coração", Icons.Outlined.FavoriteBorder, Color(0xFFC75780)),
    AvatarPreset("forest", "Natureza", Icons.Outlined.Park, Color(0xFF247D69)),
    AvatarPreset("fire", "Chama", Icons.Outlined.LocalFireDepartment, Color(0xFFC05235)),
    AvatarPreset("game", "Gamer", Icons.Outlined.SportsEsports, Color(0xFF8754BB)),
)
private val profileColors = linkedMapOf(
    "blue" to Color(0xFF398AFF),
    "purple" to Color(0xFFAA82FF),
    "pink" to Color(0xFFFF78AE),
    "green" to Color(0xFF49CC9A),
    "gold" to Color(0xFFFFC768),
    "white" to Color.White,
)
private fun accent(profile: ReiAnixProfile) = profileColors[profile.color] ?: profileColors.getValue("blue")

@Composable
private fun ProfileAvatar(profile: ReiAnixProfile, store: ReiAnixProfileStore, size: Dp, modifier: Modifier = Modifier) {
    val preset = presets.firstOrNull { it.id == profile.avatar } ?: presets.first()
    val photo = store.image(profile.photo)
    val ring = when (profile.frame) {
        "accent" -> accent(profile)
        "gold" -> Color(0xFFFFC768)
        "white" -> Color.White
        else -> Color.Transparent
    }
    Box(
        modifier.size(size).then(if (ring != Color.Transparent) Modifier.border(2.dp, ring, CircleShape).padding(3.dp) else Modifier)
            .clip(CircleShape).background(preset.color.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(preset.icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        if (photo != null) AsyncImage(model = photo, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ReiAnixProfileHeaderAction(onClick: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReiAnixProfileStore.from(context) }
    val profile by store.state.collectAsStateWithLifecycle()
    IconButton(onClick = onClick, modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget)
        .semantics { contentDescription = "Meu perfil" }) {
        ProfileAvatar(profile, store, 30.dp)
    }
}

internal data class ProfileStats(
    val animeCount: Int,
    val episodeCount: Int,
    val completedEpisodes: Int,
    val watchedSeconds: Double,
    val favoriteCount: Int,
    val startedAnimeCount: Int,
    val genres: List<Pair<String, Int>>,
)
internal fun profileStatistics(animes: List<ReiAnixAnimeRecord>, rows: List<ReiAnixCollectorEpisodeRow>): ProfileStats {
    val episodes = rows.distinctBy { it.id }
    val available = episodes.filter { it.missing != true }
    fun safe(n: Double?) = n?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    val completed = episodes.filter { it.watched == true || (safe(it.duration) > 0 && safe(it.progress) >= safe(it.duration) * 0.9) }
    val started = episodes.filter { it.watched == true || safe(it.progress) > 0 }
    val watched = episodes.sumOf { row ->
        val duration = safe(row.duration)
        if (duration > 0) {
            if (row.watched == true) duration else safe(row.progress).coerceAtMost(duration)
        } else safe(row.progress)
    }
    val startedIds = started.mapTo(HashSet()) { it.animeId }
    val genres = animes.filter { it.id in startedIds }
        .flatMap { anime ->
            runCatching {
                val json = JSONArray(anime.genresJson ?: "[]")
                (0 until json.length()).map { json.optString(it).trim() }.filter { it.isNotBlank() }.distinct()
            }.getOrDefault(emptyList())
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
    return ProfileStats(animes.size, available.size, completed.size, watched, animes.count { it.favorite == true },
        startedIds.size, genres)
}

private fun timeText(seconds: Double): String {
    val minutes = ((seconds.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0) / 60).toLong()
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
private fun sceneTime(ms: Long) = "%02d:%02d".format(ms / 60_000, (ms / 1000) % 60)
private fun normalizedMedia(path: String): String = if (path.startsWith("/")) Uri.fromFile(File(path)).toString() else path

@Composable
fun ReiAnixProfileRoute(navController: NavHostController, viewModel: ReiAnixLibraryViewModel) {
    // Profile deliberately follows the supplied black reference, independently
    // of the optional app-wide light theme. Keep inherited content colors readable.
    com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme(themeMode = "dark") {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            ProfileContent(navController, viewModel)
        }
    }
}

@Composable
private fun ProfileContent(navController: NavHostController, viewModel: ReiAnixLibraryViewModel) {
    val context = LocalContext.current
    val dependencies = remember(context) { ReiAnixAppDependencies.from(context) }
    val store = remember(context) { ReiAnixProfileStore.from(context) }
    val profile by store.state.collectAsStateWithLifecycle()
    val catalog by viewModel.uiState.collectAsStateWithLifecycle()
    val library = dependencies.sqliteLibraryRepository
    var section by rememberSaveable { mutableStateOf("profile") }
    var records by remember { mutableStateOf<List<ReiAnixAnimeRecord>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<ReiAnixCollectorEpisodeRow>>(emptyList()) }
    var stats by remember { mutableStateOf<ProfileStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val openAnime: (Long) -> Unit = { id -> navController.navigateToDetails(id.toString(), ReiAnixRoutes.PROFILE) }
    val playEpisode: (Long, Long) -> Unit = { episodeId, animeId ->
        navController.navigateToPlayer(episodeId.toString(), animeId.toString(), ReiAnixRoutes.PROFILE)
    }
    val goBack: () -> Unit = { if (section != "profile") section = "profile" else navController.popBackStack() }
    BackHandler(enabled = section != "profile" && section !in setOf("edit", "customize")) { goBack() }

    LaunchedEffect(library, retry) {
        loading = true
        error = null
        try {
            val all = buildList {
                var offset = 0
                while (true) {
                    val page = library.listAnime(200, offset)
                    addAll(page)
                    if (page.size < 200) break
                    offset += page.size
                }
            }
            val rows = library.collectorSnapshot()
            records = all
            episodes = rows
            stats = withContext(Dispatchers.Default) { profileStatistics(all, rows) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Não foi possível carregar os dados do perfil." }
        finally { loading = false }
    }

    when (section) {
        "edit", "customize" -> {
            ProfileEditor(profile, store, records.filter { it.favorite == true }, section == "customize", goBack)
            return
        }
        "lists" -> {
            ReiAnixWatchStatusCollections(catalog.animes, onOpenAnime = openAnime, onBack = goBack)
            return
        }
        "scenes" -> {
            ProfileScenes(viewModel, goBack, records)
            return
        }
    }
    val title = when (section) {
        "stats" -> "Estatísticas"
        "favorites" -> "Favoritos"
        "history" -> "Histórico"
        else -> "Meu perfil"
    }
    Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
        ReiAnixTopBar(title = title, navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = goBack)
        if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { retry++ }) { Text("Tentar novamente") }
        }
        when (section) {
            "profile" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        store.image(profile.banner)?.let { file ->
                            AsyncImage(model = file, contentDescription = "Banner do perfil",
                                modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop)
                        }
                        ProfileAvatar(profile, store, 112.dp)
                        Text(profile.displayName, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                        Text(profile.displayBio, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAAAAAA),
                            textAlign = TextAlign.Center)
                        OutlinedButton(onClick = { section = "edit" }, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF454545))) {
                            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Editar perfil", color = Color.White)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            ProfileNumber(stats?.animeCount?.toString() ?: "—", "Animes", accent(profile), Modifier.weight(1f))
                            ProfileNumber(stats?.episodeCount?.toString() ?: "—", "Episódios", accent(profile), Modifier.weight(1f))
                            ProfileNumber(stats?.let { timeText(it.watchedSeconds) } ?: "—", "Assistidas", accent(profile), Modifier.weight(1f))
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val featured = records.firstOrNull { it.id == profile.featuredAnimeId && it.favorite == true }
                        if (featured != null) {
                            Text("Anime em destaque", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.labelMedium)
                            AnimeProfileRow(featured, onClick = { openAnime(featured.id) })
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0xFF292929))
                    }
                }
                item {
                    Spacer(Modifier.height(14.dp))
                    ProfileRow(Icons.Outlined.BarChart, "Estatísticas") { section = "stats" }
                    ProfileRow(Icons.Outlined.FavoriteBorder, "Favoritos") { section = "favorites" }
                    ProfileRow(Icons.Outlined.FolderCopy, "Minhas coleções") { navController.navigate(ReiAnixRoutes.COLLECTIONS) { launchSingleTop = true } }
                    ProfileRow(Icons.Outlined.Movie, "Cenas favoritas") { section = "scenes" }
                    ProfileRow(Icons.Outlined.BookmarkBorder, "Listas de acompanhamento") { section = "lists" }
                    ProfileRow(Icons.Outlined.EmojiEvents, "Conquistas") { navController.navigate(ReiAnixRoutes.COLLECTOR) { launchSingleTop = true } }
                    ProfileRow(Icons.Outlined.History, "Histórico") { section = "history" }
                    ProfileRow(Icons.Outlined.Palette, "Personalização do perfil") { section = "customize" }
                    ProfileRow(Icons.Outlined.Settings, "Configurações gerais") { navController.navigate(ReiAnixRoutes.SETTINGS_GENERAL) { launchSingleTop = true } }
                }
            }
            "stats" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                stats?.let { st ->
                    ProfileMetric("Animes na biblioteca", st.animeCount.toString())
                    ProfileMetric("Episódios disponíveis", st.episodeCount.toString())
                    ProfileMetric("Episódios concluídos", st.completedEpisodes.toString())
                    ProfileMetric("Animes iniciados", st.startedAnimeCount.toString())
                    ProfileMetric("Favoritos", st.favoriteCount.toString())
                    ProfileMetric("Tempo registrado", timeText(st.watchedSeconds))
                    Text("Estimativa com base no progresso salvo e na duração dos episódios concluídos; não contabiliza reassistidas separadamente.",
                        color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = Color(0xFF292929))
                    Text("Gêneros mais assistidos", style = MaterialTheme.typography.titleMedium)
                    val max = st.genres.firstOrNull()?.second ?: 1
                    if (st.genres.isEmpty()) Text("Nenhum gênero registrado no histórico.")
                    st.genres.take(12).forEach { (name, count) ->
                        ProfileMetric(name, count.toString())
                        LinearProgressIndicator(progress = { count.toFloat() / max }, modifier = Modifier.fillMaxWidth(), color = accent(profile))
                    }
                } ?: Text(if (loading) "Carregando…" else "Estatísticas indisponíveis.")
            }
            "favorites" -> {
                val favorites = records.filter { it.favorite == true }
                if (favorites.isEmpty()) Text(if (loading) "Carregando…" else "Nenhum anime favorito.", Modifier.padding(20.dp))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(favorites, key = { it.id }) { anime -> AnimeProfileRow(anime, onClick = { openAnime(anime.id) }) }
                }
            }
            "history" -> {
                val history = episodes.filter { it.lastPlayedAt?.let { timestamp -> timestamp.isFinite() && timestamp > 0.0 } == true }.sortedByDescending { it.lastPlayedAt }
                if (history.isEmpty()) Text(if (loading) "Carregando…" else "Nenhuma reprodução registrada.", Modifier.padding(20.dp))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    items(history, key = { it.id }) { row ->
                        val date = DateFormat.getDateTimeInstance().format(Date((row.lastPlayedAt!! * 1000).toLong()))
                        Row(Modifier.fillMaxWidth().clickable(enabled = row.missing != true) { playEpisode(row.id, row.animeId) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.History, null)
                            Column(Modifier.weight(1f)) {
                                Text(row.animeTitle ?: "Anime", style = MaterialTheme.typography.titleMedium)
                                Text(date + " · " + timeText(row.progress ?: 0.0), color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Outlined.PlayArrow, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileNumber(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(value, color = color, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(label, color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable
private fun ProfileMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = Color.White)
        Text(value, color = Color(0xFFAAAAAA))
    }
}
@Composable
private fun ProfileRow(icon: ImageVector, label: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = click).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = Color(0xFFE0E0E0), modifier = Modifier.size(24.dp))
        Text(label, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFF707070), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AnimeProfileRow(anime: ReiAnixAnimeRecord, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp, 70.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF171717)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Movie, null)
            anime.coverCache?.takeIf { it.isNotBlank() }?.let {
                AsyncImage(model = File(it), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Text(anime.title, Modifier.weight(1f), color = Color.White)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
    }
}

@Composable
private fun ProfileEditor(
    initial: ReiAnixProfile, store: ReiAnixProfileStore, favorites: List<ReiAnixAnimeRecord>,
    customize: Boolean, onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var bio by rememberSaveable { mutableStateOf(initial.bio) }
    var avatar by rememberSaveable { mutableStateOf(initial.avatar) }
    var photo by rememberSaveable { mutableStateOf(initial.photo) }
    var banner by rememberSaveable { mutableStateOf(initial.banner) }
    var frame by rememberSaveable { mutableStateOf(initial.frame) }
    var color by rememberSaveable { mutableStateOf(initial.color) }
    var featured by rememberSaveable { mutableStateOf(initial.featuredAnimeId) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val draft = ReiAnixProfile(name, bio, avatar, photo, banner, frame, color, featured)
    fun discardAndBack() {
        if (busy) return
        scope.launch {
            store.discard(photo)
            store.discard(banner)
            onBack()
        }
    }
    BackHandler { discardAndBack() }
    fun import(uri: Uri?, isBanner: Boolean) {
        if (uri == null || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val value = store.importImage(uri, isBanner)
                if (isBanner) { store.discard(banner); banner = value }
                else { store.discard(photo); photo = value }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Não foi possível importar a imagem." }
            finally { busy = false }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { import(it, false) }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { import(it, true) }
    Column(Modifier.fillMaxSize().background(Color.Black).imePadding().navigationBarsPadding()) {
        ReiAnixTopBar(title = if (customize) "Personalização do perfil" else "Editar perfil",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { discardAndBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfileAvatar(draft, store, 104.dp)
            OutlinedButton(onClick = { photoPicker.launch("image/*") }, enabled = !busy) { Text("Escolher foto da galeria") }
            if (photo != null) TextButton(onClick = {
                val old = photo; photo = null; scope.launch { store.discard(old) }
            }, enabled = !busy) { Text("Remover foto") }
            if (!customize) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Nome do usuário") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bio, { bio = it.take(160) }, label = { Text("Biografia") },
                    maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            Text("Avatares personalizados", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            presets.chunked(4).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    group.forEach { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = !busy) {
                            avatar = p.id
                            val old = photo; photo = null; scope.launch { store.discard(old) }
                        }.padding(4.dp)) {
                            ProfileAvatar(draft.copy(avatar = p.id, photo = null,
                                frame = if (avatar == p.id && photo == null) "accent" else "none"), store, 48.dp)
                            Text(p.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (customize) {
                Text("Moldura", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("none" to "Sem", "accent" to "Cor", "gold" to "Ouro", "white" to "Branca").forEach { (key, label) ->
                        FilterChip(selected = frame == key, onClick = { frame = key }, label = { Text(label) }, enabled = !busy)
                    }
                }
                Text("Cor do perfil", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    profileColors.forEach { (key, value) ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(value)
                            .then(if (color == key) Modifier.border(3.dp, Color.Gray, CircleShape) else Modifier)
                            .semantics { contentDescription = "Cor $key" }.clickable(enabled = !busy) { color = key })
                    }
                }
                Text("Banner", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                store.image(banner)?.let { file ->
                    AsyncImage(model = file, contentDescription = "Prévia do banner", modifier = Modifier.fillMaxWidth().height(100.dp), contentScale = ContentScale.Crop)
                }
                OutlinedButton(onClick = { bannerPicker.launch("image/*") }, enabled = !busy) { Text("Escolher banner") }
                if (banner != null) TextButton(onClick = { val old = banner; banner = null; scope.launch { store.discard(old) } }, enabled = !busy) { Text("Remover banner") }
                Text("Anime favorito em destaque", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { featured = null }, enabled = !busy) { Text(if (featured == null) "✓ Nenhum" else "Nenhum") }
                favorites.forEach { anime ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { featured = anime.id }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = featured == anime.id, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(anime.title)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(onClick = {
            busy = true
            scope.launch {
                try { store.save(draft); onBack() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Não foi possível salvar o perfil." }
                finally { busy = false }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 50.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Salvar perfil")
        }
    }
}

@Composable
private fun ProfileScenes(viewModel: ReiAnixLibraryViewModel, onBack: () -> Unit, records: List<ReiAnixAnimeRecord>) {
    val context = LocalContext.current
    val repository = remember(context) { ReiAnixAppDependencies.from(context).sqliteLibraryRepository }
    val store = remember(context) { PlayerLocalMetadataStore(context) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var scenes by remember { mutableStateOf<List<PlayerLocalMetadataStore.FavoriteScene>>(emptyList()) }
    var matches by remember { mutableStateOf<Map<String, ReiAnixEpisodeRecord>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<PlayerLocalMetadataStore.FavoriteScene?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(records, reload) {
        loading = true
        error = null
        try {
            val saved = withContext(Dispatchers.IO) { store.favoriteScenes() }
            val wanted = saved.map { it.mediaId }.toSet()
            val found: Map<String, ReiAnixEpisodeRecord> = if (wanted.isEmpty()) emptyMap() else buildMap {
                for (anime in records) {
                    repository.listEpisodes(anime.id).forEach { episode ->
                        val path = normalizedMedia(episode.path)
                        if (path in wanted && episode.missing != true) put(path, episode)
                    }
                    if (size == wanted.size) break
                }
            }
            scenes = saved
            matches = found
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Não foi possível carregar as cenas." }
        finally { loading = false }
    }
    LaunchedEffect(state.lastCommandId, state.lastCommandStatus, request) {
        if (request != null && state.lastCommandId == request && state.lastCommandStatus?.uppercase() == "FAILED") {
            error = state.lastCommandError ?: "Não foi possível abrir a cena."
            request = null
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding()) {
        ReiAnixTopBar("Cenas favoritas", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (scenes.isEmpty()) Text("Nenhuma cena salva. Salve trechos no player do ReiAnix.", Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(scenes, key = { it.note.id }) { scene ->
                val episode = matches[scene.mediaId]
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(enabled = episode != null && scene.note.clipEndMs != null, onClick = {
                        episode?.let { request = viewModel.openEpisode(it.id, it.animeId, favoriteSceneId = scene.note.id) }
                    }) { Icon(Icons.Outlined.PlayArrow, "Reproduzir cena") }
                    Column(Modifier.weight(1f)) {
                        Text(scene.note.text, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(scene.note.animeTitle, scene.note.episodeLabel).filter { it.isNotBlank() }.joinToString(" · "),
                            color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                        Text(sceneTime(scene.note.timestampMs) + (scene.note.clipEndMs?.let { " – " + sceneTime(it) } ?: " · Marcador"),
                            color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                        if (episode == null) Text("Arquivo original indisponível", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { delete = scene }) { Icon(Icons.Outlined.Delete, "Excluir cena") }
                }
            }
        }
    }
    delete?.let { scene ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Excluir cena?") },
            text = { Text("O vídeo original será preservado.") },
            confirmButton = { TextButton(onClick = {
                delete = null
                scope.launch {
                    try { withContext(Dispatchers.IO) { store.deleteNote(scene.mediaId, scene.note.id) }; reload++ }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Não foi possível excluir a cena." }
                }
            }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancelar") } })
    }
}
