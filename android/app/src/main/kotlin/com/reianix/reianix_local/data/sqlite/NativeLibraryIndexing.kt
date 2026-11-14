package com.reianix.reianix_local.data.sqlite

import com.reianix.reianix_local.data.media.MediaTitleNormalizer

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
    data class Candidate(
        val index: Int,
        val baseTitle: String,
        val normalizedBaseTitle: String,
        val sourceFolder: String,
        val episodeNumber: Double,
    )

    val terminalNumber = Regex("^(.+?)\\s+(\\d{1,4})$")
    val candidates = items.mapIndexedNotNull { index, item ->
        if (item.identificationSource != "release_language_episode") return@mapIndexedNotNull null
        val title = item.animeTitle?.trim().orEmpty()
        val match = terminalNumber.matchEntire(title) ?: return@mapIndexedNotNull null
        val episode = item.episodeNumber ?: return@mapIndexedNotNull null
        val suffix = match.groupValues[2].toDoubleOrNull() ?: return@mapIndexedNotNull null
        val base = match.groupValues[1].trim()
        if (base.isBlank() || suffix != episode) return@mapIndexedNotNull null
        val normalizedBase = MediaTitleNormalizer.normalizeTitle(base)
        if (normalizedBase.isBlank()) return@mapIndexedNotNull null
        Candidate(
            index = index,
            baseTitle = base,
            normalizedBaseTitle = normalizedBase,
            sourceFolder = item.sourceFolder.orEmpty().trim(),
            episodeNumber = episode,
        )
    }

    // Group by the selected library scope and canonical base title, not by the
    // immediate directory. A scan can contain multiple unrelated anime in the root,
    // and one series can have episodes distributed across season subfolders.
    val replacements = HashMap<Int, String>()
    val grouped = candidates.groupBy { it.sourceFolder + "|" + it.normalizedBaseTitle }
    for (siblings in grouped.values) {
        if (siblings.size < 2) continue
        if (siblings.map { it.episodeNumber }.distinct().size < 2) continue
        siblings.forEach { replacements[it.index] = it.baseTitle }
    }

    return items.mapIndexed { index, item ->
        replacements[index]?.let { item.copy(animeTitle = it) } ?: item
    }
}


/**
 * A plain filename such as "One piece.mkv" is not proof of a movie when an
 * explicitly numbered "One Piece Dublado Episódio 03.mp4" lives beside it.
 * Promote only a single, unnumbered root-level candidate with a unique, exact
 * normalized series title in the same directory. No extension or fuzzy title
 * matching is used; explicit Movie/Film markers and unrelated titles are safe.
 */
internal fun recognizeUnnumberedSeriesSiblings(
    items: List<NativeDiscoveredMedia>,
): List<NativeDiscoveredMedia> {
    fun titleKey(value: String?): String = MediaTitleNormalizer.normalizeMediaTitle(value)
        .replace(Regex("\\b(?:dublado|legendado|dub|sub|pt br)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()
    fun folder(item: NativeDiscoveredMedia): String =
        (item.relativePath ?: item.scopeLocation)?.replace('\\', '/')
            ?.substringBeforeLast('/', "")
            ?.trim()?.lowercase()
            ?: item.sourceFolder.orEmpty().trim().lowercase()

    val seeds = items.filter {
        it.episodeNumber != null &&
            it.episodeType?.equals("regular", ignoreCase = true) == true &&
            !it.ambiguous && !it.animeTitle.isNullOrBlank() &&
            it.identificationConfidence?.lowercase() in setOf("high", "medium")
    }.groupBy { folder(it) to titleKey(it.animeTitle) }
    val candidates=items.withIndex().filter { (_, item) ->
        item.identificationSource == "standalone_root_movie" &&
            item.episodeNumber == null && item.episodeType == "movie" &&
            !item.animeTitle.isNullOrBlank() &&
            !item.fileName.contains(Regex("(?i)\\b(?:movie|film)\\b"))
    }.groupBy { (_,item) -> folder(item) to titleKey(item.animeTitle) }
    val updates = mutableMapOf<Int,NativeDiscoveredMedia>()
    for ((key, group) in candidates) {
        if(group.size != 1 || key.second.isBlank()) continue
        val sameSeries=seeds[key].orEmpty()
        if(sameSeries.isEmpty()) continue
        val seasons=sameSeries.mapNotNull { it.season }.distinct()
        if(seasons.size>1) continue
        val seed=sameSeries.first()
        updates[group.single().index] = group.single().value.copy(
            animeTitle=seed.animeTitle,
            season=seasons.singleOrNull() ?: 1,
            episodeNumber=1.0,
            episodeType="regular",
            identificationSource="verified_series_sibling",
            identificationConfidence="high",
        )
    }
    return items.mapIndexed { index,item -> updates[index] ?: item }
}
