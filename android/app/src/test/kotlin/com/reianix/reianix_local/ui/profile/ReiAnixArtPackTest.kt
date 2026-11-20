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
    fun catalogHasFortyFourUniqueImagesWithSeparateCategories() {
        val all = ReiAnixArtPack.banners + ReiAnixArtPack.wallpapers
        assertEquals(22, ReiAnixArtPack.banners.size)
        assertEquals(22, ReiAnixArtPack.wallpapers.size)
        assertEquals(44, all.map { it.path }.distinct().size)
        assertEquals(32, all.count { it.path.endsWith(".webp") })
        assertEquals(12, all.count { it.path.endsWith(".png") })
    }

    @Test
    fun allFortyFourArtsAreBundledAndReadableWithoutImport() {
        val context = RuntimeEnvironment.getApplication()
        for (art in ReiAnixArtPack.banners + ReiAnixArtPack.wallpapers) {
            val bytes = ReiAnixArtPack.open(context, art).use { it.readBytes() }
            assertTrue(bytes.size > 1000)
            if (art.extension == "png") {
                assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), bytes.copyOfRange(0, 8))
                val dimensions = java.nio.ByteBuffer.wrap(bytes, 16, 8)
                val width = dimensions.int
                val height = dimensions.int
                if (art.folder == "banners") assertEquals(width, height * 3)
                else assertEquals(width * 16, height * 9)
            } else {
                assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
            }
        }
        assertEquals(22, ReiAnixArtPack.available(true).size)
        assertEquals(22, ReiAnixArtPack.available(false).size)
    }

    @Test
    fun userSelectedReferenceImagesAreFirstInBothGalleries() {
        assertEquals(listOf(
            "samurai_rubro_cinematico",
            "metropole_violeta",
            "fuji_sakura_torii",
            "lua_sobre_montanhas",
            "eclipse_castelo",
            "oceano_azul",
        ), ReiAnixArtPack.available(true).take(6).map { it.id })
        assertEquals(listOf(
            "samurai_lua_vermelha",
            "cidade_chuvosa_violeta",
            "torii_via_lactea",
            "retrato_manga",
            "portal_floresta_luminosa",
            "pagoda_ao_entardecer",
        ), ReiAnixArtPack.available(false).take(6).map { it.id })
        assertEquals("chuva_neon", ReiAnixArtPack.available(true)[6].id)
        assertEquals("cafe_chuvoso", ReiAnixArtPack.available(false)[6].id)
        assertEquals("costa_estelar_turquesa", ReiAnixArtPack.available(true).last().id)
        assertEquals("templo_do_sol_poente", ReiAnixArtPack.available(false).last().id)
    }

    @Test
    fun referenceImagesCanBeAppliedOffline() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        for (isBanner in listOf(true, false)) {
            val store = if (isBanner) ReiAnixWallpaperStore.banner(context) else ReiAnixWallpaperStore.from(context)
            val art = ReiAnixArtPack.available(isBanner).first()
            val name = store.importPreset(art)
            val file = requireNotNull(store.file(name))
            try {
                assertArrayEquals(ReiAnixArtPack.open(context, art).use { it.readBytes() }, file.readBytes())
                store.save(ReiAnixWallpaper(imageName = name))
                assertEquals(name, store.state.value.imageName)
            } finally {
                store.save(ReiAnixWallpaper())
                store.discard(name)
            }
        }
    }

    @Test
    fun bundledPngCanBeAppliedAndRemovedWithoutImportingFromGallery() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ReiAnixWallpaperStore.banner(context)
        val art = ReiAnixArtPack.banners.first { it.extension == "png" }
        val name = store.importPreset(art)
        val file = requireNotNull(store.file(name))
        try {
            assertTrue(name.endsWith(".png"))
            assertTrue(file.isFile)
            store.save(ReiAnixWallpaper(imageName = name))
            assertEquals(name, store.state.value.imageName)
            store.discard(name)
            assertTrue(file.exists())
            store.save(ReiAnixWallpaper())
            assertFalse(file.exists())
        } finally {
            store.save(ReiAnixWallpaper())
            store.discard(name)
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
