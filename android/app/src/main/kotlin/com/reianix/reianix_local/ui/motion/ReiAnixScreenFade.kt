package com.reianix.reianix_local.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Fade in the new content without keeping the outgoing page's handlers active. */
@Composable
internal fun Modifier.reiAnixScreenFade(destination: Any?): Modifier {
    val lastDestination = remember { mutableStateOf(destination) }
    val animate = ReiAnixMotionPolicy.systemAnimationsEnabled()
    val opacity = remember(destination, animate) {
        Animatable(if (animate && lastDestination.value != destination) 0.55f else 1f)
    }
    SideEffect { lastDestination.value = destination }
    LaunchedEffect(opacity) {
        if (opacity.value < 1f) opacity.animateTo(1f, tween(durationMillis = 150))
    }
    return graphicsLayer { alpha = opacity.value }
}
