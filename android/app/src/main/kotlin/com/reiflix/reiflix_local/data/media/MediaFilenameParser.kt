package com.reiflix.reiflix_local.data.media

import java.util.Locale

enum class MediaIdentificationConfidence { HIGH, MEDIUM, LOW }
enum class ParsedMediaKind { REGULAR, SPECIAL, OVA, OAD, ONA, EXTRA, MOVIE, UNKNOWN }

enum class ParsedMediaField {
    ORIGINAL_FILE_NAME,
    BASE_NAME,
    TITLE,
    SEASON,
    EPISODE_NUMBER,
    ABSOLUTE_NUMBER,
    EXTENSION,
    TECHNICAL_METADATA,
}

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
    /** Exact leaf filename as supplied; it is never renamed or modified by parsing. */
    val originalFileName: String = "",
    /** Original filename without its final extension, retaining source casing and punctuation. */
    val baseName: String = "",
    /** Recognized technical/release tokens, retaining the original spelling. */
    val technicalTokens: List<String> = emptyList(),
    /** Whether the extracted extension is in the legacy Python parser's video allowlist. */
    val isSupportedVideoExtension: Boolean = false,
    /** Confidence for facts actually extracted; missing values are listed separately. */
    val fieldConfidence: Map<ParsedMediaField, MediaIdentificationConfidence> = emptyMap(),
    val unknownFields: Set<ParsedMediaField> = emptySet(),
    /** Uncertainty markers that can affect interpretation; a result can be partial. */
    val ambiguities: List<String> = emptyList(),
)

/** Offline conservative filename parsing with explicit markers ahead of numeric guesses. */
object MediaFilenameParser {
    /** Kept in parity with core/library_parser.py; extensions are normalized to lowercase. */
    val supportedVideoExtensions: Set<String> = setOf(
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v",
        ".ts", ".m2ts", ".flv", ".wmv",
    )

    fun supportsExtension(extension: String): Boolean =
        extension.lowercase(Locale.ROOT) in supportedVideoExtensions

