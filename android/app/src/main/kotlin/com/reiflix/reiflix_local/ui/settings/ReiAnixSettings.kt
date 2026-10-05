package com.reiflix.reiflix_local.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.BuildConfig
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixCard
import com.reiflix.reiflix_local.ui.ReiAnixIconActionButton
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixRecoverableErrorState
import com.reiflix.reiflix_local.ui.ReiAnixScreenTitle
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryText
import com.reiflix.reiflix_local.ui.ReiAnixSettingCard
import com.reiflix.reiflix_local.ui.ReiAnixTextField
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsAccountUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens

data class ReiAnixSettingsCategoryUiModel(
    val label: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    companion object {
        fun defaultCategories(): List<ReiAnixSettingsCategoryUiModel> = listOf(
            ReiAnixSettingsCategoryUiModel("Conta", "Conta Google e sessão", Icons.Filled.AccountCircle),
            ReiAnixSettingsCategoryUiModel("Geral", "Comportamento geral do aplicativo", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Aparência", "Tema e apresentação", Icons.Filled.DarkMode),
            ReiAnixSettingsCategoryUiModel("Biblioteca", "Cards, organização e Continue Watching", Icons.Filled.List),
            ReiAnixSettingsCategoryUiModel("Player", "Reprodução, controles, vídeo e orientação", Icons.Filled.PlayArrow),
            ReiAnixSettingsCategoryUiModel("Gestos", "Gestos disponíveis durante a reprodução", Icons.Filled.Search),
            ReiAnixSettingsCategoryUiModel("Áudio e Legendas", "Preferências de áudio e texto", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Metadata", "Integração e associação de metadata", Icons.Filled.Search),
            ReiAnixSettingsCategoryUiModel("Artwork", "Artwork remoto e cache", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Armazenamento", "Permissões, SAF e fontes locais", Icons.Filled.Storage),
            ReiAnixSettingsCategoryUiModel("Dados e Cache", "Importação, exportação e limpeza", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Backup e Restauração", "Proteção e recuperação da biblioteca", Icons.Filled.Settings),
            ReiAnixSettingsCategoryUiModel("Privacidade", "Dados locais e conectividade", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Varredura", "Estado da descoberta local", Icons.Filled.Refresh),
            ReiAnixSettingsCategoryUiModel("Diagnóstico", "Informações técnicas e integridade", Icons.Filled.Info),
            ReiAnixSettingsCategoryUiModel("Sobre", "Informações do aplicativo", Icons.Filled.Info),
        )
    }
}

/**
 * Categories that are entirely representable by persisted settings or existing
 * read-only application metadata. Specialized Flet categories remain the
 * existing fallback when they expose file pickers, backup, cache, or diagnostics
 * callbacks that do not have a native command contract yet.
 */
private val NativeManagedSettingsCategories = setOf(
    "Conta",
    "Geral",
    "Aparência",
    "Biblioteca",
    "Player",
    "Gestos",
    "Áudio e Legendas",
    "Metadata",
    "Privacidade",
    "Varredura",
    "Sobre",
)

private data class ReiAnixSettingChoice(
    val value: String,
    val label: String,
)

@Composable
fun ReiAnixSettingsRoute(
    viewModel: ReiAnixSettingsViewModel,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    ReiAnixComposeTheme(
        themeMode = state.settings["appearance.theme"],
    ) {
        if (selectedCategory == null) {
            ReiAnixSettingsScreen(
                state = state,
                onBack = onBack,
                onOpenCategory = { category ->
                    if (category in NativeManagedSettingsCategories) {
                        selectedCategory = category
                    } else {
                        onOpenCategory(category)
                    }
                },
                onRetry = viewModel::refresh,
            )
        } else {
            ReiAnixSettingsCategoryScreen(
                category = selectedCategory.orEmpty(),
                state = state,
                onBack = { selectedCategory = null },
                onSettingChanged = viewModel::setSetting,
                onAccountAction = viewModel::requestAccountAction,
                onSettingsAction = viewModel::requestAction,
            )
        }
    }
}

@Composable
private fun ReiAnixSettingsScreen(
    state: ReiAnixSettingsUiState,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                top = ReiAnixTokens.Spacing.sm,
                bottom = ReiAnixTokens.Dimensions.screenBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            item(key = "settings-header") {
                SettingsHeader(
                    title = "Configurações",
                    subtitle = "Preferências do ReiAnix",
                    onBack = onBack,
                )
            }

            when (state.status) {
                com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.LOADING -> {
                    item(key = "settings-loading") {
                        ReiAnixLoadingState(
                            title = "Carregando configurações",
                            message = "Lendo os valores persistidos do aplicativo…",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.ERROR -> {
                    item(key = "settings-error") {
                        ReiAnixRecoverableErrorState(
                            title = "Não foi possível carregar as configurações",
                            message = state.error ?: "A projeção atual do Settings não está disponível.",
                            onRetry = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus.READY -> {
                    val available = state.categories.associateBy { it.label }
                    val groups = listOf(
                        "Conta" to listOf("Conta"),
                        "Preferências" to listOf("Geral", "Aparência", "Biblioteca"),
                        "Reprodução" to listOf("Player", "Gestos", "Áudio e Legendas"),
                        "Mídia e sistema" to listOf(
                            "Metadata",
                            "Artwork",
                            "Armazenamento",
                            "Dados e Cache",
                            "Backup e Restauração",
                            "Privacidade",
                            "Varredura",
                            "Diagnóstico",
                        ),
                        "Informações" to listOf("Sobre"),
                    )

                    groups.forEach { (sectionTitle, labels) ->
                        val categories = labels.mapNotNull { available[it] }
                        if (categories.isEmpty()) return@forEach

                        item(key = "section:$sectionTitle") {
                            SectionHeader(title = sectionTitle)
                        }

                        items(
                            items = categories,
                            key = { "category:\${it.label}" },
                        ) { category ->
                            ReiAnixSettingCard(
                                title = category.label,
                                description = category.description,
                                value = categorySummary(category.label, state),
                                icon = category.icon,
                                onClick = { onOpenCategory(category.label) },
                                enabled = true,
                                trailingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ReiAnixTokens.Dimensions.topBarMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReiAnixIconActionButton(
            icon = Icons.Filled.ArrowBack,
            contentDescription = "Voltar",
            onClick = onBack,
            modifier = Modifier.padding(end = ReiAnixTokens.Spacing.xs),
        )
        ReiAnixScreenTitle(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = ReiAnixTokens.Spacing.sm,
                bottom = ReiAnixTokens.Spacing.xs,
            ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ReiAnixSettingsCategoryScreen(
    category: String,
    state: ReiAnixSettingsUiState,
    onBack: () -> Unit,
    onSettingChanged: (String, String) -> Unit,
    onAccountAction: (String) -> Unit,
    onSettingsAction: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                top = ReiAnixTokens.Spacing.sm,
                bottom = ReiAnixTokens.Dimensions.screenBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            item(key = "category-header") {
                SettingsHeader(
                    title = category,
                    subtitle = categorySubtitle(category),
                    onBack = onBack,
                )
            }

            when (category) {
                "Conta" -> item(key = "account-content") {
                    ReiAnixSettingsAccountContent(
                        account = state.account,
                        onAction = onAccountAction,
                    )
                }

                "Geral" -> item(key = "general-content") {
                    SettingsSection(
                        title = "Geral",
                        description = "Comportamento padrão do aplicativo.",
                    ) {
                        BooleanSettingRow(
                            keyName = "app.confirm_destructive",
                            title = "Confirmar ações destrutivas",
                            description = "Pede confirmação antes de limpar cache ou restaurar configurações.",
                            value = state.settings["app.confirm_destructive"] == "true",
                            onValueChange = { onSettingChanged("app.confirm_destructive", it.toString()) },
                        )
                    }
                }

                "Aparência" -> item(key = "appearance-content") {
                    SettingsSection(
                        title = "Aparência",
                        description = "Altera somente opções visuais já suportadas pelo aplicativo.",
                    ) {
                        ChoiceSettingRow(
                            keyName = "appearance.theme",
                            title = "Tema",
                            description = "A alteração é aplicada imediatamente ao shell existente.",
                            value = state.settings["appearance.theme"].orEmpty().ifBlank { "dark" },
                            choices = listOf(
                                ReiAnixSettingChoice("system", "Sistema"),
                                ReiAnixSettingChoice("light", "Claro"),
                                ReiAnixSettingChoice("dark", "Escuro"),
                            ),
                            onValueChange = { onSettingChanged("appearance.theme", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "appearance.card_size",
                            title = "Tamanho dos cards",
                            description = "Controla a largura visual dos cards da Home.",
                            value = state.settings["appearance.card_size"].orEmpty().ifBlank { "medium" },
                            choices = listOf(
                                ReiAnixSettingChoice("small", "Pequeno"),
                                ReiAnixSettingChoice("medium", "Médio"),
                                ReiAnixSettingChoice("large", "Grande"),
                            ),
                            onValueChange = { onSettingChanged("appearance.card_size", it) },
                        )
                        BooleanSettingRow(
                            keyName = "appearance.show_thumbnails",
                            title = "Mostrar miniaturas",
                            description = "Mantém o espaço dos cards, mas evita carregar miniaturas quando desativado.",
                            value = state.settings["appearance.show_thumbnails"] == "true",
                            onValueChange = { onSettingChanged("appearance.show_thumbnails", it.toString()) },
                        )
                    }
                }

                "Biblioteca" -> item(key = "library-content") {
                    SettingsSection(
                        title = "Biblioteca",
                        description = "Preferências já usadas pela projeção local da biblioteca.",
                    ) {
                        ChoiceSettingRow(
                            keyName = "library.sort_default",
                            title = "Ordenação padrão",
                            description = "Define a ordenação inicial quando outra ordenação não foi salva na tela.",
                            value = state.settings["library.sort_default"].orEmpty().ifBlank { "added_desc" },
                            choices = listOf(
                                ReiAnixSettingChoice("added_desc", "Mais recentes"),
                                ReiAnixSettingChoice("title_asc", "Nome A–Z"),
                                ReiAnixSettingChoice("title_desc", "Nome Z–A"),
                                ReiAnixSettingChoice("recently_watched", "Assistidos recentemente"),
                            ),
                            onValueChange = { onSettingChanged("library.sort_default", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "library.grid_density",
                            title = "Densidade da grade",
                            description = "Controla a largura efetiva dos cards.",
                            value = state.settings["library.grid_density"].orEmpty().ifBlank { "medium" },
                            choices = listOf(
                                ReiAnixSettingChoice("small", "Menos cards"),
                                ReiAnixSettingChoice("medium", "Equilibrada"),
                                ReiAnixSettingChoice("large", "Mais cards"),
                            ),
                            onValueChange = { onSettingChanged("library.grid_density", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "library.page_size",
                            title = "Itens por página",
                            description = "Quantidade persistida de itens na paginação do catálogo.",
                            value = state.settings["library.page_size"].orEmpty().ifBlank { "36" },
                            choices = listOf(
                                ReiAnixSettingChoice("24", "24"),
                                ReiAnixSettingChoice("36", "36"),
                                ReiAnixSettingChoice("48", "48"),
                                ReiAnixSettingChoice("72", "72"),
                            ),
                            onValueChange = { onSettingChanged("library.page_size", it) },
                        )
                        BooleanSettingRow(
                            keyName = "library.continue_watching",
                            title = "Continue Watching",
                            description = "Controla a preferência global dessa seção quando consumida pela Home.",
                            value = state.settings["library.continue_watching"] == "true",
                            onValueChange = { onSettingChanged("library.continue_watching", it.toString()) },
                        )
                        ChoiceSettingRow(
                            keyName = "library.continue_watching_limit",
                            title = "Limite de Continue Watching",
                            description = "Limite persistido para a seção.",
                            value = state.settings["library.continue_watching_limit"].orEmpty().ifBlank { "10" },
                            choices = listOf(
                                ReiAnixSettingChoice("5", "5"),
                                ReiAnixSettingChoice("10", "10"),
                                ReiAnixSettingChoice("15", "15"),
                                ReiAnixSettingChoice("20", "20"),
                            ),
                            onValueChange = { onSettingChanged("library.continue_watching_limit", it) },
                        )
                    }
                }

                "Player" -> item(key = "player-content") {
                    SettingsSection(
                        title = "Player",
                        description = "Somente preferências já consumidas pelo Media3/NativePlayer existente.",
                    ) {
                        BooleanSettingRow(
                            keyName = "player.autoplay_next",
                            title = "Autoplay do próximo episódio",
                            description = "Permite avanço automático no player local.",
                            value = state.settings["player.autoplay_next"] == "true",
                            onValueChange = { onSettingChanged("player.autoplay_next", it.toString()) },
                        )
                        BooleanSettingRow(
                            keyName = "player.resume",
                            title = "Continuar reprodução",
                            description = "Usa a posição de progresso já salva; desativar não apaga o progresso.",
                            value = state.settings["player.resume"] == "true",
                            onValueChange = { onSettingChanged("player.resume", it.toString()) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.default_speed",
                            title = "Velocidade padrão",
                            description = "Aplicada quando um episódio é aberto.",
                            value = state.settings["player.default_speed"].orEmpty().ifBlank { "1.0" },
                            choices = listOf(
                                ReiAnixSettingChoice("0.5", "0,50×"),
                                ReiAnixSettingChoice("0.75", "0,75×"),
                                ReiAnixSettingChoice("1.0", "1,00×"),
                                ReiAnixSettingChoice("1.25", "1,25×"),
                                ReiAnixSettingChoice("1.5", "1,50×"),
                                ReiAnixSettingChoice("1.75", "1,75×"),
                                ReiAnixSettingChoice("2.0", "2,00×"),
                            ),
                            onValueChange = { onSettingChanged("player.default_speed", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.aspect_ratio",
                            title = "Modo de vídeo",
                            description = "Ajustar preserva toda a imagem; Preencher ocupa a tela cortando somente o excedente.",
                            value = state.settings["player.aspect_ratio"].orEmpty().ifBlank { "fit" },
                            choices = listOf(
                                ReiAnixSettingChoice("fit", "Ajustar"),
                                ReiAnixSettingChoice("fill", "Preencher"),
                            ),
                            onValueChange = { onSettingChanged("player.aspect_ratio", it) },
                        )
                        BooleanSettingRow(
                            keyName = "player.zoom_enabled",
                            title = "Zoom por gesto",
                            description = "Permite ampliar e mover o vídeo com gesto.",
                            value = state.settings["player.zoom_enabled"] == "true",
                            onValueChange = { onSettingChanged("player.zoom_enabled", it.toString()) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.double_tap_seek_seconds",
                            title = "Salto no double tap",
                            description = "Quantidade de segundos avançados ou retrocedidos pelo double tap.",
                            value = state.settings["player.double_tap_seek_seconds"].orEmpty().ifBlank { "10" },
                            choices = listOf(
                                ReiAnixSettingChoice("5", "5 s"),
                                ReiAnixSettingChoice("10", "10 s"),
                                ReiAnixSettingChoice("15", "15 s"),
                                ReiAnixSettingChoice("30", "30 s"),
                            ),
                            onValueChange = { onSettingChanged("player.double_tap_seek_seconds", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.long_press_speed",
                            title = "Velocidade da pressão longa",
                            description = "Velocidade temporária enquanto a pressão longa estiver ativa.",
                            value = state.settings["player.long_press_speed"].orEmpty().ifBlank { "2.0" },
                            choices = listOf(
                                ReiAnixSettingChoice("1.5", "1,50×"),
                                ReiAnixSettingChoice("1.75", "1,75×"),
                                ReiAnixSettingChoice("2.0", "2,00×"),
                            ),
                            onValueChange = { onSettingChanged("player.long_press_speed", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.max_video_resolution",
                            title = "Resolução máxima",
                            description = "Limita a faixa de vídeo selecionada pelo Media3.",
                            value = state.settings["player.max_video_resolution"].orEmpty().ifBlank { "auto" },
                            choices = listOf(
                                ReiAnixSettingChoice("auto", "Automática"),
                                ReiAnixSettingChoice("480p", "480p"),
                                ReiAnixSettingChoice("720p", "720p"),
                                ReiAnixSettingChoice("1080p", "1080p"),
                                ReiAnixSettingChoice("1440p", "1440p"),
                                ReiAnixSettingChoice("2160p", "2160p"),
                            ),
                            onValueChange = { onSettingChanged("player.max_video_resolution", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.max_video_frame_rate",
                            title = "FPS máximo",
                            description = "Limita a taxa de frames da track de vídeo selecionada.",
                            value = state.settings["player.max_video_frame_rate"].orEmpty().ifBlank { "0" },
                            choices = listOf(
                                ReiAnixSettingChoice("0", "Automático"),
                                ReiAnixSettingChoice("24", "24 fps"),
                                ReiAnixSettingChoice("30", "30 fps"),
                                ReiAnixSettingChoice("60", "60 fps"),
                            ),
                            onValueChange = { onSettingChanged("player.max_video_frame_rate", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.max_audio_channels",
                            title = "Canais de áudio máximos",
                            description = "Limita a seleção de áudio sem criar mixer ou decoder alternativo.",
                            value = state.settings["player.max_audio_channels"].orEmpty().ifBlank { "0" },
                            choices = listOf(
                                ReiAnixSettingChoice("0", "Automático"),
                                ReiAnixSettingChoice("2", "2 canais"),
                                ReiAnixSettingChoice("6", "5.1 / 6"),
                                ReiAnixSettingChoice("8", "7.1 / 8"),
                            ),
                            onValueChange = { onSettingChanged("player.max_audio_channels", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.immersive",
                            title = "Modo imersivo",
                            description = "Controla as barras do sistema somente no player.",
                            value = state.settings["player.immersive"].orEmpty().ifBlank { "always" },
                            choices = listOf(
                                ReiAnixSettingChoice("always", "Sempre"),
                                ReiAnixSettingChoice("landscape", "Somente landscape"),
                                ReiAnixSettingChoice("never", "Nunca"),
                            ),
                            onValueChange = { onSettingChanged("player.immersive", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.rotation",
                            title = "Rotação",
                            description = "Orienta somente o player.",
                            value = state.settings["player.rotation"].orEmpty().ifBlank { "auto" },
                            choices = listOf(
                                ReiAnixSettingChoice("auto", "Automática"),
                                ReiAnixSettingChoice("portrait", "Portrait"),
                                ReiAnixSettingChoice("landscape", "Landscape"),
                            ),
                            onValueChange = { onSettingChanged("player.rotation", it) },
                        )
                        BooleanSettingRow(
                            keyName = "player.pip",
                            title = "Picture-in-Picture",
                            description = "Permite PiP quando suportado pelo dispositivo.",
                            value = state.settings["player.pip"] == "true",
                            onValueChange = { onSettingChanged("player.pip", it.toString()) },
                        )
                        ChoiceSettingRow(
                            keyName = "player.auto_hide_seconds",
                            title = "Ocultar controles automaticamente",
                            description = "0 significa nunca.",
                            value = state.settings["player.auto_hide_seconds"].orEmpty().ifBlank { "5" },
                            choices = listOf(
                                ReiAnixSettingChoice("5", "5 s"),
                                ReiAnixSettingChoice("10", "10 s"),
                                ReiAnixSettingChoice("15", "15 s"),
                                ReiAnixSettingChoice("30", "30 s"),
                                ReiAnixSettingChoice("0", "Nunca"),
                            ),
                            onValueChange = { onSettingChanged("player.auto_hide_seconds", it) },
                        )
                        ActionSettingRow(
                            keyName = "player.reset",
                            title = "Restaurar Player",
                            description = "Restaura somente as preferências do Player aos padrões existentes.",
                            actionLabel = "Restaurar",
                            onClick = { onSettingsAction("reset_player") },
                        )
                    }
                }

                "Gestos" -> item(key = "gestures-content") {
                    SettingsSection(
                        title = "Gestos",
                        description = "Controles já suportados pelo player atual.",
                    ) {
                        BooleanSettingRow(
                            keyName = "gestures.volume",
                            title = "Gestos de volume",
                            description = "Swipe vertical no lado direito ajusta o volume.",
                            value = state.settings["gestures.volume"] == "true",
                            onValueChange = { onSettingChanged("gestures.volume", it.toString()) },
                        )
                        BooleanSettingRow(
                            keyName = "gestures.brightness",
                            title = "Gestos de brilho",
                            description = "Swipe vertical no lado esquerdo ajusta o brilho.",
                            value = state.settings["gestures.brightness"] == "true",
                            onValueChange = { onSettingChanged("gestures.brightness", it.toString()) },
                        )
                        BooleanSettingRow(
                            keyName = "gestures.double_tap",
                            title = "Double tap para seek",
                            description = "Controla o double tap existente.",
                            value = state.settings["gestures.double_tap"] == "true",
                            onValueChange = { onSettingChanged("gestures.double_tap", it.toString()) },
                        )
                        BooleanSettingRow(
                            keyName = "gestures.long_press",
                            title = "Pressão longa",
                            description = "Controla a ação de long press existente.",
                            value = state.settings["gestures.long_press"] == "true",
                            onValueChange = { onSettingChanged("gestures.long_press", it.toString()) },
                        )
                    }
                }

                "Áudio e Legendas" -> item(key = "audio-content") {
                    SettingsSection(
                        title = "Áudio e Legendas",
                        description = "Somente preferências persistidas; o Media3 continua selecionando entre tracks reais.",
                    ) {
                        ChoiceSettingRow(
                            keyName = "audio.subtitle_scale",
                            title = "Escala da legenda",
                            description = "Tamanho relativo pelo SubtitleView.",
                            value = state.settings["audio.subtitle_scale"].orEmpty().ifBlank { "1.0" },
                            choices = listOf(
                                ReiAnixSettingChoice("0.75", "75%"),
                                ReiAnixSettingChoice("1.0", "100%"),
                                ReiAnixSettingChoice("1.25", "125%"),
                                ReiAnixSettingChoice("1.5", "150%"),
                            ),
                            onValueChange = { onSettingChanged("audio.subtitle_scale", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "audio.subtitle_bottom_padding",
                            title = "Margem inferior da legenda",
                            description = "Margem inferior quando a cue não especifica uma linha fixa.",
                            value = state.settings["audio.subtitle_bottom_padding"].orEmpty().ifBlank { "8" },
                            choices = listOf(
                                ReiAnixSettingChoice("4", "4%"),
                                ReiAnixSettingChoice("8", "8%"),
                                ReiAnixSettingChoice("12", "12%"),
                                ReiAnixSettingChoice("16", "16%"),
                            ),
                            onValueChange = { onSettingChanged("audio.subtitle_bottom_padding", it) },
                        )
                        BooleanSettingRow(
                            keyName = "audio.subtitle_embedded_style",
                            title = "Estilo embutido da legenda",
                            description = "Permite aplicar o estilo declarado pela própria faixa.",
                            value = state.settings["audio.subtitle_embedded_style"] == "true",
                            onValueChange = { onSettingChanged("audio.subtitle_embedded_style", it.toString()) },
                        )
                        LanguageSettingRow(
                            keyName = "audio.preferred_language",
                            title = "Idioma de áudio",
                            description = "BCP-47, por exemplo pt-BR, en ou ja. Só será selecionada uma track existente.",
                            value = state.settings["audio.preferred_language"].orEmpty(),
                            onValueChange = { onSettingChanged("audio.preferred_language", it) },
                        )
                        LanguageSettingRow(
                            keyName = "audio.preferred_subtitle_language",
                            title = "Idioma da legenda",
                            description = "BCP-47. A seleção ocorre somente entre tracks existentes no arquivo.",
                            value = state.settings["audio.preferred_subtitle_language"].orEmpty(),
                            onValueChange = { onSettingChanged("audio.preferred_subtitle_language", it) },
                        )
                        ChoiceSettingRow(
                            keyName = "audio.subtitles",
                            title = "Legendas",
                            description = "Automático respeita o arquivo; Sempre tenta selecionar uma legenda; Nunca desativa a track de texto.",
                            value = state.settings["audio.subtitles"].orEmpty().ifBlank { "auto" },
                            choices = listOf(
                                ReiAnixSettingChoice("auto", "Automático"),
                                ReiAnixSettingChoice("always", "Sempre"),
                                ReiAnixSettingChoice("never", "Nunca"),
                            ),
                            onValueChange = { onSettingChanged("audio.subtitles", it) },
                        )
                        ReiAnixSecondaryText(
                            text = "Delay global de legenda não é suportado por esta camada do Media3 e não é exibido como uma configuração falsa.",
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                    }
                }

                "Metadata" -> item(key = "metadata-content") {
                    SettingsSection(
                        title = "Metadata",
                        description = "Preferências já existentes da integração local com AniList.",
                    ) {
                        BooleanSettingRow(
                            keyName = "metadata.anilist_enabled",
                            title = "Usar AniList",
                            description = "Permite ou bloqueia chamadas remotas do cliente AniList; o catálogo local continua disponível sem rede.",
                            value = state.settings["metadata.anilist_enabled"] == "true",
                            onValueChange = { onSettingChanged("metadata.anilist_enabled", it.toString()) },
                        )
                        BooleanSettingRow(
                            keyName = "metadata.auto_match",
                            title = "Auto-match AniList",
                            description = "Quando desativado, novas buscas automáticas não são disparadas.",
                            value = state.settings["metadata.auto_match"] == "true",
                            onValueChange = { onSettingChanged("metadata.auto_match", it.toString()) },
                        )
                        ReiAnixSecondaryText(
                            text = "Alterar Settings não dispara sincronização em massa.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                "Privacidade" -> item(key = "privacy-content") {
                    SettingsSection(
                        title = "Privacidade",
                        description = "Informações já válidas para o funcionamento atual.",
                    ) {
                        ReadOnlyInfoRow(
                            title = "Biblioteca local",
                            description = "Biblioteca, histórico e caminhos locais permanecem no dispositivo.",
                            badge = "Local",
                        )
                        ReadOnlyInfoRow(
                            title = "Conectividade",
                            description = "Não há analytics ou tracking da biblioteca. Google Login não é requisito para reprodução local.",
                            badge = "Offline-first",
                        )
                    }
                }

                "Varredura" -> item(key = "scan-content") {
                    SettingsSection(
                        title = "Varredura",
                        description = "A descoberta continua sob o ScanCoordinator e os scanners existentes.",
                    ) {
                        ReadOnlyInfoRow(
                            title = "Scanner",
                            description = "MediaStore, SAF e broad storage continuam usando a infraestrutura nativa existente.",
                            badge = "Existente",
                        )
                        ReadOnlyInfoRow(
                            title = "Atualização",
                            description = "Ações de refresh continuam no fluxo da Biblioteca; Settings não inicia uma varredura automaticamente.",
                            badge = "Sem scan",
                        )
                    }
                }

                "Sobre" -> item(key = "about-content") {
                    SettingsSection(
                        title = "Sobre",
                        description = "Informações obtidas da aplicação instalada.",
                    ) {
                        ReadOnlyInfoRow(
                            title = "ReiAnix",
                            description = "Aplicativo local para organização e reprodução de mídia.",
                            badge = "ReiAnix",
                        )
                        ReadOnlyInfoRow(
                            title = "Versão",
                            description = "Build " + BuildConfig.VERSION_NAME,
                            badge = BuildConfig.VERSION_NAME,
                        )
                        ReadOnlyInfoRow(
                            title = "Player",
                            description = "Media3 / NativePlayer existente.",
                            badge = "Media3",
                        )
                        ReadOnlyInfoRow(
                            title = "Armazenamento",
                            description = "MediaStore / SAF / scanners nativos existentes.",
                            badge = "Nativo",
                        )
                    }
                }

                else -> {
                    item(key = "delegated-content") {
                        ReiAnixCard {
                            ReiAnixSecondaryText(
                                text = "Esta seção continua usando o fluxo existente do ReiAnix para preservar ações como backup, exportação/importação, cache e diagnóstico.",
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 6,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        content()
    }
}

@Composable
private fun ReiAnixSettingRow(
    keyName: String,
    title: String,
    description: String,
    valueLabel: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable () -> Unit,
) {
    val interactiveModifier = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    ReiAnixCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactiveModifier)
            .semantics {
                contentDescription = "Configuração: $keyName"

private fun storageSummary(state: ReiAnixSettingsUiState): String {
    val storage = state.storage
    if (!storage.known) return "Verificando permissões"
    val media = when (storage.mediaReadState) {
        "full" -> "Vídeos permitidos"
        "partial" -> "Acesso parcial"
        "denied" -> "Vídeos sem permissão"
        else -> "Estado de vídeos desconhecido"
    }
    val saf = storage.safRootCount
    return buildString {
        append(media)
        if (saf > 0) append(" • $saf SAF")
        if (storage.safSelectionPending) append(" • Seleção em andamento")
    }
}