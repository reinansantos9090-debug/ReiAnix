package com.reiflix.reiflix_local.data.catalog

import java.text.Normalizer
import java.util.Locale

data class ReiAnixCatalogIdentity(
    val titles: Set<String>,
    val releaseYear: Int?,
    val format: String?,
    val mediaKind: ReiAnixCatalogMediaKind?,
    /** Calendar season (WINTER/SPRING/SUMMER/FALL), not sequel number. */
    val season: String? = null,
    /** Explicit sequel/part number, populated only when the source proves it. */
    val seasonNumber: Int? = null,
    val episodes: Int? = null,
) {
    init {
        require(titles.any { it.isNotBlank() })
        require(releaseYear == null || releaseYear in 1900..2200)
        require(seasonNumber == null || seasonNumber > 0)
        require(episodes == null || episodes >= 0)
    }
}

enum class ReiAnixCatalogIdentityDecision {
    CONFIRMED,
    PROBABLE,
    AMBIGUOUS,
    REJECTED,
    UNRESOLVED,
}

/** A probable/rejected evaluation never carries a persistable match. */
data class ReiAnixCatalogIdentityEvaluation<T>(
    val decision: ReiAnixCatalogIdentityDecision,
    val value: T? = null,
    val reason: String,
) {
    init {
        require((decision == ReiAnixCatalogIdentityDecision.CONFIRMED) == (value != null))
        require(reason.isNotBlank())
    }
}

class ReiAnixCatalogIdentityMatch internal constructor(
    val localAnimeId: Long,
    val source: ReiAnixCatalogSource,
    val externalIds: ReiAnixExternalCatalogIds,
    internal val localIdentityFingerprint: String,
    val matchedTitle: String,
) {
    init {
        require(localAnimeId > 0L)
        require(!source.resolvesIdentifiers)
        require(externalIds.idFor(source).isNullOrBlank().not())
    }
}

object ReiAnixCatalogIdentityMatcher {
    /**
     * Assessment is deliberately exact-title only. Fuzzy similarity is not identity evidence:
     * it can be useful for discovery/UI ranking, but cannot authorize metadata or artwork.
     */
    fun evaluate(
        localAnimeId: Long,
        local: ReiAnixCatalogIdentity,
        remote: ReiAnixCatalogAnime,
        knownIds: ReiAnixExternalCatalogIds = ReiAnixExternalCatalogIds(),
    ): ReiAnixCatalogIdentityEvaluation<ReiAnixCatalogIdentityMatch> {
        if (localAnimeId <= 0L || remote.source.resolvesIdentifiers ||
            remote.ids.idFor(remote.source).isNullOrBlank()
        ) return rejected("invalid_local_id_or_remote_source_id")

        val mergedIds = runCatching { knownIds.mergeVerified(remote.ids) }.getOrElse {
            return rejected("conflicting_previously_verified_identifiers")
        }
        val exactTitle = findExactTitle(local.titles, remote.alternativeTitles + remote.title)
            ?: return rejected("no_discriminative_exact_title_match")

        val evidence = evaluateCompatibleEvidence(
            local = local,
            releaseYear = remote.releaseYear,
            format = remote.format,
            mediaKind = remote.mediaKind,
            season = remote.season,
            seasonNumber = remote.seasonNumber,
            episodes = remote.episodes,
        )
        if (evidence.rejectionReason != null) return rejected(evidence.rejectionReason)

        // Fetching a record through the exact, previously-confirmed provider ID is an identity
        // anchor. All metadata contradictions are still rejected above.
        val sourceIdIsAnchored =
            knownIds.idFor(remote.source) != null &&
                knownIds.idFor(remote.source) == remote.ids.idFor(remote.source)

        if (!sourceIdIsAnchored && evidence.matchedDimensions < MINIMUM_INDEPENDENT_DIMENSIONS) {
            return probable("exact_title_but_insufficient_independent_metadata")
        }

        return confirmed(
            ReiAnixCatalogIdentityMatch(
                localAnimeId = localAnimeId,
                source = remote.source,
                externalIds = mergedIds,
                localIdentityFingerprint = fingerprint(local),
                matchedTitle = exactTitle,
            ),
            if (sourceIdIsAnchored) "confirmed_by_existing_provider_id_and_exact_title"
            else "confirmed_by_exact_title_and_" + evidence.matchedDimensions + "_independent_dimensions",
        )
    }

    /** Compatibility wrapper retained for existing callers; only confirmed results are returned. */
    fun verify(
        localAnimeId: Long,
        local: ReiAnixCatalogIdentity,
        remote: ReiAnixCatalogAnime,
        knownIds: ReiAnixExternalCatalogIds = ReiAnixExternalCatalogIds(),
    ): ReiAnixCatalogIdentityMatch? =
        evaluate(localAnimeId, local, remote, knownIds).value

