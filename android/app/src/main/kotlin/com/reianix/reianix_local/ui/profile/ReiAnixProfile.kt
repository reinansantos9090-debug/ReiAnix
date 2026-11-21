package com.reianix.reianix_local.ui.profile

import com.reianix.reianix_local.ui.motion.reiAnixScreenFade
import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.reianix.reianix_local.R
import com.reianix.reianix_local.ReiAnixAppDependencies
import com.reianix.reianix_local.data.profile.DEFAULT_PROFILE_AVATAR_ID
import com.reianix.reianix_local.data.profile.ReiAnixProfile
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import com.reianix.reianix_local.ui.theme.ReiAnixWallpaperLayer
import com.reianix.reianix_local.ui.theme.WallpaperImageLoad
import com.reianix.reianix_local.ui.theme.rememberWallpaperImage
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class AvatarPreset(val id: String, val label: String, @DrawableRes val image: Int)

private val presets = listOf(
    AvatarPreset("person", "Ops!", R.drawable.profile_avatar_dragao_assustado),
    AvatarPreset("moon", "Feliz", R.drawable.profile_avatar_raposa_curiosa),
    AvatarPreset("star", "Surpreso", R.drawable.profile_avatar_dragao_surpresa),
    AvatarPreset("bolt", "Amoroso", R.drawable.profile_avatar_abraco_raposa),
    AvatarPreset("heart", "Dormindo", R.drawable.profile_avatar_soneca_dragao),
    AvatarPreset("forest", "Lanchando", R.drawable.profile_avatar_lanche_raposa),
    AvatarPreset(DEFAULT_PROFILE_AVATAR_ID, "Estiloso", R.drawable.profile_avatar_dragao_estiloso),
    AvatarPreset("game", "Divertindo", R.drawable.profile_avatar_festa_raposa),
)
private val defaultAvatarPreset = presets.first { it.id == DEFAULT_PROFILE_AVATAR_ID }

private fun avatarPresetFor(id: String): AvatarPreset =
    presets.firstOrNull { it.id == id } ?: defaultAvatarPreset

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
    val preset = avatarPresetFor(profile.avatar)
    val photo = store.image(profile.photo)
    var photoFailed by remember(photo) { mutableStateOf(false) }
    val animated = isAnimatedProfileFrame(profile.frame)
    val ring = when (profile.frame) {
        "accent" -> accent(profile)
        "white" -> Color.White
        else -> Color.Transparent
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // Space for frame ornaments; photo and built-in avatar remain circular and clipped.
        Box(
            Modifier.fillMaxSize()
                .padding(if (animated) size * 0.11f else 0.dp)
                .then(if (ring != Color.Transparent) Modifier.border(2.dp, ring, CircleShape).padding(3.dp) else Modifier)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = if (photo != null && !photoFailed) photo else preset.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { if (photo != null) photoFailed = true },
            )
        }
        if (animated) {
            AnimatedProfileFrame(
                frame = profile.frame,
                modifier = Modifier.fillMaxSize().testTag("profile-frame-animation-" + profile.frame),
            )
        }
    }
}

/** Camera badge is centered on the circular avatar's lower-right edge.
 * A single accessible click target covers both the photo and the badge. */
