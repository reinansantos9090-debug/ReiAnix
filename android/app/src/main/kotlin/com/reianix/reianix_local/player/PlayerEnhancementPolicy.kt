package com.reianix.reianix_local.player

data class PlayerSkipSegment(val kind: String, val startMs: Long, val endMs: Long) {
    val label get() = when (kind) {
        "op" -> "Pular abertura"
        "ed" -> "Pular encerramento"
        else -> "Pular recapitulação"
    }
}

object PlayerEnhancementPolicy {
    fun resumePosition(positionMs: Long, enabled: Boolean): Long =
        if (enabled) (positionMs.coerceAtLeast(0L) - 5_000L).coerceAtLeast(0L)
        else positionMs.coerceAtLeast(0L)

    fun validSegment(kind: String, startMs: Long, endMs: Long, durationMs: Long): Boolean =
        kind in setOf("op", "ed", "recap") && durationMs > 0L &&
            startMs >= 0L && endMs > startMs && endMs <= durationMs

    fun episodeNumber(number: Double?): Int? =
        number?.takeIf { it.isFinite() && it >= 1.0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()

    /** A local season-relative number is not necessarily the MAL episode number. */
    fun aniSkipEpisodeNumber(season: Int, number: Double?, absoluteNumber: Double?, episodeType: String?): Int? {
        if (season < 1 || episodeType?.trim()?.lowercase(java.util.Locale.ROOT) in
            setOf("special", "ova", "oad", "ona", "extra", "movie")) return null
        val relative = episodeNumber(number)
        val absolute = episodeNumber(absoluteNumber)
        return if (season == 1) {
            if (relative != null && absolute != null && relative != absolute) null else absolute ?: relative
        } else {
            // Never guess an offset from incomplete local folders.
            absolute?.takeIf { relative == null || it > relative }
        }
    }

    /** AniSkip intervals are community timestamps, not scene recognition.
     * Prefer no automatic button to skipping a potentially normal scene.
     * These constraints deliberately do not apply to user-entered markers.
     */
    fun plausibleRemoteSegment(segment: PlayerSkipSegment, duration: Long): Boolean {
        if (!validSegment(segment.kind, segment.startMs, segment.endMs, duration)) return false
        val length = segment.endMs - segment.startMs
        if (length !in 15_000L..240_000L || length > duration * 0.35) return false
        return when (segment.kind) {
            "op" -> segment.startMs <= minOf(300_000.0, duration * 0.30) &&
                segment.endMs <= duration * 0.45
            "ed" -> segment.startMs >= duration * 0.75 && segment.endMs >= duration * 0.85
            else -> false
        }
    }

    fun trustedRemoteSegments(remote: List<PlayerSkipSegment>, duration: Long): List<PlayerSkipSegment> =
        remote.filter { plausibleRemoteSegment(it, duration) }.distinct()
            .groupBy { it.kind }.values.mapNotNull { it.singleOrNull() }.sortedBy { it.startMs }

    fun segments(manual: PlayerLocalMetadataStore.Metadata, remote: List<PlayerSkipSegment>, duration: Long): List<PlayerSkipSegment> {
        val local = listOfNotNull(
            manual.opening?.let { PlayerSkipSegment("op", it.startMs, it.endMs) },
            manual.ending?.let { PlayerSkipSegment("ed", it.startMs, it.endMs) },
        ).filter { validSegment(it.kind, it.startMs, it.endMs, duration) }
        return (local + trustedRemoteSegments(remote, duration).filter { r ->
            local.none { it.kind == r.kind || (it.startMs < r.endMs && r.startMs < it.endMs) }
        })
            .filter { validSegment(it.kind, it.startMs, it.endMs, duration) }.distinct().sortedBy { it.startMs }
    }
}