    /**
     * AniMap is trusted only as a crosswalk anchored by at least one exact ID already stored for
     * this local anime. Missing remote metadata is neutral; contradictory metadata rejects.
     */
    fun evaluateMapping(
        localAnimeId: Long,
        local: ReiAnixCatalogIdentity,
        mapping: ReiAnixCatalogIdentifierMapping,
        knownIds: ReiAnixExternalCatalogIds,
    ): ReiAnixCatalogIdentityEvaluation<ReiAnixCatalogIdMappingMatch> {
        if (localAnimeId <= 0L || !mapping.ids.hasAnyId || !knownIds.hasAnyId) {
            return rejected("crosswalk_has_no_valid_identifier_anchor")
        }
        if (!sharesVerifiedIdentifier(knownIds, mapping.ids)) {
            return rejected("crosswalk_does_not_contain_the_requested_verified_identifier")
        }
        val mergedIds = runCatching { knownIds.mergeVerified(mapping.ids) }.getOrElse {
            return rejected("crosswalk_conflicts_with_previously_verified_identifiers")
        }
        val exactTitle = findExactTitle(local.titles, mapping.alternativeTitles + mapping.title)
            ?: return rejected("crosswalk_has_no_discriminative_exact_title_match")

        val evidence = evaluateCompatibleEvidence(
            local = local,
            releaseYear = mapping.releaseYear,
            format = mapping.format,
            mediaKind = mapping.mediaKind,
            season = mapping.season,
            seasonNumber = null,
            episodes = mapping.episodes,
        )
        if (evidence.rejectionReason != null) return rejected(evidence.rejectionReason)

        return confirmed(
            ReiAnixCatalogIdMappingMatch(
                localAnimeId = localAnimeId,
                externalIds = mergedIds,
                localIdentityFingerprint = fingerprint(local),
                matchedTitle = exactTitle,
            ),
            "crosswalk_id_anchor_and_exact_title",
        )
    }

    /** Compatibility wrapper retained for any existing source-level callers. */
    fun verifyMapping(
        localAnimeId: Long,
        local: ReiAnixCatalogIdentity,
        mapping: ReiAnixCatalogIdentifierMapping,
        knownIds: ReiAnixExternalCatalogIds,
    ): ReiAnixCatalogIdMappingMatch? =
        evaluateMapping(localAnimeId, local, mapping, knownIds).value

    private data class EvidenceEvaluation(
        val matchedDimensions: Int,
        val rejectionReason: String? = null,
    )

    /**
     * Fields absent from either side are unknown, not contradictory. When both sides provide a
     * field, disagreement is a hard rejection. Search-only matches need two independent groups;
     * format and media kind form one group because they describe the same dimension.
     */
    private fun evaluateCompatibleEvidence(
        local: ReiAnixCatalogIdentity,
        releaseYear: Int?,
        format: String?,
        mediaKind: ReiAnixCatalogMediaKind?,
        season: String?,
        seasonNumber: Int?,
        episodes: Int?,
    ): EvidenceEvaluation {
        if (local.releaseYear != null && releaseYear != null && local.releaseYear != releaseYear) {
            return EvidenceEvaluation(0, "release_year_conflict")
        }

        val localFormat = normalizeCatalogFormat(local.format)
        val remoteFormat = normalizeCatalogFormat(format)
        if (localFormat.isNotEmpty() && remoteFormat.isNotEmpty() && localFormat != remoteFormat) {
            return EvidenceEvaluation(0, "format_conflict")
        }

        val localKind = local.mediaKind?.takeIf { it != ReiAnixCatalogMediaKind.UNKNOWN }
        val remoteKind = mediaKind?.takeIf { it != ReiAnixCatalogMediaKind.UNKNOWN }
        if (localKind != null && remoteKind != null && localKind != remoteKind) {
            return EvidenceEvaluation(0, "media_kind_conflict")
        }

        if (local.seasonNumber != null && seasonNumber != null &&
            local.seasonNumber != seasonNumber
        ) return EvidenceEvaluation(0, "sequel_season_conflict")

        val localCalendarSeason = normalizeCalendarSeason(local.season)
        val remoteCalendarSeason = normalizeCalendarSeason(season)
        if (localCalendarSeason != null && remoteCalendarSeason != null &&
            localCalendarSeason != remoteCalendarSeason
        ) return EvidenceEvaluation(0, "calendar_season_conflict")

        val localEpisodes = local.episodes?.takeIf { it > 0 }
        val remoteEpisodes = episodes?.takeIf { it > 0 }
        if (localEpisodes != null && remoteEpisodes != null && localEpisodes != remoteEpisodes) {
            return EvidenceEvaluation(0, "episode_count_conflict")
        }

        var dimensions = 0
        if (local.releaseYear != null && releaseYear != null) dimensions++
        if ((localFormat.isNotEmpty() && remoteFormat.isNotEmpty()) ||
            (localKind != null && remoteKind != null)
        ) dimensions++
        if ((local.seasonNumber != null && seasonNumber != null) ||
            (localCalendarSeason != null && remoteCalendarSeason != null)
        ) dimensions++
        if (localEpisodes != null && remoteEpisodes != null) dimensions++
        return EvidenceEvaluation(dimensions)
    }

