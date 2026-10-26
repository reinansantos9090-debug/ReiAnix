package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.settings.ReiAnixSettingKind
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReiAnixSettingsContractTest {
    @Test fun knownDefaultsAndTypesMatchLegacyContract() {
        assertEquals("dark", ReiAnixSettingsContract.defaultFor("appearance.theme"))
        assertEquals("true", ReiAnixSettingsContract.defaultFor("player.autoplay_next"))
        assertEquals("true", ReiAnixSettingsContract.defaultFor("gestures.double_tap"))
        assertEquals("false", ReiAnixSettingsContract.defaultFor("gestures.horizontal_seek"))
        assertEquals(ReiAnixSettingKind.LANGUAGE, ReiAnixSettingsContract.definitions["audio.preferred_language"]?.kind)
        assertNull(ReiAnixSettingsContract.defaultFor("future.legacy_key"))
    }

    @Test fun missingPersistedSettingsUseContractDefaultsWithoutRewritingThem() {
        val effective = ReiAnixSettingsContract.withDefaults(emptyMap())

        assertEquals("true", effective["appearance.show_thumbnails"])
        assertEquals("dark", effective["appearance.theme"])
        assertEquals("36", effective["library.page_size"])
        assertEquals("false", effective["player.zoom_enabled"])
        assertEquals("false", effective["gestures.volume"])
    }

    @Test fun canonicalPreferencesDoNotInheritMissingKeysFromStaleProjection() {
        val effective = ReiAnixSettingsContract.effectivePreferences(
            canonicalPreferences = mapOf("appearance.theme" to "light"),
            projectedPreferences = mapOf("appearance.show_thumbnails" to "false"),
        )

        assertEquals("light", effective["appearance.theme"])
        assertEquals("true", effective["appearance.show_thumbnails"])
        assertEquals("36", effective["library.page_size"])
    }

    @Test fun persistedPreferencesOverrideDefaultsAndLegacyKeysAreRetained() {
        val effective = ReiAnixSettingsContract.withDefaults(
            mapOf(
                "appearance.show_thumbnails" to "false",
                "appearance.theme" to "light",
                "plugin.legacy" to "custom-old-format",
            ),
        )

        assertEquals("false", effective["appearance.show_thumbnails"])
        assertEquals("light", effective["appearance.theme"])
        assertEquals("custom-old-format", effective["plugin.legacy"])
        assertEquals("false", effective["player.zoom_enabled"])
    }

    @Test fun normalizesBooleanAliasesWithoutChangingMeaning() {
        assertEquals("true", ReiAnixSettingsContract.normalize("player.autoplay_next", " YES "))
        assertEquals("false", ReiAnixSettingsContract.normalize("player.autoplay_next", "0"))
    }

    @Test fun rejectsInvalidKnownValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsContract.normalize("appearance.theme", "sepia")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsContract.normalize("library.page_size", "37")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsContract.normalize("player.autoplay_next", "sometimes")
        }
    }

    @Test fun preservesUnknownLegacyKeysAndValues() {
        assertEquals("custom-old-format", ReiAnixSettingsContract.normalize("plugin.legacy", "custom-old-format"))
    }

    @Test fun validatesLanguageTagsAndDecimalChoices() {
        assertEquals("pt-BR", ReiAnixSettingsContract.normalize("audio.preferred_language", "pt-BR"))
        assertEquals("1.5", ReiAnixSettingsContract.normalize("player.default_speed", "1.5"))
        assertEquals("1.0", ReiAnixSettingsContract.normalize("player.default_speed", "1.00"))
        assertEquals("5", ReiAnixSettingsContract.normalize("player.auto_hide_seconds", "05"))
        assertEquals("0", ReiAnixSettingsContract.normalize("player.auto_hide_seconds", "00"))
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixSettingsContract.normalize("audio.preferred_language", "not a language tag")
        }
    }
}
