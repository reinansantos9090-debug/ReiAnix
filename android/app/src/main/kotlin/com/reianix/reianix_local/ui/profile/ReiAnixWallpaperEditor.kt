package com.reianix.reianix_local.ui.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.reianix.reianix_local.data.profile.PersonalArt
import com.reianix.reianix_local.data.profile.ReiAnixArtPack
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.theme.ReiAnixWallpaperLayer
import com.reianix.reianix_local.ui.theme.rememberWallpaperImage
import com.reianix.reianix_local.ui.theme.transformWallpaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ReiAnixWallpaperEditor(onBack: () -> Unit, banner: Boolean = false) {
    val context = LocalContext.current
    val store = remember(context, banner) { if (banner) ReiAnixWallpaperStore.banner(context) else ReiAnixWallpaperStore.from(context) }
    val saved by store.state.collectAsStateWithLifecycle()
    var image by rememberSaveable { mutableStateOf(saved.imageName) }
    var zoom by rememberSaveable { mutableStateOf(saved.zoom) }
    var x by rememberSaveable { mutableStateOf(saved.positionX) }
    var y by rememberSaveable { mutableStateOf(saved.positionY) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val draft = ReiAnixWallpaper(image, zoom, x, y).normalized()
    val scope = rememberCoroutineScope()
    var showArts by rememberSaveable { mutableStateOf(false) }
    val arts = remember(banner) { ReiAnixArtPack.available(banner) }
    fun selectArt(art: PersonalArt) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            var imported: String? = null
            try {
                imported = store.importPreset(art)
                // Built-in art already has its final framing and needs no crop editor.
                store.save(ReiAnixWallpaper(imageName = imported))
                store.discard(image)
                onBack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                store.discard(imported)
                error = "Não foi possível aplicar a arte."
            } finally {
                busy = false
            }
        }
    }
    fun leave() {
        if (busy) return
        busy = true
        scope.launch {
            try { store.discard(image); onBack() }
            finally { busy = false }
        }
    }
    BackHandler { if (!busy && showArts) showArts = false else leave() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) {
            busy = true
            error = null
            scope.launch {
                try {
                    val imported = store.importWallpaper(uri)
                    val previous = image
                    image = imported
                    zoom = 1f; x = 0.5f; y = 0.5f
                    store.discard(previous)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Não foi possível importar a imagem." }
                finally { busy = false }
            }
        }
    }
    if (showArts) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
            ReiAnixTopBar(if (banner) "Banners otaku" else "Papéis de parede otaku",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = { if (!busy) showArts = false })
            Text("Escolha uma arte para aplicar imediatamente.",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(arts.chunked(if (banner) 1 else 2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { art ->
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { selectArt(art) }) {
                                AsyncImage(model = ReiAnixArtPack.model(art), contentDescription = art.title,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(if (banner) 3f else 9f / 16f)
                                        .clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                                Text(art.title, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(vertical = 6.dp))
                            }
                        }
                        if (!banner && row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        return
    }
    val previewState = rememberWallpaperImage(store, draft)
    val preview = previewState.image
    val config = LocalConfiguration.current
    val ratio = if (banner) 3f else (config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)).coerceIn(0.3f, 3f)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        ReiAnixTopBar(if (banner) "Banner do perfil" else "Papel de parede", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { leave() })
        Text("Arraste a imagem e use dois dedos para ampliar.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            if (preview == null) {
                if (previewState.loading) {
                    CircularProgressIndicator(Modifier.size(30.dp))
                } else {
                    Text(if (image != null) "Imagem indisponível. Escolha outra ou remova o papel de parede."
                        else "Escolha uma imagem da galeria.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val frameWidth = minOf(maxWidth, maxHeight * ratio)
                val frameHeight = frameWidth / ratio
                Box(Modifier.size(frameWidth, frameHeight).background(Color.Black)
                    .pointerInput(preview, busy) {
                        if (!busy) detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            val result = transformWallpaper(ReiAnixWallpaper(image, zoom, x, y), preview.bitmap.width, preview.bitmap.height,
                                size.width, size.height, centroid.x, centroid.y, pan.x, pan.y, gestureZoom)
                            zoom = result.zoom; x = result.positionX; y = result.positionY
                        }
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.85f))) {
                    ReiAnixWallpaperLayer(preview, draft, Modifier.matchParentSize(), editing = true)
                    Canvas(Modifier.matchParentSize()) {
                        val grid = Color.White.copy(alpha = 0.45f)
                        for (i in 1..2) {
                            val px = size.width * i / 3f
                            val py = size.height * i / 3f
                            drawLine(grid, Offset(px, 0f), Offset(px, size.height), 1.dp.toPx())
                            drawLine(grid, Offset(0f, py), Offset(size.width, py), 1.dp.toPx())
                        }
                        val l = 16.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 4)
                        val stroke = 3.dp.toPx()
                        for (corner in listOf(Offset(0f, 0f), Offset(size.width, 0f),
                            Offset(0f, size.height), Offset(size.width, size.height))) {
                            val dx = if (corner.x == 0f) l else -l
                            val dy = if (corner.y == 0f) l else -l
                            drawLine(Color.White, corner, corner + Offset(dx, 0f), stroke)
                            drawLine(Color.White, corner, corner + Offset(0f, dy), stroke)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (banner) "O banner é opcional e aparece somente no seu perfil." else "O brilho é ajustado automaticamente. O player mantém o fundo original.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Escolher da galeria") }
            OutlinedButton(onClick = { showArts = true }, enabled = !busy) {
                Text(if (banner) "Banners otaku" else "Papéis de parede otaku")
            }
            if (image != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(enabled = !busy, onClick = { zoom = 1f; x = 0.5f; y = 0.5f }) { Text("Reverter") }
                    TextButton(enabled = !busy, onClick = {
                        val previous = image
                        image = null; zoom = 1f; x = 0.5f; y = 0.5f
                        scope.launch { store.discard(previous) }
                    }) { Text(if (banner) "Remover banner" else "Remover papel de parede") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && (image == null || preview != null), onClick = {
                busy = true
                error = null
                // Capture the latest gesture positions directly, even if a frame has not recomposed.
                val selection = ReiAnixWallpaper(image, zoom, x, y).normalized()
                scope.launch {
                    try { store.save(selection); onBack() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Não foi possível salvar a imagem." }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (banner) "Salvar banner" else "Salvar papel de parede")
            }
        }
    }
}
