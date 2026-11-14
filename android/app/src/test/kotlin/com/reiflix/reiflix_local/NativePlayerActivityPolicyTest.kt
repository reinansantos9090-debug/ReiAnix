package com.reiflix.reiflix_local

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlayerActivityPolicyTest {
    @Test
    fun alwaysPolicyIsImmersiveInAnyOrientation() {
        assertTrue(
            NativePlayerActivity.resolveImmersivePolicy(
                "always",
                Configuration.ORIENTATION_PORTRAIT,
            )
        )
        assertTrue(
            NativePlayerActivity.resolveImmersivePolicy(
                "always",
                Configuration.ORIENTATION_LANDSCAPE,
            )
        )
    }

    @Test
    fun landscapePolicyOnlyImmersesInLandscape() {
        assertFalse(
            NativePlayerActivity.resolveImmersivePolicy(
                "landscape",
                Configuration.ORIENTATION_PORTRAIT,
            )
        )
        assertTrue(
            NativePlayerActivity.resolveImmersivePolicy(
                "landscape",
                Configuration.ORIENTATION_LANDSCAPE,
            )
        )
    }

    @Test
    fun neverPolicyRestoresSystemBars() {
        assertFalse(
            NativePlayerActivity.resolveImmersivePolicy(
                "never",
                Configuration.ORIENTATION_LANDSCAPE,
            )
        )
    }

    @Test
    fun unknownPolicyUsesSafeImmersiveDefault() {
        assertTrue(
            NativePlayerActivity.resolveImmersivePolicy(
                "unexpected",
                Configuration.ORIENTATION_PORTRAIT,
            )
        )
    }
}
