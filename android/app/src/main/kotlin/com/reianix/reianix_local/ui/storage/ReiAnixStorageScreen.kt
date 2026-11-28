package com.reianix.reianix_local.ui.storage

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Add
import com.reianix.reianix_local.ui.settings.settingsClickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reianix.reianix_local.ui.ReiAnixBadge
import com.reianix.reianix_local.ui.ReiAnixCompactButton
import com.reianix.reianix_local.ui.ReiAnixBadgeTone
import com.reianix.reianix_local.ui.ReiAnixCard
import com.reianix.reianix_local.ui.ReiAnixLoadingState
import com.reianix.reianix_local.ui.ReiAnixPrimaryButton
import com.reianix.reianix_local.ui.ReiAnixScannerInProgressState
import com.reianix.reianix_local.ui.ReiAnixSecondaryButton
import com.reianix.reianix_local.ui.model.ReiAnixLibraryUiState
import com.reianix.reianix_local.ui.model.ReiAnixStorageSourceUiModel
import com.reianix.reianix_local.viewmodel.ReiAnixNativeFoldersUiState
import com.reianix.reianix_local.viewmodel.ReiAnixNativeFoldersStatus
import com.reianix.reianix_local.ui.settings.ReiAnixSettingsSurface
import com.reianix.reianix_local.ui.settings.ReiAnixSettingsRoot
import com.reianix.reianix_local.ui.settings.SettingsHeader
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import com.reianix.reianix_local.ui.theme.LocalReiAnixResponsiveMetrics
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixStorageRoute(
    viewModel: ReiAnixLibraryViewModel,
    onBack: () -> Unit,
    onRemoveSaf: (String) -> Unit,
    onOpenBroadSettings: () -> Unit,
    onCheckAccess: () -> Unit,
    title: String = "Armazenamento",
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nativeFolders by viewModel.nativeFoldersState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.refreshNativeFolders()
    }
    ReiAnixStorageScreen(
        state = state,
        title = title,
        nativeFolders = nativeFolders,
        onRefreshNativeFolders = viewModel::refreshNativeFolders,
        onBack = onBack,
        onSelectSaf = viewModel::selectSafTree,
        onRemoveSaf = onRemoveSaf,
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
    onOpenBroadSettings: () -> Unit,
    onCheckAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
    nativeFolders: ReiAnixNativeFoldersUiState = ReiAnixNativeFoldersUiState(),
    onRefreshNativeFolders: () -> Unit = {},
    title: String = "Armazenamento",
) {
    ReiAnixSettingsRoot {
        ReiAnixLibraryFoldersSection(state, nativeFolders, onSelectSaf, onRemoveSaf,
            onRefreshNativeFolders, onBack = onBack)
    }
}

/** Same canonical folder state and native SAF actions as the dedicated storage screen. */
@Composable
fun ReiAnixLibraryFoldersRoute(
    viewModel: ReiAnixLibraryViewModel,
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nativeFolders by viewModel.nativeFoldersState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshNativeFolders() }
    ReiAnixSettingsRoot {
    ReiAnixLibraryFoldersSection(
        state = state,
        nativeFolders = nativeFolders,
        onSelectSaf = viewModel::selectSafTree,
        onRemoveSaf = viewModel::removeSafTree,
        onRefreshNativeFolders = viewModel::refreshNativeFolders,
        onBack = onBack,
    )
    }
}

