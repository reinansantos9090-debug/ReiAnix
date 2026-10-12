package com.reiflix.reiflix_local.ui.storage

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.reiflix.reiflix_local.storage.SafFileOperations
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
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
                notice = "$operation concluída no armazenamento. A atualização do catálogo foi solicitada; confira a reconciliação antes de presumir que histórico e progresso foram preservados."
                error = null
                viewModel.refresh()
                current?.let(::refresh)
            }
            is SafFileOperations.Result.Failure -> {
                notice = null
                error = result.message + if (result.recoverable) " O estado é ambíguo; verifique a pasta antes de repetir a operação." else ""
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = {
                    if (movingTarget != null) movingTarget = null
                    else if (parents.isNotEmpty()) { currentText = parents.last(); parents = parents.dropLast(1) }
                    else onBack()
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "Voltar") }
                Column(Modifier.weight(1f)) {
                    Text(if (movingTarget == null) "Gerenciar arquivos" else "Mover: ${movingTarget?.name}", style = MaterialTheme.typography.titleLarge)
                    Text(if (movingTarget == null) "Arquivos e pastas das fontes SAF autorizadas" else "Navegue até o destino e confirme.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (movingTarget == null) {
                    IconButton(enabled = !busy && current != null, onClick = { createDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Criar pasta")
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
                    }) { Text("Mover para cá") }
                }
            }
            if (roots.size > 1) {
                Text("Fontes autorizadas", style = MaterialTheme.typography.labelLarge)
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
                Text("Autorize uma pasta pelo fluxo de armazenamento existente.")
                Button(onClick = onBack) { Text("Voltar e adicionar pasta") }
            } else if (items.isEmpty() && error == null) {
                Text("Esta pasta está vazia.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        title = { Text("Criar pasta") },
        text = { OutlinedTextField(createName, { createName = it }, label = { Text("Nome da pasta") }, singleLine = true) },
        confirmButton = { TextButton(enabled = !busy && createName.isNotBlank() && current != null, onClick = {
            val parent = current ?: return@TextButton
            busy = true
            scope.launch {
                val result = viewModel.createSafDirectory(parent, createName)
                busy = false; createDialog = false; createName = ""
                finish(result, "Criação de pasta")
            }
        }) { Text("Criar") } },
        dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancelar") } },
    )

    renameTarget?.let { target -> AlertDialog(
        onDismissRequest = { if (!busy) renameTarget = null },
        title = { Text("Renomear item") },
        text = { OutlinedTextField(renameName, { renameName = it }, label = { Text("Novo nome") }, singleLine = true) },
        confirmButton = { TextButton(enabled = !busy && renameName.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                val result = viewModel.renameSafItem(target.uri, renameName)
                busy = false; renameTarget = null
                finish(result, "Renomeação")
            }
        }) { Text("Renomear") } },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } },
    ) }

    actionTarget?.let { target -> AlertDialog(
        onDismissRequest = { actionTarget = null },
        title = { Text(target.name) },
        text = { Text(if (target.isDirectory) "Pasta" else target.mimeType ?: "Tipo não informado") },
        confirmButton = { TextButton(onClick = { actionTarget = null; renameTarget = target; renameName = target.name }) {
            Icon(Icons.Default.Edit, contentDescription = null); Text("Renomear")
        } },
        dismissButton = { Row {
            TextButton(onClick = { actionTarget = null; movingTarget = target }) {
                Icon(Icons.Default.DriveFileMove, contentDescription = null); Text("Mover")
            }
            TextButton(onClick = { actionTarget = null; deleteTarget = target }) {
                Icon(Icons.Default.Delete, contentDescription = null); Text("Excluir")
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
        }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
    ) }
}

private fun formatSafBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
