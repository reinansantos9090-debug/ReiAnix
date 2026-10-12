package com.reiflix.reiflix_local.data.sqlite

import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer

data class NativeLibraryScanSnapshot(
    val operationId: String,
    val source: String,
    val scopeRoot: String,
    val expectedLocations: Set<String>,
    val processedLocations: Set<String>,
    val failedLocations: Set<String> = emptySet(),
    val unprocessedLocations: Set<String> = emptySet(),
    val status: String,
    val complete: Boolean,
    val cancelled: Boolean = false,
    val authorizationValid: Boolean,
    val items: List<NativeDiscoveredMedia>,
    val scopeReference: String = scopeRoot,
    val scanGeneration: Long? = null,
) {
    init { require(scanGeneration == null || scanGeneration > 0L) { "scanGeneration must be positive when supplied." } }

    val mayReconcile: Boolean
        get() {
            val terminalStatus = status.trim().uppercase()
            return operationId.isNotBlank() && source.isNotBlank() && scopeRoot.isNotBlank() &&
                expectedLocations.isNotEmpty() && processedLocations.containsAll(expectedLocations) &&
                failedLocations.isEmpty() && unprocessedLocations.isEmpty() && authorizationValid &&
                complete && !cancelled &&
                terminalStatus in setOf("COMPLETED", "EMPTY_COMPLETE", "SUCCEEDED") &&
                items.all { it.scopeVerified || (it.scopeLocation ?: it.location).isWithinScope(scopeRoot) }
        }
}

data class NativeDiscoveredMedia(
    val location: String,
    val fileName: String,
    val scopeLocation: String? = null,
    /** True only when the scanner/provider validated this document against the granted tree. */
    val scopeVerified: Boolean = false,
    val animeTitle: String? = null,
    val season: Int? = null,
    val episodeNumber: Double? = null,
    val absoluteNumber: Double? = null,
    val episodeType: String? = null,
    val episodeTitle: String? = null,
    val identificationSource: String? = null,
    val identificationConfidence: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAtEpochSeconds: Double? = null,
    val sourceFolder: String? = null,
    val relativePath: String? = null,
    val volumeId: String? = null,
    val volumeUuid: String? = null,
    val mediaIdentity: String? = null,
    val ambiguous: Boolean = false,
) {
    init {
        require(location.isNotBlank()) { "Discovered media location must not be blank." }
        require(fileName.isNotBlank()) { "Discovered media filename must not be blank." }
        require(sizeBytes == null || sizeBytes >= 0L)
        require(modifiedAtEpochSeconds == null || (modifiedAtEpochSeconds.isFinite() && modifiedAtEpochSeconds >= 0.0))
        require(episodeNumber == null || (episodeNumber.isFinite() && episodeNumber > 0.0))
        require(absoluteNumber == null || (absoluteNumber.isFinite() && absoluteNumber > 0.0))
    }

    val stableIdentity: String?
        get() = mediaIdentity?.trim()?.takeIf { it.isNotEmpty() }
            ?: location.trim().takeIf { it.isNotEmpty() }?.let { "location:$it" }

    val hasConfidentEpisodeIdentity: Boolean
        get() = !ambiguous && !animeTitle.isNullOrBlank() &&
            ((season != null && season >= 0) || absoluteNumber != null) &&
            (episodeNumber != null || absoluteNumber != null) &&
            identificationConfidence?.trim()?.lowercase() in setOf("high", "certain", "exact", "high_confidence")
}

data class NativeLibraryIndexReport(
    val operationId: String,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val ambiguous: Int,
    val rejectedOutOfScope: Int,
    val reconciledMissing: Int,
    val reconciliationAuthorized: Boolean,
)

internal fun String.isWithinScope(scopeRoot: String): Boolean {
    fun normalize(value: String) = value.trim().replace('\\', '/').trimEnd('/')
    val path = normalize(this)
    val root = normalize(scopeRoot)
    return path.isNotBlank() && root.isNotBlank() && (path == root || path.startsWith("$root/"))
}

internal fun NativeDiscoveredMedia.normalizedAnimeTitle(): String =
    MediaTitleNormalizer.normalizeTitle(animeTitle)
