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
                "Geral", "Player", "Metadados e Capas", "Diagnóstico e Backup",
                "Contas e Avançado", "Pastas",
            ),
            SettingsRootCategories.map { it.label },
        )
    }

    @Test fun oldSavedDestinationsStillOpenTheCorrectCurrentCategory() {
        assertEquals("Geral", settingsPageForDestination("appearance_theme"))
        assertEquals("Player", settingsPageForDestination("player_audio"))
        assertEquals("Diagnóstico e Backup", settingsPageForDestination("about_reports"))
        assertEquals("Pastas", settingsPageForDestination("library_storage"))
        assertEquals("Contas e Avançado", settingsPageForDestination("Contas"))
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

    @Test fun diagnosticAndDataActionsNeverShareIcons() {
        val actions = listOf(
            "Restaurar", "Limpar cache de capas", "Exportar configurações",
            "Importar configurações", "Restaurar configurações", "Fazer backup",
            "Restaurar backup", "Verificar integridade", "Reconciliar arquivos",
            "Executar diagnóstico geral", "Importar logcat (ADB)", "Cancelar coleta atual",
            "Exportar relatório coletado", "Limpar resultados e histórico",
            "Sistema Android", "Logcat disponível", "Armazenamento e permissões",
            "Scanner e indexação", "SQLite e biblioteca", "Thumbnails, capas e cache",
            "Ponte e comunicação interna", "Desempenho e estabilidade",
        )
        val icons = actions.map(::settingsIcon)
        assertEquals(actions.size, icons.distinct().size)
        icons.forEach { assertNotEquals(Icons.Filled.Settings, it) }
    }
}
