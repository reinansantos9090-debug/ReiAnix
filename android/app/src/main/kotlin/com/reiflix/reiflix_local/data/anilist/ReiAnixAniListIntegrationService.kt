package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.organize.ReiAnixGenreRegistry

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
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryCatalogRow
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reiflix.reiflix_local.data.sqlite.updateAniListMetadataAndAssociation
import com.reiflix.reiflix_local.data.sqlite.recordAniListAutoMatchOutcome
import com.reiflix.reiflix_local.data.sqlite.setMetadataStatusByLookupTitle
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository

import java.io.File
import java.net.URI
import java.util.Locale
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


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
        // Keep this eligibility policy deterministic in both Android runtime and plain
        // JVM unit tests. android.net.Uri methods can be unimplemented in the mockable
        // android.jar used by local unit tests; swallowing that exception misclassifies
        // every valid HTTP(S) URL as missing artwork.
        val parsed = runCatching { URI(coverUrl.trim()) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
        val hasUsableHttpUrl = scheme != null &&
            scheme in setOf("http", "https") &&
            !parsed?.host.isNullOrBlank() &&
            parsed?.rawUserInfo == null
        if (hasUsableHttpUrl && !hasExhaustedArtworkFailure) return false
    }
    if (manualMetadata && !hasAniListId) return false
    // Stable title-search outcomes are governed by their search-signature TTL.
    // Known-ID retries use the persisted discovery-attempt marker at the caller;
    // transient failures clear that marker instead of being hidden by metadataFetchedAt.
    return true
}

internal const val MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS = 24L * 60L * 60L

/** Temporary server/network failures must not be turned into definitive lookup cooldowns. */
internal fun autoMatchFailureStatus(error: AniListError): String? = when (error) {
    is AniListError.RateLimited -> "rate_limited"
    is AniListError.Network -> "network_error"
    is AniListError.Http -> when {
        error.statusCode == 429 -> "rate_limited"
        error.statusCode == 408 || error.statusCode >= 500 -> "network_error"
        else -> "invalid_response"
    }
    is AniListError.GraphQl, is AniListError.InvalidResponse -> "invalid_response"
    is AniListError.InvalidRequest -> null
}

internal fun AniListError.isTransientForAniListRetry(): Boolean = when (this) {
    is AniListError.RateLimited, is AniListError.Network,
    is AniListError.GraphQl, is AniListError.InvalidResponse -> true
    is AniListError.Http -> statusCode == 408 || statusCode == 429 || statusCode >= 500
    is AniListError.InvalidRequest -> false
}


internal fun rotateArtworkDiscoveryIds(ids: List<Long>, cursor: Long): List<Long> {
    val sorted = ids.filter { it > 0L }.distinct().sorted()
    if (sorted.isEmpty()) return emptyList()
    val start = sorted.indexOfFirst { it > cursor }.let { if (it < 0) 0 else it }
    return sorted.drop(start) + sorted.take(start)
}

internal const val ARTWORK_DISCOVERY_CURSOR_PREFERENCE = "__reianix.internal.artwork_discovery_cursor_v1"
internal const val ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX = "__reianix.internal.artwork_discovery_attempt_v1."
internal const val ANILIST_SEARCH_SIGNATURE_PREFERENCE_PREFIX = "__reianix.internal.anilist_search_signature_v1."
internal const val ANILIST_STABLE_MATCH_RETRY_TTL_SECONDS = 7L * 24L * 60L * 60L

internal data class PersistedAniListSearchSignature(
    val signature: String,
    val attemptedAtSeconds: Double,
)

