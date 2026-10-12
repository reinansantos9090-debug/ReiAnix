package com.reiflix.reiflix_local.data.anilist

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeMetadataPatch
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AniList domain integration used by native ViewModels. The canonical SQLite
 * repository remains the sole persistence authority; remote failures never mutate
 * the local record. The existing ArtworkEngine remains the owner of materialized
 * artwork and this service only persists the remote URL as catalog metadata.
 */
class ReiAnixAniListIntegrationService(
    private val client: ReiAnixAniListClient,
    private val library: ReiAnixSqliteLibraryRepository,
    private val settings: ReiAnixSqliteSettingsRepository,
) {
    private val autoMatchAttempts = ConcurrentHashMap<Long, Long>()

    constructor(context: Context) : this(
        client = ReiAnixAniListClient(),
        library = ReiAnixSqliteLibraryRepository(context),
        settings = ReiAnixSqliteSettingsRepository(context),
    )

    suspend fun search(term: String, limit: Int = 10): AniListResult<List<AniListAnime>> {
        if (!isAniListEnabled()) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList integration is disabled in settings."))
        }
        return client.searchAnime(term, limit)
    }

    suspend fun refreshMetadata(animeId: Long): AniListResult<ReiAnixAnimeRecord> {
        if (!isAniListEnabled()) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList integration is disabled in settings."))
        }
        val local = library.getAnime(animeId)
            ?: return AniListResult.Failure(AniListError.InvalidRequest("Local anime does not exist."))
        val remoteId = local.anilistId
            ?: return AniListResult.Failure(AniListError.InvalidRequest("This local anime has no AniList association."))
        val remote = when (val result = client.getAnime(remoteId.toInt())) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> return result
        } ?: return AniListResult.Failure(AniListError.InvalidResponse("AniList returned no anime for this ID."))
        return applyRemote(local, remote, manual = local.anilistMatchManual == true, score = local.anilistMatchScore, margin = local.anilistMatchMargin)
    }

    suspend fun associateManually(animeId: Long, anilistId: Int): AniListResult<ReiAnixAnimeRecord> {
        if (!isAniListEnabled()) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList integration is disabled in settings."))
        }
        if (anilistId <= 0) return AniListResult.Failure(AniListError.InvalidRequest("AniList ID must be positive."))
        val local = library.getAnime(animeId)
            ?: return AniListResult.Failure(AniListError.InvalidRequest("Local anime does not exist."))
        val remote = when (val result = client.getAnime(anilistId)) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> return result
        } ?: return AniListResult.Failure(AniListError.InvalidResponse("AniList returned no anime for this ID."))
        val patched = when (val result = applyRemote(local, remote, manual = true, score = 1.0, margin = 1.0)) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> return result
        }
        return AniListResult.Success(patched)
    }

    /**
     * Conservative auto-match: only a unique exact normalized title/alias match
     * with a clear margin is persisted. Existing manual associations are immutable
     * to this path, and candidates are returned for UI confirmation otherwise.
     */
    suspend fun autoMatch(animeId: Long): AniListResult<AniListAutoMatchResult> {
        if (!isAniListEnabled()) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList integration is disabled in settings."))
        }
        if (!settingEnabled("metadata.auto_match", defaultValue = true)) {
            return AniListResult.Success(AniListAutoMatchResult.Disabled)
        }
        val local = library.getAnime(animeId)
            ?: return AniListResult.Failure(AniListError.InvalidRequest("Local anime does not exist."))
        if (local.anilistMatchManual == true && local.anilistId != null) {
            return AniListResult.Success(AniListAutoMatchResult.AlreadyManuallyMatched(local.anilistId))
        }
        if (local.anilistId != null) {
            return AniListResult.Success(AniListAutoMatchResult.AlreadyMatched(local.anilistId))
        }
        val now = System.currentTimeMillis()
        val previousAttempt = autoMatchAttempts[animeId]
        if (previousAttempt != null && now - previousAttempt < AUTO_MATCH_COOLDOWN_MS) {
            return AniListResult.Success(AniListAutoMatchResult.RecentlyAttempted)
        }
        autoMatchAttempts[animeId] = now
        val query = local.title.takeIf { it.isNotBlank() } ?: local.lookupTitle
        val results = when (val result = client.searchAnime(query, 10)) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> {
                autoMatchAttempts.remove(animeId)
                return result
            }
        }
        if (results.isEmpty()) return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        val localTitles = localTitles(local).map(::normalize).filter(String::isNotBlank).toSet()
        val ranked = results.map { candidate ->
            val remoteTitles = buildList {
                add(candidate.titleRomaji.orEmpty())
                add(candidate.titleEnglish.orEmpty())
                add(candidate.titleNative.orEmpty())
                addAll(candidate.synonyms)
            }.map(::normalize).filter(String::isNotBlank).toSet()
            val exact = localTitles.intersect(remoteTitles).isNotEmpty()
            var score = if (exact) 0.96 else 0.0
            if (exact) {
                // Contradictory catalog facts lower confidence; matching facts only
                // break ties and never turn a weak title match into an automatic link.
                if (local.year != null && candidate.seasonYear != null) {
                    score += if (local.year == candidate.seasonYear) 0.02 else -0.30
                }
                if (!local.format.isNullOrBlank() && !candidate.format.isNullOrBlank()) {
                    score += if (local.format.equals(candidate.format, ignoreCase = true)) 0.01 else -0.18
                }
                if (local.episodesCount != null && candidate.episodes != null) {
                    score += if (local.episodesCount == candidate.episodes) 0.01 else -0.15
                }
                score = score.coerceIn(0.0, 1.0)
            }
            candidate to score
        }.sortedByDescending { it.second }
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.second ?: 0.0
        val margin = best.second - second
        if (best.second < AUTO_MATCH_MIN_SCORE || margin < AUTO_MATCH_MIN_MARGIN) {
            return AniListResult.Success(AniListAutoMatchResult.Ambiguous(ranked.take(5).map { it.first }))
        }
        val persisted = when (val result = applyRemote(local, best.first, manual = false, score = best.second, margin = margin)) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> return result
        }
        return AniListResult.Success(AniListAutoMatchResult.Matched(persisted, best.first))
    }

    private suspend fun applyRemote(
        local: ReiAnixAnimeRecord,
        remote: AniListAnime,
        manual: Boolean,
        score: Double?,
        margin: Double?,
    ): AniListResult<ReiAnixAnimeRecord> {
        return try {
            // Artwork preference controls whether the remote URL becomes eligible
            // for the existing artwork pipeline. This service never downloads images.
            val artworkEnabled = settingEnabled("artwork.enabled", defaultValue = true)
            library.updateAniListMetadataAndAssociation(
                patch = ReiAnixAnimeMetadataPatch(
                    animeId = local.id,
                    title = remote.preferredTitle.takeIf { it.isNotBlank() },
                    romaji = remote.titleRomaji,
                    english = remote.titleEnglish,
                    nativeTitle = remote.titleNative,
                    aliasesJson = remote.synonyms.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                    description = remote.description,
                    coverUrl = if (artworkEnabled) remote.coverExtraLargeUrl ?: remote.coverLargeUrl else null,
                    bannerUrl = remote.bannerUrl,
                    genresJson = remote.genres.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                    year = remote.seasonYear,
                    season = remote.season,
                    status = remote.status,
                    episodesCount = remote.episodes,
                    duration = remote.durationMinutes,
                    score = remote.averageScore,
                    format = remote.format,
                    studio = remote.studios.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    metadataFetchedAt = System.currentTimeMillis() / 1000.0,
                ),
                anilistId = remote.id.toLong(),
                manual = manual,
                score = score,
                margin = margin,
            ).let { updated ->
                if (!manual && updated.anilistId != remote.id.toLong()) {
                    AniListResult.Failure(AniListError.InvalidRequest("Automatic match was not applied because a manual or existing association has priority."))
                } else {
                    AniListResult.Success(updated)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AniListResult.Failure(AniListError.InvalidResponse(error.message ?: "Could not persist AniList metadata."))
        }
    }

    private suspend fun isAniListEnabled(): Boolean =
        settingEnabled("metadata.anilist_enabled", defaultValue = true)

    private suspend fun settingEnabled(key: String, defaultValue: Boolean): Boolean =
        settings.getPreference(key)?.value?.trim()?.lowercase(Locale.ROOT)?.let {
            it == "true" || it == "1" || it == "yes"
        } ?: defaultValue

    private fun localTitles(local: ReiAnixAnimeRecord): List<String> = buildList {
        add(local.title)
        add(local.lookupTitle)
        local.romaji?.let(::add)
        local.english?.let(::add)
        local.nativeTitle?.let(::add)
        val rawAliases = local.aliasesJson
        if (!rawAliases.isNullOrBlank()) {
            runCatching {
                val json = JSONArray(rawAliases)
                for (index in 0 until json.length()) json.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun normalize(value: String): String = MediaTitleNormalizer.normalizeTitle(value)

    companion object {
        const val AUTO_MATCH_MIN_SCORE = 0.88
        const val AUTO_MATCH_MIN_MARGIN = 0.12
        const val AUTO_MATCH_COOLDOWN_MS = 6 * 60 * 60 * 1000L
    }
}

sealed interface AniListAutoMatchResult {
    data object Disabled : AniListAutoMatchResult
    data object NoMatch : AniListAutoMatchResult
    data object RecentlyAttempted : AniListAutoMatchResult
    data class AlreadyMatched(val anilistId: Long) : AniListAutoMatchResult
    data class AlreadyManuallyMatched(val anilistId: Long) : AniListAutoMatchResult
    data class Matched(val anime: ReiAnixAnimeRecord, val remote: AniListAnime) : AniListAutoMatchResult
    data class Ambiguous(val candidates: List<AniListAnime>) : AniListAutoMatchResult
}
