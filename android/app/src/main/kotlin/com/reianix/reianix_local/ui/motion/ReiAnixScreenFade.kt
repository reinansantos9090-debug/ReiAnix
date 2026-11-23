package com.reianix.reianix_local.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val SCREEN_TRANSITION_MILLIS = 150
private const val SCREEN_TRANSITION_SCALE = 0.9f

private fun screenTransitionSpec() = tween<Float>(
    durationMillis = SCREEN_TRANSITION_MILLIS,
    easing = LinearEasing,
)

internal fun reiAnixScreenEnterTransition(): EnterTransition =
    fadeIn(animationSpec = screenTransitionSpec()) +
        scaleIn(animationSpec = screenTransitionSpec(), initialScale = SCREEN_TRANSITION_SCALE)

internal fun reiAnixScreenExitTransition(): ExitTransition =
    fadeOut(animationSpec = screenTransitionSpec()) +
        scaleOut(animationSpec = screenTransitionSpec(), targetScale = SCREEN_TRANSITION_SCALE)

/**
 * Navigation back is a different interaction from navigation forward. Keep the
 * same fade-and-scale effect (unlike the rejected fade-only PR), while slightly
 * reducing the scale range on returning to an existing screen.
 */
internal fun reiAnixScreenPopEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) +
        scaleIn(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing), initialScale = 0.96f)

internal fun reiAnixScreenPopExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) +
        scaleOut(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing), targetScale = 0.96f)

/**
 * Detail transitions keep fade AND scale, but avoid transforming the large
 * poster/episode tree through the full 10% size range. Benchmark results must
 * determine whether this helps on the user's physical device.
 */
internal fun reiAnixDetailsEnterTransition(): EnterTransition =
    fadeIn(animationSpec = screenTransitionSpec()) +
        scaleIn(animationSpec = screenTransitionSpec(), initialScale = 0.97f)

internal fun reiAnixDetailsExitTransition(): ExitTransition =
    fadeOut(animationSpec = screenTransitionSpec()) +
        scaleOut(animationSpec = screenTransitionSpec(), targetScale = 0.97f)

internal fun reiAnixDetailsPopEnterTransition(): EnterTransition =
    fadeIn(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) +
        scaleIn(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing), initialScale = 0.985f)

internal fun reiAnixDetailsPopExitTransition(): ExitTransition =
    fadeOut(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) +
        scaleOut(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing), targetScale = 0.985f)

/** Animate the incoming content when an internal tab changes in place. */
@Composable
internal fun Modifier.reiAnixScreenFade(destination: Any?): Modifier {
    val lastDestination = remember { mutableStateOf(destination) }
    val animate = ReiAnixMotionPolicy.systemAnimationsEnabled()
    val progress = remember(destination, animate) {
        Animatable(if (animate && lastDestination.value != destination) 0f else 1f)
    }
    SideEffect { lastDestination.value = destination }
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f, screenTransitionSpec())
    }
    return graphicsLayer {
        val fraction = progress.value
        val scale = SCREEN_TRANSITION_SCALE + (1f - SCREEN_TRANSITION_SCALE) * fraction
        alpha = fraction
        scaleX = scale
        scaleY = scale
    }
}
