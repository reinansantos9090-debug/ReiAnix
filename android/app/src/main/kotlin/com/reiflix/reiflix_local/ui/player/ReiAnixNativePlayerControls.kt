package com.reiflix.reiflix_local.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

data class ReiAnixNativePlayerUiState(
    val title: String = "Episódio",
    val episodeLabel: String = "Episódio",
    val technicalLine: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val errorVisible: Boolean = false,
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val aspectLabel: String = "Ajustar",
    val playbackSpeed: Float = 1f,
    val safeTopPx: Int = 0,
    val safeBottomPx: Int = 0,
)

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
    val density = LocalDensity.current
    val safeTop = with(density) { state.safeTopPx.toDp() }
    val safeBottom = with(density) { state.safeBottomPx.toDp() }
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
    val displayedPosition = if (duration > 0L) {
        (sliderFraction * duration.toFloat()).toLong().coerceIn(0L, duration)
    } else {
        state.positionMs.coerceAtLeast(0L)
    }
    val playScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (state.isPlaying) 1.04f else 1f,
        animationSpec = androidx.compose.animation.core.tween(ReiAnixTokens.Motion.stateChangeMillis),
        label = "player-play-scale",
    )

    if (state.locked || state.errorVisible) {
        return
    }

    AnimatedVisibility(
        visible = state.controlsVisible,
        enter = fadeIn(androidx.compose.animation.core.tween(ReiAnixTokens.Motion.contentEnterMillis)),
        exit = fadeOut(androidx.compose.animation.core.tween(ReiAnixTokens.Motion.contentExitMillis)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerTopOverlay(
                title = state.title,
                episodeLabel = state.episodeLabel,
                technicalLine = state.technicalLine,
                safeTop = safeTop,
                onBack = onBack,
            )

            PlayerCenterControls(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                ended = state.ended,
                playScale = playScale,
                onPlayPause = onPlayPause,
                onSeekRelative = onSeekRelative,
            )

            PlayerBottomOverlay(
                positionMs = displayedPosition,
                durationMs = duration,
                sliderFraction = sliderFraction,
                safeBottom = safeBottom,
                canNext = state.canNext,
                onSliderChanged = { sliderFraction = it.coerceIn(0f, 1f) },
                onSliderFinished = {
                    if (duration > 0L) {
                        onSeekTo((sliderFraction * duration.toFloat()).toLong().coerceIn(0L, duration))
                    }
                },
                onToggleLock = onToggleLock,
                onResize = onResize,
                onSource = onSource,
                onNext = onNext,
            )
        }
    }
}

@Composable
private fun PlayerTopOverlay(
    title: String,
    episodeLabel: String,
    technicalLine: String,
    safeTop: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.86f),
                        Color.Black.copy(alpha = 0.52f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(
                start = 6.dp,
                end = 10.dp,
                top = safeTop + 4.dp,
                bottom = 24.dp,
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
                tint = Color.White,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.82f)
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = episodeLabel,
                color = Color.White.copy(alpha = 0.96f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (technicalLine.isNotBlank()) {
                Text(
                    text = technicalLine,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun BoxScope.PlayerCenterControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    ended: Boolean,
    playScale: Float,
    onPlayPause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.align(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CirclePlayerButton(
            icon = Icons.Filled.Replay10,
            description = "Voltar 10 segundos",
            onClick = { onSeekRelative(-10_000L) },
        )

        Box(
            modifier = Modifier
                .size(76.dp)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                },
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
                        shape = CircleShape,
                    )
                    .semantics {
                        contentDescription = if (ended) {
                            "Reproduzir novamente"
                        } else if (isPlaying) {
                            "Pausar"
                        } else {
                            "Reproduzir"
                        }
                        role = Role.Button
                    },
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = when {
                            ended -> Icons.Filled.PlayArrow
                            isPlaying -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        CirclePlayerButton(
            icon = Icons.Filled.Forward10,
            description = "Avançar 10 segundos",
            onClick = { onSeekRelative(10_000L) },
        )
    }
}

@Composable
private fun CirclePlayerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(58.dp)
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
            )
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun PlayerBottomOverlay(
    positionMs: Long,
    durationMs: Long,
    sliderFraction: Float,
    safeBottom: androidx.compose.ui.unit.Dp,
    canNext: Boolean,
    onSliderChanged: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onToggleLock: () -> Unit,
    onResize: () -> Unit,
    onSource: () -> Unit,
    onNext: () -> Unit,
) {
    val durationKnown = durationMs > 0L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.46f),
                        Color.Black.copy(alpha = 0.90f),
                    ),
                ),
            )
            .padding(
                start = 10.dp,
                end = 10.dp,
                top = 30.dp,
                bottom = safeBottom + 4.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlayerTime(positionMs),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.widthIn(min = 48.dp),
            )
            Slider(
                value = sliderFraction,
                onValueChange = onSliderChanged,
                onValueChangeFinished = onSliderFinished,
                enabled = durationKnown,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .semantics {
                        contentDescription = "Barra de progresso"
                    },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                ),
                steps = 0,
            )
            Text(
                text = if (durationKnown) formatPlayerTime(durationMs) else "--:--",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.widthIn(min = 48.dp),
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerAction(
                icon = Icons.Filled.Lock,
                label = "Bloquear toques",
                contentDescription = "Bloquear toques",
                onClick = onToggleLock,
                modifier = Modifier.weight(1f),
            )
            PlayerAction(
                icon = Icons.Filled.Fullscreen,
                label = "Redimensionar",
                contentDescription = "Redimensionar vídeo",
                onClick = onResize,
                modifier = Modifier.weight(1f),
            )
            PlayerAction(
                icon = Icons.Filled.PlaylistPlay,
                label = "Fonte",
                contentDescription = "Fonte e opções do player",
                onClick = onSource,
                modifier = Modifier.weight(1f),
            )
            PlayerAction(
                icon = Icons.Filled.SkipNext,
                label = "Próximo episódio",
                contentDescription = "Próximo episódio",
                onClick = onNext,
                enabled = canNext,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlayerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    selectedIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Column(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Button
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(42.dp),
        ) {
            Icon(
                imageVector = selectedIcon ?: icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.36f),
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 0.92f else 0.42f),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

private fun formatPlayerTime(valueMs: Long): String {
    val safe = valueMs.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(java.util.Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(java.util.Locale.US, minutes, seconds)
    }
}
