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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private fun archive(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry(name))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun rejectsTraversalAndIncompletePacks() {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "art-pack-test")
        val stage = File(root, "stage")
        stage.mkdirs()
        try {
            for (name in listOf("../outside.webp", "README.txt")) {
                try {
                    ReiAnixArtPack.extract(ByteArrayInputStream(archive(name)), stage)
                    fail("Invalid archive accepted")
                } catch (_: IllegalArgumentException) { }
            }
            assertFalse(File(root, "outside.webp").exists())
        } finally {
            root.deleteRecursively()
        }
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
