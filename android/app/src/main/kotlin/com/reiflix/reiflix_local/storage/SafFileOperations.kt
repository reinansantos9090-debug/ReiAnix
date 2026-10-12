package com.reiflix.reiflix_local.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * SAF-only file operations for user-authorized library trees.
 *
 * Content URIs remain the item identity. This service never derives filesystem paths
 * from provider document IDs and never falls back to direct filesystem access.
 * Persistence/catalog mutations are deliberately not performed here: callers must
 * commit identity changes only after receiving a confirmed [Result.Success].
 */
class SafFileOperations(private val context: Context) {
    enum class FailureKind {
        CANCELLED, PERMISSION_DENIED, NOT_FOUND, INVALID_ITEM, INVALID_NAME, CONFLICT,
        UNSUPPORTED, PARTIAL, FAILURE
    }

    sealed interface Result {
        data class Success(
            val uri: Uri,
            val name: String,
            val isDirectory: Boolean,
            val size: Long? = null,
            val lastModified: Long? = null,
            val mimeType: String? = null,
            val canRead: Boolean = true,
            val canWrite: Boolean = false,
            val catalogReconciliationQueued: Boolean = false,
        ) : Result
        data class Failure(val kind: FailureKind, val message: String, val sourceUri: Uri? = null,
                           val destinationUri: Uri? = null, val recoverable: Boolean = false) : Result
    }

    data class ItemInfo(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val size: Long?,
        val lastModified: Long?,
        val mimeType: String?,
        val canRead: Boolean,
        val canWrite: Boolean,
    )

    private val resolver: ContentResolver = context.contentResolver
    private val locks = ConcurrentHashMap<String, Any>()

