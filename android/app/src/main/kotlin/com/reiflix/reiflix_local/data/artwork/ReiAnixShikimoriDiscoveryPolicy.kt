package com.reiflix.reiflix_local.data.artwork

internal const val SHIKIMORI_DISCOVERY_COOLDOWN_SECONDS = 24.0 * 60.0 * 60.0
internal const val SHIKIMORI_TRANSIENT_RETRY_DELAY_SECONDS = 15.0 * 60.0

/** Identity-aware cooldown; transient failures retry sooner than completed no-matches. */
internal fun shouldDeferShikimoriDiscoveryAttempt(
    storedIdentityFingerprint: String?,
    storedAttemptAtSeconds: Double?,
    storedOutcome: String?,
    currentIdentityFingerprint: String,
    nowSeconds: Double,
): Boolean {
    if (storedIdentityFingerprint.isNullOrBlank() ||
        storedIdentityFingerprint != currentIdentityFingerprint ||
        storedAttemptAtSeconds == null ||
        !storedAttemptAtSeconds.isFinite() ||
        !nowSeconds.isFinite()
    ) return false
    val retryDelay = if (storedOutcome == "pending" || storedOutcome == "transient") {
        SHIKIMORI_TRANSIENT_RETRY_DELAY_SECONDS
    } else {
        SHIKIMORI_DISCOVERY_COOLDOWN_SECONDS
    }
    return nowSeconds < storedAttemptAtSeconds + retryDelay
}
