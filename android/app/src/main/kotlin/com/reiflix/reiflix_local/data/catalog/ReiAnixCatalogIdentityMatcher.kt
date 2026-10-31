package com.reiflix.reiflix_local.data.catalog

import java.text.Normalizer
import java.util.Locale

data class ReiAnixCatalogIdentity(
    val titles: Set<String>,
    val releaseYear: Int?,
    val format: String?,
    val mediaKind: ReiAnixCatalogMediaKind?,
    val season: String? = null,
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
    /** Exact normalized title + year + format + media kind; ambiguous season evidence fails closed. */
    fun verify(
        localAnimeId: Long,
        local: ReiAnixCatalogIdentity,
        remote: ReiAnixCatalogAnime,
    ): ReiAnixCatalogIdentityMatch? {
        if (localAnimeId <= 0L || remote.source.resolvesIdentifiers ||
            remote.ids.idFor(remote.source).isNullOrBlank()
        ) return null

        val localTitles = local.titles.map(::normalizeCatalogTitle).filter(String::isNotEmpty).toSet()
        val remoteTitles = (remote.alternativeTitles + remote.title)
            .map(::normalizeCatalogTitle).filter(String::isNotEmpty).toSet()
        val exactTitle = (localTitles intersect remoteTitles).sorted().firstOrNull() ?: return null

        if (local.releaseYear == null || local.releaseYear != remote.releaseYear) return null
        val localFormat = normalizeCatalogFormat(local.format)
        val remoteFormat = normalizeCatalogFormat(remote.format)
        if (localFormat.isEmpty() || localFormat != remoteFormat) return null
        val localKind = local.mediaKind?.takeIf { it != ReiAnixCatalogMediaKind.UNKNOWN } ?: return null
        val remoteKind = remote.mediaKind?.takeIf { it != ReiAnixCatalogMediaKind.UNKNOWN } ?: return null
        if (localKind != remoteKind) return null

        if (local.seasonNumber != null && remote.seasonNumber != null &&
            local.seasonNumber != remote.seasonNumber
        ) return null
        val localSeason = local.season?.let(::normalizeCatalogFormat)
        val remoteSeason = remote.season?.let(::normalizeCatalogFormat)
        if (localSeason != null && remoteSeason != null && localSeason != remoteSeason) return null
        if (local.episodes != null && remote.episodes != null && local.episodes != remote.episodes) return null

        return ReiAnixCatalogIdentityMatch(
            localAnimeId, remote.source, remote.ids, fingerprint(local), exactTitle,
        )
    }

    internal fun fingerprint(identity: ReiAnixCatalogIdentity): String = buildString {
        append(identity.titles.map(::normalizeCatalogTitle).filter(String::isNotEmpty).distinct().sorted().joinToString("|"))
        append('\u001f').append(identity.releaseYear ?: "")
        append('\u001f').append(normalizeCatalogFormat(identity.format))
        append('\u001f').append(identity.mediaKind?.name.orEmpty())
        append('\u001f').append(identity.season?.let(::normalizeCatalogFormat).orEmpty())
        append('\u001f').append(identity.seasonNumber ?: "")
        append('\u001f').append(identity.episodes ?: "")
    }

    fun normalizeCatalogTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun normalizeCatalogFormat(value: String?): String {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return when (normalized) {
            "tv series", "series", "anime" -> "tv"
            "film" -> "movie"
            "tv_short" -> "tv short"
            else -> normalized
        }
    }
}
