package com.reianix.reianix_local.data.anilist

import com.reianix.reianix_local.data.organize.ReiAnixGenreRegistry
import com.reianix.reianix_local.data.artwork.ReiAnixArtworkCandidate
import com.reianix.reianix_local.data.artwork.ReiAnixPreparedRepairArtwork
import com.reianix.reianix_local.data.artwork.ReiAnixAniListCoverCandidate
import com.reianix.reianix_local.data.artwork.aniListArtworkCandidateKey
import com.reianix.reianix_local.data.artwork.validReiAnixArtworkHttpUrl
import com.reianix.reianix_local.data.media.MediaTitleNormalizer
import com.reianix.reianix_local.data.media.MediaFilenameParser
import com.reianix.reianix_local.data.sqlite.ReiAnixEpisodeRecord

import com.reianix.reianix_local.data.tracemoe.MAX_TRACE_MOE_EPISODE_SAMPLES
import com.reianix.reianix_local.data.tracemoe.ReiAnixTraceMoeClient
import com.reianix.reianix_local.data.tracemoe.TraceMoeApiException
import com.reianix.reianix_local.data.tracemoe.TraceMoeFailureKind
import com.reianix.reianix_local.data.tracemoe.TraceMoeRequestProgressStage
import com.reianix.reianix_local.data.tracemoe.TraceMoeEpisodeSample
import com.reianix.reianix_local.data.tracemoe.TraceMoeSearchMatch
import com.reianix.reianix_local.data.tracemoe.TraceMoeIdentificationProgress
import com.reianix.reianix_local.data.tracemoe.TraceMoeIdentificationProgressPhase
import com.reianix.reianix_local.data.tracemoe.hasRemainingTraceMoeResponseSlots
import com.reianix.reianix_local.data.tracemoe.shouldCompleteTraceMoeIdentification
import com.reianix.reianix_local.data.tracemoe.shouldAdvanceToNextTraceMoeEpisodeAfterFailure
import com.reianix.reianix_local.data.tracemoe.latestTraceMoeEpisodeMatches
import com.reianix.reianix_local.data.tracemoe.appendTraceMoeEpisodeMatches
import com.reianix.reianix_local.data.tracemoe.evaluateTraceMoeProgressiveIdentity
import com.reianix.reianix_local.storage.VideoThumbnailExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.reianix.reianix_local.security.ReiAnixPrivateLog as Log
import com.reianix.reianix_local.bridge.NativeMailbox
import com.reianix.reianix_local.ReiAnixAppDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import com.reianix.reianix_local.data.sqlite.ReiAnixAnimeMetadataPatch
import com.reianix.reianix_local.data.sqlite.ReiAnixAnimeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixLibraryCatalogRow
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reianix.reianix_local.data.sqlite.updateAniListMetadataAndAssociation
import com.reianix.reianix_local.data.sqlite.recordAniListAutoMatchOutcome
import com.reianix.reianix_local.data.sqlite.findCachedPostersWithMetadataGaps
import com.reianix.reianix_local.data.sqlite.candidateMetadataRecoveryAttemptAt
import com.reianix.reianix_local.data.sqlite.markCandidateMetadataRecoveryAttempt
import com.reianix.reianix_local.data.sqlite.setMetadataStatusByLookupTitle
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository

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

