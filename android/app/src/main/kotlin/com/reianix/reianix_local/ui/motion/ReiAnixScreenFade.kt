package com.reianix.reianix_local.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// Match the Anikku's Material fade-through (tabs) and shared axis X (screen stack).
private const val TAB_TRANSITION_MILLIS = 200
private const val SCREEN_TRANSITION_MILLIS = 300
private const val OUTGOING_FRACTION = 0.35f

private fun incomingFade(durationMillis: Int): TweenSpec<Float> {
    val delay = (durationMillis * OUTGOING_FRACTION).toInt()
    return tween(durationMillis - delay, delay, LinearOutSlowInEasing)
}

private fun outgoingFade(durationMillis: Int) =
    tween<Float>((durationMillis * OUTGOING_FRACTION).toInt(), easing = FastOutLinearInEasing)

internal fun reiAnixTabEnterTransition(): EnterTransition =
    fadeIn(animationSpec = incomingFade(TAB_TRANSITION_MILLIS))

internal fun reiAnixTabExitTransition(): ExitTransition =
    fadeOut(animationSpec = outgoingFade(TAB_TRANSITION_MILLIS))

internal fun reiAnixScreenEnterTransition(offset: Int): EnterTransition =
    fadeIn(animationSpec = incomingFade(SCREEN_TRANSITION_MILLIS)) +
        slideInHorizontally(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) { offset }

internal fun reiAnixScreenExitTransition(offset: Int): ExitTransition =
    fadeOut(animationSpec = outgoingFade(SCREEN_TRANSITION_MILLIS)) +
        slideOutHorizontally(animationSpec = tween(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)) { -offset }

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
        if (progress.value < 1f) progress.animateTo(1f, incomingFade(TAB_TRANSITION_MILLIS))
    }
    return graphicsLayer { alpha = progress.value }
}