internal fun aniListSearchTermsSignature(
    terms: List<String>,
    seasonEvidence: Int? = null,
    yearEvidence: Int? = null,
    mediaKindEvidence: String? = null,
): String {
    val canonical = buildList {
        addAll(
            terms.map(ReiAnixAniListMatcher::normalizeForLookup)
                .filter(String::isNotBlank)
                .distinct(),
        )
        add("season=${seasonEvidence ?: ""}")
        add("year=${yearEvidence ?: ""}")
        add("format=${mediaKindEvidence?.trim()?.lowercase(Locale.ROOT).orEmpty()}")
    }.joinToString("\u001f")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun shouldSkipStableAniListRetry(
    matchStatus: String?,
    previous: PersistedAniListSearchSignature?,
    currentSignature: String,
    nowSeconds: Double,
): Boolean {
    if (matchStatus?.lowercase(Locale.ROOT) !in setOf("not_found", "ambiguous")) return false
    if (previous == null || previous.signature != currentSignature) return false
    val elapsed = nowSeconds - previous.attemptedAtSeconds
    return elapsed >= 0.0 && elapsed < ANILIST_STABLE_MATCH_RETRY_TTL_SECONDS.toDouble()
}

/** Search signatures share the canonical SQLite v29 preferences table; no DDL is needed. */
internal suspend fun readPersistedAniListSearchSignatures(
    database: ReiAnixSqliteDatabase,
): Map<Long, PersistedAniListSearchSignature> = withContext(Dispatchers.IO) {
    database.read { connection ->
        connection.rawQuery(
            "SELECT key, value FROM preferences WHERE substr(key, 1, ?) = ?",
            arrayOf(
                ANILIST_SEARCH_SIGNATURE_PREFERENCE_PREFIX.length.toString(),
                ANILIST_SEARCH_SIGNATURE_PREFERENCE_PREFIX,
            ),
        ).use { cursor ->
            buildMap<Long, PersistedAniListSearchSignature> {
                while (cursor.moveToNext()) {
                    val animeId = cursor.getString(0).orEmpty()
                        .removePrefix(ANILIST_SEARCH_SIGNATURE_PREFERENCE_PREFIX)
                        .toLongOrNull()?.takeIf { it > 0L } ?: continue
                    val stored = cursor.getString(1).orEmpty().split('|', limit = 2)
                    val signature = stored.getOrNull(0)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                        ?: continue
                    val attemptedAt = stored.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: continue
                    put(animeId, PersistedAniListSearchSignature(signature, attemptedAt))
                }
            }
        }
    }
}

internal suspend fun writePersistedAniListSearchSignature(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
    terms: List<String>,
    attemptedAtSeconds: Double,
    seasonEvidence: Int? = null,
    yearEvidence: Int? = null,
    mediaKindEvidence: String? = null,
) = withContext(Dispatchers.IO) {
    require(animeId > 0L)
    require(attemptedAtSeconds.isFinite() && attemptedAtSeconds >= 0.0)
    val value = aniListSearchTermsSignature(terms, seasonEvidence, yearEvidence, mediaKindEvidence) + "|" + attemptedAtSeconds
    database.transaction { connection ->
        val values = ContentValues().apply {
            put("key", ANILIST_SEARCH_SIGNATURE_PREFERENCE_PREFIX + animeId)
            put("value", value)
            put("updated_at", attemptedAtSeconds)
        }
        connection.insertWithOnConflict("preferences", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

internal suspend fun clearPersistedAniListSearchSignature(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
) = withContext(Dispatchers.IO) {
    require(animeId > 0L)
    database.transaction { connection ->
        connection.delete(
            "preferences",
            "key=?",
            arrayOf(ANILIST_SEARCH_SIGNATURE_PREFERENCE_PREFIX + animeId),
        )
    }
}

internal fun persistedAniListSearchSignaturePreferenceKey(animeId: Long): String {
    require(animeId > 0L)
    return ANILIST_SEARCH_SIGNATURE_PREFERENCE_PREFIX + animeId
}

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

internal suspend fun clearPersistedArtworkDiscoveryAttemptAt(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
) = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Artwork discovery requires a positive local anime ID." }
    database.transaction { connection ->
        connection.delete(
            "preferences",
            "key=?",
            arrayOf(artworkDiscoveryAttemptPreferenceKey(animeId)),
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


internal fun boundedAniListSearchTerms(candidates: List<String>, limit: Int = 8): List<String> {
    require(limit in 1..8) { "AniList title search limit must be between 1 and 8." }
    return candidates.asSequence()
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length in 2..180 && ReiAnixAniListMatcher.normalizeForLookup(it).isNotBlank() }
        .distinctBy(ReiAnixAniListMatcher::normalizeForLookup)
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
) {
    private val effectiveGenreRegistry: ReiAnixGenreRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        genreRegistry ?: ReiAnixGenreRegistry(library.database)
    }
    private val autoMatchAttempts: ConcurrentHashMap<Long, Long> = autoMatchAttemptTimestamps
    private val missingArtworkLookupAttempts = ConcurrentHashMap<Long, Long>()
    private val missingArtworkRecoveryRunning = AtomicBoolean(false)
    private val autoMatchBatchRunning = AtomicBoolean(false)

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

    private fun localAniListSearchTerms(
        local: ReiAnixAnimeRecord,
        additionalTitleCandidates: List<String> = emptyList(),
    ): List<String> {
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
            addAll(additionalTitleCandidates)
        }
        val variants = localTitleCandidates.flatMap { candidate ->
            buildList {
                addAll(ReiAnixAniListMatcher.localTitleSearchVariants(candidate))
                if (candidate.contains('/') || candidate.indexOf(92.toChar()) >= 0) {
                    addAll(ReiAnixAniListMatcher.extractLocalTitleCandidates(candidate))
                }
            }
        }
        return boundedAniListSearchTerms(variants, limit = 8)
    }

    private fun episodeSeasonEvidence(row: ReiAnixLibraryCatalogRow): Int? {
        val seasons = buildSet {
            row.episodes.asSequence()
                .filter {
                    it.identificationSource in setOf("sxxexx", "temp_episode", "x_episode") &&
                        it.identificationConfidence.equals("high", ignoreCase = true)
                }
                .map { it.season }
                .filter { it > 0 }
                .forEach { add(it) }
            row.episodes.asSequence()
                .map { it.path }
                .filter(String::isNotBlank)
                .distinct()
                .take(8)
                .mapNotNull(ReiAnixAniListMatcher::extractLocalSeason)
                .forEach { add(it) }
        }
        return seasons.singleOrNull()
    }

    private fun episodeYearEvidence(row: ReiAnixLibraryCatalogRow): Int? =
        row.episodes.asSequence()
            .map { it.path }
            .filter(String::isNotBlank)
            .distinct()
            .take(8)
            .mapNotNull(ReiAnixAniListMatcher::extractLocalYear)
            .distinct()
            .take(2)
            .toList()
            .singleOrNull()

    private fun inferTitleYear(values: List<String>): Int? =
        values.firstNotNullOfOrNull { value ->
            Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").find(value)?.value?.toIntOrNull()
        }

    private fun inferTitleSeason(values: List<String>): Int? =
        values.firstNotNullOfOrNull { value ->
            Regex("(?i)\\b(?:s|season|temporada|temp)[ ._-]*(\\d{1,3})\\b")
                .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)[ ._-]*season\\b")
                    .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

    /**
     * Sample a few distinct source paths rather than expanding every episode path into
     * an unbounded candidate list. The per-anime title query is capped separately.
     */
    private fun episodeTitleCandidates(row: ReiAnixLibraryCatalogRow): List<String> = buildList {
        row.episodes.asSequence()
            .map { it.path }
            .filter(String::isNotBlank)
            .distinct()
            .take(4)
            .forEach { path ->
                val extracted = ReiAnixAniListMatcher.extractLocalTitleCandidates(path)
                addAll(extracted)
                val pathYear = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").find(path)?.value
                val fileTitle = extracted.firstOrNull()
                if (pathYear != null && !fileTitle.isNullOrBlank()) add("$fileTitle $pathYear")
            }
        row.episodes.asSequence()
            .mapNotNull { it.relativePath?.takeIf(String::isNotBlank) }
            .distinct()
            .take(2)
            .forEach { addAll(ReiAnixAniListMatcher.extractLocalTitleCandidates(it)) }
        row.episodes.asSequence()
            .mapNotNull { it.fileName.takeIf(String::isNotBlank) }
            .distinct()
            .take(2)
            .forEach { addAll(ReiAnixAniListMatcher.extractLocalTitleCandidates(it)) }
        row.episodes.asSequence()
            .mapNotNull { it.sourceFolder?.takeIf(String::isNotBlank) }
            .distinct()
            .take(2)
            .forEach { addAll(ReiAnixAniListMatcher.localTitleSearchVariants(it)) }
    }.distinctBy(ReiAnixAniListMatcher::normalizeForLookup)
        .filter { ReiAnixAniListMatcher.normalizeForLookup(it).isNotBlank() }
        .take(16)

    suspend fun autoMatch(
        animeId: Long,
        force: Boolean = false,
    ): AniListResult<AniListAutoMatchResult> =
        autoMatchInternal(animeId, force)

    private suspend fun autoMatchInternal(
        animeId: Long,
        force: Boolean,
        additionalTitleCandidates: List<String> = emptyList(),
        preloadedCandidates: List<AniListAnime> = emptyList(),
        preloadedSearchSupplied: Boolean = false,
        preloadedSearchComplete: Boolean = true,
        searchTermsOverride: List<String>? = null,
        seasonEvidence: Int? = null,
        yearEvidence: Int? = null,
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
        val searchTerms = searchTermsOverride ?: localAniListSearchTerms(local, additionalTitleCandidates)
        if (searchTerms.isEmpty()) {
            autoMatchAttempts.remove(animeId)
            return AniListResult.Failure(
                AniListError.InvalidRequest("Local anime has no usable title for AniList lookup."),
            )
        }
        val rawLocalTitles = buildList {
            add(local.title)
            add(local.lookupTitle)
            add(local.nativeTitle.orEmpty())
            add(local.romaji.orEmpty())
            add(local.english.orEmpty())
            addAll(additionalTitleCandidates)
        }
        val resolvedYear = yearEvidence ?: local.year ?: inferTitleYear(rawLocalTitles)
        val resolvedSeason = seasonEvidence ?: local.season?.toIntOrNull() ?: inferTitleSeason(rawLocalTitles)
        val resolvedMediaKind = local.mediaKind
        val nowMillis = System.currentTimeMillis()
        val nowSeconds = nowMillis / 1000.0
        if (!force && !preloadedSearchSupplied) {
            val previousAttempt = autoMatchAttempts[animeId]
            val previousSignature = readPersistedAniListSearchSignatures(library.database)[animeId]
            val currentSignature = aniListSearchTermsSignature(
                searchTerms,
                resolvedSeason,
                resolvedYear,
                resolvedMediaKind,
            )
            val signatureChanged = previousSignature?.signature != currentSignature
            if (previousAttempt != null && nowMillis - previousAttempt < AUTO_MATCH_COOLDOWN_MS &&
                !signatureChanged
            ) {
                return AniListResult.Success(AniListAutoMatchResult.RecentlyAttempted)
            }
            if (shouldSkipStableAniListRetry(
                    local.anilistMatchStatus,
                    previousSignature,
                    currentSignature,
                    nowSeconds,
                )
            ) {
                return AniListResult.Success(AniListAutoMatchResult.RecentlyAttempted)
            }
        }
        autoMatchAttempts[animeId] = nowMillis

        val candidates = if (preloadedSearchSupplied) {
            if (!preloadedSearchComplete) {
                recordAutoMatchOutcome(animeId, "invalid_response")
                return AniListResult.Failure(
                    AniListError.InvalidResponse("AniList returned an incomplete title-search batch."),
                )
            }
            preloadedCandidates
        } else {
            val batch = try {
                client.searchAnimeBatch(searchTerms, perTermLimit = 10)
            } catch (cancelled: CancellationException) {
                autoMatchAttempts.remove(animeId)
                throw cancelled
            }
            when (batch) {
                is AniListResult.Failure -> {
                    val status = autoMatchFailureStatus(batch.error)
                    if (status != null) recordAutoMatchOutcome(animeId, status)
                    else autoMatchAttempts.remove(animeId)
                    return batch
                }
                is AniListResult.Success -> {
                    val incomplete = searchTerms.any { term ->
                        val normalized = ReiAnixAniListMatcher.normalizeForLookup(term)
                        batch.value.incompleteKeys.any {
                            ReiAnixAniListMatcher.normalizeForLookup(it) == normalized
                        } || batch.value.items.keys.none {
                            ReiAnixAniListMatcher.normalizeForLookup(it) == normalized
                        }
                    }
                    if (incomplete) {
                        recordAutoMatchOutcome(animeId, "invalid_response")
                        return AniListResult.Failure(
                            AniListError.InvalidResponse("AniList returned an incomplete title-search alias."),
                        )
                    }
                    searchTerms.flatMap { term ->
                        val normalized = ReiAnixAniListMatcher.normalizeForLookup(term)
                        batch.value.items.entries.firstOrNull {
                            ReiAnixAniListMatcher.normalizeForLookup(it.key) == normalized
                        }?.value.orEmpty()
                    }.distinctBy { it.id }
                }
            }
        }

        val searchContext = ReiAnixAniListMatcher.Context(
            year = resolvedYear,
            mediaKind = resolvedMediaKind,
            season = resolvedSeason,
        )
        val ranked = ReiAnixAniListMatcher.rank(searchTerms, candidates, searchContext)
        if (ranked.isEmpty() || ranked.first().score < ReiAnixAniListMatcher.MINIMUM_SCORE) {
            recordAutoMatchOutcome(
                animeId,
                "not_found",
                searchTerms = searchTerms,
                seasonEvidence = resolvedSeason,
                yearEvidence = resolvedYear,
                mediaKindEvidence = resolvedMediaKind,
            )
            return AniListResult.Success(AniListAutoMatchResult.NoMatch)
        }

        val best = ranked.first()
        val second = ranked.getOrNull(1)?.score ?: 0.0
        val margin = best.score - second
        if (best.score < AUTO_MATCH_MIN_SCORE || margin < AUTO_MATCH_MIN_MARGIN) {
            recordAutoMatchOutcome(
                animeId,
                "ambiguous",
                score = best.score,
                margin = margin,
                searchTerms = searchTerms,
                seasonEvidence = resolvedSeason,
                yearEvidence = resolvedYear,
                mediaKindEvidence = resolvedMediaKind,
            )
            return AniListResult.Success(
                AniListAutoMatchResult.Ambiguous(ranked.take(5).map { it.anime }),
            )
        }
        val persisted = when (
            val result = applyRemote(local, best.anime, manual = false, score = best.score, margin = margin)
        ) {
            is AniListResult.Success -> result.value
            is AniListResult.Failure -> {
                if (result.error.isTransientForAniListRetry()) autoMatchAttempts.remove(animeId)
                return result
            }
        }
        clearPersistedAniListSearchSignature(library.database, animeId)
        autoMatchAttempts.remove(animeId)
        return AniListResult.Success(AniListAutoMatchResult.Matched(persisted, best.anime))
    }

    /**
     * Packs all local titles and alternative variants into aliases batches of at most
     * 15 terms. One local anime is kept wholly inside one request group.
     */
    private suspend fun autoMatchBatch(
        locals: List<ReiAnixAnimeRecord>,
        additionalTitleCandidatesByAnimeId: Map<Long, List<String>> = emptyMap(),
        additionalSeasonEvidenceByAnimeId: Map<Long, Int> = emptyMap(),
        additionalYearEvidenceByAnimeId: Map<Long, Int> = emptyMap(),
    ): Map<Long, AniListResult<AniListAutoMatchResult>> {
        if (locals.isEmpty() || !autoMatchBatchRunning.compareAndSet(false, true)) return emptyMap()
        return try {
            autoMatchBatchLocked(
                locals,
                additionalTitleCandidatesByAnimeId,
                additionalSeasonEvidenceByAnimeId,
                additionalYearEvidenceByAnimeId,
            )
        } finally {
            autoMatchBatchRunning.set(false)
        }
    }

    private suspend fun autoMatchBatchLocked(
        locals: List<ReiAnixAnimeRecord>,
        additionalTitleCandidatesByAnimeId: Map<Long, List<String>>,
        additionalSeasonEvidenceByAnimeId: Map<Long, Int>,
        additionalYearEvidenceByAnimeId: Map<Long, Int>,
    ): Map<Long, AniListResult<AniListAutoMatchResult>> {
        data class Work(
            val local: ReiAnixAnimeRecord,
            val additionalTitles: List<String>,
            val terms: List<String>,
            val seasonEvidence: Int?,
            val yearEvidence: Int?,
        )
        val results = linkedMapOf<Long, AniListResult<AniListAutoMatchResult>>()
        val previousSignatures = readPersistedAniListSearchSignatures(library.database)
        val nowMillis = System.currentTimeMillis()
        val nowSeconds = nowMillis / 1000.0
        val workItems = mutableListOf<Work>()

        for (local in locals) {
            currentCoroutineContext().ensureActive()
            if (
                local.id <= 0L ||
                local.anilistId != null ||
                local.anilistMatchManual == true ||
                local.metadataSource == "manual" ||
                local.metadataStatus == "manual"
            ) continue

            val additionalTitles = additionalTitleCandidatesByAnimeId[local.id].orEmpty()
            val terms = localAniListSearchTerms(local, additionalTitles)
            if (terms.isEmpty()) {
                results[local.id] = AniListResult.Failure(
                    AniListError.InvalidRequest("Local anime has no usable title for AniList lookup."),
                )
                continue
            }
            val rawLocalTitles = buildList {
                add(local.title)
                add(local.lookupTitle)
                add(local.nativeTitle.orEmpty())
                add(local.romaji.orEmpty())
                add(local.english.orEmpty())
                addAll(additionalTitles)
            }
            val resolvedSeason = additionalSeasonEvidenceByAnimeId[local.id]
                ?: local.season?.toIntOrNull()
                ?: inferTitleSeason(rawLocalTitles)
            val resolvedYear = local.year
                ?: additionalYearEvidenceByAnimeId[local.id]
                ?: inferTitleYear(rawLocalTitles)
            val signature = aniListSearchTermsSignature(terms, resolvedSeason, resolvedYear, local.mediaKind)
            if (shouldSkipStableAniListRetry(
                    local.anilistMatchStatus,
                    previousSignatures[local.id],
                    signature,
                    nowSeconds,
                )
            ) {
                results[local.id] = AniListResult.Success(AniListAutoMatchResult.RecentlyAttempted)
                continue
            }
            val previousAttempt = autoMatchAttempts[local.id]
            val signatureChanged = previousSignatures[local.id]?.signature != signature
            if (previousAttempt != null && nowMillis - previousAttempt < AUTO_MATCH_COOLDOWN_MS &&
                !signatureChanged
            ) {
                results[local.id] = AniListResult.Success(AniListAutoMatchResult.RecentlyAttempted)
                continue
            }
            // Reserve eligible IDs before issuing the first request so overlapping scans cannot duplicate work.
            autoMatchAttempts[local.id] = nowMillis
            workItems += Work(
                local = local,
                additionalTitles = additionalTitles,
                terms = terms,
                seasonEvidence = resolvedSeason,
                yearEvidence = resolvedYear,
            )
        }

        val requestGroups = mutableListOf<List<Work>>()
        var currentGroup = mutableListOf<Work>()
        var currentTerms = linkedMapOf<String, String>()
        for (work in workItems) {
            val missingKeys = work.terms.filter {
                ReiAnixAniListMatcher.normalizeForLookup(it) !in currentTerms
            }
            if (currentGroup.isNotEmpty() &&
                currentTerms.size + missingKeys.distinctBy(ReiAnixAniListMatcher::normalizeForLookup).size >
                ReiAnixAniListClient.MAX_BATCH_SIZE
            ) {
                requestGroups += currentGroup.toList()
                currentGroup = mutableListOf()
                currentTerms = linkedMapOf()
            }
            currentGroup += work
            work.terms.forEach { term ->
                currentTerms.putIfAbsent(ReiAnixAniListMatcher.normalizeForLookup(term), term)
            }
        }
        if (currentGroup.isNotEmpty()) requestGroups += currentGroup.toList()

        for (group in requestGroups) {
            currentCoroutineContext().ensureActive()
            val queryTerms = linkedMapOf<String, String>()
            group.forEach { work ->
                work.terms.forEach { term ->
                    queryTerms.putIfAbsent(ReiAnixAniListMatcher.normalizeForLookup(term), term)
                }
            }
            val batch = try {
                client.searchAnimeBatch(queryTerms.values.toList(), perTermLimit = 10)
            } catch (cancelled: CancellationException) {
                workItems.filterNot { it.local.id in results }
                    .forEach { autoMatchAttempts.remove(it.local.id) }
                throw cancelled
            }
            when (batch) {
                is AniListResult.Failure -> {
                    val status = autoMatchFailureStatus(batch.error)
                    for (work in group) {
                        if (status != null) recordAutoMatchOutcome(work.local.id, status)
                        else autoMatchAttempts.remove(work.local.id)
                        results[work.local.id] = batch
                    }
                }
                is AniListResult.Success -> {
                    val payload = batch.value
                    for (work in group) {
                        currentCoroutineContext().ensureActive()
                        val normalizedKeys = work.terms.map(ReiAnixAniListMatcher::normalizeForLookup)
                        val incomplete = normalizedKeys.any { key ->
                            payload.incompleteKeys.any {
                                ReiAnixAniListMatcher.normalizeForLookup(it) == key
                            } || payload.items.keys.none {
                                ReiAnixAniListMatcher.normalizeForLookup(it) == key
                            }
                        }
                        if (incomplete) {
                            recordAutoMatchOutcome(work.local.id, "invalid_response")
                            results[work.local.id] = AniListResult.Failure(
                                AniListError.InvalidResponse("AniList returned an incomplete title-search alias."),
                            )
                        } else {
                            val candidates = normalizedKeys.flatMap { key ->
                                payload.items.entries.firstOrNull {
                                    ReiAnixAniListMatcher.normalizeForLookup(it.key) == key
                                }?.value.orEmpty()
                            }.distinctBy { it.id }
                            results[work.local.id] = try {
                                autoMatchInternal(
                                    animeId = work.local.id,
                                    force = false,
                                    additionalTitleCandidates = work.additionalTitles,
                                    preloadedCandidates = candidates,
                                    preloadedSearchSupplied = true,
                                    preloadedSearchComplete = true,
                                    searchTermsOverride = work.terms,
                                    seasonEvidence = work.seasonEvidence,
                                    yearEvidence = work.yearEvidence,
                                )
                            } catch (cancelled: CancellationException) {
                                workItems.filterNot { it.local.id in results }
                                    .forEach { autoMatchAttempts.remove(it.local.id) }
                                throw cancelled
                            }
                        }
                    }
                }
            }
        }
        return results
    }

    private suspend fun recordAutoMatchOutcome(
        animeId: Long,
        status: String,
        score: Double? = null,
        margin: Double? = null,
        searchTerms: List<String>? = null,
        seasonEvidence: Int? = null,
        yearEvidence: Int? = null,
        mediaKindEvidence: String? = null,
    ) {
        if (status in AUTO_MATCH_RETRYABLE_STATUSES) {
            autoMatchAttempts.remove(animeId)
            clearPersistedAniListSearchSignature(library.database, animeId)
        }
        library.recordAniListAutoMatchOutcome(
            animeId = animeId,
            status = status,
            score = score,
            margin = margin,
        )
        if (status in AUTO_MATCH_TERMINAL_STATUSES && !searchTerms.isNullOrEmpty()) {
            writePersistedAniListSearchSignature(
                library.database,
                animeId,
                searchTerms,
                System.currentTimeMillis() / 1000.0,
                seasonEvidence = seasonEvidence,
                yearEvidence = yearEvidence,
                mediaKindEvidence = mediaKindEvidence,
            )
        }
        // Publish even unresolved rows so the native projection can show their
        // persisted status. A false requestArtwork flag prevents unrelated image work.
        enqueueMetadataCommit(animeId, requestArtwork = false)
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
     * The full catalog is scanned with a resumable cursor; per-request aliases and local
     * candidate windows are bounded to keep large libraries from creating request bursts.
     */
    suspend fun recoverMissingArtworkMetadata(): Int {
        if (!missingArtworkRecoveryRunning.compareAndSet(false, true)) return 0
        val selectedRows = mutableListOf<ReiAnixAnimeRecord>()
        val completedAttemptIds = mutableSetOf<Long>()
        try {
            if (!isAniListEnabled() || !settingEnabled("artwork.enabled", defaultValue = true)) return 0
            val autoMatchEnabled = settingEnabled("metadata.auto_match", defaultValue = true)
            val rows = library.loadLibraryCatalogRows()
            if (rows.isEmpty()) return 0
            val rowsById = rows.associateBy { it.anime.id }
            val discoveryCursor = readArtworkDiscoveryCursor()
            val persistedAttemptTimes = readArtworkDiscoveryAttemptTimes().toMutableMap()
            val persistedSearchSignatures = readPersistedAniListSearchSignatures(library.database)
            val orderedRows = rotateArtworkDiscoveryIds(rowsById.keys.toList(), discoveryCursor)
                .mapNotNull(rowsById::get)
            val orderedIds = orderedRows.map { it.anime.id }
            val completedDiscoveryIds = orderedIds.toMutableSet()
            var completedPrefixSize = 0
            var persistedProgressCursor = discoveryCursor
            var persistedCursorValue = discoveryCursor

            suspend fun persistCompletedDiscoveryPrefix() {
                while (
                    completedPrefixSize < orderedIds.size &&
                    orderedIds[completedPrefixSize] in completedDiscoveryIds
                ) {
                    persistedProgressCursor = orderedIds[completedPrefixSize]
                    completedPrefixSize++
                }
                if (persistedProgressCursor != persistedCursorValue) {
                    writeArtworkDiscoveryCursor(persistedProgressCursor)
                    persistedCursorValue = persistedProgressCursor
                }
            }
            val exhaustedPosterIds = library.database.read { connection ->
                connection.rawQuery(
                    "SELECT DISTINCT entity_id FROM artwork WHERE entity_type IN ('anime','movie') " +
                        "AND artwork_type='poster' AND status='failed' AND COALESCE(failure_count, 0)>=5",
                    null,
                ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            }
            val nowSeconds = System.currentTimeMillis() / 1000.0

            for (row in orderedRows) {
                currentCoroutineContext().ensureActive()
                val local = row.anime
                if (local.id <= 0L) continue
                if (local.anilistId == null && !autoMatchEnabled) continue
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

                val titleEvidence = if (local.anilistId == null) episodeTitleCandidates(row) else emptyList()
                val currentTerms = if (local.anilistId == null) {
                    localAniListSearchTerms(local, titleEvidence)
                } else {
                    emptyList()
                }
                val localRawTitles = buildList {
                    add(local.title)
                    add(local.lookupTitle)
                    add(local.nativeTitle.orEmpty())
                    add(local.romaji.orEmpty())
                    add(local.english.orEmpty())
                    addAll(titleEvidence)
                }
                val seasonEvidence = if (local.anilistId == null) {
                    episodeSeasonEvidence(row) ?: local.season?.toIntOrNull() ?: inferTitleSeason(localRawTitles)
                } else null
                val yearEvidence = if (local.anilistId == null) {
                    local.year ?: episodeYearEvidence(row) ?: inferTitleYear(localRawTitles)
                } else null
                val currentSignature = aniListSearchTermsSignature(currentTerms, seasonEvidence, yearEvidence, local.mediaKind)
                val previousSignature = persistedSearchSignatures[local.id]
                val signatureChanged = local.anilistId == null && (
                    previousSignature?.signature != currentSignature ||
                        (previousSignature != null &&
                            nowSeconds - previousSignature.attemptedAtSeconds >= ANILIST_STABLE_MATCH_RETRY_TTL_SECONDS)
                    )
                if (local.anilistId == null && shouldSkipStableAniListRetry(
                        local.anilistMatchStatus,
                        previousSignature,
                        currentSignature,
                        nowSeconds,
                    )
                ) continue
                if (shouldDeferArtworkDiscoveryAttempt(persistedAttemptTimes[local.id], nowSeconds) &&
                    !signatureChanged
                ) continue

                val attemptAt = nowSeconds.toLong()
                val previousAttempt = missingArtworkLookupAttempts.putIfAbsent(local.id, attemptAt)
                if (previousAttempt != null) {
                    if (!signatureChanged &&
                        nowSeconds - previousAttempt < MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS
                    ) continue
                    if (!missingArtworkLookupAttempts.replace(local.id, previousAttempt, attemptAt)) continue
                }
                completedDiscoveryIds.remove(local.id)
                selectedRows += local
            }

            var recovered = 0
            suspend fun finishAttempt(local: ReiAnixAnimeRecord, persistCooldown: Boolean) {
                if (persistCooldown) {
                    val completedAtSeconds = System.currentTimeMillis() / 1000.0
                    writeArtworkDiscoveryAttemptAt(local.id, completedAtSeconds)
                    persistedAttemptTimes[local.id] = completedAtSeconds
                } else {
                    missingArtworkLookupAttempts.remove(local.id)
                    try {
                        clearPersistedArtworkDiscoveryAttemptAt(library.database, local.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not clear transient AniList artwork lookup cooldown animeId=" + local.id, error)
                    }
                    persistedAttemptTimes.remove(local.id)
                }
                completedAttemptIds += local.id
                completedDiscoveryIds += local.id
                persistCompletedDiscoveryPrefix()
            }

            // Persist any leading rows that were safely skipped before the first selected row.
            persistCompletedDiscoveryPrefix()

            // Every ID chunk is fetched, decoded and persisted before the next chunk starts.
            val rowsByRemoteId = selectedRows.mapNotNull { local ->
                local.anilistId
                    ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                    ?.toInt()
                    ?.let { it to local }
            }.groupBy({ it.first }, { it.second })
            for (idBatch in rowsByRemoteId.keys.toList().chunked(ReiAnixAniListClient.MAX_BATCH_SIZE)) {
                currentCoroutineContext().ensureActive()
                val batchById: Map<Int, AniListResult<AniListAnime?>> = when (val batch = client.getAnimeBatch(idBatch)) {
                    is AniListResult.Failure -> idBatch.associateWith { AniListResult.Failure(batch.error) }
                    is AniListResult.Success -> {
                        val returned = batch.value.items.associateBy { it.id }
                        idBatch.associateWith { id ->
                            when {
                                returned[id] != null -> AniListResult.Success(returned[id])
                                batch.value.isComplete -> AniListResult.Success(null)
                                else -> AniListResult.Failure(
                                    AniListError.InvalidResponse("AniList returned a partial ID batch without this ID."),
                                )
                            }
                        }
                    }
                }
                for (remoteId in idBatch) {
                    for (local in rowsByRemoteId[remoteId].orEmpty()) {
                        currentCoroutineContext().ensureActive()
                        var persistCooldown = true
                        when (val lookup = batchById.getValue(remoteId)) {
                            is AniListResult.Success -> {
                                val remote = lookup.value
                                if (remote == null) {
                                    Log.w(TAG, "Missing-artwork AniList batch returned no media for animeId=" + local.id)
                                } else {
                                    val posterUrl = remote.coverExtraLargeUrl ?: remote.coverLargeUrl
                                    when (val applied = applyRemote(
                                        local = local,
                                        remote = remote,
                                        manual = local.anilistMatchManual == true ||
                                            local.metadataSource == "manual" || local.metadataStatus == "manual",
                                        score = local.anilistMatchScore,
                                        margin = local.anilistMatchMargin,
                                    )) {
                                        is AniListResult.Success -> {
                                            if (!posterUrl.isNullOrBlank() && applied.value.coverUrl == posterUrl) {
                                                recovered++
                                            }
                                        }
                                        is AniListResult.Failure -> {
                                            persistCooldown = !applied.error.isTransientForAniListRetry()
                                            Log.w(TAG, "Missing-artwork metadata update failed for animeId=" + local.id + ": " + applied.error)
                                        }
                                    }
                                }
                            }
                            is AniListResult.Failure -> {
                                persistCooldown = !lookup.error.isTransientForAniListRetry()
                                Log.w(TAG, "Missing-artwork AniList batch failed for animeId=" + local.id + ": " + lookup.error)
                            }
                        }
                        finishAttempt(local, persistCooldown)
                    }
                }
            }

            // Invalid persisted IDs are not silently converted into title searches.
            val invalidIdRows = selectedRows.filter {
                it.anilistId != null && it.anilistId !in 1L..Int.MAX_VALUE.toLong()
            }
            for (local in invalidIdRows) {
                Log.w(TAG, "Skipping invalid persisted AniList ID for local animeId=" + local.id)
                finishAttempt(local, persistCooldown = true)
            }

            // Unknown IDs are searched by aliases in groups of 15; metadata for each
            // primary-title match is persisted before the next title batch is submitted.
            val titleRows = if (autoMatchEnabled) {
                selectedRows.filter { it.anilistId == null }
            } else {
                emptyList()
            }
            for (titleBatch in titleRows.chunked(100)) {
                currentCoroutineContext().ensureActive()
                val titleEvidenceByAnimeId = titleBatch.associate { local ->
                    local.id to rowsById[local.id]?.let(::episodeTitleCandidates).orEmpty()
                }
                val titleSeasonEvidenceByAnimeId = titleBatch.mapNotNull { local ->
                    rowsById[local.id]?.let(::episodeSeasonEvidence)?.let { local.id to it }
                }.toMap()
                val titleYearEvidenceByAnimeId = titleBatch.mapNotNull { local ->
                    rowsById[local.id]?.let(::episodeYearEvidence)?.let { local.id to it }
                }.toMap()
                val titleResults = autoMatchBatch(
                    titleBatch,
                    titleEvidenceByAnimeId,
                    titleSeasonEvidenceByAnimeId,
                    titleYearEvidenceByAnimeId,
                )
                for (local in titleBatch) {
                    currentCoroutineContext().ensureActive()
                var persistCooldown = true
                when (val result = titleResults[local.id]) {
                    is AniListResult.Success -> when (val outcome = result.value) {
                        is AniListAutoMatchResult.Matched -> {
                            val posterUrl = outcome.remote.coverExtraLargeUrl ?: outcome.remote.coverLargeUrl
                            if (!posterUrl.isNullOrBlank() && outcome.anime.coverUrl == posterUrl) recovered++
                        }
                        AniListAutoMatchResult.RecentlyAttempted,
                        AniListAutoMatchResult.Disabled,
                        is AniListAutoMatchResult.AlreadyMatched,
                        is AniListAutoMatchResult.AlreadyManuallyMatched -> persistCooldown = false
                        AniListAutoMatchResult.NoMatch,
                        is AniListAutoMatchResult.Ambiguous -> Unit
                    }
                    is AniListResult.Failure -> {
                        persistCooldown = !result.error.isTransientForAniListRetry()
                        Log.w(TAG, "Missing-artwork title batch failed for animeId=" + local.id + ": " + result.error)
                    }
                    null -> persistCooldown = false
                }
                    finishAttempt(local, persistCooldown)
                }
            }

            persistCompletedDiscoveryPrefix()
            return recovered
        } finally {
            // Never leave an in-memory cooldown behind for an unfinished/cancelled row.
            selectedRows.asSequence()
                .map { it.id }
                .filterNot { it in completedAttemptIds }
                .forEach(missingArtworkLookupAttempts::remove)
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
        val eligibleRows = rows.filter { row ->
            val local = row.anime
            local.id > 0L &&
                local.anilistId == null &&
                local.anilistMatchManual != true &&
                local.metadataSource != "manual" &&
                local.metadataStatus != "manual"
        }
        var processed = 0
        for (catalogBatch in eligibleRows.chunked(100)) {
            currentCoroutineContext().ensureActive()
            val titleEvidence = catalogBatch.associate { row ->
                row.anime.id to episodeTitleCandidates(row)
            }
            val seasonEvidence = catalogBatch.mapNotNull { row ->
                episodeSeasonEvidence(row)?.let { row.anime.id to it }
            }.toMap()
            val yearEvidence = catalogBatch.mapNotNull { row ->
                episodeYearEvidence(row)?.let { row.anime.id to it }
            }.toMap()
            processed += autoMatchBatch(
                catalogBatch.map { it.anime },
                titleEvidence,
                seasonEvidence,
                yearEvidence,
            ).size
        }
        return processed
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
                    posterArtworkUrl = (remote.coverExtraLargeUrl ?: remote.coverLargeUrl).takeIf { artworkEnabled },
                    posterArtworkSourceRef = "anilist:${remote.id}:coverImage",
                    posterArtworkVariant = if (remote.coverExtraLargeUrl != null) "extraLarge" else "large",
                    posterArtworkPriority = 400,
                    // AniList bannerImage is Banner, not the legacy Backdrop banner_url column.
                    bannerUrl = null,
                    bannerArtworkUrl = remote.bannerUrl.takeIf { artworkEnabled },
                    bannerArtworkSourceRef = "anilist:${remote.id}:bannerImage",
                    bannerArtworkVariant = "original",
                    bannerArtworkPriority = 400,
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
        private val AUTO_MATCH_RETRYABLE_STATUSES = setOf(
            "network_error",
            "rate_limited",
            "invalid_response",
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
