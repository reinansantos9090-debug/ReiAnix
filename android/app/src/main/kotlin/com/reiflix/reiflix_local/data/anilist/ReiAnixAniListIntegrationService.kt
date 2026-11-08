package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.organize.ReiAnixGenreRegistry
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkCandidate
import com.reiflix.reiflix_local.data.artwork.ReiAnixAniListCoverCandidate
import com.reiflix.reiflix_local.data.artwork.aniListArtworkCandidateKey
import com.reiflix.reiflix_local.data.artwork.validReiAnixArtworkHttpUrl
import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer

import com.reiflix.reiflix_local.data.tracemoe.MAX_TRACE_MOE_EPISODE_SAMPLES
import com.reiflix.reiflix_local.data.tracemoe.ReiAnixTraceMoeClient
import com.reiflix.reiflix_local.data.tracemoe.TraceMoeApiException
import com.reiflix.reiflix_local.data.tracemoe.TraceMoeFailureKind
import com.reiflix.reiflix_local.data.tracemoe.TraceMoeEpisodeSample
import com.reiflix.reiflix_local.data.tracemoe.TraceMoeSearchMatch
import com.reiflix.reiflix_local.data.tracemoe.TraceMoeIdentificationProgress
import com.reiflix.reiflix_local.data.tracemoe.TraceMoeIdentificationProgressPhase
import com.reiflix.reiflix_local.data.tracemoe.hasRemainingTraceMoeResponseSlots
import com.reiflix.reiflix_local.data.tracemoe.shouldAdvanceToNextTraceMoeEpisodeAfterFailure
import com.reiflix.reiflix_local.data.tracemoe.latestTraceMoeEpisodeMatches
import com.reiflix.reiflix_local.data.tracemoe.appendTraceMoeEpisodeMatches
import com.reiflix.reiflix_local.data.tracemoe.evaluateTraceMoeProgressiveIdentity
import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeMetadataPatch
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reiflix.reiflix_local.data.sqlite.updateAniListMetadataAndAssociation
import com.reiflix.reiflix_local.data.sqlite.recordAniListAutoMatchOutcome
import com.reiflix.reiflix_local.data.sqlite.setMetadataStatusByLookupTitle
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository

import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun shouldAllowTraceMoeWithAutoMatchSetting(
    autoMatchEnabled: Boolean,
    userInitiated: Boolean,
): Boolean = autoMatchEnabled || userInitiated

internal fun shouldHonorTraceMoeSuccessfulCooldown(
    userInitiated: Boolean,
    previousAttemptSeconds: Double?,
    nowSeconds: Double,
    cooldownSeconds: Long,
): Boolean {
    if (userInitiated) return false
    val previous = previousAttemptSeconds?.takeIf { it.isFinite() } ?: return false
    return nowSeconds - previous < cooldownSeconds
}

private const val MAX_ARTWORK_CANDIDATES = 5

/**
 * Artwork lookup preserves provider/query order and checks only whether a candidate has an image.
 * Identity scoring remains available to the separate metadata auto-match path.
 */
internal fun usableAniListArtworkCandidates(
    candidates: Collection<AniListAnime>,
): List<AniListAnime> =
    candidates.asSequence()
        .filter { remote ->
            remote.id > 0 &&
                (!remote.coverExtraLargeUrl.isNullOrBlank() ||
                    !remote.coverLargeUrl.isNullOrBlank() || !remote.bannerUrl.isNullOrBlank())
        }
        .distinctBy { it.id }
        .take(MAX_ARTWORK_CANDIDATES)
        .toList()


/** Pure policy for bounded discovery of missing posters from the canonical catalogue. */
@Suppress("UNUSED_PARAMETER")
internal fun shouldDiscoverMissingArtwork(
    coverUrl: String?,
    hasUsableLocalCover: Boolean,
    hasAniListId: Boolean,
    manualMetadata: Boolean,
    matchStatus: String?,
    metadataFetchedAtSeconds: Double?,
    nowSeconds: Double,
    hasExhaustedArtworkFailure: Boolean = false,
): Boolean {
    if (hasUsableLocalCover) return false
    if (!coverUrl.isNullOrBlank()) {
        // A valid canonical URL is already handled by the downloader; an exhausted
        // failure is eligible for a fresh catalogue lookup after its persisted backoff.
        // Use the same HTTPS-only rule as the AniList parser and artwork downloader.
        // A persisted cleartext HTTP URL is unusable on Android and must not block recovery.
        val hasUsableHttpUrl = validReiAnixArtworkHttpUrl(coverUrl) != null
        if (hasUsableHttpUrl && !hasExhaustedArtworkFailure) return false
    }
    // Identity and metadata provenance describe association confidence, not whether an
    // image may be displayed. Manual metadata remains untouched by artwork-only lookup.
    val fetchedAt = metadataFetchedAtSeconds
    if (!manualMetadata && fetchedAt != null && fetchedAt.isFinite() &&
        nowSeconds - fetchedAt < MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS
    ) return false
    return true
}

internal const val MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS = 24L * 60L * 60L


internal fun rotateArtworkDiscoveryIds(ids: List<Long>, cursor: Long): List<Long> {
    val sorted = ids.filter { it > 0L }.distinct().sorted()
    if (sorted.isEmpty()) return emptyList()
    val start = sorted.indexOfFirst { it > cursor }.let { if (it < 0) 0 else it }
    return sorted.drop(start) + sorted.take(start)
}

internal const val ARTWORK_DISCOVERY_CURSOR_PREFERENCE = "__reianix.internal.artwork_discovery_cursor_v1"
internal const val ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX = "__reianix.internal.artwork_discovery_attempt_v1."

