package com.reianix.reianix_local.data.anilist

import java.util.Locale

/**
 * Testable, side-effect-free rules for account synchronization.
 * Never infer an AniList identity from permissive artwork matching.
 */
internal object AniListAccountSyncPolicy {
    private val excludedTypes = setOf("special", "ova", "oad", "ona", "extra")

    fun reachedCheckpoint(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= 0L && positionMs.toDouble() / durationMs >= 0.8

    fun episodeProgress(
        mediaKind: String?,
        season: Int,
        number: Double?,
        absoluteNumber: Double?,
        episodeType: String?,
    ): Int? {
        if (season < 1 || episodeType?.trim()?.lowercase(Locale.ROOT) in excludedTypes) return null
        val candidate = when {
            mediaKind.equals("movie", ignoreCase = true) -> 1.0
            season == 1 -> number
            else -> absoluteNumber
        }
        return candidate?.takeIf {
            it.isFinite() && it >= 1.0 && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0
        }?.toInt()
    }

    fun shouldComplete(progress: Int, total: Int, remoteStatus: String): Boolean =
        total > 0 && progress == total && remoteStatus != "COMPLETED" && remoteStatus != "REPEATING"

    fun shouldSend(progress: Int, remoteProgress: Int, total: Int, remoteStatus: String): Boolean =
        progress > remoteProgress || shouldComplete(progress, total, remoteStatus)

    fun statusFor(progress: Int, total: Int, remoteStatus: String): String = when {
        remoteStatus == "COMPLETED" || remoteStatus == "REPEATING" -> remoteStatus
        total > 0 && progress >= total -> "COMPLETED"
        else -> "CURRENT"
    }
}
