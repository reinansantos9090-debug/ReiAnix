package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.catalog.ReiAnixAnimeCatalogProvider
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogAnime
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogError
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogMediaKind
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogResult
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogSource
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalCatalogIds
import com.reiflix.reiflix_local.data.catalog.isValidCatalogArtworkUrl
import java.util.Locale

/**
 * Adapts the existing AniList GraphQL client to the shared provider contract.
 * It does not replace the existing transport, cancellation or request rate controller.
 */
class ReiAnixAniListCatalogProviderAdapter(
    private val client: ReiAnixAniListClient,
) : ReiAnixAnimeCatalogProvider {
    override val source: ReiAnixCatalogSource = ReiAnixCatalogSource.ANILIST

    override suspend fun searchAnime(
        query: String,
        limit: Int,
    ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
        if (limit !in 1..50) {
            return ReiAnixCatalogResult.Failure(
                ReiAnixCatalogError.InvalidRequest("AniList result limit must be between 1 and 50."),
            )
        }
        return when (val result = client.searchAnime(query, limit)) {
            is AniListResult.Success -> if (result.incomplete) {
                ReiAnixCatalogResult.Failure(
                    ReiAnixCatalogError.InvalidResponse(
                        "AniList returned a partial search result; incomplete candidate sets cannot be used for identity resolution.",
                    ),
                )
            } else {
                val mapped = result.value.map { it.toCatalogAnime() }
                if (mapped.any { it == null }) {
                    ReiAnixCatalogResult.Failure(
                        ReiAnixCatalogError.InvalidResponse(
                            "AniList returned a media record without a usable title; the candidate set is incomplete.",
                        ),
                    )
                } else {
                    ReiAnixCatalogResult.Success(mapped.filterNotNull())
                }
            }
            is AniListResult.Failure ->
                ReiAnixCatalogResult.Failure(result.error.toCatalogError())
        }
    }

    override suspend fun getAnime(externalId: String): ReiAnixCatalogResult<ReiAnixCatalogAnime?> {
        val id = externalId.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: return ReiAnixCatalogResult.Failure(ReiAnixCatalogError.InvalidRequest("AniList ID must be positive."))
        return when (val result = client.getAnime(id)) {
            is AniListResult.Success -> if (result.incomplete) {
                ReiAnixCatalogResult.Failure(
                    ReiAnixCatalogError.InvalidResponse(
                        "AniList returned partial detail data; incomplete records cannot be used for identity resolution.",
                    ),
                )
            } else {
                val remote = result.value
                val mapped = remote?.toCatalogAnime()
                if (remote != null && mapped == null) {
                    ReiAnixCatalogResult.Failure(
                        ReiAnixCatalogError.InvalidResponse(
                            "AniList detail has no usable title for provider identity resolution.",
                        ),
                    )
                } else {
                    ReiAnixCatalogResult.Success(mapped)
                }
            }
            is AniListResult.Failure -> ReiAnixCatalogResult.Failure(result.error.toCatalogError())
        }
    }
}

private fun AniListAnime.toCatalogAnime(): ReiAnixCatalogAnime? {
    val preferred = preferredTitle.trim().takeIf(String::isNotEmpty) ?: return null
    val poster = coverExtraLargeUrl
        ?.takeIf(::isValidCatalogArtworkUrl)
        ?.let { it to "extraLarge" }
        ?: coverLargeUrl
            ?.takeIf(::isValidCatalogArtworkUrl)
            ?.let { it to "large" }
    return ReiAnixCatalogAnime(
        source = ReiAnixCatalogSource.ANILIST,
        ids = ReiAnixExternalCatalogIds(
            aniListId = id.toLong(),
            myAnimeListId = myAnimeListId,
        ),
        title = preferred,
        alternativeTitles = (synonyms + listOfNotNull(titleRomaji, titleEnglish, titleNative))
            .filter { it.isNotBlank() && !it.equals(preferred, ignoreCase = true) }
            .toSet(),
        synopsis = description,
        coverUrl = poster?.first,
        coverImageField = "coverImage",
        coverVariant = poster?.second,
        bannerUrl = bannerUrl.takeIf(::isValidCatalogArtworkUrl),
        bannerImageField = "bannerImage",
        bannerVariant = "original",
        releaseYear = seasonYear,
        format = format,
        mediaKind = format.toCatalogMediaKind(),
        season = season,
        episodes = episodes,
        durationMinutes = durationMinutes,
        status = status,
        averageScore = averageScore,
    )
}

private fun String?.toCatalogMediaKind(): ReiAnixCatalogMediaKind? = when (
    this?.trim()?.uppercase(Locale.ROOT)
) {
    "TV", "TV_SHORT" -> ReiAnixCatalogMediaKind.SERIES
    "MOVIE" -> ReiAnixCatalogMediaKind.MOVIE
    "OVA" -> ReiAnixCatalogMediaKind.OVA
    "ONA" -> ReiAnixCatalogMediaKind.ONA
    "SPECIAL" -> ReiAnixCatalogMediaKind.SPECIAL
    "MUSIC" -> ReiAnixCatalogMediaKind.MUSIC
    else -> ReiAnixCatalogMediaKind.UNKNOWN
}

private fun AniListError.toCatalogError(): ReiAnixCatalogError = when (this) {
    is AniListError.Network -> ReiAnixCatalogError.Network(message)
    is AniListError.Http -> when {
        statusCode == 429 -> ReiAnixCatalogError.RateLimited(null)
        statusCode in 500..599 -> ReiAnixCatalogError.Server(statusCode)
        else -> ReiAnixCatalogError.Http(statusCode)
    }
    is AniListError.RateLimited ->
        ReiAnixCatalogError.RateLimited(
            retryAfterSeconds?.takeIf { it >= 0L && it <= Long.MAX_VALUE / 1_000L }?.times(1_000L),
        )
    is AniListError.GraphQl -> ReiAnixCatalogError.InvalidResponse(messages.joinToString("; "))
    is AniListError.InvalidRequest -> ReiAnixCatalogError.InvalidRequest(message)
    is AniListError.InvalidResponse -> ReiAnixCatalogError.InvalidResponse(message)
}
