package com.reiflix.reiflix_local.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import java.util.Locale

/**
 * Flat, vertically scrolling player options list. Every existing player option
 * occupies its own row, following the supplied YouTube-style layout while
 * keeping the ReiAnix AMOLED-black visual language.
 */
@Composable
fun ReiAnixNativePlayerOptionsPanel(
    state: ReiAnixNativePlayerUiState,
    showPictureInPicture: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onPlaybackSpeed: () -> Unit,
    onRestart: () -> Unit,
    onMarkers: () -> Unit,
    onAutoplayChanged: (Boolean) -> Unit,
    onGesturesChanged: (Boolean) -> Unit,
    onVolumeGestureChanged: (Boolean) -> Unit,
    onBrightnessGestureChanged: (Boolean) -> Unit,
    onDoubleTapChanged: (Boolean) -> Unit,
    onLongPressChanged: (Boolean) -> Unit,
    onPictureInPicture: () -> Unit,
    onInformation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        PlayerOptionActionRow(
            icon = Icons.Filled.SkipPrevious,
            label = "Anterior",
            enabled = state.canPrevious && !state.episodeTransitionInProgress,
            onClick = onPrevious,
        )
        PlayerOptionActionRow(
            icon = Icons.Filled.SkipNext,
            label = "Próximo episódio",
            enabled = state.canNext && !state.episodeTransitionInProgress,
            onClick = onNext,
        )
        PlayerOptionActionRow(
            icon = Icons.Filled.GraphicEq,
            label = "Áudio",
            value = "Selecionar",
            enabled = !state.episodeTransitionInProgress,
            onClick = onAudio,
        )
        PlayerOptionActionRow(
            icon = Icons.Filled.ClosedCaption,
            label = "Legendas",
            value = "Selecionar",
            enabled = !state.episodeTransitionInProgress,
            onClick = onSubtitles,
        )
        PlayerOptionActionRow(
            icon = Icons.Filled.Speed,
            label = "Velocidade",
            value = String.format(Locale.US, "%.2fx", state.playbackSpeed),
            enabled = !state.episodeTransitionInProgress,
            onClick = onPlaybackSpeed,
        )
        PlayerOptionActionRow(
            icon = Icons.Filled.Replay,
            label = "Reiniciar",
            enabled = !state.episodeTransitionInProgress,
            onClick = onRestart,
        )
        PlayerOptionActionRow(
            icon = Icons.Filled.BookmarkBorder,
            label = "Marcadores",
            onClick = onMarkers,
        )
        PlayerOptionToggleRow(
            icon = Icons.Filled.PlayCircle,
            label = "Autoplay",
            checked = state.autoplayNext,
            onCheckedChange = onAutoplayChanged,
        )
        PlayerOptionToggleRow(
            icon = Icons.Filled.Gesture,
            label = "Gestos",
            checked = state.horizontalSeekEnabled,
            description = "Ativar ou desativar gestos horizontais de busca temporal",
            onCheckedChange = onGesturesChanged,
        )
        PlayerOptionToggleRow(
            icon = Icons.Filled.VolumeUp,
            label = "Volume",
            checked = state.volumeGesturesEnabled,
            description = "Ativar ou desativar o gesto de volume",
            onCheckedChange = onVolumeGestureChanged,
        )
        PlayerOptionToggleRow(
            icon = Icons.Filled.Brightness6,
            label = "Brilho",
            checked = state.brightnessGesturesEnabled,
            description = "Ativar ou desativar o gesto de brilho",
            onCheckedChange = onBrightnessGestureChanged,
        )
        PlayerOptionToggleRow(
            icon = Icons.Filled.TouchApp,
            label = "Double Tap",
            checked = state.doubleTapEnabled,
            onCheckedChange = onDoubleTapChanged,
        )
        PlayerOptionToggleRow(
            icon = Icons.Filled.PanTool,
            label = "Pressão",
            checked = state.longPressEnabled,
            description = "Ativar ou desativar a pressão longa",
            onCheckedChange = onLongPressChanged,
        )
        if (showPictureInPicture) {
            PlayerOptionActionRow(
                icon = Icons.Filled.PictureInPictureAlt,
                label = "PIP",
                onClick = onPictureInPicture,
            )
        }
        PlayerOptionActionRow(
            icon = Icons.Filled.Info,
            label = "Informações",
            onClick = onInformation,
        )
    }
}

@Composable
private fun PlayerOptionActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ReiAnixTokens.Colors.playerControl.copy(alpha = contentAlpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = ReiAnixTokens.Colors.playerControl.copy(alpha = contentAlpha),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                color = ReiAnixTokens.Colors.textMuted.copy(alpha = contentAlpha),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = ReiAnixTokens.Colors.textMuted.copy(alpha = contentAlpha),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PlayerOptionToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    description: String = label,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ReiAnixTokens.Colors.playerControl,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = ReiAnixTokens.Colors.playerControl,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                this.contentDescription = description
            },
        )
    }
}
