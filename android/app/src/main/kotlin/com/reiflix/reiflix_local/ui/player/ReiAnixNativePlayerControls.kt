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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.reiflix.reiflix_local.player.PlayerTimeFormatter
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    if (!state.controlsVisible || state.locked || state.errorVisible) return

    Box(modifier = Modifier.fillMaxSize()) {
        ReiAnixNativePlayerTopControls(
            state = state,
            onBack = onBack,
        )
        ReiAnixNativePlayerCenterControls(
            state = state,
            onPlayPause = onPlayPause,
            onSeekRelative = onSeekRelative,
        )
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
                        Color.Black.copy(alpha = 0.88f),
                        Color.Black.copy(alpha = 0.50f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(
                start = 4.dp,
                end = 10.dp,
                top = safeTop + 2.dp,
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
                .fillMaxWidth(0.86f)
                .padding(top = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.episodeLabel.ifBlank { "Episódio" },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (state.technicalLine.isNotBlank()) {
                Text(
                    text = state.technicalLine,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Text(
                text = state.title.ifBlank { "Episódio" },
                color = Color.White.copy(alpha = 0.98f),
                fontSize = 11.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun ReiAnixNativePlayerCenterControls(
    state: ReiAnixNativePlayerUiState,
    onPlayPause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerSeekGlyphButton(
                glyph = "↶",
                seconds = "10",
                description = "Voltar 10 segundos",
                onClick = { onSeekRelative(-10_000L) },
            )

            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(64.dp)
                        .semantics {
                            contentDescription = when {
                                state.ended -> "Reproduzir novamente"
                                state.isPlaying -> "Pausar"
                                else -> "Reproduzir"
                            }
                            role = Role.Button
                        },
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                    } else if (state.isPlaying && !state.ended) {
                        Text(
                            text = "Ⅱ",
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }

            PlayerSeekGlyphButton(
                glyph = "↷",
                seconds = "10",
                description = "Avançar 10 segundos",
                onClick = { onSeekRelative(10_000L) },
            )
        }
    }
}

@Composable
private fun PlayerSeekGlyphButton(
    glyph: String,
    seconds: String,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(58.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = seconds,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = glyph,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart),
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
    val safeBottom = with(LocalDensity.current) { state.safeBottomPx.toDp() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.50f),
                        Color.Black.copy(alpha = 0.92f),
                    ),
                ),
            )
            .padding(
                start = 10.dp,
                end = 10.dp,
                top = 26.dp,
                bottom = safeBottom + 4.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PlayerTimeFormatter.format(displayPosition),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.widthIn(min = 48.dp),
            )
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
                    .weight(1f)
                    .heightIn(min = 48.dp)
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
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                ),
                steps = 0,
            )
            Text(
                text = if (duration > 0L) PlayerTimeFormatter.format(duration) else "--:--",
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
                .padding(horizontal = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerBottomAction(
                icon = Icons.Filled.Lock,
                label = "Bloquear toques",
                contentDescription = "Bloquear toques",
                onClick = onToggleLock,
            )
            PlayerBottomAction(
                glyph = "⛶",
                label = "Redimensionar",
                contentDescription = "Redimensionar vídeo",
                onClick = onResize,
            )
            PlayerBottomAction(
                glyph = "☷",
                label = "Fonte",
                contentDescription = "Fonte e opções do player",
                onClick = onSource,
            )
            PlayerBottomAction(
                glyph = "»",
                label = "Próximo episódio",
                contentDescription = "Próximo episódio",
                onClick = onNext,
                enabled = state.canNext,
            )
        }
    }
}

@Composable
private fun RowScope.PlayerBottomAction(
    glyph: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
        ) {
            if (glyph != null) {
                Text(
                    text = glyph,
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.36f),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) Color.White else Color.White.copy(alpha = 0.36f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 0.92f else 0.42f),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

