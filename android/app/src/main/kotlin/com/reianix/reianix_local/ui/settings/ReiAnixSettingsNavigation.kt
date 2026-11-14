package com.reianix.reianix_local.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings

internal data class SettingsEntry(val key: String, val title: String, val keywords: String = "")
internal data class SettingsDestination(
    val id: String,
    val title: String,
    val parent: String,
    val entries: List<SettingsEntry>,
)
internal data class SettingsSearchResult(val destination: SettingsDestination, val entry: SettingsEntry)

internal val SettingsRootCategories = listOf(
    ReiAnixSettingsCategoryUiModel("Geral", "Preferências, aparência e biblioteca", Icons.Filled.Settings),
    ReiAnixSettingsCategoryUiModel("Player", "Reprodução, controles, áudio e tela", Icons.Filled.PlayArrow),
    ReiAnixSettingsCategoryUiModel("Metadados e Capas", "Identificação, capas e miniaturas", Icons.Filled.Image),
    ReiAnixSettingsCategoryUiModel("Diagnóstico e Backup", "Dados, backup e diagnóstico", Icons.Filled.Backup),
    ReiAnixSettingsCategoryUiModel("Contas e Avançado", "AniList", Icons.Filled.Settings),
    ReiAnixSettingsCategoryUiModel("Pastas", "Pastas locais da biblioteca", Icons.Filled.LibraryBooks),
    ReiAnixSettingsCategoryUiModel("Sobre", "Versão, informações e licenças", Icons.Filled.Info),
)

/** Existing destinations remain addressable for saved navigation and search consumers. */
internal fun settingsPageSections(page: String): List<String> = when (page) {
    "Geral" -> listOf("Geral", "appearance_theme", "appearance_library", "Biblioteca")
    "Player" -> listOf("player_playback", "player_video", "player_controls", "player_audio", "player_display", "Player")
    "Diagnóstico e Backup" -> listOf("data_settings", "data_backup", "Dados e Backup", "about_diagnostics", "about_reports")
    "Sobre" -> listOf("Sobre")
    else -> listOf(page)
}

internal fun settingsSectionTitle(section: String): String = when (section) {
    "Geral" -> "Comportamento"
    "Biblioteca" -> "Organização da biblioteca"
    "Dados e Backup" -> "Cache"
    "Sobre" -> "Informações do ReiAnix"
    "Player" -> "Restaurar preferências"
    else -> settingsDestination(section)?.title ?: section
}

internal fun settingsPageForDestination(destination: String?): String? = when (destination) {
    null -> null
    "Aparência", "Biblioteca", "appearance_theme", "appearance_library", "general_behavior", "general_privacy", "library_organization" -> "Geral"
    "library_storage", "library_scan" -> "Pastas"
    "Dados e Backup", "data_cache", "data_settings", "data_backup", "about_diagnostics", "about_reports" -> "Diagnóstico e Backup"
    "Sobre e Diagnóstico", "about_app", "about:app", "about:licenses" -> "Sobre"
    "player_advanced", "player_playback", "player_video", "player_controls", "player_audio", "player_display" -> "Player"
    "metadata_identification", "metadata_artwork" -> "Metadados e Capas"
    "Conta", "Contas" -> "Contas e Avançado"
    else -> destination
}

