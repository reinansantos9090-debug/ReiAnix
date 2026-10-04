package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.storage.NativeBatch
import com.reiflix.reiflix_local.storage.NativeIndex
import com.reiflix.reiflix_local.storage.StorageAuthorization
import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.HashSet

object BroadStorageScanner {
    private const val TAG = "[REIFLIX][SCANNER]"
    const val SOURCE = "broad-storage"
    const val DISPLAY_NAME = "Armazenamento local"
    // Keep the extension fallback close to Nova's local video intent/indexing
    // surface, while still allowing MediaStore/SAF MIME types to win when present.
    private val videoExtensions = setOf(
        "3g2","3gp","3gp2","3gpp","asf","avi","divx","flv","f4v","qt","m4v",
        "mtv","mkv","mp4","mpeg","mpe","mpg","mov","ogm","ogv","ogx","vob","wtv",
        "webm","ts","m2ts","wmv"
    )

    /** A physical shared-storage root, not merely a path supplied by one scanner. */
    data class StorageRoot(
        val file: File,
        val volumeId: String,
        val volumeUuid: String?,
        val primary: Boolean,
        val removable: Boolean,
        val emulated: Boolean,
        val state: String,
    )
    /**
     * Broad traversal is available only when Android itself reports the special
     * MANAGE_EXTERNAL_STORAGE grant. Settings callbacks, intents and Python
     * state are deliberately not used as evidence of authorization.
     */
    fun accessLevel(context: Context): BroadStorageAccessLevel {
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        return StorageAuthorization.broadAccess(granted)
    }

    fun hasAccess(context: Context): Boolean =
        accessLevel(context) == BroadStorageAccessLevel.AVAILABLE

