package com.reianix.reianix_local.scanner

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


/**
 * Process-scoped observers for the user's configured SAF roots.
 *
 * The observers remain registered while MainActivity is backgrounded, so leaving
 * and returning to ReiAnix never creates a scan by itself. A provider notification
 * is inspected on IO; only a video file or a directory containing video files is
 * persisted as pending work. The pending URI/timestamp survives Activity recreation
 * and process death while a scan is running.
 */
internal fun hasNewOrChangedVideoDocuments(
    previous: Map<String, String>,
    current: Map<String, String>,
): Boolean = current.any { (documentId, fingerprint) ->
    previous[documentId] != fingerprint
}

object SafTreeChangeMonitor {
    private const val TAG = "[REIANIX][SAF_CHANGE]"
    private const val PREFS_NAME = "reianix_saf_change_monitor"
    private const val PENDING_CHANGES_KEY = "pending_video_tree_changes"
    private const val INVENTORY_PREFIX = "video_inventory_v1_"
    private const val MAX_INVENTORY_DOCUMENTS = 50_000
    private const val DEBOUNCE_MILLIS = 1_500L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = LinkedHashMap<String, ContentObserver>()
    private val debounceTasks = HashMap<String, Runnable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableChanges = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: SharedFlow<String> = mutableChanges.asSharedFlow()