internal val SettingsDestinations = listOf(
    SettingsDestination("Geral", "Geral", "", listOf(
        SettingsEntry("setting:app.confirm_destructive", "Confirmar ações destrutivas"),
    )),
    SettingsDestination("Player", "Player", "", listOf(
        SettingsEntry("setting:player.reset", "Restaurar configurações do player", "Restaurar configurações do player Restaura somente as preferências do Player aos padrões existentes. Restaurar"),
    )),
    SettingsDestination("Metadados e Capas", "Metadados e Capas", "", listOf(
        SettingsEntry("setting:metadata.anilist_enabled", "Usar AniList"),
        SettingsEntry("setting:metadata.auto_match", "Identificar automaticamente pelo AniList"),
        SettingsEntry("setting:artwork.enabled", "Baixar capas pela internet"),
        SettingsEntry("setting:artwork.cache_limit_mb", "Limite do cache de capas"),
    )),
    SettingsDestination("Dados e Backup", "Dados e Backup", "", listOf(
        SettingsEntry("data-cache:artwork-cache", "Limpar cache de capas", "miniaturas imagens cache artwork"),
    )),
    SettingsDestination("Sobre", "Sobre", "", listOf(
        SettingsEntry("about:app", "Versão e informações do aplicativo", "ReiAnix versão"),
        SettingsEntry("about:licenses", "Licenças", "Bibliotecas código aberto Apache licença créditos terceiros"),
    )),
    SettingsDestination("Biblioteca", "Biblioteca", "", listOf(
        SettingsEntry("setting:library.sort_default", "Ordenação padrão"),
        SettingsEntry("setting:library.page_size", "Itens por página"),
        SettingsEntry("folders", "Pasta da biblioteca", "selecionar pasta pastas cadastradas armazenamento fontes remover pasta adicionar ou alterar pasta"),
    )),
    SettingsDestination("appearance_theme", "Tema", "Aparência", listOf(
        SettingsEntry("setting:appearance.theme", "Tema"),
    )),
    SettingsDestination("appearance_library", "Home e Biblioteca", "Aparência", listOf(
        SettingsEntry("setting:appearance.card_size", "Tamanho dos cards"),
        SettingsEntry("setting:library.grid_density", "Densidade da grade"),
        SettingsEntry("setting:appearance.poster_size", "Tamanho dos pôsteres (0–16)", ""),
        SettingsEntry("setting:appearance.show_thumbnails", "Mostrar miniaturas"),
        SettingsEntry("setting:library.continue_watching", "Continuar assistindo"),
        SettingsEntry("setting:library.continue_watching_limit", "Limite de continuar assistindo"),
    )),
    SettingsDestination("player_playback", "Reprodução", "Player", listOf(
        SettingsEntry("setting:player.autoplay_next", "Reprodução automática do próximo episódio"),
        SettingsEntry("setting:player.resume", "Continuar reprodução"),
        SettingsEntry("setting:player.default_speed", "Velocidade padrão"),
    )),
    SettingsDestination("player_video", "Vídeo e qualidade", "Player", listOf(
        SettingsEntry("setting:player.aspect_ratio", "Modo de vídeo"),
        SettingsEntry("setting:player.max_video_resolution", "Resolução máxima"),
        SettingsEntry("setting:player.max_video_frame_rate", "FPS máximo"),
    )),
    SettingsDestination("player_controls", "Controles e gestos", "Player", listOf(
        SettingsEntry("setting:player.zoom_enabled", "Zoom por gesto"),
        SettingsEntry("setting:player.lock_mode", "Bloqueio dos controles"),
        SettingsEntry("setting:player.double_tap_seek_seconds", "Salto com toque duplo"),
        SettingsEntry("setting:player.long_press_speed", "Velocidade da pressão longa"),
        SettingsEntry("setting:gestures.volume", "Gestos de volume"),
        SettingsEntry("setting:gestures.brightness", "Gestos de brilho"),
        SettingsEntry("setting:gestures.horizontal_seek", "Gestos horizontais"),
        SettingsEntry("setting:gestures.double_tap", "Toque duplo para avançar ou retroceder"),
        SettingsEntry("setting:gestures.long_press", "Ativar pressão longa"),
    )),
    SettingsDestination("player_audio", "Áudio e legendas", "Player", listOf(
        SettingsEntry("setting:player.max_audio_channels", "Canais de áudio máximos"),
        SettingsEntry("setting:audio.preferred_language", "Idioma de áudio"),
        SettingsEntry("setting:audio.preferred_subtitle_language", "Idioma da legenda"),
        SettingsEntry("setting:audio.subtitle_scale", "Escala da legenda"),
        SettingsEntry("setting:audio.subtitle_bottom_padding", "Margem inferior da legenda"),
        SettingsEntry("setting:audio.subtitle_embedded_style", "Estilo embutido da legenda"),
        SettingsEntry("setting:audio.subtitles", "Legendas"),
    )),
    SettingsDestination("player_display", "Tela e Picture-in-Picture", "Player", listOf(
        SettingsEntry("setting:player.immersive", "Modo imersivo"),
        SettingsEntry("setting:player.rotation", "Rotação"),
        SettingsEntry("setting:player.pip", "Picture-in-Picture", "Picture-in-Picture PIP janela flutuante"),
        SettingsEntry("setting:player.auto_hide_seconds", "Ocultar controles automaticamente"),
    )),
    SettingsDestination("data_settings", "Configurações locais", "Dados e Backup", listOf(
        SettingsEntry("data-cache:actions", "Exportar configurações", "Exportar configurações Importar configurações Restaurar configurações"),
    )),
    SettingsDestination("data_backup", "Backup e restauração", "Dados e Backup", listOf(
        SettingsEntry("backup:actions", "Fazer backup", "Fazer backup Restaurar backup Verificar integridade Reconciliar arquivos"),
    )),
    SettingsDestination("about_diagnostics", "Diagnóstico técnico", "Diagnóstico e Backup", listOf(
        SettingsEntry("data-cache:info", "Informações do armazenamento de preferências", "SettingsStore dados locais"),
        SettingsEntry("backup:info", "Informações dos dados de backup", "SQLite schema"),
        SettingsEntry("diagnostic:info", "Diagnóstico técnico", "Diagnóstico técnico DiagnosticsService existente"),
        SettingsEntry("diagnostic:actions", "Verificações do dispositivo", "Executar diagnóstico geral Android logcat armazenamento permissões scanner indexação SQLite biblioteca capas comunicação interna desempenho estabilidade"),
    )),
    SettingsDestination("about_reports", "Relatórios e histórico", "Diagnóstico e Backup", listOf(
        SettingsEntry("diagnostic:reports", "Relatórios e histórico", "Importar logcat ADB cancelar coleta exportar relatório limpar resultados histórico"),
        SettingsEntry("diagnostic:timeline:heading", "Eventos nativos recentes", "timeline histórico"),
    )),
)

internal fun settingsDestination(id: String?): SettingsDestination? = SettingsDestinations.firstOrNull { it.id == id }

private fun normalizeSettingsSearch(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(java.util.Locale.ROOT)

internal fun searchSettings(query: String): List<SettingsSearchResult> {
    val words = normalizeSettingsSearch(query).trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return SettingsDestinations.flatMap { destination ->
        destination.entries.mapNotNull { entry ->
            val text = normalizeSettingsSearch("${destination.parent} ${destination.title} ${entry.title} ${entry.keywords}")
            if (words.all { it in text }) SettingsSearchResult(destination, entry) else null
        }
    }
}
