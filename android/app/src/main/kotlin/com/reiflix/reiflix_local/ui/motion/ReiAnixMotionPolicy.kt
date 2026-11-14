package com.reiflix.reiflix_local.ui.motion

import android.animation.ValueAnimator
import android.os.Build

/**
 * Reads the Android system animator setting for accessibility.
 *
 * This is intentionally not a ReiAnix user preference and contains no product
 * or domain state. When system animations are disabled, small visual transitions
 * switch to their immediate equivalents instead of introducing a second motion
 * configuration.
 */
object ReiAnixMotionPolicy {
    fun systemAnimationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
}
