package com.reiflix.reiflix_local.data.artwork

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** A validated AniList poster candidate. The variant is descriptive metadata, not a new provider. */
internal data class ReiAnixAniListCoverCandidate(val variant: String, val url: String)

/** Validates only actual HTTP(S) image endpoints without requiring network access. */
internal fun validReiAnixArtworkHttpUrl(value: String?): String? {
    val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
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

/** Stable identity for alternate cover rows, scoped to local catalog item + AniList + URL. */
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
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
