package com.reiflix.reiflix_local.data.anilist

import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * Native port of core/organizer_ai.py candidate ranking.
 *
 * Scoring is deliberately local: it never makes requests or writes associations.
 * Automatic association remains gated by the same title-score and margin thresholds.
 */
internal object ReiAnixAniListMatcher {
    const val MINIMUM_SCORE = 0.48
    const val AUTO_CONFIRM_SCORE = 0.86
    const val AUTO_CONFIRM_MARGIN = 0.05

    data class Context(
        val year: Int? = null,
        val mediaKind: String? = null,
        val episodeType: String? = null,
    )

    data class RankedCandidate(
        val anime: AniListAnime,
        val score: Double,
        val titleKind: String,
    )

    fun rank(
        localTitle: String,
        candidates: List<AniListAnime>,
        context: Context = Context(),
    ): List<RankedCandidate> = candidates.map { candidate ->
        val (score, kind) = candidateScore(localTitle, candidate, context)
        RankedCandidate(candidate, score, kind)
    }.sortedWith(
        compareByDescending<RankedCandidate> { it.score }
            .thenBy { (it.anime.titleEnglish ?: it.anime.titleRomaji ?: "").lowercase(Locale.ROOT) }
            .thenBy { it.anime.id },
    )

    private fun candidateScore(
        localTitle: String,
        candidate: AniListAnime,
        context: Context,
    ): Pair<Double, String> {
        val local = baseNormalize(localTitle)
        val titleValues = buildList {
            candidate.titleEnglish?.takeIf(String::isNotBlank)?.let { add("english" to it) }
            candidate.titleRomaji?.takeIf(String::isNotBlank)?.let { add("romaji" to it) }
            candidate.titleNative?.takeIf(String::isNotBlank)?.let { add("native" to it) }
            candidate.synonyms.filter(String::isNotBlank).forEach { add("synonym" to it) }
        }
        var best = 0.0
        var bestKind = ""
        titleValues.forEach { (kind, value) ->
            val score = tokenScore(local, baseNormalize(value))
            if (score > best) {
                best = score
                bestKind = kind
            }
        }

        val expectedFormat = expectedFormat(context)
        val candidateFormat = normalizeFormat(candidate.format)
        if (!expectedFormat.isNullOrBlank() && candidateFormat.isNotBlank()) {
            best += when {
                expectedFormat == candidateFormat -> 0.08
                expectedFormat in SERIES_FORMATS && candidateFormat in SERIES_FORMATS -> -0.02
                else -> -0.18
            }
        }

        val localSeason = extractSeason(localTitle)
        val candidateSeason = titleValues.firstNotNullOfOrNull { (_, value) -> extractSeason(value) }
        if (localSeason != null && candidateSeason != null) {
            best += if (localSeason == candidateSeason) 0.07 else -0.12
        }

        val localYear = context.year ?: extractYear(localTitle)
        val remoteYear = candidate.seasonYear
        if (localYear != null && remoteYear != null) {
            best += if (remoteYear == localYear) 0.04 else -0.03
        }

        return best.coerceIn(0.0, 1.0).roundToScore() to bestKind
    }

    private fun tokenScore(local: String, remote: String): Double {
        if (local.isEmpty() || remote.isEmpty()) return 0.0
        if (local == remote) return 1.0
        if (local.contains(remote) || remote.contains(local)) {
            val containment = minOf(local.length, remote.length).toDouble() / maxOf(local.length, remote.length)
            return 0.78 + 0.18 * containment
        }
        val localTokens = local.split(WHITESPACE).filter(String::isNotBlank).toSet()
        val remoteTokens = remote.split(WHITESPACE).filter(String::isNotBlank).toSet()
        val union = localTokens union remoteTokens
        val overlap = if (union.isEmpty()) 0.0 else (localTokens intersect remoteTokens).size.toDouble() / union.size
        return maxOf(sequenceMatcherRatio(local, remote), overlap)
    }