    private val seasonMarker = Regex("(?:\\bS|\\bseason[ ._-]*|\\btemporada[ ._-]*|\\btemp[ ._-]*)(\\d{1,3})(?=\\b|[ ._-]*E)", RegexOption.IGNORE_CASE)
    private val sxxexx = Regex("\\bS(\\d{1,3})[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d{1,4}(?:[.,]\\d{1,2})?)(?:v\\d+)?(?=\\b|E\\d)", RegexOption.IGNORE_CASE)
    private val tempEpisode = Regex("\\b(?:temp|temporada)[ ._-]*(\\d{1,3})[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d{1,4}(?:[.,]\\d{1,2})?)(?:v\\d+)?\\b", RegexOption.IGNORE_CASE)
    private val crossEpisode = Regex("\\b(\\d{1,2})\\s*[xX]\\s*(\\d{1,4}(?:[.,]\\d{1,2})?)(?:v\\d+)?\\b")
    private val wordEpisode = Regex("\\b(?:E(?:P(?:ISODE)?)?|EPISODE|Epis[oó]dio)[ ._-]*(\\d{1,4}(?:[.,]\\d{1,2})?)(?:v\\d+)?\\b", RegexOption.IGNORE_CASE)
    private val special = Regex("\\b(OVA|OAD|ONA|SPECIALS?|SP|EXTRA)(?:\\b|(?=\\d))[ ._-]*(\\d{1,4}(?:[.,]\\d{1,2})?)?", RegexOption.IGNORE_CASE)
    private val specialZero = Regex("\\bS00(?:[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d{1,4}(?:[.,]\\d{1,2})?))?\\b", RegexOption.IGNORE_CASE)
    private val absolute = Regex("\\b(?:ABS(?:OLUTE)?|ANIME[- ._]?EP)[ ._-]*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
    private val absoluteDecimal = Regex(
        "\\b(?:ABS(?:OLUTE)?|ANIME[- ._]?EP)[ ._-]*\\d{1,4}[.,]\\d+\\b",
        RegexOption.IGNORE_CASE,
    )
    private val unsupportedEpisodeDecimal = Regex(
        "\\b(?:S\\d{1,3}[ ._-]*E(?:P(?:ISODE)?)?|(?:temp|temporada)[ ._-]*\\d{1,3}[ ._-]*E(?:P(?:ISODE)?)?|\\d{1,2}\\s*[xX]|E(?:P(?:ISODE)?)?|EPISODE|Epis[oó]dio|OVA|OAD|ONA|SPECIALS?|SP|EXTRA|S00[ ._-]*E(?:P(?:ISODE)?)?)[ ._-]*\\d{1,4}[.,]\\d{3,}(?:v\\d+)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val movie = Regex("\\b(?:MOVIE|FILM)\\b", RegexOption.IGNORE_CASE)
    private val year = Regex("^(?:19\\d{2}|20\\d{2})$")
    private val filenameNumber = Regex("(?<!\\d)(\\d{1,4}(?:[.,]\\d{1,2})?)(?:v\\d+)?\\b")
    private val numericOnlyFilename = Regex("\\d+(?:[.,]\\d+)?(?:v\\d+)?")
    private val unsupportedNumericSuffixDecimal = Regex(
        "(?<!\\d)\\d{1,4}[.,]\\d{3,}(?:v\\d+)?\\b",
    )
    private val genericFolders = setOf("anime", "videos", "video", "media", "downloads", "download", "complete", "finished", "batch", "dub", "sub")
    private val numericTechnicalFolder = setOf("264", "265", "4320", "2160", "1080", "720", "480", "8", "10", "12")
    private val seasonLabel = Regex("(?i)^(?:s|season|temporada|temp)[ ._-]*\\d{1,3}$")
    private val volumeOrDisc = Regex("\\b(?:vol(?:ume)?|disc|disk)[ ._-]*\\d{1,3}\\b", RegexOption.IGNORE_CASE)

    private fun isProbableTitleFolder(folder: String): Boolean {
        val cleaned = MediaTitleNormalizer.cleanMediaTitle(folder)
        if (cleaned.isBlank()) return false
        if (cleaned.lowercase(Locale.ROOT) in genericFolders) return false
        if (seasonMarker.find(folder) != null || seasonLabel.matches(cleaned)) return false
        if (volumeOrDisc.containsMatchIn(folder)) return false
        if (year.matches(cleaned) || cleaned in numericTechnicalFolder) return false
        return true
    }

    private fun parseEpisodeNumber(value: String?): Double? =
        value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun parseVideoPath(path: String, libraryRoot: String? = null): ParsedMediaName {
        val normalizedPath = path.replace('\\', '/')
        val fileName = normalizedPath.substringAfterLast('/')
        val extensionStart = fileName.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val extension = if (extensionStart >= 0) fileName.substring(extensionStart).lowercase(Locale.ROOT) else ""
        val stem = if (extensionStart >= 0) fileName.substring(0, extensionStart) else fileName
        val isNumericOnlyFilename = numericOnlyFilename.matches(stem.trim())
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
        val absoluteDecimalMatch = absoluteDecimal.find(stem)
        val unsupportedEpisodeDecimalMatch = unsupportedEpisodeDecimal.find(stem)

        // Reject malformed decimal identifiers before any marker family can
        // accept an integer prefix, including S00 and OVA/SP special markers.
        if (absoluteDecimalMatch != null || unsupportedEpisodeDecimalMatch != null) {
            marker = absoluteDecimalMatch ?: unsupportedEpisodeDecimalMatch
            kind = ParsedMediaKind.UNKNOWN
            confidence = MediaIdentificationConfidence.LOW
            if (absoluteDecimalMatch != null) {
                source = "invalid_absolute_decimal_number"
                evidence += "INVALID_ABSOLUTE_DECIMAL_NUMBER:${absoluteDecimalMatch.value}"
                unresolved += "absolute_number_must_be_integer"
            } else {
                source = "unsupported_episode_decimal_precision"
                evidence += "UNSUPPORTED_EPISODE_DECIMAL_PRECISION:${unsupportedEpisodeDecimalMatch!!.value}"
                unresolved += "unsupported_episode_decimal_precision"
            }
        } else if (seasonZero != null) {
            marker = seasonZero
            episode = parseEpisodeNumber(seasonZero.groupValues.getOrNull(1))
            kind = ParsedMediaKind.SPECIAL
            source = "s00_marker"
            confidence = MediaIdentificationConfidence.HIGH
            evidence += "SPECIAL_TYPE_TOKEN:${seasonZero.value}"
        } else if (specialMatch != null) {
            marker = specialMatch
            episode = parseEpisodeNumber(specialMatch.groupValues.getOrNull(2))
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
                    episode = parseEpisodeNumber(explicit.groupValues[2])
                    kind = ParsedMediaKind.REGULAR
                    source = "sxxexx"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "EXPLICIT_SEASON_EPISODE:${explicit.value}"
                }
                temporary != null -> {
                    marker = temporary
                    season = temporary.groupValues[1].toInt()
                    episode = parseEpisodeNumber(temporary.groupValues[2])
                    kind = ParsedMediaKind.REGULAR
                    source = "temp_episode"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "EXPLICIT_SEASON_EPISODE:${temporary.value}"
                }
                cross != null -> {
                    marker = cross
                    season = cross.groupValues[1].toInt()
                    episode = parseEpisodeNumber(cross.groupValues[2])
                    kind = ParsedMediaKind.REGULAR
                    source = "x_episode"
                    confidence = MediaIdentificationConfidence.HIGH
                    evidence += "EXPLICIT_SEASON_EPISODE:${cross.value}"
                }
                word != null -> {
                    marker = word
                    episode = parseEpisodeNumber(word.groupValues[1])
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
                    val unsupportedDecimalSuffix = unsupportedNumericSuffixDecimal.find(stem)
                    if (unsupportedDecimalSuffix != null && !isNumericOnlyFilename) {
                        marker = unsupportedDecimalSuffix
                        kind = ParsedMediaKind.UNKNOWN
                        source = "unsupported_numeric_suffix_decimal_precision"
                        confidence = MediaIdentificationConfidence.LOW
                        evidence += "UNSUPPORTED_NUMERIC_SUFFIX_DECIMAL_PRECISION:${unsupportedDecimalSuffix.value}"
                        unresolved += "unsupported_numeric_suffix_decimal_precision"
                    } else {
                        val candidates = filenameNumber.findAll(stem).filter { match ->
                            val value = match.groupValues[1]
                            val before = stem.substring(0, match.range.first)
                            val decimalContinuation = (before.endsWith(".") || before.endsWith(",")) &&
                                (before.dropLast(1).lastOrNull()?.isDigit() == true)
                            val technicalNumber = value in setOf("264", "265", "4320", "2160", "1080", "720", "480", "8", "10", "12")
                            val numberFollowsTechnicalMarker =
                                Regex("(?i)(?:x|h\\.?|codec|bit)\\s*${Regex.escape(value)}\\b").containsMatchIn(stem)
                            !decimalContinuation &&
                                !year.matches(value) &&
                                !Regex("(?i)(?<!\\d)${Regex.escape(value)}p\\b").containsMatchIn(stem) &&
                                !(technicalNumber && numberFollowsTechnicalMarker) &&
                                value !in setOf("264", "265", "1080", "2160", "720", "480") &&
                                !volumeOrDisc.containsMatchIn(stem) &&
                                !Regex("(?i)\\b(?:vol(?:ume)?|disc|disk)\\s*$").containsMatchIn(before) &&
                                before.trim(' ', '.', '_', '-', '[', ']', '(', ')', '{', '}').isNotEmpty()
                        }.toList()
                        if (candidates.isNotEmpty() && absoluteMatch == null && absoluteDecimalMatch == null &&
                            unsupportedEpisodeDecimalMatch == null && !isNumericOnlyFilename
                        ) {
                            marker = candidates.last()
                            episode = parseEpisodeNumber(marker!!.groupValues[1])
                            season = season ?: 1
                            kind = ParsedMediaKind.REGULAR
                            source = "numeric_suffix"
                            confidence = MediaIdentificationConfidence.MEDIUM
                            evidence += "NUMERIC_SUFFIX:${marker!!.value}"
                            if (candidates.size > 1) unresolved += "multiple_numeric_candidates"
                        }
                    }
                }
            }
            if ((explicit != null || temporary != null) && folderSeason != null && season != null && folderSeason != season) {
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
        if (title.isBlank() || isNumericOnlyFilename ||
            title.matches(Regex("(?i)(?:e|ep|episode)?\\s*\\d+(?:[.,]\\d{1,2})?")) ||
            seasonLabel.matches(title)
        ) {
            title = effectiveFolders.asReversed().firstOrNull(::isProbableTitleFolder)
                ?.let(MediaTitleNormalizer::cleanMediaTitle).orEmpty()
        }
        if (folderSeason != null && kind == ParsedMediaKind.UNKNOWN) {
            val folderTitle = effectiveFolders.asReversed().firstOrNull(::isProbableTitleFolder)
                ?.let(MediaTitleNormalizer::cleanMediaTitle).orEmpty()
            if (folderTitle.isNotBlank()) title = folderTitle
        }
        if (title.isBlank()) title = "Arquivo não identificado"

        // A number-only filename is insufficient evidence, even inside a series folder.
        if (isNumericOnlyFilename) {
            if (effectiveFolders.any(::isProbableTitleFolder)) {
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
        val technicalTokens = MediaTitleNormalizer.extractTechnicalTokens(stem)
        val fieldConfidence = linkedMapOf<ParsedMediaField, MediaIdentificationConfidence>()
        if (fileName.isNotEmpty()) fieldConfidence[ParsedMediaField.ORIGINAL_FILE_NAME] = MediaIdentificationConfidence.HIGH
        if (stem.isNotEmpty()) fieldConfidence[ParsedMediaField.BASE_NAME] = MediaIdentificationConfidence.HIGH
        if (title != "Arquivo não identificado") {
            fieldConfidence[ParsedMediaField.TITLE] =
                if (marker != null && titleSource.isNotBlank()) confidence else MediaIdentificationConfidence.LOW
        }
        if (season != null) {
            fieldConfidence[ParsedMediaField.SEASON] = when {
                source == "folder_context" -> MediaIdentificationConfidence.LOW
                folderSeason != null && marker == null -> MediaIdentificationConfidence.LOW
                else -> confidence
            }
        }
        if (episode != null) fieldConfidence[ParsedMediaField.EPISODE_NUMBER] = confidence
        if (absoluteNumber != null) fieldConfidence[ParsedMediaField.ABSOLUTE_NUMBER] = MediaIdentificationConfidence.HIGH
        if (extension.isNotEmpty()) fieldConfidence[ParsedMediaField.EXTENSION] = MediaIdentificationConfidence.HIGH
        if (technicalTokens.isNotEmpty()) fieldConfidence[ParsedMediaField.TECHNICAL_METADATA] = MediaIdentificationConfidence.HIGH
        val unknownFields = buildSet {
            if (title == "Arquivo não identificado") add(ParsedMediaField.TITLE)
            if (season == null && kind != ParsedMediaKind.MOVIE) add(ParsedMediaField.SEASON)
            if (episode == null && kind != ParsedMediaKind.MOVIE) add(ParsedMediaField.EPISODE_NUMBER)
            if (extension.isEmpty()) add(ParsedMediaField.EXTENSION)
        }
        val ambiguities = unresolved.filter { item ->
            item.startsWith("season_conflict:") ||
                item == "multiple_numeric_candidates" ||
                item == "multi_episode_range" ||
                item == "absolute_number_must_be_integer" ||
                item == "unsupported_episode_decimal_precision"
        }
        return ParsedMediaName(
            animeTitle = title,
            season = season,
            episodeNumber = episode,
            absoluteNumber = absoluteNumber,
            kind = kind,
            identificationSource = if (
                kind == ParsedMediaKind.UNKNOWN &&
                folderSeason != null &&
                source == "unknown"
            ) "folder_context" else source,
            confidence = if (kind == ParsedMediaKind.UNKNOWN) MediaIdentificationConfidence.LOW else confidence,
            displayTitle = tail.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)v?\\d+")) } ?: title,
            extension = extension,
            evidence = evidence.toList(),
            unresolved = unresolved.toList(),
            originalFileName = fileName,
            baseName = stem,
            technicalTokens = technicalTokens,
            isSupportedVideoExtension = supportsExtension(extension),
            fieldConfidence = fieldConfidence.toMap(),
            unknownFields = unknownFields,
            ambiguities = ambiguities,
        )
    }
}
