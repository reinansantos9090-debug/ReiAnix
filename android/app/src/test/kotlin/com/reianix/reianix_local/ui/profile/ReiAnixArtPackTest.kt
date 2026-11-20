package com.reianix.reianix_local.ui.profile

import android.app.Application
import com.reianix.reianix_local.data.profile.ReiAnixArtPack
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReiAnixArtPackTest {
    @Test
    fun catalogHasTwentyUniqueImagesWithSeparateCategories() {
        val all = ReiAnixArtPack.banners + ReiAnixArtPack.wallpapers
        assertEquals(10, ReiAnixArtPack.banners.size)
        assertEquals(10, ReiAnixArtPack.wallpapers.size)
        assertEquals(20, all.map { it.path }.distinct().size)
        assertTrue(all.all { it.path.endsWith(".webp") })
    }

    @Test
    fun allTwentyArtsAreBundledAndReadableWithoutImport() {
        val context = RuntimeEnvironment.getApplication()
        for (art in ReiAnixArtPack.banners + ReiAnixArtPack.wallpapers) {
            val bytes = ReiAnixArtPack.open(context, art).use { it.readBytes() }
            assertTrue(bytes.size > 1000)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        }
        assertEquals(10, ReiAnixArtPack.available(true).size)
        assertEquals(10, ReiAnixArtPack.available(false).size)
    }

    @Test
    fun bannerAndWallpaperHaveIndependentFilesAndPreferences() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val banner = ReiAnixWallpaperStore.banner(context)
        val wallpaper = ReiAnixWallpaperStore.from(context)
        val original = wallpaper.state.value
        val name = "a1234567.webp"
        val file = requireNotNull(banner.file(name))
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            assertNotEquals(file, wallpaper.file(name))
            banner.save(ReiAnixWallpaper(imageName = name, zoom = 2f))
            assertEquals(name, banner.state.value.imageName)
            assertEquals(original, wallpaper.state.value)
            banner.save(ReiAnixWallpaper())
            assertFalse(file.exists())
            assertEquals(original, wallpaper.state.value)
        } finally {
            banner.save(ReiAnixWallpaper())
            file.delete()
        }
    }
}
