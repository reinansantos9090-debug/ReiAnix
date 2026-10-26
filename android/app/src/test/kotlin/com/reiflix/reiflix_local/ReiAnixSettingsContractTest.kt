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

    @Test fun missingAndInvalidKnownSettingsUseContractDefaultsWithoutOverwritingValidValues() {
        val effective = ReiAnixSettingsContract.withDefaults(
            mapOf(
                "appearance.theme" to "light",
                "appearance.show_thumbnails" to "false",
                "player.autoplay_next" to "invalid",
                "legacy.custom_option" to "preserve-me",
            ),
        )

        assertEquals("light", effective["appearance.theme"])
        assertEquals("false", effective["appearance.show_thumbnails"])
        assertEquals("true", effective["player.autoplay_next"])
        assertEquals("false", effective["gestures.horizontal_seek"])
        assertEquals("preserve-me", effective["legacy.custom_option"])
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
