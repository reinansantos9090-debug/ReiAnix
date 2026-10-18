package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide native scan coordination.
 *
 * One operation per source key is allowed at a time. Different discovery sources
 * retain independent ownership, while every operation has its own immutable
 * observable snapshot. Cancellation is cooperative: scanners poll the token and
 * publish their terminal partial result before the token is released.
 */
object NativeScanController {
    private data class Token(
        val cancelled: AtomicBoolean,
        val sourceKey: String?,
        val startedAtMillis: Long,
    )

    private val tokens = ConcurrentHashMap<String, Token>()
    private val sourceOwners = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutableProgress = MutableStateFlow<Map<String, ScanProgressSnapshot>>(emptyMap())

    /** Read-only snapshots keyed by operation ID; safe for ViewModels/Compose to collect. */
    val progress: StateFlow<Map<String, ScanProgressSnapshot>> = mutableProgress

    /** Latest snapshot retained after completion for diagnostics and UI summaries. */
    fun snapshot(scanId: String): ScanProgressSnapshot? = mutableProgress.value[scanId]

    @Synchronized
    fun begin(scanId: String, sourceKey: String? = null): Boolean {
        if (scanId.isBlank() || tokens.containsKey(scanId)) return false
        if (sourceKey != null && sourceOwners.containsKey(sourceKey)) return false
        val now = System.currentTimeMillis()
        tokens[scanId] = Token(AtomicBoolean(false), sourceKey, now)
        if (sourceKey != null) sourceOwners[sourceKey] = scanId
        putSnapshot(
            ScanProgressSnapshot(
                operationId = scanId,
                source = sourceKey?.substringBefore(':')?.ifBlank { "unknown" } ?: "unknown",
                state = ScanLifecycleState.PREPARING,
                message = "Preparando varredura",
                startedAtMillis = now,
            )
        )
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_SCAN_STARTED",
            source = sourceKey?.substringBefore(':')?.ifBlank { "unknown" } ?: "unknown",
            result = ScanLifecycleState.PREPARING.name,
            scanId = scanId,
        )
        return true
    }

    fun isCancelled(scanId: String): Boolean =
        tokens[scanId]?.cancelled?.get() == true

    @Synchronized
    fun launch(scanId: String, block: suspend CoroutineScope.() -> Unit): Boolean {
        if (!tokens.containsKey(scanId) || jobs.containsKey(scanId)) return false
        if (isCancelled(scanId)) {
            complete(scanId, "CANCELLED", JSONObject().put("cancelled", true))
            finish(scanId)
            return false
        }
        updateState(scanId, ScanLifecycleState.RUNNING, message = "Varredura em execução")
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        jobs[scanId] = job
        job.start()
        return true
    }

    @Synchronized
    fun requestCancel(scanId: String, reason: String = "Solicitado pelo usuário"): Boolean {
        val token = tokens[scanId] ?: return false
        val current = snapshot(scanId) ?: return false
        if (current.state.isTerminal) return false
        token.cancelled.set(true)
        putSnapshot(
            current.copy(
                state = ScanLifecycleState.CANCELLATION_REQUESTED,
                partial = true,
                message = "Cancelamento solicitado",
                errorCategory = reason.takeIf { it.isNotBlank() },
            )
        )
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_SCAN_CANCELLATION_REQUESTED",
            severity = "WARN",
            source = current.source,
            result = ScanLifecycleState.CANCELLATION_REQUESTED.name,
            scanId = scanId,
        )
        return true
    }

    fun isRunning(sourceKey: String): Boolean =
        sourceOwners.containsKey(sourceKey)

    /** Returns the current owner for a source so aggregate refreshes can await an existing scan. */
    fun activeScanId(sourceKey: String): String? = sourceOwners[sourceKey]

    fun cancelAll(): List<String> {
        val ids = tokens.keys.toList()
        ids.forEach { requestCancel(it, "Cancelamento geral solicitado") }
        return ids
    }

    /** Merge scanner telemetry without inventing a total or a completion percentage. */
    @Synchronized
    fun updateProgress(scanId: String, payload: JSONObject) {
        val current = snapshot(scanId) ?: return
        if (current.state.isTerminal || current.state == ScanLifecycleState.CANCELLATION_REQUESTED) return
        val stats = payload.optJSONObject("stats") ?: payload
        val errors = stats.optJSONArray("errors")
        val failedQueries = stats.optLong("failedQueries", current.failures)
        val files = stats.optLong("videos", payload.optLong("videos", current.filesFound)).coerceAtLeast(current.filesFound)
        val processed = stats.optLong("processed", payload.optLong("processed", current.filesProcessed))
            .coerceAtLeast(current.filesProcessed)
        val directories = stats.optLong("directories", payload.optLong("directories", current.directoriesCompleted))
            .coerceAtLeast(current.directoriesCompleted)
        val failures = maxOf(current.failures, failedQueries, errors?.length()?.toLong() ?: 0L)
        val location = payload.optString("currentPath").takeIf { it.isNotBlank() }
            ?: payload.optString("currentDirectory").takeIf { it.isNotBlank() }
            ?: payload.optString("volumeId").takeIf { it.isNotBlank() }
            ?: current.currentLocation
        val phase = payload.optString("phase")
        putSnapshot(
            current.copy(
                state = if (current.state == ScanLifecycleState.PREPARING) ScanLifecycleState.RUNNING else current.state,
                currentLocation = location,
                filesFound = files,
                filesProcessed = processed,
                failures = failures,
                directoriesCompleted = directories,
                message = when (phase) {
                    "started" -> "Descoberta iniciada"
                    "scanning" -> "Descobrindo arquivos"
                    else -> current.message
                },
            )
        )
    }

    /**
     * Classify the scanner result. Only explicit COMPLETED/EMPTY_COMPLETE results
     * are complete; missing status, cancellation, permission loss and partial
     * discovery never qualify for destructive reconciliation.
     */
    @Synchronized
    fun complete(scanId: String, status: String, payload: JSONObject = JSONObject()) {
        val current = snapshot(scanId) ?: return
        if (current.state.isTerminal) return
        val stats = payload.optJSONObject("stats") ?: JSONObject()
        val normalized = status.trim().uppercase()
        val cancelled = isCancelled(scanId) || payload.optBoolean("cancelled") ||
            normalized == "CANCELLED" || normalized == "CANCELED"
        val state = when {
            cancelled -> ScanLifecycleState.CANCELLED
            normalized in setOf("COMPLETED", "EMPTY_COMPLETE", "SUCCEEDED", "SUCCESS") &&
                !payload.optBoolean("partial") -> ScanLifecycleState.SUCCEEDED
            normalized in setOf("PARTIAL", "WAITING_FOR_MEDIASTORE") -> ScanLifecycleState.PARTIAL
            else -> ScanLifecycleState.FAILED
        }
        val errors = readStrings(stats.optJSONArray("errors"))
        val files = stats.optLong("videos", payload.optLong("videos", current.filesFound)).coerceAtLeast(current.filesFound)
        val processed = payload.optLong("processed", stats.optLong("processed", current.filesProcessed)).coerceAtLeast(current.filesProcessed)
        val directories = stats.optLong("directories", current.directoriesCompleted).coerceAtLeast(current.directoriesCompleted)
        val failures = maxOf(current.failures, stats.optLong("failedQueries", 0L), errors.size.toLong())
        val now = System.currentTimeMillis()
        val partial = state != ScanLifecycleState.SUCCEEDED
        val unprocessed = if (partial) listOfNotNull(current.currentLocation) else emptyList()
        val result = ScanOperationResult(
            operationId = scanId,
            source = current.source,
            discoveredFiles = files,
            processedFiles = processed,
            directoriesCompleted = directories,
            failedLocations = if (failures > 0) unprocessed else emptyList(),
            errors = errors,
            unprocessedLocations = unprocessed,
            cancelled = state == ScanLifecycleState.CANCELLED,
            complete = state == ScanLifecycleState.SUCCEEDED && !partial,
            finalState = state,
            startedAtMillis = current.startedAtMillis,
            finishedAtMillis = now,
        )
        putSnapshot(
            current.copy(
                state = state,
                filesFound = files,
                filesProcessed = processed,
                failures = failures,
                directoriesCompleted = directories,
                partial = partial,
                message = when (state) {
                    ScanLifecycleState.SUCCEEDED -> if (normalized == "EMPTY_COMPLETE") "Varredura concluída: nenhum arquivo compatível" else "Varredura concluída"
                    ScanLifecycleState.PARTIAL -> "Varredura concluída parcialmente"
                    ScanLifecycleState.CANCELLED -> "Varredura cancelada"
                    ScanLifecycleState.FAILED -> "Falha na varredura"
                    else -> "Varredura finalizada"
                },
                finishedAtMillis = now,
                errorCategory = payload.optString("errorType").takeIf { it.isNotBlank() }
                    ?: normalized.takeIf { state == ScanLifecycleState.FAILED },
                result = result,
            )
        )
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_SCAN_FINISHED",
            severity = when (state) {
                ScanLifecycleState.FAILED -> "ERROR"
                ScanLifecycleState.PARTIAL, ScanLifecycleState.CANCELLED -> "WARN"
                else -> "INFO"
            },
            source = current.source,
            result = state.name,
            scanId = scanId,
            context = mapOf(
                "discovered_files" to files,
                "processed_files" to processed,
                "directories_completed" to directories,
                "failure_count" to failures,
                "duration_ms" to (now - current.startedAtMillis).coerceAtLeast(0L),
            ),
        )
    }

    @Synchronized
    fun finish(scanId: String) {
        val current = snapshot(scanId)
        if (current != null && !current.state.isTerminal) {
            val status = if (isCancelled(scanId)) "CANCELLED" else "FAILED"
            complete(
                scanId,
                status,
                JSONObject().put("cancelled", status == "CANCELLED")
                    .put("errorType", if (status == "FAILED") "MISSING_TERMINAL_RESULT" else ""),
            )
        }
        val token = tokens.remove(scanId) ?: return
        jobs.remove(scanId)
        token.sourceKey?.let { key -> sourceOwners.remove(key, scanId) }
    }

    private fun updateState(
        scanId: String,
        state: ScanLifecycleState,
        message: String,
    ) {
        val current = snapshot(scanId) ?: return
        if (!current.state.isTerminal) putSnapshot(current.copy(state = state, message = message))
    }

    private fun putSnapshot(snapshot: ScanProgressSnapshot) {
        mutableProgress.value = mutableProgress.value + (snapshot.operationId to snapshot)
    }

    private fun readStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
