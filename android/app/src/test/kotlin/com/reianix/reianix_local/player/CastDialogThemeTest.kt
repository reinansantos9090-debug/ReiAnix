package com.reianix.reianix_local.player

import android.content.res.TypedArray
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import com.reianix.reianix_local.NativePlayerActivity
import com.reianix.reianix_local.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A MediaRouteButton delegates route dialogs to its hosting FragmentActivity,
 * not to an AndroidView-specific ContextThemeWrapper. The host therefore needs
 * an AppCompat theme, even though the player itself is visually native/Compose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastDialogThemeTest {

    @Test fun playerHostUsesSupportFragmentManager() {
        assertTrue(FragmentActivity::class.java.isAssignableFrom(NativePlayerActivity::class.java))
    }

    @Test fun routeDialogHostHasAppCompatAttributesAndOpaqueBackground() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), R.style.ReiAnixPlayerTheme,
        )
        val primary = TypedValue()
        assertTrue(
            "MediaRouter dialogs require an AppCompat colorPrimary attribute",
            context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, primary, true),
        )
        val attrs: TypedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        try {
            assertEquals("MediaRouter's dialog background must be opaque", 255,
                Color.alpha(attrs.getColor(0, Color.TRANSPARENT)))
        } finally {
            attrs.recycle()
        }
    }
}