    private fun findExactTitle(localTitles: Set<String>, remoteTitles: Set<String>): String? {
        val normalizedLocal = localTitles.map(::normalizeCatalogTitle)
            .filter(::isDiscriminativeTitle).toSet()
        val normalizedRemote = remoteTitles.map(::normalizeCatalogTitle)
            .filter(::isDiscriminativeTitle).toSet()
        return (normalizedLocal intersect normalizedRemote).sorted().firstOrNull()
    }

    private fun isDiscriminativeTitle(normalized: String): Boolean {
        if (normalized.length < 3 || normalized.all(Char::isDigit)) return false
        val words = normalized.split(' ').filter(String::isNotBlank)
        return !(words.size == 1 && normalized in GENERIC_TITLES)
    }

    private fun sharesVerifiedIdentifier(
        known: ReiAnixExternalCatalogIds,
        mapped: ReiAnixExternalCatalogIds,
    ): Boolean {
        if (known.aniListId != null && known.aniListId == mapped.aniListId) return true
        if (known.myAnimeListId != null && known.myAnimeListId == mapped.myAnimeListId) return true
        if (known.kitsuId != null && known.kitsuId == mapped.kitsuId) return true
        if (known.aniDbId != null && known.aniDbId == mapped.aniDbId) return true
        return known.additionalIds.any { (catalog, id) -> mapped.additionalIds[catalog] == id }
    }

    private fun <T> confirmed(
        value: T,
        reason: String,
    ) = ReiAnixCatalogIdentityEvaluation(
        decision = ReiAnixCatalogIdentityDecision.CONFIRMED,
        value = value,
        reason = reason,
    )

    private fun <T> probable(reason: String) = ReiAnixCatalogIdentityEvaluation<T>(
        decision = ReiAnixCatalogIdentityDecision.PROBABLE,
        reason = reason,
    )

    private fun <T> rejected(reason: String) = ReiAnixCatalogIdentityEvaluation<T>(
        decision = ReiAnixCatalogIdentityDecision.REJECTED,
        reason = reason,
    )

    internal fun fingerprint(identity: ReiAnixCatalogIdentity): String = buildString {
        append(identity.titles.map(::normalizeCatalogTitle).filter(String::isNotEmpty).distinct().sorted().joinToString("|"))
        append('\u001f').append(identity.releaseYear ?: "")
        append('\u001f').append(normalizeCatalogFormat(identity.format))
        append('\u001f').append(identity.mediaKind?.name.orEmpty())
        append('\u001f').append(normalizeCalendarSeason(identity.season).orEmpty())
        append('\u001f').append(identity.seasonNumber ?: "")
        append('\u001f').append(identity.episodes ?: "")
    }

    /**
     * Accent-fold Latin transliteration while retaining Japanese dakuten/handakuten. The previous
     * NFKD + remove-all-marks implementation collapsed distinct kana (for example が and か),
     * creating a real collision risk. NFKD also handles full-width and compatibility forms.
     */
    fun normalizeCatalogTitle(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        val eastAsianScripts = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.BOPOMOFO,
        )
        val folded = buildString(decomposed.length) {
            var offset = 0
            var previousBaseScript: Character.UnicodeScript? = null
            while (offset < decomposed.length) {
                val codePoint = decomposed.codePointAt(offset)
                val type = Character.getType(codePoint)
                val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt()
                if (!isMark || previousBaseScript?.let { it in eastAsianScripts } == true) {
                    appendCodePoint(codePoint)
                }
                if (!isMark) previousBaseScript = Character.UnicodeScript.of(codePoint)
                offset += Character.charCount(codePoint)
            }
        }
        return Normalizer.normalize(folded, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace("ς", "σ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun normalizeCatalogFormat(value: String?): String {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
        if (normalized in UNKNOWN_FORMAT_VALUES) return ""
        return when (normalized) {
            "tv series", "series", "anime" -> "tv"
            "film" -> "movie"
            "tv short", "short tv" -> "tv short"
            else -> normalized
        }
    }

    private fun normalizeCalendarSeason(value: String?): String? =
        when (value.orEmpty().trim().uppercase(Locale.ROOT)) {
            "WINTER" -> "winter"
            "SPRING" -> "spring"
            "SUMMER" -> "summer"
            "FALL", "AUTUMN" -> "fall"
            else -> null
        }

    private val UNKNOWN_FORMAT_VALUES = setOf(
        "", "unknown", "undefined", "null", "none", "n/a", "na", "not available",
    )

    private val GENERIC_TITLES = setOf(
        "anime", "series", "movie", "film", "special", "ova", "ona", "music",
        "episode", "season", "part", "unknown", "untitled", "the anime",
    )

    private const val MINIMUM_INDEPENDENT_DIMENSIONS = 2
}
