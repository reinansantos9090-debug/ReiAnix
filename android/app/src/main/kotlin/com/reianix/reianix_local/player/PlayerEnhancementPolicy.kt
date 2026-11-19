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

    fun segments(manual: PlayerLocalMetadataStore.Metadata, remote: List<PlayerSkipSegment>, duration: Long): List<PlayerSkipSegment> {
        val local = listOfNotNull(
            manual.opening?.let { PlayerSkipSegment("op", it.startMs, it.endMs) },
            manual.ending?.let { PlayerSkipSegment("ed", it.startMs, it.endMs) },
        ).filter { validSegment(it.kind, it.startMs, it.endMs, duration) }
        return (local + remote.filter { r -> local.none { it.kind == r.kind } })
            .filter { validSegment(it.kind, it.startMs, it.endMs, duration) }.distinct().sortedBy { it.startMs }
    }
}
