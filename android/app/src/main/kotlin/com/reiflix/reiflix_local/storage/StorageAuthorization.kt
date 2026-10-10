package com.reiflix.reiflix_local.storage

/**
 * Single native authorization model.
 *
 * Android framework APIs remain authoritative; this model only translates
 * already-observed facts into explicit source capabilities and lifecycle state.
 */
enum class MediaAccessLevel { DENIED, PARTIAL, FULL }
enum class SafAccessLevel { UNKNOWN, AVAILABLE, REVOKED }
enum class BroadStorageAccessLevel { AVAILABLE, UNAVAILABLE }

enum class StorageLifecycleState {
    UNKNOWN, CHECKING, DENIED, PARTIAL, FULL, REQUESTING, RETURNED,
    REVALIDATED, SCAN_CAPABLE, NOT_SCAN_CAPABLE,
}

data class StorageCapabilities(
    val mediaReadState: MediaAccessLevel,
    val broadStorageState: BroadStorageAccessLevel,
    val safRoots: List<String>,
    val removableVolumes: List<String>,
    val scannerCapabilities: Set<String>,
    val reconciliationCapabilities: Set<String>,
    val lifecycleState: StorageLifecycleState,
) {
    fun canScan(source: String): Boolean = source in scannerCapabilities
    fun canReconcile(source: String): Boolean = source in reconciliationCapabilities
}

object StorageAuthorization {
    fun mediaAccess(
        apiLevel: Int,
        readExternalStorage: Boolean = false,
        readMediaVideo: Boolean = false,
        readSelectedVisualMedia: Boolean = false,
    ): MediaAccessLevel = when {
        apiLevel <= 32 ->
            if (readExternalStorage) MediaAccessLevel.FULL else MediaAccessLevel.DENIED
        apiLevel >= 34 ->
            when {
                readMediaVideo -> MediaAccessLevel.FULL
                readSelectedVisualMedia -> MediaAccessLevel.PARTIAL
                else -> MediaAccessLevel.DENIED
            }
        else ->
            if (readMediaVideo) MediaAccessLevel.FULL else MediaAccessLevel.DENIED
    }

    fun safIdentity(value: String): String? {
        // Keep this helper independent from Android framework URI parsing so the
        // same authorization model behaves identically in JVM unit tests and on
        // Android. Match Python's saf_source_identity: lowercase authority, decode
        // the tree document id once, and include the canonical "saf:" prefix.
        val raw = value.trim()
        if (!raw.startsWith("content://", ignoreCase = true)) return null
        val parsed = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        val authority = parsed.rawAuthority?.trim().orEmpty()
        val rawPath = parsed.rawPath?.trimEnd('/').orEmpty()
        val marker = "/tree/"
        val markerIndex = rawPath.indexOf(marker)
        if (authority.isBlank() || markerIndex < 0) return null
        val encodedTreeId = rawPath.substring(markerIndex + marker.length)
            .substringBefore("/")
            .trim()
        if (encodedTreeId.isBlank()) return null
        val documentId = runCatching {
            java.net.URLDecoder.decode(
                encodedTreeId.replace("+", "%2B"),
                Charsets.UTF_8.name(),
            )
        }.getOrNull()?.trim()
        if (documentId.isNullOrBlank()) return null
        return "saf:" + authority.lowercase(java.util.Locale.ROOT) + ":" + documentId
    }

    fun safAccess(configuredTreeUri: String?, persistedReadUris: Collection<String>): SafAccessLevel {
        val tree = configuredTreeUri?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return SafAccessLevel.UNKNOWN
        val expected = safIdentity(tree) ?: return SafAccessLevel.REVOKED
        return if (persistedReadUris.asSequence().mapNotNull(::safIdentity).any { it == expected }) {
            SafAccessLevel.AVAILABLE
        } else SafAccessLevel.REVOKED
    }

    fun broadAccess(hasAllFilesAccess: Boolean): BroadStorageAccessLevel =
        if (hasAllFilesAccess) BroadStorageAccessLevel.AVAILABLE else BroadStorageAccessLevel.UNAVAILABLE

    fun deriveLifecycleState(
        mediaAccess: MediaAccessLevel,
        broadAccess: BroadStorageAccessLevel,
        safRoots: Collection<String>,
    ): StorageLifecycleState {
        val scanCapable = canScanMediaStore(mediaAccess) ||
            broadAccess == BroadStorageAccessLevel.AVAILABLE ||
            safRoots.isNotEmpty()
        return when {
            scanCapable -> StorageLifecycleState.SCAN_CAPABLE
            mediaAccess == MediaAccessLevel.FULL -> StorageLifecycleState.FULL
            mediaAccess == MediaAccessLevel.PARTIAL -> StorageLifecycleState.PARTIAL
            else -> StorageLifecycleState.NOT_SCAN_CAPABLE
        }
    }

    fun capabilities(
        mediaAccess: MediaAccessLevel,
        broadAccess: BroadStorageAccessLevel,
        safRoots: Collection<String> = emptyList(),
        removableVolumes: Collection<String> = emptyList(),
        lifecycleState: StorageLifecycleState? = null,
    ): StorageCapabilities {
        val normalizedSaf = safRoots.map(String::trim).filter(String::isNotEmpty).distinct()
        val normalizedVolumes = removableVolumes.map(String::trim).filter(String::isNotEmpty).distinct()
        val scanners = linkedSetOf<String>()
        val reconciliators = linkedSetOf<String>()
        if (canScanMediaStore(mediaAccess)) scanners += "mediastore"
        if (canReconcileMediaStore(mediaAccess)) reconciliators += "mediastore"
        if (broadAccess == BroadStorageAccessLevel.AVAILABLE) {
            scanners += "broad-storage"
            reconciliators += "broad-storage"
        }
        if (normalizedSaf.isNotEmpty()) {
            scanners += "saf"
            reconciliators += "saf"
        }
        return StorageCapabilities(
            mediaReadState = mediaAccess,
            broadStorageState = broadAccess,
            safRoots = normalizedSaf,
            removableVolumes = normalizedVolumes,
            scannerCapabilities = scanners,
            reconciliationCapabilities = reconciliators,
            lifecycleState = lifecycleState ?: deriveLifecycleState(mediaAccess, broadAccess, normalizedSaf),
        )
    }

    fun canScanMediaStore(access: MediaAccessLevel): Boolean =
        access == MediaAccessLevel.FULL || access == MediaAccessLevel.PARTIAL

    fun canReconcileMediaStore(access: MediaAccessLevel): Boolean =
        access == MediaAccessLevel.FULL

    fun canScanSaf(access: SafAccessLevel): Boolean =
        access == SafAccessLevel.AVAILABLE

    fun canScanBroad(hasAllFilesAccess: Boolean): Boolean =
        hasAllFilesAccess
}
