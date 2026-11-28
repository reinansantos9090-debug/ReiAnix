package com.reianix.reianix_local.ui.profile

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

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
            catch (_: Exception) { error = context.getString(R.string.profile_art_apply_error) }
            finally {
                try { withContext(NonCancellable) { store.discard(imported) } }
                finally { busy = false }
            }
        }
    }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        ReiAnixTopBar(if (banner) stringResource(R.string.profile_banner) else stringResource(R.string.profile_wallpaper),
            navigationIcon = Icons.AutoMirrored.Filled.ArrowBack, onNavigationClick = { leave() },
            actions = {
                if (saved.imageName != null && selected == null) {
                    IconButton(onClick = { confirmRemove = true }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteOutline, if (banner) stringResource(R.string.profile_banner_remove) else stringResource(R.string.profile_wallpaper_remove))
                    }
                }
            })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (selected != null) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val ratio = if (banner) 3f else 9f / 16f
                val width = minOf(maxWidth, maxHeight * ratio)
                AsyncImage(model = ReiAnixArtPack.model(selected), contentDescription = localizedArtTitle(selected),
                    modifier = Modifier.size(width, width / ratio).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop)
            }
            Text(localizedArtTitle(selected), modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                style = MaterialTheme.typography.titleMedium)
            Button(onClick = { selectArt(selected) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp)) {
                Text(if (banner) stringResource(R.string.profile_banner_apply) else stringResource(R.string.profile_wallpaper_apply))
            }
        } else {
            val columns = if (banner) 2 else 3
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saved.imageName != null) {
                    item {
                        val current = rememberWallpaperImage(store, saved)
                        Text(if (banner) stringResource(R.string.profile_banner_current) else stringResource(R.string.profile_wallpaper_current),
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
                    Text(if (banner) stringResource(R.string.profile_banner_choose) else stringResource(R.string.profile_wallpaper_choose),
                        style = MaterialTheme.typography.titleMedium)
                }
                items(arts.chunked(columns), key = { it.first().id }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { art ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy) { selectedId = art.id }) {
                                AsyncImage(model = ReiAnixArtPack.model(art), contentDescription = localizedArtTitle(art),
                                    modifier = Modifier.fillMaxWidth().aspectRatio(if (banner) 2f else 9f / 16f)
                                        .clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                Text(localizedArtTitle(art), style = MaterialTheme.typography.labelSmall,
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
            title = { Text(if (banner) stringResource(R.string.profile_banner_remove_question) else stringResource(R.string.profile_wallpaper_remove_question)) },
            text = { Text(stringResource(R.string.tail_wallpaper_default)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmRemove = false
                    busy = true
                    error = null
                    scope.launch {
                        try { store.save(ReiAnixWallpaper()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = context.getString(R.string.profile_art_remove_error) }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.extra_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.locale_cancel)) } })
    }
}


@Composable
private fun localizedArtTitle(art: PersonalArt): String = when (art.id) {
    "samurai_rubro_cinematico" -> stringResource(R.string.art_samurai_rubro_cinematico)
    "fuji_sakura_torii" -> stringResource(R.string.art_fuji_sakura_torii)
    "oceano_azul" -> stringResource(R.string.art_oceano_azul)
    "chuva_neon" -> stringResource(R.string.art_chuva_neon)
    "cidade_dourada" -> stringResource(R.string.art_cidade_dourada)
    "montanha_nevada" -> stringResource(R.string.art_montanha_nevada)
    "quarto_gamer" -> stringResource(R.string.art_quarto_gamer)
    "ruinas_submersas" -> stringResource(R.string.art_ruinas_submersas)
    "sakura_lunar" -> stringResource(R.string.art_sakura_lunar)
    "samurai_lua_rubra" -> stringResource(R.string.art_samurai_lua_rubra)
    "ruas_neon_violeta" -> stringResource(R.string.art_ruas_neon_violeta)
    "eclipse_dourado" -> stringResource(R.string.art_eclipse_dourado)
    "costa_estelar_turquesa" -> stringResource(R.string.art_costa_estelar_turquesa)
    "cidade_chuvosa_violeta" -> stringResource(R.string.art_cidade_chuvosa_violeta)
    "cafe_chuvoso" -> stringResource(R.string.art_cafe_chuvoso)
    "caminho_sakura" -> stringResource(R.string.art_caminho_sakura)
    "costa_ao_entardecer" -> stringResource(R.string.art_costa_ao_entardecer)
    "crepusculo_urbano" -> stringResource(R.string.art_crepusculo_urbano)
    "magia_violeta" -> stringResource(R.string.art_magia_violeta)
    "viajante_lunar" -> stringResource(R.string.art_viajante_lunar)
    "samurai_escarlate" -> stringResource(R.string.art_samurai_escarlate)
    "toquio_violeta" -> stringResource(R.string.art_toquio_violeta)
    "manga_lunar" -> stringResource(R.string.art_manga_lunar)
    else -> art.title
}
