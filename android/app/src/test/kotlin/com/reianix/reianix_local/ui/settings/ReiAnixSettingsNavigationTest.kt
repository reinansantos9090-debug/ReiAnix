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

    @Test fun everyPreferenceUsesAnExplicitDistinctIcon() {
        val keys = SettingsDestinations.flatMap { destination ->
            destination.entries.map { entry -> entry.key.removePrefix("setting:") }
        }.distinct()
        val icons = keys.map(::settingsIcon)
        assertEquals(keys.size, icons.size)
        assertEquals("Every preference must have its own icon", keys.size, icons.distinct().size)
        icons.forEach { assertNotEquals(Icons.Filled.Settings, it) }
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