@Composable
private fun ReiAnixLibraryFoldersSection(
    state: ReiAnixLibraryUiState,
    nativeFolders: ReiAnixNativeFoldersUiState,
    onSelectSaf: () -> Unit,
    onRemoveSaf: (String) -> Unit,
    onRefreshNativeFolders: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val storage = state.storage
    var pendingRemoval by remember { mutableStateOf<ReiAnixStorageSourceUiModel?>(null) }

    // The library snapshot also retains configured SAF sources while the SQLite query
    // is loading or unavailable. Show those sources rather than an empty folder list.
    val snapshotSafSources = storage.configuredSources
        .filter { it.kind.equals("saf", ignoreCase = true) && it.reference.isNotBlank() }
    val sqliteSafSources = nativeFolders.folders
        .filter { android.provider.DocumentsContract.isTreeUri(android.net.Uri.parse(it.path)) }
        .map { ReiAnixStorageSourceUiModel(reference = it.path, name = it.name.orEmpty(), kind = "saf") }
    val configuredSafSources = when (nativeFolders.status) {
        // Once SQLite has answered, it is authoritative. Avoid showing a recently
        // removed folder that may still be present in the older library snapshot.
        ReiAnixNativeFoldersStatus.READY, ReiAnixNativeFoldersStatus.EMPTY -> {
            val persistedPaths = nativeFolders.folders.map { it.path }.toSet()
            (snapshotSafSources.filter { it.reference in persistedPaths } + sqliteSafSources)
                .distinctBy { it.reference }
        }
        // During loading or query failures, retain the last known configured folders.
        else -> snapshotSafSources
    }
    // Keep the folder list stable while SQLite refreshes. A very short query should
    // not flash a full-screen loading state over folders we already know about.
    var showFolderLoading by remember { mutableStateOf(false) }
    LaunchedEffect(nativeFolders.status, configuredSafSources.isEmpty()) {
        showFolderLoading = false
        if (nativeFolders.status == ReiAnixNativeFoldersStatus.LOADING &&
            configuredSafSources.isEmpty()) {
            kotlinx.coroutines.delay(300)
            showFolderLoading = true
        }
    }
    val selectionBusy =
        storage.safSelectionPending ||
            (
                state.lastCommandAction.equals("select_saf", ignoreCase = true) &&
                    state.lastCommandStatus?.uppercase() in setOf("QUEUED", "RUNNING")
                )
    val removeBusy =
        state.lastCommandAction.equals("remove_saf", ignoreCase = true) &&
            state.lastCommandStatus?.uppercase() in setOf("QUEUED", "RUNNING")

    Surface(modifier = modifier.fillMaxSize(), color = reiAnixScreenBackground(), contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                item(key = "folders-header") {
                    SettingsHeader(stringResource(R.string.settings_folders), "", onBack)
                }
                if (showFolderLoading) {
                    item(key = "folders-loading") {
                        ReiAnixLoadingState(stringResource(R.string.final_storage_check), stringResource(R.string.final_storage_folders_read))
                    }
                }
                if (storage.safSelectionPending) {
                    item(key = "folders-selecting") {
                        ReiAnixLoadingState(stringResource(R.string.final_storage_selecting), stringResource(R.string.final_storage_wait_picker))
                    }
                }
                items(configuredSafSources, key = { it.reference }) { source ->
                    val name = friendlySourceName(source.name).ifBlank { stringResource(R.string.storage_library_folder) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurface)
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(source.reference, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.IconButton(onClick = { pendingRemoval = source },
                            enabled = !removeBusy && !selectionBusy) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.a11y_remove_named, name))
                        }
                    }
                }
                if (nativeFolders.status == ReiAnixNativeFoldersStatus.ERROR ||
                    nativeFolders.status == ReiAnixNativeFoldersStatus.NOT_FOUND) {
                    item(key = "folders-error") {
                        Text(stringResource(R.string.extra_storage_read_error), modifier = Modifier.padding(20.dp))
                        ReiAnixSecondaryButton("Tentar novamente", onRefreshNativeFolders)
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp)
                .heightIn(min = 48.dp)
                .settingsClickable(enabled = !selectionBusy && !removeBusy, onClick = onSelectSaf)
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.extra_storage_add), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    pendingRemoval?.let { source ->
        val sourceName = friendlySourceName(source.name)
        val removalDescription = stringResource(R.string.final_storage_remove_a11y, sourceName)
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            title = { Text(stringResource(R.string.extra_storage_remove_question)) },
            text = {
                Text(
                    stringResource(R.string.final_storage_remove_confirm, sourceName),
                )
            },
            confirmButton = {
                ReiAnixCompactButton(
                    text = stringResource(R.string.extra_remove),
                    onClick = {
                        pendingRemoval = null
                        onRemoveSaf(source.reference)
                    },
                    enabled = !removeBusy && !selectionBusy,
                    modifier = Modifier.semantics {
                        contentDescription = removalDescription
                    },
                )
            },
            dismissButton = {
                ReiAnixCompactButton(
                    text = stringResource(R.string.locale_cancel),
                    onClick = { pendingRemoval = null },
                )
            },
        )
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
            title = stringResource(R.string.extra_storage_records),
            description = stringResource(R.string.extra_storage_records_desc),
        )
        when (state.status) {
            ReiAnixNativeFoldersStatus.IDLE -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.extra_storage_not_queried),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReiAnixNativeFoldersStatus.LOADING -> ReiAnixLoadingState(
                title = stringResource(R.string.extra_storage_querying),
                message = stringResource(R.string.extra_storage_reading),
                modifier = Modifier.fillMaxWidth(),
            )
            ReiAnixNativeFoldersStatus.READY -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)) {
                    Text(
                        text = stringResource(R.string.final_storage_record_count, state.folders.size),
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
                    text = stringResource(R.string.extra_storage_no_records),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReiAnixNativeFoldersStatus.NOT_FOUND -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.extra_storage_invalid_ref),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                ReiAnixSecondaryButton(text = stringResource(R.string.extra_storage_query_again), onClick = onRetry)
            }
            ReiAnixNativeFoldersStatus.ERROR -> ReiAnixSettingsSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.extra_storage_read_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                ReiAnixSecondaryButton(text = stringResource(R.string.ui_retry), onClick = onRetry)
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
    val name = friendlySourceName(source.name).ifBlank { stringResource(R.string.storage_library_folder) }
    val available = state == "available"
    val stateLabel = safStateLabel(state)
    val actionLabel = stringResource(if (available) R.string.storage_change_folder else R.string.storage_reauthorize)

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
                        text = stringResource(R.string.extra_storage_local_folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    com.reianix.reianix_local.ui.ReiAnixSourceUnavailableState(
                        title = stateLabel,
                        message = when (state) {
                            "revoked" -> stringResource(R.string.storage_folder_permission_missing)
                            "unavailable" -> stringResource(R.string.storage_folder_provider_now)
                            else -> stringResource(R.string.storage_folder_access_unknown)
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
                text = stringResource(R.string.extra_remove),
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
    val name = friendlySourceName(source.name).ifBlank { stringResource(R.string.extra_storage_local_folder) }
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
                text = stringResource(R.string.extra_storage_verify),
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
            text = stringResource(R.string.extra_storage_refresh),
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
                text = stringResource(R.string.extra_storage_verify),
                onClick = onCheckAccess,
                enabled = !state.scanInProgress,
                modifier = Modifier.weight(1f),
            )
            ReiAnixPrimaryButton(
                text = stringResource(R.string.extra_refresh_library),
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

@Composable
private fun friendlySourceName(raw: String): String {
    val value = raw.trim()
    return if (
        value.isBlank() ||
        value.startsWith("content://", ignoreCase = true) ||
        value.startsWith("file://", ignoreCase = true)
    ) {
        stringResource(R.string.storage_library_folder)
    } else {
        value
    }
}

@Composable
private fun safStateLabel(state: String): String = when (state.lowercase()) {
    "available", "granted", "full" -> stringResource(R.string.final_access_granted)
    "revoked" -> stringResource(R.string.final_access_lost)
    "unavailable", "error" -> stringResource(R.string.final_access_unavailable)
    "partial" -> stringResource(R.string.final_access_partial)
    "unknown" -> stringResource(R.string.final_access_checking)
    else -> stringResource(R.string.final_access_required)
}

@Composable
private fun genericSourceStateLabel(state: String): String = when (state.lowercase()) {
    "available", "granted", "full" -> stringResource(R.string.final_access_granted)
    "partial" -> stringResource(R.string.final_access_partial)
    "revoked" -> stringResource(R.string.final_access_lost)
    "unavailable", "error" -> stringResource(R.string.final_access_unavailable)
    "denied" -> stringResource(R.string.final_access_required)
    else -> stringResource(R.string.final_access_unknown)
}

@Composable
private fun mediaAccessLabel(state: String): String = when (state) {
    "full" -> stringResource(R.string.final_access_granted)
    "partial" -> stringResource(R.string.final_access_partial)
    else -> stringResource(R.string.final_access_required)
}

private fun accessBadgeTone(state: String, successState: String): ReiAnixBadgeTone =
    if (state == successState) ReiAnixBadgeTone.Success else ReiAnixBadgeTone.Warning

@Composable
private fun scanStateLabel(state: String, inProgress: Boolean): String = when {
    inProgress -> stringResource(R.string.flow_library_refreshing)
    state.equals("COMPLETED", ignoreCase = true) -> stringResource(R.string.final_scan_updated)
    state.equals("EMPTY", ignoreCase = true) || state.equals("EMPTY_COMPLETE", ignoreCase = true) ->
        stringResource(R.string.final_scan_empty)
    state.equals("PARTIAL", ignoreCase = true) -> stringResource(R.string.final_scan_partial)
    state.equals("FAILED", ignoreCase = true) -> stringResource(R.string.final_scan_failed)
    state.equals("CANCELLED", ignoreCase = true) -> stringResource(R.string.final_scan_cancelled)
    state.equals("WAITING_FOR_MEDIASTORE", ignoreCase = true) -> stringResource(R.string.final_scan_media_wait)
    state.equals("VOLUME_UNAVAILABLE", ignoreCase = true) -> stringResource(R.string.final_scan_volume)
    else -> stringResource(R.string.final_scan_idle)
}
