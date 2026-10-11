package com.reiflix.reiflix_local.domain.media

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

enum class MediaIdentificationConfidence { HIGH, MEDIUM, LOW }
enum class ParsedMediaKind { REGULAR, SPECIAL, OVA, OAD, ONA, EXTRA, MOVIE, UNKNOWN }

data class ParsedMediaName(
    val animeTitle: String,
    val season: Int?,
    val episodeNumber: Double?,
    val absoluteNumber: Int?,
    val kind: ParsedMediaKind,
    val identificationSource: String,
    val confidence: MediaIdentificationConfidence,
    val displayTitle: String,
    val extension: String,
    val evidence: List<String>,
    val unresolved: List<String>,
)

/** Offline conservative filename parsing with explicit markers ahead of numeric guesses. */
object MediaFilenameParser {
    private val seasonMarker = Regex("(?:\\bS|\\bseason[ ._-]*|\\btemporada[ ._-]*|\\btemp[ ._-]*)(\\d{1,3})(?=\\b|[ ._-]*E)", RegexOption.IGNORE_CASE)
    private val sxxexx = Regex("\\bS(\\d{1,3})[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d{1,4})(?:v\\d+)?(?=\\b|E\\d)", RegexOption.IGNORE_CASE)
    private val tempEpisode = Regex("\\b(?:temp|temporada)[ ._-]*(\\d{1,3})[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d{1,4})(?:v\\d+)?\\b", RegexOption.IGNORE_CASE)
    private val crossEpisode = Regex("\\b(\\d{1,2})\\s*[xX]\\s*(\\d{1,4})(?:v\\d+)?\\b")
    private val wordEpisode = Regex("\\b(?:E(?:P(?:ISODE)?)?|EPISODE|Epis[oó]dio)[ ._-]*(\\d{1,4})(?:v\\d+)?\\b", RegexOption.IGNORE_CASE)
    private val special = Regex("\\b(OVA|OAD|ONA|SPECIALS?|SP|EXTRA)(?:\\b|(?=\\d))[ ._-]*(\\d{1,4})?", RegexOption.IGNORE_CASE)
    private val specialZero = Regex("\\bS00(?:[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d{1,4}))?\\b", RegexOption.IGNORE_CASE)
    private val absolute = Regex("\\b(?:ABS(?:OLUTE)?|ANIME[- ._]?EP)[ ._-]*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
    private val movie = Regex("\\b(?:MOVIE|FILM)\\b", RegexOption.IGNORE_CASE)
    private val year = Regex("^(?:19\\d{2}|20\\d{2})$")
    private val filenameNumber = Regex("(?<!\\d)(\\d{1,4})(?:v\\d+)?\\b")
    private val genericFolders = setOf("anime", "videos", "video", "media", "downloads", "download", "complete", "finished", "batch", "dub", "sub")

    fun parseVideoPath(path: String, libraryRoot: String? = null): ParsedMediaName {
        val normalizedPath = path.replace('\\', '/')
        val fileName = normalizedPath.substringAfterLast('/')
        val extensionStart = fileName.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val extension = if (extensionStart >= 0) fileName.substring(extensionStart).lowercase(Locale.ROOT) else ""
        val stem = if (extensionStart >= 0) fileName.substring(0, extensionStart) else fileName
        val folders = normalizedPath.substringBeforeLast('/', "")
            .split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        val root = libraryRoot?.replace('\\', '/')?.trimEnd('/')
        val effectiveFolders = if (!root.isNullOrBlank() && normalizedPath.startsWith("$root/")) {
            normalizedPath.removePrefix("$root/").substringBeforeLast('/', "").split('/').filter(String::isNotBlank)
        } else folders
        val folderSeason = effectiveFolders.asReversed().firstNotNullOfOrNull {
            seasonMarker.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        var season = folderSeason
        var episode: Double? = null
        var absoluteNumber: Int? = null
        var kind = ParsedMediaKind.UNKNOWN
        var source = "unknown"
        var confidence = MediaIdentificationConfidence.LOW
        val evidence = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        var marker: MatchResult? = null
        val seasonZero = specialZero.find(stem)
        val specialMatch = special.find(stem)
        val absoluteMatch = absolute.find(stem)

        if (seasonZero != null) {
            marker = seasonZero
            episode = seasonZero.groupValues.getOrNull(1)?.toIntOrNull()?.toDouble()
            kind = ParsedMediaKind.SPECIAL
            source = "s00_marker"
            confidence = MediaIdentificationConfidence.HIGH
            evidence += "SPECIAL_TYPE_TOKEN:${seasonZero.value}"
        } else if (specialMatch != null) {
            marker = specialMatch
            episode = specialMatch.groupValues.getOrNull(2)?.toIntOrNull()?.toDouble()
            kind = when (specialMatch.groupValues[1].lowercase(Locale.ROOT)) {
                "ova" -> ParsedMediaKind.OVA
                "oad" -> ParsedMediaKind.OAD
                "ona" -> ParsedMediaKind.ONA
                "extra" -> ParsedMediaKind.EXTRA
                else -> ParsedMediaKind.SPECIAL
            }
            source = "special_marker"
            confidence = MediaIdentificationConfidence.HIGH
            evidence += "SPECIAL_TYPE_TOKEN:${specialMatch.value}"
        } else {
            if (absoluteMatch != null) {
                absoluteNumber = absoluteMatch.groupValues[1].toIntOrNull()
                source = "absolute_marker"
                confidence = MediaIdentificationConfidence.HIGH
                evidence += "ABSOLUTE_NUMBER_TOKEN:${absoluteMatch.value}"
            }
            val explicit = sxxexx.find(stem)
            val temporary = tempEpisode.find(stem)
            val cross = crossEpisode.find(stem)
            val word = wordEpisode.find(stem)
            when {
                explicit != null -> {
                    marker = explicit
                    season = explicit.groupValues[1].toInt()
                    episode = explicit.groupValues[2].toDouble()
                    kind = ParsedMediaKind.REGULAR
                    source = "sxxexx"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "EXPLICIT_SEASON_EPISODE:${explicit.value}"
                }
                temporary != null -> {
                    marker = temporary
                    season = temporary.groupValues[1].toInt()
                    episode = temporary.groupValues[2].toDouble()
                    kind = ParsedMediaKind.REGULAR
                    source = "temp_episode"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "EXPLICIT_SEASON_EPISODE:${temporary.value}"
                }
                cross != null -> {
                    marker = cross
                    season = cross.groupValues[1].toInt()
                    episode = cross.groupValues[2].toDouble()
                    kind = ParsedMediaKind.REGULAR
                    source = "x_episode"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "EXPLICIT_SEASON_EPISODE:${cross.value}"
                }
                word != null -> {
                    marker = word
                    episode = word.groupValues[1].toDouble()
                    season = season ?: 1
                    kind = ParsedMediaKind.REGULAR
                    source = "episode_marker"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "EXPLICIT_EPISODE_TOKEN:${word.value}"
                }
                movie.find(stem) != null -> {
                    marker = movie.find(stem)
                    kind = ParsedMediaKind.MOVIE
                    source = "movie_marker"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "MOVIE_TYPE_TOKEN:${marker?.value.orEmpty()}"
                }
                else -> {
                    val candidates = filenameNumber.findAll(stem).filter { match ->
                        val value = match.groupValues[1]
                        val before = stem.substring(0, match.range.first)
                        !year.matches(value) &&
                            !Regex("(?i)\\b${Regex.escape(value)}p\\b").containsMatchIn(stem) &&
                            value !in setOf("264", "265", "1080", "2160", "720", "480") &&
                            !Regex("(?i)\\b(?:vol(?:ume)?|disc|disk)[ ._-]*${Regex.escape(value)}\\b").containsMatchIn(stem) &&
                            before.trim(' ', '.', '_', '-', '[', ']', '(', ')', '{', '}').isNotEmpty()
                    }.toList()
                    if (candidates.isNotEmpty() && absoluteMatch == null &&
                        !stem.trim().matches(Regex("\\d{1,4}(?:v\\d+)?"))
                    ) {
                        marker = candidates.last()
                        episode = marker!!.groupValues[1].toDouble()
                        season = season ?: 1
                        kind = ParsedMediaKind.REGULAR
                        source = "numeric_suffix"
                        confidence = MediaIdentificationConfidence.MEDIUM
                        evidence += "NUMERIC_SUFFIX:${marker!!.value}"
                        if (candidates.size > 1) unresolved += "multiple_numeric_candidates"
                    }
                }
            }
            if (folderSeason != null && season != null && folderSeason != season) {
                confidence = MediaIdentificationConfidence.MEDIUM
                unresolved += "season_conflict:file=$season,folder=$folderSeason"
                evidence += "CONFLICT_FILE_SEASON_VS_FOLDER"
            }
        }

        if (kind == ParsedMediaKind.REGULAR && episode != null && episode <= 0.0) {
            kind = ParsedMediaKind.UNKNOWN
            episode = null
            source = "invalid_episode_number"
            confidence = MediaIdentificationConfidence.LOW
            unresolved += "invalid_regular_episode_number"
            evidence += "INVALID_REGULAR_EPISODE_NUMBER"
        }
        if (kind == ParsedMediaKind.REGULAR && folderSeason == 0) {
            kind = ParsedMediaKind.SPECIAL
            source = "s00_folder_context"
            confidence = MediaIdentificationConfidence.MEDIUM
            evidence += "SPECIAL_S00_FOLDER_CONTEXT"
        }
        if (kind == ParsedMediaKind.UNKNOWN && folderSeason != null) {
            evidence += "FOLDER_SEASON:$folderSeason"
            unresolved += "episode_not_identified"
        }
        if (Regex("\\bS\\d{1,3}E\\d{1,4}(?:E\\d{1,4}|-\\d{1,4})\\b", RegexOption.IGNORE_CASE).containsMatchIn(stem)) {
            unresolved += "multi_episode_range"
        }

        val titleSource = marker?.let { stem.substring(0, it.range.first) } ?: stem
        var title = MediaTitleNormalizer.cleanMediaTitle(titleSource)
        if (title.isBlank() || title.matches(Regex("(?i)(?:e|ep|episode)?\\s*\\d+"))) {
            title = effectiveFolders.asReversed().firstOrNull {
                it.lowercase(Locale.ROOT) !in genericFolders
            }?.let(MediaTitleNormalizer::cleanMediaTitle).orEmpty()
        }
        if (title.isBlank()) title = "Arquivo não identificado"

        // A number-only filename is insufficient evidence, even inside a series folder.
        if (stem.trim().matches(Regex("\\d{1,4}(?:v\\d+)?"))) {
            if (effectiveFolders.any { it.lowercase(Locale.ROOT) !in genericFolders }) {
                unresolved += "numeric_filename_without_episode_marker"
            }
            kind = ParsedMediaKind.UNKNOWN
            source = "unknown"
            confidence = MediaIdentificationConfidence.LOW
            episode = null
        }

        val tail = marker?.let { stem.substring(it.range.last + 1) }.orEmpty()
            .replace(Regex("^[ ._\\-–—]+"), "")
            .replace(Regex("\\[[^]]*]"), " ")
            .let(MediaTitleNormalizer::cleanMediaTitle)
        return ParsedMediaName(
            animeTitle = title,
            season = season,
            episodeNumber = episode,
            absoluteNumber = absoluteNumber,
            kind = kind,
            identificationSource = if (kind == ParsedMediaKind.UNKNOWN && folderSeason != null) "folder_context" else source,
            confidence = if (kind == ParsedMediaKind.UNKNOWN) MediaIdentificationConfidence.LOW else confidence,
            displayTitle = tail.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)v?\\d+")) } ?: title,
            extension = extension,
            evidence = evidence.toList(),
            unresolved = unresolved.toList(),
        )
    }
}
