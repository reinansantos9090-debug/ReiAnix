package com.reianix.reianix_local.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import com.reianix.reianix_local.player.PlayerSkipSegment
import com.reianix.reianix_local.player.PlayerShortcut
import com.reianix.reianix_local.player.PlayerShortcutPolicy
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
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.reianix.reianix_local.player.PlayerTimeFormatter
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.painterResource
import com.reianix.reianix_local.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
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
    val shortcuts: List<PlayerShortcut> = PlayerShortcutPolicy.default,
    val smartSkipEnabled: Boolean = true,
    val animePreferencesEnabled: Boolean = false,
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
    onCast: () -> Unit = {},
    onAudioTracks: () -> Unit = {},
    onAspect: () -> Unit = {},
    onManualSkip: () -> Unit = {},
    onNotes: () -> Unit = {},
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
            onCast = onCast,
            onAudioTracks = onAudioTracks,
            onAspect = onAspect,
            onManualSkip = onManualSkip,
            onNotes = onNotes,
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
        PlayerIconButton(
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
                style = ReiAnixTokens.TypographyTokens.playerTopLabel.copy(fontSize = 15.sp, lineHeight = 18.sp),
                color = ReiAnixTokens.Colors.playerControl,
                maxLines = 1,
            )
            if (state.technicalLine.isNotBlank()) {
                Text(
                    text = state.technicalLine,
                    style = ReiAnixTokens.TypographyTokens.playerTechnical.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    color = ReiAnixTokens.Colors.playerControl.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
            Text(
                text = state.title.ifBlank { "Episódio" },
                style = ReiAnixTokens.TypographyTokens.metadata.copy(fontSize = 14.sp, lineHeight = 18.sp),
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
    accumulatedSeekSeconds: Long = 0L,
    rewindAlpha: () -> Float = { 1f },
    playAlpha: () -> Float = { 1f },
    forwardAlpha: () -> Float = { 1f },
    onSeekIconPositioned: (Boolean, Offset) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dimensions = ReiAnixTokens.PlayerDimensions
        // The native blue preparation spinner is centered in the PlayerView.
        // In portrait Play/Pause must use the very same geometric center;
        // landscape keeps its previously approved five-shortcut alignment.
        // Both ten-second controls still move with Play, retaining their
        // original spacing, sizes and touch targets.
        val groupShiftPx = with(LocalDensity.current) {
            (maxWidth * PlayerShortcutPolicy.centerActionGroupShiftFraction(state.shortcuts, landscape)).roundToPx()
        }
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = if (landscape) androidx.compose.ui.unit.Dp.Infinity else dimensions.centerControlsMaxWidth)
                .fillMaxWidth()
                .absoluteOffset { IntOffset(x = groupShiftPx, y = 0) }
                .padding(horizontal = if (landscape) 0.dp else dimensions.centerHorizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            val seekDistance = if (landscape) maxWidth * dimensions.landscapeSeekOffsetFraction
                else ((maxWidth - dimensions.seekButtonSize) / 2).coerceAtLeast(0.dp)
            val seekTouchSize = if (landscape) dimensions.landscapeSeekIconSize(maxWidth.value).dp else dimensions.seekIconSize
            val playTouchSize = if (landscape) (maxWidth * 0.105f).coerceIn(dimensions.playIconSize, 96.dp)
                else dimensions.playIconSize
            val seekSize = if (landscape) dimensions.landscapeSeekGlyphSize else dimensions.seekIconSize
            val playSize = if (landscape) dimensions.landscapePlayGlyphSize else dimensions.playIconSize
            // Round ONCE, then mirror the same pixel offset in both directions.
            // Independent Dp rounding of +x and -x can differ by one pixel.
            val seekDistancePx = with(LocalDensity.current) { seekDistance.roundToPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .absoluteOffset { IntOffset(x = -seekDistancePx, y = 0) }
                    .graphicsLayer { alpha = rewindAlpha() },
                contentAlignment = Alignment.Center,
            ) {
                PlayerSeekIconButton(
                    seconds = if (accumulatedSeekSeconds < 0) -accumulatedSeekSeconds else seekSeconds,
                    description = "Voltar $seekSeconds segundos",
                    forward = false,
                    iconSize = seekSize,
                    touchSize = seekTouchSize,
                    landscape = landscape,
                    enabled = !state.episodeTransitionInProgress,
                    onClick = { onSeekRelative(-seekSeconds * 1_000L) },
                    onIconPositioned = onSeekIconPositioned,
                )
            }

            Box(
                modifier = Modifier.align(Alignment.Center)
                    .graphicsLayer { alpha = playAlpha() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(maxOf(dimensions.centerButtonContainerSize, playTouchSize)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerIconButton(
                        onClick = onPlayPause,
                        enabled = !state.episodeTransitionInProgress,
                        modifier = Modifier
                            .size(maxOf(dimensions.centerButtonSize, playTouchSize))
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
                            state.isBuffering -> {
                                // The persistent native indicator owns loading visuals.
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
                                    modifier = Modifier.size(playSize),
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
                                    modifier = Modifier.size(playSize),
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
                                    modifier = Modifier.size(playSize),
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .absoluteOffset { IntOffset(x = seekDistancePx, y = 0) }
                    .graphicsLayer { alpha = forwardAlpha() },
                contentAlignment = Alignment.Center,
            ) {
                PlayerSeekIconButton(
                    seconds = if (accumulatedSeekSeconds > 0) accumulatedSeekSeconds else seekSeconds,
                    description = "Avançar $seekSeconds segundos",
                    forward = true,
                    iconSize = seekSize,
                    touchSize = seekTouchSize,
                    landscape = landscape,
                    enabled = !state.episodeTransitionInProgress,
                    onClick = { onSeekRelative(seekSeconds * 1_000L) },
                    onIconPositioned = onSeekIconPositioned,
                )
            }
        }
    }
}

@Composable
private fun PlayerSeekIconButton(
    seconds: Long,
    description: String,
    forward: Boolean,
    iconSize: androidx.compose.ui.unit.Dp,
    touchSize: androidx.compose.ui.unit.Dp,
    landscape: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onIconPositioned: (Boolean, Offset) -> Unit,
) {
    PlayerIconButton(
        onClick = onClick,
        enabled = enabled,
        showPressFeedback = false,
        modifier = Modifier
            .size(maxOf(ReiAnixTokens.PlayerDimensions.seekButtonSize, touchSize))
            .semantics {
                contentDescription = description
                stateDescription = if (enabled) "Habilitado" else "Desabilitado"
                role = Role.Button
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_player_seek_circle),
                contentDescription = null,
                tint = if (enabled) ReiAnixTokens.Colors.playerControl else ReiAnixTokens.Colors.playerControl.copy(alpha = 0.36f),
                modifier = Modifier
                    .size(iconSize)
                    .onGloballyPositioned { coordinates ->
                        val center = Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
                        val windowCenter = coordinates.localToWindow(center)
                        if (windowCenter.x.isFinite() && windowCenter.y.isFinite()) {
                            onIconPositioned(forward, windowCenter)
                        }
                    }
                    .graphicsLayer {
                        scaleX = if (forward) -1f else 1f
                    },
            )
            Text(
                text = seconds.toString(),
                style = ReiAnixTokens.TypographyTokens.chip.copy(
                    fontSize = if (landscape) ReiAnixTokens.PlayerDimensions.landscapeSeekCounterSize else 10.sp,
                    lineHeight = if (landscape) 22.sp else 12.sp,
                    fontWeight = if (landscape) androidx.compose.ui.text.font.FontWeight.Bold else
                        ReiAnixTokens.TypographyTokens.chip.fontWeight,
                ),
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
    onPlaybackSpeed: () -> Unit = {},
    onCast: () -> Unit = {},
    onSubtitles: () -> Unit = {},
    onSleepTimer: () -> Unit = {},
    onAudioTracks: () -> Unit = {},
    onAspect: () -> Unit = {},
    onManualSkip: () -> Unit = {},
    onNotes: () -> Unit = {},
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val safeBottom = with(LocalDensity.current) { state.safeBottomPx.toDp() }
    val timelineHeight = if (landscape) 36.dp else ReiAnixTokens.PlayerDimensions.timelineHeight
    val topPadding = if (landscape) 8.dp else ReiAnixTokens.PlayerDimensions.bottomTopPadding
    val timelineSpacerHeight = if (landscape) 0.dp else ReiAnixTokens.Spacing.xs / 2
    if (state.locked) {
        // Use the same top anchor, measured timeline and weighted shortcut slots
        // as the unlocked bar. Only the unlock button is placed; the hidden
        // timeline cannot draw, receive input or expose accessibility actions.
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
            val duration = state.durationMs.takeIf { it > 0L } ?: 0L
            Layout(
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = PlayerTimeFormatter.format(state.positionMs.coerceIn(0L, duration)),
                            style = ReiAnixTokens.TypographyTokens.playerTime,
                            modifier = Modifier.widthIn(min = ReiAnixTokens.PlayerDimensions.timelineTimeWidth),
                        )
                        Box(
                            modifier = Modifier.weight(1f).heightIn(min = timelineHeight),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Slider(
                                value = 0f,
                                onValueChange = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                steps = 0,
                            )
                        }
                        Text(
                            text = if (duration > 0L) PlayerTimeFormatter.format(duration) else "--:--",
                            style = ReiAnixTokens.TypographyTokens.playerTime,
                            modifier = Modifier.widthIn(min = ReiAnixTokens.PlayerDimensions.timelineTimeWidth),
                        )
                    }
                },
            ) { measurables, constraints ->
                val timeline = measurables.single().measure(constraints)
                layout(timeline.width, timeline.height) {
                    // Reserve exactly the timeline's measured height without placing it.
                }
            }
            Spacer(modifier = Modifier.height(timelineSpacerHeight))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fiveShortcuts = state.shortcuts.size == PlayerShortcutPolicy.MAX_COUNT
                val insetUnit = maxWidth * PlayerShortcutPolicy.FIVE_SHORTCUT_INSET_UNIT_FRACTION
                val rowStartInset = if (fiveShortcuts) insetUnit else 0.dp
                val rowEndInset = if (fiveShortcuts) insetUnit * 3f else 0.dp
                val displayShortcuts = state.shortcuts.filterNot { it == PlayerShortcut.OPTIONS } + PlayerShortcut.OPTIONS
                // A restored locked session may have no Lock shortcut. Keep
                // an accessible, centered unlock affordance in that case.
                val slots = if (PlayerShortcut.LOCK in displayShortcuts) displayShortcuts
                    else listOf(PlayerShortcut.LOCK)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (slots.size == 1) 0.dp else rowStartInset,
                            end = if (slots.size == 1) 0.dp else rowEndInset,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.none),
                    verticalAlignment = Alignment.Top,
                ) {
                    slots.forEach { shortcut ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (shortcut == PlayerShortcut.LOCK) {
                                PlayerIconButton(
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
                            } else {
                                Spacer(Modifier.size(ReiAnixTokens.PlayerDimensions.actionButtonSize))
                            }
                        }
                    }
                }
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

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // At five, position the whole evenly-spaced row a little further
            // left so its visual center matches the video area (not the black
            // side margins). Keep the total insets unchanged, so every slot
            // retains exactly the same width and center-to-center distance.
            // The four-shortcut layout is intentionally unchanged.
            val fiveShortcuts = state.shortcuts.size == PlayerShortcutPolicy.MAX_COUNT
            val insetUnit = maxWidth * PlayerShortcutPolicy.FIVE_SHORTCUT_INSET_UNIT_FRACTION
            val rowStartInset = if (fiveShortcuts) insetUnit else 0.dp
            val rowEndInset = if (fiveShortcuts) insetUnit * 3f else 0.dp
            val displayShortcuts = state.shortcuts.filterNot { it == PlayerShortcut.OPTIONS } + PlayerShortcut.OPTIONS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = rowStartInset, end = rowEndInset),
                horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.none),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only the shortcuts selected by the user are composed; the
                // five-icon default keeps Options in the final position.
                displayShortcuts.forEach { shortcut ->
                    when (shortcut) {
                        PlayerShortcut.LOCK -> PlayerBottomAction(
                            icon = Icons.Filled.Lock,
                            label = "Bloquear",
                            showLabel = !landscape,
                            contentDescription = "Bloquear toques",
                            onClick = onToggleLock,
                            enabled = !state.episodeTransitionInProgress,
                            iconVisualScale = 0.75f,
                        )
                        PlayerShortcut.EPISODES -> PlayerBottomAction(
                            icon = Icons.Filled.PlaylistPlay,
                            label = "Episódios",
                            showLabel = !landscape,
                            contentDescription = "Abrir lista de episódios",
                            onClick = onResize,
                            enabled = !state.favoriteClipMode && !state.episodeTransitionInProgress,
                        )
                        PlayerShortcut.NEXT -> PlayerBottomAction(
                            icon = Icons.Filled.SkipNext,
                            label = "Próximo episódio",
                            showLabel = !landscape,
                            contentDescription = "Próximo episódio",
                            onClick = onNext,
                            enabled = state.canNext && !state.episodeTransitionInProgress,
                        )
                        PlayerShortcut.OPTIONS -> PlayerBottomAction(
                            icon = Icons.Filled.MoreVert,
                            label = "Opções",
                            showLabel = !landscape,
                            contentDescription = buildString {
                                append("Mais opções do player")
                                state.playbackSpeed.takeIf { it.isFinite() && it > 0f }?.let {
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
                        PlayerShortcut.CAST -> PlayerBottomAction(
                            icon = Icons.Filled.Cast,
                            label = "Transmitir",
                            showLabel = !landscape,
                            contentDescription = "Transmitir para TV",
                            iconVisualScale = if (fiveShortcuts) 0.88f else 1f,
                            onClick = onCast,
                            enabled = !state.episodeTransitionInProgress,
                        )
                        PlayerShortcut.SPEED -> PlayerBottomAction(
                            icon = Icons.Filled.Speed,
                            label = "Velocidade",
                            showLabel = !landscape,
                            contentDescription = "Alterar velocidade do vídeo",
                            iconVisualScale = if (fiveShortcuts) 0.88f else 1f,
                            onClick = onPlaybackSpeed,
                            enabled = !state.episodeTransitionInProgress,
                        )
                        PlayerShortcut.SUBTITLES -> PlayerBottomAction(
                            icon = Icons.Filled.Subtitles,
                            label = "Legendas",
                            showLabel = !landscape,
                            contentDescription = "Selecionar legendas",
                            iconVisualScale = if (fiveShortcuts) 0.88f else 1f,
                            onClick = onSubtitles,
                            enabled = !state.episodeTransitionInProgress,
                        )
                        PlayerShortcut.TIMER -> PlayerBottomAction(
                            icon = Icons.Filled.Timer,
                            label = "Temporizador",
                            showLabel = !landscape,
                            contentDescription = "Temporizador de reprodução",
                            iconVisualScale = if (fiveShortcuts) 0.88f else 1f,
                            onClick = onSleepTimer,
                            enabled = !state.episodeTransitionInProgress,
                        )
                        PlayerShortcut.AUDIO -> PlayerBottomAction(
                            icon = Icons.Filled.Headphones,
                            label = "Áudio",
                            showLabel = !landscape,
                            contentDescription = "Selecionar faixa de áudio",
                            iconVisualScale = if (fiveShortcuts) 0.88f else 1f,
                            onClick = onAudioTracks,
                            enabled = !state.episodeTransitionInProgress,
                        )
                        PlayerShortcut.ASPECT -> PlayerBottomAction(
                            icon = Icons.Filled.AspectRatio,
                            label = "Proporção",
                            showLabel = !landscape,
                            contentDescription = "Alternar entre ajustar e preencher o vídeo",
                            // Normalize the wider rectangular artwork in portrait only.
                            // Keep the same centered 48dp hit target and weighted slot.
                            iconVisualScale = (if (fiveShortcuts) 0.88f else 1f) *
                                (if (landscape) 1f else 0.8f),
                            onClick = onAspect,
                            enabled = !state.episodeTransitionInProgress && !state.audioOnly,
                        )
                        PlayerShortcut.SKIP_OP_ED -> PlayerBottomAction(
                            icon = Icons.Filled.FastForward,
                            label = "Pular OP/ED",
                            showLabel = !landscape,
                            contentDescription = "Avançar 85 segundos",
                            iconVisualScale = if (fiveShortcuts) 0.88f else 1f,
                            onClick = onManualSkip,
                            enabled = !state.episodeTransitionInProgress && state.durationMs > 0L,
                        )
                        PlayerShortcut.NOTES -> PlayerBottomAction(
                            icon = Icons.Filled.EditNote,
                            label = "Anotações",
                            showLabel = !landscape,
                            contentDescription = "Criar anotação no tempo atual do episódio",
                            iconVisualScale = if (fiveShortcuts) 0.88f else 1f,
                            onClick = onNotes,
                            enabled = !state.episodeTransitionInProgress && !state.favoriteClipMode && state.hasRenderedFirstFrame,
                        )
                    }
                }
            }
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
        PlayerIconButton(
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

@OptIn(ExperimentalMaterial3Api::class)
private val playerIconRipple = RippleConfiguration(
    color = Color.White,
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.16f,
        focusedAlpha = 0.12f,
        hoveredAlpha = 0.08f,
        pressedAlpha = 0.5f,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showPressFeedback: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides if (showPressFeedback) playerIconRipple else null,
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}