    /** Compact runtime diagnostics used to explain why local storage is or is not visible. */
    fun accessSnapshot(context: Context): JSONObject {
        val result = JSONObject()
            .put("api", Build.VERSION.SDK_INT)
            .put("hasAccess", hasAccess(context))
            .put("permissionAuthority", "Environment.isExternalStorageManager")
            .put("permissionAuthoritative", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            .put("permissionCheckedAt", System.currentTimeMillis())
        val rootsJson = JSONArray()
        roots(context).forEach { root ->
            val pathAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Environment.isExternalStorageManager(root.file) }.getOrDefault(false)
            } else {
                hasAccess(context)
            }
            val check = JSONObject().put("path", root.file.path)
                .put("volumeId", root.volumeId).put("uuid", root.volumeUuid ?: "")
                .put("primary", root.primary).put("removable", root.removable).put("emulated", root.emulated)
                .put("state", root.state).put("exists", root.file.exists())
                .put("directory", root.file.isDirectory).put("readable", root.file.canRead())
                .put("allFilesAccessForPath", pathAccess)
            if (root.file.exists() && root.file.isDirectory) {
                check.put("children", runCatching { root.file.list()?.size ?: 0 }.getOrDefault(-1))
            }
            rootsJson.put(check)
        }
        result.put("roots", rootsJson)
        val volumes = JSONArray()
        if (Build.VERSION.SDK_INT >= 24) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes?.forEach { volume ->
                val item = JSONObject()
                    .put("volumeId", if (Build.VERSION.SDK_INT >= 30) (volume.mediaStoreVolumeName ?: volume.uuid ?: "") else (volume.uuid ?: ""))
                    .put("uuid", volume.uuid ?: "")
                    .put("primary", volume.isPrimary)
                    .put("removable", volume.isRemovable)
                    .put("emulated", volume.isEmulated)
                    .put("state", volume.state ?: "unknown")
                if (Build.VERSION.SDK_INT >= 30) {
                    volume.mediaStoreVolumeName?.let { item.put("mediaStoreVolumeName", it) }
                    runCatching { volume.directory?.canonicalPath }.getOrNull()?.let { item.put("directory", it) }
                }
                volumes.put(item)
            }
        }
        result.put("volumes", volumes)
        return result
    }

    fun roots(context: Context): List<StorageRoot> {
        val found = LinkedHashMap<String, StorageRoot>()

        fun addRoot(root: StorageRoot) {
            val canonical = runCatching { root.file.canonicalFile }.getOrNull() ?: root.file
            if (canonical.exists() && canonical.isDirectory) {
                found[canonical.path] = root.copy(file = canonical)
            }
        }

        val primary = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
        if (primary != null) {
            addRoot(
                StorageRoot(
                    primary,
                    "external_primary",
                    null,
                    primary = true,
                    removable = false,
                    emulated = true,
                    state = Environment.getExternalStorageState(primary),
                )
            )
        }

        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes?.forEach { volume ->
                val directory = runCatching { volume.directory }.getOrNull() ?: return@forEach
                val volumeId = runCatching { volume.mediaStoreVolumeName }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: volume.uuid?.takeIf { it.isNotBlank() }
                    ?: if (volume.isPrimary) "external_primary" else "path:" + runCatching { directory.canonicalPath }.getOrDefault(directory.path)
                addRoot(
                    StorageRoot(
                        directory,
                        volumeId,
                        volume.uuid,
                        volume.isPrimary,
                        volume.isRemovable,
                        volume.isEmulated,
                        volume.state ?: Environment.getExternalStorageState(directory),
                    )
                )
            }
        } else if (Build.VERSION.SDK_INT >= 19) {
            // StorageVolume.directory is only public from API 30. On API 19-29,
            // getExternalFilesDirs() is the supported way to enumerate every
            // shared/external volume, including a mounted SD card. Derive the
            // volume root from the app-specific path without persisting that
            // absolute path as the volume identity.
            context.getExternalFilesDirs(null).forEach { appDirectory ->
                val volumeRoot = inferVolumeRoot(context, appDirectory) ?: return@forEach
                val primaryVolume = runCatching {
                    volumeRoot.canonicalFile == primary?.canonicalFile
                }.getOrDefault(false)
                val removable = runCatching { Environment.isExternalStorageRemovable(volumeRoot) }.getOrDefault(false)
                val emulated = runCatching { Environment.isExternalStorageEmulated(volumeRoot) }.getOrDefault(primaryVolume)
                val volumeId = if (primaryVolume) {
                    "external_primary"
                } else {
                    "removable:" + volumeRoot.name
                }
                addRoot(
                    StorageRoot(
                        volumeRoot,
                        volumeId,
                        volumeRoot.name.takeIf { removable },
                        primaryVolume,
                        removable,
                        emulated,
                        Environment.getExternalStorageState(volumeRoot),
                    )
                )
            }
        }
        return found.values.toList()
    }

    private fun inferVolumeRoot(context: Context, appDirectory: File?): File? {
        val directory = appDirectory ?: return null
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val marker = File.separator + "Android" + File.separator + "data" +
            File.separator + context.packageName + File.separator + "files"
        val path = canonical.path
        val index = path.lastIndexOf(marker)
        if (index <= 0) return null
        return runCatching { File(path.substring(0, index)).canonicalFile }.getOrNull()
    }

    fun isAuthorizedFile(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file" || !hasAccess(context)) return false
        val file = runCatching { File(uri.path ?: "").canonicalFile }.getOrNull() ?: return false
        return roots(context).any { isReadableState(it.state) && isInside(file, it.file) } && !isRestricted(file)
    }

    private fun isInside(file: File, root: File): Boolean =
        file.path == root.path || file.path.startsWith(root.path + File.separator)

    private fun isRestricted(file: File): Boolean {
        val parts = file.path.split(File.separator).filter(String::isNotEmpty)
        val i = parts.indexOfLast { it.equals("Android", true) }
        val child = if (i >= 0) parts.getOrNull(i + 1)?.lowercase() else null
        return child == "data" || child == "obb"
    }

    private fun isNoMediaDirectory(directory: File): Boolean =
        runCatching { File(directory, ".nomedia").isFile }.getOrDefault(false)

    private fun rootForFile(file: File, roots: List<StorageRoot>): StorageRoot? =
        roots.filter { isInside(file, it.file) && isReadableState(it.state) }.maxByOrNull { it.file.path.length }

    private fun relativePath(file: File, root: File): String =
        runCatching {
            val prefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
            file.canonicalPath.removePrefix(prefix).replace(File.separatorChar, '/')
        }.getOrDefault(file.name)

    private fun volumeKey(context: Context, root: File): String {
        val primary = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
        if (primary != null && primary == runCatching { root.canonicalFile }.getOrNull()) {
            return "external_primary"
        }
        if (Build.VERSION.SDK_INT >= 24) {
            val volume = if (Build.VERSION.SDK_INT >= 30) {
                context.getSystemService(StorageManager::class.java)?.storageVolumes?.firstOrNull {
                    runCatching { it.directory?.canonicalFile == root.canonicalFile }.getOrDefault(false)
                }
            } else {
                null
            }
            if (Build.VERSION.SDK_INT >= 30) {
                volume?.mediaStoreVolumeName?.takeIf { it.isNotBlank() }?.let { return it }
            }
            volume?.uuid?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return "path:" + runCatching { root.canonicalPath }.getOrDefault(root.path)
    }

    fun scan(context: Context, onProgress: ((JSONObject) -> Unit)? = null, shouldCancel: () -> Boolean = { false }, scanId: String? = null, onBatch: ((JSONObject) -> Unit)? = null, authorizedRoots: List<File> = emptyList()): JSONObject {
        val access = hasAccess(context)
        Log.i(TAG, "SCAN_STARTED: api=" + Build.VERSION.SDK_INT + ", granted=" + access)
        check(access) { "Acesso amplo ao armazenamento não foi concedido." }
        val errors = JSONArray()
        val discoveredRoots = roots(context)
        val authorizedRootFiles = authorizedRoots.mapNotNull { candidate ->
            runCatching { candidate.canonicalFile }.getOrNull()
                ?.takeIf { it.exists() && it.isDirectory && !isRestricted(it) }
        }.distinctBy { it.path }
        if (authorizedRootFiles.isEmpty()) {
            Log.w(TAG, "SCAN_SOURCE_REJECTED reason=NO_CONFIGURED_LIBRARY_SOURCE")
            onProgress?.invoke(JSONObject().put("phase", "blocked").put("source", SOURCE)
                .put("reason", "NO_CONFIGURED_LIBRARY_SOURCE"))
            return JSONObject().put("source", SOURCE).put("name", DISPLAY_NAME).put("volumeScopes", JSONArray())
                .put("stats", JSONObject().put("directories", 0).put("files", 0).put("videos", 0)
                    .put("errors", errors).put("access", access).put("status", "BLOCKED")
                    .put("rejection", "NO_CONFIGURED_LIBRARY_SOURCE"))
                .put("partial", false).put("cancelled", false)
        }
        val rootFiles = authorizedRootFiles.mapNotNull { libraryRoot ->
            val volume = rootForFile(libraryRoot, discoveredRoots) ?: return@mapNotNull null
            volume.copy(file = libraryRoot)
        }
        val errorsByVolume = LinkedHashMap<String, JSONArray>()
        val batchesByVolume = LinkedHashMap<String, NativeBatch.Accumulator>()
        val statsByVolume = LinkedHashMap<String, JSONObject>()
        val generationByVolume = LinkedHashMap<String, Long>()
        val rootByVolume = LinkedHashMap<String, StorageRoot>()
        var cancelled = false
        var directories = 0
        var files = 0
        var videos = 0
        var excludedNoMedia = 0

        rootFiles.forEach { root ->
            rootByVolume[root.volumeId] = root
            val currentGeneration = { generationByVolume[root.volumeId] ?: 0L }
            batchesByVolume[root.volumeId] = NativeBatch.Accumulator(NativeBatch.DEFAULT_SIZE) { batch, batchId, batchNumber ->
                onBatch?.invoke(
                    JSONObject()
                        .put("volumeId", root.volumeId)
                        .put("volumeUuid", root.volumeUuid ?: "")
                        .put("generation", currentGeneration())
                        .put("batchId", batchId)
                        .put("batchNumber", batchNumber)
                        .put("batchSize", batch.length())
                        .put("documents", batch)
                )
            }
            errorsByVolume[root.volumeId] = JSONArray()
            statsByVolume[root.volumeId] = JSONObject().put("directories", 0).put("files", 0).put("videos", 0)
                .put("excludedNoMedia", 0)
            generationByVolume[root.volumeId] = NativeIndex.startGeneration(
                context, SOURCE, "broad-storage:" + root.volumeId,
                JSONObject().put("volumeId", root.volumeId).put("volumeUuid", root.volumeUuid ?: "").put("scanId", scanId ?: "")
                    .put("state", root.state).put("removable", root.removable).put("primary", root.primary)
            )
            val rootAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Environment.isExternalStorageManager(root.file) }.getOrDefault(false)
            } else {
                true
            }
            statsByVolume[root.volumeId]?.put("allFilesAccessForPath", rootAccess)
            if (!isReadableState(root.state)) {
                val message = "Volume não disponível: " + root.volumeId + " (" + root.state + ")."
                val classified = JSONObject()
                    .put("type", volumeErrorType(root.state))
                    .put("message", message)
                    .put("volumeId", root.volumeId)
                    .put("state", root.state)
                errors.put(classified)
                errorsByVolume[root.volumeId]?.put(classified)
            } else if (!rootAccess) {
                val classified = JSONObject()
                    .put("type", "ACCESS_DENIED")
                    .put("message", "O Android não permite leitura ampla neste volume: " + root.volumeId)
                    .put("volumeId", root.volumeId)
                    .put("state", root.state)
                    .put("allFilesAccessForPath", false)
                errors.put(classified)
                errorsByVolume[root.volumeId]?.put(classified)
            }
        }
        if (rootFiles.isEmpty()) errors.put("Nenhuma raiz de armazenamento compartilhado foi encontrada.")

        val visited = HashSet<String>()
        val pending = ArrayDeque<Pair<File, StorageRoot>>()
        rootFiles.filter {
            isReadableState(it.state) && (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    runCatching { Environment.isExternalStorageManager(it.file) }.getOrDefault(false)
            )
        }.forEach { pending.addLast(it.file to it) }
        onProgress?.invoke(JSONObject().put("phase", "started").put("source", SOURCE)
            .put("directories", 0).put("files", 0).put("videos", 0).put("excludedNoMedia", 0).put("nomediaDirectories", 0).put("nomediaFiles", 0))

        while (pending.isNotEmpty()) {
            if (shouldCancel()) { cancelled = true; break }
            val (dir, root) = pending.removeLast()
            val canonical = runCatching { dir.canonicalFile }.getOrElse { dir }
            if (!visited.add(canonical.path) || isRestricted(canonical)) continue

            val volumeStats = statsByVolume[root.volumeId] ?: JSONObject()
            if (isNoMediaDirectory(canonical)) {
                excludedNoMedia++
                volumeStats.put("excludedNoMedia", volumeStats.optInt("excludedNoMedia", 0) + 1)
                    .put("nomediaDirectories", volumeStats.optInt("nomediaDirectories", 0) + 1)
                    .put("nomediaFiles", volumeStats.optInt("nomediaFiles", 0) + 1)
                statsByVolume[root.volumeId] = volumeStats
                continue
            }
            directories++
            volumeStats.put("directories", volumeStats.optInt("directories", 0) + 1)
            val children = try {
                canonical.listFiles()
            } catch (security: SecurityException) {
                val error = JSONObject()
                    .put("type", "SECURITY_EXCEPTION")
                    .put("message", security.message ?: "Acesso negado pelo Android.")
                    .put("path", canonical.path)
                    .put("volumeId", root.volumeId)
                errors.put(error)
                errorsByVolume[root.volumeId]?.put(error)
                null
            } catch (io: java.io.IOException) {
                val error = JSONObject()
                    .put("type", "IO_ERROR")
                    .put("message", io.message ?: "Erro de I/O.")
                    .put("path", canonical.path)
                    .put("volumeId", root.volumeId)
                errors.put(error)
                errorsByVolume[root.volumeId]?.put(error)
                null
            } catch (exception: Exception) {
                val error = JSONObject()
                    .put("type", "OS_ERROR")
                    .put("message", exception.message ?: exception::class.java.simpleName)
                    .put("path", canonical.path)
                    .put("volumeId", root.volumeId)
                errors.put(error)
                errorsByVolume[root.volumeId]?.put(error)
                null
            }
            if (children == null) {
                if (!canonical.exists()) {
                    val error = JSONObject()
                        .put("type", "DIRECTORY_NOT_FOUND")
                        .put("message", "Diretório inexistente: ${canonical.path}")
                        .put("path", canonical.path)
                        .put("volumeId", root.volumeId)
                    errors.put(error)
                    errorsByVolume[root.volumeId]?.put(error)
                } else if (!canonical.isDirectory) {
                    val error = JSONObject()
                        .put("type", "DIRECTORY_INVALID")
                        .put("message", "Entrada não é um diretório: ${canonical.path}")
                        .put("path", canonical.path)
                        .put("volumeId", root.volumeId)
                    errors.put(error)
                    errorsByVolume[root.volumeId]?.put(error)
                } else if (!canonical.canRead()) {
                    val error = JSONObject()
                        .put("type", "ACCESS_DENIED")
                        .put("message", "Diretório sem leitura: ${canonical.path}")
                        .put("path", canonical.path)
                        .put("volumeId", root.volumeId)
                    errors.put(error)
                    errorsByVolume[root.volumeId]?.put(error)
                }
                continue
            }
            if (children.any { it.isFile && it.name.equals(".nomedia", ignoreCase = true) }) {
                excludedNoMedia++
                volumeStats.put("excludedNoMedia", volumeStats.optInt("excludedNoMedia", 0) + 1)
                    .put("nomediaDirectories", volumeStats.optInt("nomediaDirectories", 0) + 1)
                    .put("nomediaFiles", volumeStats.optInt("nomediaFiles", 0) + 1)
                statsByVolume[root.volumeId] = volumeStats
                continue
            }

            for (child in children) {
                if (shouldCancel()) { cancelled = true; break }
                if (isRestricted(child)) continue
                if (child.isDirectory) { pending.addLast(child to root); continue }

                files++
                volumeStats.put("files", volumeStats.optInt("files", 0) + 1)
                if (child.name.equals(".nomedia", ignoreCase = true)) {
                    volumeStats.put("nomediaFiles", volumeStats.optInt("nomediaFiles", 0) + 1)
                    continue
                }
                if (!child.isFile || child.extension.lowercase() !in videoExtensions) continue

                val file = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (!isInside(file, root.file) || isRestricted(file)) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "SCAN_SOURCE_FILE_REJECTED reason=OUTSIDE_SOURCE path=" + file.path)
                    continue
                }
                val volumeName = root.volumeId
                val relative = relativePath(file, root.file)
                val document = JSONObject()
                    .put("uri", Uri.fromFile(file).toString())
                    .put("path", file.path)
                    .put("name", file.name)
                    .put("relativePath", relative)
                    .put("volumeName", volumeName)
                    .put("volumeId", volumeName)
                    .put("volumeUuid", root.volumeUuid ?: "")
                    .put("mimeType", mimeFor(file.extension))
                    .put("size", runCatching { file.length() }.getOrDefault(0L))
                    .put("modifiedAt", runCatching { file.lastModified() }.getOrDefault(0L))
                batchesByVolume[volumeName]?.add(document)
                videos++
                volumeStats.put("videos", volumeStats.optInt("videos", 0) + 1)
                if (videos % 100 == 0) {
                    Log.i(TAG, "VIDEO_PROGRESS: videos=" + videos + ", files=" + files + ", directories=" + directories)
                    onProgress?.invoke(JSONObject().put("phase", "scanning").put("source", SOURCE)
                        .put("volumeId", volumeName).put("directories", directories).put("files", files)
                        .put("videos", videos).put("excludedNoMedia", excludedNoMedia))
                }
            }
            statsByVolume[root.volumeId] = volumeStats
            if (cancelled) break
        }

        batchesByVolume.values.forEach { it.flush() }

        val volumeScopes = JSONArray()
        var totalDuplicates = 0

        for ((volumeId, root) in rootByVolume) {
            val scopeKey = "broad-storage:" + volumeId
            val generation = generationByVolume[volumeId] ?: continue
            val volumeErrors = errorsByVolume[volumeId] ?: JSONArray()
            val scopeStats = statsByVolume[volumeId] ?: JSONObject()
            val endState = runCatching { Environment.getExternalStorageState(root.file) }.getOrDefault(root.state)
            val volumeStillReadable = isReadableState(endState)
            val volumeStillAuthorized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Environment.isExternalStorageManager(root.file) }.getOrDefault(false)
            } else {
                true
            }
            if (!volumeStillReadable && !cancelled) {
                val endError = JSONObject()
                    .put("type", volumeErrorType(endState))
                    .put("message", "Volume ficou indisponível durante a varredura: " + volumeId + " (" + endState + ").")
                    .put("volumeId", volumeId)
                    .put("state", endState)
                volumeErrors.put(endError)
                errors.put(endError)
            }
            val batchCount = NativeIndex.batchCount(context, SOURCE, scopeKey, generation)
            val stagedDocuments = NativeIndex.stagedDocumentCount(context, SOURCE, scopeKey, generation)
            val complete = isReadableState(root.state) &&
                volumeStillReadable &&
                volumeStillAuthorized &&
                volumeErrors.length() == 0 &&
                !cancelled
            val status = when {
                cancelled -> NativeIndex.STATUS_CANCELLED
                !complete -> NativeIndex.STATUS_PARTIAL
                stagedDocuments == 0 -> NativeIndex.STATUS_EMPTY_COMPLETE
                else -> NativeIndex.STATUS_COMPLETED
            }
            val metadata = JSONObject()
                .put("volumeId", volumeId)
                .put("volumeUuid", root.volumeUuid ?: "")
                .put("state", endState)
                .put("initialState", root.state)
                .put("allFilesAccessForPath", volumeStillAuthorized)
                .put("removable", root.removable)
                .put("primary", root.primary)
                .put("errors", volumeErrors)
                .put("status", status)
                .put("files", scopeStats.optInt("files", 0))
                .put("videos", scopeStats.optInt("videos", 0))
                .put("batchCount", batchCount)
                .put("processed", stagedDocuments)
            val finished = NativeIndex.finishGeneration(context, SOURCE, scopeKey, generation, status, metadata)
            totalDuplicates += finished.optInt("duplicates", 0)
            volumeScopes.put(JSONObject()
                .put("volumeId", volumeId)
                .put("scopeKind", "volume")
                .put("scopeRef", volumeId)
                .put("scanGeneration", generation)
                .put("generationId", NativeIndex.generationId(SOURCE, scopeKey, generation))
                .put("scanId", (scanId ?: "") + ":" + volumeId)
                .put("status", status)
                .put("complete", complete)
                .put("reused", false)
                .put("batchCount", batchCount)
                .put("processed", stagedDocuments)
                .put("duplicates", finished.optInt("duplicates", 0))
                .put("removed", 0)
                .put("errors", volumeErrors)
                .put("stats", scopeStats))
        }

        val partial = errors.length() > 0 || cancelled
        Log.i(TAG, "SCAN_COMPLETED: directories=" + directories + ", files=" + files + ", videos=" + videos + ", nomedia=" + excludedNoMedia + ", errors=" + errors.length() + ", partial=" + partial)
        onProgress?.invoke(JSONObject().put("phase", "finished").put("source", SOURCE)
            .put("directories", directories).put("files", files).put("videos", videos)
            .put("excludedNoMedia", excludedNoMedia).put("cancelled", cancelled))

        return JSONObject()
            .put("source", SOURCE)
            .put("name", DISPLAY_NAME)
            .put("volumeScopes", volumeScopes)
            .put("stats", JSONObject()
                .put("directories", directories).put("files", files).put("videos", videos)
                .put("excludedNoMedia", excludedNoMedia)
                .put("nomediaDirectories", excludedNoMedia)
                .put("nomediaFiles", (0 until volumeScopes.length()).sumOf { volumeScopes.getJSONObject(it).optJSONObject("stats")?.optInt("nomediaFiles", 0) ?: 0 })
                .put("errors", errors).put("access", access)
                .put("duplicates", totalDuplicates).put("removed", 0)
                .put("status", when { cancelled -> "cancelled"; partial -> "partial"; else -> "completed" })
                .put("generationStatus", when { cancelled -> NativeIndex.STATUS_CANCELLED; partial -> NativeIndex.STATUS_PARTIAL; else -> NativeIndex.STATUS_COMPLETED }))
            .put("partial", errors.length() > 0 || cancelled)
            .put("permissionAuthority", "Environment.isExternalStorageManager")
            .put("permissionAuthoritative", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            .put("cancelled", cancelled)
    }
private fun mimeFor(ext:String):String = when(ext.lowercase()) {
        "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"; "ts","m2ts" -> "video/mp2t"; "flv" -> "video/x-flv"; "wmv" -> "video/x-ms-wmv"; else -> "video/mp4"
    }

    private fun isReadableState(state: String?): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun volumeErrorType(state: String?): String = when (state) {
        Environment.MEDIA_UNMOUNTED,
        Environment.MEDIA_UNMOUNTABLE,
        Environment.MEDIA_REMOVED,
        Environment.MEDIA_BAD_REMOVAL,
        Environment.MEDIA_NOFS -> "VOLUME_UNMOUNTED"
        Environment.MEDIA_SHARED,
        Environment.MEDIA_CHECKING -> "UNAVAILABLE"
        Environment.MEDIA_MOUNTED_READ_ONLY -> "READ_ONLY"
        else -> "ACCESS_DENIED"
    }
}
