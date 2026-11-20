package com.reianix.reianix_local.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.reianix.reianix_local.data.profile.PersonalArt
import com.reianix.reianix_local.data.profile.ReiAnixArtPack
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.theme.ReiAnixWallpaperLayer
import com.reianix.reianix_local.ui.theme.rememberWallpaperImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The built-in gallery is the entry screen. No gallery import or crop step. */
@Composable
internal fun ReiAnixWallpaperEditor(onBack: () -> Unit, banner: Boolean = false) {
    val context = LocalContext.current
    val store = remember(context, banner) {
        if (banner) ReiAnixWallpaperStore.banner(context) else ReiAnixWallpaperStore.from(context)
    }
    val saved by store.state.collectAsStateWithLifecycle()
    val arts = remember(banner) { ReiAnixArtPack.available(banner) }
    var selectedId by rememberSaveable(banner) { mutableStateOf<String?>(null) }
    val selected = arts.firstOrNull { it.id == selectedId }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun leave() {
        if (!busy) {
            if (selectedId != null) selectedId = null else onBack()
        }
    }
    fun selectArt(art: PersonalArt) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            var imported: String? = null
            try {
                imported = store.importPreset(art)
                store.save(ReiAnixWallpaper(imageName = imported))
                selectedId = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Não foi possível aplicar a arte." }
            finally {
                try { withContext(NonCancellable) { store.discard(imported) } }
                finally { busy = false }
            }
        }
    }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        ReiAnixTopBar(if (banner) "Banner do perfil" else "Papel de parede",
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { leave() },
            actions = {
                if (saved.imageName != null && selected == null) {
                    IconButton(onClick = { confirmRemove = true }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteOutline, if (banner) "Remover banner" else "Remover papel de parede")
                    }
                }
            })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (selected != null) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val ratio = if (banner) 3f else 9f / 16f
                val width = minOf(maxWidth, maxHeight * ratio)
                AsyncImage(model = ReiAnixArtPack.model(selected), contentDescription = selected.title,
                    modifier = Modifier.size(width, width / ratio).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop)
            }
            Text(selected.title, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                style = MaterialTheme.typography.titleMedium)
            Button(onClick = { selectArt(selected) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) {
                Text(if (banner) "Aplicar banner" else "Aplicar papel de parede")
            }
        } else {
            val columns = if (banner) 2 else 3
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saved.imageName != null) {
                    item {
                        val current = rememberWallpaperImage(store, saved)
                        Text(if (banner) "Banner atual" else "Papel de parede atual",
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(if (banner) 112.dp else 160.dp)
                            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            current.image?.let { ReiAnixWallpaperLayer(it, saved, Modifier.matchParentSize(), editing = true) }
                            if (current.loading) CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
                item {
                    Text(if (banner) "Escolher banner" else "Escolher papel de parede",
                        style = MaterialTheme.typography.titleMedium)
                }
                items(arts.chunked(columns), key = { it.first().id }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { art ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy) { selectedId = art.id }) {
                                AsyncImage(model = ReiAnixArtPack.model(art), contentDescription = art.title,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(if (banner) 2f else 9f / 16f)
                                        .clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                Text(art.title, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 5.dp, bottom = 4.dp), maxLines = 2)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false },
            title = { Text(if (banner) "Remover banner?" else "Remover papel de parede?") },
            text = { Text("O aplicativo voltará ao fundo padrão nesta opção.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmRemove = false
                    busy = true
                    error = null
                    scope.launch {
                        try { store.save(ReiAnixWallpaper()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Não foi possível remover a arte." }
                        finally { busy = false }
                    }
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } })
    }
}
