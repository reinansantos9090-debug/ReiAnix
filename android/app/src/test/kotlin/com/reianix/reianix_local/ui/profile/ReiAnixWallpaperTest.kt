package com.reianix.reianix_local.ui.profile

import android.app.Application
import androidx.compose.ui.graphics.Color
import com.reianix.reianix_local.ui.theme.wallpaperContentColor
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import com.reianix.reianix_local.ui.theme.automaticWallpaperDim
import com.reianix.reianix_local.ui.theme.transformWallpaper
import com.reianix.reianix_local.ui.theme.wallpaperCrop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReiAnixWallpaperTest {
    @Test
    fun transparentContentIsOptInAndPreservesAllOriginalColorsWhenDisabled() {
        for (original in listOf(Color.Black, Color.White, Color(0xFF151515), Color(0xFF123456))) {
            assertEquals(original, wallpaperContentColor(original, false))
            assertEquals(Color.Transparent, wallpaperContentColor(original, true))
        }
    }

    @Test
    fun cropAlwaysCoversViewportInPortraitAndLandscape() {
        for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 800 to 800)) {
            for ((sw, sh) in listOf(1600 to 900, 900 to 1600, 1000 to 1000)) {
                for (zoom in listOf(1f, 2f, 3f)) {
                    for (position in listOf(0f, .5f, 1f)) {
                        val crop = wallpaperCrop(sw, sh, w, h,
                            ReiAnixWallpaper(zoom = zoom, positionX = position, positionY = position))
                        assertTrue(crop.x <= 0 && crop.y <= 0)
                        assertTrue(crop.x + crop.width >= w && crop.y + crop.height >= h)
                    }
                }
            }
        }
    }

    @Test
    fun invalidTuningIsNormalizedAndDefaultRemainsDisabled() {
        val value = ReiAnixWallpaper(zoom = Float.NaN, positionX = -1f,
            positionY = 5f).normalized()
        assertNull(value.imageName)
        assertEquals(1f, value.zoom, 0f)
        assertEquals(0f, value.positionX, 0f)
        assertEquals(1f, value.positionY, 0f)
    }

    @Test
    fun automaticContrastDarkensBrightImagesWithoutManualControls() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val dark = automaticWallpaperDim(IntArray(100) { black })
        val bright = automaticWallpaperDim(IntArray(100) { white })
        assertTrue(bright > dark)
        assertEquals(.52f, dark, .001f)
        assertEquals(.72f, bright, .001f)
    }

    @Test
    fun pinchKeepsGestureCenterAndDragClampsToImageEdges() {
        val initial = ReiAnixWallpaper()
        val enlarged = transformWallpaper(initial, 1000, 1000, 500, 500, 250f, 250f, 0f, 0f, 2f)
        assertEquals(2f, enlarged.zoom, .001f)
        assertEquals(.5f, enlarged.positionX, .001f)
        assertEquals(.5f, enlarged.positionY, .001f)
        val dragged = transformWallpaper(enlarged, 1000, 1000, 500, 500, 250f, 250f, 10000f, -10000f, 1f)
        assertEquals(0f, dragged.positionX, .001f)
        assertEquals(1f, dragged.positionY, .001f)
        val crop = wallpaperCrop(1000, 1000, 500, 500, dragged)
        assertTrue(crop.x <= 0 && crop.y <= 0 && crop.x + crop.width >= 500 && crop.y + crop.height >= 500)
    }

    @Test
    fun saveAndRemoveWallpaperDoNotChangeProfilePhotoOrDeleteOriginal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ReiAnixWallpaperStore.from(context)
        val profile = ReiAnixProfileStore.from(context)
        val oldProfile = profile.state.value
        val name = "a1234567.jpg"
        val file = requireNotNull(store.file(name))
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            store.save(ReiAnixWallpaper(imageName = name, zoom = 2f, positionX = .8f))
            assertEquals(name, store.state.value.imageName)
            assertEquals(2f, store.state.value.zoom, 0f)
            val prefs = context.getSharedPreferences("reianix_wallpaper_v1", 0)
            assertEquals(name, prefs.getString("image", null))
            assertEquals(.8f, prefs.getFloat("x", 0f), 0f)
            assertFalse(prefs.contains("dim"))
            assertFalse(prefs.contains("blur"))
            store.discard(name)
            assertTrue(file.isFile)
            try {
                store.save(ReiAnixWallpaper(imageName = "bad/name.jpg"))
                fail("Invalid path must be rejected.")
            } catch (_: IllegalArgumentException) { }
            assertEquals(name, store.state.value.imageName)
            store.save(ReiAnixWallpaper())
            assertNull(store.state.value.imageName)
            assertFalse(file.exists())
            assertEquals(oldProfile, profile.state.value)
        } finally {
            store.save(ReiAnixWallpaper())
            file.delete()
        }
    }

    @Test
    fun legacyBannerIsRemovedWithoutChangingProfile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val profile = ReiAnixProfileStore.from(context)
        val initial = profile.state.value
        val prefs = context.getSharedPreferences("reianix_local_profile_v1", 0)
        val name = "b1234567.jpg"
        val obsolete = requireNotNull(profile.image(name))
        obsolete.parentFile?.mkdirs()
        obsolete.writeBytes(byteArrayOf(1))
        prefs.edit().putString("banner", name).commit()
        profile.removeLegacyBanner()
        assertFalse(prefs.contains("banner"))
        assertFalse(obsolete.exists())
        assertEquals(initial, profile.state.value)
    }
}
