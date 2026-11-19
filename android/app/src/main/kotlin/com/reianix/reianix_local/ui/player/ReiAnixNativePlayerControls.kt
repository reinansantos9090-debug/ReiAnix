package com.reianix.reianix_local.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import com.reianix.reianix_local.player.PlayerSkipSegment
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.reianix.reianix_local.player.PlayerTimeFormatter
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.reianix.reianix_local.player.LocalSeekPreviewFrames
import android.net.Uri
import android.graphics.Bitmap
import kotlin.math.roundToInt

data class ReiAnixNativePlayerUiState(
    val title: String = "Episódio",
    val episodeLabel: String = "Episódio",
    val technicalLine: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val ended: Boolean = false,
    val errorVisible: Boolean = false,
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val episodeTransitionInProgress: Boolean = false,
    val aspectLabel: String = "Ajustar",
    val sleepTimerLabel: String = "Desativado",
    val playbackSpeed: Float = 1f,
    val audioTrackLabel: String = "Automático",
    val subtitleTrackLabel: String = "Off",
    val audioTrackSelectionEnabled: Boolean = false,
    val subtitleTrackSelectionEnabled: Boolean = false,
    val autoplayNext: Boolean = true,
    val horizontalSeekEnabled: Boolean = true,
    val zoomEnabled: Boolean = true,
    val volumeGesturesEnabled: Boolean = true,
    val brightnessGesturesEnabled: Boolean = true,
    val doubleTapEnabled: Boolean = true,
    val longPressEnabled: Boolean = true,
    val safeTopPx: Int = 0,
    val safeBottomPx: Int = 0,
    val skipSegments: List<PlayerSkipSegment> = emptyList(),
    val favoriteSceneTimes: List<Long> = emptyList(),
    val audioOnly: Boolean = false,
    val favoriteClipMode: Boolean = false,
    val favoriteClipStartMs: Long = 0L,
    val favoriteClipSourceDurationMs: Long = 0L,
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
            .padding(
                start = ReiAnixTokens.PlayerDimensions.topHorizontalPadding,
                end = ReiAnixTokens.PlayerDimensions.topHorizontalPadding,
                top = safeTop + ReiAnixTokens.Spacing.xs,
                bottom = ReiAnixTokens.PlayerDimensions.topBottomPadding,
            ),
    ) {
        // The top overlay contains only Back and the centered episode title.
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
        // Anchor all three actions to the SAME geometric center. The two seek
        // buttons use equal and opposite offsets, independent of screen aspect ratio
        // and independent of the visual shape of the mirrored seek glyph.
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = ReiAnixTokens.PlayerDimensions.centerControlsMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = ReiAnixTokens.PlayerDimensions.centerHorizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            val seekDistance =
                ((maxWidth - ReiAnixTokens.PlayerDimensions.seekButtonSize) / 2)
                    .coerceAtLeast(0.dp)
            // Round ONCE, then mirror the same pixel offset in both directions.
            // Independent Dp rounding of +x and -x can differ by one pixel.
            val seekDistancePx = with(LocalDensity.current) { seekDistance.roundToPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(x = -seekDistancePx, y = 0) },
                contentAlignment = Alignment.Center,
            ) {
                PlayerSeekIconButton(
                    icon = Icons.Filled.Replay,
                    seconds = seekSeconds,
                    description = "Voltar $seekSeconds segundos",
                    forward = false,
                    enabled = !state.episodeTransitionInProgress,
                    onClick = { onSeekRelative(-seekSeconds * 1_000L) },
                )
            }

            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.centerButtonContainerSize),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        enabled = !state.episodeTransitionInProgress,
                        modifier = Modifier
                            .size(ReiAnixTokens.PlayerDimensions.centerButtonSize)
                            .semantics {
                                contentDescription = when {
                                    state.episodeTransitionInProgress -> "Aguarde a troca de episódio"
                                    state.ended -> "Reproduzir novamente"
                                    state.isPlaying -> "Pausar"
                                    else -> "Reproduzir"
                                }
                                role = Role.Button
                            },
                    ) {
                        when {
                            state.isBuffering && state.hasRenderedFirstFrame -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.bufferingIndicatorSize),
                                    color = ReiAnixTokens.Colors.primary,
                                    strokeWidth = ReiAnixTokens.PlayerDimensions.bufferingStroke,
                                )
                            }
                            state.ended -> {
                                Icon(
                                    imageVector = Icons.Filled.Replay,
                                    contentDescription = null,
                                    tint = if (state.episodeTransitionInProgress) {
                                        ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f)
                                    } else {
                                        ReiAnixTokens.Colors.playerControl
                                    },
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.playIconSize),
                                )
                            }
                            state.isPlaying -> {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = null,
                                    tint = if (state.episodeTransitionInProgress) {
                                        ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f)
                                    } else {
                                        ReiAnixTokens.Colors.playerControl
                                    },
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.playIconSize),
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = if (state.episodeTransitionInProgress) {
                                        ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f)
                                    } else {
                                        ReiAnixTokens.Colors.playerControl
                                    },
                                    modifier = Modifier.size(ReiAnixTokens.PlayerDimensions.playIconSize),
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(x = seekDistancePx, y = 0) },
                contentAlignment = Alignment.Center,
            ) {
                PlayerSeekIconButton(
                    icon = Icons.Filled.Replay,
                    seconds = seekSeconds,
                    description = "Avançar $seekSeconds segundos",
                    forward = true,
                    enabled = !state.episodeTransitionInProgress,
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
    forward: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(ReiAnixTokens.PlayerDimensions.seekButtonSize)
            .semantics {
                contentDescription = description
                stateDescription = if (enabled) "Habilitado" else "Desabilitado"
                role = Role.Button
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) ReiAnixTokens.Colors.playerControl else ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f),
                modifier = Modifier
                    .size(ReiAnixTokens.PlayerDimensions.seekIconSize)
                    .graphicsLayer {
                        scaleX = if (forward) -1f else 1f
                    },
            )
            Text(
                text = seconds.toString(),
                style = ReiAnixTokens.TypographyTokens.chip.copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = if (enabled) ReiAnixTokens.Colors.playerControl else ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
fun ReiAnixNativePlayerBottomControls(
    state: ReiAnixNativePlayerUiState,
    onSeekTo: (Long) -> Unit,
    mediaUri: String? = null,
    onScrubInteraction: (Boolean) -> Unit = {},
    onToggleLock: () -> Unit,
    onResize: () -> Unit,
    onSource: () -> Unit,
    onNext: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val safeBottom = with(LocalDensity.current) { state.safeBottomPx.toDp() }
    val timelineHeight = if (landscape) 36.dp else ReiAnixTokens.PlayerDimensions.timelineHeight
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
                        ReiAnixTokens.Colors.playerScrim.copy(alpha = 0.48f),
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
    var sliderFraction by remember(mediaUri, duration) {
        mutableFloatStateOf(
            if (duration > 0L) {
                (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            },
        )
    }
    var userDragging by remember(mediaUri) { mutableStateOf(false) }
    var seekPointerHeld by remember(mediaUri) { mutableStateOf(false) }
    val seekEnabled = duration > 0L && !state.episodeTransitionInProgress &&
        !state.errorVisible && state.controlsVisible
    val reportScrubInteraction by rememberUpdatedState(onScrubInteraction)

    DisposableEffect(mediaUri, seekEnabled) {
        onDispose {
            if (seekPointerHeld || userDragging) {
                seekPointerHeld = false
                userDragging = false
                reportScrubInteraction(false)
            }
        }
    }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val previewWidth = if (screenWidth > 0) {
        minOf(288.dp, (screenWidth.dp - 16.dp).coerceAtLeast(1.dp))
    } else 288.dp

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
    val context = LocalContext.current
    val previewFrames = remember(mediaUri) {
        mediaUri?.let { raw ->
            runCatching { LocalSeekPreviewFrames(context.applicationContext, Uri.parse(raw)) }.getOrNull()
        }
    }
    DisposableEffect(previewFrames) {
        onDispose { previewFrames?.close() }
    }
    var previewFrame by remember(mediaUri) { mutableStateOf<Bitmap?>(null) }
    val previewImage = remember(previewFrame) { previewFrame?.asImageBitmap() }
    LaunchedEffect(userDragging, previewFrames, duration, seekEnabled, state.audioOnly, state.favoriteClipStartMs, state.favoriteClipSourceDurationMs) {
        previewFrame = null
        if (!userDragging || !seekEnabled || state.audioOnly || previewFrames == null) return@LaunchedEffect
        try {
            while (true) {
                val targetMs = (sliderFraction * duration.toFloat()).toLong()
                    .coerceIn(0L, duration)
                // Never wait for a decoder on the Compose/UI thread.
                val sourceTarget = targetMs + state.favoriteClipStartMs
                val sourceDuration = state.favoriteClipSourceDurationMs.takeIf { it > 0L } ?: duration
                previewFrames.requestScrub(sourceTarget, sourceDuration)
                // Keep the last visible frame while a closer one is decoding.
                // As soon as any cached image exists, show the nearest one.
                val nearest = previewFrames.nearestCached(sourceTarget)
                if (nearest != null && previewFrame !== nearest) {
                    previewFrame = nearest
                }
                delay(80L)
            }
        } finally {
            previewFrames.stopScrub()
            previewFrame = null
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ReiAnixTokens.Colors.playerControl.copy(alpha = 0.66f)),
                    )
                    if (bufferedFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(bufferedFraction)
                                .fillMaxHeight()
                                .background(ReiAnixTokens.Colors.playerControl.copy(alpha = 0.22f)),
                        )
                    }
                    if (sliderFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(sliderFraction)
                                .fillMaxHeight()
                                .background(ReiAnixTokens.Colors.primary),
                        )
                    }
                }
                if (duration > 0L && (state.skipSegments.isNotEmpty() || state.favoriteSceneTimes.isNotEmpty())) {
                    Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp)) {
                        for (segment in state.skipSegments) {
                            val start = size.width * (segment.startMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)
                            val end = size.width * (segment.endMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)
                            val tint = when (segment.kind) {
                                "op" -> Color(0xFF42DDB5)
                                "ed" -> Color(0xFFB78BFF)
                                else -> Color(0xFFFFAE55)
                            }
                            drawLine(tint, androidx.compose.ui.geometry.Offset(start, size.height / 2),
                                androidx.compose.ui.geometry.Offset(end, size.height / 2), strokeWidth = 4.dp.toPx())
                        }
                        for (position in state.favoriteSceneTimes) {
                            if (position !in 0L..duration) continue
                            val x = size.width * (position.toDouble() / duration).toFloat()
                            drawCircle(Color(0xFFFFD54F), 3.dp.toPx(), androidx.compose.ui.geometry.Offset(x, size.height / 2))
                        }
                    }
                }
                if (userDragging && seekEnabled && !state.audioOnly) {
                    // Floating window renders outside the short landscape overlay
                    // and does not alter the seekbar's position or tap target.
                    val density = LocalDensity.current
                    val inset = with(density) { 10.dp.roundToPx() }
                    val gap = with(density) { 8.dp.roundToPx() }
                    val positionProvider = remember(sliderFraction, inset, gap) {
                        SeekPreviewPopupPosition(sliderFraction, inset, gap)
                    }
                    Popup(
                        popupPositionProvider = positionProvider,
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            clippingEnabled = false,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.width(previewWidth),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (previewImage != null) {
                                    Image(
                                        bitmap = previewImage,
                                        contentDescription = "Prévia da cena do anime",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (previewFrames?.isUnavailable == true) {
                                    Text(
                                        text = "Prévia indisponível",
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 11.sp,
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White.copy(alpha = 0.72f),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            Text(
                                text = PlayerTimeFormatter.format(displayPosition),
                                color = Color.White,
                                fontSize = 12.sp,
                                style = LocalTextStyle.current.copy(
                                    shadow = Shadow(
                                        color = Color.Black,
                                        offset = Offset(0f, 1f),
                                        blurRadius = 4f,
                                    ),
                                ),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                Slider(
                    value = sliderFraction,
                    onValueChange = {
                        if (!userDragging && !seekPointerHeld) reportScrubInteraction(true)
                        userDragging = true
                        sliderFraction = it.coerceIn(0f, 1f)
                    },
                    onValueChangeFinished = {
                        if (duration > 0L && !state.episodeTransitionInProgress) {
                            onSeekTo(
                                (sliderFraction * duration.toFloat())
                                    .toLong()
                                    .coerceIn(0L, duration),
                            )
                        }
                        userDragging = false
                        if (!seekPointerHeld) reportScrubInteraction(false)
                    },
                    enabled = seekEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Observe the raw gesture from DOWN to UP/CANCEL without
                        // consuming any changes. Slider still owns the actual seek.
                        // This also protects a stationary finger or a long press,
                        // before the first onValueChange has even been emitted.
                        .pointerInput(mediaUri, seekEnabled) {
                            if (!seekEnabled) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                seekPointerHeld = true
                                reportScrubInteraction(true)
                                try {
                                    // The Slider consumes movement in Main; observe
                                    // Final so consumption cannot end the hold early.
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    seekPointerHeld = false
                                    userDragging = false
                                    reportScrubInteraction(false)
                                }
                            }
                        }
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
                        thumbColor = ReiAnixTokens.Colors.primary,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
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
                label = "Bloquear",
                showLabel = !landscape,
                contentDescription = "Bloquear toques",
                onClick = onToggleLock,
                enabled = !state.episodeTransitionInProgress,
                iconVisualScale = 0.75f,
            )
            PlayerBottomAction(
                icon = Icons.Filled.PlaylistPlay,
                label = "Episódios",
                showLabel = !landscape,
                contentDescription = "Abrir lista de episódios",
                onClick = onResize,
                enabled = !state.favoriteClipMode && !state.episodeTransitionInProgress,
                active = false,
            )
            PlayerBottomAction(
                icon = Icons.Filled.SkipNext,
                label = "Próximo episódio",
                showLabel = !landscape,
                contentDescription = "Próximo episódio",
                onClick = onNext,
                enabled = state.canNext && !state.episodeTransitionInProgress,
            )
            PlayerBottomAction(
                icon = Icons.Filled.MoreVert,
                label = "Opções",
                showLabel = !landscape,
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
    showLabel: Boolean = true,
    active: Boolean = false,
    iconVisualScale: Float = 1f,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(ReiAnixTokens.PlayerDimensions.actionButtonSize)
                .semantics {
                    this.contentDescription = contentDescription
                    stateDescription = if (enabled) "Habilitado" else "Desabilitado"
                    role = Role.Button
                },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f)
                    active -> ReiAnixTokens.Colors.primary
                    else -> ReiAnixTokens.Colors.playerControl
                },
                // Lock has a much taller painted silhouette than the other three
                // icons. Normalize only the visual artwork, centered within the
                // identical button bounds; never shift the icon's baseline upward.
                modifier = Modifier
                    .size(ReiAnixTokens.PlayerDimensions.actionIconSize + 3.dp)
                    .graphicsLayer {
                        scaleX = iconVisualScale
                        scaleY = iconVisualScale
                    },
            )
        }
        if (showLabel) {
            Text(
                text = label,
                style = ReiAnixTokens.TypographyTokens.playerActionLabel,
                color = ReiAnixTokens.Colors.playerControl.copy(alpha = if (enabled) 0.92f else 0.42f),
                maxLines = 1,
            )
        }
    }
}
 
/** Positions the thumbnail over the current seek handle, clamping to the screen. */
private class SeekPreviewPopupPosition(
    private val fraction: Float,
    private val trackInsetPx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = (anchorBounds.width - 2 * trackInsetPx).coerceAtLeast(0)
        val handleX = anchorBounds.left + trackInsetPx +
            (width * fraction.coerceIn(0f, 1f)).roundToInt()
        val x = (handleX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height - gapPx)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