    suspend fun list(directoryUri: Uri): ResultOrItems = withContext(Dispatchers.IO) {
        synchronized(lockFor(directoryUri)) {
            try {
                val target = authorizedTarget(directoryUri, requireWrite = false)
                    ?: return@synchronized ResultOrItems.Failed(failureFor(directoryUri, requireWrite = false))
                val directory = target.document
                if (!directory.isDirectory) return@synchronized ResultOrItems.Failed(
                    Result.Failure(FailureKind.INVALID_ITEM, "O item informado não é um diretório.", directoryUri)
                )
                val documentId = if (isTreeRootUri(directoryUri)) {
                    DocumentsContract.getTreeDocumentId(directoryUri)
                } else {
                    DocumentsContract.getDocumentId(directory.uri)
                }
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(target.treeUri, documentId)
                val cursor = resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                ) ?: return@synchronized ResultOrItems.Failed(
                    Result.Failure(FailureKind.UNSUPPORTED, "O provedor não retornou uma consulta para listar esta pasta.", directoryUri)
                )
                val items = cursor.use { c ->
                    val idColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                        return@synchronized ResultOrItems.Failed(
                            Result.Failure(FailureKind.UNSUPPORTED, "O provedor não retornou os metadados mínimos para listar a pasta.", directoryUri)
                        )
                    }
                    buildList {
                        while (c.moveToNext()) {
                            val documentIdValue = c.getString(idColumn)?.takeIf { it.isNotBlank() } ?: continue
                            val itemUri = DocumentsContract.buildDocumentUriUsingTree(target.treeUri, documentIdValue)
                            val name = c.getString(nameColumn).orEmpty()
                            val mime = c.getString(mimeColumn)?.takeIf { it.isNotBlank() }
                            val child = DocumentFile.fromSingleUri(context, itemUri)
                            val size = if (sizeColumn >= 0 && !c.isNull(sizeColumn)) c.getLong(sizeColumn).takeIf { it >= 0L } else null
                            val modified = if (modifiedColumn >= 0 && !c.isNull(modifiedColumn)) c.getLong(modifiedColumn).takeIf { it > 0L } else null
                            add(
                                ItemInfo(
                                    uri = itemUri,
                                    name = name,
                                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                    size = size,
                                    lastModified = modified,
                                    mimeType = mime,
                                    canRead = runCatching { child?.canRead() == true }.getOrDefault(false),
                                    canWrite = target.treeUri.let { tree ->
                                        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } &&
                                            runCatching { child?.canWrite() == true }.getOrDefault(false)
                                    },
                                )
                            )
                        }
                    }
                }
                ResultOrItems.Items(items)
            } catch (e: SecurityException) {
                ResultOrItems.Failed(Result.Failure(FailureKind.PERMISSION_DENIED, "A permissão da pasta não está mais disponível.", directoryUri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResultOrItems.Failed(Result.Failure(FailureKind.FAILURE, safeMessage(e), directoryUri))
            }
        }
    }

    suspend fun info(uri: Uri): Result = withContext(Dispatchers.IO) {
        val document = authorizedDocument(uri, requireWrite = false) ?: return@withContext failureFor(uri, requireWrite = false)
        try {
            Result.Success(
                uri = document.uri,
                name = document.name.orEmpty(),
                isDirectory = document.isDirectory,
                size = runCatching { document.length() }.getOrNull()?.takeIf { it >= 0L },
                lastModified = runCatching { document.lastModified() }.getOrNull()?.takeIf { it > 0L },
                mimeType = runCatching { document.type }.getOrNull(),
                canRead = document.canRead(),
                canWrite = document.canWrite(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: SecurityException) {
            failureFor(uri, requireWrite = false)
        } catch (e: Exception) {
            Result.Failure(FailureKind.FAILURE, safeMessage(e), uri)
        }
    }

    suspend fun createDirectory(parentUri: Uri, name: String): Result = withContext(Dispatchers.IO) {
        synchronized(lockFor(parentUri)) {
            val safeName = validateName(name) ?: return@synchronized Result.Failure(FailureKind.INVALID_NAME, "Nome de pasta inválido.", parentUri)
            var mutationAttempted = false
            try {
                val parent = authorizedDocument(parentUri, requireWrite = true) ?: return@synchronized failureFor(parentUri, requireWrite = true)
                if (!parent.isDirectory || !parent.canWrite()) return@synchronized Result.Failure(FailureKind.PERMISSION_DENIED, "A pasta de destino não permite escrita.", parentUri)
                val existing = findChildByName(targetTreeUri(parentUri), parent.uri, safeName)
                if (existing != null) return@synchronized Result.Failure(FailureKind.CONFLICT, "Já existe um item com esse nome.", existing, parentUri)
                mutationAttempted = true
                val createdUri = try {
                    DocumentsContract.createDocument(
                        resolver,
                        parent.uri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        safeName,
                    )
                } catch (_: UnsupportedOperationException) {
                    return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece suporte à criação de pastas.", parentUri)
                } ?: return@synchronized Result.Failure(
                    FailureKind.PARTIAL,
                    "O provedor não retornou uma URI após a tentativa de criação. Verifique a pasta antes de repetir.",
                    parentUri,
                    recoverable = true,
                )
                val confirmedUri = findChildByName(targetTreeUri(parentUri), parent.uri, safeName)
                val confirmed = confirmedUri?.let { DocumentFile.fromSingleUri(context, it) }
                if (confirmedUri == null || confirmed == null || !confirmed.exists() || !confirmed.isDirectory || confirmed.name != safeName) {
                    return@synchronized Result.Failure(FailureKind.PARTIAL, "A criação pode ter ocorrido, mas o provedor não confirmou a pasta ao consultá-la novamente.", createdUri, parentUri, recoverable = true)
                }
                Result.Success(confirmedUri, confirmed.name.orEmpty(), true)
            } catch (e: SecurityException) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A criação pode ter ocorrido antes da falha de autorização posterior. Verifique a pasta antes de repetir.", parentUri, recoverable = true)
                else Result.Failure(FailureKind.PERMISSION_DENIED, "Sem permissão para criar a pasta.", parentUri)
            } catch (e: UnsupportedOperationException) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "O provedor falhou após a tentativa de criação; confirme a pasta antes de repetir.", parentUri, recoverable = true)
                else Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece suporte à criação de pastas.", parentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A criação pode ter ocorrido, mas a confirmação falhou. Verifique a pasta antes de repetir.", parentUri, recoverable = true)
                else Result.Failure(FailureKind.FAILURE, safeMessage(e), parentUri)
            }
        }
    }

    suspend fun rename(uri: Uri, newName: String): Result = withContext(Dispatchers.IO) {
        synchronized(lockFor(uri)) {
            val safeName = validateName(newName) ?: return@synchronized Result.Failure(FailureKind.INVALID_NAME, "Nome inválido.", uri)
            if (isTreeRootUri(uri)) return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "A raiz SAF selecionada não pode ser renomeada.", uri)
            var mutationAttempted = false
            try {
                val target = authorizedTarget(uri, requireWrite = true) ?: return@synchronized failureFor(uri, requireWrite = true)
                val source = target.document
                if (!source.canWrite()) return@synchronized Result.Failure(FailureKind.PERMISSION_DENIED, "O item não permite renomeação.", uri)
                val parent = findParentInTree(target.treeUri, source.uri)
                    ?: return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor não permitiu localizar a pasta pai autorizada deste item.", uri)
                val conflictUri = findChildByName(target.treeUri, parent.uri, safeName)
                if (conflictUri != null && !sameDocument(conflictUri, source.uri)) return@synchronized Result.Failure(FailureKind.CONFLICT, "Já existe um item com esse nome.", uri, conflictUri)
                mutationAttempted = true
                val renamedUri = try {
                    DocumentsContract.renameDocument(resolver, source.uri, safeName)
                } catch (_: UnsupportedOperationException) {
                    return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece suporte à renomeação.", uri)
                } ?: return@synchronized Result.Failure(
                        FailureKind.PARTIAL,
                        "O provedor não retornou uma URI após a tentativa de renomeação. Verifique a pasta antes de repetir.",
                        uri,
                        recoverable = true,
                    )
                val confirmedUri = findChildByName(target.treeUri, parent.uri, safeName)
                    ?: return@synchronized Result.Failure(FailureKind.PARTIAL, "A renomeação pode ter ocorrido, mas o novo item não pôde ser confirmado. Verifique a pasta antes de tentar novamente.", uri, recoverable = true)
                val renamedDocument = DocumentFile.fromSingleUri(context, confirmedUri)
                val returnedDocument = DocumentFile.fromSingleUri(context, renamedUri)
                if (renamedDocument == null || returnedDocument == null ||
                    !renamedDocument.exists() || !returnedDocument.exists() ||
                    renamedDocument.name != safeName || returnedDocument.name != safeName ||
                    !sameDocument(confirmedUri, renamedUri)) {
                    return@synchronized Result.Failure(FailureKind.PARTIAL, "O provedor retornou uma URI de renomeação que não corresponde ao item encontrado na pasta.", uri, confirmedUri, recoverable = true)
                }
                Result.Success(
                    confirmedUri,
                    renamedDocument.name.orEmpty(),
                    renamedDocument.isDirectory,
                    catalogReconciliationQueued = false,
                )
            } catch (e: SecurityException) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A renomeação pode ter ocorrido antes da falha de confirmação. Verifique a pasta antes de repetir.", uri, recoverable = true)
                else Result.Failure(FailureKind.PERMISSION_DENIED, "A permissão foi negada durante a renomeação.", uri)
            } catch (e: UnsupportedOperationException) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "O provedor falhou após a tentativa de renomeação; verifique o nome atual antes de repetir.", uri, recoverable = true)
                else Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece suporte à renomeação.", uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A renomeação pode ter ocorrido, mas a confirmação falhou. Verifique a pasta antes de repetir.", uri, recoverable = true)
                else Result.Failure(FailureKind.FAILURE, safeMessage(e), uri)
            }
        }
    }

    suspend fun move(uri: Uri, destinationDirectoryUri: Uri): Result = withContext(Dispatchers.IO) {
        val sourceTarget = authorizedTarget(uri, requireWrite = true)
            ?: return@withContext failureFor(uri, requireWrite = true)
        val destinationTarget = authorizedTarget(destinationDirectoryUri, requireWrite = true)
            ?: return@withContext failureFor(destinationDirectoryUri, requireWrite = true)
        val source = sourceTarget.document
        val destination = destinationTarget.document
        if (isTreeRootUri(uri)) return@withContext Result.Failure(FailureKind.UNSUPPORTED, "A raiz SAF selecionada não pode ser movida.", uri)
        val lockKeys = listOf(sourceTarget.treeUri.toString(), destinationTarget.treeUri.toString()).distinct().sorted()
        withLocks(lockKeys) {
            var mutationAttempted = false
            try {
                // Revalidate grants after acquiring the operation locks.
                val currentSource = authorizedTarget(uri, requireWrite = true)
                    ?: return@withLocks failureFor(uri, requireWrite = true)
                val currentDestination = authorizedTarget(destinationDirectoryUri, requireWrite = true)
                    ?: return@withLocks failureFor(destinationDirectoryUri, requireWrite = true)
                val sourceDocument = currentSource.document
                val destinationDocument = currentDestination.document
            if (!destinationDocument.isDirectory) return@withLocks Result.Failure(FailureKind.INVALID_ITEM, "O destino não é um diretório.", uri, destinationDirectoryUri)
            if (!destinationDocument.canWrite() || !sourceDocument.canWrite()) return@withLocks Result.Failure(FailureKind.PERMISSION_DENIED, "A origem ou o destino não permite escrita.", uri, destinationDirectoryUri)
            if (sameDocument(sourceDocument.uri, destinationDocument.uri)) return@withLocks Result.Failure(FailureKind.INVALID_ITEM, "A origem e o destino são iguais.", uri, destinationDirectoryUri)
            if (sourceDocument.isDirectory) {
                val destinationIsDescendant = try {
                    DocumentsContract.isChildDocument(resolver, sourceDocument.uri, destinationDocument.uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    return@withLocks Result.Failure(FailureKind.PERMISSION_DENIED, "Não foi possível verificar o escopo da pasta de destino.", uri, destinationDirectoryUri)
                } catch (_: Exception) {
                    return@withLocks Result.Failure(
                        FailureKind.UNSUPPORTED,
                        "O provedor não permite confirmar se o destino está dentro da pasta de origem; a movimentação foi recusada por segurança.",
                        uri,
                        destinationDirectoryUri,
                        recoverable = true,
                    )
                }
                if (destinationIsDescendant) {
                    return@withLocks Result.Failure(FailureKind.INVALID_ITEM, "Uma pasta não pode ser movida para dentro de si mesma ou de seus descendentes.", uri, destinationDirectoryUri)
                }
            }
            val name = sourceDocument.name?.takeIf(String::isNotBlank)
                ?: return@withLocks Result.Failure(FailureKind.FAILURE, "O provedor não informou o nome da origem.", uri, destinationDirectoryUri)
                if (findChildByName(currentDestination.treeUri, destinationDocument.uri, name) != null) {
                    return@withLocks Result.Failure(FailureKind.CONFLICT, "O destino já contém um item com esse nome.", uri, destinationDirectoryUri)
                }
                val sourceParent = findParentInTree(currentSource.treeUri, sourceDocument.uri)
                    ?: return@withLocks Result.Failure(FailureKind.UNSUPPORTED, "Não foi possível confirmar a pasta de origem autorizada.", uri, destinationDirectoryUri)
                if (sourceDocument.uri.authority != destinationDocument.uri.authority ||
                    !DocumentsContract.isDocumentUri(context, sourceDocument.uri) ||
                    !DocumentsContract.isDocumentUri(context, destinationDocument.uri)) {
                    return@withLocks Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece movimentação confirmável entre estas fontes; a origem foi preservada.", uri, destinationDirectoryUri)
                }
                mutationAttempted = true
                val movedUri = try {
                    DocumentsContract.moveDocument(
                        resolver,
                        sourceDocument.uri,
                        sourceParent.uri,
                        destinationDocument.uri,
                    )
                } catch (_: UnsupportedOperationException) {
                    return@withLocks Result.Failure(
                        FailureKind.UNSUPPORTED,
                        "O provedor não oferece suporte à movimentação. Verifique a origem antes de tentar outra estratégia.",
                        uri,
                        destinationDirectoryUri,
                    )
                } ?: return@withLocks Result.Failure(
                    FailureKind.PARTIAL,
                    "O provedor não retornou uma URI após a tentativa de movimentação. Verifique a origem e o destino antes de repetir.",
                    uri,
                    destinationDirectoryUri,
                    recoverable = true,
                )
                val moved = DocumentFile.fromSingleUri(context, movedUri)
                val locatedAtDestination = findChildByName(currentDestination.treeUri, destinationDocument.uri, name)
                if (moved != null && moved.exists() && moved.name == name &&
                    locatedAtDestination != null && sameDocument(locatedAtDestination, moved.uri)) {
                    Result.Success(moved.uri, name, moved.isDirectory)
                } else {
                    Result.Failure(FailureKind.PARTIAL, "O provedor aceitou a movimentação, mas não confirmou o documento no destino. Verifique ambas as pastas antes de repetir.", uri, destinationDirectoryUri, recoverable = true)
                }
            } catch (e: SecurityException) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A movimentação pode ter ocorrido antes da falha de confirmação. Verifique a origem e o destino antes de repetir.", uri, destinationDirectoryUri, recoverable = true)
                else Result.Failure(FailureKind.PERMISSION_DENIED, "A permissão foi negada durante a movimentação.", uri, destinationDirectoryUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A movimentação pode ter ocorrido, mas a confirmação falhou. Verifique a origem e o destino antes de repetir.", uri, destinationDirectoryUri, recoverable = true)
                else Result.Failure(FailureKind.FAILURE, safeMessage(e), uri, destinationDirectoryUri)
            }
        }
    }

    suspend fun delete(uri: Uri): Result = withContext(Dispatchers.IO) {
        synchronized(lockFor(uri)) {
            var mutationAttempted = false
            try {
                if (isTreeRootUri(uri)) return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "A raiz SAF selecionada não pode ser excluída.", uri)
                val target = authorizedTarget(uri, requireWrite = true) ?: return@synchronized failureFor(uri, requireWrite = true)
                val source = target.document
                if (!source.canWrite()) return@synchronized Result.Failure(FailureKind.PERMISSION_DENIED, "O item não permite exclusão.", uri)
                mutationAttempted = true
                val deleteReportedSuccess = try {
                    source.delete()
                } catch (_: UnsupportedOperationException) {
                    return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece suporte à exclusão.", uri)
                }
                val afterDelete = try {
                    DocumentFile.fromSingleUri(context, uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (afterDelete == null) {
                    Result.Failure(FailureKind.PARTIAL, "O provedor não permitiu verificar o estado após a exclusão; preserve o registro até reconciliar.", uri, recoverable = true)
                } else if (afterDelete.exists()) {
                    if (!deleteReportedSuccess) {
                        Result.Failure(FailureKind.UNSUPPORTED, "O provedor recusou a exclusão e o item continua presente.", uri)
                    } else {
                        Result.Failure(FailureKind.PARTIAL, "O provedor não confirmou a exclusão; o registro da biblioteca deve ser preservado.", uri, recoverable = true)
                    }
                } else {
                    Result.Success(uri, source.name.orEmpty(), source.isDirectory)
                }
            } catch (e: SecurityException) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A exclusão pode ter ocorrido antes da falha de confirmação. Preserve o registro e verifique o item antes de repetir.", uri, recoverable = true)
                else Result.Failure(FailureKind.PERMISSION_DENIED, "A permissão foi negada durante a exclusão.", uri)
            } catch (e: UnsupportedOperationException) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "O provedor falhou após a tentativa de exclusão. Verifique o item antes de repetir.", uri, recoverable = true)
                else Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece suporte à exclusão.", uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mutationAttempted) Result.Failure(FailureKind.PARTIAL, "A exclusão pode ter ocorrido, mas a confirmação falhou. Preserve o registro até verificar o item.", uri, recoverable = true)
                else Result.Failure(FailureKind.FAILURE, safeMessage(e), uri)
            }
        }
    }

    /** Persisted tree grants offered by the user; roots remain content URIs. */
    fun authorizedTreeUris(): List<Uri> =
        resolver.persistedUriPermissions.asSequence()
            .filter { it.isReadPermission }
            .map { it.uri }
            .filter { uri -> runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false) }
            .distinct()
            .toList()

    fun treeDisplayName(treeUri: Uri): String =
        SafScanner.displayName(context, treeUri)

    sealed interface ResultOrItems {
        data class Items(val value: List<ItemInfo>) : ResultOrItems
        data class Failed(val result: Result.Failure) : ResultOrItems
    }

    private data class AuthorizedTarget(val treeUri: Uri, val document: DocumentFile)

    private fun authorizedDocument(uri: Uri, requireWrite: Boolean): DocumentFile? =
        authorizedTarget(uri, requireWrite)?.document

    private fun authorizedTarget(uri: Uri, requireWrite: Boolean): AuthorizedTarget? {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return null
        val hasTreeScope = runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
        val isDocument = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)
        val isTreeRoot = hasTreeScope && !isDocument
        if (!isTreeRoot && !isDocument) return null

        val grants = resolver.persistedUriPermissions.filter { grant ->
            runCatching { DocumentsContract.isTreeUri(grant.uri) }.getOrDefault(false)
        }
        for (grant in grants) {
            val treeMatches = if (isTreeRoot) {
                grant.uri == uri
            } else {
                grant.uri.authority.equals(uri.authority, ignoreCase = true)
            }
            val withinTree = if (isTreeRoot) {
                grant.uri == uri
            } else {
                runCatching { SafScanner.isAuthorizedDocumentForTree(context, grant.uri, uri) }.getOrDefault(false)
            }
            if (!grantAllows(grant.isReadPermission, grant.isWritePermission, requireWrite, treeMatches, withinTree)) continue
            val candidateUri = if (isTreeRoot) {
                runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                }.getOrNull() ?: continue
            } else {
                runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(grant.uri, DocumentsContract.getDocumentId(uri))
                }.getOrNull() ?: continue
            }
            val document = if (isTreeRoot) {
                DocumentFile.fromTreeUri(context, uri)
            } else {
                DocumentFile.fromSingleUri(context, candidateUri)
            } ?: continue
            val usable = try {
                document.exists() && document.canRead() && (!requireWrite || document.canWrite())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                false
            } catch (_: Exception) {
                false
            }
            if (!usable) continue
            return AuthorizedTarget(grant.uri, document)
        }
        return null
    }

    private fun failureFor(uri: Uri, requireWrite: Boolean): Result.Failure {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            return Result.Failure(FailureKind.UNSUPPORTED, "Somente URIs SAF content:// válidas são aceitas.", uri)
        }
        val readTarget = authorizedTarget(uri, requireWrite = false)
        if (readTarget == null) {
            val withinAnyGrantedTree = resolver.persistedUriPermissions.any { grant ->
                grant.isReadPermission &&
                    runCatching { DocumentsContract.isTreeUri(grant.uri) }.getOrDefault(false) &&
                    grant.uri.authority.equals(uri.authority, ignoreCase = true) &&
                    runCatching { SafScanner.isAuthorizedDocumentForTree(context, grant.uri, uri) }.getOrDefault(false)
            }
            return if (withinAnyGrantedTree) {
                Result.Failure(FailureKind.NOT_FOUND, "O documento não existe ou o provedor não consegue mais acessá-lo.", uri)
            } else {
                Result.Failure(FailureKind.PERMISSION_DENIED, "A concessão SAF foi revogada ou a URI não pertence à árvore autorizada.", uri)
            }
        }
        if (requireWrite && authorizedTarget(uri, requireWrite = true) == null) {
            return Result.Failure(FailureKind.PERMISSION_DENIED, "A concessão SAF atual não autoriza escrita nesta árvore/documento.", uri)
        }
        return Result.Failure(FailureKind.FAILURE, "O provedor não disponibilizou o documento para a operação.", uri)
    }

    private fun resolveAfterMutation(oldUri: Uri, parent: DocumentFile, name: String): DocumentFile? {
        val old = DocumentFile.fromSingleUri(context, oldUri)
        if (old?.exists() == true && old.name == name) return old
        return runCatching { parent.findFile(name) }.getOrNull()?.takeIf { it.exists() && it.name == name }
    }

    private fun targetTreeUri(uri: Uri): Uri =
        resolver.persistedUriPermissions.firstOrNull { grant ->
            grant.isReadPermission &&
                runCatching { DocumentsContract.isTreeUri(grant.uri) }.getOrDefault(false) &&
                (isTreeRootUri(uri) && grant.uri == uri ||
                    runCatching { SafScanner.isAuthorizedDocumentForTree(context, grant.uri, uri) }.getOrDefault(false))
        }?.uri ?: throw SecurityException("A URI não pertence a uma árvore SAF autorizada.")

    private fun findChildByName(treeUri: Uri, parentUri: Uri, name: String): Uri? {
        val parentId = if (isTreeRootUri(parentUri)) {
            DocumentsContract.getTreeDocumentId(parentUri)
        } else {
            DocumentsContract.getDocumentId(parentUri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: throw IOException("O provedor não retornou a consulta de itens da pasta.")
        return cursor.use { c ->
            val idColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idColumn < 0 || nameColumn < 0) throw IOException("O provedor não retornou ID e nome dos documentos.")
            var found: Uri? = null
            while (c.moveToNext()) {
                if (c.getString(nameColumn) == name) {
                    val id = c.getString(idColumn)?.takeIf { it.isNotBlank() } ?: continue
                    found = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    break
                }
            }
            found
        }
    }

    private fun findParentInTree(treeUri: Uri, targetUri: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (sameDocument(root.uri, targetUri)) return null
        val pending = ArrayDeque<DocumentFile>()
        val visited = HashSet<String>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            if (!visited.add(parent.uri.toString())) continue
            val children = try {
                parent.listFiles()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return null
            } catch (_: Exception) {
                return null
            }
            for (child in children) {
                if (sameDocument(child.uri, targetUri)) return parent
                if (child.isDirectory) pending.add(child)
            }
        }
        return null
    }

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first.authority != second.authority) return false
        val firstId = runCatching { DocumentsContract.getDocumentId(first) }.getOrNull()
        val secondId = runCatching { DocumentsContract.getDocumentId(second) }.getOrNull()
        return firstId != null && firstId == secondId
    }

    private fun validateName(value: String): String? {
        val name = value.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) return null
        return name.takeIf { it.length <= 255 }
    }

    internal fun grantAllows(
        grantRead: Boolean,
        grantWrite: Boolean,
        requireWrite: Boolean,
        treeMatches: Boolean,
        withinTree: Boolean,
    ): Boolean = grantRead && (!requireWrite || grantWrite) && treeMatches && withinTree

    private fun isTreeRootUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri) }
            .getOrDefault(false)

    private fun lockFor(uri: Uri): Any {
        val treeKey = if (isTreeRootUri(uri)) {
            uri.toString()
        } else {
            resolver.persistedUriPermissions.firstOrNull { grant ->
                grant.isReadPermission &&
                    runCatching { DocumentsContract.isTreeUri(grant.uri) }.getOrDefault(false) &&
                    runCatching { SafScanner.isAuthorizedDocumentForTree(context, grant.uri, uri) }.getOrDefault(false)
            }?.uri?.toString() ?: uri.toString()
        }
        return locks.getOrPut(treeKey) { Any() }
    }

    private fun <T> withLocks(keys: Collection<String>, block: () -> T): T {
        val ordered = keys.distinct().sorted()
        fun acquire(index: Int): T =
            if (index >= ordered.size) block()
            else synchronized(locks.getOrPut(ordered[index]) { Any() }) { acquire(index + 1) }
        return acquire(0)
    }

    private fun safeMessage(error: Exception): String =
        when (error) {
            is CancellationException -> "Operação cancelada."
            is SecurityException -> "O provedor negou o acesso solicitado."
            is IOException -> "Falha de leitura ou escrita no provedor."
            else -> "Falha operacional do provedor."
        }
}
