package com.reiflix.reiflix_local.data.anilist

import android.content.Context
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeMetadataPatch
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.updateAniListMetadataAndAssociation
import com.reiflix.reiflix_local.data.sqlite.recordAniListAutoMatchOutcome
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository

import java.io.File
import java.util.Locale
import java.util.UUID
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
    private val descriptionLocalizer: ReiAnixAniListDescriptionLocalizer? = null,
    private val artworkRequestPublisher: ((Long) -> Boolean)? = null,
) {
    private val autoMatchAttempts: ConcurrentHashMap<Long, Long> = autoMatchAttemptTimestamps

    constructor(context: Context) : this(
        client = ReiAnixAniListClient(),
        library = ReiAnixSqliteLibraryRepository(context),
        settings = ReiAnixSqliteSettingsRepository(context),
        descriptionLocalizer = ReiAnixAniListDescriptionLocalizer(
            File(context.applicationContext.filesDir, "data/covers/anilist_description_ptbr.json"),
        ),
        artworkRequestPublisher = { animeId ->
            NativeMailbox.write(
                context.applicationContext,
                JSONObject()
                    .put("type", "anilist_artwork_sync")
                    .put("requestId", UUID.randomUUID().toString())
                    .put("payload", JSONObject().put("animeId", animeId)),
            )
        },
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
            ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
            ?: return AniListResult.Failure(
                AniListError.InvalidRequest("This local anime has no valid AniList association."),
            )
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
        val results = try {
            when (val result = client.searchAnime(query, 10)) {
                is AniListResult.Success -> result.value
                is AniListResult.Failure -> {
                    autoMatchAttempts.remove(animeId)
                    val outcome = when (val error = result.error) {
                        is AniListError.RateLimited -> "rate_limited"
                        is AniListError.Network -> "network_error"
                        is AniListError.Http -> when {
                            error.statusCode == 429 -> "rate_limited"
                            error.statusCode >= 500 -> "network_error"
                            else -> "invalid_response"
                        }
                        is AniListError.GraphQl, is AniListError.InvalidResponse -> "invalid_response"
                        is AniListError.InvalidRequest -> return result
                    }
                    // Do not hide a failed outcome write: without the persisted state
                    // the next catalog refresh would repeat the same remote request.
                    library.recordAniListAutoMatchOutcome(animeId, outcome)
                    return result
                }
            }
        } catch (cancelled: CancellationException) {
            autoMatchAttempts.remove(animeId)
            throw cancelled
        }
        if (results.isEmpty()) {
            library.recordAniListAutoMatchOutcome(animeId, "not_found")
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }
        val ranked = ReiAnixAniListMatcher.rank(
            localTitle = query,
            candidates = results,
            context = ReiAnixAniListMatcher.Context(
                year = local.year,
                mediaKind = local.mediaKind,
            ),
        )
        if (ranked.isEmpty()) {
            library.recordAniListAutoMatchOutcome(animeId, "not_found")
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }
        val best = ranked.first()
        if (best.score <= 0.0) {
            library.recordAniListAutoMatchOutcome(animeId, "not_found")
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }
        val second = ranked.getOrNull(1)?.score ?: 0.0
        val margin = best.score - second
        if (best.score < AUTO_MATCH_MIN_SCORE || margin < AUTO_MATCH_MIN_MARGIN) {
            library.recordAniListAutoMatchOutcome(
                animeId,
                "ambiguous",
                score = best.score,
                margin = margin,
            )
            return AniListResult.Success(
                AniListAutoMatchResult.Ambiguous(ranked.take(5).map { it.anime }),
            )
        }
        val persisted = when (
            val result = applyRemote(local, best.anime, manual = false, score = best.score, margin = margin)
        ) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> return result
        }
        return AniListResult.Success(AniListAutoMatchResult.Matched(persisted, best.anime))
    }

    /**
     * Native library replacement for the legacy Python metadata hydrator.
     * It processes the canonical SQLite catalog sequentially and persists lookup
     * outcomes so catalog refreshes cannot repeatedly query unresolved titles.
     * Image materialization remains delegated to the existing ArtworkEngine.
     */
    suspend fun reconcileUnmatchedLibraryMetadata(): Int {
        if (!isAniListEnabled() || !settingEnabled("metadata.auto_match", defaultValue = true)) return 0
        val rows = library.loadLibraryCatalogRows()
        var changedOutcomes = 0
        for (row in rows) {
            val local = row.anime
            if (local.id <= 0L ||
                local.anilistId != null ||
                local.anilistMatchManual == true ||
                local.metadataSource == "manual" ||
                local.metadataStatus == "manual"
            ) continue
            if (local.anilistMatchStatus in AUTO_MATCH_TERMINAL_STATUSES) continue
            if (local.metadataStatus !in AUTO_MATCH_ELIGIBLE_METADATA_STATUSES &&
                local.metadataSource != null
            ) continue

            when (val result = autoMatch(local.id)) {
                is AniListResult.Success -> when (result.value) {
                    is AniListAutoMatchResult.Matched,
                    AniListAutoMatchResult.NoMatch,
                    is AniListAutoMatchResult.Ambiguous,
                    -> changedOutcomes++
                    else -> Unit
                }
                is AniListResult.Failure -> if (result.error !is AniListError.InvalidRequest) {
                    // The outcome is already persisted by autoMatch when retrying it
                    // automatically would otherwise duplicate an identical request.
                    changedOutcomes++
                }
            }
        }
        return changedOutcomes
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
            val (remoteOriginalDescription, displayDescription) = withContext(Dispatchers.IO) {
                val remoteOriginal = remote.description
                    ?.let { descriptionLocalizer?.normalizeDescription(it) ?: it.trim() }
                    ?.takeIf(String::isNotBlank)
                val existingOriginal = local.descriptionOriginal
                    ?.let { descriptionLocalizer?.normalizeDescription(it) ?: it.trim() }
                    ?.takeIf(String::isNotBlank)
                val existingDescription = local.description
                    ?.let { descriptionLocalizer?.normalizeDescription(it) ?: it.trim() }
                    ?.takeIf(String::isNotBlank)
                val displayDescription = if (remoteOriginal == null) {
                    null
                } else {
                    val localizer = descriptionLocalizer
                    val existingTranslationIsValid = localizer != null &&
                        existingOriginal == remoteOriginal &&
                        existingDescription != null &&
                        localizer.isValidTranslation(remoteOriginal, existingDescription) &&
                        localizer.isPtBrDescription(existingDescription)
                    when {
                        existingTranslationIsValid -> existingDescription
                        localizer == null -> remoteOriginal
                        else -> {
                            val cached = localizer.getCachedDescriptionPtBr(remoteOriginal)
                            cached ?: localizer.localizeToPtBr(remoteOriginal) ?: remoteOriginal
                        }
                    }
                }
                remoteOriginal to displayDescription
            }
            val updated = library.updateAniListMetadataAndAssociation(
                patch = ReiAnixAnimeMetadataPatch(
                    animeId = local.id,
                    title = remote.preferredTitle.takeIf { it.isNotBlank() },
                    romaji = remote.titleRomaji,
                    english = remote.titleEnglish,
                    nativeTitle = remote.titleNative,
                    aliasesJson = remote.synonyms.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
                    description = displayDescription,
                    descriptionOriginal = remoteOriginalDescription,
                    coverUrl = if (artworkEnabled) remote.coverExtraLargeUrl ?: remote.coverLargeUrl else null,
                    bannerUrl = remote.bannerUrl.takeIf { artworkEnabled },
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
            )
            if (!manual && updated.anilistId != remote.id.toLong()) {
                return AniListResult.Failure(
                    AniListError.InvalidRequest(
                        "Automatic match was not applied because a manual or existing association has priority.",
                    ),
                )
            }
            if (artworkEnabled && (
                    !remote.coverExtraLargeUrl.isNullOrBlank() ||
                        !remote.coverLargeUrl.isNullOrBlank() ||
                        !remote.bannerUrl.isNullOrBlank()
                )
            ) {
                enqueueArtworkRefresh(updated.id)
            }
            AniListResult.Success(updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AniListResult.Failure(AniListError.InvalidResponse(error.message ?: "Could not persist AniList metadata."))
        }
    }

    /**
     * Requests materialization from the existing ArtworkEngine only after the
     * canonical metadata transaction has committed. A queue failure does not
     * invalidate durable metadata; the Compose renderer can still use its remote
     * URL fallback and a later sync can retry materialization.
     */
    private suspend fun enqueueArtworkRefresh(animeId: Long) {
        val publisher = artworkRequestPublisher ?: return
        try {
            val published = withContext(Dispatchers.IO) { publisher(animeId) }
            if (!published) Log.w(TAG, "Could not enqueue artwork refresh after AniList metadata commit")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not enqueue artwork refresh after AniList metadata commit", error)
        }
    }

    private suspend fun isAniListEnabled(): Boolean =
        settingEnabled("metadata.anilist_enabled", defaultValue = true)

    private suspend fun settingEnabled(key: String, defaultValue: Boolean): Boolean =
        settings.getPreference(key)?.value?.trim()?.lowercase(Locale.ROOT)?.let {
            it == "true" || it == "1" || it == "yes"
        } ?: defaultValue

    companion object {
        private const val TAG = "ReiAnixAniList"
        private val autoMatchAttemptTimestamps = ConcurrentHashMap<Long, Long>()
        const val AUTO_MATCH_MIN_SCORE = ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE
        const val AUTO_MATCH_MIN_MARGIN = ReiAnixAniListMatcher.AUTO_CONFIRM_MARGIN
        private val AUTO_MATCH_TERMINAL_STATUSES = setOf(
            "not_found",
            "network_error",
            "rate_limited",
            "invalid_response",
            "ambiguous",
        )
        private val AUTO_MATCH_ELIGIBLE_METADATA_STATUSES = setOf(
            null, "", "unresolved", "error", "ambiguous", "stale",
        )
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
