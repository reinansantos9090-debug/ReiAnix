package com.reianix.reianix_local.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** JVM-only contract checks; no Android device or emulator is required. */
class ReiAnixSettingsNavigationTest {
    @Test fun rootCategoriesKeepRequestedCloudStreamOrder() {
        assertEquals(
            listOf(
                "Geral", "Player", "Metadados e Capas", "Dados e Backup",
                "Contas e Avançado", "Pastas", "Sobre",
            ),
            SettingsRootCategories.map { it.label },
        )
    }

    @Test fun oldSavedDestinationsStillOpenTheCorrectCurrentCategory() {
        assertEquals("Geral", settingsPageForDestination("appearance_theme"))
        assertEquals("Player", settingsPageForDestination("player_audio"))
        assertEquals("Dados e Backup", settingsPageForDestination("about_reports"))
        assertEquals("Pastas", settingsPageForDestination("library_storage"))
        assertEquals("Contas e Avançado", settingsPageForDestination("Contas"))
        assertEquals("Sobre", settingsPageForDestination("Sobre e Diagnóstico"))
        assertEquals("Sobre", settingsPageForDestination("about_app"))
        assertEquals(listOf("Sobre"), settingsPageSections("Sobre"))
        assertEquals(false, "Sobre" in settingsPageSections("Dados e Backup"))
        assertEquals(false, "Sobre e Diagnóstico" in settingsPageSections("Dados e Backup"))
        assertEquals(false, "about_diagnostics" in settingsPageSections("Dados e Backup"))
        assertEquals(false, "about_reports" in settingsPageSections("Dados e Backup"))
        assertEquals(true, "data_backup" in settingsPageSections("Dados e Backup"))
    }

    @Test fun licensesHaveUniqueEntriesAndIncludeTheProjectAndFont() {
        assertEquals(ReiAnixLicenseCredits.size, ReiAnixLicenseCredits.map { it.id }.distinct().size)
        assertEquals("Apache License 2.0", ReiAnixLicenseCredits.first { it.id == "reianix" }.licenseName)
        assertEquals("SIL Open Font License 1.1", ReiAnixLicenseCredits.first { it.id == "google-sans" }.licenseName)
    }

    @Test fun redundantLibraryDensityOptionIsNoLongerDisplayed() {
        val appearance = SettingsDestinations.first { it.id == "appearance_library" }
        assertEquals(false, appearance.entries.any { it.key == "setting:library.grid_density" })
        assertEquals(true, appearance.entries.any { it.key == "setting:appearance.poster_size" })
    }

    @Test fun everyPreferenceUsesAnExplicitDistinctIcon() {
        val keys = SettingsDestinations.flatMap { destination ->
            destination.entries.map { entry -> entry.key.removePrefix("setting:") }
        }.distinct()
        val icons = keys.map(::settingsIcon)
        assertEquals(keys.size, icons.size)
        assertEquals("Every preference must have its own icon", keys.size, icons.distinct().size)
        icons.forEach { assertNotEquals(Icons.Filled.Settings, it) }
    }

    @Test fun newlyAddedPlayerPreferencesUseDistinctIcons() {
        val aniskip = settingsIcon("player.aniskip_enabled")
        val animePreferences = settingsIcon("player.anime_preferences_enabled")
        assertNotEquals(Icons.Filled.Settings, aniskip)
        assertNotEquals(Icons.Filled.Settings, animePreferences)
        assertNotEquals(aniskip, animePreferences)
    }

    @Test fun immersiveApplicationOptionLivesInGeneralSettings() {
        val general = settingsDestination("Geral")!!
        assertEquals("Modo imersivo", general.entries.first { it.key == "setting:app.immersive" }.title)
        assertEquals(true, searchSettings("modo imersivo").any {
            it.destination.id == "Geral" && it.entry.key == "setting:app.immersive"
        })
    }

    @Test fun episodeAlertsLiveOnlyInMetadataAndKeepTheirSearchDestination() {
        val key = "setting:releases.notifications"
        assertEquals(false, settingsDestination("Geral")!!.entries.any { it.key == key })
        val metadataEntries = settingsDestination("Metadados e Capas")!!.entries
        assertEquals(true, metadataEntries.any { it.key == key })
        assertEquals(
            metadataEntries.indexOfFirst { it.key == "setting:artwork.enabled" } + 1,
            metadataEntries.indexOfFirst { it.key == key },
        )
        assertEquals("Metadados e Capas", searchSettings("avisos episódios")
            .single { it.entry.key == key }.destination.id)
        assertNotEquals(Icons.Filled.Settings, settingsIcon("releases.notifications"))
    }

    @Test fun settingLabelsExplainTheirActualBehavior() {
        val library = SettingsDestinations.first { it.id == "Biblioteca" }
        assertEquals("Animes por lote", library.entries.first { it.key == "setting:library.page_size" }.title)
        val appearance = SettingsDestinations.first { it.id == "appearance_library" }
        assertEquals(
            "Miniaturas de episódios na Home",
            appearance.entries.first { it.key == "setting:appearance.show_thumbnails" }.title,
        )
    }

    @Test fun dataAndBackupActionsHaveDistinctIcons() {
        val actions = listOf(
            "Restaurar", "Limpar cache de capas", "Exportar configurações",
            "Importar configurações", "Restaurar configurações", "Fazer backup",
            "Restaurar backup", "Verificar integridade", "Reconciliar arquivos",
        )
        val icons = actions.map(::settingsIcon)
        assertEquals(actions.size, icons.distinct().size)
        icons.forEach { assertNotEquals(Icons.Filled.Settings, it) }
    }
}
