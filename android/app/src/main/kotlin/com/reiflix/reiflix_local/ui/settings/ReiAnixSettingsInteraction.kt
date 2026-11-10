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

/** Full-row pressed highlight, matching CloudStream's selectableItemBackground. */
fun Modifier.settingsClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val color = MaterialTheme.colorScheme.onSurface
    val alpha by animateFloatAsState(
        if (enabled && (pressed || pending || focused)) 0.32f else 0f,
        animationSpec = tween(if (pressed || pending || focused) 50 else 180), label = "settingsPress",
    )
    drawBehind { drawRect(color.copy(alpha = alpha)) }
        .clickable(enabled = enabled, role = role, interactionSource = interactions, indication = null) {
            if (!pending) scope.launch {
                pending = true
                try {
                    // Keep the confirmation visible even on a quick tap before navigation.
                    delay(100)
                    if (currentEnabled) currentClick()
                } finally { pending = false }
            }
        }
}

internal fun settingsIcon(key: String): ImageVector = when {
    key.contains("backup", true) || key.contains("restaur", true) -> Icons.Filled.Backup
    key.contains("export", true) || key.contains("import", true) -> Icons.Filled.ImportExport
    key.contains("cache", true) || key.contains("limpar", true) -> Icons.Filled.DeleteOutline
    key.contains("diagn", true) || key.contains("verific", true) -> Icons.Filled.BugReport
    key.contains("theme", true) || key.contains("tema", true) -> Icons.Filled.Palette
    key.contains("poster", true) || key.contains("card_size", true) || key.contains("grid", true) -> Icons.Filled.GridView
    key.contains("thumbnail", true) || key.contains("artwork", true) -> Icons.Filled.Image
    key.contains("metadata", true) -> Icons.Filled.ManageSearch
    key.contains("subtitle", true) || key.contains("legenda", true) -> Icons.Filled.Subtitles
    key.contains("audio", true) || key.contains("volume", true) -> Icons.Filled.VolumeUp
    key.contains("brightness", true) -> Icons.Filled.BrightnessMedium
    key.contains("speed", true) -> Icons.Filled.Speed
    key.contains("pip", true) -> Icons.Filled.PictureInPictureAlt
    key.contains("rotation", true) -> Icons.Filled.ScreenRotation
    key.contains("immersive", true) || key.contains("aspect", true) || key.contains("resolution", true) || key.contains("frame_rate", true) -> Icons.Filled.AspectRatio
    key.contains("zoom", true) -> Icons.Filled.ZoomIn
    key.contains("lock", true) -> Icons.Filled.Lock
    key.contains("gestures", true) || key.contains("tap", true) || key.contains("press", true) -> Icons.Filled.TouchApp
    key.contains("sort", true) -> Icons.Filled.Sort
    key.contains("page_size", true) -> Icons.Filled.ViewList
    key.contains("continue", true) || key.contains("resume", true) -> Icons.Filled.History
    key.contains("autoplay", true) -> Icons.Filled.SkipNext
    key.contains("confirm", true) -> Icons.Filled.Security
    key.contains("folder", true) || key.contains("pasta", true) -> Icons.Filled.Folder
    key.startsWith("player") -> Icons.Filled.PlayArrow
    else -> Icons.Filled.Settings
}

@Composable
internal fun SettingsLeadingIcon(key: String, modifier: Modifier = Modifier) {
    Icon(settingsIcon(key), contentDescription = null,
        modifier = modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
}
