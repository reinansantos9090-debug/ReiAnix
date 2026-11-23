package com.reianix.reianix_local.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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

// Full-screen Details has a backdrop, poster, and nested lazy content. Avoid
// scaling two expensive screens simultaneously during a Details handoff while
// retaining a smooth fade and preserving the original animation duration.
internal fun reiAnixDetailsEnterTransition(): EnterTransition =
    fadeIn(animationSpec = screenTransitionSpec())

internal fun reiAnixDetailsExitTransition(): ExitTransition =
    fadeOut(animationSpec = screenTransitionSpec())

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
