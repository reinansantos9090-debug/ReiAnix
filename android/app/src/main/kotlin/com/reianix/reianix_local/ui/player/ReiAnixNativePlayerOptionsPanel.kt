package com.reianix.reianix_local.ui.player

import com.reianix.reianix_local.ui.motion.reiAnixScreenFade
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Tune
import com.reianix.reianix_local.player.PlayerShortcut
import com.reianix.reianix_local.player.PlayerShortcutPolicy
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reianix.reianix_local.R
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import java.util.Locale

private enum class PlayerOptionsPage {
    MAIN,
    AUDIO,
    SUBTITLES,
    GESTURES,
    SHORTCUTS,
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
    resetSignal: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlaybackSpeed: () -> Unit,
    onSleepTimer: () -> Unit,
    onAudioTracks: () -> Unit,
    onSubtitles: () -> Unit,
    onRestart: () -> Unit,
    onHistory: () -> Unit,
    onMarkers: () -> Unit,
    onAutoplayChanged: (Boolean) -> Unit,
    onGesturesChanged: (Boolean) -> Unit,
    onBrightnessVolumeChanged: (Boolean) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onDoubleTapChanged: (Boolean) -> Unit,
    onLongPressChanged: (Boolean) -> Unit,
    onOpenWithExternal: () -> Unit,
    onPictureInPicture: () -> Unit,
    onInformation: () -> Unit,
    onFavoriteScenes: () -> Unit = {},
    onSubtitleAppearance: () -> Unit = {},
    onAudioOnlyChanged: (Boolean) -> Unit = {},
    onCaptureFrame: () -> Unit = {},
    onShortcutsChanged: (List<PlayerShortcut>) -> Unit = {},
) {
    var page by remember { mutableStateOf(PlayerOptionsPage.MAIN) }
    LaunchedEffect(resetSignal) {
        page = PlayerOptionsPage.MAIN
    }
    val panelShape = RoundedCornerShape(18.dp)
    val scrollState = key(page, resetSignal) { rememberScrollState() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .shadow(16.dp, panelShape, clip = false)
            .clip(panelShape)
            .background(Color(0xE61B1B1D))
            .reiAnixScreenFade(page)
            .verticalScroll(scrollState)
            .padding(vertical = 5.dp),
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
                    enabled = !state.favoriteClipMode,
                    onCheckedChange = onAutoplayChanged,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Speed,
                    label = "Velocidade",
                    value = String.format(Locale.US, "%.1fx", state.playbackSpeed),
                    enabled = !state.episodeTransitionInProgress,
                    onClick = onPlaybackSpeed,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Timer,
                    label = "Temporizador",
                    value = state.sleepTimerLabel,
                    enabled = !state.episodeTransitionInProgress,
                    onClick = onSleepTimer,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.VolumeUp,
                    label = "Áudio",
                    value = if (state.audioOnly) "Somente áudio" else if (state.audioTrackSelectionEnabled) state.audioTrackLabel else "Indisponível",
                    enabled = !state.episodeTransitionInProgress,
                    onClick = { page = PlayerOptionsPage.AUDIO },
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Subtitles,
                    label = "Legendas",
                    value = if (state.subtitleTrackSelectionEnabled) state.subtitleTrackLabel else "Indisponível",
                    enabled = !state.episodeTransitionInProgress,
                    onClick = { page = PlayerOptionsPage.SUBTITLES },
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Gesture,
                    label = "Gestos",
                    onClick = { page = PlayerOptionsPage.GESTURES },
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.OpenInNew,
                    label = "Abrir com...",
                    enabled = !state.favoriteClipMode && !state.episodeTransitionInProgress,
                    onClick = onOpenWithExternal,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.BookmarkBorder,
                    label = "Salvar cena",
                    onClick = onFavoriteScenes,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.CameraAlt,
                    label = "Capturar imagem",
                    enabled = !state.favoriteClipMode && !state.episodeTransitionInProgress,
                    showChevron = false,
                    onClick = onCaptureFrame,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Tune,
                    label = "Personalizar atalhos",
                    value = "${state.shortcuts.size}/5",
                    onClick = { page = PlayerOptionsPage.SHORTCUTS },
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
                PlayerOptionActionRow(
                    icon = Icons.Filled.Replay,
                    label = "Reiniciar",
                    enabled = !state.episodeTransitionInProgress,
                    showChevron = false,
                    onClick = onRestart,
                )
            }

            PlayerOptionsPage.AUDIO -> {
                PlayerOptionsSubpageHeader(
                    title = "Áudio",
                    onBack = { page = PlayerOptionsPage.MAIN },
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.VolumeUp,
                    label = "Selecionar faixa de áudio",
                    value = if (state.audioTrackSelectionEnabled) state.audioTrackLabel else "Indisponível",
                    enabled = state.audioTrackSelectionEnabled && !state.episodeTransitionInProgress,
                    onClick = onAudioTracks,
                )
                PlayerOptionToggleRow(
                    icon = Icons.Filled.VolumeUp,
                    label = "Somente áudio",
                    checked = state.audioOnly,
                    enabled = !state.episodeTransitionInProgress && (state.audioOnly || state.audioTrackSelectionEnabled),
                    onCheckedChange = onAudioOnlyChanged,
                )
            }

            PlayerOptionsPage.SUBTITLES -> {
                PlayerOptionsSubpageHeader(
                    title = "Legendas",
                    onBack = { page = PlayerOptionsPage.MAIN },
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Subtitles,
                    label = "Selecionar legendas",
                    value = if (state.subtitleTrackSelectionEnabled) state.subtitleTrackLabel else "Indisponível",
                    enabled = state.subtitleTrackSelectionEnabled && !state.episodeTransitionInProgress,
                    onClick = onSubtitles,
                )
                PlayerOptionActionRow(
                    icon = Icons.Filled.Subtitles,
                    label = "Personalizar legendas",
                    enabled = !state.episodeTransitionInProgress,
                    onClick = onSubtitleAppearance,
                )
            }

            PlayerOptionsPage.GESTURES -> {
                PlayerOptionsSubpageHeader(
                    title = "Gestos",
                    onBack = { page = PlayerOptionsPage.MAIN },
                )
                PlayerOptionToggleRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_gesture_horizontal_seek),
                    label = "Avançar e voltar",
                    checked = state.horizontalSeekEnabled,
                    description = "Ativar ou desativar o avanço e retrocesso por gesto horizontal",
                    onCheckedChange = onGesturesChanged,
                )
                PlayerOptionToggleRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_gesture_vertical_adjust),
                    label = "Brilho e volume",
                    checked = state.volumeGesturesEnabled || state.brightnessGesturesEnabled,
                    description = "Deslize verticalmente à direita para ajustar o volume e à esquerda para ajustar o brilho",
                    onCheckedChange = onBrightnessVolumeChanged,
                )
                PlayerOptionToggleRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_gesture_adjust_fill),
                    label = "Ajustar e preencher",
                    checked = state.zoomEnabled,
                    description = "Permite ajustar e preencher a tela com o vídeo usando gesto de pinça",
                    onCheckedChange = onZoomChanged,
                )
                PlayerOptionToggleRow(
                    icon = Icons.Filled.TouchApp,
                    label = "Double Tap",
                    checked = state.doubleTapEnabled,
                    onCheckedChange = onDoubleTapChanged,
                )
                PlayerOptionToggleRow(
                    icon = ImageVector.vectorResource(R.drawable.ic_gesture_long_press),
                    label = "Pressão",
                    checked = state.longPressEnabled,
                    description = "Ativar ou desativar a pressão longa",
                    onCheckedChange = onLongPressChanged,
                )
            }

            PlayerOptionsPage.SHORTCUTS -> {
                PlayerOptionsSubpageHeader(
                    title = "Personalizar atalhos",
                    onBack = { page = PlayerOptionsPage.MAIN },
                )
                Text(
                    text = "Escolha até 5 atalhos. Segure e arraste os ativos para reorganizar. Opções fica sempre em último.",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                val currentShortcuts by rememberUpdatedState(state.shortcuts)
                val currentOnShortcutsChanged by rememberUpdatedState(onShortcutsChanged)
                val stepPx = with(LocalDensity.current) { 48.dp.toPx() }
                val liftShape = remember { RoundedCornerShape(12.dp) }
                var draggedShortcut by remember(page, resetSignal) { mutableStateOf<PlayerShortcut?>(null) }
                var draggedOffsetPx by remember(page, resetSignal) { mutableFloatStateOf(0f) }
                // Preserve the player order. Options stays pinned, inactive rows stay last.
                val ordered = state.shortcuts + PlayerShortcut.entries.filterNot { it in state.shortcuts }
                ordered.forEachIndexed { position, shortcut ->
                    key(shortcut) {
                        val isSelected = shortcut in state.shortcuts
                        val draggable = isSelected && shortcut != PlayerShortcut.OPTIONS
                        val isDragging = draggedShortcut == shortcut

                        // Animate displaced neighbours instead of jumping to new slots.
                        val placementOffset = remember { Animatable(0f) }
                        var previousPosition by remember { mutableIntStateOf(position) }
                        LaunchedEffect(position) {
                            if (previousPosition != position) {
                                val fromPx = (previousPosition - position) * stepPx
                                previousPosition = position
                                if (isDragging) {
                                    placementOffset.snapTo(0f)
                                } else {
                                    placementOffset.snapTo(fromPx)
                                    placementOffset.animateTo(
                                        0f, animationSpec = tween(210, easing = FastOutSlowInEasing),
                                    )
                                }
                            }
                        }
                        val movingOffset by animateFloatAsState(
                            targetValue = if (isDragging) draggedOffsetPx else 0f,
                            animationSpec = if (isDragging) snap() else tween(190, easing = FastOutSlowInEasing),
                            label = "Player shortcut drag offset",
                        )
                        val highlight by animateColorAsState(
                            targetValue = if (isDragging) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                            animationSpec = tween(150),
                            label = "Player shortcut drag highlight",
                        )
                        val lift by animateDpAsState(
                            targetValue = if (isDragging) 10.dp else 0.dp,
                            animationSpec = tween(150),
                            label = "Player shortcut drag elevation",
                        )
                        val scale by animateFloatAsState(
                            targetValue = if (isDragging) 1.025f else 1f,
                            animationSpec = tween(150),
                            label = "Player shortcut drag scale",
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .zIndex(if (isDragging || kotlin.math.abs(movingOffset) > 1f) 1f else 0f)
                                .graphicsLayer {
                                    translationY = placementOffset.value + movingOffset
                                    scaleX = scale
                                    scaleY = scale
                                    shadowElevation = lift.toPx()
                                    shape = liftShape
                                    clip = false
                                }
                                .background(highlight, liftShape)
                                .height(48.dp)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The full label area is draggable; the switch stays a separate
                            // touch target so turning a shortcut on/off cannot begin a drag.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .then(
                                        if (draggable) Modifier.pointerInput(shortcut, stepPx) {
                                            var gestureShortcuts = currentShortcuts
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    gestureShortcuts = currentShortcuts
                                                    draggedShortcut = shortcut
                                                    draggedOffsetPx = 0f
                                                },
                                                onDragEnd = { draggedShortcut = null },
                                                onDragCancel = { draggedShortcut = null },
                                                onDrag = { change, delta ->
                                                    change.consume()
                                                    draggedOffsetPx += delta.y
                                                    var nextShortcuts = gestureShortcuts
                                                    var steps = 0
                                                    while (kotlin.math.abs(draggedOffsetPx) >= stepPx / 2f &&
                                                        steps < PlayerShortcutPolicy.MAX_COUNT
                                                    ) {
                                                        val direction = if (draggedOffsetPx > 0f) 1 else -1
                                                        val next = PlayerShortcutPolicy.move(nextShortcuts, shortcut, direction)
                                                        if (next == nextShortcuts) {
                                                            draggedOffsetPx = direction * stepPx * 0.49f
                                                            break
                                                        }
                                                        nextShortcuts = next
                                                        draggedOffsetPx -= direction * stepPx
                                                        steps++
                                                    }
                                                    if (nextShortcuts != gestureShortcuts) {
                                                        gestureShortcuts = nextShortcuts
                                                        currentOnShortcutsChanged(nextShortcuts)
                                                    }
                                                },
                                            )
                                        } else Modifier,
                                    ),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = shortcut.displayName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                )
                            }
                            Switch(
                                checked = isSelected,
                                enabled = shortcut != PlayerShortcut.OPTIONS &&
                                    (isSelected || state.shortcuts.size < PlayerShortcutPolicy.MAX_COUNT),
                                onCheckedChange = { selected ->
                                    onShortcutsChanged(PlayerShortcutPolicy.toggle(state.shortcuts, shortcut, selected))
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = ReiAnixTokens.Colors.primary),
                            )
                        }
                    }
                }
                PlayerOptionActionRow(
                    icon = Icons.Filled.Replay,
                    label = "Restaurar 4 atalhos originais",
                    showChevron = false,
                    onClick = { onShortcutsChanged(PlayerShortcutPolicy.default) },
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
                    enabled = !state.favoriteClipMode,
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
            .clickable(
                interactionSource = null,
                indication = ripple(color = Color.White),
                enabled = enabled,
                onClick = onClick,
            )
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
            .clickable(
                interactionSource = null,
                indication = ripple(color = Color.White),
                onClick = onBack,
            )
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
            .heightIn(min = 46.dp)
            .clickable(
                interactionSource = null,
                indication = ripple(color = Color.White),
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 2.dp)
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
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp, vertical = 2.dp)
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
            enabled = enabled,
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
