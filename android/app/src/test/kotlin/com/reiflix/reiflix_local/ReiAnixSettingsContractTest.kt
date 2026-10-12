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
