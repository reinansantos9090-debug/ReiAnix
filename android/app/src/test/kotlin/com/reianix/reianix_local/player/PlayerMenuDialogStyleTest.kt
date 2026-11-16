package com.reianix.reianix_local.player

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import com.reianix.reianix_local.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Prevent the player popup dialogs from reverting to Android's gray, square
 * AlertDialog window. The colors/radius must match the Compose ⋮ menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerMenuDialogStyleTest {

    @Test fun nativePlayerMenuDialogUsesRoundedTranslucentCharcoalBackground() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.ReiAnixPlayerMenuDialogTheme,
        )
        val windowBackground = TypedValue()
        assertTrue(
            context.theme.resolveAttribute(android.R.attr.windowBackground, windowBackground, true),
        )
        assertEquals(R.drawable.reianix_player_menu_dialog_background, windowBackground.resourceId)

        val background = context.getDrawable(R.drawable.reianix_player_menu_dialog_background)
        assertTrue(background is GradientDrawable)
        val shape = background as GradientDrawable
        assertEquals(0xE61B1B1D.toInt(), shape.color?.defaultColor)
        assertEquals(
            18f * context.resources.displayMetrics.density,
            shape.cornerRadius,
            0.5f,
        )
    }

    @Test fun nativePlayerMenuDialogsPreserveReiAnixBlueAccent() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.ReiAnixPlayerMenuDialogTheme,
        )
        val accent = TypedValue()
        assertTrue(context.theme.resolveAttribute(android.R.attr.colorAccent, accent, true))
        assertEquals(0xFF2579FF.toInt(), accent.data)
    }
}
