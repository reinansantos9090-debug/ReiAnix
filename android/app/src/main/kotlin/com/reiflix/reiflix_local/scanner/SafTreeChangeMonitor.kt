package com.reiflix.reiflix_local.scanner

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
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

/**
 * Process-scoped observers for the user's configured SAF roots.
 *
 * The observers remain registered while MainActivity is backgrounded, so leaving
 * and returning to ReiAnix never creates a scan by itself. A provider notification
 * is inspected on IO; only a video file or a directory containing video files is
 * persisted as pending work. The pending URI/timestamp survives Activity recreation
 * and process death while a scan is running.
 */
object SafTreeChangeMonitor {
    private const val TAG = "[REIFLIX][SAF_CHANGE]"
    private const val PREFS_NAME = "reianix_saf_change_monitor"
    private const val PENDING_CHANGES_KEY = "pending_video_tree_changes"
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
    fun synchronize(context: Context, treeReferences: Collection<String>) {
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

            (desired - observers.keys).forEach { reference ->
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
            val relevant = runCatching {
                SafScanner.containsPotentialVideoChange(context, treeUri, changedUri)
            }.onFailure { error ->
                Log.w(TAG, "SAF change inspection failed; keeping a conservative pending signal.", error)
            }.getOrDefault(true)
            if (relevant) schedulePendingChange(context, reference)
        }
    }

    private fun schedulePendingChange(context: Context, reference: String) {
        synchronized(lock) {
            debounceTasks.remove(reference)?.let(mainHandler::removeCallbacks)
            val task = Runnable {
                val timestamp = markPendingChange(context, reference)
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
