package com.reianix.reianix_local.player

import com.reianix.reianix_local.data.settings.ReiAnixSettingsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// JSONObject comes from the Android runtime; use the same JVM runtime as the
// existing player enhancement and local-metadata tests.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class PlayerShortcutAndAnimePreferencesTest {
    @Test fun defaultsToFiveInRequestedOrder() {
        val expected = listOf(PlayerShortcut.LOCK, PlayerShortcut.EPISODES,
            PlayerShortcut.SPEED, PlayerShortcut.NEXT, PlayerShortcut.OPTIONS)
        assertEquals(expected, PlayerShortcutPolicy.default)
        assertEquals(expected, PlayerShortcutPolicy.parse(null))
        assertEquals("lock,episodes,speed,next,options",
            ReiAnixSettingsContract.defaultFor("player.shortcuts"))
        assertEquals(5, expected.size)
    }

    @Test fun centerPlayerGroupAlignsWithMiddleFiveShortcutWithoutChangingSpacing() {
        val shortcuts = PlayerShortcutPolicy.default
        assertEquals(PlayerShortcut.SPEED, shortcuts[2])
        assertEquals(PlayerShortcut.OPTIONS, shortcuts.last())
        val fraction = PlayerShortcutPolicy.FIVE_SHORTCUT_INSET_UNIT_FRACTION
        for (screenWidth in listOf(360f, 720f, 1504f)) {
            val left = screenWidth * fraction
            val right = screenWidth * (fraction * 3f)
            val middleShortcutX = left + (screenWidth - left - right) / 2f
            val groupShift = screenWidth * PlayerShortcutPolicy.centerActionGroupShiftFraction(shortcuts)
            val playX = screenWidth / 2f + groupShift
            assertEquals(middleShortcutX, playX, 0.001f)
            // Seek positions are symmetric about Play and receive the same shift.
            val seekDistance = screenWidth * 0.25f
            val backX = screenWidth / 2f - seekDistance + groupShift
            val forwardX = screenWidth / 2f + seekDistance + groupShift
            assertEquals(seekDistance, playX - backX, 0.001f)
            assertEquals(seekDistance, forwardX - playX, 0.001f)
        }
        val four = shortcuts.filterNot { it == PlayerShortcut.SPEED }
        assertEquals(0f, PlayerShortcutPolicy.centerActionGroupShiftFraction(four), 0f)
    }
    @Test fun portraitOnlyMovesTheWholeCenterGroupRight() {
        val five = PlayerShortcutPolicy.default
        val four = five.filterNot { it == PlayerShortcut.SPEED }
        val portraitDp = PlayerShortcutPolicy.centerActionGroupPortraitCorrectionDp(five, landscape = false)
        val landscapeDp = PlayerShortcutPolicy.centerActionGroupPortraitCorrectionDp(five, landscape = true)
        assertEquals(4f, portraitDp, 0f)
        assertEquals(0f, landscapeDp, 0f)
        assertEquals(0f, PlayerShortcutPolicy.centerActionGroupPortraitCorrectionDp(four, landscape = false), 0f)
        assertEquals(0f, PlayerShortcutPolicy.centerActionGroupPortraitCorrectionDp(four, landscape = true), 0f)

        // No per-button shift: both seek buttons follow Play by the exact
        // same amount and retain their original center-to-center distances.
        for (widthDp in listOf(320f, 410f, 560f)) {
            val unchangedShift = widthDp * PlayerShortcutPolicy.centerActionGroupShiftFraction(five)
            val portraitShift = unchangedShift + portraitDp
            assertEquals(4f, portraitShift - unchangedShift, 0.0001f)
            val seekDistance = widthDp * 0.25f
            val play = widthDp / 2f + portraitShift
            val back = widthDp / 2f - seekDistance + portraitShift
            val forward = widthDp / 2f + seekDistance + portraitShift
            assertEquals(seekDistance, play - back, 0.0001f)
            assertEquals(seekDistance, forward - play, 0.0001f)
        }
    }
    @Test fun supportsFiveAndPreservesDeliberatelyDisabledShortcuts() {
        val four = PlayerShortcutPolicy.toggle(PlayerShortcutPolicy.default, PlayerShortcut.SPEED, false)
        assertEquals(listOf(PlayerShortcut.LOCK, PlayerShortcut.EPISODES,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), four)
        assertEquals(four, PlayerShortcutPolicy.parse(PlayerShortcutPolicy.normalize(
            four.joinToString(",") { it.key },
        )))
        val five = PlayerShortcutPolicy.toggle(four, PlayerShortcut.TIMER, true)
        assertEquals(listOf(PlayerShortcut.TIMER, PlayerShortcut.LOCK, PlayerShortcut.EPISODES,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), five)
        assertEquals(five, PlayerShortcutPolicy.toggle(five, PlayerShortcut.SUBTITLES, true))
        assertEquals(five, PlayerShortcutPolicy.toggle(five, PlayerShortcut.OPTIONS, false))
    }

    @Test fun removesRetiredCaptureFromStoredPreferencesAndBackups() {
        assertFalse(PlayerShortcut.entries.any { it.key == "capture" })
        assertEquals("lock,episodes,next,options",
            ReiAnixSettingsContract.normalize("player.shortcuts", "capture,lock,episodes,next,options"))
        assertEquals(listOf(PlayerShortcut.LOCK, PlayerShortcut.EPISODES,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.parse("capture,lock,episodes,next,options"))
        assertEquals("episodes,lock,options",
            PlayerShortcutPolicy.normalize("capture,episodes,lock,options"))
        assertEquals("speed,episodes,options",
            PlayerShortcutPolicy.normalize("speed,capture,episodes,options"))
    }

    @Test fun rejectsInvalidDuplicatedOrMissingOptions() {
        for (bad in listOf("lock,next", "options,options", "x,options",
            "lock,episodes,next,options,speed,timer", "capture,capture,options", "capture")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReiAnixSettingsContract.normalize("player.shortcuts", bad)
            }
        }
        assertEquals(PlayerShortcutPolicy.default, PlayerShortcutPolicy.parse("invalid"))
    }

    @Test fun activatingAndDisablingKeepsOptionsLast() {
        val four = PlayerShortcutPolicy.toggle(PlayerShortcutPolicy.default, PlayerShortcut.SPEED, false)
        val activated = PlayerShortcutPolicy.toggle(four, PlayerShortcut.SUBTITLES, true)
        assertEquals(listOf(PlayerShortcut.SUBTITLES, PlayerShortcut.LOCK, PlayerShortcut.EPISODES,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), activated)
        val disabled = PlayerShortcutPolicy.toggle(activated, PlayerShortcut.SUBTITLES, false)
        assertEquals(four, disabled)
        assertEquals(activated, PlayerShortcutPolicy.move(activated, PlayerShortcut.OPTIONS, -1))
        assertEquals("next,options", PlayerShortcutPolicy.normalize("options,next"))
    }

    @Test fun movingPreservesIdsAndOrdering() {
        assertEquals(listOf(PlayerShortcut.EPISODES, PlayerShortcut.LOCK, PlayerShortcut.SPEED,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.move(PlayerShortcutPolicy.default, PlayerShortcut.EPISODES, -1))
    }

    @Test fun featureFlagsAreEnabledByDefaultAndCanBeDisabled() {
        assertEquals("true", ReiAnixSettingsContract.defaultFor("player.aniskip_enabled"))
        assertEquals("true", ReiAnixSettingsContract.defaultFor("player.anime_preferences_enabled"))
        assertEquals("false", ReiAnixSettingsContract.normalize("player.anime_preferences_enabled", "off"))
    }

    @Test fun draggingNeverReordersOptionsOrDisabledShortcuts() {
        val afterDrag = PlayerShortcutPolicy.move(PlayerShortcutPolicy.default, PlayerShortcut.SPEED, 1)
        assertEquals(listOf(PlayerShortcut.LOCK, PlayerShortcut.EPISODES, PlayerShortcut.NEXT,
            PlayerShortcut.SPEED, PlayerShortcut.OPTIONS), afterDrag)
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.OPTIONS, -1))
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.TIMER, -1))
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.SPEED, 1))
    }

    @Test fun dragPreviewHysteresisPreventsJitterAtBoundary() {
        val px = 48f
        assertEquals(1, PlayerShortcutPolicy.dragPreviewIndex(1, 1, px * 0.60f, px, 3))
        assertEquals(2, PlayerShortcutPolicy.dragPreviewIndex(1, 1, px * 0.70f, px, 3))
        assertEquals(2, PlayerShortcutPolicy.dragPreviewIndex(1, 2, px * 0.45f, px, 3))
        assertEquals(1, PlayerShortcutPolicy.dragPreviewIndex(1, 2, px * 0.30f, px, 3))
        assertEquals(3, PlayerShortcutPolicy.dragPreviewIndex(1, 1, px * 5f, px, 3))
        assertEquals(0, PlayerShortcutPolicy.dragPreviewIndex(2, 2, px * -5f, px, 3))
        assertEquals(1, PlayerShortcutPolicy.dragPreviewIndex(1, 1, Float.NaN, px, 3))
    }

    @Test fun perAnimeSettingsRemainIsolatedAndPreserveTrackIdentity() {
        val onePiece = PlayerAnimePreferences(
            speed = 1.25f,
            audioTrack = PlayerAnimeTrackChoice("selected", "ja", "Japanese stereo", "audio/aac"),
            subtitleTrack = PlayerAnimeTrackChoice("selected", "pt", "Português", "text/x-ssa"),
        )
        assertEquals(onePiece, PlayerAnimePreferences.decode(onePiece.encode()))
        assertEquals("player.anime.101", PlayerAnimePreferences.keyFor("101"))
        assertNull(PlayerAnimePreferences.keyFor("content://wrong"))
        assertNull(PlayerAnimePreferences.keyFor("-20"))
        assertNull(PlayerAnimePreferences.decode("{broken"))
    }
}