@Composable
internal fun EditableProfileAvatar(
    profile: ReiAnixProfile,
    store: ReiAnixProfileStore,
    size: Dp = 112.dp,
    enabled: Boolean = true,
    onSelectPhoto: () -> Unit,
) {
    Box(
        modifier = Modifier.size(size)
            .clickable(enabled = enabled, role = Role.Button, onClick = onSelectPhoto)
            .semantics { contentDescription = "Alterar foto de perfil" },
        contentAlignment = Alignment.Center,
    ) {
        ProfileAvatar(profile, store, size)
        val badgeSize = 24.dp
        // At 45 degrees, the circular boundary is r/sqrt(2) from the center.
        val inset = size * 0.1464466f - badgeSize / 2
        Box(
            modifier = Modifier.align(Alignment.BottomEnd)
                .offset(x = -inset, y = -inset)
                .size(badgeSize)
                .background(Color(0xFF2675EF), CircleShape)
                .border(1.5.dp, Color.Black, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoCamera,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** The avatar is centered exactly on the lower edge of the optional banner. */
@Composable
private fun ProfileBannerHeader(
    profile: ReiAnixProfile,
    store: ReiAnixProfileStore,
    showCamera: Boolean = false,
    enabled: Boolean = true,
    onEditPhoto: () -> Unit,
    retainedImage: WallpaperImageLoad? = null,
) {
    val context = LocalContext.current
    val bannerStore = remember(context) { ReiAnixWallpaperStore.banner(context) }
    val banner by bannerStore.state.collectAsStateWithLifecycle()
    val imageLoad = retainedImage ?: rememberWallpaperImage(bannerStore, banner)
    val image = imageLoad.image
    if (image == null && !imageLoad.loading) {
        if (showCamera) {
            EditableProfileAvatar(profile, store, enabled = enabled, onSelectPhoto = onEditPhoto)
        } else {
            ProfileAvatar(profile, store, 112.dp)
        }
    } else {
        Box(Modifier.fillMaxWidth().padding(bottom = 58.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF202530))) {
                image?.let { ReiAnixWallpaperLayer(it, banner, Modifier.matchParentSize(), editing = true) }
            }
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 58.dp).size(116.dp)
                .background(Color(0xFF101010), CircleShape)
                .then(if (showCamera) Modifier else Modifier.clip(CircleShape).clickable(onClick = onEditPhoto)),
                contentAlignment = Alignment.Center) {
                if (showCamera) {
                    EditableProfileAvatar(profile, store, size = 108.dp,
                        enabled = enabled, onSelectPhoto = onEditPhoto)
                } else {
                    ProfileAvatar(profile, store, 108.dp)
                }
            }
        }
    }
}

