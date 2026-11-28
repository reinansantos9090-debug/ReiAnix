package com.reianix.reianix_local.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.R
import java.util.Locale

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
    "Comportamento" -> stringResource(R.string.choice_behavior)
    "Organização da biblioteca" -> stringResource(R.string.choice_library_organization)
    "Cache" -> stringResource(R.string.choice_cache)
    "Informações do ReiAnix" -> stringResource(R.string.choice_app_info)
    "Restaurar preferências" -> stringResource(R.string.choice_reset_prefs)
    "Home e Biblioteca" -> stringResource(R.string.choice_home_library)
    "Reprodução" -> stringResource(R.string.choice_playback)
    "Vídeo e qualidade" -> stringResource(R.string.choice_video_quality)
    "Controles e gestos" -> stringResource(R.string.choice_controls_gestures)
    "Áudio e legendas" -> stringResource(R.string.choice_audio_subtitles)
    "Tela e Picture-in-Picture" -> stringResource(R.string.choice_display_pip)
    "Configurações locais" -> stringResource(R.string.choice_local_settings)
    "Backup e restauração" -> stringResource(R.string.choice_backup_restore)
    "Biblioteca" -> stringResource(R.string.nav_library)
    "Tema" -> stringResource(R.string.pref_theme)
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

private val appLanguageFlags = mapOf(
    "pt-BR" to "🇧🇷",
    "en" to "🇬🇧",
    "es" to "🇪🇸",
    "ru" to "🇷🇺",
    "ja" to "🇯🇵",
    "de" to "🇩🇪",
    "ko" to "🇰🇷",
    "zh-Hans" to "🇨🇳",
    "it" to "🇮🇹",
    "fr" to "🇫🇷",
)

@Composable
internal fun ReiAnixAppLanguageRow() {
    val context = LocalContext.current
    // Read configuration so changes from Android Settings invalidate the UI.
    val configuration = LocalConfiguration.current
    var selected by remember(configuration) {
        mutableStateOf(ReiAnixAppLocales.selected(context))
    }
    val displayLocale = Locale.forLanguageTag(selected)
    val names = remember(displayLocale) {
        ReiAnixAppLocales.supported.associateWith { tag ->
            Locale.forLanguageTag(tag).getDisplayName(displayLocale)
                .replaceFirstChar { it.titlecase(displayLocale) }
        }
    }
    var showDialog by remember { mutableStateOf(false) }
    ReiAnixSettingsRow(
        icon = Icons.Filled.Translate,
        title = stringResource(R.string.locale_title),
        description = names.getValue(selected),
        onClick = { showDialog = true },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.locale_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    names.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected == tag,
                                    role = Role.RadioButton,
                                    onClick = {
                                        showDialog = false
                                        if (tag != selected) {
                                            ReiAnixAppLocales.change(context, tag)
                                            selected = ReiAnixAppLocales.selected(context)
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == tag, onClick = null)
                            appLanguageFlags[tag]?.let { flag ->
                                Text(
                                    text = flag,
                                    modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics { },
                                )
                            }
                            Text(label, Modifier.padding(start = 8.dp))
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
internal fun localizedSettingChoice(value: String): String = when (value) {
    "Sistema" -> stringResource(R.string.choice_system)
    "Claro" -> stringResource(R.string.choice_light)
    "Escuro" -> stringResource(R.string.choice_dark)
    "Pequeno" -> stringResource(R.string.choice_small)
    "Médio" -> stringResource(R.string.choice_medium)
    "Grande" -> stringResource(R.string.choice_large)
    "Ajustar" -> stringResource(R.string.choice_fit)
    "Esticar" -> stringResource(R.string.choice_stretch)
    "Sempre" -> stringResource(R.string.choice_always)
    "Somente em paisagem" -> stringResource(R.string.choice_landscape_only)
    "Nunca" -> stringResource(R.string.choice_never)
    "Automática" -> stringResource(R.string.choice_automatic_f)
    "Automático" -> stringResource(R.string.choice_automatic)
    "Retrato" -> stringResource(R.string.choice_portrait)
    "Paisagem" -> stringResource(R.string.choice_landscape)
    "2 canais" -> stringResource(R.string.choice_two_channels)
    "8% (padrão)" -> stringResource(R.string.choice_default_margin)
    "Mais recentes" -> stringResource(R.string.choice_recent)
    "Nome A–Z" -> stringResource(R.string.choice_name_asc)
    "Nome Z–A" -> stringResource(R.string.choice_name_desc)
    "Assistidos recentemente" -> stringResource(R.string.choice_watched_recent)
    "Tudo" -> stringResource(R.string.choice_all)
    "Reprodutor interno" -> stringResource(R.string.choice_internal)
    "Reprodutor externo" -> stringResource(R.string.choice_external)
    "Branco" -> stringResource(R.string.choice_white)
    "Amarelo" -> stringResource(R.string.choice_yellow)
    "Ciano" -> stringResource(R.string.choice_cyan)
    "Sem serifa" -> stringResource(R.string.choice_sans)
    "Serifada" -> stringResource(R.string.choice_serif)
    "Monoespaçada" -> stringResource(R.string.choice_mono)
    "Selecionar" -> stringResource(R.string.choice_select)
    else -> value
}
