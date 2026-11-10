package com.reiflix.reiflix_local.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** CloudStream-style touch feedback: react immediately rather than leaving a
 * delayed opaque highlight visible while the next settings page is opening.
 */
fun Modifier.settingsClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val currentClick by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    var blocked by remember { mutableStateOf(false) }
    val rippleColor = MaterialTheme.colorScheme.onSurface
    clickable(
        enabled = enabled,
        role = role,
        interactionSource = interactions,
        indication = androidx.compose.material3.ripple(color = rippleColor),
    ) {
        if (blocked) return@clickable
        blocked = true
        try {
            currentClick()
        } finally {
            // Only debounce repeated taps; never delay the actual navigation.
            scope.launch {
                delay(180)
                blocked = false
            }
        }
    }
}

/** Explicit icons avoid substring collisions (e.g., every backup or subtitle option).
 * Each action has its own symbol; unknown future keys use the safe Settings fallback.
 */
internal fun settingsIcon(key: String): ImageVector = when (key) {
    "app.confirm_destructive" -> Icons.Filled.Security
    "player.reset" -> Icons.Filled.RestartAlt
    "metadata.anilist_enabled" -> Icons.Filled.Public
    "metadata.auto_match" -> Icons.Filled.AutoFixHigh
    "artwork.enabled" -> Icons.Filled.CloudDownload
    "artwork.cache_limit_mb" -> Icons.Filled.DataUsage
    "data-cache:artwork-cache" -> Icons.Filled.DeleteSweep
    "about:app" -> Icons.Filled.Info
    "library.sort_default" -> Icons.Filled.SortByAlpha
    "library.page_size" -> Icons.Filled.FormatListNumbered
    "folders" -> Icons.Filled.FolderOpen
    "appearance.theme" -> Icons.Filled.Palette
    "appearance.card_size" -> Icons.Filled.CropPortrait
    "library.grid_density" -> Icons.Filled.ViewModule
    "appearance.poster_size" -> Icons.Filled.PhotoSizeSelectSmall
    "appearance.show_thumbnails" -> Icons.Filled.Collections
    "library.continue_watching" -> Icons.Filled.VideoLibrary
    "library.continue_watching_limit" -> Icons.Filled.PlaylistPlay
    "player.autoplay_next" -> Icons.Filled.SkipNext
    "player.resume" -> Icons.Filled.PlayCircleOutline
    "player.default_speed" -> Icons.Filled.Speed
    "player.aspect_ratio" -> Icons.Filled.FitScreen
    "player.max_video_resolution" -> Icons.Filled.Hd
    "player.max_video_frame_rate" -> Icons.Filled.SlowMotionVideo
    "player.zoom_enabled" -> Icons.Filled.ZoomIn
    "player.lock_mode" -> Icons.Filled.Lock
    "player.double_tap_seek_seconds" -> Icons.Filled.Forward10
    "player.long_press_speed" -> Icons.Filled.FastForward
    "gestures.volume" -> Icons.Filled.VolumeUp
    "gestures.brightness" -> Icons.Filled.Brightness6
    "gestures.horizontal_seek" -> Icons.Filled.Swipe
    "gestures.double_tap" -> Icons.Filled.TouchApp
    "gestures.long_press" -> Icons.Filled.PanToolAlt
    "player.max_audio_channels" -> Icons.Filled.SurroundSound
    "audio.preferred_language" -> Icons.Filled.Translate
    "audio.preferred_subtitle_language" -> Icons.Filled.Language
    "audio.subtitle_scale" -> Icons.Filled.FormatSize
    "audio.subtitle_bottom_padding" -> Icons.Filled.VerticalAlignBottom
    "audio.subtitle_embedded_style" -> Icons.Filled.FormatColorText
    "audio.subtitles" -> Icons.Filled.Subtitles
    "player.immersive" -> Icons.Filled.Fullscreen
    "player.rotation" -> Icons.Filled.ScreenRotation
    "player.pip" -> Icons.Filled.PictureInPictureAlt
    "player.auto_hide_seconds" -> Icons.Filled.TimerOff
    "data-cache:actions" -> Icons.Filled.SettingsBackupRestore
    "backup:actions" -> Icons.Filled.Backup
    "data-cache:info" -> Icons.Filled.Storage
    "backup:info" -> Icons.Filled.CloudDone
    "diagnostic:info" -> Icons.Filled.BugReport
    "diagnostic:actions" -> Icons.Filled.FactCheck
    "diagnostic:reports" -> Icons.Filled.Assessment
    "diagnostic:timeline:heading" -> Icons.Filled.Timeline
    "Restaurar" -> Icons.Filled.Restore
    "Limpar cache de capas" -> Icons.Filled.CleaningServices
    "Exportar configurações" -> Icons.Filled.UploadFile
    "Importar configurações" -> Icons.Filled.Download
    "Restaurar configurações" -> Icons.Filled.RestorePage
    "Fazer backup" -> Icons.Filled.Save
    "Restaurar backup" -> Icons.Filled.RestoreFromTrash
    "Verificar integridade" -> Icons.Filled.Verified
    "Reconciliar arquivos" -> Icons.Filled.Sync
    "Executar diagnóstico geral" -> Icons.Filled.Troubleshoot
    "Importar logcat (ADB)" -> Icons.Filled.Article
    "Cancelar coleta atual" -> Icons.Filled.Cancel
    "Exportar relatório coletado" -> Icons.Filled.FileUpload
    "Limpar resultados e histórico" -> Icons.Filled.DeleteForever
    "Sistema Android" -> Icons.Filled.Android
    "Logcat disponível" -> Icons.Filled.Terminal
    "Armazenamento e permissões" -> Icons.Filled.FolderShared
    "Scanner e indexação" -> Icons.Filled.ManageSearch
    "SQLite e biblioteca" -> Icons.Filled.Dataset
    "Thumbnails, capas e cache" -> Icons.Filled.PhotoLibrary
    "Ponte e comunicação interna" -> Icons.Filled.Cable
    "Desempenho e estabilidade" -> Icons.Filled.Insights
    "Trocar conta" -> Icons.Filled.SwitchAccount
    "Sair" -> Icons.Filled.Logout
    "Adicionar pasta" -> Icons.Filled.CreateNewFolder
    "Pasta da biblioteca" -> Icons.Filled.Folder
    else -> Icons.Filled.Settings
}

@Composable
internal fun SettingsLeadingIcon(key: String, modifier: Modifier = Modifier) {
    Icon(settingsIcon(key), contentDescription = null,
        modifier = modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
}
