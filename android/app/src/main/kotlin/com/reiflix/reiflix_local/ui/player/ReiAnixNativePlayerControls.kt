package com.reiflix.reiflix_local.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import android.content.res.Configuration
import com.reiflix.reiflix_local.player.PlayerTimeFormatter
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

data class ReiAnixNativePlayerUiState(
    val title: String = "Episódio",
    val episodeLabel: String = "Episódio",
    val technicalLine: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val errorVisible: Boolean = false,
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val episodeTransitionInProgress: Boolean = false,
    val aspectLabel: String = "Ajustar",
    val playbackSpeed: Float = 1f,
    val safeTopPx: Int = 0,
    val safeBottomPx: Int = 0,
)

/**
 * Full-size convenience composable. NativePlayerActivity currently mounts the
 * top/center/bottom variants separately so GestureLayer keeps the video touch
 * surface whenever the controls are hidden.
 */
@Composable
fun ReiAnixNativePlayerControls(
    state: ReiAnixNativePlayerUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleLock: () -> Unit,
    onResize: () -> Unit,
    onSource: () -> Unit,
    onNext: () -> Unit,
) {
    if (!state.controlsVisible || state.errorVisible) return

    Box(modifier = Modifier.fillMaxSize()) {
        if (!state.locked) {
            ReiAnixNativePlayerTopControls(
                state = state,
                onBack = onBack,
            )
            ReiAnixNativePlayerCenterControls(
                state = state,
                onPlayPause = onPlayPause,
                onSeekRelative = onSeekRelative,
            )
        }
        ReiAnixNativePlayerBottomControls(
            state = state,
            onSeekTo = onSeekTo,
            onToggleLock = onToggleLock,
            onResize = onResize,
            onSource = onSource,
            onNext = onNext,
        )
    }
}

@Composable
fun ReiAnixNativePlayerTopControls(
    state: ReiAnixNativePlayerUiState,
    onBack: () -> Unit,
) {
    val safeTop = with(LocalDensity.current) { state.safeTopPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        ReiAnixTokens.Colors.playerScrim.copy(alpha = 0.88f),
                        ReiAnixTokens.Colors.playerScrim.copy(alpha = 0.50f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(
                start = ReiAnixTokens.PlayerDimensions.topHorizontalPadding,
                end = ReiAnixTokens.PlayerDimensions.topHorizontalPadding,
                top = safeTop + ReiAnixTokens.Spacing.xs,
                bottom = ReiAnixTokens.PlayerDimensions.topBottomPadding,
            ),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.TopStart)
                .semantics {
                    contentDescription = "Voltar"
                    role = Role.Button
                },
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = null,
                tint = ReiAnixTokens.Colors.playerControl,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(ReiAnixTokens.PlayerDimensions.topTitleWidthFraction)
                .padding(top = ReiAnixTokens.Spacing.xs / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.episodeLabel.ifBlank { "Episódio" },
                style = ReiAnixTokens.TypographyTokens.playerTopLabel,
                color = ReiAnixTokens.Colors.playerControl,
                maxLines = 1,
            )
            if (state.technicalLine.isNotBlank()) {
                Text(
                    text = state.technicalLine,
                    style = ReiAnixTokens.TypographyTokens.playerTechnical,
                    color = ReiAnixTokens.Colors.playerControl.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
            Text(
                text = state.title.ifBlank { "Episódio" },
                style = ReiAnixTokens.TypographyTokens.metadata,
                color = ReiAnixTokens.Colors.playerControl.copy(alpha = 0.98f),
                maxLines = 2,
            )
        }
    }
}

@Composable
fun ReiAnixNativePlayerCenterControls(
    state: ReiAnixNativePlayerUiState,
    seekSeconds: Long = 10L,
    onPlayPause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.PlayerDimensions.centerHorizontalPadding)
                .widthIn(max = ReiAnixTokens.PlayerDimensions.centerControlsMaxWidth),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                PlayerSeekIconButton(
                    icon = Icons.Filled.FastRewind,
                    seconds = seekSeconds,
                    description = "Voltar $seekSeconds segundos",
                    onClick = { onSeekRelative(-seekSeconds * 1_000L) },
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.centerButtonContainerSize),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(ReiAnixTokens.PlayerDimensions.centerButtonSize)
                            .semantics {
                                contentDescription = when {
                                    state.ended -> "Reproduzir novamente"
                                    state.isPlaying -> "Pausar"
                                    else -> "Reproduzir"
                                }
                                role = Role.Button
                            },
                    ) {
                        when {
                            state.isBuffering -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.bufferingIndicatorSize),
                                    color = ReiAnixTokens.Colors.playerControl,
                                    strokeWidth = ReiAnixTokens.PlayerDimensions.bufferingStroke,
                                )
                            }
                            state.ended -> {
                                Icon(
                                    imageVector = Icons.Filled.Replay,
                                    contentDescription = null,
                                    tint = ReiAnixTokens.Colors.playerControl,
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.playIconSize),
                                )
                            }
                            state.isPlaying -> {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = null,
                                    tint = ReiAnixTokens.Colors.playerControl,
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.playIconSize),
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = ReiAnixTokens.Colors.playerControl,
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.playIconSize),
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                PlayerSeekIconButton(
                    icon = Icons.Filled.FastForward,
                    seconds = seekSeconds,
                    description = "Avançar $seekSeconds segundos",
                    onClick = { onSeekRelative(seekSeconds * 1_000L) },
                )
            }
        }
    }
}

