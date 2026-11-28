package com.reianix.reianix_local.ui.storage

import com.reianix.reianix_local.R

import androidx.compose.ui.res.stringResource

import com.reianix.reianix_local.ui.theme.reiAnixScreenBackground

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reianix.reianix_local.storage.SafFileOperations
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ReiAnixSafFileManagerRoute(viewModel: ReiAnixLibraryViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf(emptyList<Uri>()) }
    var currentText by rememberSaveable { mutableStateOf<String?>(null) }
    var parents by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var items by remember { mutableStateOf(emptyList<SafFileOperations.ItemInfo>()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var createName by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<SafFileOperations.ItemInfo?>(null) }
    var renameName by rememberSaveable { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<SafFileOperations.ItemInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<SafFileOperations.ItemInfo?>(null) }
    var movingTarget by remember { mutableStateOf<SafFileOperations.ItemInfo?>(null) }
    val current = currentText?.let(Uri::parse)

    fun refresh(uri: Uri) {
        scope.launch {
            loading = true
            error = null
            when (val result = viewModel.listSafDirectory(uri)) {
                is SafFileOperations.ResultOrItems.Items -> items = result.value
                is SafFileOperations.ResultOrItems.Failed -> { items = emptyList(); error = result.result.message }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        roots = viewModel.authorizedSafTreeUris()
        if (currentText == null) currentText = roots.firstOrNull()?.toString()
        if (roots.isEmpty()) error = "Nenhuma pasta SAF persistida disponível. Adicione uma pasta pela opção de armazenamento."
    }
    LaunchedEffect(currentText) {
        val uri = currentText?.let(Uri::parse) ?: return@LaunchedEffect
        loading = true
        error = null
        when (val result = viewModel.listSafDirectory(uri)) {
            is SafFileOperations.ResultOrItems.Items -> items = result.value
            is SafFileOperations.ResultOrItems.Failed -> { items = emptyList(); error = result.result.message }
        }
        loading = false
    }

    fun finish(result: SafFileOperations.Result, operation: String) {
        when (result) {
            is SafFileOperations.Result.Success -> {
                notice = if (result.catalogReconciliationQueued) {
                    "$operation confirmada pelo provedor; reconciliação canônica enfileirada. A identidade só será considerada atualizada após o processamento do catálogo."
                } else {
                    "$operation concluída no armazenamento. A atualização do catálogo foi solicitada; a preservação de histórico e progresso ainda não foi confirmada."
                }
                error = null
                // Rename/move publish a durable identity-change event first. The
                // Python consumer publishes the canonical snapshot after its transaction.
                if (!result.catalogReconciliationQueued) viewModel.refresh()
                current?.let(::refresh)
            }
            is SafFileOperations.Result.Failure -> {
                notice = null
                error = result.message + if (result.recoverable) " O estado é ambíguo; verifique a pasta antes de repetir a operação." else ""
                if (result.recoverable) {
                    // The provider may have mutated storage before confirmation or mailbox failure.
                    // Refresh for recovery, but do not present the catalog as reconciled.
                    viewModel.refresh()
                    current?.let(::refresh)
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = reiAnixScreenBackground(), contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(
            Modifier.fillMaxSize().background(reiAnixScreenBackground()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = {
                    if (movingTarget != null) movingTarget = null
                    else if (parents.isNotEmpty()) { currentText = parents.last(); parents = parents.dropLast(1) }
                    else onBack()
                }) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.ui_back)) }
                Column(Modifier.weight(1f)) {
                    Text(if (movingTarget == null) "Gerenciar arquivos" else "Mover: ${movingTarget?.name}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (movingTarget == null) "Arquivos e pastas das fontes SAF autorizadas"
                        else "Navegue até o destino e confirme. A identidade do catálogo pode mudar; a preservação do histórico/progresso não é garantida.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (movingTarget == null) {
                    IconButton(enabled = !busy && current != null, onClick = { createDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.tail_create_folder))
                    }
                } else {
                    Button(enabled = !busy && current != null, onClick = {
                        val source = movingTarget
                        val destination = current
                        if (source != null && destination != null) {
                            busy = true
                            scope.launch {
                                val result = viewModel.moveSafItem(source.uri, destination)
                                busy = false
                                movingTarget = null
                                finish(result, "Movimentação")
                            }
                        }
                    }) { Text(stringResource(R.string.tail_move_here)) }
                }
            }
            if (roots.size > 1) {
                Text(stringResource(R.string.tail_authorized_sources), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    roots.forEachIndexed { index, root ->
                        OutlinedButton(onClick = {
                            currentText = root.toString(); parents = emptyList(); movingTarget = null
                        }, enabled = !busy) { Text(viewModel.safTreeDisplayName(root).take(22)) }
                    }
                }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (loading || busy) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (roots.isEmpty()) {
                Text(stringResource(R.string.tail_authorize_folder_hint))
                Button(onClick = onBack) { Text(stringResource(R.string.tail_back_add_folder)) }
            } else if (items.isEmpty() && error == null) {
                Text(stringResource(R.string.tail_folder_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(items, key = { it.uri.toString() }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy && item.isDirectory) {
                                val here = currentText ?: return@clickable
                                parents = parents + here
                                currentText = item.uri.toString()
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp).size(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val metadata = buildList {
                                    if (item.size != null && !item.isDirectory) add(formatSafBytes(item.size))
                                    if (item.lastModified != null) add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.lastModified)))
                                    item.mimeType?.takeIf { it.isNotBlank() }?.let(::add)
                                }.joinToString(" • ")
                                if (metadata.isNotEmpty()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(enabled = !busy, onClick = { actionTarget = item }) { Icon(Icons.Default.MoreVert, contentDescription = "Ações para ${item.name}") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (createDialog) AlertDialog(
        onDismissRequest = { if (!busy) createDialog = false },
        title = { Text(stringResource(R.string.tail_create_folder)) },
        text = { OutlinedTextField(createName, { createName = it }, label = { Text(stringResource(R.string.tail_folder_name)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = !busy && createName.isNotBlank() && current != null, onClick = {
            val parent = current ?: return@TextButton
            busy = true
            scope.launch {
                val result = viewModel.createSafDirectory(parent, createName)
                busy = false; createDialog = false; createName = ""
                finish(result, "Criação de pasta")
            }
        }) { Text(stringResource(R.string.tail_create)) } },
        dismissButton = { TextButton(onClick = { createDialog = false }) { Text(stringResource(R.string.locale_cancel)) } },
    )

    renameTarget?.let { target -> AlertDialog(
        onDismissRequest = { if (!busy) renameTarget = null },
        title = { Text(stringResource(R.string.tail_rename_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(renameName, { renameName = it }, label = { Text(stringResource(R.string.tail_new_name)) }, singleLine = true)
                Text(
                    stringResource(R.string.tail_rename_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = !busy && renameName.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                val result = viewModel.renameSafItem(target.uri, renameName)
                busy = false; renameTarget = null
                finish(result, "Renomeação")
            }
        }) { Text(stringResource(R.string.more_rename)) } },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.locale_cancel)) } },
    ) }

    actionTarget?.let { target -> AlertDialog(
        onDismissRequest = { actionTarget = null },
        title = { Text(target.name) },
        text = { Text(if (target.isDirectory) "Pasta" else target.mimeType ?: "Tipo não informado") },
        confirmButton = { TextButton(onClick = { actionTarget = null; renameTarget = target; renameName = target.name }) {
            Icon(Icons.Default.Edit, contentDescription = null); Text(stringResource(R.string.more_rename))
        } },
        dismissButton = { Row {
            TextButton(onClick = { actionTarget = null; movingTarget = target }) {
                Icon(Icons.Default.DriveFileMove, contentDescription = null); Text(stringResource(R.string.tail_move))
            }
            TextButton(onClick = { actionTarget = null; deleteTarget = target }) {
                Icon(Icons.Default.Delete, contentDescription = null); Text(stringResource(R.string.ui_delete))
            }
        } },
    ) }

    deleteTarget?.let { target -> AlertDialog(
        onDismissRequest = { if (!busy) deleteTarget = null },
        title = { Text("Excluir ${if (target.isDirectory) "pasta" else "arquivo"}?") },
        text = { Text("Esta ação é destrutiva. Confirme que ${target.name} é o item correto. O catálogo não será considerado reconciliado apenas pelo retorno do provedor.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val result = viewModel.deleteSafItem(target.uri)
                busy = false; deleteTarget = null
                finish(result, "Exclusão")
            }
        }) { Text(stringResource(R.string.ui_delete)) } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.locale_cancel)) } },
    ) }
}

private fun formatSafBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
