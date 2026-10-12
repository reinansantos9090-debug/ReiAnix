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
            val directory = authorizedDocument(directoryUri, requireWrite = false)
                ?: return@synchronized ResultOrItems.Failed(failureFor(directoryUri, requireWrite = false))
            if (!directory.isDirectory) return@synchronized ResultOrItems.Failed(
                Result.Failure(FailureKind.INVALID_ITEM, "O item informado não é um diretório.", directoryUri)
            )
            try {
                val children = directory.listFiles().map { child ->
                    ItemInfo(
                        uri = child.uri,
                        name = child.name.orEmpty(),
                        isDirectory = child.isDirectory,
                        size = child.length().takeIf { it >= 0L },
                        lastModified = child.lastModified().takeIf { it > 0L },
                        mimeType = child.type,
                        canRead = child.canRead(),
                        canWrite = child.canWrite(),
                    )
                }
                ResultOrItems.Items(children)
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
            val parent = authorizedDocument(parentUri, requireWrite = true) ?: return@synchronized failureFor(parentUri, requireWrite = true)
            if (!parent.isDirectory || !parent.canWrite()) return@synchronized Result.Failure(FailureKind.PERMISSION_DENIED, "A pasta de destino não permite escrita.", parentUri)
            try {
                val existing = parent.findFile(safeName)
                if (existing != null) return@synchronized Result.Failure(FailureKind.CONFLICT, "Já existe um item com esse nome.", existing.uri, parentUri)
                val created = parent.createDirectory(safeName)
                    ?: return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor não conseguiu criar a pasta.", parentUri)
                val confirmed = parent.findFile(safeName)
                if (confirmed == null || !confirmed.exists() || !confirmed.isDirectory || confirmed.name != safeName) {
                    return@synchronized Result.Failure(FailureKind.PARTIAL, "A criação pode ter ocorrido, mas o provedor não confirmou a pasta ao consultá-la novamente.", created.uri, parentUri, recoverable = true)
                }
                Result.Success(confirmed.uri, confirmed.name.orEmpty(), true)
            } catch (e: SecurityException) {
                Result.Failure(FailureKind.PERMISSION_DENIED, "Sem permissão para criar a pasta.", parentUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failure(FailureKind.FAILURE, safeMessage(e), parentUri)
            }
        }
    }

    suspend fun rename(uri: Uri, newName: String): Result = withContext(Dispatchers.IO) {
        synchronized(lockFor(uri)) {
            val safeName = validateName(newName) ?: return@synchronized Result.Failure(FailureKind.INVALID_NAME, "Nome inválido.", uri)
            if (isTreeRootUri(uri)) return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "A raiz SAF selecionada não pode ser renomeada.", uri)
            val target = authorizedTarget(uri, requireWrite = true) ?: return@synchronized failureFor(uri, requireWrite = true)
            val source = target.document
            if (!source.canWrite()) return@synchronized Result.Failure(FailureKind.PERMISSION_DENIED, "O item não permite renomeação.", uri)
            val parent = findParentInTree(target.treeUri, source.uri)
                ?: return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor não permitiu localizar a pasta pai autorizada deste item.", uri)
            val conflict = runCatching { parent.findFile(safeName) }.getOrNull()
            if (conflict != null && !sameDocument(conflict.uri, source.uri)) return@synchronized Result.Failure(FailureKind.CONFLICT, "Já existe um item com esse nome.", uri, conflict.uri)
            try {
                if (!source.renameTo(safeName)) return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor recusou a renomeação.", uri)
                val renamed = resolveAfterMutation(source.uri, parent, safeName)
                    ?: return@synchronized Result.Failure(FailureKind.PARTIAL, "A renomeação pode ter ocorrido, mas o novo item não pôde ser confirmado. Verifique a pasta antes de tentar novamente.", uri, recoverable = true)
                Result.Success(renamed.uri, renamed.name.orEmpty(), renamed.isDirectory)
            } catch (e: SecurityException) {
                Result.Failure(FailureKind.PERMISSION_DENIED, "A permissão foi negada durante a renomeação.", uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failure(FailureKind.FAILURE, safeMessage(e), uri)
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
            if (sourceDocument.isDirectory && (sameDocument(sourceDocument.uri, destinationDocument.uri) ||
                runCatching { DocumentsContract.isChildDocument(resolver, sourceDocument.uri, destinationDocument.uri) }.getOrDefault(false))) {
                return@withLocks Result.Failure(FailureKind.INVALID_ITEM, "Uma pasta não pode ser movida para dentro de si mesma ou de seus descendentes.", uri, destinationDirectoryUri)
            }
            val name = sourceDocument.name?.takeIf(String::isNotBlank)
                ?: return@withLocks Result.Failure(FailureKind.FAILURE, "O provedor não informou o nome da origem.", uri, destinationDirectoryUri)
            if (runCatching { destinationDocument.findFile(name) }.getOrNull() != null) {
                return@withLocks Result.Failure(FailureKind.CONFLICT, "O destino já contém um item com esse nome.", uri, destinationDirectoryUri)
            }
            try {
                val sourceParent = findParentInTree(currentSource.treeUri, sourceDocument.uri)
                    ?: return@withLocks Result.Failure(FailureKind.UNSUPPORTED, "Não foi possível confirmar a pasta de origem autorizada.", uri, destinationDirectoryUri)
                if (sourceDocument.uri.authority != destinationDocument.uri.authority ||
                    !DocumentsContract.isDocumentUri(context, sourceDocument.uri) ||
                    !DocumentsContract.isDocumentUri(context, destinationDocument.uri)) {
                    return@withLocks Result.Failure(FailureKind.UNSUPPORTED, "O provedor não oferece movimentação confirmável entre estas fontes; a origem foi preservada.", uri, destinationDirectoryUri)
                }
                val movedUri = try {
                    DocumentsContract.moveDocument(resolver, sourceDocument.uri, sourceParent.uri, destinationDocument.uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: UnsupportedOperationException) {
                    null
                }
                if (movedUri == null) return@withLocks Result.Failure(FailureKind.UNSUPPORTED, "O provedor não confirmou suporte à movimentação. A origem foi preservada.", uri, destinationDirectoryUri)
                val moved = DocumentFile.fromSingleUri(context, movedUri)
                val locatedAtDestination = runCatching { destinationDocument.findFile(name) }.getOrNull()
                if (moved != null && moved.exists() && moved.name == name &&
                    locatedAtDestination != null && sameDocument(locatedAtDestination.uri, moved.uri)) {
                    Result.Success(moved.uri, name, moved.isDirectory)
                } else {
                    Result.Failure(FailureKind.PARTIAL, "O provedor aceitou a movimentação, mas não confirmou o documento no destino. Verifique ambas as pastas antes de repetir.", uri, destinationDirectoryUri, recoverable = true)
                }
            } catch (e: SecurityException) {
                Result.Failure(FailureKind.PERMISSION_DENIED, "A permissão foi negada durante a movimentação.", uri, destinationDirectoryUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failure(FailureKind.FAILURE, safeMessage(e), uri, destinationDirectoryUri)
            }
        }
    }

    suspend fun delete(uri: Uri): Result = withContext(Dispatchers.IO) {
        synchronized(lockFor(uri)) {
            if (isTreeRootUri(uri)) return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "A raiz SAF selecionada não pode ser excluída.", uri)
            val target = authorizedTarget(uri, requireWrite = true) ?: return@synchronized failureFor(uri, requireWrite = true)
            val source = target.document
            try {
                if (!source.canWrite()) return@synchronized Result.Failure(FailureKind.PERMISSION_DENIED, "O item não permite exclusão.", uri)
                if (!source.delete()) return@synchronized Result.Failure(FailureKind.UNSUPPORTED, "O provedor recusou a exclusão.", uri)
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
                    Result.Failure(FailureKind.PARTIAL, "O provedor não confirmou a exclusão; o registro da biblioteca deve ser preservado.", uri, recoverable = true)
                } else {
                    Result.Success(uri, source.name.orEmpty(), source.isDirectory)
                }
            } catch (e: SecurityException) {
                Result.Failure(FailureKind.PERMISSION_DENIED, "A permissão foi negada durante a exclusão.", uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failure(FailureKind.FAILURE, safeMessage(e), uri)
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
            else -> error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Falha operacional do provedor."
        }
}
