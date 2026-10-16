package com.reiflix.reiflix_local.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsOperationState
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel

/**
 * Explicit recovery surface used when the canonical SQLite database fails the native
 * runtime contract. It does not read or write the damaged catalog; the backup service
 * validates a selected v1 archive and creates a raw safety snapshot before promotion.
 */
@Composable
fun ReiAnixRecoveryRoute(
    viewModel: ReiAnixSettingsViewModel,
) {
    val settingsState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmationVisible by rememberSaveable { mutableStateOf(false) }
    val operation = settingsState.operations.values
        .filter {
            it.action == "backup_recovery_restore" ||
                it.action == "backup_recovery_snapshot" ||
                it.action == "diagnostic_export"
        }
        .maxByOrNull { it.timestampMs }
    val busy = operation?.state == ReiAnixSettingsOperationState.QUEUED ||
        operation?.state == ReiAnixSettingsOperationState.RUNNING

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Recuperação da biblioteca",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "O banco de dados local não passou na verificação nativa de integridade ou compatibilidade. " +
                    "A biblioteca não será inicializada normalmente até que um backup válido seja restaurado.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Selecione um backup ReiAnix ZIP no formato suportado. O arquivo será inspecionado e seus dados " +
                    "apresentados antes da confirmação. Antes de substituir o banco, o aplicativo preservará uma cópia bruta " +
                    "do SQLite atual e dos arquivos auxiliares disponíveis. Vídeos externos e permissões SAF não são incorporados.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settingsState.status.name == "ERROR" && !settingsState.error.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "A leitura das configurações atuais também está indisponível até que o banco seja recuperado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = operation?.message?.takeIf { it.isNotBlank() }
                            ?: "Validando e restaurando a biblioteca…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            when (operation?.state) {
                ReiAnixSettingsOperationState.SUCCESS -> {
                    Text(
                        text = operation.message ?: "Restauração validada. A biblioteca será reaberta.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                ReiAnixSettingsOperationState.ERROR -> {
                    Text(
                        text = operation.error ?: "A restauração falhou. O banco anterior foi preservado quando possível.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                ReiAnixSettingsOperationState.CANCELLED -> {
                    Text(
                        text = operation.message ?: "A seleção ou a restauração foi cancelada.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                else -> Unit
            }
            Button(
                onClick = { confirmationVisible = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Selecionar backup para recuperar")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.requestAction("backup_recovery_snapshot") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Criar snapshot bruto de segurança")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.requestAction("diagnostic_export") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Exportar diagnóstico")
            }
        }
    }

    if (confirmationVisible) {
        AlertDialog(
            onDismissRequest = { confirmationVisible = false },
            title = { Text("Recuperar a biblioteca?") },
            text = {
                Text(
                    "O backup escolhido será validado antes da promoção. O banco ativo pode ser substituído. " +
                        "Será criado um snapshot de segurança com o banco e os arquivos auxiliares que ainda estiverem disponíveis. " +
                        "Use um backup do ReiAnix em que você confia.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmationVisible = false
                        viewModel.requestAction("backup_recovery_restore")
                    },
                ) {
                    Text("Escolher backup")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmationVisible = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}