/** Read attempt timestamps from the existing schema-v29 preferences table. */
internal suspend fun readPersistedArtworkDiscoveryAttemptTimes(
    database: ReiAnixSqliteDatabase,
): Map<Long, Double> = withContext(Dispatchers.IO) {
    database.read { connection ->
        connection.rawQuery(
            "SELECT key, value FROM preferences WHERE substr(key, 1, ?) = ?",
            arrayOf(
                ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX.length.toString(),
                ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX,
            ),
        ).use { cursor ->
            buildMap<Long, Double> {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0).orEmpty()
                    val animeId = key.removePrefix(ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX)
                        .toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?: continue
                    val attemptedAt = cursor.getString(1)?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() }
                        ?: continue
                    put(animeId, attemptedAt)
                }
            }
        }
    }
}

/** Commit a recovery attempt timestamp to the same canonical preferences table. */
internal suspend fun writePersistedArtworkDiscoveryAttemptAt(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
    attemptAtSeconds: Double,
) = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Artwork discovery requires a positive local anime ID." }
    require(attemptAtSeconds.isFinite()) { "Artwork discovery attempt timestamp must be finite." }
    database.transaction { connection ->
        val values = ContentValues().apply {
            put("key", artworkDiscoveryAttemptPreferenceKey(animeId))
            put("value", attemptAtSeconds.toString())
            put("updated_at", attemptAtSeconds)
        }
        connection.insertWithOnConflict(
            "preferences",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

internal fun artworkDiscoveryAttemptPreferenceKey(animeId: Long): String {
    require(animeId > 0L) { "Artwork discovery requires a positive local anime ID." }
    return ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX + animeId
}

internal fun shouldDeferArtworkDiscoveryAttempt(
    lastAttemptSeconds: Double?,
    nowSeconds: Double,
): Boolean {
    val lastAttempt = lastAttemptSeconds?.takeIf { it.isFinite() } ?: return false
    return nowSeconds - lastAttempt < MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS
}


internal fun boundedAniListSearchTerms(candidates: List<String>, limit: Int = 6): List<String> {
    require(limit in 1..8) { "AniList title search limit must be between 1 and 8." }
    val seen = HashSet<String>()
    return candidates.asSequence()
        .flatMap { MediaTitleNormalizer.searchTitleVariants(it).asSequence() }
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length in 2..120 }
        .filter { seen.add(MediaTitleNormalizer.normalizeTitle(it)) }
        .take(limit)
        .toList()
}



/**
 * AniList domain integration used by native ViewModels. The canonical SQLite
 * repository remains the sole persistence authority; remote failures retain
 * existing local metadata and update only its freshness/status projection. Requested
 * artwork is materialized by the Kotlin artwork service after metadata commits.
 */
class ReiAnixAniListIntegrationService(
    private val client: ReiAnixAniListClient,
    private val library: ReiAnixSqliteLibraryRepository,
    private val settings: ReiAnixSqliteSettingsRepository,
    private val descriptionLocalizer: ReiAnixAniListDescriptionLocalizer? = null,
    private val metadataCommitPublisher: ((Long, Boolean) -> Boolean)? = null,
    private val genreRegistry: ReiAnixGenreRegistry? = null,
    private val artworkCandidatePublisher: ((Long, List<ReiAnixArtworkCandidate>) -> Boolean)? = null,
    private val traceMoeClient: ReiAnixTraceMoeClient = ReiAnixTraceMoeClient(),
) {
    private val effectiveGenreRegistry: ReiAnixGenreRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        genreRegistry ?: ReiAnixGenreRegistry(library.database)
    }
    private val autoMatchAttempts: ConcurrentHashMap<Long, Long> = autoMatchAttemptTimestamps
    private val traceMoeIdentificationInFlight = ConcurrentHashMap.newKeySet<Long>()

    private data class TraceMoeProgressState(
        val matchesByEpisode: LinkedHashMap<Long, List<TraceMoeSearchMatch>>,
        val completed: Boolean,
        val successfulApiEpisodeIds: Set<Long>,
        val hasSuccessfulApiEpisodeMetadata: Boolean,
    )
    private val missingArtworkLookupAttempts = ConcurrentHashMap<Long, Long>()
    private val missingArtworkRecoveryRunning = AtomicBoolean(false)

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
        artworkCandidatePublisher = { animeId, candidates ->
            ReiAnixAppDependencies.existingOrNull(context.applicationContext)
                ?.artworkService?.requestAnimeArtworkCandidates(animeId, candidates) ?: false
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
     * Uses every available local episode thumbnail to verify or correct an automatic
     * AniList association. Only the latest three successful Trace.moe responses form
     * each identity decision; later episodes keep replacing the oldest evidence until
     * a clear match or a two-episode majority is found. Manual metadata is protected.
     */
    suspend fun identifyFromEpisodeThumbnails(
        animeId: Long,
        samples: List<TraceMoeEpisodeSample>,
        userInitiated: Boolean = false,
        onProgress: (TraceMoeIdentificationProgress) -> Unit = {},
    ): Boolean = identifyFromEpisodeThumbnails(animeId, samples.asFlow(), userInitiated, onProgress)

    /**
     * Consume episode thumbnails as soon as they become available. The app may
     * inspect any number of episodes, while limiting each identity decision to a
     * rolling window of three successful API responses. The upstream flow is
     * cancelled only after a reliable identity is confirmed or the source closes.
     */
    suspend fun identifyFromEpisodeThumbnails(
        animeId: Long,
        samples: Flow<TraceMoeEpisodeSample>,
        userInitiated: Boolean = false,
        onProgress: (TraceMoeIdentificationProgress) -> Unit = {},
    ): Boolean {
        if (animeId <= 0L || !traceMoeIdentificationInFlight.add(animeId)) return false
        return try {
            identifyFromEpisodeThumbnailsInternal(animeId, samples, userInitiated, onProgress)
        } finally {
            traceMoeIdentificationInFlight.remove(animeId)
        }
    }

    private suspend fun identifyFromEpisodeThumbnailsInternal(
        animeId: Long,
        samples: Flow<TraceMoeEpisodeSample>,
        userInitiated: Boolean,
        onProgress: (TraceMoeIdentificationProgress) -> Unit,
    ): Boolean {
        if (animeId <= 0L || !isAniListEnabled() ||
            !shouldAllowTraceMoeWithAutoMatchSetting(
                autoMatchEnabled = settingEnabled("metadata.auto_match", defaultValue = true),
                userInitiated = userInitiated,
            )
        ) return false

        var local = library.getAnime(animeId) ?: return false
        if (local.anilistMatchManual == true || local.metadataSource == "manual" || local.metadataStatus == "manual") {
            return false
        }

        val correction = local.anilistId != null
        val persisted = readTraceMoeProgress(animeId, correction)
        val nowSeconds = System.currentTimeMillis() / 1000.0
        val previousAttempt = readTraceMoeAttemptAt(animeId, correction)
        if (!userInitiated && persisted.completed && shouldHonorTraceMoeSuccessfulCooldown(
                userInitiated = false,
                previousAttemptSeconds = previousAttempt,
                nowSeconds = nowSeconds,
                cooldownSeconds = TRACE_MOE_ATTEMPT_COOLDOWN_SECONDS,
            )
        ) return false

        // A fresh manual tap is a new identification session. Previous inconclusive
        // results must not make it jump over episode 1, and the SHA-256 cache still
        // makes already-searched thumbnails effectively immediate without another API call.
        var matchesByEpisode = if (userInitiated || persisted.completed) {
            linkedMapOf()
        } else {
            latestTraceMoeEpisodeMatches(persisted.matchesByEpisode)
        }
        val successfulApiEpisodeIds = linkedSetOf<Long>()
        if (!userInitiated && !persisted.completed) {
            if (persisted.hasSuccessfulApiEpisodeMetadata) {
                successfulApiEpisodeIds.addAll(persisted.successfulApiEpisodeIds)
            } else {
                // Legacy non-empty result arrays prove a successful API response;
                // legacy empty arrays are ambiguous and are deliberately retried.
                successfulApiEpisodeIds.addAll(
                    persisted.matchesByEpisode.filterValues { it.isNotEmpty() }.keys,
                )
            }
            successfulApiEpisodeIds.addAll(matchesByEpisode.keys)
        }
        var successfulResponses = successfulApiEpisodeIds.size
        writeTraceMoeProgress(
            animeId,
            matchesByEpisode,
            completed = false,
            correction = correction,
            successfulApiEpisodeIds = successfulApiEpisodeIds,
        )

        val seenEpisodeIds = HashSet<Long>().apply {
            addAll(successfulApiEpisodeIds)
            addAll(matchesByEpisode.keys)
        }
        var attemptedEpisodes = 0
        var lastEpisodeFailure: TraceMoeApiException? = null
        var confirmedIdentity = evaluateTraceMoeProgressiveIdentity(matchesByEpisode)

        fun report(
            phase: TraceMoeIdentificationProgressPhase,
            sample: TraceMoeEpisodeSample? = null,
            message: String,
        ) {
            onProgress(
                TraceMoeIdentificationProgress(
                    phase = phase,
                    episodeId = sample?.episodeId,
                    thumbnailPath = sample?.imageFile?.absolutePath,
                    validResponses = successfulResponses.coerceAtMost(MAX_TRACE_MOE_EPISODE_SAMPLES),
                    message = message,
                ),
            )
        }

        // Three is a hard cap for successful API responses in this identification
        // session. Failed requests do not increment this counter and can be replaced
        // by later episodes; an inconclusive third valid response ends the search.
        while (
            confirmedIdentity == null &&
            hasRemainingTraceMoeResponseSlots(successfulResponses)
        ) {
            currentCoroutineContext().ensureActive()
            val sample = samples.firstOrNull { candidate ->
                candidate.episodeId > 0L && seenEpisodeIds.add(candidate.episodeId)
            } ?: break

            attemptedEpisodes += 1
            report(
                TraceMoeIdentificationProgressPhase.PREPARING_THUMBNAIL,
                sample,
                "Preparando a thumbnail de ${sample.imageFile.name}...",
            )
            val usable = withContext(Dispatchers.IO) {
                sample.imageFile.isFile &&
                    sample.imageFile.length() in 4L..ReiAnixTraceMoeClient.MAX_IMAGE_BYTES &&
                    VideoThumbnailExtractor.isUsableThumbnailFile(sample.imageFile.absolutePath)
            }
            if (!usable) {
                report(
                    TraceMoeIdentificationProgressPhase.SKIPPING_FAILED_EPISODE,
                    sample,
                    "Thumbnail inválida. Passando para o próximo episódio...",
                )
                continue
            }

            report(
                TraceMoeIdentificationProgressPhase.SENDING_TO_API,
                sample,
                "Enviando thumbnail ao Trace.moe (${successfulResponses.coerceAtMost(MAX_TRACE_MOE_EPISODE_SAMPLES)}/$MAX_TRACE_MOE_EPISODE_SAMPLES respostas válidas)...",
            )
            val matches = try {
                traceMoeClient.search(
                    imageFile = sample.imageFile,
                    onQuotaWait = {
                        report(
                            TraceMoeIdentificationProgressPhase.WAITING_FOR_QUOTA,
                            sample,
                            "Aguardando brevemente a disponibilidade da API Trace.moe...",
                        )
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TraceMoeApiException) {
                if (!shouldAdvanceToNextTraceMoeEpisodeAfterFailure(error.kind)) {
                    throw error
                }
                lastEpisodeFailure = error
                Log.w(
                    TAG,
                    "Trace.moe episode failed; replacing it with a later thumbnail kind=" +
                        error.kind + " httpStatus=" + error.httpStatus +
                        " animeId=" + animeId + " episodeId=" + sample.episodeId,
                    error,
                )
                report(
                    TraceMoeIdentificationProgressPhase.SKIPPING_FAILED_EPISODE,
                    sample,
                    "A consulta deste episódio falhou. Tentando a próxima thumbnail sem consumir uma resposta válida...",
                )
                continue
            }

            // Any normal HTTP/API response, including an empty result list, is one
            // successful response and consumes exactly one of the three slots.
            successfulResponses += 1
            successfulApiEpisodeIds.add(sample.episodeId)
            matchesByEpisode = appendTraceMoeEpisodeMatches(
                matchesByEpisode,
                episodeId = sample.episodeId,
                matches = matches,
            )
            writeTraceMoeProgress(
                animeId,
                matchesByEpisode,
                completed = false,
                correction = correction,
                successfulApiEpisodeIds = successfulApiEpisodeIds,
            )
            report(
                TraceMoeIdentificationProgressPhase.RESPONSE_RECEIVED,
                sample,
                "Resposta recebida (${successfulResponses}/$MAX_TRACE_MOE_EPISODE_SAMPLES respostas válidas). Verificando a correspondência...",
            )
            confirmedIdentity = evaluateTraceMoeProgressiveIdentity(matchesByEpisode)
        }

        val consensus = confirmedIdentity
        if (consensus == null) {
            // A no-match result after three valid responses is final for this session.
            // Persist completion to prevent automatic loops; a manual retry starts fresh.
            writeTraceMoeProgress(
                animeId,
                matchesByEpisode,
                completed = true,
                correction = correction,
                successfulApiEpisodeIds = successfulApiEpisodeIds,
            )
            writeTraceMoeAttemptAt(animeId, System.currentTimeMillis() / 1000.0, correction)
            if (lastEpisodeFailure != null &&
                successfulResponses < MAX_TRACE_MOE_EPISODE_SAMPLES &&
                attemptedEpisodes > 0
            ) {
                throw lastEpisodeFailure
            }
            return false
        }

        report(
            TraceMoeIdentificationProgressPhase.UPDATING_COVER,
            message = "Correspondência confiável encontrada. Buscando a capa no AniList...",
        )
        local = library.getAnime(animeId) ?: return false
        if (local.anilistMatchManual == true || local.metadataSource == "manual" || local.metadataStatus == "manual") {
            return false
        }
        val remote = when (val result = client.getAnime(consensus.anilistId)) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> {
                Log.w(TAG, "AniList detail lookup failed for Trace.moe ID=${consensus.anilistId}: ${result.error}")
                return false
            }
        } ?: return false
        if (remote.id != consensus.anilistId) return false

        val applied = when (
            val result = applyRemote(
                local = local,
                remote = remote,
                manual = false,
                score = consensus.averageSimilarity,
                margin = consensus.margin,
                traceMoeConfirmed = true,
            )
        ) {
            is AniListResult.Success -> result.value.anilistId == consensus.anilistId.toLong()
            is AniListResult.Failure -> {
                Log.w(TAG, "Trace.moe identity could not be committed for animeId=$animeId: ${result.error}")
                false
            }
        }
        if (applied) {
            val completedAt = System.currentTimeMillis() / 1000.0
            writeTraceMoeProgress(
                animeId,
                matchesByEpisode,
                completed = true,
                correction = correction,
                successfulApiEpisodeIds = successfulApiEpisodeIds,
            )
            writeTraceMoeAttemptAt(animeId, completedAt, correction)
        }
        return applied
    }

    private suspend fun readTraceMoeAttemptAt(animeId: Long, correction: Boolean): Double? = withContext(Dispatchers.IO) {
        val prefix = if (correction) TRACE_MOE_CORRECTION_ATTEMPT_PREFERENCE_PREFIX else TRACE_MOE_ATTEMPT_PREFERENCE_PREFIX
        library.database.read { connection ->
            connection.rawQuery("SELECT value FROM preferences WHERE key=? LIMIT 1", arrayOf(prefix + animeId)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.toDoubleOrNull()?.takeIf { it.isFinite() } else null
            }
        }
    }

    private suspend fun writeTraceMoeAttemptAt(animeId: Long, attemptAtSeconds: Double, correction: Boolean) = withContext(Dispatchers.IO) {
        val prefix = if (correction) TRACE_MOE_CORRECTION_ATTEMPT_PREFERENCE_PREFIX else TRACE_MOE_ATTEMPT_PREFERENCE_PREFIX
        library.database.transaction { connection ->
            val values = ContentValues().apply {
                put("key", prefix + animeId)
                put("value", attemptAtSeconds.toString())
                put("updated_at", attemptAtSeconds)
            }
            connection.insertWithOnConflict("preferences", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private suspend fun readTraceMoeProgress(animeId: Long, correction: Boolean): TraceMoeProgressState = withContext(Dispatchers.IO) {
        val prefix = if (correction) TRACE_MOE_CORRECTION_PROGRESS_PREFERENCE_PREFIX else TRACE_MOE_PROGRESS_PREFERENCE_PREFIX
        val raw = library.database.read { connection ->
            connection.rawQuery("SELECT value FROM preferences WHERE key=? LIMIT 1", arrayOf(prefix + animeId)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
        if (raw.isNullOrBlank()) {
            return@withContext TraceMoeProgressState(
                matchesByEpisode = linkedMapOf(),
                completed = false,
                successfulApiEpisodeIds = emptySet(),
                hasSuccessfulApiEpisodeMetadata = true,
            )
        }
        try {
            val root = JSONObject(raw)
            val episodes = root.optJSONArray("episodes") ?: JSONArray()
            val results = linkedMapOf<Long, List<TraceMoeSearchMatch>>()
            for (index in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(index) ?: continue
                val episodeId = episode.optLong("episodeId", 0L)
                if (episodeId <= 0L) continue
                val rawMatches = episode.optJSONArray("matches") ?: JSONArray()
                val matches = buildList {
                    for (matchIndex in 0 until rawMatches.length()) {
                        val item = rawMatches.optJSONObject(matchIndex) ?: continue
                        val anilistId = item.optInt("anilistId", 0)
                        val similarity = item.optDouble("similarity", Double.NaN)
                        if (anilistId > 0 && similarity.isFinite() && similarity in 0.0..1.0) {
                            add(TraceMoeSearchMatch(anilistId, similarity))
                        }
                    }
                }
                results[episodeId] = matches
            }
            val successfulIdsJson = root.optJSONArray("successfulApiEpisodeIds")
            val successfulIds = linkedSetOf<Long>()
            if (successfulIdsJson != null) {
                for (index in 0 until successfulIdsJson.length()) {
                    successfulIdsJson.optLong(index, 0L).takeIf { it > 0L }?.let(successfulIds::add)
                }
            }
            TraceMoeProgressState(
                matchesByEpisode = results,
                completed = root.optBoolean("completed", false),
                successfulApiEpisodeIds = successfulIds,
                hasSuccessfulApiEpisodeMetadata = successfulIdsJson != null,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Stored Trace.moe progress is unreadable for animeId=$animeId", error)
            TraceMoeProgressState(
                matchesByEpisode = linkedMapOf(),
                completed = false,
                successfulApiEpisodeIds = emptySet(),
                hasSuccessfulApiEpisodeMetadata = false,
            )
        }
    }

    private suspend fun writeTraceMoeProgress(
        animeId: Long,
        matchesByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
        completed: Boolean,
        correction: Boolean,
        successfulApiEpisodeIds: Set<Long>,
    ) = withContext(Dispatchers.IO) {
        val prefix = if (correction) TRACE_MOE_CORRECTION_PROGRESS_PREFERENCE_PREFIX else TRACE_MOE_PROGRESS_PREFERENCE_PREFIX
        val episodes = JSONArray()
        matchesByEpisode.forEach { (episodeId, matches) ->
            val matchArray = JSONArray()
            matches.forEach { match ->
                matchArray.put(JSONObject().put("anilistId", match.anilistId).put("similarity", match.similarity))
            }
            episodes.put(JSONObject().put("episodeId", episodeId).put("matches", matchArray))
        }
        val successfulIds = JSONArray().apply {
            successfulApiEpisodeIds.sorted().forEach { put(it) }
        }
        val value = JSONObject()
            .put("completed", completed)
            .put("episodes", episodes)
            .put("successfulApiEpisodeIds", successfulIds)
        library.database.transaction { connection ->
            val values = ContentValues().apply {
                put("key", prefix + animeId)
                put("value", value.toString())
                put("updated_at", System.currentTimeMillis() / 1000.0)
            }
            connection.insertWithOnConflict("preferences", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
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
        val localTitleCandidates = buildList {
            add(local.title)
            add(local.lookupTitle)
            add(local.nativeTitle.orEmpty())
            add(local.romaji.orEmpty())
            add(local.english.orEmpty())
            local.aliasesJson?.let { aliasesJson ->
                runCatching { JSONArray(aliasesJson) }.getOrNull()?.let { aliases ->
                    for (index in 0 until aliases.length()) {
                        val raw = aliases.opt(index)
                        if (raw is String) add(raw)
                    }
                }
            }
        }
        val searchTerms = boundedAniListSearchTerms(localTitleCandidates, limit = 6)
        if (searchTerms.isEmpty()) {
            autoMatchAttempts.remove(animeId)
            return AniListResult.Failure(
                AniListError.InvalidRequest("Local anime has no usable title for AniList lookup."),
            )
        }
        val searchContext = ReiAnixAniListMatcher.Context(
            year = local.year,
            mediaKind = local.mediaKind,
        )
        fun rankCandidates(
            candidates: Collection<AniListAnime>,
            titles: List<String>,
        ): List<ReiAnixAniListMatcher.RankedCandidate> = candidates.mapNotNull { candidate ->
            titles.mapNotNull { title ->
                ReiAnixAniListMatcher.rank(title, listOf(candidate), searchContext).firstOrNull()
            }.maxByOrNull { it.score }
        }.sortedWith(
            compareByDescending<ReiAnixAniListMatcher.RankedCandidate> { it.score }
                .thenBy { (it.anime.titleEnglish ?: it.anime.titleRomaji ?: "").lowercase(Locale.ROOT) }
                .thenBy { it.anime.id },
        )

        val candidatesById = linkedMapOf<Int, AniListAnime>()
        var ranked: List<ReiAnixAniListMatcher.RankedCandidate> = emptyList()
        var searchedTerms: List<String> = emptyList()
        for ((termIndex, searchTerm) in searchTerms.withIndex()) {
            searchedTerms = searchTerms.take(termIndex + 1)
            val searchResult = try {
                client.searchAnime(searchTerm, 10)
            } catch (cancelled: CancellationException) {
                autoMatchAttempts.remove(animeId)
                throw cancelled
            }
            val found = when (searchResult) {
                is AniListResult.Success -> searchResult.value
                is AniListResult.Failure -> {
                    val outcome = when (val error = searchResult.error) {
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
                            return searchResult
                        }
                    }
                    // Never auto-associate from a partial candidate set after a transient
                    // failure on one of the alternate title queries.
                    recordAutoMatchOutcome(animeId, outcome)
                    return searchResult
                }
            }
            found.forEach { candidate -> candidatesById.putIfAbsent(candidate.id, candidate) }
            ranked = rankCandidates(candidatesById.values, searchTerms.take(termIndex + 1))

            // Preserve the fast path for an already-unambiguous primary-title result.
            // Alternative titles are queried only when the primary result is absent,
            // low-confidence or ambiguous, avoiding extra requests for common cases.
            if (termIndex == 0) {
                val best = ranked.firstOrNull()
                val margin = best?.let { it.score - (ranked.getOrNull(1)?.score ?: 0.0) } ?: 0.0
                if (
                    best != null &&
                    best.score >= AUTO_MATCH_MIN_SCORE &&
                    margin >= AUTO_MATCH_MIN_MARGIN
                ) break
            }
        }
        if (candidatesById.isEmpty()) {
            recordAutoMatchOutcome(animeId, "not_found")
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }
        ranked = rankCandidates(candidatesById.values, searchedTerms)
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


    private fun publishArtworkCandidates(animeId: Long, candidates: List<AniListAnime>): Boolean {
        val publisher = artworkCandidatePublisher ?: return false
        val artworkCandidates = usableAniListArtworkCandidates(candidates)
            .map { remote ->
                ReiAnixArtworkCandidate(
                    anilistId = remote.id.toLong(),
                    posterUrl = remote.coverExtraLargeUrl,
                    bannerUrl = remote.bannerUrl,
                    posterFallbackUrl = remote.coverLargeUrl,
                )
            }
            .toList()
        if (artworkCandidates.isEmpty()) return false
        return runCatching { publisher(animeId, artworkCandidates) }
            .onFailure { error ->
                Log.w(TAG, "Could not enqueue AniList artwork candidates for animeId=" + animeId, error)
            }
            .getOrDefault(false)
    }

    /**
     * Read-only artwork search. Results remain in provider/query order; identity matching
     * is not consulted when deciding which returned images may be published.
     * A failed alias request does not discard results already obtained from other aliases.
     */
    private suspend fun discoverArtworkCandidates(local: ReiAnixAnimeRecord): List<AniListAnime> {
        val titleCandidates = buildList {
            add(local.title)
            add(local.lookupTitle)
            add(local.nativeTitle.orEmpty())
            add(local.romaji.orEmpty())
            add(local.english.orEmpty())
            local.aliasesJson?.let { raw ->
                runCatching { JSONArray(raw) }.getOrNull()?.let { aliases ->
                    for (index in 0 until aliases.length()) {
                        (aliases.opt(index) as? String)?.let(::add)
                    }
                }
            }
        }
        val terms = boundedAniListSearchTerms(titleCandidates, limit = 6)
        if (terms.isEmpty()) return emptyList()
        val candidatesById = linkedMapOf<Int, AniListAnime>()
        for (term in terms) {
            currentCoroutineContext().ensureActive()
            when (val result = try {
                client.searchAnime(term, 10)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "AniList artwork search failed for animeId=" + local.id + " title=" + term, error)
                continue
            }) {
                is AniListResult.Success -> result.value.forEach { remote ->
                    if (remote.id > 0) candidatesById.putIfAbsent(remote.id, remote)
                }
                is AniListResult.Failure -> Log.w(
                    TAG,
                    "AniList artwork search failed for animeId=" + local.id + " title=" + term + ": " + result.error,
                )
            }
        }
        if (candidatesById.isEmpty()) return emptyList()
        // Do not use identity confidence, exact-title checks, or metadata similarity to
        // decide which AniList images can be published. Keep the provider's result order.
        return usableAniListArtworkCandidates(candidatesById.values)
    }

    private suspend fun readArtworkDiscoveryCursor(): Long = withContext(Dispatchers.IO) {
        library.database.read { connection ->
            connection.rawQuery(
                "SELECT value FROM preferences WHERE key=? LIMIT 1",
                arrayOf(ARTWORK_DISCOVERY_CURSOR_PREFERENCE),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.toLongOrNull() ?: 0L else 0L
            }
        }
    }

    private suspend fun writeArtworkDiscoveryCursor(animeId: Long) = withContext(Dispatchers.IO) {
        library.database.transaction { connection ->
            val values = ContentValues().apply {
                put("key", ARTWORK_DISCOVERY_CURSOR_PREFERENCE)
                put("value", animeId.toString())
                put("updated_at", System.currentTimeMillis() / 1000.0)
            }
            connection.insertWithOnConflict(
                "preferences",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private suspend fun readArtworkDiscoveryAttemptTimes(): Map<Long, Double> =
        readPersistedArtworkDiscoveryAttemptTimes(library.database)

    private suspend fun writeArtworkDiscoveryAttemptAt(animeId: Long, attemptAtSeconds: Double) =
        writePersistedArtworkDiscoveryAttemptAt(library.database, animeId, attemptAtSeconds)

    /**
     * Discovers artwork for existing canonical rows whose poster URL was never saved.
     * It reuses the existing AniList client, conservative matcher and SQLite writer;
     * it does not create local anime rows or a second catalogue.
     * A small per-run budget and persisted metadata freshness prevent startup request storms.
     */
    suspend fun recoverMissingArtworkMetadata(maxRecords: Int = 12): Int {
        if (!missingArtworkRecoveryRunning.compareAndSet(false, true)) return 0
        try {
            require(maxRecords in 1..24) { "Missing-artwork lookup budget must be between 1 and 24." }
            if (!isAniListEnabled() || !settingEnabled("artwork.enabled", defaultValue = true)) return 0
            val rows = library.loadLibraryCatalogRows()
            if (rows.isEmpty()) return 0
            val rowsById = rows.associateBy { it.anime.id }
            val discoveryCursor = readArtworkDiscoveryCursor()
            val persistedAttemptTimes = readArtworkDiscoveryAttemptTimes().toMutableMap()
            val orderedRows = rotateArtworkDiscoveryIds(rowsById.keys.toList(), discoveryCursor)
                .mapNotNull(rowsById::get)
            var lastVisitedCursor = discoveryCursor
            val exhaustedPosterIds = library.database.read { connection ->
                connection.rawQuery(
                    "SELECT DISTINCT entity_id FROM artwork WHERE entity_type IN ('anime','movie') " +
                        "AND artwork_type='poster' AND status='failed' AND COALESCE(failure_count, 0)>=5",
                    null,
                ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            }
            var examined = 0
            var recovered = 0
            val nowSeconds = System.currentTimeMillis() / 1000.0
            for (row in orderedRows) {
                if (examined >= maxRecords) break
                currentCoroutineContext().ensureActive()
                val local = row.anime
                if (local.id <= 0L) continue
                lastVisitedCursor = local.id
                val cachedPath = local.coverCache?.trim().orEmpty()
                val usableLocalCover = cachedPath.isNotEmpty() && runCatching {
                    val file = File(cachedPath)
                    if (!file.isFile || file.length() <= 0L) {
                        false
                    } else {
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                        options.outWidth > 0 && options.outHeight > 0
                    }
                }.getOrDefault(false)
                if (!shouldDiscoverMissingArtwork(
                        coverUrl = local.coverUrl,
                        hasUsableLocalCover = usableLocalCover,
                        hasAniListId = local.anilistId != null,
                        hasExhaustedArtworkFailure = local.id.toString() in exhaustedPosterIds,
                        manualMetadata = local.anilistMatchManual == true ||
                            local.metadataSource == "manual" || local.metadataStatus == "manual",
                        matchStatus = local.anilistMatchStatus,
                        metadataFetchedAtSeconds = local.metadataFetchedAt,
                        nowSeconds = nowSeconds,
                    )
                ) continue
                if (shouldDeferArtworkDiscoveryAttempt(persistedAttemptTimes[local.id], nowSeconds)) {
                    continue
                }
                if (examined >= maxRecords) break
                val attemptAt = nowSeconds.toLong()
                val previousAttempt = missingArtworkLookupAttempts.putIfAbsent(local.id, attemptAt)
                if (previousAttempt != null) {
                    if (nowSeconds - previousAttempt < MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS ||
                        !missingArtworkLookupAttempts.replace(local.id, previousAttempt, attemptAt)
                    ) continue
                }
                examined++
                // Persist before the request starts so a process restart after a network
                // failure/crash cannot immediately repeat the same lookup.
                val attemptAtSeconds = attemptAt.toDouble()
                writeArtworkDiscoveryAttemptAt(local.id, attemptAtSeconds)
                persistedAttemptTimes[local.id] = attemptAtSeconds
                val remoteId = local.anilistId?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                if (remoteId != null) {
                    val remote = when (val result = client.getAnime(remoteId.toInt())) {
                        is AniListResult.Success -> result.value
                        is AniListResult.Failure -> {
                            Log.w(TAG, "Missing-artwork AniList lookup failed for animeId=" + local.id + ": " + result.error)
                            null
                        }
                    }
                    if (remote == null) {
                        if (publishArtworkCandidates(local.id, discoverArtworkCandidates(local))) recovered++
                        continue
                    }
                    val remotePosterUrl = remote.coverExtraLargeUrl ?: remote.coverLargeUrl
                    when (val applied = applyRemote(
                        local = local,
                        remote = remote,
                        manual = local.anilistMatchManual == true ||
                            local.metadataSource == "manual" || local.metadataStatus == "manual",
                        score = local.anilistMatchScore,
                        margin = local.anilistMatchMargin,
                    )) {
                        is AniListResult.Success -> {
                            if (!remotePosterUrl.isNullOrBlank() && applied.value.coverUrl == remotePosterUrl) {
                                recovered++
                            } else if (publishArtworkCandidates(local.id, discoverArtworkCandidates(local))) {
                                recovered++
                            }
                        }
                        is AniListResult.Failure -> {
                            Log.w(TAG, "Missing-artwork metadata update failed for animeId=" + local.id + ": " + applied.error)
                            if (publishArtworkCandidates(local.id, discoverArtworkCandidates(local))) recovered++
                        }
                    }
                } else {
                    // Artwork recovery is read-only with respect to identity:
                    // PROBABLE/AMBIGUOUS/REJECTED/UNRESOLVED titles can provide ranked image
                    // candidates without assigning their AniList ID to the local anime.
                    if (publishArtworkCandidates(local.id, discoverArtworkCandidates(local))) recovered++
                }
            }
            if (lastVisitedCursor > 0L && lastVisitedCursor != discoveryCursor) {
                writeArtworkDiscoveryCursor(lastVisitedCursor)
            }
            return recovered
        } finally {
            missingArtworkRecoveryRunning.set(false)
        }
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

    private suspend fun applyRemote(
        local: ReiAnixAnimeRecord,
        remote: AniListAnime,
        manual: Boolean,
        score: Double?,
        margin: Double?,
        traceMoeConfirmed: Boolean = false,
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
                traceMoeConfirmed = traceMoeConfirmed,
            )
            if (!manual && updated.anilistId != remote.id.toLong()) {
                return AniListResult.Failure(
                    AniListError.InvalidRequest(
                        "Automatic match was not applied because a manual or existing association has priority.",
                    ),
                )
            }
            // The metadata patch above is already committed to the canonical database.
            // Persist the lower-quality HTTPS variant before projection so it can be
            // tried only if the primary cover cannot be materialized.
            if (artworkEnabled) {
                try {
                    persistAniListCoverFallbackCandidates(updated.id, updated.mediaKind, remote)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not persist AniList fallback cover candidates for animeId=" + updated.id, error)
                }
            }
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

    private suspend fun persistAniListCoverFallbackCandidates(
        animeId: Long,
        mediaKind: String?,
        remote: AniListAnime,
    ) {
        if (animeId <= 0L) return
        val candidates = remote.coverCandidates.drop(1)
        val currentUrls = remote.coverCandidates.mapNotNull { validReiAnixArtworkHttpUrl(it.url) }.distinct()
        val entityType = if (mediaKind.equals("movie", ignoreCase = true)) "movie" else "anime"
        val now = System.currentTimeMillis() / 1000.0
        library.database.transaction { db ->
            // Keep old successful files for diagnosis/reuse, but make URLs absent from the
            // latest AniList response ineligible for automatic fallback selection.
            val placeholders = currentUrls.joinToString(",") { "?" }
            val excludeCurrentUrls = if (currentUrls.isEmpty()) "" else " AND external_url NOT IN ($placeholders)"
            db.execSQL(
                """
                UPDATE artwork
                SET variant='obsolete', priority=MIN(COALESCE(priority,350),350), updated_at=?
                WHERE entity_type IN ('anime','movie') AND entity_id=? AND artwork_type='poster'
                  AND LOWER(COALESCE(source,'')) IN ('anilist','cache') AND manual=0
                  AND external_url IS NOT NULL
                  AND LOWER(COALESCE(status,'')) IN ('source_candidate','ready','failed','retry_wait','downloading')
                  $excludeCurrentUrls
                """.trimIndent(),
                arrayOf<Any?>(now, animeId.toString(), *currentUrls.toTypedArray()),
            )
            // Remove stale unmaterialized candidate rows, not successful or referenced local files.
            db.delete(
                "artwork",
                "entity_type IN ('anime','movie') AND entity_id=? AND artwork_type='poster' " +
                    "AND source='anilist' AND manual=0 AND status='source_candidate' " +
                    "AND local_path IS NULL AND variant='obsolete'",
                arrayOf(animeId.toString()),
            )
            for (candidate in candidates) {
                val url = validReiAnixArtworkHttpUrl(candidate.url) ?: continue
                val key = aniListArtworkCandidateKey(animeId, remote.id.toLong(), url)
                val existing = db.rawQuery(
                    "SELECT id, source, local_path, manual, status, artwork_key, variant FROM artwork " +
                        "WHERE entity_type IN ('anime','movie') AND entity_id=? AND artwork_type='poster' " +
                        "AND (external_url=? OR source_ref=? OR artwork_key=?) " +
                        "ORDER BY priority DESC, updated_at DESC, id DESC LIMIT 1",
                    arrayOf(animeId.toString(), url, url, key),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else ExistingCoverCandidateRow(
                        id = cursor.getLong(0),
                        source = cursor.getString(1),
                        localPath = cursor.getString(2),
                        manual = cursor.getInt(3) != 0,
                        status = cursor.getString(4),
                        key = cursor.getString(5),
                        variant = cursor.getString(6),
                    )
                }

                if (existing != null) {
                    // Preserve user-owned/local art. Keep a downloaded AniList variant associated
                    // with this URL so the downloader can reuse it if the primary source later fails.
                    if (existing.manual || existing.source.equals("manual", true) ||
                        existing.source.equals("local", true)
                    ) continue
                    if (!existing.localPath.isNullOrBlank()) {
                        if (existing.source.equals("cache", true) || existing.source.equals("anilist", true)) {
                            val values = ContentValues().apply {
                                put("source_ref", url)
                                put("external_url", url)
                                put("priority", 350)
                                put("updated_at", now)
                                put("artwork_key", key)
                                put("variant", candidate.variant)
                            }
                            db.update("artwork", values, "id=? AND manual=0", arrayOf(existing.id.toString()))
                        }
                        continue
                    }
                    val candidateIdentityIsCurrent = existing.key == key &&
                        !existing.variant.equals("obsolete", true)
                    if (existing.status != null && !existing.status.equals("failed", true) &&
                        candidateIdentityIsCurrent
                    ) continue

                    // A URL which used to be primary but is now an alternate can be tried as fallback.
                    val values = ContentValues().apply {
                        put("source", "anilist")
                        put("source_ref", url)
                        put("external_url", url)
                        put("status", "source_candidate")
                        put("priority", 350)
                        put("updated_at", now)
                        put("failure_count", 0)
                        put("artwork_key", key)
                        put("variant", candidate.variant)
                        putNull("next_retry_at")
                        putNull("http_status")
                    }
                    db.update("artwork", values, "id=? AND manual=0", arrayOf(existing.id.toString()))
                    continue
                }

                val values = ContentValues().apply {
                    put("entity_type", entityType)
                    put("entity_id", animeId.toString())
                    put("artwork_type", "poster")
                    put("source", "anilist")
                    put("source_ref", url)
                    putNull("local_path")
                    put("external_url", url)
                    put("manual", 0)
                    put("priority", 350)
                    put("status", "source_candidate")
                    put("discovered_at", now)
                    put("updated_at", now)
                    putNull("last_attempt_at")
                    put("failure_count", 0)
                    put("artwork_key", key)
                    put("variant", candidate.variant)
                    putNull("byte_size")
                    putNull("width")
                    putNull("height")
                    putNull("checksum")
                    putNull("content_type")
                    putNull("last_access")
                    putNull("next_retry_at")
                    putNull("http_status")
                }
                db.insertOrThrow("artwork", null, values)
            }
        }
    }

    private data class ExistingCoverCandidateRow(
        val id: Long,
        val source: String?,
        val localPath: String?,
        val manual: Boolean,
        val status: String?,
        val key: String?,
        val variant: String?,
    )

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
        private const val TRACE_MOE_ATTEMPT_PREFERENCE_PREFIX = "__reianix.internal.trace_moe_attempt_v1."
        private const val TRACE_MOE_CORRECTION_ATTEMPT_PREFERENCE_PREFIX = "__reianix.internal.trace_moe_correction_attempt_v1."
        private const val TRACE_MOE_PROGRESS_PREFERENCE_PREFIX = "__reianix.internal.trace_moe_progress_v1."
        private const val TRACE_MOE_CORRECTION_PROGRESS_PREFERENCE_PREFIX = "__reianix.internal.trace_moe_correction_progress_v1."
        private const val TRACE_MOE_ATTEMPT_COOLDOWN_SECONDS = 24L * 60L * 60L
        private val autoMatchAttemptTimestamps = ConcurrentHashMap<Long, Long>()
        const val AUTO_MATCH_MIN_SCORE = ReiAnixAniListMatcher.AUTO_CONFIRM_SCORE
        const val AUTO_MATCH_MIN_MARGIN = ReiAnixAniListMatcher.AUTO_CONFIRM_MARGIN
        private val AUTO_MATCH_TERMINAL_STATUSES = setOf(
            "not_found",
            "ambiguous",
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
