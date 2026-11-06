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
        get() {
            val confident = identificationConfidence?.trim()?.lowercase() in
                setOf("high", "certain", "exact", "high_confidence")
            if (ambiguous || animeTitle.isNullOrBlank() || !confident) return false
            // A standalone movie is a valid catalog item without an episode number.
            if (episodeType?.trim()?.equals("movie", ignoreCase = true) == true) return true
            return ((season != null && season >= 0) || absoluteNumber != null) &&
                (episodeNumber != null || absoluteNumber != null)
        }
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


/**
 * Removes a trailing title number only when sibling files in the same folder prove that
 * the number is repeated as the release episode marker for every discovered episode.
 * Seihantai na Kimi to Boku's trailing 9/10/11 can be removed as a group; the sequel
 * suffix in Yozakura-san Chi no Daisakusen 2 remains because its episode numbers differ.
 */
internal fun normalizeDuplicateReleaseEpisodeTitleSuffixes(
    items: List<NativeDiscoveredMedia>,
): List<NativeDiscoveredMedia> {
    val grouped = items.withIndex()
        .filter { (_, item) ->
            item.identificationSource == "release_language_episode" &&
                item.episodeNumber != null && !item.relativePath.isNullOrBlank()
        }
        .groupBy { (_, item) ->
            val relative = item.relativePath.orEmpty().replace('\\', '/').trim('/')
            val parent = relative.substringBeforeLast('/', "")
            item.sourceFolder.orEmpty().trim() + "|" + parent
        }
    val replacements = HashMap<Int, String>()
    val terminalNumber = Regex("^(.+?)\\s+(\\d{1,4})$")

    for (siblings in grouped.values) {
        if (siblings.size < 2) continue
        val parsed = siblings.mapNotNull { (index, item) ->
            val title = item.animeTitle?.trim().orEmpty()
            val match = terminalNumber.matchEntire(title) ?: return@mapNotNull null
            val episode = item.episodeNumber ?: return@mapNotNull null
            val suffix = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val base = match.groupValues[1].trim()
            if (base.isBlank() || suffix != episode) return@mapNotNull null
            Triple(index, base, MediaTitleNormalizer.normalizeTitle(base))
        }
        if (parsed.size != siblings.size) continue
        if (parsed.map { it.first }.distinct().size != siblings.size) continue
        if (siblings.mapNotNull { it.value.episodeNumber }.distinct().size != siblings.size) continue
        if (parsed.map { it.third }.distinct().size != 1) continue
        parsed.forEach { (index, base, _) -> replacements[index] = base }
    }

    return items.mapIndexed { index, item ->
        replacements[index]?.let { item.copy(animeTitle = it) } ?: item
    }
}
