package com.reiflix.reiflix_local.storage

import com.reiflix.reiflix_local.storage.NativeBatch
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Durable native physical index. This is not a catalogue/database: only physical
 * discovery snapshots, generations, volume state and source metadata live here.
 */
object NativeIndex {
    const val SOURCE_MEDIASTORE = "mediastore"
    const val SOURCE_SAF = "saf"
    const val SOURCE_BROAD = "broad_storage"
    private const val VERSION = 4
    private const val FILE_NAME = "reiflix-native-index.json"
    private const val DATA_DIR = "data"
    private const val BATCH_DIR = "native-batches"
    const val BATCH_SIZE = NativeBatch.DEFAULT_SIZE

    data class NativePrepared(
        val documents: JSONArray, val generation: Long, val newItems: Int,
        val changedItems: Int, val unchangedItems: Int, val duplicates: Int, val removedItems: Int,
        val status: String = STATUS_COMPLETED,
        val scopeKey: String = "",
    )

    data class NativeBatchPrepared(
        val documents: JSONArray,
        val generation: Long,
        val generationId: String,
        val batchId: String,
        val batchNumber: Int,
        val batchSize: Int,
        val duplicates: Int,
        val stagingFile: String,
    )

    const val STATUS_STARTED = "STARTED"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_EMPTY_COMPLETE = "EMPTY_COMPLETE"
    const val STATUS_UNAVAILABLE = "UNAVAILABLE"
    const val STATUS_PARTIAL = "PARTIAL"
    const val STATUS_CANCELLED = "CANCELLED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_WAITING_FOR_MEDIASTORE = "WAITING_FOR_MEDIASTORE"

    private fun file(context: Context) = File(File(context.filesDir, DATA_DIR), FILE_NAME)

    private fun batchDirectory(context: Context) = File(File(context.filesDir, DATA_DIR), BATCH_DIR)

    private fun scopeFileKey(source: String, scopeKey: String, generation: Long): String =
        sha256(source + "|" + scopeKey + "|" + generation).take(32)

    private fun stagingFile(context: Context, source: String, scopeKey: String, generation: Long): File =
        File(batchDirectory(context), scopeFileKey(source, scopeKey, generation) + ".ndjson.tmp")

    private fun committedFile(context: Context, source: String, scopeKey: String, generation: Long): File =
        File(batchDirectory(context), scopeFileKey(source, scopeKey, generation) + ".ndjson")

    private fun committedFileFromScope(context: Context, scope: JSONObject): File? {
        val path = scope.optString("committedFile").trim()
        return path.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
    }

    private fun read(context: Context): JSONObject {
        val target = file(context)
        if (!target.isFile) return JSONObject().put("version", VERSION)
        return runCatching { JSONObject(target.readText(Charsets.UTF_8)) }.getOrElse { error ->
            Log.e("NativeIndex", "Native index snapshot is unreadable; LibraryStore remains authoritative and the native cache will rebuild on the next scan.", error)
            JSONObject().put("version", VERSION).put("recovery", "corrupt_snapshot")
        }.also {
            val storedVersion = it.optInt("version", 1)
            if (storedVersion <= VERSION) {
                it.put("version", VERSION)
            } else {
                Log.w("NativeIndex", "Native index version " + storedVersion + " is newer than supported " + VERSION)
            }
        }
    }

