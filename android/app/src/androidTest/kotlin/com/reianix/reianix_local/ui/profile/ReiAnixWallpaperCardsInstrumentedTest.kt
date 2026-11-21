package com.reianix.reianix_local.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import com.reianix.reianix_local.R
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithText
import com.reianix.reianix_local.ui.collections.TrackingAnime
import com.reianix.reianix_local.ui.collections.TrackingCollectionsContent
import com.reianix.reianix_local.data.personal.ReiAnixWatchStatus
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.ui.ReiAnixAnimeCard
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import com.reianix.reianix_local.ui.navigation.ReiAnixNavigationHost
import com.reianix.reianix_local.ui.navigation.ReiAnixRoutes
import com.reianix.reianix_local.ui.ReiAnixEpisodeCard
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaAvailability
import com.reianix.reianix_local.ui.theme.LocalReiAnixWallpaperVisible
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReiAnixWallpaperCardsInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun transparentDestinationRevealsStableWallpaperAfterTheTransition() {
        val store = ReiAnixWallpaperStore.from(composeRule.activity)
        val original = store.state.value
        val originalFile = store.file(original.imageName)
        val originalBytes = originalFile?.takeIf { it.isFile }?.readBytes()
        val name = UUID.randomUUID().toString() + ".png"
        val file = requireNotNull(store.file(name))
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            runBlocking { store.save(ReiAnixWallpaper(imageName = name)) }
            lateinit var navController: NavHostController
            composeRule.setContent {
                ReiAnixComposeTheme(themeMode = "dark") {
                    navController = rememberNavController()
                    Box(Modifier.fillMaxSize().testTag("navigation-frame")) {
                        ReiAnixNavigationHost(
                            navController = navController,
                            home = {
                                if (LocalReiAnixWallpaperVisible.current) {
                                    Box(
                                        Modifier.fillMaxSize(.5f).background(Color.Green)
                                            .testTag("wallpaper-ready"),
                                    )
                                }
                            },
                            library = {},
                            search = {},
                            settings = {},
                            details = {},
                            player = {},
                            profile = {},
                            showBottomNavigation = false,
                        )
                    }
                }
            }

            composeRule.waitUntil(5_000L) {
                composeRule.onNodeWithTag("wallpaper-ready").isDisplayed()
            }
            fun frame() = composeRule.onNodeWithTag("navigation-frame").captureToImage().toPixelMap()

            val before = frame()
            assertTrue(before[before.width / 4, before.height / 4].green > .9f)
            val wallpaper = before[before.width * 3 / 4, before.height * 3 / 4]
            assertTrue(wallpaper.blue > .2f)
            composeRule.mainClock.autoAdvance = false
            try {
                composeRule.runOnIdle { navController.navigate(ReiAnixRoutes.PROFILE) }
                composeRule.mainClock.advanceTimeBy(96L)
                val during = frame()
                assertEquals(wallpaper, during[during.width * 3 / 4, during.height * 3 / 4])
                composeRule.mainClock.advanceTimeBy(750L)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            val settled = frame()
            assertEquals(wallpaper, settled[settled.width / 4, settled.height / 4])
        } finally {
            if (originalBytes != null) originalFile?.writeBytes(originalBytes)
            runBlocking { store.save(if (originalBytes != null) original else ReiAnixWallpaper()) }
            file.delete()
        }
    }

    @Test
    fun collectorDashboardSeparatesSectionsAndRetainsAutomaticRefresh() {
        val journey = com.reianix.reianix_local.data.collector.ReiAnixCollectorJourneyCalculator.build(emptyList(), null)
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                com.reianix.reianix_local.ui.collector.ReiAnixCollectorJourneyScreen(
                    state = com.reianix.reianix_local.viewmodel.ReiAnixCollectorJourneyUiState(journey = journey),
                    onBack = {}, onRefresh = {}, onSelectTitle = {},
                )
            }
        }
        composeRule.onNodeWithText("Nível 1").assertIsDisplayed()
        composeRule.onNodeWithText("Atualizar").assertDoesNotExist()
        composeRule.onNodeWithText("Estatísticas da jornada").performClick()
        composeRule.onNodeWithText("Animes concluídos").assertIsDisplayed()
        composeRule.onNodeWithText("Nível 1").assertDoesNotExist()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("Nível 1").assertIsDisplayed()
    }

    @Test
    fun historyDisplaysActualEpisodeNumberAndPlaybackTime() {
        assertEquals("Episódio 01", profileEpisodeLabel(1.0, null, "video.mp4"))
        assertEquals("Episódio 12 · Final", profileEpisodeLabel(12.0, "Final", "video.mp4"))
        assertEquals("08:25", profilePlaybackTime(505.0))
        assertEquals("1:01:01", profilePlaybackTime(3661.0))
        assertEquals("00:00", profilePlaybackTime(Double.NaN))
        val now = java.util.Calendar.getInstance()
        assertEquals("Hoje", historyGroup(now.timeInMillis / 1000.0, now))
        val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        assertEquals("Ontem", historyGroup(yesterday.timeInMillis / 1000.0, now))
        val row = com.reianix.reianix_local.data.sqlite.ReiAnixCollectorEpisodeRow(
            id = 1L, animeId = 42L, progress = 505.0, duration = 1440.0, watched = false,
            missing = false, lastPlayedAt = now.timeInMillis / 1000.0, episodeType = null,
            animeTitle = "Teste", year = null, genresJson = null, format = null, mediaKind = null,
        )
        assertTrue(!profileHistoryCompleted(row))
        assertTrue(profileHistoryCompleted(row.copy(watched = true)))
        assertTrue(profileHistoryCompleted(row.copy(progress = 1400.0)))
    }

    @Test
    fun statisticsShowsRealMetricsAndProgress() {
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                ProfileStatisticsContent(ProfileStats(57, 602, 100, 600.0, 1, 3, emptyList(), 60), false)
            }
        }
        composeRule.onNodeWithText("57").assertIsDisplayed()
        composeRule.onNodeWithText("602").assertIsDisplayed()
        composeRule.onNodeWithText("60 de 602 episódios concluídos · 9%").assertExists()
        composeRule.onNodeWithText("Gêneros mais assistidos").assertExists()
    }

    @Test
    fun trackingCategoriesOpenTheirOwnListWithRealProgress() {
        var opened = 0L
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                TrackingCollectionsContent(
                    listOf(TrackingAnime(42, "Anime de teste", null, null, ReiAnixWatchStatus.WATCHING, 12, 3)),
                    false, null, {}, { opened = it }, {},
                )
            }
        }
        composeRule.onNodeWithText("Quero assistir").assertExists()
        composeRule.onNodeWithText("Anime de teste").assertDoesNotExist()
        composeRule.onNodeWithText("Assistindo").performClick()
        composeRule.onNodeWithText("12 episódios disponíveis").assertIsDisplayed()
        composeRule.onNodeWithText("3/12 concluídos").assertIsDisplayed()
        composeRule.onNodeWithText("Anime de teste").performClick()
        assertEquals(42L, opened)
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("Quero assistir").assertIsDisplayed()
    }

    @Test
    fun unifiedEditorAutosavesIdentityAndFrameWithoutGlobalSave() {
        val store = ReiAnixProfileStore.from(composeRule.activity)
        val original = store.state.value
        try {
            runBlocking { store.save(original.copy(frame = "none")) }
            composeRule.setContent {
                ReiAnixComposeTheme(themeMode = "dark") {
                    ProfileCustomization(store, emptyList(), onBack = {})
                }
            }
            listOf("Avatares personalizados", "Moldura", "Banner do perfil", "Papel de parede", "Anime em destaque").forEach {
                composeRule.onNodeWithText(it).assertExists()
            }
            composeRule.onNodeWithText("Editar perfil").assertIsDisplayed()
            composeRule.onNodeWithText("Nome do usuário").performScrollTo().performTextReplacement("Otaku automático")
            composeRule.onNodeWithText("Biografia").performScrollTo().performTextReplacement("Minha biblioteca")
            composeRule.waitUntil(timeoutMillis = 5000) {
                store.state.value.name == "Otaku automático" && store.state.value.bio == "Minha biblioteca"
            }
            composeRule.onNodeWithText("Cor do perfil").assertDoesNotExist()
            composeRule.onNodeWithText("Salvar perfil").assertDoesNotExist()
            composeRule.onNodeWithText("Salvar alterações").assertDoesNotExist()
            composeRule.onNodeWithText("Escolher foto da galeria").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Alterar foto de perfil").assertExists()
            composeRule.onNodeWithText("Avatares personalizados").performScrollTo().performClick()
            composeRule.onNodeWithText("Escolha um avatar").assertExists()
            composeRule.onNodeWithText("Escolher foto da galeria").assertDoesNotExist()
            composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Moldura").performScrollTo().performClick()
            composeRule.onNodeWithText("Dourada royal").assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5000) { store.state.value.frame == "gold" }
            assertEquals(original.color, store.state.value.color)
            composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Editar perfil").assertIsDisplayed()
            assertEquals("Otaku automático", store.state.value.name)
            assertEquals("Minha biblioteca", store.state.value.bio)
            composeRule.onNodeWithText("Dourada royal").assertDoesNotExist()
        } finally {
            runBlocking { store.save(original) }
        }
    }

    @Test
    fun animatedProfileFramesCanBeSelectedAndSavedWithoutSeparateAnimationSetting() {
        val store = ReiAnixProfileStore.from(composeRule.activity)
        val original = store.state.value
        try {
            runBlocking { store.save(original.copy(frame = "none")) }
            composeRule.setContent {
                ReiAnixComposeTheme(themeMode = "dark") {
                    ProfileCustomization(store, emptyList(), onBack = {})
                }
            }
            composeRule.onNodeWithText("Moldura").performScrollTo().performClick()
            composeRule.onNodeWithTag("profile-frame-preview").assertIsDisplayed()
            composeRule.onNodeWithText("Gatinho mágico").assertExists()
            composeRule.onNodeWithText("Fone gamer").assertDoesNotExist()
            for (frame in listOf("neon", "sakura", "cat_ears", "gold", "galaxy")) {
                composeRule.onNodeWithTag("profile-frame-option-" + frame)
                    .performScrollTo().performClick()
                composeRule.waitUntil(timeoutMillis = 5000) { store.state.value.frame == frame }
                composeRule.onAllNodesWithTag("profile-frame-animation-" + frame, useUnmergedTree = true)
                    .assertCountEquals(2)
                if (frame == "gold" || frame == "cat_ears") {
                    composeRule.onAllNodesWithTag("profile-frame-top-accessory-" + frame, useUnmergedTree = true)
                        .assertCountEquals(2)
                }
                if (frame == "cat_ears") {
                    // The ears must stay still; no separate animated layer.
                    composeRule.onAllNodesWithTag("profile-frame-cat-left-ear", useUnmergedTree = true)
                        .assertCountEquals(0)
                    composeRule.onAllNodesWithTag("profile-frame-cat-right-ear", useUnmergedTree = true)
                        .assertCountEquals(0)
                }
            }
            composeRule.onNodeWithTag("profile-frame-option-white")
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5000) { store.state.value.frame == "white" }
            composeRule.onNodeWithTag("profile-frame-option-none")
                .performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5000) { store.state.value.frame == "none" }
        } finally {
            runBlocking { store.save(original) }
        }
    }

    @Test
    fun everyIllustratedFrameIsBundledOfflineWithTransparentPortraitArea() {
        val resources = composeRule.activity.resources
        val art = listOf(
            "gold" to R.drawable.profile_frame_gold,
            "neon" to R.drawable.profile_frame_neon,
            "galaxy" to R.drawable.profile_frame_galaxy,
            "cat_ears" to R.drawable.profile_frame_cat_ears,
        )
        art.forEach { (name, drawableId) ->
            assertTrue("$name should be illustrated", isIllustratedProfileFrame(name))
            val bitmap = BitmapFactory.decodeResource(resources, drawableId)
            requireNotNull(bitmap) { "Artwork missing for $name" }
            try {
                assertTrue("$name is too small", bitmap.width >= 180 && bitmap.height >= 180)
                assertTrue("$name must have transparency", bitmap.hasAlpha())
                val centerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                assertTrue("$name obscures the profile photo", AndroidColor.alpha(centerPixel) < 64)
                if (name == "gold" || name == "cat_ears") {
                    // Crown and accessories should not form a heavy circular border.
                    val bottomPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height * 9 / 10)
                    assertTrue("$name must have a clean lower edge", AndroidColor.alpha(bottomPixel) < 64)
                }
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue("Sakura is kept on its original implementation",
            !isIllustratedProfileFrame("sakura"))
    }

    @Test
    fun crownAndCatEarsTouchPortraitTopWithoutPaintingWholeSheet() {
        // Shared layout metrics prevent the exact regression shown in screenshots:
        // centering a padded PNG puts the crown/ears over the face or floats them away.
        for (frame in listOf("gold", "cat_ears")) {
            val geometry = requireNotNull(topAccessoryGeometry(frame))
            assertTrue("$frame not anchored to photo", geometry.artworkBottom > geometry.portraitTop)
            assertTrue("$frame covers too much face",
                geometry.artworkBottom - geometry.portraitTop < 0.09f)
            assertTrue("$frame ornament does not rise above photo",
                geometry.artworkTop < geometry.portraitTop)
            assertTrue("$frame portrait exceeds avatar bounds",
                geometry.portraitTop + geometry.portraitDiameter <= 1f)
        }
        assertTrue("Sakura was changed", topAccessoryGeometry("sakura") == null)
        assertTrue("Neon was changed", topAccessoryGeometry("neon") == null)
    }

    @Test
    fun deprecatedHeadsetSelectionFallsBackToNoFrame() {
        val store = ReiAnixProfileStore.from(composeRule.activity)
        val original = store.state.value
        try {
            runBlocking { store.save(original.copy(frame = "headset")) }
            assertEquals("none", store.state.value.frame)
            assertTrue(profileFrameOptions.none { it.id == "headset" })
            assertTrue(!isIllustratedProfileFrame("headset"))
            assertTrue(!isAnimatedProfileFrame("headset"))
        } finally {
            runBlocking { store.save(original) }
        }
    }

    @Test
    fun photoAndCameraBadgeShareOneClickTarget() {
        val store = ReiAnixProfileStore.from(composeRule.activity)
        var opened = 0
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                EditableProfileAvatar(store.state.value, store, onSelectPhoto = { opened++ })
            }
        }
        val avatar = composeRule.onNodeWithContentDescription("Alterar foto de perfil")
        avatar.performTouchInput { click(center) }
        avatar.performTouchInput { click(Offset(width * 0.8535534f, height * 0.8535534f)) }
        composeRule.runOnIdle { assertEquals(2, opened) }
    }

    @Test
    fun posterAndEpisodeMetadataRevealWallpaperOnlyWhenEnabled() {
        val active = mutableStateOf(true)
        val wallpaperColor = Color(0xFF244A66)
        val episode = ReiAnixEpisodeUiModel(
            id = 1, animeId = 2, seasonNumber = 1, number = 1.0,
            title = "Episódio", fileName = "episode.mp4",
            media = ReiAnixLocalMediaUiModel("episode.mp4", null, "episode.mp4",
                null, null, ReiAnixMediaAvailability.AVAILABLE),
            progressSeconds = null, durationSeconds = null, watched = false,
            consumptionState = null, artwork = null,
        )
        composeRule.setContent {
            ReiAnixComposeTheme(themeMode = "dark") {
                Box(Modifier.fillMaxSize().background(wallpaperColor)) {
                    CompositionLocalProvider(LocalReiAnixWallpaperVisible provides active.value) {
                        Column {
                            ReiAnixAnimeCard(title = "Anime", artworkPath = null,
                                metadata = listOf("2026"), modifier = Modifier.width(132.dp).testTag("wallpaper-anime"))
                            ReiAnixEpisodeCard(episode = episode, showThumbnail = false,
                                modifier = Modifier.width(280.dp).testTag("wallpaper-episode"))
                        }
                    }
                }
            }
        }
        fun fraction(tag: String): Float {
            val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            val left = (pixels.width * .72f).toInt()
            val right = (pixels.width * .90f).toInt()
            val top = (pixels.height * .86f).toInt()
            val bottom = (pixels.height * .94f).toInt()
            var matching = 0
            var count = 0
            for (y in top until bottom) for (x in left until right) {
                val p = pixels[x, y]
                if (kotlin.math.abs(p.red - wallpaperColor.red) < .025f &&
                    kotlin.math.abs(p.green - wallpaperColor.green) < .025f &&
                    kotlin.math.abs(p.blue - wallpaperColor.blue) < .025f) matching++
                count++
            }
            return matching.toFloat() / count.coerceAtLeast(1)
        }
        assertTrue("Anime metadata should reveal the wallpaper", fraction("wallpaper-anime") > .5f)
        assertTrue("Episode row should reveal the wallpaper", fraction("wallpaper-episode") > .5f)
        composeRule.runOnIdle { active.value = false }
        composeRule.waitForIdle()
        assertTrue("Original anime background should be restored", fraction("wallpaper-anime") < .1f)
        assertTrue("Original episode background should be restored", fraction("wallpaper-episode") < .1f)
    }
}
