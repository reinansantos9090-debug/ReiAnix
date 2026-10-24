package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.organize.ReiAnixGenreRegistry

import android.content.Context
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeMetadataPatch
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.updateAniListMetadataAndAssociation
import com.reiflix.reiflix_local.data.sqlite.recordAniListAutoMatchOutcome
import com.reiflix.reiflix_local.data.sqlite.setMetadataStatusByLookupTitle
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository

import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AniList domain integration used by native ViewModels. The canonical SQLite
 * repository remains the sole persistence authority; remote failures retain
 * existing local metadata and update only its freshness/status projection. Requested
 * artwork is materialized by the Kotlin artwork service after metadata commits.
 */
private data class SynopsisRecoveryBatchResult(
    val scannedCount: Int,
    val translatedCount: Int,
    val pendingCount: Int,
    val recoverableFailureCount: Int,
    val translationRequestCount: Int,
    val sourceLookupCount: Int,
    val hasMoreInCycle: Boolean,
    val localizerAvailable: Boolean = true,
)

class ReiAnixAniListIntegrationService(
    private val client: ReiAnixAniListClient,
    private val library: ReiAnixSqliteLibraryRepository,
    private val settings: ReiAnixSqliteSettingsRepository,
    private val descriptionLocalizer: ReiAnixAniListDescriptionLocalizer? = null,
    private val metadataCommitPublisher: ((Long, Boolean) -> Boolean)? = null,
    private val genreRegistry: ReiAnixGenreRegistry? = null,
) {
    private val effectiveGenreRegistry: ReiAnixGenreRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        genreRegistry ?: ReiAnixGenreRegistry(library.database)
    }
    private val autoMatchAttempts: ConcurrentHashMap<Long, Long> = autoMatchAttemptTimestamps

    constructor(context: Context) : this(
        client = ReiAnixAniListClient(),
        library = ReiAnixSqliteLibraryRepository(context),
        settings = ReiAnixSqliteSettingsRepository(context),
        descriptionLocalizer = ReiAnixAniListDescriptionLocalizer(
            File(context.applicationContext.filesDir, "data/covers/anilist_description_ptbr.json"),
        ),
        metadataCommitPublisher = { animeId, requestArtwork ->
            if (requestArtwork) {
                ReiAnixAppDependencies.existingOrNull(context.applicationContext)
                    ?.artworkService?.requestAnimeArtwork(animeId, force = true)
            }
            NativeMailbox.write(
                context.applicationContext,
                JSONObject()
                    .put("type", "anilist_metadata_committed")
                    .put("requestId", UUID.randomUUID().toString())
                    .put("payload", JSONObject().put("animeId", animeId).put("requestArtwork", false)),
            )
        },
    )

    suspend fun search(term: String, limit: Int = 10): AniListResult<List<AniListAnime>> {
        if (!isAniListEnabled()) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList integration is disabled in settings."))
        }
        return client.searchAnime(term, limit)
    }

    /**
     * Read-only lookup for a remote catalogue result. It never creates a local
     * anime row or association; persistence remains an explicit user/domain action.
     */
    suspend fun lookupAnimeById(anilistId: Int): AniListResult<AniListAnime?> {
        if (!isAniListEnabled()) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList integration is disabled in settings."))
        }
        if (anilistId <= 0) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList ID must be positive."))
        }
        return client.getAnime(anilistId)
    }

    /** Exact ID lookup; remote titles are never used to invent or select a local record. */
    suspend fun findLocalAnimeIdsByAniListId(anilistId: Int): List<Long> {
        require(anilistId > 0) { "AniList ID must be positive." }
        return library.findAnimeIdsByAniListId(anilistId.toLong())
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

        // Port main.py's refresh_metadata lifecycle: a cached local record remains
        // visible while a forced refresh runs, and failed requests mark it stale.
        // The canonical SQLite row stays the only metadata/status source.
        library.setMetadataStatusByLookupTitle(
            lookupTitle = local.lookupTitle,
            status = "refreshing",
            confidence = local.metadataConfidence,
        )

        suspend fun retainStaleMetadata(): AniListResult<ReiAnixAnimeRecord> {
            library.setMetadataStatusByLookupTitle(
                lookupTitle = local.lookupTitle,
                status = "stale",
                confidence = local.metadataConfidence ?: "low",
            )
            val stale = library.getAnime(animeId) ?: local.copy(metadataStatus = "stale")
            return AniListResult.Success(stale)
        }

        val remoteResult = try {
            client.getAnime(remoteId.toInt())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w("ReiAnixAniList", "Metadata refresh failed; retaining stale local metadata animeId=$animeId", failure)
            return retainStaleMetadata()
        }
        val remote = when (remoteResult) {
            is AniListResult.Success -> remoteResult.value
            is AniListResult.Failure -> {
                Log.w(
                    "ReiAnixAniList",
                    "Metadata refresh failed; retaining stale local metadata animeId=$animeId error=${remoteResult.error}",
                )
                return retainStaleMetadata()
            }
        } ?: run {
            Log.w("ReiAnixAniList", "Metadata refresh returned no record; retaining stale local metadata animeId=$animeId")
            return retainStaleMetadata()
        }

        return when (
            val applied = applyRemote(
                local,
                remote,
                manual = local.anilistMatchManual == true,
                score = local.anilistMatchScore,
                margin = local.anilistMatchMargin,
            )
        ) {
            is AniListResult.Success -> applied
            is AniListResult.Failure -> {
                // Failed metadata writes must not leave the local projection looking
                // like it is still being refreshed.
                retainStaleMetadata()
            }
        }
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
    suspend fun autoMatch(
        animeId: Long,
        force: Boolean = false,
    ): AniListResult<AniListAutoMatchResult> {
        if (!isAniListEnabled()) {
            return AniListResult.Failure(AniListError.InvalidRequest("AniList integration is disabled in settings."))
        }
        if (!force && !settingEnabled("metadata.auto_match", defaultValue = true)) {
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
                    val outcome = when (val error = result.error) {
                        is AniListError.RateLimited -> "rate_limited"
                        is AniListError.Network -> "network_error"
                        is AniListError.Http -> when {
                            error.statusCode == 429 -> "rate_limited"
                            error.statusCode >= 500 -> "network_error"
                            else -> "invalid_response"
                        }
                        is AniListError.GraphQl, is AniListError.InvalidResponse -> "invalid_response"
                        is AniListError.InvalidRequest -> {
                            autoMatchAttempts.remove(animeId)
                            return result
                        }
                    }
                    // Keep the per-process cooldown after a transient remote failure.
                    // The persisted status is diagnostic, not permanently terminal:
                    // a later refresh/restart can retry without hammering the API.
                    recordAutoMatchOutcome(animeId, outcome)
                    return result
                }
            }
        } catch (cancelled: CancellationException) {
            autoMatchAttempts.remove(animeId)
            throw cancelled
        }
        if (results.isEmpty()) {
            recordAutoMatchOutcome(animeId, "not_found")
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
            recordAutoMatchOutcome(animeId, "not_found")
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }
        val best = ranked.first()
        if (best.score < ReiAnixAniListMatcher.MINIMUM_SCORE) {
            recordAutoMatchOutcome(animeId, "not_found")
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }
        val second = ranked.getOrNull(1)?.score ?: 0.0
        val margin = best.score - second
        if (best.score < AUTO_MATCH_MIN_SCORE || margin < AUTO_MATCH_MIN_MARGIN) {
            recordAutoMatchOutcome(
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

    private suspend fun recordAutoMatchOutcome(
        animeId: Long,
        status: String,
        score: Double? = null,
        margin: Double? = null,
    ) {
        library.recordAniListAutoMatchOutcome(
            animeId = animeId,
            status = status,
            score = score,
            margin = margin,
        )
        // Publish even unresolved rows so the native projection can show their
        // persisted status. A false requestArtwork flag prevents unrelated image work.
        enqueueMetadataCommit(animeId, requestArtwork = false)
    }

    /**
     * Native library replacement for the legacy Python metadata hydrator.
     * It processes the canonical SQLite catalog sequentially and persists lookup
     * outcomes so catalog refreshes cannot repeatedly query unresolved titles.
     * Persisted image URLs are handed to the application-scoped Kotlin artwork service.
     */
    suspend fun reconcileUnmatchedLibraryMetadata(): Int {
        if (!isAniListEnabled() || !settingEnabled("metadata.auto_match", defaultValue = true)) return 0
        val rows = library.loadLibraryCatalogRows()
        var changedOutcomes = 0
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val local = row.anime
            if (local.id <= 0L ||
                local.anilistId != null ||
                local.anilistMatchManual == true ||
                local.metadataSource == "manual" ||
                local.metadataStatus == "manual"
            ) continue
            if (local.anilistMatchStatus in AUTO_MATCH_TERMINAL_STATUSES) continue

            when (val result = autoMatch(local.id)) {
                is AniListResult.Success -> when (result.value) {
                    is AniListAutoMatchResult.Matched -> changedOutcomes++
                    AniListAutoMatchResult.NoMatch -> changedOutcomes++
                    is AniListAutoMatchResult.Ambiguous -> changedOutcomes++
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

    /**
     * Localizes a remote-only synopsis for presentation using the same canonical
     * localizer/cache as persisted metadata. It never returns the source as a
     * successful translation unless the source itself validates as Portuguese.
     */
    suspend fun localizeSynopsisForPresentation(description: String?): String? {
        val localizer = descriptionLocalizer ?: return null
        val source = localizer.normalizeDescription(description)
        if (source.isBlank()) return null
        val cached = localizer.getCachedDescriptionPtBr(source)
            ?.takeIf { localizer.isValidPtBrResult(source, it) }
        if (cached != null) return cached
        return localizer.localizeToPtBr(source)
            ?.takeIf { localizer.isValidPtBrResult(source, it) }
    }

    /**
     * Incrementally repairs pending synopses in the canonical SQLite catalog.
     * A stable-ID cursor and cycle state in the existing preferences table survive
     * process restarts without schema changes. The public pass traverses bounded
     * batches to the current end of the ID range, yielding between them so lifecycle
     * cancellation remains responsive. No lock is held while network I/O is performed.
     */
    suspend fun reconcilePendingSynopsisTranslations(
        maxCandidates: Int = MAX_SYNOPSIS_CANDIDATES_PER_RUN,
        maxTranslationAttempts: Int = MAX_SYNOPSIS_TRANSLATION_ATTEMPTS_PER_RUN,
        force: Boolean = false,
    ): Int {
        require(maxCandidates in 1..MAX_SYNOPSIS_CANDIDATES_PER_RUN)
        require(maxTranslationAttempts in 1..MAX_SYNOPSIS_TRANSLATION_ATTEMPTS_PER_RUN)

        val previousState = settings.getPreference(SYNOPSIS_RECOVERY_STATE_KEY)?.value
        var cursor = settings.getPreference(SYNOPSIS_RECOVERY_CURSOR_KEY)
            ?.value?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val now = System.currentTimeMillis()
        val retryAfter = settings.getPreference(SYNOPSIS_RECOVERY_RETRY_AFTER_KEY)
            ?.value?.toLongOrNull() ?: 0L
        val highWater = settings.getPreference(SYNOPSIS_RECOVERY_HIGH_WATER_KEY)
            ?.value?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        // A completed or intentionally deferred sweep does not re-scan the old catalog
        // on every Activity start. It only checks for newly inserted eligible IDs above
        // the high-water mark. Due transient retries and forced scans start at ID zero.
        if (!force && cursor == 0L) {
            when (previousState) {
                "complete" -> {
                    if (library.loadAniListSynopsisRecoveryBatch(highWater, limit = 1).isEmpty()) return 0
                    cursor = highWater
                }
                "pending" -> {
                    if (retryAfter > now || retryAfter <= 0L) {
                        if (library.loadAniListSynopsisRecoveryBatch(highWater, limit = 1).isEmpty()) return 0
                        cursor = highWater
                    }
                }
            }
        }

        // One shared process-level gate prevents different service instances from
        // dispatching duplicate requests against the same persisted cursor.
        if (!synopsisRecoveryInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "Synopsis recovery already active; this invocation is coalesced.")
            return 0
        }

        return try {
            if (settings.getPreference(SYNOPSIS_RECOVERY_CURSOR_KEY)?.value != cursor.toString()) {
                settings.setPreference(SYNOPSIS_RECOVERY_CURSOR_KEY, cursor.toString())
            }
            val startsNewCycle = cursor == 0L && previousState != "in_progress" ||
                previousState == "complete" && cursor > 0L
            if (startsNewCycle) {
                settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_PENDING_KEY, "false")
                settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_RECOVERABLE_KEY, "false")
            }
            settings.setPreference(SYNOPSIS_RECOVERY_STATE_KEY, "in_progress")

            val batch = reconcilePendingSynopsisTranslationsBatch(
                maxCandidates = maxCandidates,
                maxTranslationAttempts = maxTranslationAttempts,
            )
            if (!batch.localizerAvailable) {
                settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_PENDING_KEY, "true")
                settings.setPreference(SYNOPSIS_RECOVERY_STATE_KEY, "pending")
                settings.setPreference(SYNOPSIS_RECOVERY_RETRY_AFTER_KEY, "0")
                return 0
            }

            if (batch.hasMoreInCycle) {
                // This invocation is deliberately bounded. The Compose host starts the
                // next batch cooperatively; the durable cursor preserves unvisited rows.
                settings.setPreference(SYNOPSIS_RECOVERY_STATE_KEY, "in_progress")
                Log.i(
                    TAG,
                    "Synopsis recovery batch scanned=" + batch.scannedCount +
                        " translated=" + batch.translatedCount +
                        " pending=" + batch.pendingCount +
                        " translationRequests=" + batch.translationRequestCount +
                        " sourceLookups=" + batch.sourceLookupCount +
                        " cursor=" + (settings.getPreference(SYNOPSIS_RECOVERY_CURSOR_KEY)?.value ?: "0"),
                )
                return batch.translatedCount
            }

            val cycleHasPending = settings.getPreference(SYNOPSIS_RECOVERY_CYCLE_PENDING_KEY)
                ?.value?.toBooleanStrictOrNull() == true || batch.pendingCount > 0
            val cycleHasRecoverableFailure =
                settings.getPreference(SYNOPSIS_RECOVERY_CYCLE_RECOVERABLE_KEY)
                    ?.value?.toBooleanStrictOrNull() == true || batch.recoverableFailureCount > 0
            settings.setPreference(
                SYNOPSIS_RECOVERY_STATE_KEY,
                if (cycleHasPending) "pending" else "complete",
            )

            val existingRetryAfter = settings.getPreference(SYNOPSIS_RECOVERY_RETRY_AFTER_KEY)
                ?.value?.toLongOrNull() ?: 0L
            val nextRetryAfter = if (!cycleHasPending || !cycleHasRecoverableFailure) {
                0L
            } else if (existingRetryAfter > System.currentTimeMillis()) {
                existingRetryAfter
            } else {
                System.currentTimeMillis() +
                    (ReiAnixAniListDescriptionLocalizer.FAILED_RETRY_WINDOW_SECONDS * 1000.0).toLong()
            }
            settings.setPreference(SYNOPSIS_RECOVERY_RETRY_AFTER_KEY, nextRetryAfter.toString())
            Log.i(
                TAG,
                "Synopsis recovery cycle ended translated=" + batch.translatedCount +
                    " pending=" + cycleHasPending +
                    " recoverableFailures=" + cycleHasRecoverableFailure +
                    " nextRetryAfter=" + nextRetryAfter,
            )
            batch.translatedCount
        } finally {
            synopsisRecoveryInProgress.set(false)
        }
    }

    /** True only when the previous bounded batch ended and the next batch is safe to start. */
    suspend fun shouldContinueSynopsisRecovery(): Boolean {
        if (synopsisRecoveryInProgress.get()) return false
        return settings.getPreference(SYNOPSIS_RECOVERY_STATE_KEY)?.value == "in_progress"
    }

    /** A single timer boundary derived from the existing translator failure backoff. */
    suspend fun nextSynopsisRecoveryRetryAfterEpochMillis(): Long? =
        settings.getPreference(SYNOPSIS_RECOVERY_RETRY_AFTER_KEY)
            ?.value?.toLongOrNull()
            ?.takeIf { it > System.currentTimeMillis() }

    private suspend fun reconcilePendingSynopsisTranslationsBatch(
        maxCandidates: Int,
        maxTranslationAttempts: Int,
    ): SynopsisRecoveryBatchResult {
        val localizer = descriptionLocalizer
        if (localizer == null) {
            Log.w(TAG, "Synopsis recovery unavailable because the canonical localizer is not configured")
            return SynopsisRecoveryBatchResult(
                scannedCount = 0,
                translatedCount = 0,
                pendingCount = 0,
                recoverableFailureCount = 0,
                translationRequestCount = 0,
                sourceLookupCount = 0,
                hasMoreInCycle = false,
                localizerAvailable = false,
            )
        }

        var cursor = settings.getPreference(SYNOPSIS_RECOVERY_CURSOR_KEY)
            ?.value?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        var cycleCompleted = false
        var scanned = 0
        var translatedCount = 0
        var translationAttempts = 0
        var sourceLookups = 0
        var cacheHits = 0
        var alreadyValid = 0
        var failed = 0
        var pending = 0
        var recoverableFailures = 0
        var conflicts = 0
        var cleared = 0
        var manualSkipped = 0

        while (scanned < maxCandidates && translationAttempts < maxTranslationAttempts) {
            currentCoroutineContext().ensureActive()
            val page = library.loadAniListSynopsisRecoveryBatch(
                afterAnimeId = cursor,
                limit = minOf(SYNOPSIS_RECOVERY_PAGE_SIZE, maxCandidates - scanned),
            )
            if (page.isEmpty()) {
                // Persist the high-water mark before resetting the active cursor. New
                // eligible IDs above it can be found without walking a completed catalog.
                if (cursor > 0L) {
                    val knownHighWater = settings.getPreference(SYNOPSIS_RECOVERY_HIGH_WATER_KEY)
                        ?.value?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    if (cursor > knownHighWater) {
                        settings.setPreference(SYNOPSIS_RECOVERY_HIGH_WATER_KEY, cursor.toString())
                    }
                }
                if (cursor != 0L) settings.setPreference(SYNOPSIS_RECOVERY_CURSOR_KEY, "0")
                cursor = 0L
                cycleCompleted = true
                break
            }

            for (row in page) {
                if (scanned >= maxCandidates || translationAttempts >= maxTranslationAttempts) break
                currentCoroutineContext().ensureActive()
                scanned++

                var outcome = "skipped"
                var usedCache = false
                var clearedStaleDisplay = false
                try {
                    val anilistId = row.anilistId?.takeIf {
                        it in 1L..Int.MAX_VALUE.toLong()
                    }
                    if (row.anilistId != null && anilistId == null) {
                        outcome = "invalid_association"
                    } else if (hasManuallyOwnedSynopsis(row)) {
                        outcome = "manual"
                    } else {
                        val originalRaw = row.descriptionOriginal
                        val displayRaw = row.description
                        val original = localizer.normalizeDescription(originalRaw)
                        val display = localizer.normalizeDescription(displayRaw)

                        if (original.isNotEmpty() && display.isNotEmpty() &&
                            localizer.isValidPtBrResult(original, display)
                        ) {
                            outcome = "already_valid"
                        } else if (original.isEmpty() && display.isNotEmpty() &&
                            localizer.isPtBrDescription(display)
                        ) {
                            // Keep an existing Portuguese synopsis even if an older row
                            // did not preserve its source field.
                            outcome = "already_valid"
                        } else {
                            var source = original.takeIf(String::isNotBlank)
                                ?: display.takeIf(String::isNotBlank)

                            if (source == null) {
                                if (anilistId == null || !isAniListEnabled() ||
                                    sourceLookups >= MAX_SYNOPSIS_SOURCE_LOOKUPS_PER_RUN
                                ) {
                                    outcome = "source_unavailable"
                                } else {
                                    sourceLookups++
                                    val remoteResult = try {
                                        client.getAnime(anilistId.toInt())
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        Log.i(
                                            TAG,
                                            "Synopsis source lookup failed animeId=" + row.id +
                                                " errorType=" + error::class.java.simpleName,
                                        )
                                        null
                                    }
                                    source = when (remoteResult) {
                                        is AniListResult.Success -> remoteResult.value?.description
                                            ?.let(localizer::normalizeDescription)
                                            ?.takeIf(String::isNotBlank)
                                        is AniListResult.Failure -> {
                                            Log.i(
                                                TAG,
                                                "Synopsis source lookup failed animeId=" + row.id +
                                                    " errorType=" + remoteResult.error::class.java.simpleName,
                                            )
                                            null
                                        }
                                        null -> null
                                    }
                                    if (source.isNullOrBlank()) outcome = "source_unavailable"
                                }
                            }

                            val sourceText = source?.takeIf(String::isNotBlank)
                            if (sourceText != null) {
                                // CAS-clearing first ensures an old English/other-language
                                // fallback never remains presented as a completed synopsis.
                                // The original/source is retained in description_original.
                                val prepared = library.updateAniListSynopsisIfUnchanged(
                                    animeId = row.id,
                                    expectedAniListId = anilistId,
                                    expectedOriginal = originalRaw,
                                    expectedDescription = displayRaw,
                                    originalSource = sourceText,
                                    translatedDescription = null,
                                )
                                if (!prepared) {
                                    outcome = "conflict"
                                } else {
                                    clearedStaleDisplay = display.isNotEmpty()
                                    if (clearedStaleDisplay || originalRaw != sourceText) {
                                        enqueueMetadataCommit(row.id, requestArtwork = false)
                                    }

                                    val cached = localizer.getCachedDescriptionPtBr(sourceText)
                                        ?.takeIf { localizer.isValidPtBrResult(sourceText, it) }
                                    val translated = if (cached != null) {
                                        usedCache = true
                                        cached
                                    } else {
                                        translationAttempts++
                                        localizer.localizeToPtBr(sourceText)
                                    }
                                    val validTranslation = translated
                                        ?.takeIf { localizer.isValidPtBrResult(sourceText, it) }
                                    if (validTranslation == null) {
                                        outcome = if (localizer.detectDescriptionLanguage(sourceText).first == "unknown") {
                                            "unknown_source_language"
                                        } else {
                                            "translation_failed"
                                        }
                                    } else {
                                        val persisted = library.updateAniListSynopsisIfUnchanged(
                                            animeId = row.id,
                                            expectedAniListId = anilistId,
                                            expectedOriginal = sourceText,
                                            expectedDescription = null,
                                            originalSource = sourceText,
                                            translatedDescription = validTranslation,
                                        )
                                        outcome = if (persisted) "translated" else "conflict"
                                        if (persisted) enqueueMetadataCommit(row.id, requestArtwork = false)
                                    }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    outcome = "error"
                    Log.w(
                        TAG,
                        "Synopsis recovery failed animeId=" + row.id +
                            " errorType=" + error::class.java.simpleName,
                    )
                }

                when (outcome) {
                    "translated" -> {
                        translatedCount++
                        if (usedCache) cacheHits++
                    }
                    "already_valid" -> alreadyValid++
                    "manual" -> manualSkipped++
                    "unknown_source_language", "invalid_association" -> {
                        failed++
                        pending++
                        settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_PENDING_KEY, "true")
                    }
                    "translation_failed", "source_unavailable", "error" -> {
                        failed++
                        pending++
                        recoverableFailures++
                        settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_PENDING_KEY, "true")
                        settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_RECOVERABLE_KEY, "true")
                    }
                    "conflict" -> {
                        conflicts++
                        pending++
                        // A concurrent edit may have changed the row during network I/O.
                        // Re-read it after the same bounded backoff rather than treating
                        // the conflict as a completed/terminal record.
                        settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_PENDING_KEY, "true")
                        settings.setPreference(SYNOPSIS_RECOVERY_CYCLE_RECOVERABLE_KEY, "true")
                    }
                }
                if (clearedStaleDisplay) cleared++

                // Advance only after the record was processed normally. Cancellation
                // above leaves the cursor before this anime so the work resumes safely.
                cursor = row.id
                settings.setPreference(SYNOPSIS_RECOVERY_CURSOR_KEY, cursor.toString())
            }
        }

        Log.i(
            TAG,
            "Synopsis recovery scanned=" + scanned +
                " translated=" + translatedCount +
                " cacheHits=" + cacheHits +
                " alreadyValid=" + alreadyValid +
                " failed=" + failed +
                " pending=" + pending +
                " recoverableFailures=" + recoverableFailures +
                " translationRequests=" + translationAttempts +
                " sourceLookups=" + sourceLookups +
                " staleDisplaysCleared=" + cleared +
                " conflicts=" + conflicts +
                " manualSkipped=" + manualSkipped +
                " cursor=" + cursor,
        )
        return SynopsisRecoveryBatchResult(
            scannedCount = scanned,
            translatedCount = translatedCount,
            pendingCount = pending,
            recoverableFailureCount = recoverableFailures,
            translationRequestCount = translationAttempts,
            sourceLookupCount = sourceLookups,
            hasMoreInCycle = !cycleCompleted,
        )
    }

    private fun hasManuallyOwnedSynopsis(record: ReiAnixAnimeRecord): Boolean {
        if (
            record.metadataSource.equals("manual", ignoreCase = true) ||
            record.metadataStatus.equals("manual", ignoreCase = true)
        ) return true
        val raw = record.metadataManualFieldsJson?.trim().orEmpty()
        if (raw.isBlank()) return false
        return try {
            val fields = JSONArray(raw)
            (0 until fields.length()).any {
                fields.optString(it).trim().equals("description", ignoreCase = true)
            }
        } catch (_: Exception) {
            // Do not overwrite an anime if ownership metadata cannot be read safely.
            true
        }
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
                        localizer.isValidPtBrResult(remoteOriginal, existingDescription)
                    when {
                        localizer == null -> null
                        existingTranslationIsValid -> existingDescription
                        else -> {
                            val cached = localizer.getCachedDescriptionPtBr(remoteOriginal)
                                ?.takeIf { localizer.isValidPtBrResult(remoteOriginal, it) }
                            val candidate = cached ?: localizer.localizeToPtBr(remoteOriginal)
                            candidate?.takeIf { localizer.isValidPtBrResult(remoteOriginal, it) }
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
                clearUntranslatedDescriptionIfMatches = if (
                    remoteOriginalDescription != null && displayDescription == null
                ) {
                    local.description?.takeIf(String::isNotBlank)
                } else {
                    null
                },
                validateSynopsisSnapshot = true,
                expectedSynopsisOriginal = local.descriptionOriginal,
                expectedSynopsisDescription = local.description,
            )
            if (!manual && updated.anilistId != remote.id.toLong()) {
                return AniListResult.Failure(
                    AniListError.InvalidRequest(
                        "Automatic match was not applied because a manual or existing association has priority.",
                    ),
                )
            }
            // The metadata patch above is already committed to the canonical database.
            // Keep genre identity associations aligned when AniList supplied real labels;
            // empty remote arrays intentionally do not erase previously persisted genres.
            if (remote.genres.isNotEmpty() && !manualFieldOwnsGenres(updated.metadataManualFieldsJson)) {
                try {
                    effectiveGenreRegistry.syncAnime(
                        animeId = updated.id,
                        names = remote.genres,
                        source = if (manual) "manual" else "anilist",
                        replaceSource = true,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A registry failure must not misreport the already-committed metadata
                    // transaction as rolled back. The explicit organizer initialization can
                    // backfill the JSON column into the canonical registry later.
                    Log.w(TAG, "AniList metadata committed but genre registry sync failed", error)
                }
            }
            enqueueMetadataCommit(updated.id, requestArtwork = artworkEnabled)
            AniListResult.Success(updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AniListResult.Failure(AniListError.InvalidResponse(error.message ?: "Could not persist AniList metadata."))
        }
    }

    /**
     * Notifies projection consumers after the canonical metadata transaction commits.
     * The Kotlin artwork service is queued separately, and mailbox failure does not
     * roll back metadata that has already been persisted.
     */
    private suspend fun enqueueMetadataCommit(animeId: Long, requestArtwork: Boolean) {
        val publisher = metadataCommitPublisher ?: return
        try {
            val published = withContext(Dispatchers.IO) { publisher(animeId, requestArtwork) }
            if (!published) Log.w(TAG, "Could not publish AniList metadata update to native consumers")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not publish AniList metadata update to native consumers", error)
        }
    }

    /** Metadata patch ownership applies to the normalized genre relation as well. */
    private fun manualFieldOwnsGenres(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return false
        return try {
            val fields = JSONArray(value)
            (0 until fields.length()).any {
                fields.optString(it).trim().equals("genres", ignoreCase = true)
            }
        } catch (_: Exception) {
            value.contains("genres", ignoreCase = true)
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
            "ambiguous",
        )
        const val AUTO_MATCH_COOLDOWN_MS = 6 * 60 * 60 * 1000L
        private val synopsisRecoveryInProgress = AtomicBoolean(false)
        private const val SYNOPSIS_RECOVERY_CURSOR_KEY = "metadata.synopsis_recovery_cursor"
        private const val SYNOPSIS_RECOVERY_HIGH_WATER_KEY = "metadata.synopsis_recovery_high_water"
        private const val SYNOPSIS_RECOVERY_STATE_KEY = "metadata.synopsis_recovery_state"
        private const val SYNOPSIS_RECOVERY_CYCLE_PENDING_KEY = "metadata.synopsis_recovery_cycle_pending"
        private const val SYNOPSIS_RECOVERY_CYCLE_RECOVERABLE_KEY = "metadata.synopsis_recovery_cycle_recoverable"
        private const val SYNOPSIS_RECOVERY_RETRY_AFTER_KEY = "metadata.synopsis_recovery_retry_after"
        private const val SYNOPSIS_RECOVERY_PAGE_SIZE = 64
        private const val MAX_SYNOPSIS_CANDIDATES_PER_RUN = 512
        private const val MAX_SYNOPSIS_TRANSLATION_ATTEMPTS_PER_RUN = 8
        private const val MAX_SYNOPSIS_SOURCE_LOOKUPS_PER_RUN = 4
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