@Composable
private fun PlayerSeekIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    seconds: Long,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(ReiAnixTokens.PlayerDimensions.seekButtonSize)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ReiAnixTokens.Colors.playerControl,
                modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.seekIconSize),
            )
            Text(
                text = seconds.toString(),
                style = ReiAnixTokens.TypographyTokens.chip,
                color = ReiAnixTokens.Colors.playerControl,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
fun ReiAnixNativePlayerBottomControls(
    state: ReiAnixNativePlayerUiState,
    onSeekTo: (Long) -> Unit,
    onToggleLock: () -> Unit,
    onResize: () -> Unit,
    onSource: () -> Unit,
    onNext: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val safeBottom = with(LocalDensity.current) { state.safeBottomPx.toDp() }
    val timelineHeight = if (landscape) 36.dp else ReiAnixTokens.PlayerDimensions.timelineHeight
    val actionButtonSize = if (landscape) 40.dp else ReiAnixTokens.PlayerDimensions.actionButtonSize
    val topPadding = if (landscape) 8.dp else ReiAnixTokens.PlayerDimensions.bottomTopPadding
    val timelineSpacerHeight = if (landscape) 0.dp else ReiAnixTokens.Spacing.xs / 2
    if (state.locked) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = safeBottom + ReiAnixTokens.PlayerDimensions.lockAffordanceBottomPadding,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier
                    .size(ReiAnixTokens.PlayerDimensions.lockAffordanceSize)
                    .background(
                        Color.Black.copy(alpha = 0.48f),
                        shape = androidx.compose.foundation.shape.CircleShape,
                    )
                    .semantics {
                        contentDescription = "Desbloquear controles"
                        role = Role.Button
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = ReiAnixTokens.Colors.playerControl,
                )
            }
        }
        return
    }

    val duration = state.durationMs.takeIf { it > 0L } ?: 0L
    var sliderFraction by remember(duration) {
        mutableFloatStateOf(
            if (duration > 0L) {
                (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
        )
    }
    var userDragging by remember { mutableStateOf(false) }

    LaunchedEffect(state.positionMs, state.durationMs, userDragging) {
        if (!userDragging) {
            sliderFraction = if (duration > 0L) {
                (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }

    val displayPosition = if (duration > 0L) {
        (sliderFraction * duration.toFloat()).toLong().coerceIn(0L, duration)
    } else {
        state.positionMs.coerceAtLeast(0L)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        ReiAnixTokens.Colors.playerScrim.copy(alpha = 0.50f),
                        ReiAnixTokens.Colors.playerScrim.copy(alpha = 0.92f),
                    ),
                ),
            )
            .padding(
                start = ReiAnixTokens.PlayerDimensions.bottomHorizontalPadding,
                end = ReiAnixTokens.PlayerDimensions.bottomHorizontalPadding,
                top = topPadding,
                bottom = safeBottom + ReiAnixTokens.PlayerDimensions.bottomExtraPadding,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PlayerTimeFormatter.format(displayPosition),
                style = ReiAnixTokens.TypographyTokens.playerTime,
                color = ReiAnixTokens.Colors.playerControl,
                modifier = Modifier.widthIn(min = ReiAnixTokens.PlayerDimensions.timelineTimeWidth),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = timelineHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                val bufferedFraction = if (duration > 0L) {
                    (state.bufferedPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                if (bufferedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bufferedFraction)
                            .height(3.dp)
                            .background(ReiAnixTokens.Colors.playerControl.copy(alpha = 0.22f)),
                    )
                }
                Slider(
                    value = sliderFraction,
                    onValueChange = {
                        userDragging = true
                        sliderFraction = it.coerceIn(0f, 1f)
                    },
                    onValueChangeFinished = {
                        if (duration > 0L) {
                            onSeekTo(
                                (sliderFraction * duration.toFloat())
                                    .toLong()
                                    .coerceIn(0L, duration),
                            )
                        }
                        userDragging = false
                    },
                    enabled = duration > 0L,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Barra de progresso do vídeo"
                            stateDescription = if (duration > 0L) {
                                PlayerTimeFormatter.format(displayPosition) +
                                    " de " + PlayerTimeFormatter.format(duration)
                            } else {
                                "Duração indisponível"
                            }
                        },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline,
                    ),
                    steps = 0,
                )
            }
            Text(
                text = if (duration > 0L) PlayerTimeFormatter.format(duration) else "--:--",
                style = ReiAnixTokens.TypographyTokens.playerTime,
                color = ReiAnixTokens.Colors.playerControl,
                modifier = Modifier.widthIn(min = ReiAnixTokens.PlayerDimensions.timelineTimeWidth),
            )
        }

        Spacer(modifier = Modifier.height(timelineSpacerHeight))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.Spacing.none),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.none),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerBottomAction(
                icon = Icons.Filled.Lock,
                label = "Bloquear toques",
                buttonSize = actionButtonSize,
                contentDescription = "Bloquear toques",
                onClick = onToggleLock,
                enabled = !state.episodeTransitionInProgress,
            )
            PlayerBottomAction(
                icon = Icons.Filled.AspectRatio,
                label = "Proporção",
                buttonSize = actionButtonSize,
                contentDescription = "Alterar proporção do vídeo",
                onClick = onResize,
                enabled = !state.episodeTransitionInProgress,
            )
            PlayerBottomAction(
                icon = Icons.Filled.MoreVert,
                label = "Opções",
                buttonSize = actionButtonSize,
                contentDescription = buildString {
                    append("Mais opções do player")
                    val speed = state.playbackSpeed.takeIf { it.isFinite() && it > 0f }
                    speed?.let {
                        append(". Velocidade ")
                        append(String.format(java.util.Locale.ROOT, "%.2gx", it))
                    }
                    if (state.aspectLabel.isNotBlank()) {
                        append(". Proporção ")
                        append(state.aspectLabel)
                    }
                },
                onClick = onSource,
                enabled = !state.episodeTransitionInProgress,
            )
            PlayerBottomAction(
                icon = Icons.Filled.SkipNext,
                label = "Próximo episódio",
                buttonSize = actionButtonSize,
                contentDescription = "Próximo episódio",
                onClick = onNext,
                enabled = state.canNext && !state.episodeTransitionInProgress,
            )
        }
    }
}

@Composable
private fun RowScope.PlayerBottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    buttonSize: Dp = ReiAnixTokens.PlayerDimensions.actionButtonSize,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(buttonSize)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) ReiAnixTokens.Colors.playerControl else ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f),
                modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.actionIconSize),
            )
        }
        Text(
            text = label,
            style = ReiAnixTokens.TypographyTokens.playerActionLabel,
            color = ReiAnixTokens.Colors.playerControl.copy(alpha = if (enabled) 0.92f else 0.42f),
            maxLines = 1,
        )
    }
}

