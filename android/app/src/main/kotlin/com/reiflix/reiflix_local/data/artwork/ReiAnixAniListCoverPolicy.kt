package com.reiflix.reiflix_local.data.artwork

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** A validated AniList poster candidate. The variant is descriptive metadata, not a new provider. */
internal data class ReiAnixAniListCoverCandidate(val variant: String, val url: String)

/**
 * Accept only URLs the Android app can actually fetch. Cleartext HTTP is deliberately
 * rejected so an unusable extraLarge URL cannot suppress a valid HTTPS large variant.
 */
internal fun validReiAnixArtworkHttpUrl(value: String?): String? {
    val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
    if (uri.port !in -1..65535) return null
    return candidate
}

/** AniList cover variants: extraLarge first, then large; reject invalid and duplicate URLs. */
internal fun resolveAniListCoverCandidates(
    extraLargeUrl: String?,
    largeUrl: String?,
): List<ReiAnixAniListCoverCandidate> =
    listOf("extraLarge" to extraLargeUrl, "large" to largeUrl)
        .mapNotNull { (variant, value) ->
            validReiAnixArtworkHttpUrl(value)?.let { ReiAnixAniListCoverCandidate(variant, it) }
        }
        .distinctBy(ReiAnixAniListCoverCandidate::url)

/** Stable identity for alternate cover rows, scoped to local anime + AniList + URL. */
internal fun aniListArtworkCandidateKey(
    animeId: Long,
    aniListId: Long,
    url: String,
    artworkType: String = "poster",
): String {
    require(animeId > 0L) { "Anime ID must be positive." }
    require(aniListId > 0L) { "AniList ID must be positive." }
    val material = "reianix|anime:$animeId|anilist:$aniListId|$url|$artworkType|candidate"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * A valid non-primary AniList URL remains eligible even when the primary image works.
 * Manual/local artwork and obsolete candidate rows remain protected.
 */
internal fun canMaterializeAniListArtworkVariant(
    primaryUrl: String?,
    candidateUrl: String?,
    candidateStatus: String?,
    artworkIsUserOwned: Boolean,
): Boolean {
    val candidate = validReiAnixArtworkHttpUrl(candidateUrl) ?: return false
    val primary = validReiAnixArtworkHttpUrl(primaryUrl)
    if (candidate == primary || artworkIsUserOwned) return false
    val candidateState = candidateStatus?.trim()?.lowercase(Locale.ROOT) ?: return false
    return candidateState in setOf(
        "source_candidate", "downloading", "not_requested", "pending", "ready", "failed", "retry_wait",
    )
}