/** Ready artwork rows use source=cache. Recover origin from the provider URL, not its cache label. */
internal fun isKitsuArtworkUrl(value: String?): Boolean {
    val host = runCatching { URI(value.orEmpty()).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
    return host == "kitsu.io" || host.endsWith(".kitsu.io") ||
        host == "kitsu.app" || host.endsWith(".kitsu.app") ||
        host == "kitsu-production-media.s3.amazonaws.com" ||
        (host.startsWith("kitsu-production-media.s3.") && host.endsWith(".amazonaws.com"))
}

/** Detect actual visible Kitsu artwork or Kitsu-sourced editorial metadata. */
internal fun isKitsuUpgradeTarget(row: ReiAnixLibraryCatalogRow): Boolean {
    val anime = row.anime
    if (anime.anilistMatchManual == true || anime.metadataSource.equals("manual", true) ||
        anime.metadataStatus.equals("manual", true) ||
        row.episodes.none { it.missing != true &&
            (it.availabilityState.isNullOrBlank() || it.availabilityState.equals("available", true)) }
    ) return false
    if (anime.metadataSource.equals("kitsu", true) || anime.metadataSource.equals("kitsu_candidate", true)) return true
    val ready = row.animeArtwork.filter { it.status.equals("ready", true) &&
        it.localPath?.let { path -> File(path).isFile && File(path).length() > 0L } == true }
    val posters = ready.filter { it.artworkType.equals("poster", true) || it.artworkType.equals("cover", true) }
    val poster = posters.firstOrNull { it.manual == true || it.source.equals("manual", true) }
        ?: posters.firstOrNull { it.localPath == anime.coverCache }
        ?: posters.firstOrNull()
    val backdrop = ready.firstOrNull { it.artworkType.equals("backdrop", true) || it.artworkType.equals("banner", true) }
    return listOfNotNull(poster, backdrop).any {
        it.manual != true && !it.source.equals("manual", true) && !it.source.equals("local", true) &&
            (it.source.equals("kitsu", true) || isKitsuArtworkUrl(it.externalUrl) || isKitsuArtworkUrl(it.sourceRef))
    } || (poster == null && isKitsuArtworkUrl(anime.coverUrl) &&
        anime.coverCache?.let { File(it).isFile && File(it).length() > 0L } == true)
}

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

/** Incomplete runs resume; completed runs start fresh when the user retries manually. */
internal fun shouldResumeTraceMoeSession(persistedCompleted: Boolean): Boolean =
    !persistedCompleted

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
    forceRetry: Boolean = false,
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
    if (!forceRetry && !manualMetadata && fetchedAt != null && fetchedAt.isFinite() &&
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
private const val LEGACY_KITSU_UPGRADE_ATTEMPT_PREFIX = "__reianix.internal.kitsu_anilist_upgrade_v1."
private const val KITSU_UPGRADE_ATTEMPT_PREFIX = "__reianix.internal.kitsu_anilist_upgrade_v2."
private const val KITSU_UPGRADE_INTERVAL_MS = 2_200L
private const val KITSU_UPGRADE_COOLDOWN_SECONDS = 24L * 60L * 60L

/** Read attempt timestamps from the existing schema-v29 preferences table. */
internal suspend fun readPersistedArtworkDiscoveryAttemptTimes(
    database: ReiAnixSqliteDatabase,
    keyPrefix: String = ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX,
): Map<Long, Double> = withContext(Dispatchers.IO) {
    database.read { connection ->
        connection.rawQuery(
            "SELECT key, value FROM preferences WHERE substr(key, 1, ?) = ?",
            arrayOf(
                keyPrefix.length.toString(),
                keyPrefix,
            ),
        ).use { cursor ->
            buildMap<Long, Double> {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0).orEmpty()
                    val animeId = key.removePrefix(keyPrefix)
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
    keyPrefix: String = ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX,
) = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Artwork discovery requires a positive local anime ID." }
    require(attemptAtSeconds.isFinite()) { "Artwork discovery attempt timestamp must be finite." }
    database.transaction { connection ->
        val values = ContentValues().apply {
            put("key", keyPrefix + animeId)
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
    forceRetry: Boolean = false,
): Boolean {
    if (forceRetry) return false
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
    private val candidateMetadataPublisher: ((Long, AniListAnime) -> Unit)? = null,
    private val explicitArtworkPublisher: ((Long) -> Unit)? = null,
    private val prepareRepairArtwork: (suspend (Long, AniListAnime) -> ReiAnixPreparedRepairArtwork?)? = null,
    private val traceMoeConsent: () -> Boolean = { false },
    private val automaticRepairClient: ReiAnixAniListClient = client,
) {
    private val effectiveGenreRegistry: ReiAnixGenreRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        genreRegistry ?: ReiAnixGenreRegistry(library.database)
    }
    private val autoMatchAttempts: ConcurrentHashMap<Long, Long> = autoMatchAttemptTimestamps
    private val traceMoeIdentificationInFlight = ConcurrentHashMap.newKeySet<Long>()
    private val explicitRepairInFlight = ConcurrentHashMap.newKeySet<Long>()
    private val kitsuUpgradeMutex = Mutex()
    private var kitsuUpgradeNotBeforeMs = 0L

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
        traceMoeConsent = { com.reianix.reianix_local.data.tracemoe.ReiAnixTraceMoeConsent.allowed(context) },
        descriptionLocalizer = ReiAnixAniListDescriptionLocalizer(
            File(context.applicationContext.filesDir, "data/covers/anilist_description_ptbr.json"),
        ),
        explicitArtworkPublisher = { animeId ->
            ReiAnixAppDependencies.existingOrNull(context.applicationContext)
                ?.artworkService?.requestAnimeArtwork(animeId, force = true)
            Unit
        },
        prepareRepairArtwork = { animeId, remote ->
            ReiAnixAppDependencies.existingOrNull(context.applicationContext)?.artworkService
                ?.prepareExplicitRepairArtwork(animeId, remote.id.toLong(), remote.coverCandidates.map { it.url }, remote.bannerUrl)
        },
        metadataCommitPublisher = { animeId, requestArtwork ->
            if (requestArtwork) {
                ReiAnixAppDependencies.existingOrNull(context.applicationContext)
                    ?.artworkService?.requestAnimeArtwork(animeId, force = false)
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
        candidateMetadataPublisher = { animeId, candidate ->
            ReiAnixAppDependencies.existingOrNull(context.applicationContext)
                ?.candidateMetadataService?.offerAniList(animeId, candidate)
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

    /**
     * Deliberate user action, separate from the automatic library discovery path.
     * Search the original local title and known aliases together, then commit only
     * a strongly supported non-MUSIC candidate. A failed search changes nothing.
     */
    suspend fun repairMetadataAndArtwork(animeId: Long): AniListResult<ReiAnixAnimeRecord> =
        repairMetadataAndArtworkInternal(animeId)

    /** Identical evidence, aliases, ranking and thresholds; only ownership and scheduling differ. */
    private suspend fun repairMetadataAndArtworkInternal(
        animeId: Long,
        automaticKitsuUpgrade: Boolean = false,
        mayUpgrade: suspend () -> Boolean = { true },
    ): AniListResult<ReiAnixAnimeRecord> {
        if (!isAniListEnabled()) return AniListResult.Failure(
            AniListError.InvalidRequest("A integração AniList está desativada nas configurações."),
        )
        if (!automaticKitsuUpgrade && !explicitRepairInFlight.add(animeId)) return AniListResult.Failure(
            AniListError.InvalidRequest("Este anime já está sendo verificado."),
        )
        try {
            val local = library.getAnime(animeId) ?: return AniListResult.Failure(
                AniListError.InvalidRequest("Anime não encontrado na biblioteca."),
            )
            if (automaticKitsuUpgrade && (!mayUpgrade() || local.anilistMatchManual == true ||
                    local.metadataSource.equals("manual", true) || local.metadataStatus.equals("manual", true))
            ) return AniListResult.Failure(AniListError.InvalidRequest("Automatic upgrade deferred."))
            val episodes = library.listEpisodes(animeId)
            val evidence = withContext(Dispatchers.Default) { explicitAniListLocalEvidence(episodes) }
            val titles = explicitAniListRepairTitles(local, evidence)
            // Give each distinct stored title a query slot before spending
            // remaining slots on cleaned variants of the same title.
            val expectedEdition = expectedRepairEdition(local, evidence)
            if (expectedEdition.conflict) return AniListResult.Failure(
                AniListError.InvalidRequest("Temporada ou parte local ambígua. Dados atuais preservados."),
            )
            val terms = editionAwareRepairSearchTerms(titles, local.lookupTitle, expectedEdition)
            val anchor = local.lookupTitle.takeIf(String::isNotBlank) ?: local.title
            if (terms.isEmpty() || anchor.isBlank()) return AniListResult.Failure(
                AniListError.InvalidRequest("O anime não tem título válido para identificação."),
            )
            // All returned candidates are evaluated against every stored title.
            // The original name is a preference, not a mandatory match.
            val repairClient = if (automaticKitsuUpgrade) automaticRepairClient else client
            val candidates = when (val result = repairClient.searchAnimeAliases(terms)) {
                is AniListResult.Success -> result.value
                is AniListResult.Failure -> return result
            }
            val ranked = withContext(Dispatchers.Default) {
                rankExplicitAniListRepair(local, anchor, candidates, evidence)
            }
            Log.d(TAG, "Explicit AniList repair animeId=$animeId queries=${terms.size} returned=${candidates.size} " +
                "music=${candidates.count { it.format.equals("MUSIC", true) }} ranked=${ranked.size} " +
                "top=${ranked.take(5).joinToString { "${it.anime.id}:${it.anime.format}:${it.score}" }}")
            val best = ranked.firstOrNull() ?: return AniListResult.Failure(
                AniListError.InvalidRequest("Nenhuma correspondência confiável no AniList. Dados atuais preservados."),
            )
            val margin = best.score - (ranked.getOrNull(1)?.score ?: 0.0)
            if (best.score < EXPLICIT_REPAIR_MIN_SCORE) return AniListResult.Failure(
                AniListError.InvalidRequest("Identificação inconclusiva. Capa e metadados atuais preservados."),
            )
            val correctManualMusic = !automaticKitsuUpgrade && local.anilistMatchManual == true &&
                local.anilistId != best.anime.id.toLong() && local.format.equals("MUSIC", true) &&
                (evidence.episodeCount >= 2 || (evidence.durationMinutes ?: 0.0) >= 10.0) &&
                best.score >= 0.86
            if (local.anilistMatchManual == true && local.anilistId != best.anime.id.toLong() && !correctManualMusic) {
                return AniListResult.Failure(
                    AniListError.InvalidRequest("A associação escolhida manualmente foi preservada."),
                )
            }
            val latest = library.getAnime(animeId) ?: return AniListResult.Failure(
                AniListError.InvalidRequest("Anime não encontrado na biblioteca."),
            )
            if (latest.anilistId != local.anilistId || latest.anilistMatchManual != local.anilistMatchManual ||
                latest.lookupTitle != local.lookupTitle) return AniListResult.Failure(
                AniListError.InvalidRequest("A identificação mudou durante a busca. Tente novamente."),
            )
            return applyRemote(
                latest, best.anime, manual = latest.anilistMatchManual == true && !correctManualMusic,
                score = best.score, margin = margin.coerceIn(0.0, 1.0), explicitRepair = true,
                correctManualMusic = correctManualMusic,
                automaticKitsuUpgrade = automaticKitsuUpgrade,
                mayUpgrade = mayUpgrade,
            )
        } finally {
            if (!automaticKitsuUpgrade) explicitRepairInFlight.remove(animeId)
        }
    }

    /**
     * Separate, low-priority pass. The existing initial pipeline is not paced,
     * blocked or modified. Persist attempts to avoid repeated unsuccessful lookups.
     */
    suspend fun upgradeKitsuLibraryToAniList(
        ordinaryWorkIdle: () -> Boolean,
    ): Int = kitsuUpgradeMutex.withLock {
        if (!isAniListEnabled() || !settingEnabled("metadata.auto_match", true) ||
            !settingEnabled("artwork.enabled", true)) return@withLock 0

        suspend fun awaitIdle() {
            while (true) {
                currentCoroutineContext().ensureActive()
                val pause = (kitsuUpgradeNotBeforeMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (pause > 0) {
                    delay(pause)
                    continue
                }
                if (ordinaryWorkIdle()) {
                    delay(750L)
                    if (ordinaryWorkIdle()) return
                } else delay(500L)
            }
        }

        awaitIdle()
        val attempts = readPersistedArtworkDiscoveryAttemptTimes(library.database, KITSU_UPGRADE_ATTEMPT_PREFIX)
        val previousUpgradeIds = readPersistedArtworkDiscoveryAttemptTimes(library.database, LEGACY_KITSU_UPGRADE_ATTEMPT_PREFIX).keys
        // Cheap provenance prefilter; materialize catalog/episode rows only for
        // plausible targets. The actual visible artwork is checked below.
        val targets = library.database.read { db ->
            db.rawQuery(
                """
                SELECT a.id FROM anime a
                WHERE COALESCE(a.anilist_match_manual,0)=0
                  AND LOWER(COALESCE(a.metadata_source,''))!='manual'
                  AND LOWER(COALESCE(a.metadata_status,''))!='manual'
                  AND (LOWER(COALESCE(a.metadata_source,'')) IN ('kitsu','kitsu_candidate')
                    OR INSTR(LOWER(COALESCE(a.cover_url,'')),'kitsu')>0
                    OR EXISTS (SELECT 1 FROM artwork art
                      WHERE art.entity_id=CAST(a.id AS TEXT)
                        AND art.entity_type IN ('anime','movie')
                        AND art.artwork_type IN ('poster','cover','backdrop','banner')
                        AND art.status='ready' AND COALESCE(art.manual,0)=0
                        AND (LOWER(COALESCE(art.source,''))='kitsu'
                          OR INSTR(LOWER(COALESCE(art.external_url,'')),'kitsu')>0
                          OR INSTR(LOWER(COALESCE(art.source_ref,'')),'kitsu')>0)))
                ORDER BY a.id
                """.trimIndent(), null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        }
        var upgraded = 0
        for (animeId in (targets + previousUpgradeIds).distinct()) {
            currentCoroutineContext().ensureActive()
            val previous = attempts[animeId]
            if (previous != null && System.currentTimeMillis() / 1_000.0 - previous < KITSU_UPGRADE_COOLDOWN_SECONDS) continue
            awaitIdle()
            if (!isAniListEnabled() || !settingEnabled("metadata.auto_match", true) ||
                !settingEnabled("artwork.enabled", true)) break
            if (library.loadLibraryCatalogRows(setOf(animeId)).none { isKitsuUpgradeTarget(it) ||
                    (animeId in previousUpgradeIds && needsLegacySeasonRepair(it)) }) continue
            for (retry in 0..1) {
                awaitIdle()
                if (animeId in explicitRepairInFlight) break
                var deferred = false
                val result = repairMetadataAndArtworkInternal(animeId, automaticKitsuUpgrade = true) {
                    val ready = animeId !in explicitRepairInFlight && ordinaryWorkIdle() && isAniListEnabled() &&
                        settingEnabled("metadata.auto_match", true) && settingEnabled("artwork.enabled", true) &&
                        library.loadLibraryCatalogRows(setOf(animeId)).any { isKitsuUpgradeTarget(it) ||
                            (animeId in previousUpgradeIds && needsLegacySeasonRepair(it)) }
                    val stillIdle = ready && ordinaryWorkIdle() && animeId !in explicitRepairInFlight
                    if (!stillIdle) deferred = true
                    stillIdle
                }
                val now = android.os.SystemClock.elapsedRealtime()
                kitsuUpgradeNotBeforeMs = now + KITSU_UPGRADE_INTERVAL_MS
                if (result is AniListResult.Failure && result.error is AniListError.RateLimited) {
                    val seconds = (result.error as AniListError.RateLimited).retryAfterSeconds
                        ?.coerceIn(1L, 21_600L) ?: 60L
                    kitsuUpgradeNotBeforeMs = now + maxOf(KITSU_UPGRADE_INTERVAL_MS, seconds * 1_000L)
                    if (retry == 0) continue
                    return@withLock upgraded
                }
                if (deferred) {
                    if (retry == 0) continue
                    break
                }
                if (result is AniListResult.Success) upgraded++
                if (result is AniListResult.Failure &&
                    (result.error is AniListError.Network || result.error is AniListError.Http)) break
                writePersistedArtworkDiscoveryAttemptAt(
                    library.database, animeId, System.currentTimeMillis() / 1_000.0, KITSU_UPGRADE_ATTEMPT_PREFIX,
                )
                break
            }
        }
        upgraded
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
        val patched = when (val result = applyRemote(local, remote, manual = true, score = 1.0, margin = 1.0, manualSelection = true)) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> return result
        }
        return AniListResult.Success(patched)
    }

    /**
     * Uses local episode thumbnails to verify or correct an automatic AniList association.
     * Each manual session consumes no more than three valid Trace.moe responses. A strong
     * result is applied immediately; inconclusive valid responses advance only until the
     * third response. A failed image request does not consume a slot and may be replaced
     * by a later episode. Manual metadata is protected.
     */
    suspend fun identifyFromEpisodeThumbnails(
        animeId: Long,
        samples: List<TraceMoeEpisodeSample>,
        userInitiated: Boolean = false,
        onProgress: (TraceMoeIdentificationProgress) -> Unit = {},
    ): Boolean = identifyFromEpisodeThumbnails(animeId, samples.asFlow(), userInitiated, onProgress)

    /**
     * Consume thumbnails in episode order, stopping on a reliable identity or after
     * three successful API responses. Failed requests are skipped and replaced by a
     * later thumbnail without consuming a valid-response slot.
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
        if (animeId <= 0L || !isAniListEnabled() || !traceMoeConsent() ||
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

        // Reuse persisted successful responses on manual retries as well as automatic
        // recovery. This survives Android process restarts (unlike the in-memory SHA
        // cache), prevents uploading the same episode thumbnail repeatedly, and lets an
        // interrupted session continue with the next unconsumed thumbnail.
        // A completed session is final for that run, but a new manual attempt must
        // be allowed to re-query with fresh API data. Incomplete sessions (for example,
        // cancellation or a recoverable failure before three valid responses) resume
        // from their persisted evidence to avoid duplicate uploads after process death.
        var matchesByEpisode = if (!shouldResumeTraceMoeSession(persisted.completed)) {
            linkedMapOf()
        } else {
            latestTraceMoeEpisodeMatches(persisted.matchesByEpisode)
        }
        val successfulApiEpisodeIds = linkedSetOf<Long>()
        if (shouldResumeTraceMoeSession(persisted.completed)) {
            if (persisted.hasSuccessfulApiEpisodeMetadata) {
                successfulApiEpisodeIds.addAll(persisted.successfulApiEpisodeIds)
            } else {
                // Legacy non-empty result arrays prove a successful API response;
                // legacy empty arrays are ambiguous and must be queried again.
                val provenSuccessfulIds = persisted.matchesByEpisode
                    .filterValues { it.isNotEmpty() }
                    .keys
                successfulApiEpisodeIds.addAll(provenSuccessfulIds)
                matchesByEpisode = LinkedHashMap(matchesByEpisode.filterKeys { it in provenSuccessfulIds })
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

        if (successfulResponses > 0) {
            report(
                TraceMoeIdentificationProgressPhase.VERIFYING_IDENTITY,
                message = "Reutilizando $successfulResponses respostas válidas já salvas; nenhuma thumbnail já consultada será reenviada.",
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

            val matches = try {
                traceMoeClient.search(imageFile = sample.imageFile, reserveRequest = { traceMoeConsent() }) { requestStage ->
                    when (requestStage) {
                        TraceMoeRequestProgressStage.CHECKING_QUOTA -> report(
                            TraceMoeIdentificationProgressPhase.CHECKING_QUOTA,
                            sample,
                            "Consultando quota e limite de concorrência do Trace.moe antes de enviar a thumbnail...",
                        )
                        TraceMoeRequestProgressStage.UPLOADING_IMAGE -> report(
                            TraceMoeIdentificationProgressPhase.SENDING_TO_API,
                            sample,
                            "Enviando thumbnail ao Trace.moe (${successfulResponses.coerceAtMost(MAX_TRACE_MOE_EPISODE_SAMPLES)}/$MAX_TRACE_MOE_EPISODE_SAMPLES respostas válidas)...",
                        )
                        TraceMoeRequestProgressStage.WAITING_FOR_RESPONSE -> report(
                            TraceMoeIdentificationProgressPhase.WAITING_FOR_API,
                            sample,
                            "Thumbnail enviada. Aguardando a resposta do Trace.moe...",
                        )
                        TraceMoeRequestProgressStage.RETRYING_REQUEST -> report(
                            TraceMoeIdentificationProgressPhase.RETRYING_REQUEST,
                            sample,
                            "A API recusou ou não concluiu esta chamada; fazendo a retentativa limitada...",
                        )
                    }
                }
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
            // A later valid response replaced any earlier transient thumbnail failure.
            lastEpisodeFailure = null
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
                "Resposta recebida (${successfulResponses}/$MAX_TRACE_MOE_EPISODE_SAMPLES respostas válidas).",
            )
            report(
                TraceMoeIdentificationProgressPhase.VERIFYING_IDENTITY,
                sample,
                "Conferindo se o resultado corresponde ao anime...",
            )
            confirmedIdentity = evaluateTraceMoeProgressiveIdentity(matchesByEpisode)
        }

        val consensus = confirmedIdentity
        if (consensus == null) {
            // A third valid response ends the session. If recoverable failures remain
            // unreplaced and fewer than three valid responses were obtained, preserve
            // the progress as incomplete and do not activate the 24-hour cooldown.
            val canMarkCompleted = shouldCompleteTraceMoeIdentification(
                successfulResponses = successfulResponses,
                hadUnreplacedFailure = lastEpisodeFailure != null,
            )
            writeTraceMoeProgress(
                animeId,
                matchesByEpisode,
                completed = canMarkCompleted,
                correction = correction,
                successfulApiEpisodeIds = successfulApiEpisodeIds,
            )
            if (!canMarkCompleted) {
                lastEpisodeFailure?.let { throw it }
                return false
            }
            writeTraceMoeAttemptAt(animeId, System.currentTimeMillis() / 1000.0, correction)
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
        // A provisional year borrowed from an artwork candidate must not penalize
        // the true anime in later AniList identification.
        val candidateOnly = local.metadataSource?.endsWith("_candidate", ignoreCase = true) == true
        val searchContext = ReiAnixAniListMatcher.Context(
            year = local.year.takeUnless { candidateOnly },
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
            // The same fetched candidate can still fill empty description/details.
            // This does not assign an AniList ID or affect the existing poster work.
            candidateMetadataPublisher?.invoke(animeId, best.anime)
            recordAutoMatchOutcome(animeId, "not_found")
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }
        val second = ranked.getOrNull(1)?.score ?: 0.0
        val margin = best.score - second
        if (best.score < AUTO_MATCH_MIN_SCORE || margin < AUTO_MATCH_MIN_MARGIN) {
            candidateMetadataPublisher?.invoke(animeId, best.anime)
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
        val artworkCandidates = usableAniListArtworkCandidates(candidatesById.values)
        // No extra API call: candidate metadata arrives in the same AniList response.
        artworkCandidates.firstOrNull()?.let { candidateMetadataPublisher?.invoke(local.id, it) }
        return artworkCandidates
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
    suspend fun recoverMissingArtworkMetadata(
        maxRecords: Int = 12,
        forceRetry: Boolean = false,
        targetAnimeIds: List<Long>? = null,
    ): Int {
        if (!missingArtworkRecoveryRunning.compareAndSet(false, true)) return 0
        try {
            require(maxRecords in 1..24) { "Missing-artwork lookup budget must be between 1 and 24." }
            if (!isAniListEnabled() || !settingEnabled("artwork.enabled", defaultValue = true)) return 0
            val discoveryCursor = readArtworkDiscoveryCursor()
            val persistedAttemptTimes = readArtworkDiscoveryAttemptTimes().toMutableMap()
            // Manual recovery already knows exactly which IDs are missing posters. Fetch only
            // a small bounded set of anime records; never materialize the entire episode,
            // thumbnail and genre catalog snapshot for a button press.
            val orderedAnimes = if (targetAnimeIds != null) {
                rotateArtworkDiscoveryIds(targetAnimeIds, discoveryCursor)
                    .take(maxRecords)
                    .mapNotNull { animeId -> library.getAnime(animeId) }
            } else {
                val rows = library.loadLibraryCatalogRows()
                if (rows.isEmpty()) return 0
                val rowsById = rows.associateBy { it.anime.id }
                rotateArtworkDiscoveryIds(rowsById.keys.toList(), discoveryCursor)
                    .mapNotNull(rowsById::get)
                    .map { it.anime }
            }
            if (orderedAnimes.isEmpty()) return 0
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
            for (local in orderedAnimes) {
                if (examined >= maxRecords) break
                currentCoroutineContext().ensureActive()
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
                        forceRetry = forceRetry,
                    )
                ) continue
                if (shouldDeferArtworkDiscoveryAttempt(
                        persistedAttemptTimes[local.id], nowSeconds, forceRetry = forceRetry,
                    )
                ) {
                    continue
                }
                if (examined >= maxRecords) break
                val attemptAt = nowSeconds.toLong()
                val previousAttempt = missingArtworkLookupAttempts.putIfAbsent(local.id, attemptAt)
                if (previousAttempt != null) {
                    if ((!forceRetry && nowSeconds - previousAttempt < MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS) ||
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
            if (targetAnimeIds == null && lastVisitedCursor > 0L && lastVisitedCursor != discoveryCursor) {
                writeArtworkDiscoveryCursor(lastVisitedCursor)
            }
            return recovered
        } finally {
            missingArtworkRecoveryRunning.set(false)
        }
    }

    /**
     * Independently restore missing details for cached posters that originated at
     * AniList, including posters shown before a confident catalog association.
     * No artwork request, candidate list or image refresh path is changed.
     */
    suspend fun recoverMetadataForReadyAniListPosters(maxRecords: Int = 100): Int {
        require(maxRecords in 1..200)
        val publisher = candidateMetadataPublisher ?: return 0
        if (!isAniListEnabled()) return 0
        val artworkDownloading = library.database.read { db ->
            db.rawQuery(
                "SELECT 1 FROM artwork WHERE artwork_type IN ('poster','backdrop') " +
                    "AND status='downloading' AND last_attempt_at>=? LIMIT 1",
                arrayOf((System.currentTimeMillis() / 1_000.0 - 120.0).toString()),
            ).use { it.moveToFirst() }
        }
        if (artworkDownloading) return 0

        var offered = 0
        val targets = library.findCachedPostersWithMetadataGaps(maxRecords)
        for (target in targets) {
            if (!target.posterSource.equals("anilist", ignoreCase = true)) continue
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis() / 1_000.0
            val attemptedAt = library.candidateMetadataRecoveryAttemptAt(target.animeId, "anilist")
            if (attemptedAt != null && now - attemptedAt < AUTO_MATCH_COOLDOWN_MS / 1_000.0) continue

            val local = library.getAnime(target.animeId) ?: continue
            val knownId = local.anilistId?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
            val candidate = if (knownId != null) {
                when (val result = client.getAnime(knownId.toInt())) {
                    is AniListResult.Success -> result.value
                    is AniListResult.Failure -> null
                }
            } else {
                when (val result = client.searchAnime(target.title, 10)) {
                    is AniListResult.Success -> {
                        val options = result.value
                        options.firstOrNull {
                            target.posterUrl != null &&
                                (it.coverExtraLargeUrl == target.posterUrl ||
                                    it.coverLargeUrl == target.posterUrl)
                        } ?: options.firstOrNull { !it.description.isNullOrBlank() }
                            ?: options.firstOrNull()
                    }
                    is AniListResult.Failure -> null
                }
            }
            library.markCandidateMetadataRecoveryAttempt(target.animeId, now, "anilist")
            if (candidate != null) {
                publisher(target.animeId, candidate)
                offered++
            }
        }
        return offered
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
        explicitRepair: Boolean = false,
        correctManualMusic: Boolean = false,
        manualSelection: Boolean = false,
        automaticKitsuUpgrade: Boolean = false,
        mayUpgrade: suspend () -> Boolean = { true },
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
                            cached ?: if (explicitRepair || manualSelection) remoteOriginal else localizer.localizeToPtBr(remoteOriginal) ?: remoteOriginal
                        }
                    }
                }
                remoteOriginal to displayDescription
            }
            if (automaticKitsuUpgrade && (!artworkEnabled || !mayUpgrade())) return AniListResult.Failure(
                AniListError.InvalidRequest("Automatic upgrade deferred."),
            )
            val prepared = if ((explicitRepair || manualSelection) && artworkEnabled && (explicitRepair || prepareRepairArtwork != null)) {
                prepareRepairArtwork?.invoke(local.id, remote) ?: return AniListResult.Failure(
                    AniListError.InvalidRequest("Não foi possível preparar a capa e o banner. Os dados anteriores foram mantidos."),
                )
            } else null
            if (automaticKitsuUpgrade && !mayUpgrade()) return AniListResult.Failure(
                AniListError.InvalidRequest("Automatic upgrade deferred."),
            )
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
                    coverUrl = if (artworkEnabled) prepared?.poster?.url ?: remote.coverExtraLargeUrl ?: remote.coverLargeUrl else null,
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
                explicitRepair = explicitRepair,
                expectedAssociationId = local.anilistId,
                correctManualMusic = correctManualMusic,
                preparedArtwork = prepared,
                manualSelection = manualSelection,
                protectManualRefresh = manual && !manualSelection,
                automaticKitsuUpgrade = automaticKitsuUpgrade,
            )
            if (updated.anilistId != remote.id.toLong() || (automaticKitsuUpgrade &&
                    (updated.anilistMatchManual == true || updated.metadataSource.equals("manual", true) ||
                        updated.metadataStatus.equals("manual", true)))) {
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
            if (artworkEnabled && prepared == null && (manual || traceMoeConfirmed || explicitRepair)) {
                explicitArtworkPublisher?.invoke(updated.id)
            }
            enqueueMetadataCommit(updated.id, requestArtwork = artworkEnabled && prepared == null)
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

// Only the user-initiated repair uses these more permissive thresholds.
internal const val EXPLICIT_REPAIR_MIN_SCORE = 0.60
internal const val EXPLICIT_REPAIR_MIN_MARGIN = 0.0

internal data class ExplicitAniListLocalEvidence(
    val titles: List<String> = emptyList(),
    val episodeCount: Int = 0,
    val durationMinutes: Double? = null,
    val fileTitles: List<String> = titles,
    val editionTitles: List<String> = emptyList(),
)

private val repairMarks = Regex("\\p{M}+")
private val repairInvisible = Regex("[\\p{Cf}]")
private val repairSeparators = Regex("[^\\p{L}\\p{N}]+")
private val repairWhitespace = Regex("\\s+")
private val repairNumberMarker = Regex("(?:s|e|ep)\\d+")
private val repairGenericWords = setOf("anime", "animes", "home", "adm", "download", "downloads",
    "video", "videos", "media", "storage", "emulated", "internal", "reianix", "episode", "episodes",
    "episodio", "episodios", "ep", "e", "s", "season", "temporada", "temp", "movie", "filme",
    "show", "series", "pasta", "folder", "the", "of", "a", "an", "no", "ni", "na", "de", "da", "do",
    "to", "and", "in", "wa", "ga", "wo", "pack", "batch", "complete", "dublado", "legendado", "mp4", "mkv",
    "arquivo", "identificado", "identificada", "unknown", "catalog", "key", "online", "primary", "document",
    "sdcard", "android", "movies", "files", "downloaded", "episode", "trailer", "sample", "clip")
private val repairGenericTitle = Regex("(?:minha )?(?:pasta|folder|catalog key|unknown|sem titulo|arquivo nao identificado)(?: \\d+)?")

private fun repairPlain(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
    .replace(repairMarks, "").replace(repairInvisible, "").lowercase(Locale.ROOT)
    .replace("ß", "ss").replace("ς", "σ").replace(repairSeparators, " ").trim()

// The uninterrupted spelling is independent of romanization word boundaries:
// shitemo == shite mo, dewa == de wa, full-width/accents/punctuation likewise.
// Preserve all numbers so sequels with different explicit numbers do not become equal.
internal fun explicitAniListCompactTitle(value: String): String = repairPlain(value).replace(" ", "")

private fun repairWords(value: String): Set<String> = repairPlain(value).split(" ")
    .filter { it.isNotBlank() && it !in repairGenericWords && it.any(Char::isLetter) &&
        !repairNumberMarker.matches(it) }.toSet()

private fun usefulRepairTitle(value: String): Boolean =
    value.length in 2..120 && repairWords(value).isNotEmpty() && !repairGenericTitle.matches(repairPlain(value))

private fun decodedRepairPath(value: String): String =
    // SAF document IDs and relative paths often encode the real folder as %2F/%20.
    // Uri.decode preserves literal '+' in titles (unlike form URL decoding).
    android.net.Uri.decode(value).replace('\\', '/')

internal fun explicitAniListLocalEvidence(episodes: List<ReiAnixEpisodeRecord>): ExplicitAniListLocalEvidence {
    val available = episodes.filter { it.missing != true }
    val durations = available.mapNotNull { it.duration?.takeIf { seconds -> seconds.isFinite() && seconds > 0 } }.sorted()
    val files = linkedSetOf<String>()
    val folders = linkedSetOf<String>()
    val editionTitles = linkedSetOf<String>()
    fun addTitle(raw: String, target: MutableSet<String>) {
        val title = raw.trim()
        if (usefulRepairTitle(title)) target.add(title)
    }
    fun addFile(raw: String) {
        val name = decodedRepairPath(raw).substringAfterLast('/')
            .replace(Regex("(?i)^(?:primary|home|[0-9a-f]{4}-[0-9a-f]{4}):"), "")
        if (name.isBlank() || !MediaFilenameParser.supportsExtension("." + name.substringAfterLast('.', "").lowercase(Locale.ROOT))) return
        val rawTitle = name.substringBeforeLast('.')
        if (repairEdition(rawTitle, allowBare = false).hasMarker) editionTitles.add(rawTitle)
        val parsed = MediaFilenameParser.parseVideoPath(name, allowStandaloneMovie = true)
        addTitle(parsed.animeTitle, files)
    }
    fun addFolders(raw: String?, containsFile: Boolean) {
        if (raw.isNullOrBlank()) return
        val path = decodedRepairPath(raw).trimEnd('/')
        // Never use provider/authority, URI document IDs, or arbitrary numeric IDs.
        val normalized = path.substringAfterLast("/document/", path)
            .replace(Regex("(?i)^(?:primary|home|[0-9a-f]{4}-[0-9a-f]{4}):"), "")
        val segments = normalized.split('/').filter(String::isNotBlank)
        val parents = if (containsFile) segments.dropLast(1) else segments
        parents.asReversed().take(3).forEach { folder ->
            if (!folder.contains('%') && !folder.startsWith("com.") &&
                !folder.startsWith("content:", true) && !folder.startsWith("file:", true) &&
                !folder.matches(Regex("(?i)(?:primary|home|[0-9a-f]{4}-[0-9a-f]{4}):.*"))) {
                // Keep explicit season/part markers which the filename normalizer may remove.
                if (repairEdition(folder).hasMarker && folder !in editionTitles) editionTitles.add(folder)
                addTitle(MediaTitleNormalizer.cleanMediaTitle(folder), folders)
            }
        }
    }
    available.take(8).forEach { ep ->
        addFile(ep.fileName)
        addFile(ep.path)
        addFolders(ep.path, containsFile = true)
        // Relative path is normally the directory, but also tolerate a leaf filename.
        val relative = ep.relativePath
        if (!relative.isNullOrBlank()) {
            val leaf = decodedRepairPath(relative).trimEnd('/').substringAfterLast('/')
            val hasFile = MediaFilenameParser.supportsExtension("." + leaf.substringAfterLast('.', "").lowercase(Locale.ROOT))
            if (hasFile) addFile(relative)
            addFolders(relative, hasFile)
        }
        addFolders(ep.sourceFolder, containsFile = false)
    }
    val fileTitles = files.distinctBy(::repairPlain).take(4)
    val titles = (fileTitles + folders).distinctBy(::repairPlain).take(8)
    return ExplicitAniListLocalEvidence(titles, available.size, durations.getOrNull(durations.size / 2)?.div(60.0), fileTitles, editionTitles.toList())
}

private fun independentRepairTitles(local: ReiAnixAnimeRecord, evidence: ExplicitAniListLocalEvidence): List<String> {
    val localTitles = evidence.titles.filter(::usefulRepairTitle)
    val lookup = local.lookupTitle.trim()
    // A catalog name can already have been corrupted by a prior association.
    // When real file evidence exists prefer it over such a copied lookup title.
    val copiedLookup = local.anilistId != null && local.anilistMatchManual != true &&
        explicitAniListCompactTitle(lookup) == explicitAniListCompactTitle(local.title) &&
        localTitles.isNotEmpty() && localTitles.none { explicitAniListCompactTitle(it) == explicitAniListCompactTitle(lookup) }
    return (localTitles + listOf(lookup).filter { !copiedLookup }).filter(::usefulRepairTitle)
        .distinctBy(::repairPlain)
}

internal fun explicitAniListRepairTitles(local: ReiAnixAnimeRecord, evidence: ExplicitAniListLocalEvidence = ExplicitAniListLocalEvidence()): List<String> = buildList {
    addAll(independentRepairTitles(local, evidence))
    // Remote aliases of a previous automatic match cannot prove that match.
    if ((local.anilistId == null && !local.format.equals("MUSIC", true)) || local.anilistMatchManual == true) {
        add(local.title); add(local.romaji.orEmpty()); add(local.english.orEmpty()); add(local.nativeTitle.orEmpty())
        local.aliasesJson?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { aliases ->
                for (i in 0 until aliases.length()) add(aliases.optString(i))
            }
        }
    }
}.map(String::trim).filter(::usefulRepairTitle).distinctBy(::repairPlain).take(16)

private fun repairQuerySpelling(title: String): String {
    // Query variants only; the original name is never rewritten on disk.
    return repairPlain(title)
        .replace(Regex("\\bdewa\\b"), "de wa")
        .replace(Regex("\\bshitemo\\b"), "shite mo")
        .replace(Regex("\\bshitewa\\b"), "shite wa")
        .replace(Regex("\\bniwa\\b"), "ni wa")
        .replace(Regex("\\btoha\\b"), "to ha")
        .replace(Regex("\\bnanode\\b"), "na node")
}

private fun distinctiveRepairFragments(title: String): List<String> {
    val words = repairPlain(title).split(" ").filter(String::isNotBlank)
    if (words.size < 4) return emptyList()
    val windows = words.windowed(minOf(4, words.size - 1), 1)
        .filter { part -> part.any { it.length >= 4 && it !in repairGenericWords } &&
            part.count { it !in repairGenericWords && it.any(Char::isLetter) } >= 2 }
        .sortedByDescending { part -> part.filter { it !in repairGenericWords }.sumOf { it.length.coerceAtMost(12) } }
    return (listOf(words.take(4).joinToString(" ")) + windows.take(2).map { it.joinToString(" ") })
        .filter(::usefulRepairTitle).distinct()
}

internal fun explicitAniListRepairSearchTerms(titles: List<String>): List<String> {
    val variants = titles.filter(::usefulRepairTitle).map(MediaTitleNormalizer::searchTitleVariants)
    val preferred = variants.mapNotNull { it.firstOrNull() }
    val primary = preferred.firstOrNull() ?: return emptyList()
    val spelling = repairQuerySpelling(primary)
    val cleaned = MediaTitleNormalizer.searchTitleVariants(primary).drop(1).take(1)
    val fragments = distinctiveRepairFragments(spelling)
    // Reserve slots for split romanization and fragments before extra aliases
    // can exhaust the request budget. All eight searches share one HTTP request.
    return (listOf(primary, spelling) + cleaned + preferred.drop(1).take(3) +
        fragments.take(2) + preferred + variants.flatten()).filter(::usefulRepairTitle)
        .distinctBy(::repairPlain).take(8)
}


/**
 * Edition is an identity constraint, not a similarity bonus. Do not infer a
 * season from the default local episode group (often always 1), release year,
 * or a title previously copied from a remote association.
 */
internal data class RepairEdition(
    val season: Int? = null,
    val part: Int? = null,
    val format: String? = null,
    val conflict: Boolean = false,
) {
    val hasMarker: Boolean get() = season != null || part != null || format != null
}

private val repairRomanNumbers = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x",
    "xi", "xii", "xiii", "xiv", "xv", "xvi", "xvii", "xviii", "xix", "xx")
private const val REPAIR_EDITION_NUMBER = "(\\d{1,2}|xviii|xvii|xvi|xv|xiv|xiii|xii|xi|xx|x|viii|vii|vi|iv|iii|ii|ix|v|i)"
private val repairSeasonPrefix = Regex("\\b(?:season|temporada|temp|s)\\s*" + REPAIR_EDITION_NUMBER + "(?:st|nd|rd|th|a|o)?(?=\\b|e\\d)")
private val repairSeasonSuffix = Regex("\\b" + REPAIR_EDITION_NUMBER + "(?:st|nd|rd|th|a|o)?\\s*(?:season|temporada|temp|ki)\\b")
private val repairJapaneseSeason = Regex("第\\s*(\\d{1,2})\\s*期")
private val repairPartPrefix = Regex("\\b(?:part|parte|cour|c)\\s*" + REPAIR_EDITION_NUMBER + "(?:st|nd|rd|th)?\\b")
private val repairPartSuffix = Regex("\\b" + REPAIR_EDITION_NUMBER + "(?:st|nd|rd|th)?\\s*(?:part|parte|cour)\\b")
private val repairBareEdition = Regex("\\s+(\\d{1,2}|ii|iii|iv|v|vi|vii|viii|ix|x|xi|xii|xiii|xiv|xv|xvi|xvii|xviii|xix|xx)$")
private val repairYearSuffix = Regex("\\s+(?:19\\d{2}|20\\d{2})$")
private val repairMovieMarker = Regex("\\b(?:the movie|movie|filme|gekijouban|gekijoban)\\b")
private val repairSpecialMarker = Regex("\\b(?:special|especia[li]|ova|oad)\\b")
private val repairSeriesFormats = setOf("TV", "TV_SHORT", "ONA")

private fun repairEditionNumber(raw: String): Int? =
    (raw.toIntOrNull() ?: (repairRomanNumbers.indexOf(raw) + 1)).takeIf { it in 1..30 }

private fun repairEditionFormat(value: String): String? = when {
    repairMovieMarker.containsMatchIn(value) -> "MOVIE"
    repairSpecialMarker.containsMatchIn(value) -> if (Regex("\\b(?:ova|oad)\\b").containsMatchIn(value)) "OVA" else "SPECIAL"
    else -> null
}

/** Bare suffixes are accepted on titles/folders, never raw episode filenames. */
internal fun repairEdition(value: String, allowBare: Boolean = true): RepairEdition {
    val plain = repairPlain(value)
    val seasons = listOf(repairSeasonPrefix, repairSeasonSuffix, repairJapaneseSeason)
        .flatMap { regex -> regex.findAll(plain).mapNotNull { repairEditionNumber(it.groupValues[1]) }.toList() }.toSet()
    val parts = listOf(repairPartPrefix, repairPartSuffix)
        .flatMap { regex -> regex.findAll(plain).mapNotNull { repairEditionNumber(it.groupValues[1]) }.toList() }.toSet()
    val withoutYear = plain.replace(repairYearSuffix, "")
    val suffix = if (allowBare && seasons.isEmpty() && parts.isEmpty()) repairBareEdition.find(withoutYear) else null
    val stem = suffix?.let { withoutYear.substring(0, it.range.first).trim() }.orEmpty()
    // Numbers in literal names such as "Kaiju No. 8" are not season markers.
    val bare = suffix?.takeIf { stem.any(Char::isLetter) &&
        stem.substringAfterLast(' ') !in setOf("no", "number", "episode", "ep", "capitulo", "episodio", "volume", "vol") }
        ?.let { repairEditionNumber(it.groupValues[1]) }
    return RepairEdition(seasons.singleOrNull() ?: bare, parts.singleOrNull(),
        repairEditionFormat(plain), seasons.size > 1 || parts.size > 1)
}

private fun combineRepairEditions(values: List<RepairEdition>): RepairEdition {
    val seasons = values.mapNotNull { it.season }.toSet()
    val parts = values.mapNotNull { it.part }.toSet()
    val formats = values.mapNotNull { it.format }.toSet()
    return RepairEdition(seasons.singleOrNull(), parts.singleOrNull(), formats.singleOrNull(),
        values.any { it.conflict } || seasons.size > 1 || parts.size > 1 || formats.size > 1)
}

internal fun expectedRepairEdition(local: ReiAnixAnimeRecord, evidence: ExplicitAniListLocalEvidence): RepairEdition {
    val lookup = repairEdition(local.lookupTitle)
    val explicit = evidence.editionTitles.map { repairEdition(it, allowBare = !it.contains(Regex("(?i)\\bs\\d+e\\d+\\b"))) }
    val independent = combineRepairEditions(listOf(lookup) + explicit)
    if (independent.hasMarker || independent.conflict) return independent
    // The parsed filenames/folders are secondary evidence; do not trust an
    // automatically associated remote title or its potentially wrong aliases.
    val parsed = combineRepairEditions(evidence.titles.map { repairEdition(it) })
    if (parsed.hasMarker || parsed.conflict) return parsed
    if (local.anilistId == null && local.metadataSource?.startsWith("kitsu", true) == true) {
        return repairEdition(local.title)
    }
    return independent
}

private fun remoteRepairEdition(candidate: AniListAnime): RepairEdition {
    val primary = combineRepairEditions(listOfNotNull(candidate.titleRomaji, candidate.titleEnglish, candidate.titleNative)
        .map { repairEdition(it) })
    val aliases = combineRepairEditions(candidate.synonyms.map { repairEdition(it) })
    // Canonical numbering outranks synonyms; an ambiguous alias cannot prove a season.
    return RepairEdition(primary.season ?: aliases.season.takeUnless { aliases.conflict },
        primary.part ?: aliases.part.takeUnless { aliases.conflict }, primary.format,
        primary.conflict)
}

/**
 * PREQUEL chains provide secondary evidence for sequels with a subtitle
 * rather than a number. Only an unambiguous TV/ONA chain counts. A split
 * cour is not an additional season, and movies/specials never count.
 * Missing relation data fails closed rather than guessing a sequel's number.
 */
private fun inferredRepairSeason(
    candidate: AniListAnime,
    byId: Map<Int, AniListAnime>,
    seen: Set<Int> = emptySet(),
): Int? {
    if (candidate.id in seen || seen.size >= 10 || candidate.format !in repairSeriesFormats) return null
    val edition = remoteRepairEdition(candidate)
    if (edition.conflict) return null
    edition.season?.let { return it }
    val relations = candidate.repairRelations ?: return null
    val predecessors = relations.filter { it.kind == "PREQUEL" && it.format in repairSeriesFormats }.distinctBy { it.id }
    if (predecessors.isEmpty()) {
        // A node with only a movie/OVA prequel does not establish TV season 1.
        return if (relations.any { it.kind == "PREQUEL" } || (edition.part ?: 1) > 1) null else 1
    }
    val previous = predecessors.singleOrNull() ?: return null
    val previousEdition = combineRepairEditions(previous.titles.map { repairEdition(it) })
    if (previousEdition.conflict) return null
    val previousSeason = previousEdition.season ?: byId[previous.id]?.let {
        inferredRepairSeason(it, byId, seen + candidate.id)
    } ?: return null
    return if ((edition.part ?: 1) > 1) previousSeason else (previousSeason + 1).takeIf { it <= 30 }
}

internal fun repairEditionCompatible(expected: RepairEdition, candidate: AniListAnime, candidates: List<AniListAnime>): Boolean {
    if (expected.conflict) return false
    val remote = remoteRepairEdition(candidate)
    if (remote.conflict) return false
    if (expected.format != null && candidate.format != expected.format) return false
    if (expected.season != null && expected.format == null && candidate.format !in repairSeriesFormats) return false
    if (expected.part != null && remote.part != expected.part) return false
    if (expected.part == null && expected.season != null && (remote.part ?: 1) > 1) return false
    if (expected.season != null) {
        val season = remote.season ?: inferredRepairSeason(candidate, candidates.associateBy { it.id })
        if (season != expected.season) return false
    }
    return true
}

internal fun editionAwareRepairSearchTerms(titles: List<String>, lookupTitle: String, edition: RepairEdition): List<String> {
    val existing = explicitAniListRepairSearchTerms(titles)
    if (edition.season == null && edition.part == null) return existing
    val original = lookupTitle.trim().takeIf(::usefulRepairTitle) ?: titles.firstOrNull() ?: return existing
    var base = repairPlain(original).replace(repairYearSuffix, "")
    listOf(repairSeasonPrefix, repairSeasonSuffix, repairJapaneseSeason, repairPartPrefix, repairPartSuffix).forEach { regex ->
        base = base.replace(regex, " ")
    }
    if (repairEdition(original).season != null) base = base.replace(repairBareEdition, "")
    base = base.replace(repairWhitespace, " ").trim()
    if (!usefulRepairTitle(base)) return existing
    val numbered = edition.season?.let { season -> "$base Season $season" } ?: base
    val full = edition.part?.let { "$numbered Part $it" } ?: numbered
    val short = edition.season?.let { "$base $it" } ?: base
    val shortPart = edition.part?.let { "$short Part $it" } ?: short
    return (listOf(original, full, shortPart, numbered, short, base) + existing)
        .filter(::usefulRepairTitle).distinctBy(::repairPlain).take(8)
}

/** Revisit previous automatic conversions if original local edition evidence disagrees. */
internal fun needsLegacySeasonRepair(row: ReiAnixLibraryCatalogRow): Boolean {
    val local = row.anime
    if (local.anilistId == null || local.anilistMatchManual == true ||
        local.metadataSource.equals("manual", true) || local.metadataStatus.equals("manual", true) ||
        row.episodes.none { it.missing != true && (it.availabilityState.isNullOrBlank() || it.availabilityState.equals("available", true)) }
    ) return false
    val expected = expectedRepairEdition(local, explicitAniListLocalEvidence(row.episodes))
    if (expected.conflict || !expected.hasMarker) return false
    val stored = combineRepairEditions(listOfNotNull(local.title, local.romaji, local.english, local.nativeTitle)
        .map { repairEdition(it) })
    return stored.conflict || (expected.season != null && stored.season != expected.season) ||
        (expected.part != null && stored.part != expected.part) ||
        (expected.format != null && local.format != expected.format)
}

private fun repairEditSimilarity(left: String, right: String): Double {
    if (left == right) return 1.0
    if (left.isEmpty() || right.isEmpty()) return 0.0
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (i in left.indices) {
        current[0] = i + 1
        for (j in right.indices) {
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (left[i] == right[j]) 0 else 1)
        }
        val swap = previous; previous = current; current = swap
    }
    return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
}

private data class RepairTitle(val value: String, val compact: String = explicitAniListCompactTitle(value),
    val words: Set<String> = repairWords(value))

/** Explicit repair only: genuine title evidence, then soft local metadata. */
internal fun rankExplicitAniListRepair(
    local: ReiAnixAnimeRecord,
    anchor: String,
    candidates: List<AniListAnime>,
    evidence: ExplicitAniListLocalEvidence = ExplicitAniListLocalEvidence(),
): List<ReiAnixAniListMatcher.RankedCandidate> {
    val independent = independentRepairTitles(local, evidence)
    val titles = explicitAniListRepairTitles(local, evidence).map { RepairTitle(it) }
    val primary = independent.ifEmpty { titles.map { it.value } }.toSet()
    val fileKeys = evidence.fileTitles.filter(::usefulRepairTitle).map(::explicitAniListCompactTitle).toSet()
    val anchorKey = explicitAniListCompactTitle(anchor)
    val independentlySourced = local.anilistId == null && !local.format.equals("MUSIC", true) &&
        !local.metadataSource.orEmpty().contains("anilist", true) &&
        !local.metadataSource.orEmpty().endsWith("_candidate", true)
    val localGenres = runCatching { JSONArray(local.genresJson.orEmpty()) }.getOrNull()?.let { raw ->
        (0 until raw.length()).map { repairPlain(raw.optString(it)) }.toSet()
    }.orEmpty()
    val localSynopsis = repairWords(local.descriptionOriginal ?: local.description.orEmpty())
    val edition = expectedRepairEdition(local, evidence)
    if (edition.conflict) return emptyList()
    val eligible = candidates.distinctBy { it.id }.filter {
        !it.format.equals("MUSIC", true) && it.coverCandidates.isNotEmpty() &&
            repairEditionCompatible(edition, it, candidates)
    }
        .map { candidate ->
            candidate to (listOfNotNull(candidate.titleRomaji, candidate.titleEnglish, candidate.titleNative) + candidate.synonyms)
                .filter(::usefulRepairTitle).distinctBy(::explicitAniListCompactTitle).map { RepairTitle(it) }
        }
    val frequencies = mutableMapOf<String, Int>()
    eligible.forEach { (_, remote) ->
        remote.flatMap { it.words }.toSet().forEach { word -> frequencies[word] = (frequencies[word] ?: 0) + 1 }
    }
    fun weight(word: String): Double =
        (1.0 + word.length.coerceAtMost(12) / 12.0) / kotlin.math.sqrt((frequencies[word] ?: 1).toDouble())
    val primaryTitles = titles.filter { it.value in primary }
    return eligible.mapNotNull { (candidate, remote) ->
        val remoteKeys = remote.map { it.compact }.toSet()
        val exactFile = fileKeys.any { it in remoteKeys }
        val exactOriginal = primaryTitles.any { it.compact == anchorKey } && anchorKey in remoteKeys
        val exactPrimary = primaryTitles.any { it.compact in remoteKeys }
        val exactOther = titles.any { it.compact in remoteKeys }
        var best = 0.0
        for (title in titles) {
            for (other in remote) {
                if (title.compact == other.compact) {
                    best = maxOf(best, if (title.value in primary) 0.98 else 0.94)
                    continue
                }
                val shared = title.words.intersect(other.words)
                val totalWeight = title.words.sumOf(::weight) + other.words.sumOf(::weight)
                val overlap = if (totalWeight > 0) 2.0 * shared.sumOf(::weight) / totalWeight else 0.0
                val minLength = minOf(title.compact.length, other.compact.length)
                val maxLength = maxOf(title.compact.length, other.compact.length)
                if (minLength < 4 || minLength.toDouble() / maxLength < 0.45) continue
                // Avoid quadratic edit work on obviously unrelated titles. A
                // no-word-overlap typo must be close in length to reach 0.82.
                if (overlap < 0.45 && (minLength < 5 || minLength.toDouble() / maxLength < 0.82)) continue
                val spelling = repairEditSimilarity(title.compact, other.compact)
                val close = minLength >= 5 && spelling >= 0.82
                // A single shared generic word or unrelated metadata is never sufficient.
                if (!close && (overlap < 0.45 || shared.isEmpty())) continue
                if (!close && shared.size == 1 && maxOf(title.words.size, other.words.size) > 2) continue
                val similarity = if (close) maxOf(spelling * 0.95, 0.60 * spelling + 0.40 * overlap)
                    else 0.55 * spelling + 0.45 * overlap
                best = maxOf(best, similarity * if (title.value in primary) 1.0 else 0.94)
            }
        }
        if (best <= 0.0) return@mapNotNull null
        var score = when {
            exactFile -> maxOf(best, 0.995)
            exactOriginal -> maxOf(best, 0.98)
            exactPrimary -> maxOf(best, 0.97)
            exactOther -> maxOf(best, 0.94)
            else -> best.coerceAtMost(0.90)
        }
        val localDuration = evidence.durationMinutes
        val remoteDuration = candidate.durationMinutes
        if (localDuration != null && remoteDuration != null && localDuration >= 5) {
            val difference = kotlin.math.abs(localDuration - remoteDuration)
            if (difference <= 5) score += 0.035
            else if (difference >= 25) score -= 0.025
        }
        if (evidence.episodeCount > 1 && (candidate.episodes ?: 0) >= evidence.episodeCount) score += 0.015
        if (independentlySourced) {
            if (local.year != null && local.year == candidate.seasonYear) score += 0.02
            if (localGenres.intersect(candidate.genres.map(::repairPlain).toSet()).isNotEmpty()) score += 0.015
            val remoteSynopsis = repairWords(candidate.description.orEmpty())
            if (localSynopsis.size >= 5 && remoteSynopsis.size >= 5 &&
                localSynopsis.intersect(remoteSynopsis).size.toDouble() / minOf(localSynopsis.size, remoteSynopsis.size) >= 0.45) score += 0.015
        }
        val cap = if (exactPrimary) 1.0 else if (exactOther) 0.94 else 0.92
        ReiAnixAniListMatcher.RankedCandidate(candidate, score.coerceIn(0.0, cap),
            if (exactFile) "file" else if (exactOriginal) "original" else if (exactPrimary) "folder" else if (exactOther) "alias" else "words")
    }.sortedWith(compareByDescending<ReiAnixAniListMatcher.RankedCandidate> { it.score }
        .thenByDescending { it.titleKind == "file" }.thenByDescending { it.titleKind == "original" }
        .thenBy { it.anime.id })
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
