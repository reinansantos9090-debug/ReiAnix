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
    @Test fun keepsFourCurrentShortcutsByDefault() {
        assertEquals("lock,episodes,next,options", PlayerShortcutPolicy.default.joinToString(",") { it.key })
        assertEquals(PlayerShortcutPolicy.default, PlayerShortcutPolicy.parse(null))
        assertEquals(4, PlayerShortcutPolicy.default.size)
    }

    @Test fun supportsFiveAndKeepsMenuReachable() {
        val five = PlayerShortcutPolicy.toggle(PlayerShortcutPolicy.default, PlayerShortcut.CAPTURE, true)
        assertEquals(5, five.size)
        assertEquals(five, PlayerShortcutPolicy.toggle(five, PlayerShortcut.TIMER, true))
        assertEquals(five, PlayerShortcutPolicy.toggle(five, PlayerShortcut.OPTIONS, false))
        assertEquals("capture,lock,episodes,next,options", ReiAnixSettingsContract.normalize("player.shortcuts", five.joinToString(",") { it.key }))
        assertEquals(listOf(PlayerShortcut.NEXT, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.parse("options,next"))
    }

    @Test fun rejectsInvalidDuplicatedOrMissingOptions() {
        for (bad in listOf("lock,next", "options,options", "x,options", "lock,episodes,next,options,speed,timer")) {
            assertThrows(IllegalArgumentException::class.java) { ReiAnixSettingsContract.normalize("player.shortcuts", bad) }
        }
        assertEquals(PlayerShortcutPolicy.default, PlayerShortcutPolicy.parse("invalid"))
    }

    @Test fun activatingAndDisablingKeepsOptionsLast() {
        val activated = PlayerShortcutPolicy.toggle(PlayerShortcutPolicy.default, PlayerShortcut.CAPTURE, true)
        assertEquals(listOf(PlayerShortcut.CAPTURE, PlayerShortcut.LOCK, PlayerShortcut.EPISODES,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), activated)
        val disabled = PlayerShortcutPolicy.toggle(activated, PlayerShortcut.CAPTURE, false)
        assertEquals(PlayerShortcutPolicy.default, disabled)
        assertEquals(activated, PlayerShortcutPolicy.move(activated, PlayerShortcut.OPTIONS, -1))
        assertEquals("next,options", PlayerShortcutPolicy.normalize("options,next"))
    }

    @Test fun movingPreservesIdsAndOrdering() {
        assertEquals(listOf(PlayerShortcut.EPISODES, PlayerShortcut.LOCK, PlayerShortcut.NEXT, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.move(PlayerShortcutPolicy.default, PlayerShortcut.EPISODES, -1))
    }

    @Test fun featureFlagsAreEnabledByDefaultAndCanBeDisabled() {
        assertEquals("true", ReiAnixSettingsContract.defaultFor("player.aniskip_enabled"))
        assertEquals("true", ReiAnixSettingsContract.defaultFor("player.anime_preferences_enabled"))
        assertEquals("false", ReiAnixSettingsContract.normalize("player.anime_preferences_enabled", "off"))
    }

    @Test fun draggingNeverReordersOptionsOrDisabledShortcuts() {
        val five = PlayerShortcutPolicy.toggle(PlayerShortcutPolicy.default, PlayerShortcut.CAPTURE, true)
        val afterDrag = PlayerShortcutPolicy.move(five, PlayerShortcut.CAPTURE, 1)
        assertEquals(listOf(PlayerShortcut.LOCK, PlayerShortcut.CAPTURE, PlayerShortcut.EPISODES,
            PlayerShortcut.NEXT, PlayerShortcut.OPTIONS), afterDrag)
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.OPTIONS, -1))
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.TIMER, -1))
        assertEquals(afterDrag, PlayerShortcutPolicy.move(afterDrag, PlayerShortcut.NEXT, 1))
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
