package com.reianix.reianix_local.data.media

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** Pure JVM identity helpers corresponding to core/media_identity.py. */
object LocalMediaIdentity {
    private val primaryAliases = setOf("external_primary", "primary", "external")
    private const val externalStorageAuthority = "com.android.externalstorage.documents"
    private val storagePath = Regex("^/(?:storage|mnt/media_rw)/([^/]+)(?:/(.*))?$", RegexOption.IGNORE_CASE)

    fun identityFromFileUri(uri: String): String? {
        val parsed = parseUri(uri) ?: return null
        if (!parsed.scheme.equals("file", ignoreCase = true)) return null
        val match = storagePath.matchEntire(decodedPath(parsed) ?: return null) ?: return null
        val (volume, relativeRaw) = normalizeVolumeRelative(match.groupValues[1], match.groupValues[2])
        val relative = cleanRelative(relativeRaw)
        return relative.takeIf { it.isNotEmpty() }?.let { "shared:${volume}:$it" }
    }

    fun identityFromRelativePath(relativePath: String?, volumeName: String? = null): String? {
        val value = decodePercent(relativePath.orEmpty()).replace('\\', '/').trim()
        if (value.isEmpty()) return null
        val match = storagePath.matchEntire(value)
        if (match != null) {
            val (volume, relativeRaw) = normalizeVolumeRelative(match.groupValues[1], match.groupValues[2])
            val relative = cleanRelative(relativeRaw)
            return relative.takeIf { it.isNotEmpty() }?.let { "shared:${volume}:$it" }
        }
        val relative = cleanRelative(value)
        if (relative.isEmpty()) return null
        return "shared:${normalizeVolume(volumeName)}:$relative"
    }

    fun identityFromDocument(
        uri: String?,
        relativePath: String? = null,
        volumeName: String? = null,
        treeUri: String? = null,
    ): String? {
        val rawUri = uri.orEmpty()
        if (rawUri.startsWith("file://", ignoreCase = true)) {
            identityFromFileUri(rawUri)?.let { return it }
        }

        if (!treeUri.isNullOrBlank()) {
            val tree = parseUri(treeUri)
            val treePath = tree?.let(::decodedPath).orEmpty()
            val markerIndex = treePath.indexOf("/tree/")
            if (tree?.authority.equals(externalStorageAuthority, ignoreCase = true) && markerIndex >= 0) {
                val treeId = treePath.substring(markerIndex + "/tree/".length)
                val separator = treeId.indexOf(':')
                if (separator >= 0) {
                    val treeVolume = treeId.substring(0, separator)
                    val treeRoot = treeId.substring(separator + 1).trim('/')
                    val documentRelative = cleanRelative(relativePath.orEmpty())
                    val combined = listOf(treeRoot, documentRelative)
                        .filter { it.isNotEmpty() }.joinToString("/")
                    val normalized = cleanRelative(combined)
                    if (normalized.isNotEmpty()) return "shared:${normalizeVolume(treeVolume)}:$normalized"
                }
            }
        }

        if (!volumeName.isNullOrBlank()) {
            identityFromRelativePath(relativePath, volumeName)?.let { return it }
        }

        val parsed = parseUri(rawUri) ?: return null
        val authority = parsed.authority?.takeIf { it.isNotBlank() } ?: return null
        val path = decodedPath(parsed)?.takeIf { it.isNotBlank() } ?: return null
        if (!parsed.scheme.equals("content", ignoreCase = true)) return null
        val normalizedPath = cleanRelative(path)
        if (normalizedPath.isEmpty()) return null
        return "uri:${caseFold(authority)}:$normalizedPath"
    }

    /** File size, source kind and modification time are discovery metadata, not stable identity. */
    fun localMediaIdentity(
        uri: String?,
        sourceKind: String = "unknown",
        relativePath: String? = null,
        size: Long? = null,
        modifiedAt: Double? = null,
        volumeId: String? = null,
    ): String? {
        @Suppress("UNUSED_VARIABLE")
        val discoveryMetadata = listOf(sourceKind, size, modifiedAt)
        return identityFromDocument(uri, relativePath, volumeId, null)
    }

    private fun normalizeVolume(volume: String?): String {
        val value = caseFold(volume.orEmpty().trim())
        return if (value.isEmpty() || value in primaryAliases) "primary" else value
    }

    private fun normalizeVolumeRelative(volume: String, relative: String): Pair<String, String> {
        val value = volume.trim().lowercase(Locale.ROOT)
        val normalizedRelative = relative.trim('/')
        if (value == "emulated" && (normalizedRelative == "0" || normalizedRelative.startsWith("0/"))) {
            return "primary" to normalizedRelative.removePrefix("0").trimStart('/')
        }
        return normalizeVolume(volume) to normalizedRelative
    }

