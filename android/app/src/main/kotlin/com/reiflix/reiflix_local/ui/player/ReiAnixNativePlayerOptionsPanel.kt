package com.reiflix.reiflix_local.ui.player

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import java.util.Locale

private enum class PlayerOptionsPage {
    MAIN,
    GESTURES,
    OTHER,
}

/**
 * YouTube-style player settings surface using only ReiAnix playback features.
 * The main page keeps navigation, autoplay, tracks and playback options visible;
 * gesture toggles and secondary destinations live in their own subpages.
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
    onHistory: () -> Unit,
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
    var page by remember { mutableStateOf(PlayerOptionsPage.MAIN) }
    BackHandler(enabled = page != PlayerOptionsPage.MAIN) {
        page = PlayerOptionsPage.MAIN
    }

    val panelShape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .shadow(16.dp, panelShape, clip = false)
            .clip(panelShape)
            .background(Color(0xE61B1B1D))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        when (page) {
            PlayerOptionsPage.MAIN -> {
                PlayerOptionEpisodeNavigationRow(
                    canPrevious = state.canPrevious && !state.episodeTransitionInProgress,
                    canNext = state.canNext && !state.episodeTransitionInProgress,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
                PlayerOptionToggleRow(
                    icon = Icons.Filled.PlayCircle,
                    label = "Autoplay",
                    checked = state.autoplayNext,
                    onCheckedChange = onAutoplayChanged,
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
                    showChevron = false,
                    onClick = onRestart,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Gesture,
                    label = "Gestos",
                    trailing = "Configurar",
                    onClick = { page = PlayerOptionsPage.GESTURES },
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.MoreHoriz,
                    label = "Outras opções",
                    onClick = { page = PlayerOptionsPage.OTHER },
                )
                if (showPictureInPicture) {
                    PlayerOptionActionRow(
                        icon = Icons.Filled.PictureInPictureAlt,
                        label = "PIP",
                        showChevron = false,
                        onClick = onPictureInPicture,
                    )
                }
            }

            PlayerOptionsPage.GESTURES -> {
                PlayerOptionsSubpageHeader(
                    title = "Gestos",
                    onBack = { page = PlayerOptionsPage.MAIN },
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
            }

            PlayerOptionsPage.OTHER -> {
                PlayerOptionsSubpageHeader(
                    title = "Outras opções",
                    onBack = { page = PlayerOptionsPage.MAIN },
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.History,
                    label = "Histórico",
                    showChevron = false,
                    onClick = onHistory,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.BookmarkBorder,
                    label = "Marcadores",
                    showChevron = false,
                    onClick = onMarkers,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Info,
                    label = "Informações",
                    showChevron = false,
                    onClick = onInformation,
                )
            }
        }
    }
}

@Composable
private fun PlayerOptionEpisodeNavigationRow(
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerOptionNavigationAction(
            icon = Icons.Filled.SkipPrevious,
            label = "Anterior",
            enabled = canPrevious,
            onClick = onPrevious,
            modifier = Modifier.weight(1f),
        )
        PlayerOptionNavigationAction(
            icon = Icons.Filled.SkipNext,
            label = "Próximo",
            enabled = canNext,
            onClick = onNext,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlayerOptionNavigationAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerOptionsSubpageHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onBack)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = "Voltar",
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PlayerOptionActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.42f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = contentAlpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = contentAlpha),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val trailingText = value ?: trailing
        if (!trailingText.isNullOrBlank()) {
            Text(
                text = trailingText,
                color = Color(0xFFD0D0D0).copy(alpha = contentAlpha),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color(0xFFC8C8C8).copy(alpha = contentAlpha),
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
            .heightIn(min = 50.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ReiAnixTokens.Colors.primary,
                checkedBorderColor = ReiAnixTokens.Colors.primary,
                uncheckedThumbColor = Color(0xFFBDBDBD),
                uncheckedTrackColor = Color(0xFF555555),
                uncheckedBorderColor = Color(0xFF777777),
            ),
            modifier = Modifier.semantics {
                this.contentDescription = description
            },
        )
    }
}
