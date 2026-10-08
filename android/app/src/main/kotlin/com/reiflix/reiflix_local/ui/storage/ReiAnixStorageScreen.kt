package com.reiflix.reiflix_local.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.ReiAnixBadge
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixCard
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixScannerInProgressState
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixStorageSourceUiModel
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsSurface
import com.reiflix.reiflix_local.ui.settings.SettingsHeader
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import com.reiflix.reiflix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixStorageRoute(
    viewModel: ReiAnixLibraryViewModel,
    onBack: () -> Unit,
    onRemoveSaf: (String) -> Unit,
    onRequestMediaAccess: () -> Unit,
    onOpenBroadSettings: () -> Unit,
    onCheckAccess: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReiAnixStorageScreen(
        state = state,
        onBack = onBack,
        onSelectSaf = viewModel::selectSafTree,
        onRemoveSaf = onRemoveSaf,
        onRequestMediaAccess = onRequestMediaAccess,
        onOpenBroadSettings = onOpenBroadSettings,
        onCheckAccess = onCheckAccess,
        onRefreshLibrary = viewModel::refresh,
    )
}

@Composable
fun ReiAnixStorageScreen(
    state: ReiAnixLibraryUiState,
    onBack: () -> Unit,
    onSelectSaf: () -> Unit,
    onRemoveSaf: (String) -> Unit,
    onRequestMediaAccess: () -> Unit,
    onOpenBroadSettings: () -> Unit,
    onCheckAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    ReiAnixResponsiveRoot {
    val storage = state.storage
    var pendingRemoval by remember { mutableStateOf<ReiAnixStorageSourceUiModel?>(null) }

    val configuredSafSources = storage.configuredSources
        .filter { it.kind.equals("saf", ignoreCase = true) }
        .sortedBy { it.stableKey }

    val otherSources = storage.configuredSources
        .filterNot { it.kind.equals("saf", ignoreCase = true) }
        .sortedBy { it.stableKey }

    val selectionBusy =
        storage.safSelectionPending ||
            (
                state.lastCommandAction.equals("select_saf", ignoreCase = true) &&
                    state.lastCommandStatus?.uppercase() in setOf("QUEUED", "RUNNING")
                )
    val removeBusy =
        state.lastCommandAction.equals("remove_saf", ignoreCase = true) &&
            state.lastCommandStatus?.uppercase() in setOf("QUEUED", "RUNNING")

    val mediaState = storage.mediaReadState.lowercase()
    val broadState = storage.broadStorageState.lowercase()
    val mediaNeedsAction = mediaState != "full"
    val broadNeedsAction = broadState != "available"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = LocalReiAnixResponsiveMetrics.current.settingsMaxWidth)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding,
                vertical = ReiAnixTokens.Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
        ) {
            item(key = "header") {
                SettingsHeader(
                    title = "Armazenamento",
                    subtitle = "Pastas da biblioteca e acessos locais",
                    onBack = onBack,
                    backContentDescription = "Voltar do armazenamento",
                )
            }

            if (storage.lifecycleState == "unknown" || storage.api == null) {
                item(key = "checking") {
                    ReiAnixLoadingState(
                        title = "Verificando armazenamento",
                        message = "Consultando as permissões e fontes locais…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (storage.safSelectionPending) {
                item(key = "selection") {
                    ReiAnixLoadingState(
                        title = "Seleção de pasta em andamento",
                        message = "Aguardando o seletor de pastas do Android…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "library-section") {
                StorageSectionTitle(
                    title = "Pasta da biblioteca",
                    description = "Escolha uma pasta local que o ReiAnix poderá ler e manter autorizada no Android.",
                )
            }

            if (configuredSafSources.isEmpty()) {
                item(key = "library-empty") {
                    ReiAnixSettingsSurface(
                        modifier = Modifier
                            .fillMaxWidth()
            
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Nenhuma pasta configurada",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Escolha uma pasta local para alimentar a biblioteca.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (storage.safRoots.isNotEmpty()) {
                                    Text(
                                        text = persistedGrantMessage(storage.safRoots.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(ReiAnixTokens.Spacing.md))
                        ReiAnixPrimaryButton(
                            text = "Selecionar pasta",
                            onClick = onSelectSaf,
                            enabled = !selectionBusy,
                            leadingIcon = Icons.Filled.Info,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                items(
                    items = configuredSafSources,
                    key = { "saf-source:" + it.stableKey },
                ) { source ->
                    SafSourceCard(
                        source = source,
                        state = storage.sourceState(source),
                        selectionBusy = selectionBusy,
                        removeBusy = removeBusy,
                        onReauthorize = onSelectSaf,
                        onRemove = { pendingRemoval = source },
                    )
                }

                item(key = "library-add") {
                    ReiAnixSecondaryButton(
                        text = "Adicionar ou alterar pasta",
                        onClick = onSelectSaf,
                        enabled = !selectionBusy,
                        leadingIcon = Icons.Filled.Info,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "device-access-section") {
                StorageSectionTitle(
                    title = "Acessos do dispositivo",
                    description = "Esses acessos são capacidades do Android e não substituem a pasta configurada para a biblioteca.",
                )
            }

            item(key = "media-access") {
                DeviceAccessCard(
                    title = "Vídeos do dispositivo",
                    description = "Permissão de leitura usada para descobrir vídeos que o Android já indexou.",
                    stateLabel = mediaAccessLabel(mediaState),
                    stateTone = accessBadgeTone(mediaState, "full"),
                    icon = Icons.Filled.Info,
                    actionLabel = if (mediaNeedsAction) "Conceder acesso" else null,
                    onAction = if (mediaNeedsAction) onRequestMediaAccess else null,
                )
            }

            item(key = "broad-access") {
                DeviceAccessCard(
                    title = "Acesso amplo ao dispositivo",
                    description = "Permissão especial do Android, usada somente quando já foi concedida nas configurações.",
                    stateLabel = if (broadState == "available") "Acesso concedido" else "Acesso necessário",
                    stateTone = if (broadState == "available") ReiAnixBadgeTone.Success else ReiAnixBadgeTone.Warning,
                    icon = Icons.Filled.Settings,
                    actionLabel = if (broadNeedsAction) "Abrir configurações" else null,
                    onAction = if (broadNeedsAction) onOpenBroadSettings else null,
                )
            }

            if (otherSources.isNotEmpty()) {
                item(key = "other-sources-section") {
                    StorageSectionTitle(
                        title = "Outras fontes configuradas",
                        description = "Fontes adicionais já existentes na configuração atual; esta tela não cria uma segunda fonte de dados.",
                    )
                }

                items(
                    items = otherSources,
                    key = { "configured-source:" + it.stableKey },
                ) { source ->
                    ConfiguredSourceCard(
                        source = source,
                        state = storage.sourceState(source),
                        onAction = when (source.kind.lowercase()) {
                            "mediastore", "media" -> onRequestMediaAccess
                            "broad-storage", "broad" -> onOpenBroadSettings
                            else -> null
                        },
                    )
                }
            }

            item(key = "scan") {
                StorageScanCard(
                    state = state,
                    onCheckAccess = onCheckAccess,
                    onRefreshLibrary = onRefreshLibrary,
                )
            }
        }
    }

    pendingRemoval?.let { source ->
        val sourceName = friendlySourceName(source.name).ifBlank { "esta pasta" }
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Remover pasta da biblioteca?") },
            text = {
                Text(
                    "“$sourceName” será removida somente da configuração da biblioteca. "
                        + "A permissão Android dessa pasta também será liberada. Nenhum arquivo físico será apagado.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onRemoveSaf(source.reference)
                    },
                    enabled = !removeBusy && !selectionBusy,
                    modifier = Modifier.semantics {
                        contentDescription = "Confirmar remoção de $sourceName"
                    },
                ) {
                    Text("Remover")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text("Cancelar")
                }
            },
        )
    }

    }
    }
}

@Composable
private fun StorageSectionTitle(
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SafSourceCard(
    source: ReiAnixStorageSourceUiModel,
    state: String,
    selectionBusy: Boolean,
    removeBusy: Boolean,
    onReauthorize: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = friendlySourceName(source.name).ifBlank { "Pasta da biblioteca" }
    val available = state == "available"
    val stateLabel = safStateLabel(state)
    val actionLabel = if (available) "Alterar pasta" else "Reautorizar"

    ReiAnixSettingsSurface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$name: $stateLabel"
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Icon(
                imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (available) ReiAnixTokens.Colors.success else ReiAnixTokens.Colors.warning,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (available) {
                    Text(
                        text = "Pasta local da biblioteca",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    com.reiflix.reiflix_local.ui.ReiAnixSourceUnavailableState(
                        title = stateLabel,
                        message = when (state) {
                            "revoked" -> "A autorização desta pasta não está mais disponível no Android."
                            "unavailable" -> "O provedor desta pasta está indisponível no momento."
                            else -> "Não foi possível confirmar o acesso desta pasta."
                        },
                        actionLabel = null,
                        onAction = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ReiAnixBadge(
                text = stateLabel,
                tone = if (available) ReiAnixBadgeTone.Success else ReiAnixBadgeTone.Warning,
            )
        }

        Spacer(Modifier.height(ReiAnixTokens.Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            ReiAnixSecondaryButton(
                text = actionLabel,
                onClick = onReauthorize,
                enabled = !selectionBusy,
                leadingIcon = Icons.Filled.Refresh,
                modifier = Modifier.weight(1f),
            )
            ReiAnixSecondaryButton(
                text = "Remover",
                onClick = onRemove,
                enabled = !removeBusy && !selectionBusy,
                leadingIcon = Icons.Filled.Delete,
                modifier = Modifier.weight(0.78f),
            )
        }
    }
}

@Composable
private fun DeviceAccessCard(
    title: String,
    description: String,
    stateLabel: String,
    stateTone: ReiAnixBadgeTone,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ReiAnixBadge(
                text = stateLabel,
                tone = stateTone,
            )
        }

        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(ReiAnixTokens.Spacing.md))
            ReiAnixSecondaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConfiguredSourceCard(
    source: ReiAnixStorageSourceUiModel,
    state: String,
    onAction: (() -> Unit)?,
) {
    val name = friendlySourceName(source.name).ifBlank { "Fonte local" }
    val stateLabel = genericSourceStateLabel(state)
    val available = state in setOf("available", "full", "partial", "granted")

    ReiAnixSettingsSurface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$name: $stateLabel"
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
        ) {
            Icon(
                imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (available) ReiAnixTokens.Colors.success else ReiAnixTokens.Colors.warning,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReiAnixBadge(
                text = stateLabel,
                tone = if (available) ReiAnixBadgeTone.Success else ReiAnixBadgeTone.Warning,
            )
        }

        if (!available && onAction != null) {
            Spacer(Modifier.height(ReiAnixTokens.Spacing.md))
            ReiAnixSecondaryButton(
                text = "Verificar acesso",
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StorageScanCard(
    state: ReiAnixLibraryUiState,
    onCheckAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    val scanLabel = scanStateLabel(state.scanState, state.scanInProgress)

    ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Atualização da biblioteca",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(ReiAnixTokens.Spacing.xs))
        Text(
            text = scanLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.scanInProgress) {
            Spacer(Modifier.height(ReiAnixTokens.Spacing.sm))
            ReiAnixScannerInProgressState(
                scanState = scanLabel,
                compact = true,
            )
        }

        Spacer(Modifier.height(ReiAnixTokens.Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm),
        ) {
            ReiAnixSecondaryButton(
                text = "Verificar acesso",
                onClick = onCheckAccess,
                enabled = !state.scanInProgress,
                modifier = Modifier.weight(1f),
            )
            ReiAnixPrimaryButton(
                text = "Atualizar biblioteca",
                onClick = onRefreshLibrary,
                enabled = !state.scanInProgress,
                leadingIcon = Icons.Filled.Refresh,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun persistedGrantMessage(count: Int): String =
    if (count == 1) {
        "Existe uma permissão de pasta já salva no Android, mas ela ainda não foi configurada na biblioteca."
    } else {
        "Existem $count permissões de pasta já salvas no Android, mas nenhuma foi configurada na biblioteca."
    }

private fun friendlySourceName(raw: String): String {
    val value = raw.trim()
    return if (
        value.isBlank() ||
        value.startsWith("content://", ignoreCase = true) ||
        value.startsWith("file://", ignoreCase = true)
    ) {
        "Pasta da biblioteca"
    } else {
        value
    }
}

private fun safStateLabel(state: String): String = when (state.lowercase()) {
    "available", "granted", "full" -> "Acesso concedido"
    "revoked" -> "Acesso perdido"
    "unavailable", "error" -> "Acesso indisponível"
    "partial" -> "Acesso parcial"
    "unknown" -> "Verificando acesso"
    else -> "Acesso necessário"
}

private fun genericSourceStateLabel(state: String): String = when (state.lowercase()) {
    "available", "granted", "full" -> "Acesso concedido"
    "partial" -> "Acesso parcial"
    "revoked" -> "Acesso perdido"
    "unavailable", "error" -> "Acesso indisponível"
    "denied" -> "Acesso necessário"
    else -> "Estado desconhecido"
}

private fun mediaAccessLabel(state: String): String = when (state) {
    "full" -> "Acesso concedido"
    "partial" -> "Acesso parcial"
    else -> "Acesso necessário"
}

private fun accessBadgeTone(state: String, successState: String): ReiAnixBadgeTone =
    if (state == successState) ReiAnixBadgeTone.Success else ReiAnixBadgeTone.Warning

private fun scanStateLabel(state: String, inProgress: Boolean): String = when {
    inProgress -> "Atualizando a biblioteca…"
    state.equals("COMPLETED", ignoreCase = true) -> "Biblioteca atualizada"
    state.equals("EMPTY", ignoreCase = true) || state.equals("EMPTY_COMPLETE", ignoreCase = true) ->
        "A fonte está acessível e não contém vídeos reconhecidos"
    state.equals("PARTIAL", ignoreCase = true) -> "Atualização parcial; o catálogo confirmado foi preservado"
    state.equals("FAILED", ignoreCase = true) -> "Não foi possível concluir a atualização"
    state.equals("CANCELLED", ignoreCase = true) -> "Atualização cancelada"
    state.equals("WAITING_FOR_MEDIASTORE", ignoreCase = true) -> "Aguardando o MediaStore concluir a indexação"
    state.equals("VOLUME_UNAVAILABLE", ignoreCase = true) -> "Uma fonte de mídia está indisponível"
    else -> "Nenhuma atualização em andamento"
}
