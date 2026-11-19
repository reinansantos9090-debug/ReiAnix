package com.reianix.reianix_local.ui.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
internal fun ReiAnixWallpaperEditor(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReiAnixWallpaperStore.from(context) }
    val saved by store.state.collectAsStateWithLifecycle()
    var image by rememberSaveable { mutableStateOf(saved.imageName) }
    var zoom by rememberSaveable { mutableStateOf(saved.zoom) }
    var x by rememberSaveable { mutableStateOf(saved.positionX) }
    var y by rememberSaveable { mutableStateOf(saved.positionY) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val draft = ReiAnixWallpaper(image, zoom, x, y).normalized()
    val scope = rememberCoroutineScope()
    fun leave() {
        if (busy) return
        busy = true
        scope.launch {
            try { store.discard(image); onBack() }
            finally { busy = false }
        }
    }
    BackHandler { leave() }
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
    val previewState = rememberWallpaperImage(store, draft)
    val preview = previewState.image
    val config = LocalConfiguration.current
    val ratio = (config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)).coerceIn(0.3f, 3f)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        ReiAnixTopBar("Papel de parede", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { leave() })
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
            Text("O brilho é ajustado automaticamente. Fundo apenas no Perfil e nos Ajustes.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Escolher papel de parede") }
            if (image != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(enabled = !busy, onClick = { zoom = 1f; x = 0.5f; y = 0.5f }) { Text("Reverter") }
                    TextButton(enabled = !busy, onClick = {
                        val previous = image
                        image = null; zoom = 1f; x = 0.5f; y = 0.5f
                        scope.launch { store.discard(previous) }
                    }) { Text("Remover papel de parede") }
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
                    catch (_: Exception) { error = "Não foi possível salvar o papel de parede." }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Salvar papel de parede")
            }
        }
    }
}