    private fun cleanRelative(input: String): String {
        val normalized = decodePercent(input).replace('\\', '/').trim().trimStart('/')
        if (normalized.isEmpty()) return ""
        val parts = ArrayDeque<String>()
        for (part in normalized.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return caseFold(parts.joinToString("/"))
    }

    private fun caseFold(value: String): String =
        value.lowercase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ")

    private fun parseUri(value: String): URI? = runCatching { URI(value.trim()) }.getOrNull()
    private fun decodedPath(uri: URI): String? = uri.rawPath?.let(::decodePercent)

    private fun decodePercent(value: String): String =
        runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
}

/** Exact title normalization for candidate lookup; deliberately no fuzzy matching. */
object MediaTitleNormalizer {
    fun normalizeTitle(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("[._\\-‐‑‒–—]+"), " ")
            .replace(Regex("[\\p{Punct}&&[^']]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    /** Removes release metadata while retaining the source title's casing for presentation. */
    fun cleanMediaTitle(value: String?): String {
        if (value.isNullOrBlank()) return ""
        var result = value.trim()
            .replace(Regex("^\\s*\\[[^\\]]{1,120}]\\s*"), "")
            .replace(Regex("\\[[^\\]]{0,120}]"), " ")
        for (pattern in technicalPatterns) result = pattern.replace(result, " ")
        result = recordingTimestamp.replace(result, " ")
        result = standaloneTimestamp.replace(result, " ")
        result = result.replace(Regex("\\b(?:complete|batch|season[ ._-]*pack)\\b", RegexOption.IGNORE_CASE), " ")
        result = result.replace(Regex("[._]+"), " ")
        result = result.replace(Regex("\\s+"), " ")
        return result.trim().trim(' ', '-', '_', '[', ']', '(', ')', '{', '}')
    }

    /** Comparison form of the cleaned title; never proves identity on its own. */
    fun normalizeMediaTitle(value: String?): String = normalizeTitle(cleanMediaTitle(value))

    /**
     * Search-only representations of a local title. The original spelling is always
     * retained as the first query; cleaned forms are additional candidates only and
     * must never be written back to the user's library.
     */
    fun searchTitleVariants(value: String?): List<String> {
        val original = value?.trim()?.replace(SEARCH_WHITESPACE, " ")?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val cleaned = cleanMediaTitle(original)
        val episodeCleaned = cleaned
            .replace(SEARCH_EPISODE_MARKERS, " ")
            .replace(SEARCH_SEASON_MARKERS, " ")
            .replace(SEARCH_WHITESPACE, " ")
            .trim()
        val prefixless = stripOrganizationalPrefix(episodeCleaned)
        return buildList {
            // Query the exact local spelling first, then the most useful canonical
            // form, so technical metadata and organizer folders do not crowd out
            // native/romaji/English aliases in the bounded provider query budget.
            add(original)
            add(prefixless)
            add(episodeCleaned)
            add(cleaned)
            add(foldDiacritics(prefixless))
        }.asSequence()
            .map { it.trim().replace(SEARCH_WHITESPACE, " ") }
            .filter { it.length in 2..120 }
            .distinctBy(::normalizeTitle)
            .toList()
    }

    private fun stripOrganizationalPrefix(value: String): String {
        val match = HOME_ANIMES_PREFIX.find(value) ?: return value
        return match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) ?: value
    }

    private fun foldDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD).filterNot {
            val type = Character.getType(it)
            type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
        }

    private val SEARCH_WHITESPACE = Regex("\\s+")
    private val HOME_ANIMES_PREFIX = Regex(
        """(?i)^\s*home[\s._-]+animes[\s._:|—–-]+(.+?)\s*$""",
    )
    private val SEARCH_EPISODE_MARKERS = Regex(
        """(?i)\b(?:s\d{1,3}[ ._-]*e(?:p(?:isode)?)?[ ._-]*\d{1,4}|(?:e(?:p(?:isode)?)?|episode|epis[oó]dio)[ ._-]*\d{1,4})\b""",
    )
    private val SEARCH_SEASON_MARKERS = Regex(
        """(?i)\b(?:season|temporada|temp)[ ._-]*\d{1,3}\b|\b\d{1,2}(?:st|nd|rd|th)[ ._-]*season\b""",
    )

    /**
     * Mirrors core.library_parser._episode_title_after exactly.
     *
     * This is deliberately narrower than cleanMediaTitle: the legacy parser keeps
     * ordinary words in an episode suffix (for example "Complete Batch") and only
     * strips bracket annotations and technical release tokens from that suffix.
     */
    internal fun episodeTitleAfter(stem: String, marker: MatchResult?): String? {
        if (marker == null) return null
        var tail = stem.substring(marker.range.last + 1)
            .replace(Regex("^[ ._\\-–—]+"), "")
            .replace(Regex("\\[[^]]*]"), " ")
        technicalPatterns.forEach { pattern -> tail = pattern.replace(tail, " ") }
        // Removing dotted/underscored release tokens must not leave their separators
        // as fake text in the episode title (for example: "Title.1080p.WEB-DL").
        tail = tail.replace(Regex("[._\\s]+"), " ")
        tail = tail.replace(Regex("\\s+"), " ").trim(' ', '-', '_')
        if (tail.isBlank() || Regex("(?i)v?\\d+").matches(tail)) return null
        return tail
    }

    /** Returns recognized release/technical tokens in their original spelling and order by pattern. */
    fun extractTechnicalTokens(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val found = linkedSetOf<String>()
        technicalPatterns.forEach { pattern -> pattern.findAll(value).forEach { found += it.value } }
        return found.toList()
    }

    private val technicalPatterns = listOf(
        Regex("(?<![A-Za-z0-9])(?:2160p|1080p|720p|480p|4320p|4k)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:x264|x265|h264|h\\.264|h265|h\\.265|hevc|av1|avc)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:8bit|10bit|12bit|hdr10|hdr|dv|dolby[ ._-]*vision)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:web[ ._-]*dl|webrip|bluray|bdrip|bdremux|hdtv)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:aac|ac3|eac3|flac|dts|dual[ ._-]*audio|multi[ ._-]*audio)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])(?:pt[-_ ]?br|pt|portugu[eê]s|eng|english|jpn|japanese|dub|dublado|sub|legendado)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
    )
    private val recordingTimestamp = Regex("\\b(?:recording|recorded)[ ._-]*(?:19\\d{2}|20\\d{2})\\d{4}\\s+(?:[01]?\\d|2[0-3])\\d{4}\\b", RegexOption.IGNORE_CASE)
    private val standaloneTimestamp = Regex("\\b(?:19\\d{2}|20\\d{2})\\d{4}\\s+(?:[01]?\\d|2[0-3])\\d{4}\\b")
}
