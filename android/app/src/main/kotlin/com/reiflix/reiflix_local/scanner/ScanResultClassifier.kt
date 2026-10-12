package com.reiflix.reiflix_local.scanner

/**
 * Pure allow-list classification for scanner output.
 *
 * Scanner status values are external to the coordinator contract and must never
 * become success merely because they are not recognized as failures.
 */
object ScanResultClassifier {
    private const val COMPLETED = "COMPLETED"
    private const val EMPTY_COMPLETE = "EMPTY_COMPLETE"
    private const val PARTIAL = "PARTIAL"
    private const val CANCELLED = "CANCELLED"
    private const val FAILED = "FAILED"
    private const val WAITING_FOR_MEDIASTORE = "WAITING_FOR_MEDIASTORE"

    private val broadSuccess = setOf(COMPLETED, EMPTY_COMPLETE)
    private val broadPartial = setOf(PARTIAL)
    private val broadFailure = setOf(FAILED, "BLOCKED", "UNAVAILABLE", "REVOKED", "DENIED", "ERROR")

    private val mediaStoreSuccess = setOf(COMPLETED, EMPTY_COMPLETE)
    private val mediaStorePartial = setOf(PARTIAL, WAITING_FOR_MEDIASTORE)
    private val mediaStoreFailure = setOf(FAILED, "BLOCKED", "UNAVAILABLE", "REVOKED", "DENIED", "ERROR")

    private val safSuccess = setOf(COMPLETED, EMPTY_COMPLETE)
    private val safPartial = setOf(PARTIAL)
    private val safFailure = setOf(FAILED, "BLOCKED", "UNAVAILABLE", "REVOKED", "DENIED", "ERROR")

    fun classifyBroadStorage(status: String?, cancelled: Boolean, partial: Boolean): String =
        classify(status, cancelled, partial, broadSuccess, broadPartial, broadFailure)

    fun classifyMediaStore(status: String?, cancelled: Boolean, partial: Boolean): String =
        classify(status, cancelled, partial, mediaStoreSuccess, mediaStorePartial, mediaStoreFailure)

    fun classifySaf(status: String?, cancelled: Boolean = false, partial: Boolean = false): String =
        classify(status, cancelled, partial, safSuccess, safPartial, safFailure)

    private fun classify(
        rawStatus: String?,
        cancelled: Boolean,
        partial: Boolean,
        successStates: Set<String>,
        partialStates: Set<String>,
        failureStates: Set<String>,
    ): String {
        val status = rawStatus?.trim()?.uppercase().orEmpty()
        // Explicit rejection/error wins over contradictory generic booleans.
        if (status in failureStates || status.isBlank()) return FAILED
        if (status !in successStates && status !in partialStates && status != CANCELLED) return FAILED
        if (status == CANCELLED || cancelled) return CANCELLED
        if (status in partialStates || partial) return PARTIAL
        return COMPLETED
    }
}
