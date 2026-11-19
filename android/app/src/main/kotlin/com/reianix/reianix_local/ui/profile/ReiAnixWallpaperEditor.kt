package com.reianix.reianix_local.ui.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import com.reianix.reianix_local.ui.ReiAnixTopBar
import com.reianix.reianix_local.ui.theme.ReiAnixWallpaperLayer
import com.reianix.reianix_local.ui.theme.rememberWallpaperBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun ReiAnixWallpaperEditor(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReiAnixWallpaperStore.from(context) }
    val saved by store.state.collectAsStateWithLifecycle()
    var image by rememberSaveable { mutableStateOf(saved.imageName) }
    var zoom by rememberSaveable { mutableStateOf(saved.zoom) }
    var x by rememberSaveable { mutableStateOf(saved.positionX) }
    var y by rememberSaveable { mutableStateOf(saved.positionY) }
    var dim by rememberSaveable { mutableStateOf(saved.dim) }
    var blur by rememberSaveable { mutableStateOf(saved.blur) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val draft = ReiAnixWallpaper(image, zoom, x, y, dim, blur).normalized()
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
                    val imported = store.import(uri)
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
    val bitmap = rememberWallpaperBitmap(store, draft)
    val config = LocalConfiguration.current
    val ratio = (config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)).coerceIn(0.3f, 3f)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        ReiAnixTopBar("Papel de parede", navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { leave() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Personalize o fundo do aplicativo", style = MaterialTheme.typography.titleLarge)
            Text("O player de vídeo continuará com o fundo original.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.widthIn(max = if (ratio >= 1f) 420.dp else 220.dp).fillMaxWidth().aspectRatio(ratio)
                .clip(RoundedCornerShape(14.dp)).background(Color.Black).border(1.dp, Color(0xFF454545), RoundedCornerShape(14.dp))) {
                bitmap?.let { ReiAnixWallpaperLayer(it, draft, Modifier.matchParentSize()) }
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ReiAnix", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Biblioteca", color = Color.White)
                    repeat(2) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(3) {
                                Box(Modifier.weight(1f).aspectRatio(0.7f).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.16f)))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Início   Biblioteca   Buscar   Ajustes", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("Prévia · proporção da tela", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Escolher papel de parede") }
            if (image != null) {
                WallpaperSlider("Zoom", zoom, 1f..3f, (zoom * 100).roundToInt(), !busy) { zoom = it }
                WallpaperSlider("Posição horizontal", x, 0f..1f, (x * 100).roundToInt(), !busy) { x = it }
                WallpaperSlider("Posição vertical", y, 0f..1f, (y * 100).roundToInt(), !busy) { y = it }
                WallpaperSlider("Escurecimento", dim, 0f..1f, (dim * 100).roundToInt(), !busy) { dim = it }
                WallpaperSlider("Desfoque", blur, 0f..1f, (blur * 100).roundToInt(), !busy) { blur = it }
                Text("Em imagens claras, aumente o escurecimento para facilitar a leitura.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { zoom = 1f; x = 0.5f; y = 0.5f; dim = 0.65f; blur = 0f }) { Text("Restaurar ajustes") }
                TextButton(enabled = !busy, onClick = {
                    val previous = image
                    image = null; zoom = 1f; x = 0.5f; y = 0.5f; dim = 0.65f; blur = 0f
                    scope.launch { store.discard(previous) }
                }) { Text("Remover papel de parede") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(enabled = !busy && (image == null || bitmap != null), onClick = {
            busy = true
            error = null
            scope.launch {
                try { store.save(draft); onBack() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Não foi possível salvar o papel de parede." }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Salvar papel de parede")
        }
    }
}

@Composable
private fun WallpaperSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>,
    percent: Int, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text("$percent%")
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}
