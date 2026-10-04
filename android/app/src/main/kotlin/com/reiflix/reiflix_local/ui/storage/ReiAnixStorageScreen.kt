package com.reiflix.reiflix_local.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixStorageSourceUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixStorageUiState
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Composable
fun ReiAnixStorageRoute(
    viewModel: ReiAnixLibraryViewModel,
    onBack: () -> Unit,
    onRequestMediaAccess: () -> Boolean,
    onOpenBroadSettings: () -> Boolean,
    onCheckAccess: () -> Boolean,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReiAnixStorageScreen(
        state = state,
        onBack = onBack,
        onSelectSaf = viewModel::selectSafTree,
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
    onRequestMediaAccess: () -> Unit,
    onOpenBroadSettings: () -> Unit,
    onCheckAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    val storage = state.storage
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = ReiAnixTokens.Colors.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics {
                        contentDescription = "Voltar das configurações de armazenamento"
                    },
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                }
                Text(
                    text = "Armazenamento",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (storage.lifecycleState == "unknown" || storage.api == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (storage.safSelectionPending) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                        vertical = ReiAnixTokens.Spacing.xs,
                    ),
                ) {
                    Text(
                        text = "Seleção de pasta em andamento…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                    vertical = ReiAnixTokens.Spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.md),
            ) {
                item(key = "summary") {
                    StorageSummaryCard(
                        storage = storage,
                        scanInProgress = state.scanInProgress,
                        scanState = state.scanState,
                        onCheckAccess = onCheckAccess,
                        onRefreshLibrary = onRefreshLibrary,
                    )
                }
                item(key = "saf") {
                    StoragePermissionCard(
                        title = "Pasta da biblioteca (SAF)",
                        description = "As pastas autorizadas continuam usando a permissão persistente do Android.",
                        icon = Icons.Filled.Home,
                        actionLabel = "Escolher pasta",
                        onAction = onSelectSaf,
                    )
                }
                item(key = "media") {
                    StoragePermissionCard(
                        title = "Vídeos do dispositivo",
                        description = "MediaStore é uma capacidade do dispositivo; uma permissão de mídia não cria uma fonte da biblioteca.",
                        icon = Icons.Filled.Info,
                        actionLabel = "Solicitar acesso",
                        onAction = onRequestMediaAccess,
                    )
                }
                item(key = "broad") {
                    StoragePermissionCard(
                        title = "Armazenamento amplo",
                        description = "Somente a permissão especial realmente concedida pelo Android é considerada.",
                        icon = Icons.Filled.Settings,
                        actionLabel = "Abrir configurações",
                        onAction = onOpenBroadSettings,
                    )
                }
                item(key = "configured") {
                    ConfiguredSourcesCard(storage)
                }
            }
        }
    }
}

@Composable
private fun StorageSummaryCard(
    storage: ReiAnixStorageUiState,
    scanInProgress: Boolean,
    scanState: String,
    onCheckAccess: () -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Estado real do armazenamento",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            StorageStateLine("MediaStore", storage.mediaReadState, storage.mediaReadState in setOf("full", "partial"))
            StorageStateLine("SAF", if (storage.safRoots.isNotEmpty()) "available" else "revoked", storage.safRoots.isNotEmpty())
            StorageStateLine("Armazenamento amplo", storage.broadStorageState, storage.broadStorageState == "available")
            Text(
                text = "Ciclo: " + storage.lifecycleState.ifBlank { "unknown" } + " • Android " + (storage.api ?: "?"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Varredura: " + scanState.lowercase() + if (scanInProgress) " • em andamento" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheckAccess) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.padding(start = 4.dp))
                    Text("Verificar")
                }
                FilledTonalButton(onClick = onRefreshLibrary) {
                    Text("Atualizar biblioteca")
                }
            }
        }
    }
}

@Composable
private fun StoragePermissionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ConfiguredSourcesCard(storage: ReiAnixStorageUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ReiAnixTokens.Colors.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Fontes configuradas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (storage.configuredSources.isEmpty()) {
                Text(
                    "Nenhuma fonte foi configurada na biblioteca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                storage.configuredSources.forEach { source ->
                    ConfiguredSourceRow(source, storage.sourceState(source))
                }
            }
        }
    }
}

@Composable
private fun ConfiguredSourceRow(
    source: ReiAnixStorageSourceUiModel,
    state: String,
) {
    val available = state in setOf("available", "full", "partial", "granted")
    val icon = if (available) Icons.Filled.CheckCircle else Icons.Filled.Warning
    val label = when (state.lowercase()) {
        "available", "full", "granted" -> "Disponível"
        "partial" -> "Parcial"
        "revoked" -> "Revogada"
        "unavailable" -> "Indisponível"
        else -> state.replace('_', ' ').ifBlank { "Desconhecida" }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = source.name.ifBlank { source.reference } + ": " + label
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(
                text = source.name.ifBlank { source.reference },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            text = source.reference,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StorageStateLine(label: String, state: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null)
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(state.ifBlank { "unknown" }.replace('_', ' '))
    }
}
