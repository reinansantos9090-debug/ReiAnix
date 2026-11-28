package com.reianix.reianix_local.data.media

import java.util.Locale

/** Cheap, local-only routing of automatic catalog lookups. No identity confirmation
 * is added to the ordinary anime artwork path. */
internal enum class LocalCatalogLookup { NORMAL, PERSONAL, UNCERTAIN }

internal object LocalCatalogLookupPolicy {
    private val captureName = Regex(
        """^(?:(?:vid|video|img|pxl|mvi|dsc|mvimg|line|whatsapp(?:[ _.-]+video)?|screen[ _.-]*record(?:ing|er)?|recording|gravacao|gravação)[ _.-]*\d{3,}.*|(?:\d{8}|\d{4}[-_]\d{2}[-_]\d{2})[ _.-]+\d{4,}.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val generic = setOf(
        "line", "vid", "video", "videos", "img", "pxl", "mvi", "dsc",
        "whatsapp", "whatsapp video", "screen recording", "screen recorder",
        "recording", "gravacao", "gravação", "arquivo não identificado",
    )
    private val episodeMarker = Regex(
        """(?i)(?:\bS\d{1,3}[ ._-]*E\d{1,4}\b|\b(?:EP|EPISODE|EPISÓDIO|EPISODIO)[ ._-]*\d{1,4}\b)""",
    )

    private fun stem(name: String): String =
        name.substringAfterLast('/').substringBeforeLast('.', name.substringAfterLast('/')).trim()

    private fun isCapture(name: String): Boolean = captureName.matches(name.trim()) || captureName.matches(stem(name))

    fun needsEvidence(title: String, mediaKind: String?): Boolean =
        mediaKind.equals("movie", true) || isCapture(title) ||
            title.trim().lowercase(Locale.ROOT) in generic

    fun classify(title: String, mediaKind: String?, fileNames: List<String>): LocalCatalogLookup {
        if (!needsEvidence(title, mediaKind)) return LocalCatalogLookup.NORMAL
        // Explicit episodic names take priority over a coincidentally generic anime title.
        if (fileNames.any { episodeMarker.containsMatchIn(stem(it)) }) return LocalCatalogLookup.NORMAL
        if (fileNames.isNotEmpty() && fileNames.all(::isCapture)) return LocalCatalogLookup.PERSONAL
        if (fileNames.isEmpty() && isCapture(title)) return LocalCatalogLookup.PERSONAL
        return LocalCatalogLookup.UNCERTAIN
    }

    /** Applied only to uncertain entries, using titles already present in the response.
     * Whole-title equality or a multiword title contained at token boundaries is enough.
     * No extra provider requests, ranking, or confidence threshold. */
    fun allowsCandidate(mode: LocalCatalogLookup, localTitle: String, remoteTitles: Iterable<String?>): Boolean {
        if (mode == LocalCatalogLookup.NORMAL) return true
        if (mode == LocalCatalogLookup.PERSONAL) return false
        val local = MediaTitleNormalizer.normalizeTitle(localTitle)
        if (local.isBlank()) return false
        return remoteTitles.any { raw ->
            val remote = MediaTitleNormalizer.normalizeTitle(raw)
            remote.isNotBlank() && (local == remote ||
                (' ' in local && " $remote ".contains(" $local ")) ||
                (' ' in remote && " $local ".contains(" $remote ")))
        }
    }
}
