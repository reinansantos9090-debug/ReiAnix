package com.reianix.reianix_local.ui.profile

import android.app.Application
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import com.reianix.reianix_local.ui.theme.boxBlurPixels
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
            positionY = 5f, dim = Float.POSITIVE_INFINITY, blur = 3f).normalized()
        assertNull(value.imageName)
        assertEquals(1f, value.zoom, 0f)
        assertEquals(0f, value.positionX, 0f)
        assertEquals(1f, value.positionY, 0f)
        assertEquals(.65f, value.dim, 0f)
        assertEquals(1f, value.blur, 0f)
    }

    @Test
    fun blurKeepsUniformPixelsAndSoftensContrast() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertArrayEquals(IntArray(9) { white }, boxBlurPixels(IntArray(9) { white }, 3, 3, 8))
        val source = intArrayOf(black, white, black)
        val result = boxBlurPixels(source, 3, 1, 1)
        assertTrue((result[1] and 255) in 1..254)
        assertTrue((result[0] and 255) in 1..254)
        assertArrayEquals(source, boxBlurPixels(source, 3, 1, 0))
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
            store.save(ReiAnixWallpaper(imageName = name, zoom = 2f, dim = .8f, blur = .25f))
            assertEquals(name, store.state.value.imageName)
            assertEquals(2f, store.state.value.zoom, 0f)
            val prefs = context.getSharedPreferences("reianix_wallpaper_v1", 0)
            assertEquals(name, prefs.getString("image", null))
            assertEquals(.8f, prefs.getFloat("dim", 0f), 0f)
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
