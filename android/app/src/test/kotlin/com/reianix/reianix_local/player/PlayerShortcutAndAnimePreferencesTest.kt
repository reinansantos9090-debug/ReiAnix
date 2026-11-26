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
        val expected = listOf(PlayerShortcut.CAST, PlayerShortcut.EPISODES,
            PlayerShortcut.LOCK, PlayerShortcut.NEXT, PlayerShortcut.OPTIONS)
        assertEquals(expected, PlayerShortcutPolicy.default)
        assertEquals(expected, PlayerShortcutPolicy.parse(null))
        assertEquals("cast,episodes,lock,next,options",
            ReiAnixSettingsContract.defaultFor("player.shortcuts"))
        assertEquals(5, expected.size)
    }

    @Test fun landscapeGroupAlignsWithMiddleFiveShortcutWithoutChangingSpacing() {
        val shortcuts = PlayerShortcutPolicy.default
        assertEquals(PlayerShortcut.LOCK, shortcuts[2])
        assertEquals(PlayerShortcut.OPTIONS, shortcuts.last())
        val fraction = PlayerShortcutPolicy.FIVE_SHORTCUT_INSET_UNIT_FRACTION
        for (screenWidth in listOf(360f, 720f, 1504f)) {
            val left = screenWidth * fraction
            val right = screenWidth * (fraction * 3f)
            val middleShortcutX = left + (screenWidth - left - right) / 2f
            val groupShift = screenWidth * PlayerShortcutPolicy.centerActionGroupShiftFraction(shortcuts, landscape = true)
            val playX = screenWidth / 2f + groupShift
            assertEquals(middleShortcutX, playX, 0.001f)
            // Seek positions are symmetric about Play and receive the same shift.
            val seekDistance = screenWidth * 0.25f
            val backX = screenWidth / 2f - seekDistance + groupShift
            val forwardX = screenWidth / 2f + seekDistance + groupShift
            assertEquals(seekDistance, playX - backX, 0.001f)
            assertEquals(seekDistance, forwardX - playX, 0.001f)
        }
        val four = shortcuts.filterNot { it == PlayerShortcut.CAST }
        assertEquals(0f, PlayerShortcutPolicy.centerActionGroupShiftFraction(four, landscape = true), 0f)
    }
    @Test fun portraitPlayMatchesNativeSpinnerCenterWithoutChangingSeekSpacing() {
        val five = PlayerShortcutPolicy.default
        val four = five.filterNot { it == PlayerShortcut.CAST }
        for (screenWidth in listOf(360f, 720f, 828f, 1504f)) {
            val portraitShift = screenWidth *
                PlayerShortcutPolicy.centerActionGroupShiftFraction(five, landscape = false)
            val playCenter = screenWidth / 2f + portraitShift
            val nativeSpinnerCenter = screenWidth / 2f
            assertEquals(nativeSpinnerCenter, playCenter, 0.001f)

            val seekDistance = screenWidth * 0.25f
            val rewindCenter = playCenter - seekDistance
            val forwardCenter = playCenter + seekDistance
            assertEquals(seekDistance, playCenter - rewindCenter, 0.001f)
            assertEquals(seekDistance, forwardCenter - playCenter, 0.001f)
        }
        assertEquals(0f, PlayerShortcutPolicy.centerActionGroupShiftFraction(four, landscape = false), 0f)
        assertEquals(0f, PlayerShortcutPolicy.centerActionGroupShiftFraction(four, landscape = true), 0f)
        assertEquals(-PlayerShortcutPolicy.FIVE_SHORTCUT_INSET_UNIT_FRACTION,
            PlayerShortcutPolicy.centerActionGroupShiftFraction(five, landscape = true), 0f)
    }
    @Test fun remainingShortcutsPersistWithoutChangingTheDefaultOrFiveSlotLimit() {
        val selection = listOf(PlayerShortcut.ASPECT, PlayerShortcut.SKIP_OP_ED,
            PlayerShortcut.SPEED, PlayerShortcut.LOCK, PlayerShortcut.OPTIONS)
        val raw = "aspect,skip_op_ed,speed,lock,options"
        assertEquals(9, PlayerShortcut.entries.size)
        assertEquals(raw, ReiAnixSettingsContract.normalize("player.shortcuts", raw))
        assertEquals(selection, PlayerShortcutPolicy.parse(raw))
        assertEquals(selection, PlayerShortcutPolicy.toggle(selection, PlayerShortcut.CAST, true))
        assertEquals(PlayerShortcut.OPTIONS, selection.last())
        assertEquals(85_000L, PlayerShortcutPolicy.MANUAL_OP_ED_SKIP_MS)
        assertEquals("cast,episodes,lock,next,options", PlayerShortcutPolicy.defaultValue)
    }

    @Test fun removesThreeRetiredShortcutIdsAndKeepsRenamedAudio() {
        val removed = setOf("subtitles", "timer", "notes")
        assertFalse(PlayerShortcut.entries.any { it.key in removed })
        assertEquals("Trilhas sonoras", PlayerShortcut.AUDIO.displayName)
        assertEquals("audio,aspect,skip_op_ed,options",
            ReiAnixSettingsContract.normalize("player.shortcuts", "audio,aspect,notes,skip_op_ed,options"))
        assertEquals(listOf(PlayerShortcut.LOCK, PlayerShortcut.CAST, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.parse("timer,lock,subtitles,cast,options"))
        assertEquals(listOf(PlayerShortcut.AUDIO, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.parse("audio,subtitles,timer,notes,options"))
    }

    @Test fun aspectModesCycleAndPersistIncludingLegacyFill() {
        assertEquals(PlayerAspectMode.STRETCH, PlayerAspectMode.FIT.next())
        assertEquals(PlayerAspectMode.ZOOM, PlayerAspectMode.STRETCH.next())
        assertEquals(PlayerAspectMode.FIT, PlayerAspectMode.ZOOM.next())
        assertEquals(PlayerAspectMode.ZOOM, PlayerAspectMode.fromSetting("fill"))
        assertEquals(PlayerAspectMode.ZOOM, PlayerAspectMode.fromLabel("Preencher"))
        assertEquals("stretch", ReiAnixSettingsContract.normalize("player.aspect_ratio", "stretch"))
        val prefs = PlayerAnimePreferences(aspect = "stretch")
        assertEquals(prefs, PlayerAnimePreferences.decode(prefs.encode()))
        assertEquals("fill", PlayerAnimePreferences.decode(PlayerAnimePreferences(aspect = "fill").encode())?.aspect)
    }

    @Test fun supportsFiveAndPreservesDeliberatelyDisabledShortcuts() {
        val four = PlayerShortcutPolicy.toggle(PlayerShortcutPolicy.default, PlayerShortcut.CAST, false)
        assertEquals(listOf(PlayerShortcut.EPISODES, PlayerShortcut.LOCK,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), four)
        assertEquals(four, PlayerShortcutPolicy.parse(PlayerShortcutPolicy.normalize(
            four.joinToString(",") { it.key },
        )))
        val five = PlayerShortcutPolicy.toggle(four, PlayerShortcut.ASPECT, true)
        assertEquals(listOf(PlayerShortcut.ASPECT, PlayerShortcut.EPISODES, PlayerShortcut.LOCK,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), five)
        assertEquals(five, PlayerShortcutPolicy.toggle(five, PlayerShortcut.SKIP_OP_ED, true))
        assertEquals(five, PlayerShortcutPolicy.toggle(five, PlayerShortcut.OPTIONS, false))
    }

    @Test fun migratesHistoricalFiveButtonDefaultWithoutOverwritingCustomShortcuts() {
        assertEquals("cast,episodes,lock,next,options",
            PlayerShortcutPolicy.normalize("lock,episodes,speed,next,options"))
        assertEquals("cast,episodes,lock,next,options",
            ReiAnixSettingsContract.normalize("player.shortcuts", "lock,episodes,speed,next,options"))
        assertEquals("speed,episodes,next,options",
            PlayerShortcutPolicy.normalize("speed,episodes,next,options"))
        assertEquals("episodes,cast,next,options",
            PlayerShortcutPolicy.normalize("episodes,cast,next,options"))
        assertEquals("lock,episodes,cast,next,options",
            PlayerShortcutPolicy.normalize("lock,episodes,cast,next,options"))
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
        val four = PlayerShortcutPolicy.toggle(PlayerShortcutPolicy.default, PlayerShortcut.CAST, false)
        val activated = PlayerShortcutPolicy.toggle(four, PlayerShortcut.SKIP_OP_ED, true)
        assertEquals(listOf(PlayerShortcut.SKIP_OP_ED, PlayerShortcut.EPISODES, PlayerShortcut.LOCK,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), activated)
        val disabled = PlayerShortcutPolicy.toggle(activated, PlayerShortcut.SKIP_OP_ED, false)
        assertEquals(four, disabled)
        assertEquals(activated, PlayerShortcutPolicy.move(activated, PlayerShortcut.OPTIONS, -1))
        assertEquals("next,options", PlayerShortcutPolicy.normalize("options,next"))
    }

    @Test fun movingPreservesIdsAndOrdering() {
        assertEquals(listOf(PlayerShortcut.EPISODES, PlayerShortcut.CAST, PlayerShortcut.LOCK,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.move(PlayerShortcutPolicy.default, PlayerShortcut.EPISODES, -1))
    }

    @Test fun featureFlagsAreEnabledByDefaultAndCanBeDisabled() {
        assertEquals("true", ReiAnixSettingsContract.defaultFor("player.aniskip_enabled"))
        assertEquals("true", ReiAnixSettingsContract.defaultFor("player.anime_preferences_enabled"))
        assertEquals("false", ReiAnixSettingsContract.normalize("player.anime_preferences_enabled", "off"))
    }

    @Test fun draggingNeverReordersOptionsOrDisabledShortcuts() {
        val afterDrag = PlayerShortcutPolicy.move(PlayerShortcutPolicy.default, PlayerShortcut.LOCK, 1)
        assertEquals(listOf(PlayerShortcut.CAST, PlayerShortcut.EPISODES, PlayerShortcut.NEXT,
            PlayerShortcut.LOCK, PlayerShortcut.OPTIONS), afterDrag)
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.OPTIONS, -1))
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.ASPECT, -1))
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.LOCK, 1))
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
