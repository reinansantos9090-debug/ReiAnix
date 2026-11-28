package com.reianix.reianix_local.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.R

// Resolve only display strings; route keys and stored preferences stay unchanged.
@Composable
internal fun localizedSettingsCategory(value: String): String = when (value) {
    "Geral" -> stringResource(R.string.settings_general)
    "Player" -> stringResource(R.string.settings_player)
    "Metadados e Capas" -> stringResource(R.string.settings_metadata)
    "Dados e Backup" -> stringResource(R.string.settings_backup)
    "Contas e Avançado" -> stringResource(R.string.settings_accounts)
    "Pastas" -> stringResource(R.string.settings_folders)
    "Sobre" -> stringResource(R.string.settings_about)
    else -> value
}

@Composable
internal fun localizedSettingsDescription(value: String): String = when (value) {
    "Preferências, aparência e biblioteca" -> stringResource(R.string.settings_general_desc)
    "Reprodução, controles, áudio e tela" -> stringResource(R.string.settings_player_desc)
    "Identificação, capas e miniaturas" -> stringResource(R.string.settings_metadata_desc)
    "Configurações locais, backup e cache" -> stringResource(R.string.settings_backup_desc)
    "Pastas locais da biblioteca" -> stringResource(R.string.settings_folders_desc)
    "Versão, informações e licenças" -> stringResource(R.string.settings_about_desc)
    else -> value
}

@Composable
internal fun ReiAnixAppLanguageRow() {
    val context = LocalContext.current
    // Read configuration so changes from Android Settings invalidate the UI.
    val configuration = LocalConfiguration.current
    val selected = remember(configuration) {
        ReiAnixAppLocales.selected(context)
    }
    val options = listOf(
        "" to R.string.locale_system,
        "pt-BR" to R.string.locale_pt,
        "en" to R.string.locale_en,
        "es" to R.string.locale_es,
        "ru" to R.string.locale_ru,
        "ja" to R.string.locale_ja,
    )
    val current = options.firstOrNull { it.first == selected }
        ?: options.firstOrNull { it.first == selected.substringBefore('-') }
    val summary = current?.let { stringResource(it.second) } ?: selected
    var showDialog by remember { mutableStateOf(false) }
    SettingsLanguageRowContent(
        label = stringResource(R.string.locale_title),
        summary = summary,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.locale_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(
                                    selected = current?.first == tag,
                                    role = Role.RadioButton,
                                    onClick = {
                                        showDialog = false
                                        if (tag != selected) {
                                            ReiAnixAppLocales.change(context, tag)
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current?.first == tag, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.locale_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsLanguageRowContent(label: String, summary: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                Text(summary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