@Composable
fun ReiAnixProfileHeaderAction(onClick: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReiAnixProfileStore.from(context) }
    val profile by store.state.collectAsStateWithLifecycle()
    IconButton(onClick = onClick, modifier = Modifier.size(ReiAnixTokens.Dimensions.touchTarget)
        .semantics { contentDescription = "Meu perfil" }) {
        ProfileAvatar(profile, store, 32.dp)
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
    val availableCompletedEpisodes: Int = completedEpisodes,
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
        startedIds.size, genres, completed.count { it.missing != true })
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
        Surface(color = reiAnixScreenBackground(), contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
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

    LaunchedEffect(library, retry, section == "history" || section == "favorites") {
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

    val bannerStore = remember(context) { ReiAnixWallpaperStore.banner(context) }
    val banner by bannerStore.state.collectAsStateWithLifecycle()
    val bannerImage = rememberWallpaperImage(bannerStore, banner)

    Box(Modifier.fillMaxSize().reiAnixScreenFade(section)) {
    when (section) {
        // "customize" remains a compatibility alias for restored navigation state.
        "edit", "customize" -> {
            ProfileCustomization(store, records.filter { it.favorite == true }, goBack)
            return@Box
        }
        "lists" -> {
            ReiAnixWatchStatusCollections(catalog.animes, onOpenAnime = openAnime, onBack = goBack)
            return@Box
        }
        "scenes" -> {
            ProfileScenes(viewModel, goBack, records)
            return@Box
        }
        "favorites" -> {
            ProfileFavorites(records, catalog.animes, episodes, loading, openAnime, goBack)
            return@Box
        }
        "history" -> {
            ProfileHistory(episodes, catalog.animes, loading, library, playEpisode, goBack)
            return@Box
        }
    }
    val title = when (section) {
        "stats" -> "Estatísticas"
        "favorites" -> "Favoritos"
        "history" -> "Histórico"
        else -> "Meu perfil"
    }
    Column(Modifier.fillMaxSize().background(reiAnixScreenBackground()).navigationBarsPadding()) {
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
                        ProfileBannerHeader(profile, store, onEditPhoto = { section = "edit" }, retainedImage = bannerImage)
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
                    ProfileRow(Icons.Outlined.Settings, "Configurações gerais") { navController.navigate(ReiAnixRoutes.SETTINGS_GENERAL) { launchSingleTop = true } }
                }
            }
            "stats" -> ProfileStatisticsContent(stats, loading)

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
internal fun ProfileStatisticsContent(stats: ProfileStats?, loading: Boolean) {
    if (stats == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text("Estatísticas indisponíveis.")
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Resumo da biblioteca", style = MaterialTheme.typography.titleMedium)
        val metrics = listOf(
            Triple(Icons.Outlined.VideoLibrary, stats.animeCount.toString(), "Animes na biblioteca"),
            Triple(Icons.Outlined.Movie, stats.episodeCount.toString(), "Episódios disponíveis"),
            Triple(Icons.Outlined.CheckCircle, stats.completedEpisodes.toString(), "Episódios concluídos"),
            Triple(Icons.Outlined.PlayCircleOutline, stats.startedAnimeCount.toString(), "Animes iniciados"),
            Triple(Icons.Outlined.FavoriteBorder, stats.favoriteCount.toString(), "Favoritos"),
            Triple(Icons.Outlined.Schedule, timeText(stats.watchedSeconds), "Tempo assistido"),
        )
        metrics.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (icon, value, label) ->
                    Card(Modifier.weight(1f).fillMaxHeight(), colors = CardDefaults.cardColors(
                        containerColor = com.reianix.reianix_local.ui.theme.reiAnixContentBackground(MaterialTheme.colorScheme.surfaceContainer),
                        contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Text(value, style = MaterialTheme.typography.headlineSmall)
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text("Progresso da biblioteca", style = MaterialTheme.typography.titleMedium)
        // The existing completed count may include episodes no longer present.
        // Use the same available-library denominator, avoiding impossible percentages.
        val availableCompleted = stats.availableCompletedEpisodes.coerceIn(0, stats.episodeCount.coerceAtLeast(0))
        val progress = if (stats.episodeCount > 0) availableCompleted.toFloat() / stats.episodeCount else 0f
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text("$availableCompleted de ${stats.episodeCount} episódios concluídos · ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Tempo estimado pelo progresso salvo, sem contar reassistidas separadamente.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Gêneros mais assistidos", style = MaterialTheme.typography.titleMedium)
        if (stats.genres.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.BarChart, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Seu histórico de gêneros aparecerá aqui.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            val max = stats.genres.maxOf { it.second }.coerceAtLeast(1)
            stats.genres.take(12).forEach { (name, count) ->
                ProfileMetric(name, count.toString())
                LinearProgressIndicator(progress = { (count.toFloat() / max).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            }
        }
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

/** Unified profile editor. Identity and profile choices save automatically;
 * image editors retain their explicit crop/preview confirmation. */
@Composable
internal fun ProfileCustomization(
    store: ReiAnixProfileStore,
    favorites: List<ReiAnixAnimeRecord>,
    onBack: () -> Unit,
) {
    val profile by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("menu") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Keep the raw text while typing (the store trims surrounding whitespace).
    // The job snapshots only identity fields, never stale photo/frame/banner values.
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var bio by rememberSaveable { mutableStateOf(profile.bio) }
    var textSaveJob by remember { mutableStateOf<Job?>(null) }

    suspend fun persistIdentity(newName: String, newBio: String) {
        store.update { it.copy(name = newName, bio = newBio) }
    }

    fun scheduleTextSave() {
        val newName = name
        val newBio = bio
        val previous = textSaveJob
        previous?.cancel()
        error = null
        textSaveJob = scope.launch {
            try {
                previous?.join()
                delay(200)
                persistIdentity(newName, newBio)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Não foi possível salvar o perfil automaticamente." }
        }
    }

    // Flush the latest text before navigating so a rapid Back or menu tap
    // cannot discard the last edit or race with a customization write.
    fun leaveMenu(next: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                textSaveJob?.cancelAndJoin()
                val current = store.state.value
                if (name.trim() != current.name || bio.trim() != current.bio) {
                    persistIdentity(name, bio)
                }
                next()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Não foi possível salvar o perfil. Tente novamente." }
            finally { busy = false }
        }
    }

    fun goBack() {
        if (busy) return
        error = null
        if (page == "menu") leaveMenu(onBack) else page = "menu"
    }
    fun saveChange(change: (ReiAnixProfile) -> ReiAnixProfile) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                store.update(change)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Não foi possível salvar a personalização." }
            finally { busy = false }
        }
    }
    var photoDraft by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep the editor on screen behind the system picker, even while importing.
    // Persist only the external picker state, not an interrupted import job.
    var photoPickerOpen by rememberSaveable { mutableStateOf(false) }
    var launchPhotoPicker by remember { mutableStateOf(false) }
    var photoPreparing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (photoPickerOpen) {
            photoPickerOpen = false
            if (uri != null && photoDraft == null) {
                photoPreparing = true
                busy = true
                error = null
                scope.launch {
                    try {
                        photoDraft = store.importPhotoDraft(uri)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Não foi possível abrir a foto." }
                    finally {
                        photoPreparing = false
                        busy = false
                    }
                }
            }
        }
    }
    LaunchedEffect(launchPhotoPicker) {
        if (launchPhotoPicker) {
            // Commit the editor's layout and give it a frame to draw before
            // covering this activity with the external gallery.
            withFrameNanos { }
            withFrameNanos { }
            launchPhotoPicker = false
            try {
                picker.launch("image/*")
            } catch (_: Exception) {
                photoPickerOpen = false
                error = "Não foi possível abrir a galeria."
            }
        }
    }
    val draft = photoDraft
    val waitingForPhoto = photoPickerOpen || photoPreparing
    val showPhotoEditor = waitingForPhoto || draft != null
    BackHandler(enabled = !showPhotoEditor && page != "banner" && page != "wallpaper") { goBack() }
    Box(Modifier.fillMaxSize().reiAnixScreenFade(if (showPhotoEditor) "crop" else page)) {
    if (showPhotoEditor) {
        ReiAnixAvatarEditor(
            draft = draft?.let { store.photoDraftFile(it) },
            busy = busy || waitingForPhoto,
            error = error,
            loadingSelection = waitingForPhoto,
            onCancel = {
                if (!busy) {
                    photoDraft = null
                    error = null
                    if (draft != null) scope.launch { store.discardPhotoDraft(draft) }
                }
            },
            onSave = { crop ->
                if (!busy && !waitingForPhoto && draft != null) {
                    busy = true
                    error = null
                    scope.launch {
                        var imported: String? = null
                        try {
                            val result = store.importCroppedPhoto(draft, crop.left, crop.top, crop.size)
                            imported = result
                            store.update { it.copy(photo = result) }
                            photoDraft = null
                            store.discardPhotoDraft(draft)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Não foi possível salvar a foto." }
                        finally {
                            try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { store.discard(imported) }
                            } finally { busy = false }
                        }
                    }
                }
            },
        )
        return@Box
    }
    if (page == "banner" || page == "wallpaper") {
        ReiAnixWallpaperEditor(onBack = { page = "menu" }, banner = page == "banner")
        return@Box
    }
    val title = when (page) {
        "photo" -> "Avatares personalizados"
        "frame" -> "Moldura"
        "featured" -> "Anime em destaque"
        else -> "Editar perfil"
    }
    Column(Modifier.fillMaxSize().background(reiAnixScreenBackground()).imePadding().navigationBarsPadding()) {
        ReiAnixTopBar(title = title, navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { goBack() })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (page == "featured") {
            // Favorites can be large; do not compose all thumbnails at once.
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                item {
                    Text("Escolha um anime favorito para destacar no perfil.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileFeaturedChoice("Nenhum", profile.featuredAnimeId == null, !busy) {
                        saveChange { it.copy(featuredAnimeId = null) }
                    }
                }
                items(favorites, key = { it.id }) { anime ->
                    ProfileFeaturedChoice(anime.title, profile.featuredAnimeId == anime.id, !busy) {
                        saveChange { it.copy(featuredAnimeId = anime.id) }
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (page) {
                    "photo" -> {
                        ProfileAvatar(profile, store, 112.dp)
                        if (profile.photo != null) TextButton(enabled = !busy, onClick = {
                            saveChange { it.copy(photo = null) }
                        }) { Text("Remover foto") }
                        Text("Escolha um avatar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                        val selectedAvatarId = avatarPresetFor(profile.avatar).id
                        presets.chunked(4).forEach { group ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                group.forEach { p ->
                                    val selected = selectedAvatarId == p.id && profile.photo == null
                                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f).clickable(enabled = !busy) {
                                            saveChange { it.copy(avatar = p.id, photo = null) }
                                        }.padding(vertical = 8.dp)) {
                                        ProfileAvatar(profile.copy(avatar = p.id, photo = null,
                                            frame = if (selected) "accent" else "none"), store, 48.dp)
                                        Text(p.label, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    "frame" -> {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)
                                .testTag("profile-frame-preview"),
                            contentAlignment = Alignment.Center,
                        ) {
                            ProfileAvatar(profile, store, 148.dp)
                        }
                        Text(
                            "Escolha a moldura da foto do perfil.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        profileFrameOptions.forEach { option ->
                            val selected = profile.frame == option.id
                            val shape = RoundedCornerShape(18.dp)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(shape)
                                    .background(if (selected) Color(0xFF101C32) else Color.Transparent)
                                    .border(
                                        if (selected) 1.dp else 0.dp,
                                        if (selected) Color(0xFF3E84FF) else Color.Transparent,
                                        shape,
                                    )
                                    .clickable(enabled = !busy) { saveChange { it.copy(frame = option.id) } }
                                    .testTag("profile-frame-option-" + option.id)
                                    .heightIn(min = 76.dp)
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                ProfileAvatar(profile.copy(frame = option.id), store, 58.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                RadioButton(selected = selected, onClick = null, enabled = !busy)
                            }
                        }
                    }
                    else -> {
                        ProfileBannerHeader(
                            profile = profile,
                            store = store,
                            showCamera = true,
                            enabled = !busy,
                            onEditPhoto = {
                                leaveMenu {
                                    photoPickerOpen = true
                                    launchPhotoPicker = true
                                }
                            },
                        )
                        Text("Prévia do perfil", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40); scheduleTextSave() },
                            label = { Text("Nome do usuário") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = bio,
                            onValueChange = { bio = it.take(160); scheduleTextSave() },
                            label = { Text("Biografia") },
                            maxLines = 4,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HorizontalDivider(color = Color(0xFF292929))
                        Spacer(Modifier.height(4.dp))
                        CustomizationCategory(Icons.Outlined.Person, "Avatares personalizados",
                            "Escolha um avatar para seu perfil", !busy) { leaveMenu { page = "photo" } }
                        CustomizationCategory(Icons.Outlined.CropSquare, "Moldura",
                            "Personalize o contorno da foto", !busy) { leaveMenu { page = "frame" } }
                        CustomizationCategory(Icons.Outlined.Image, "Banner do perfil",
                            "Escolha e personalize o banner", !busy) { leaveMenu { page = "banner" } }
                        CustomizationCategory(Icons.Outlined.Wallpaper, "Papel de parede",
                            "Personalize o fundo do ReiAnix", !busy) { leaveMenu { page = "wallpaper" } }
                        CustomizationCategory(Icons.Outlined.StarBorder, "Anime em destaque",
                            "Escolha seu anime favorito", !busy) { leaveMenu { page = "featured" } }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
    }
    }
}

@Composable
private fun CustomizationCategory(icon: ImageVector, title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileFeaturedChoice(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
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
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<PlayerLocalMetadataStore.FavoriteScene?>(null) }
    var saving by remember { mutableStateOf(false) }
    val titles = remember(records) { records.associate { it.id to it.title } }
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
    val visible = scenes.filter { scene ->
        val episode = matches[scene.mediaId]
        listOf(scene.note.text, scene.note.animeTitle, scene.note.episodeLabel, episode?.let { titles[it.animeId] }.orEmpty())
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().background(reiAnixScreenBackground()).navigationBarsPadding()) {
        ReiAnixTopBar("Cenas favoritas", subtitle = "${scenes.size} cenas salvas",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = onBack, actions = {
                IconButton(onClick = { search = !search; if (!search) query = "" }) { Icon(Icons.Outlined.Search, "Pesquisar cenas") }
            })
        if (search) ProfileMediaSearch(query) { query = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && visible.isEmpty()) Text(if (scenes.isEmpty()) "Nenhuma cena salva. Salve trechos no player do ReiAnix." else "Nenhuma cena encontrada.", Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visible, key = { it.note.id }) { scene ->
                val episode = matches[scene.mediaId]
                val note = scene.note
                var menu by remember { mutableStateOf(false) }
                val playable = episode != null && note.clipEndMs != null && !saving
                Card(Modifier.fillMaxWidth().clickable(enabled = playable) {
                    episode?.let { request = viewModel.openEpisode(it.id, it.animeId, favoriteSceneId = note.id) }
                }, colors = CardDefaults.cardColors(
                    containerColor = com.reianix.reianix_local.ui.theme.reiAnixContentBackground(MaterialTheme.colorScheme.surfaceContainer),
                    contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(116.dp).aspectRatio(16f / 9f)) {
                            ProfileVideoFrame(episode?.path, note.timestampMs, episode?.modifiedAt.toString(),
                                Modifier.fillMaxSize(), exactScene = true)
                            Icon(Icons.Outlined.PlayCircleOutline, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp))
                            note.clipEndMs?.let { end ->
                                Text(sceneTime((end - note.timestampMs).coerceAtLeast(0L)),
                                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                        .background(Color.Black.copy(alpha = .75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(note.text, style = MaterialTheme.typography.titleMedium)
                            val animeTitle = episode?.let { titles[it.animeId] } ?: note.animeTitle
                            Text(animeTitle.ifBlank { "Anime local" }, style = MaterialTheme.typography.bodySmall)
                            Text(if (episode != null) profileEpisodeLabel(episode.number, episode.episodeTitle, episode.fileName)
                                else note.episodeLabel.ifBlank { "Episódio local" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(sceneTime(note.timestampMs) + (note.clipEndMs?.let { " – " + sceneTime(it) } ?: " · Marcador"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (episode == null) Text("Arquivo original indisponível", style = MaterialTheme.typography.labelSmall)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !saving) { Icon(Icons.Outlined.MoreVert, "Opções da cena") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Editar") }, enabled = episode != null && note.clipEndMs != null,
                                    onClick = { menu = false; edit = scene })
                                DropdownMenuItem(text = { Text("Excluir") }, onClick = { menu = false; delete = scene })
                            }
                        }
                    }
                }
            }
        }
    }
    edit?.let { scene ->
        val episode = matches[scene.mediaId]
        val note = scene.note
        var name by remember(note.id) { mutableStateOf(note.text) }
        var from by remember(note.id) { mutableStateOf((note.timestampMs / 1000.0).toString()) }
        var to by remember(note.id) { mutableStateOf(((note.clipEndMs ?: note.timestampMs) / 1000.0).toString()) }
        val start = from.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() }
        val end = to.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() }
        val duration = episode?.duration?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }
            ?: note.clipEndMs ?: 0L
        AlertDialog(onDismissRequest = { if (!saving) edit = null }, title = { Text("Editar cena") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome da cena") })
                    OutlinedTextField(from, { from = it }, singleLine = true, label = { Text("Início (segundos)") })
                    OutlinedTextField(to, { to = it }, singleLine = true, label = { Text("Fim (segundos)") })
                    Text("Duração disponível: " + sceneTime(duration), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = !saving && start != null && end != null && end > start && end <= duration,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                store.saveFavoriteClip(scene.mediaId, requireNotNull(start), requireNotNull(end), duration,
                                    name, episode?.let { titles[it.animeId] } ?: note.animeTitle,
                                    note.episodeLabel, existingId = note.id)
                            }
                            edit = null
                            reload++
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Não foi possível editar a cena."; edit = null }
                        finally { saving = false }
                    }
                }) { Text("Salvar") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { edit = null }) { Text("Cancelar") } })
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
