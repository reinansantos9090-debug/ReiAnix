package com.reiflix.reiflix_local.scanner

/**
 * Observable lifecycle for one native discovery operation.
 *
 * Counts are best-effort scanner telemetry. A missing total means that progress
 * is indeterminate; consumers must not infer completion from a percentage.
 */
enum class ScanLifecycleState {
    IDLE,
    PREPARING,
    RUNNING,
    CANCELLATION_REQUESTED,
    CANCELLED,
    SUCCEEDED,
    PARTIAL,
    FAILED;

    val isTerminal: Boolean
        get() = this == CANCELLED || this == SUCCEEDED || this == PARTIAL || this == FAILED
}

/** Immutable, UI-safe progress snapshot exposed by [NativeScanController]. */
data class ScanProgressSnapshot(
    val operationId: String,
    val source: String,
    val state: ScanLifecycleState,
    val currentLocation: String? = null,
    val filesFound: Long = 0,
    val filesProcessed: Long = 0,
    val failures: Long = 0,
    val directoriesCompleted: Long = 0,
    val totalFiles: Long? = null,
    val partial: Boolean = false,
    val message: String = "",
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val errorCategory: String? = null,
    val result: ScanOperationResult? = null,
) {
    val isIndeterminate: Boolean
        get() = totalFiles == null || totalFiles <= 0

    val completionPercent: Int?
        get() = totalFiles?.takeIf { it > 0 }?.let {
            ((filesProcessed.coerceAtMost(it) * 100L) / it).toInt()
        }

    val isComplete: Boolean
        get() = state == ScanLifecycleState.SUCCEEDED && !partial
}

/**
 * Safe hand-off to a future index/reconciliation layer. This type contains
 * discovery facts only and intentionally performs no catalogue mutation.
 */
data class ScanOperationResult(
    val operationId: String,
    val source: String,
    val discoveredFiles: Long,
    val processedFiles: Long,
    val directoriesCompleted: Long,
    val failedLocations: List<String>,
    val errors: List<String>,
    val unprocessedLocations: List<String>,
    val cancelled: Boolean,
    val complete: Boolean,
    val finalState: ScanLifecycleState,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
) {
    val durationMillis: Long
        get() = (finishedAtMillis - startedAtMillis).coerceAtLeast(0L)
}
