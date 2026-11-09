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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.reiflix.reiflix_local.ui.ReiAnixCompactButton
import com.reiflix.reiflix_local.ui.ReiAnixBadgeTone
import com.reiflix.reiflix_local.ui.ReiAnixCard
import com.reiflix.reiflix_local.ui.ReiAnixLoadingState
import com.reiflix.reiflix_local.ui.ReiAnixPrimaryButton
import com.reiflix.reiflix_local.ui.ReiAnixScannerInProgressState
import com.reiflix.reiflix_local.ui.ReiAnixSecondaryButton
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixStorageSourceUiModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixNativeFoldersUiState
import com.reiflix.reiflix_local.viewmodel.ReiAnixNativeFoldersStatus
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
    val nativeFolders by viewModel.nativeFoldersState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.refreshNativeFolders()
    }
    ReiAnixStorageScreen(
        state = state,
        nativeFolders = nativeFolders,
        onRefreshNativeFolders = viewModel::refreshNativeFolders,
        onBack = onBack,
        onSelectSaf = viewModel::selectSafTree,
        onRemoveSaf = onRemoveSaf,
        onRequestMediaAccess = onRequestMediaAccess,
        onOpenBroadSettings = onOpenBroadSettings,
        onCheckAccess = onCheckAccess,
        onRefreshLibrary = {
            viewModel.refresh()
            viewModel.refreshNativeFolders()
        },
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
    nativeFolders: ReiAnixNativeFoldersUiState = ReiAnixNativeFoldersUiState(),
    onRefreshNativeFolders: () -> Unit = {},
) {
    ReiAnixResponsiveRoot {
    val storage = state.storage
    var pendingRemoval by remember { mutableStateOf<ReiAnixStorageSourceUiModel?>(null) }

    val configuredSafSources = nativeFolders.folders
        .filter { android.provider.DocumentsContract.isTreeUri(android.net.Uri.parse(it.path)) }
        .map { ReiAnixStorageSourceUiModel(reference = it.path, name = it.name.orEmpty(), kind = "saf") }
    val selectionBusy =
        storage.safSelectionPending ||
            (
                state.lastCommandAction.equals("select_saf", ignoreCase = true) &&
                    state.lastCommandStatus?.uppercase() in setOf("QUEUED", "RUNNING")
                )
    val removeBusy =
        state.lastCommandAction.equals("remove_saf", ignoreCase = true) &&
            state.lastCommandStatus?.uppercase() in setOf("QUEUED", "RUNNING")

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

            if (nativeFolders.status == ReiAnixNativeFoldersStatus.LOADING) {
                item(key = "checking") {
                    ReiAnixLoadingState(
                        title = "Verificando armazenamento",
                        message = "Consultando as pastas da biblioteca…",
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

            item(key = "select-folder") {
                ReiAnixPrimaryButton(
                    text = "Selecionar pasta",
                    onClick = onSelectSaf,
                    enabled = !selectionBusy && !removeBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (nativeFolders.status == ReiAnixNativeFoldersStatus.READY ||
                nativeFolders.status == ReiAnixNativeFoldersStatus.EMPTY) {
                item(key = "folder-count") {
                    val count = configuredSafSources.size
                    Text(
                        text = if (count == 1) "1 pasta cadastrada" else "$count pastas cadastradas",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            items(configuredSafSources, key = { it.reference }) { source ->
                val name = friendlySourceName(source.name).ifBlank { source.reference }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = source.reference,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.IconButton(
                        onClick = { pendingRemoval = source },
                        enabled = !removeBusy && !selectionBusy,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remover $name")
                    }
                }
            }
            if (nativeFolders.status == ReiAnixNativeFoldersStatus.ERROR ||
                nativeFolders.status == ReiAnixNativeFoldersStatus.NOT_FOUND) {
                item { ReiAnixSecondaryButton(text = "Tentar novamente", onClick = onRefreshNativeFolders) }
            }
        }
    }

    pendingRemoval?.let { source ->
        val sourceName = friendlySourceName(source.name).ifBlank { "esta pasta" }
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            title = { Text("Remover pasta da biblioteca?") },
            text = {
                Text(
                    "“$sourceName” será removida somente da configuração da biblioteca. "
                        + "A permissão Android dessa pasta também será liberada. Nenhum arquivo físico será apagado.",
                )
            },
            confirmButton = {
                ReiAnixCompactButton(
                    text = "Remover",
                    onClick = {
                        pendingRemoval = null
                        onRemoveSaf(source.reference)
                    },
                    enabled = !removeBusy && !selectionBusy,
                    modifier = Modifier.semantics {
                        contentDescription = "Confirmar remoção de $sourceName"
                    },
                )
            },
            dismissButton = {
                ReiAnixCompactButton(
                    text = "Cancelar",
                    onClick = { pendingRemoval = null },
                )
            },
        )
    }

    }
    }
}

@Composable
private fun NativeSqliteFoldersSection(
    state: ReiAnixNativeFoldersUiState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs),
    ) {
        StorageSectionTitle(
            title = "Registros persistidos no SQLite",
            description = "Consulta somente leitura do banco canônico. As permissões e alterações de pastas continuam sendo gerenciadas pelo fluxo atual.",
        )
        when (state.status) {
            ReiAnixNativeFoldersStatus.IDLE -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "A consulta nativa ainda não foi executada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReiAnixNativeFoldersStatus.LOADING -> ReiAnixLoadingState(
                title = "Consultando pastas persistidas",
                message = "Lendo os registros locais do SQLite…",
                modifier = Modifier.fillMaxWidth(),
            )
            ReiAnixNativeFoldersStatus.READY -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
                    Text(
                        text = "${state.folders.size} registro(s) de pasta no SQLite",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    state.folders.forEach { folder ->
                        Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.xs)) {
                            Text(
                                text = folder.name?.takeIf { it.isNotBlank() } ?: folder.path,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = folder.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            ReiAnixNativeFoldersStatus.EMPTY -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Nenhuma pasta registrada no banco SQLite.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReiAnixNativeFoldersStatus.NOT_FOUND -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "A consulta nativa retornou uma referência inexistente.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                ReiAnixSecondaryButton(text = "Consultar novamente", onClick = onRetry)
            }
            ReiAnixNativeFoldersStatus.ERROR -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Não foi possível consultar as pastas persistidas. Os registros configurados continuam preservados.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                ReiAnixSecondaryButton(text = "Tentar novamente", onClick = onRetry)
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