    private fun write(context: Context, state: JSONObject) {
        val dir = File(context.filesDir, DATA_DIR)
        check(dir.isDirectory || dir.mkdirs()) { "Could not create native index directory" }
        val temp = File(dir, FILE_NAME + ".tmp")
        FileOutputStream(temp).use {
            it.write(state.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        check(temp.renameTo(file(context))) { "Could not publish native index snapshot" }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun cleanPath(value: String) = value.trim().replace('\\', '/').trim('/')

    private fun canonicalVolume(value: String): String = when (value.trim().lowercase()) {
        "", "primary", "external", "external_primary" -> "primary"
        else -> value.trim()
    }

    fun stableIdentity(document: JSONObject, source: String): String {
        val explicit = document.optString("stableId").trim()
        if (explicit.isNotEmpty()) return explicit
        val volume = canonicalVolume(document.optString("volumeId"))
        val relative = cleanPath(document.optString("relativePath"))
        val tree = document.optString("treeUri").trim()
        val documentId = document.optString("documentId").trim()
        if (source == SOURCE_SAF && documentId.isNotEmpty()) {
            if (documentId.contains(":")) {
                val documentVolume = canonicalVolume(documentId.substringBefore(":"))
                val documentPath = cleanPath(documentId.substringAfter(":"))
                if (documentPath.isNotEmpty()) return "shared:" + documentVolume + ":" + documentPath
            }
            if (tree.isNotEmpty()) return "saf:" + tree + ":" + documentId
        }
        if (volume.isNotEmpty() && relative.isNotEmpty()) return "shared:" + volume + ":" + relative
        val uri = document.optString("uri").trim()
        return if (uri.isNotEmpty()) "uri:" + uri else "opaque:" + sha256(document.toString())
    }

    private fun fingerprint(document: JSONObject, stableId: String): String =
        sha256(
            stableId + "|" + document.optString("name") + "|" + document.optString("size") + "|" +
                document.optString("modifiedAt") + "|" + document.optString("mediaId") + "|" + document.optString("mimeType") + "|" +
                document.optString("relativePath")
        )

    private fun scopes(state: JSONObject) =
        state.optJSONObject("scopes") ?: JSONObject().also { state.put("scopes", it) }

    private fun counters(state: JSONObject) =
        state.optJSONObject("generationCounters") ?: JSONObject().also { state.put("generationCounters", it) }

    fun generationId(source: String, scopeKey: String, generation: Long): String =
        "native:" + source + ":" + scopeKey + ":" + generation

    private fun nextGeneration(state: JSONObject, scopeKey: String): Long {
        val map = counters(state)
        val next = map.optLong(scopeKey, 0L) + 1L
        map.put(scopeKey, next)
        return next
    }

    fun startGeneration(context: Context, source: String, scopeKey: String, metadata: JSONObject = JSONObject()): Long = synchronized(this) {
        val state = read(context)
        val scopeMap = scopes(state)
        val scope = scopeMap.optJSONObject(scopeKey) ?: JSONObject().also { scopeMap.put(scopeKey, it) }
        val generation = nextGeneration(state, scopeKey)
        val now = System.currentTimeMillis()
        scope.put("generation", generation)
            .put("generationId", generationId(source, scopeKey, generation))
            .put("version", VERSION)
            .put("status", STATUS_STARTED)
            .put("source", source)
            .put("scopeKey", scopeKey)
            .put("startedAt", now)
            .put("scanId", metadata.optString("scanId"))
            .put("state", metadata.optString("state", STATUS_STARTED))
            .put("availability", "discovering")
            .put("finishedAt", JSONObject.NULL)
            .put("metadata", JSONObject(metadata.toString()))
            .put("counts", JSONObject())
            .put("batchId", JSONObject.NULL)
            .put("batchNumber", 0)
            .put("batchSize", 0)
            .put("processed", 0)
            .put("discovered", 0)
            .put("inserted", 0)
            .put("updated", 0)
            .put("unchanged", 0)
            .put("duplicates", 0)
            .put("removed", 0)
        write(context, state)
        val staging = stagingFile(context, source, scopeKey, generation)
        check(staging.parentFile?.isDirectory == true || staging.parentFile?.mkdirs() == true)
        if (!staging.exists()) staging.writeText("", Charsets.UTF_8)
        generation
    }

    fun markGenerationRunning(context: Context, source: String, scopeKey: String, generation: Long) = synchronized(this) {
        val state = read(context)
        val scopeMap = scopes(state)
        val scope = scopeMap.optJSONObject(scopeKey) ?: JSONObject().also { scopeMap.put(scopeKey, it) }
        if (scope.optLong("generation", 0L) == generation) {
            scope.put("status", STATUS_RUNNING).put("source", source).put("scopeKey", scopeKey)
            write(context, state)
        }
    }

    /**
     * Incrementally prepares one bounded batch. The batch is appended to a
     * generation-specific NDJSON staging file; the last committed snapshot is
     * never mutated until finishGeneration() reports a trusted COMPLETE result.
     */
    fun prepareBatch(
        context: Context,
        source: String,
        scopeKey: String,
        input: JSONArray,
        generation: Long,
        batchId: String,
        batchNumber: Int,
        metadata: JSONObject = JSONObject(),
    ): NativeBatchPrepared = synchronized(this) {
        val state = read(context)
        val scopeMap = scopes(state)
        val scope = scopeMap.optJSONObject(scopeKey) ?: JSONObject().also { scopeMap.put(scopeKey, it) }
        val effectiveGeneration = if (generation > 0L) generation else scope.optLong("generation", 0L)
        require(effectiveGeneration > 0L) { "Native generation is required for batched indexing" }
        require(scope.optLong("generation", effectiveGeneration) == effectiveGeneration) {
            "Native generation no longer owns scope: $scopeKey"
        }
        val generationIdentifier = generationId(source, scopeKey, effectiveGeneration)
        val output = JSONArray()
        val seen = HashSet<String>()
        var duplicates = 0
        val scanId = metadata.optString("scanId").trim()
        val now = System.currentTimeMillis()

        for (i in 0 until input.length()) {
            val raw = input.optJSONObject(i) ?: continue
            val document = JSONObject(raw.toString())
            val stableId = stableIdentity(document, source)
            if (!seen.add(stableId)) {
                duplicates++
                continue
            }
            val fp = fingerprint(document, stableId)
            document.put("stableId", stableId)
                .put("nativeFingerprint", fp)
                .put("nativeChange", "OBSERVED")
                .put("scanGeneration", effectiveGeneration)
                .put("generationId", generationIdentifier)
                .put("nativeVersion", VERSION)
                .put("scanId", scanId)
                .put("source", source)
                .put("scopeKey", scopeKey)
                .put("state", "OBSERVED")
                .put("availability", "available")
                .put("lastSeen", now)
            output.put(document)
        }

        val staging = stagingFile(context, source, scopeKey, effectiveGeneration)
        check(staging.parentFile?.isDirectory == true || staging.parentFile?.mkdirs() == true)
        if (!staging.exists()) staging.writeText("", Charsets.UTF_8)
        NativeBatch.appendNdjson(staging, output)

        val counts = scope.optJSONObject("counts") ?: JSONObject().also { scope.put("counts", it) }
        counts.put("batches", counts.optInt("batches", 0) + 1)
            .put("processed", counts.optInt("processed", 0) + output.length())
            .put("discovered", counts.optInt("discovered", 0) + input.length())
            .put("duplicates", counts.optInt("duplicates", 0) + duplicates)
        scope.put("status", STATUS_RUNNING)
            .put("generation", effectiveGeneration)
            .put("generationId", generationIdentifier)
            .put("source", source)
            .put("scopeKey", scopeKey)
            .put("scanId", scanId)
            .put("batchId", batchId)
            .put("batchNumber", batchNumber)
            .put("batchSize", output.length())
            .put("stagingFile", staging.absolutePath)
            .put("updatedAt", now)
            .put("availability", "staged")
            .put("progress", JSONObject()
                .put("processed", counts.optInt("processed", 0))
                .put("discovered", counts.optInt("discovered", 0))
                .put("batchId", batchId)
                .put("batchNumber", batchNumber)
                .put("batchSize", output.length())
                .put("elapsedMs", now - scope.optLong("startedAt", now)))
        write(context, state)

        NativeBatchPrepared(
            output, effectiveGeneration, generationIdentifier, batchId,
            batchNumber, output.length(), duplicates, staging.absolutePath
        )
    }

    /**
     * Atomically publishes a fully scanned generation. Partial/cancelled/failed
     * generations keep the previously committed NDJSON snapshot untouched.
     */
    fun finishGeneration(
        context: Context,
        source: String,
        scopeKey: String,
        generation: Long,
        status: String,
        metadata: JSONObject = JSONObject(),
    ): JSONObject = synchronized(this) {
        val state = read(context)
        val scopeMap = scopes(state)
        val scope = scopeMap.optJSONObject(scopeKey) ?: JSONObject().also { scopeMap.put(scopeKey, it) }
        val normalizedStatus = when {
            status.equals(STATUS_EMPTY_COMPLETE, true) -> STATUS_EMPTY_COMPLETE
            status.equals(STATUS_COMPLETED, true) -> STATUS_COMPLETED
            status.equals(STATUS_CANCELLED, true) -> STATUS_CANCELLED
            status.equals(STATUS_FAILED, true) -> STATUS_FAILED
            status.equals(STATUS_UNAVAILABLE, true) -> STATUS_UNAVAILABLE
            status.equals(STATUS_WAITING_FOR_MEDIASTORE, true) -> STATUS_WAITING_FOR_MEDIASTORE
            else -> STATUS_PARTIAL
        }
        val effectiveGeneration = if (generation > 0L) generation else scope.optLong("generation", 0L)
        val sourceKey = generationId(source, scopeKey, effectiveGeneration)
        val staging = stagingFile(context, source, scopeKey, effectiveGeneration)
        val committed = committedFile(context, source, scopeKey, effectiveGeneration)
        val now = System.currentTimeMillis()
        val complete = normalizedStatus == STATUS_COMPLETED || normalizedStatus == STATUS_EMPTY_COMPLETE
        var published = false
        if (complete) {
            check(staging.parentFile?.isDirectory == true || staging.parentFile?.mkdirs() == true)
            if (!staging.exists()) staging.writeText("", Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    staging.toPath(),
                    committed.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    staging.toPath(),
                    committed.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            published = true
        }
        val counts = scope.optJSONObject("counts") ?: JSONObject()
        scope.put("generation", effectiveGeneration)
            .put("generationId", sourceKey)
            .put("source", source)
            .put("scopeKey", scopeKey)
            .put("status", normalizedStatus)
            .put("state", normalizedStatus)
            .put("availability", if (complete) "available" else "preserved")
            .put("finishedAt", now)
            .put("committedFile", if (published) committed.absolutePath else committedFileFromScope(context, scope)?.absolutePath ?: "")
            .put("metadata", JSONObject(metadata.toString()))
            .put("counts", counts.put("finishedAt", now))
        write(context, state)
        JSONObject()
            .put("generation", effectiveGeneration)
            .put("generationId", sourceKey)
            .put("status", normalizedStatus)
            .put("complete", complete)
            .put("published", published)
            .put("batchCount", counts.optInt("batches", 0))
            .put("processed", counts.optInt("processed", 0))
            .put("discovered", counts.optInt("discovered", 0))
            .put("duplicates", counts.optInt("duplicates", 0))
            .put("elapsedMs", now - scope.optLong("startedAt", now))
            .put("committedFile", if (published) committed.absolutePath else "")
    }

    fun prepare(context: Context, source: String, scopeKey: String, input: JSONArray, complete: Boolean,
                metadata: JSONObject = JSONObject(), generation: Long = 0L, status: String = ""): NativePrepared = synchronized(this) {
        val state = read(context)
        val scopeMap = scopes(state)
        val scope = scopeMap.optJSONObject(scopeKey) ?: JSONObject().also { scopeMap.put(scopeKey, it) }
        val previous = scope.optJSONObject("items") ?: JSONObject()
        val effectiveGeneration = if (generation > 0L) generation else nextGeneration(state, scopeKey)
        val generationIdentifier = generationId(source, scopeKey, effectiveGeneration)
        val scanId = metadata.optString("scanId").trim()
        val now = System.currentTimeMillis()
        val output = JSONArray()
        val seen = HashSet<String>()
        var newItems = 0; var changedItems = 0; var unchangedItems = 0; var duplicates = 0

        for (i in 0 until input.length()) {
            val raw = input.optJSONObject(i) ?: continue
            val document = JSONObject(raw.toString())
            val stableId = stableIdentity(document, source)
            if (!seen.add(stableId)) { duplicates++; continue }
            val fp = fingerprint(document, stableId)
            val before = previous.optJSONObject(stableId)
            val change = when {
                before == null -> "NEW"
                before.optString("fingerprint") == fp -> "UNCHANGED"
                else -> "CHANGED"
            }
            when (change) { "NEW" -> newItems++; "CHANGED" -> changedItems++; else -> unchangedItems++ }
            val firstSeen = before?.optLong("firstSeen", now) ?: now
            document.put("stableId", stableId).put("nativeFingerprint", fp)
                .put("nativeChange", change).put("scanGeneration", effectiveGeneration)
                .put("generationId", generationIdentifier)
                .put("nativeVersion", VERSION)
                .put("scanId", scanId)
                .put("source", source).put("scopeKey", scopeKey)
                .put("state", "OBSERVED")
                .put("availability", "available")
                .put("firstSeen", firstSeen)
                .put("lastSeen", now)
            output.put(document)
        }

        val oldKeys = previous.keys().asSequence().toSet()
        val removedItems = oldKeys.count { !seen.contains(it) }
        val normalizedStatus = when {
            status.equals(STATUS_EMPTY_COMPLETE, ignoreCase = true) || status.equals("empty_complete", ignoreCase = true) -> STATUS_EMPTY_COMPLETE
            status.equals(STATUS_UNAVAILABLE, ignoreCase = true) || status.equals("unavailable", ignoreCase = true) || status.equals("revoked", ignoreCase = true) -> STATUS_UNAVAILABLE
            status.equals(STATUS_CANCELLED, ignoreCase = true) || status.equals("cancelled", ignoreCase = true) -> STATUS_CANCELLED
            status.equals(STATUS_FAILED, ignoreCase = true) || status.equals("failed", ignoreCase = true) -> STATUS_FAILED
            complete -> STATUS_COMPLETED
            else -> STATUS_PARTIAL
        }
        scope.put("generation", effectiveGeneration)
            .put("generationId", generationIdentifier)
            .put("version", VERSION)
            .put("status", normalizedStatus)
            .put("source", source)
            .put("scopeKey", scopeKey)
            .put("scanId", scanId)
            .put("state", normalizedStatus)
            .put("availability", when (normalizedStatus) {
                STATUS_COMPLETED, STATUS_EMPTY_COMPLETE -> "available"
                STATUS_UNAVAILABLE -> "unavailable"
                else -> "partial"
            })
            .put("finishedAt", now)
            .put("metadata", JSONObject(metadata.toString()))
            .put("counts", JSONObject()
                .put("new", newItems)
                .put("changed", changedItems)
                .put("unchanged", unchangedItems)
                .put("duplicates", duplicates)
                .put("removed", removedItems)
                .put("observed", output.length())
                .put("errors", metadata.optJSONArray("errors") ?: JSONArray()))
        if (complete) {
            val items = JSONObject()
            for (i in 0 until output.length()) {
                val doc = output.getJSONObject(i)
                items.put(doc.getString("stableId"), JSONObject(doc.toString()).put("fingerprint", doc.getString("nativeFingerprint")))
            }
            scope.put("updatedAt", now).put("items", items)
        }
        write(context, state)
        NativePrepared(output, effectiveGeneration, newItems, changedItems, unchangedItems, duplicates, removedItems, normalizedStatus, scopeKey)
    }

    /**
     * Marks every in-flight generation for a source as FAILED after a scanner-level
     * exception. The last completed snapshot remains intact because only the
     * generation status is changed.
     */
    fun failActiveGenerations(context: Context, source: String, error: String) = synchronized(this) {
        val state = read(context)
        val scopeMap = scopes(state)
        val now = System.currentTimeMillis()
        val keys = scopeMap.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val scope = scopeMap.optJSONObject(key) ?: continue
            if (scope.optString("source") != source) continue
            val status = scope.optString("status")
            if (status == STATUS_STARTED || status == STATUS_RUNNING) {
                scope.put("status", STATUS_FAILED)
                    .put("finishedAt", now)
                    .put("error", error)
            }
        }
        write(context, state)
    }

    fun failGeneration(context: Context, source: String, scopeKey: String, generation: Long, error: String, metadata: JSONObject = JSONObject()) = synchronized(this) {
        val state = read(context)
        val scopeMap = scopes(state)
        val scope = scopeMap.optJSONObject(scopeKey) ?: JSONObject().also { scopeMap.put(scopeKey, it) }
        scope.put("generation", generation)
            .put("status", STATUS_FAILED)
            .put("source", source)
            .put("scopeKey", scopeKey)
            .put("finishedAt", System.currentTimeMillis())
            .put("error", error)
            .put("metadata", JSONObject(metadata.toString()))
        write(context, state)
    }

    fun forEachCachedBatch(
        context: Context,
        scopeKey: String,
        batchSize: Int = BATCH_SIZE,
        onBatch: (JSONArray, Int) -> Unit,
    ): Int = synchronized(this) {
        val scope = scopes(read(context)).optJSONObject(scopeKey) ?: return@synchronized 0
        val committed = committedFileFromScope(context, scope)
        if (committed != null) {
            var sequence = 0
            return@synchronized NativeBatch.readNdjsonBatches(committed, batchSize) {
                sequence += 1
                onBatch(it, sequence)
            }
        }
        val items = scope.optJSONObject("items") ?: return@synchronized 0
        var sequence = 0
        var batch = JSONArray()
        var total = 0
        val keys = items.keys()
        while (keys.hasNext()) {
            val item = items.optJSONObject(keys.next()) ?: continue
            val document = JSONObject(item.toString()).also { it.remove("fingerprint") }
            batch.put(document)
            total++
            if (batch.length() >= NativeBatch.normalizeSize(batchSize)) {
                sequence++
                onBatch(batch, sequence)
                batch = JSONArray()
            }
        }
        if (batch.length() > 0) {
            sequence++
            onBatch(batch, sequence)
        }
        total
    }

    fun stagedDocumentCount(context: Context, source: String, scopeKey: String, generation: Long): Int = synchronized(this) {
        val scope = scopes(read(context)).optJSONObject(scopeKey) ?: return@synchronized 0
        val staging = stagingFile(context, source, scopeKey, generation)
        var count = 0
        if (staging.isFile) {
            java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(staging), Charsets.UTF_8)).use { reader ->
                while (reader.readLine() != null) count++
            }
        }
        count
    }

    fun batchCount(context: Context, source: String, scopeKey: String, generation: Long): Int = synchronized(this) {
        scopes(read(context)).optJSONObject(scopeKey)?.optJSONObject("counts")?.optInt("batches", 0) ?: 0
    }

    fun cachedGeneration(context: Context, scopeKey: String): Long = synchronized(this) {
        scopes(read(context)).optJSONObject(scopeKey)?.optLong("generation", 0L) ?: 0L
    }

    fun canReuseMediaStoreVolume(context: Context, volumeName: String, accessLevel: String,
                                 currentVersion: String, currentGeneration: Long): Boolean = synchronized(this) {
        if (Build.VERSION.SDK_INT < 30 || accessLevel != "full" || currentVersion.isBlank() || currentGeneration <= 0L) return@synchronized false
        val scope = scopes(read(context)).optJSONObject("mediastore:" + volumeName) ?: return@synchronized false
        if (scope.optString("status") !in setOf(STATUS_COMPLETED, STATUS_EMPTY_COMPLETE)) return@synchronized false
        val meta = scope.optJSONObject("metadata") ?: return@synchronized false
        val committed = committedFileFromScope(context, scope)
        val legacyItems = scope.optJSONObject("items")
        val snapshotAvailable = committed != null || (legacyItems != null && legacyItems.length() >= 0)
        meta.optString("mediaStoreVersion") == currentVersion &&
            meta.optLong("mediaStoreGeneration", -1L) == currentGeneration &&
            meta.optString("accessLevel") == "full" && snapshotAvailable
    }

    fun volumeSnapshot(context: Context): JSONArray {
        val output = JSONArray()
        if (Build.VERSION.SDK_INT < 24) return output
        val manager = context.getSystemService(StorageManager::class.java) ?: return output
        manager.storageVolumes.forEach { volume ->
            val mediaStoreName = if (Build.VERSION.SDK_INT >= 30) volume.mediaStoreVolumeName else null
            val uuid = volume.uuid
            val id = when {
                !mediaStoreName.isNullOrBlank() -> mediaStoreName
                !uuid.isNullOrBlank() -> uuid
                volume.isPrimary -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                else -> ""
            }
            if (id.isBlank()) return@forEach
            val volumeState = volume.state ?: "unknown"
            val available = volumeState == "mounted" || volumeState == "mounted_ro"
            val item = JSONObject().put("volumeId", id).put("uuid", uuid ?: "")
                .put("mediaStoreVolumeName", mediaStoreName ?: "")
                .put("primary", volume.isPrimary).put("removable", volume.isRemovable)
                .put("emulated", volume.isEmulated).put("state", volumeState)
                .put("available", available)
                .put("observedAt", System.currentTimeMillis())
            runCatching { volume.directory?.canonicalPath }.getOrNull()?.let { item.put("directory", it) }
            runCatching { item.put("description", volume.getDescription(context)) }
            output.put(item)
        }
        return output
    }

    private fun volumeSemantics(item: JSONObject): String {
        val normalized = JSONObject(item.toString())
        normalized.remove("observedAt")
        normalized.remove("lastObservedAt")
        return normalized.toString()
    }

    fun updateVolumeSnapshot(context: Context, current: JSONArray): JSONObject = synchronized(this) {
        val state = read(context)
        val previous = state.optJSONObject("volumes") ?: JSONObject()
        if (current.length() == 0 && previous.length() > 0) {
            return JSONObject()
                .put("changed", false)
                .put("added", JSONArray())
                .put("removed", JSONArray())
                .put("changedVolumes", JSONArray())
                .put("current", JSONArray())
                .put("observedAt", System.currentTimeMillis())
                .put("observationComplete", false)
        }
        val next = JSONObject()
        val added = JSONArray(); val removed = JSONArray(); val changed = JSONArray(); val seen = HashSet<String>()
        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            val id = item.optString("volumeId").trim()
            if (id.isEmpty() || !seen.add(id)) continue
            next.put(id, item)
            val before = previous.optJSONObject(id)
            when {
                before == null -> added.put(item)
                before != null && volumeSemantics(before) != volumeSemantics(item) ->
                    changed.put(JSONObject().put("before", JSONObject(before.toString())).put("after", item))
            }
        }
        val now = System.currentTimeMillis()
        val oldKeys = previous.keys()
        while (oldKeys.hasNext()) {
            val id = oldKeys.next()
            if (!seen.contains(id)) {
                val before = previous.optJSONObject(id)
                val wasAvailable = before?.let {
                    if (it.has("available")) it.optBoolean("available", false)
                    else !it.optString("state").equals("unavailable", ignoreCase = true)
                } ?: true
                val unavailable = if (before != null) JSONObject(before.toString()) else JSONObject().put("volumeId", id)
                unavailable.put("state", "unavailable")
                    .put("available", false)
                    .put("unavailableAt", if (before?.has("unavailableAt") == true) before.optLong("unavailableAt", now) else now)
                    .put("lastObservedAt", now)
                    .put("reason", "volume_removed_from_observation")
                next.put(id, unavailable)
                if (wasAvailable) removed.put(unavailable)
            }
        }
        state.put("volumes", next)
        state.put("volumeSnapshotAt", now)
        write(context, state)
        JSONObject().put("changed", added.length() + removed.length() + changed.length() > 0)
            .put("added", added).put("removed", removed).put("changedVolumes", changed).put("current", current)
            .put("observedAt", now)
            .put("observationComplete", true)
    }
}
