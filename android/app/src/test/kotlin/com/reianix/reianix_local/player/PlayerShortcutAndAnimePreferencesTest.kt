package com.reianix.reianix_local.player

import com.reianix.reianix_local.data.settings.ReiAnixSettingsContract
import org.junit.Assert.*
import org.junit.Test

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
        assertEquals("lock,episodes,next,options,capture", ReiAnixSettingsContract.normalize("player.shortcuts", five.joinToString(",") { it.key }))
        assertEquals(listOf(PlayerShortcut.OPTIONS, PlayerShortcut.NEXT),
            PlayerShortcutPolicy.parse("options,next"))
    }

    @Test fun rejectsInvalidDuplicatedOrMissingOptions() {
        for (bad in listOf("lock,next", "options,options", "x,options", "lock,episodes,next,options,speed,timer")) {
            assertThrows(IllegalArgumentException::class.java) { ReiAnixSettingsContract.normalize("player.shortcuts", bad) }
        }
        assertEquals(PlayerShortcutPolicy.default, PlayerShortcutPolicy.parse("invalid"))
    }

    @Test fun movingPreservesIdsAndOrdering() {
        assertEquals(listOf(PlayerShortcut.EPISODES, PlayerShortcut.LOCK, PlayerShortcut.NEXT, PlayerShortcut.OPTIONS),
            PlayerShortcutPolicy.move(PlayerShortcutPolicy.default, PlayerShortcut.EPISODES, -1))
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
