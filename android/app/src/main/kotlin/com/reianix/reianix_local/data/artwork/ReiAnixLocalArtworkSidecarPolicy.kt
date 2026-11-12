package com.reianix.reianix_local.data.artwork

import java.util.Locale

internal const val MAX_LOCAL_SIDECAR_EPISODES = 64
internal const val MAX_LOCAL_SIDECARS_PER_DIRECTORY = 8
internal const val MAX_LOCAL_SIDECAR_CANDIDATES = 32
internal const val MAX_LOCAL_SIDECAR_DIRECTORY_ENTRIES = 256

/** Mirrors the bounded legacy artwork-engine naming policy for sibling image files. */
internal fun isLegacyArtworkSidecarCandidate(fileName: String, artworkType: String): Boolean {
    if (fileName.isBlank() || fileName.startsWith(".")) return false
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in setOf("jpg", "jpeg", "png", "webp", "avif", "gif")) return false
    val normalized = fileName.substringBeforeLast('.', fileName)
        .trim()
        .lowercase(Locale.ROOT)
        .replace("_", "-")
        .replace(" ", "-")
    val hints = when (artworkType.lowercase(Locale.ROOT)) {
        "poster" -> setOf("poster", "cover", "folder", "front")
        "backdrop" -> setOf("backdrop", "fanart", "banner", "background")
        else -> emptySet()
    }
    return normalized in hints
}

/** Never reconstruct an unversioned cache pointer unless it still belongs to this anime. */
internal fun shouldReconstructLegacyCoverCache(
    pointerStillMatchesAnime: Boolean,
    imageIsValid: Boolean,
    hasConflictingOwner: Boolean,
): Boolean = pointerStillMatchesAnime && imageIsValid && !hasConflictingOwner