    /**
     * Ratcliff/Obershelp longest-common-block ratio equivalent to difflib.SequenceMatcher
     * for title-sized strings. The explicit stack avoids deep recursion on adversarial text.
     */
    private fun sequenceMatcherRatio(left: String, right: String): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        data class Window(val leftStart: Int, val leftEnd: Int, val rightStart: Int, val rightEnd: Int)
        val pending = ArrayDeque<Window>()
        pending.addLast(Window(0, left.length, 0, right.length))
        var matches = 0
        while (pending.isNotEmpty()) {
            val window = pending.removeLast()
            if (window.leftStart >= window.leftEnd || window.rightStart >= window.rightEnd) continue
            val width = window.rightEnd - window.rightStart
            var previous = IntArray(width + 1)
            var bestLength = 0
            var bestLeft = window.leftStart
            var bestRight = window.rightStart
            for (i in window.leftStart until window.leftEnd) {
                val current = IntArray(width + 1)
                for (offset in 1..width) {
                    if (left[i] == right[window.rightStart + offset - 1]) {
                        current[offset] = previous[offset - 1] + 1
                        if (current[offset] > bestLength) {
                            bestLength = current[offset]
                            bestLeft = i - bestLength + 1
                            bestRight = window.rightStart + offset - bestLength
                        }
                    }
                }
                previous = current
            }
            if (bestLength == 0) continue
            matches += bestLength
            pending.addLast(
                Window(
                    bestLeft + bestLength,
                    window.leftEnd,
                    bestRight + bestLength,
                    window.rightEnd,
                ),
            )
            pending.addLast(
                Window(
                    window.leftStart,
                    bestLeft,
                    window.rightStart,
                    bestRight,
                ),
            )
        }
        return 2.0 * matches / (left.length + right.length)
    }

    private fun baseNormalize(value: String): String {
        var result = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .filterNot { Character.getType(it) in COMBINING_MARKS }
            .lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace("ς", "σ")
        result = result.replace(LEADING_BRACKET_GROUP, " ")
            .replace(BRACKET_GROUP, " ")
            .replace(EPISODE_MARKERS, " ")
            .replace(SEASON_MARKERS, " ")
            .replace(ORDINAL_SEASON_MARKERS, " ")
            .replace(TECHNICAL_MARKERS, " ")
            .replace(YEAR_MARKERS, " ")
        result = buildString(result.length) {
            result.forEach { character ->
                if (isPunctuationOrSymbol(character)) append(' ') else append(character)
            }
        }
        return result.split(WHITESPACE).filter(String::isNotBlank).joinToString(" ")
    }

    private fun isPunctuationOrSymbol(character: Char): Boolean {
        val type = Character.getType(character)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.MATH_SYMBOL.toInt() ||
            type == Character.CURRENCY_SYMBOL.toInt() ||
            type == Character.MODIFIER_SYMBOL.toInt() ||
            type == Character.OTHER_SYMBOL.toInt()
    }

    private fun expectedFormat(context: Context): String? = when (
        (context.episodeType ?: context.mediaKind.orEmpty()).lowercase(Locale.ROOT)
            .replace('-', '_')
            .trim()
    ) {
        "movie", "film" -> "MOVIE"
        "ova" -> "OVA"
        "ona" -> "ONA"
        "special", "extra" -> "SPECIAL"
        else -> null
    }

    private fun normalizeFormat(value: String?): String =
        when (value.orEmpty().lowercase(Locale.ROOT).replace('-', '_').trim()) {
            "tv" -> "TV"
            "tv_short" -> "TV_SHORT"
            "movie" -> "MOVIE"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special" -> "SPECIAL"
            "music" -> "MUSIC"
            else -> value.orEmpty().uppercase(Locale.ROOT).replace('-', '_').trim()
        }

    private fun extractSeason(value: String): Int? =
        SEASON_MARKER_WITH_NUMBER.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: ORDINAL_SEASON_MARKER_WITH_NUMBER.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun extractYear(value: String): Int? =
        YEAR_IN_TITLE.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun Double.roundToScore(): Double = kotlin.math.round(this * 1000.0) / 1000.0

    private val SERIES_FORMATS = setOf("TV", "TV_SHORT", "ONA")
    private val WHITESPACE = Regex("\\s+")
    private val COMBINING_MARKS = setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )
    private val LEADING_BRACKET_GROUP = Regex("^\\s*\\[[^\\]]{1,120}]\\s*")
    private val BRACKET_GROUP = Regex("\\[[^\\]]{0,120}]")
    private val EPISODE_MARKERS = Regex(
        "\\b(?:s\\d{1,3}[ ._-]*e(?:p(?:isode)?)?[ ._-]*\\d{1,4}|e(?:p(?:isode)?)?[ ._-]*\\d{1,4}|episode[ ._-]*\\d{1,4}|epis[oó]dio[ ._-]*\\d{1,4})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_MARKERS = Regex(
        "\\b(?:s|season|temporada|temp)[ ._-]*(\\d{1,3})(?:st|nd|rd|th)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SEASON_MARKER_WITH_NUMBER = Regex(
        "\\b(?:s|season|temporada|temp)[ ._-]*(\\d{1,3})(?:st|nd|rd|th)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ORDINAL_SEASON_MARKERS = Regex(
        "\\b(\\d{1,2})(?:st|nd|rd|th)?[ ._-]*season\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ORDINAL_SEASON_MARKER_WITH_NUMBER = ORDINAL_SEASON_MARKERS
    private val TECHNICAL_MARKERS = Regex(
        "\\b(?:2160p|1080p|720p|480p|4320p|4k|x264|x265|h264|h\\.264|h265|h\\.265|hevc|av1|avc|" +
            "8bit|10bit|12bit|hdr10|hdr|dv|dolby[ ._-]*vision|web[ ._-]*dl|webrip|bluray|bdrip|bdremux|hdtv|" +
            "aac|ac3|eac3|flac|dts|dual[ ._-]*audio|multi[ ._-]*audio|pt[-_ ]?br|portugu[eê]s|eng|english|" +
            "jpn|japanese|dub|dublado|sub|legendado|softsub|hardsub|ass|srt)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val YEAR_MARKERS = Regex("\\b(?:19\\d{2}|20\\d{2})\\b")
    private val YEAR_IN_TITLE = Regex("\\b((?:19\\d{2}|20\\d{2}))\\b")
}