    /** Reconcile process-scoped observers with the canonical SQLite SAF roots. */
    fun synchronize(
        context: Context,
        treeReferences: Collection<String>,
        forceInventoryCheck: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val desired = treeReferences.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filter { raw ->
                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@filter false
                uri.scheme.equals("content", true) &&
                    DocumentsContract.isTreeUri(uri) &&
                    SafScanner.hasPersistedReadPermission(appContext, uri)
            }
            .toSet()

        synchronized(lock) {
            val removed = observers.keys.filterNot(desired::contains)
            removed.forEach { reference ->
                observers.remove(reference)?.let { observer ->
                    runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
                }
                debounceTasks.remove(reference)?.let(mainHandler::removeCallbacks)
            }

            val newlyObserved = desired - observers.keys
            newlyObserved.forEach { reference ->
                val treeUri = runCatching { Uri.parse(reference) }.getOrNull() ?: return@forEach
                val identity = runCatching { SafScanner.treeIdentity(treeUri) }.getOrNull() ?: return@forEach
                val rootDocumentUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, identity.documentId)
                }.getOrNull() ?: return@forEach
                val observer = object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        inspectProviderChange(appContext, reference, treeUri, uri)
                    }
                }
                try {
                    appContext.contentResolver.registerContentObserver(rootDocumentUri, true, observer)
                    observers[reference] = observer
                    Log.i(TAG, "SAF_TREE_OBSERVER_REGISTERED treeHash=" + reference.hashCode())
                } catch (error: Exception) {
                    Log.w(TAG, "Could not register SAF tree observer treeHash=" + reference.hashCode(), error)
                }
            }

            // The first registration (including the first ever baseline) and explicit
            // volume-remount verification use the same silent inventory comparison.
            // A baseline only seeds state; it never scans by itself.
            val inventoryChecks = if (forceInventoryCheck) desired else newlyObserved
            inventoryChecks.forEach { reference ->
                val treeUri = runCatching { Uri.parse(reference) }.getOrNull() ?: return@forEach
                scope.launch { reconcilePersistedInventory(appContext, reference, treeUri) }
            }

            removePendingForUnconfiguredTrees(appContext, desired)
        }
    }

    private fun inspectProviderChange(
        context: Context,
        reference: String,
        treeUri: Uri,
        changedUri: Uri?,
    ) {
        scope.launch {
            val current = readVideoInventory(context, treeUri)
            val previous = loadVideoInventory(context, reference)
            val relevant = when {
                current != null && previous != null ->
                    hasNewOrChangedVideoDocuments(previous, current)
                // Before the first durable snapshot exists, accept only a directly
                // identified video document. A null/root notification must not scan
                // merely because the library already contains some videos.
                // A missing baseline is initialized silently. Folder selection already
                // performs its own validated initial scan, and guessing here would cause
                // false positives for folders that merely contain existing videos.
                current != null && previous == null -> false
                current == null && changedUri != null &&
                    DocumentsContract.isDocumentUri(context, changedUri) ->
                    runCatching {
                        SafScanner.containsPotentialVideoChange(context, treeUri, changedUri)
                    }.getOrDefault(false)
                else -> false
            }

            // Persist the pending event before advancing the snapshot. A process death
            // between these writes can therefore never acknowledge an unscanned video.
            if (relevant) markPendingChange(context, reference)
            if (current != null) saveVideoInventory(context, reference, current)
            if (relevant) debouncePendingDispatch(context, reference)
        }
    }

    private fun debouncePendingDispatch(context: Context, reference: String) {
        synchronized(lock) {
            debounceTasks.remove(reference)?.let(mainHandler::removeCallbacks)
            val task = Runnable {
                val timestamp = pendingChanges(context)[reference] ?: 0L
                if (timestamp > 0L) {
                    Log.i(TAG, "SAF_VIDEO_CHANGE_PENDING treeHash=" + reference.hashCode())
                    mutableChanges.tryEmit(reference)
                }
                synchronized(lock) {
                    debounceTasks.remove(reference)
                }
            }
            debounceTasks[reference] = task
            mainHandler.postDelayed(task, DEBOUNCE_MILLIS)
        }
    }

    private fun markPendingChange(context: Context, reference: String): Long = synchronized(lock) {
        val pending = readPendingJson(context)
        val previous = pending.optLong(reference, 0L)
        val timestamp = maxOf(System.currentTimeMillis(), previous + 1L)
        pending.put(reference, timestamp)
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PENDING_CHANGES_KEY, pending.toString())
            .commit()
        if (saved) timestamp else 0L
    }

    private fun reconcilePersistedInventory(
        context: Context,
        reference: String,
        treeUri: Uri,
    ) {
        val current = readVideoInventory(context, treeUri) ?: run {
            Log.i(TAG, "SAF_VIDEO_INVENTORY_SKIPPED reason=provider_unavailable treeHash=" + reference.hashCode())
            return
        }
        val previous = loadVideoInventory(context, reference)
        if (previous == null) {
            saveVideoInventory(context, reference, current)
            Log.i(TAG, "SAF_VIDEO_INVENTORY_BASELINED treeHash=" + reference.hashCode() + " videos=" + current.size)
            return
        }

        if (hasNewOrChangedVideoDocuments(previous, current)) {
            // Save a durable pending scan before acknowledging the new snapshot.
            markPendingChange(context, reference)
            Log.i(
                TAG,
                "SAF_VIDEO_INVENTORY_CHANGED treeHash=" + reference.hashCode() +
                    " previous=" + previous.size + " current=" + current.size,
            )
            mutableChanges.tryEmit(reference)
        }
        saveVideoInventory(context, reference, current)
    }

    /**
     * Enumerates only provider metadata, never opens media bytes. This silent inventory
     * is used only when a process-scoped observer is first registered after process
     * creation, not on ordinary onResume. Document IDs are provider identities, not
     * filesystem paths. Deletions alone do not request a library scan.
     */
    private fun readVideoInventory(context: Context, treeUri: Uri): Map<String, String>? {
        if (!SafScanner.hasPersistedReadPermission(context, treeUri)) return null
        val identity = runCatching { SafScanner.treeIdentity(treeUri) }.getOrNull() ?: return null
        val pending = ArrayDeque<String>()
        val visited = HashSet<String>()
        val inventory = LinkedHashMap<String, String>()
        pending.addLast(identity.documentId)
        var examined = 0

        while (pending.isNotEmpty()) {
            val parentId = pending.removeFirst()
            if (!visited.add(parentId)) continue
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            }.getOrNull() ?: return null
            val cursor = try {
                context.contentResolver.query(
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
                ) ?: return null
            } catch (error: Exception) {
                Log.w(TAG, "SAF video inventory query failed.", error)
                return null
            }

            try {
                cursor.use { rows ->
                    val idIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (idIndex < 0 || mimeIndex < 0) return null

                    while (rows.moveToNext()) {
                        examined++
                        if (examined > MAX_INVENTORY_DOCUMENTS) {
                            Log.w(TAG, "SAF video inventory exceeded metadata budget.")
                            return null
                        }
                        val id = rows.getString(idIndex)?.trim().orEmpty()
                        if (id.isBlank()) continue
                        val name = if (nameIndex >= 0) rows.getString(nameIndex).orEmpty() else ""
                        val mime = rows.getString(mimeIndex).orEmpty()
                        if (mime.equals(DocumentsContract.Document.MIME_TYPE_DIR, ignoreCase = true)) {
                            pending.addLast(id)
                            continue
                        }
                        if (!SafScanner.isPotentialVideoDocument(name, mime)) continue
                        val size = if (sizeIndex >= 0 && !rows.isNull(sizeIndex)) rows.getLong(sizeIndex) else -1L
                        val modified = if (modifiedIndex >= 0 && !rows.isNull(modifiedIndex)) rows.getLong(modifiedIndex) else -1L
                        inventory[id] = "$name|$mime|$size|$modified"
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "SAF video inventory iteration failed.", error)
                return null
            }
        }
        return inventory
    }

    private fun inventoryPreferenceKey(reference: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(reference.toByteArray(Charsets.UTF_8))
        return INVENTORY_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun saveVideoInventory(context: Context, reference: String, inventory: Map<String, String>) {
        val json = JSONObject()
        inventory.toSortedMap().forEach { (documentId, fingerprint) -> json.put(documentId, fingerprint) }
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip -> gzip.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        val saved = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(inventoryPreferenceKey(reference), encoded)
            .commit()
        if (!saved) {
            Log.w(TAG, "SAF video inventory snapshot could not be persisted.")
        }
    }

    private fun loadVideoInventory(context: Context, reference: String): Map<String, String>? {
        val encoded = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(inventoryPreferenceKey(reference), null) ?: return null
        return runCatching {
            val compressed = Base64.decode(encoded, Base64.NO_WRAP)
            val raw = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key))
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "SAF video inventory snapshot could not be restored.", error)
        }.getOrNull()
    }

    /** Snapshot of durable changes awaiting their own tree scan. */
    fun pendingChanges(context: Context): Map<String, Long> = synchronized(lock) {
        val pending = readPendingJson(context)
        buildMap {
            val iterator = pending.keys()
            while (iterator.hasNext()) {
                val reference = iterator.next()
                val timestamp = pending.optLong(reference, 0L)
                if (reference.isNotBlank() && timestamp > 0L) put(reference, timestamp)
            }
        }
    }

    /**
     * Clear only the change that the completed scan covered. A later notification
     * updates its timestamp and remains pending for a follow-up scan.
     */
    fun completePendingChange(context: Context, reference: String, expectedTimestamp: Long): Boolean =
        synchronized(lock) {
            val pending = readPendingJson(context)
            if (pending.optLong(reference, 0L) != expectedTimestamp) return@synchronized false
            pending.remove(reference)
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PENDING_CHANGES_KEY, pending.toString())
                .commit()
        }

    private fun removePendingForUnconfiguredTrees(context: Context, configured: Set<String>) {
        val pending = readPendingJson(context)
        val toRemove = mutableListOf<String>()
        val iterator = pending.keys()
        while (iterator.hasNext()) {
            val reference = iterator.next()
            if (reference !in configured) toRemove += reference
        }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach(pending::remove)
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PENDING_CHANGES_KEY, pending.toString())
                .apply()
        }
    }

    private fun readPendingJson(context: Context): JSONObject {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PENDING_CHANGES_KEY, null)
        return runCatching { raw?.let(::JSONObject) ?: JSONObject() }
            .getOrDefault(JSONObject())
    }
}
