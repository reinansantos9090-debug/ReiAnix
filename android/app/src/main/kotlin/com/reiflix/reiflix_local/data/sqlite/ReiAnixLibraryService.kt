package com.reiflix.reiflix_local.data.sqlite

import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/**
 * Native domain service for scan-driven library mutations.
 *
 * It is deliberately backed by the application's existing canonical SQLite
 * repository. It does not own a database, a second catalog, or scan scheduling.
 * NativeScanController remains the single coordinator; NativeScanRunner remains
 * the discovery execution boundary. This service is the Kotlin entry point for
 * scan indexing, terminal scan history, volume availability, and provider-proven
 * SAF identity reconciliation.
 *
 * Each write is serialized with other scan-owned writes in this service. The
 * underlying SQLite transaction remains the atomicity boundary; no lock is held
 * while storage is traversed, while network work runs, or between scan phases.
 */
class ReiAnixLibraryService(
    private val repository: ReiAnixSqliteLibraryRepository,
) {
    private val scanMutationMutex = Mutex()
    private suspend fun <T> serializeMutation(operation: suspend () -> T): T {
        scanMutationMutex.lock()
        return try {
            operation()
        } finally {
            scanMutationMutex.unlock()
        }
    }


    suspend fun indexDiscoveredMedia(
        snapshot: NativeLibraryScanSnapshot,
        onTransactionOutcome: ((
            ReiAnixSqliteTransactionOutcome,
            NativeLibraryIndexReport?,
        ) -> Unit)? = null,
    ): NativeLibraryIndexReport = serializeMutation {
        repository.indexDiscoveredMedia(snapshot, onTransactionOutcome)
    }

    /**
     * Persist the terminal scan record only after catalog indexing has completed.
     * The caller decides ownership from the confirmed index transaction result;
     * compatibility fallback writes must not enter this native writer.
     */
    suspend fun finishNativeScanRun(
        scanId: String,
        requestId: String?,
        sourceKind: String,
        scopeKind: String,
        scopeRef: String,
        nativeGeneration: Long?,
        generationId: String?,
        status: String,
        stats: JSONObject,
        indexReport: JSONObject?,
        startedAtMillis: Long?,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ): Boolean = serializeMutation {
        repository.finishNativeScanRun(
            scanId = scanId,
            requestId = requestId,
            sourceKind = sourceKind,
            scopeKind = scopeKind,
            scopeRef = scopeRef,
            nativeGeneration = nativeGeneration,
            generationId = generationId,
            status = status,
            stats = stats,
            indexReport = indexReport,
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
        )
    }

    suspend fun recordNativeVolumeChange(payload: JSONObject): JSONObject =
        serializeMutation {
            repository.recordNativeVolumeChange(payload)
        }

    /**
     * Reconcile a SAF identity only after a provider operation established the new
     * URI. The repository verifies scope and conflicts and updates the existing
     * episode row instead of replacing it, retaining consumption/history relations.
     */
    suspend fun reconcileSafDocumentIdentityChange(
        oldUri: String,
        newUri: String,
        name: String,
        treeUri: String,
    ): JSONObject = serializeMutation {
        repository.reconcileSafDocumentIdentityChange(
            oldUri = oldUri,
            newUri = newUri,
            name = name,
            treeUri = treeUri,
        )
    }
}
