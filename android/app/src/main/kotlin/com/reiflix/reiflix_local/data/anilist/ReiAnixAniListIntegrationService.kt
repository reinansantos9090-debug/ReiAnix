package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.organize.ReiAnixGenreRegistry

import android.graphics.BitmapFactory
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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


/** Pure policy for bounded discovery of missing posters from the canonical catalogue. */
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
    val status = matchStatus?.trim()?.lowercase(Locale.ROOT)
    val fetchedAt = metadataFetchedAtSeconds
    val recentlyFetched = fetchedAt != null && fetchedAt.isFinite() &&
        nowSeconds - fetchedAt < MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS
    val previousNoMatch = !hasAniListId && status in setOf("not_found", "ambiguous")
    if (previousNoMatch) {
        // A no-match/ambiguous result describes one search, not a permanent resolution.
        // The persisted attempt/freshness cooldown suppresses repeated requests, while
        // allowing a later pass to retry as AniList data and local aliases change.
        if (recentlyFetched) return false
    } else if (recentlyFetched) {
        return false
    }
    return true
}

internal const val MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS = 24L * 60L * 60L

/** Returns only AniList IDs representable by the GraphQL Int ID lookup. */
internal fun validAniListCatalogId(id: Long?): Long? =
    id?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }

/**
 * Cheap container validation protects against decoders/test shadows that accept arbitrary byte
 * streams. These are the same image formats that the existing artwork pipeline understands.
 */
private fun hasCompleteSupportedImageContainer(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    return runCatching {
        val length = file.length()
        java.io.RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(minOf(12L, length).toInt())
            input.readFully(header)
            fun matchesAt(offset: Int, bytes: ByteArray): Boolean =
                offset >= 0 && offset + bytes.size <= header.size &&
                    bytes.indices.all { header[offset + it] == bytes[it] }

            val jpeg = header.size >= 3 &&
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
            if (jpeg) {
                if (length < 4L) return@use false
                input.seek(length - 2L)
                return@use input.readUnsignedByte() == 0xFF && input.readUnsignedByte() == 0xD9
            }

            val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            if (matchesAt(0, pngSignature)) {
                if (length < 57L) return@use false
                val iend = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
                input.seek(length - iend.size)
                val tail = ByteArray(iend.size)
                input.readFully(tail)
                return@use tail.contentEquals(iend)
            }

            val gif = header.size >= 6 &&
                (String(header, 0, 6, Charsets.US_ASCII) == "GIF87a" ||
                    String(header, 0, 6, Charsets.US_ASCII) == "GIF89a")
            if (gif) {
                input.seek(length - 1L)
                return@use input.readUnsignedByte() == 0x3B
            }

            val webp = header.size >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            if (webp) {
                val declaredSize = (header[4].toLong() and 0xFF) or
                    ((header[5].toLong() and 0xFF) shl 8) or
                    ((header[6].toLong() and 0xFF) shl 16) or
                    ((header[7].toLong() and 0xFF) shl 24)
                return@use declaredSize >= 4L && declaredSize + 8L <= length
            }

            val avif = header.size >= 12 &&
                String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                String(header, 8, 4, Charsets.US_ASCII) in setOf("avif", "avis")
            avif && length >= 24L
        }
    }.getOrDefault(false)
}

/**
 * Validate a persisted local cover with a bounded-memory decode and a supported container check.
 * Runs on an I/O dispatcher at the call site so damaged files cannot stall Compose rendering.
 */
internal fun isUsableLocalCoverFile(path: String?): Boolean {
    val normalizedPath = path?.trim().orEmpty()
    if (normalizedPath.isEmpty()) return false
    return try {
        val file = File(normalizedPath)
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return false
        if (!hasCompleteSupportedImageContainer(file)) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false

        var sampleSize = 1
        while (maxOf(width / sampleSize, height / sampleSize) > 256 && sampleSize < (1 shl 30)) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
        try {
            bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap.recycle()
        }
    } catch (_: Exception) {
        false
    }
}


internal fun rotateArtworkDiscoveryIds(ids: List<Long>, cursor: Long): List<Long> {
    val sorted = ids.filter { it > 0L }.distinct().sorted()
    if (sorted.isEmpty()) return emptyList()
    val start = sorted.indexOfFirst { it > cursor }.let { if (it < 0) 0 else it }
    return sorted.drop(start) + sorted.take(start)
}

internal const val ARTWORK_DISCOVERY_CURSOR_PREFERENCE = "__reianix.internal.artwork_discovery_cursor_v1"
internal const val ANI_LIST_RECONCILIATION_CURSOR_PREFERENCE = "__reianix.internal.anilist_reconciliation_cursor_v1"
internal const val ANI_LIST_AUTO_MATCH_ATTEMPT_PREFERENCE_PREFIX = "__reianix.internal.anilist_auto_match_attempt_v1."
internal const val ARTWORK_DISCOVERY_ATTEMPT_PREFERENCE_PREFIX = "__reianix.internal.artwork_discovery_attempt_v1."

internal fun aniListAutoMatchAttemptPreferenceKey(animeId: Long): String {
    require(animeId > 0L) { "AniList auto-match attempt requires a positive anime ID." }
    return ANI_LIST_AUTO_MATCH_ATTEMPT_PREFERENCE_PREFIX + animeId
}

internal fun shouldDeferAniListAutoMatchAttempt(
    lastAttemptAtMs: Long?,
    nowMs: Long,
    cooldownMs: Long,
    force: Boolean = false,
): Boolean {
    if (force || lastAttemptAtMs == null || lastAttemptAtMs <= 0L || cooldownMs <= 0L) return false
    return nowMs - lastAttemptAtMs < cooldownMs
}

internal suspend fun readPersistedAniListAutoMatchAttemptAt(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
): Long? = withContext(Dispatchers.IO) {
    val key = aniListAutoMatchAttemptPreferenceKey(animeId)
    database.read { connection ->
        connection.rawQuery(
            "SELECT value FROM preferences WHERE key=? LIMIT 1",
            arrayOf(key),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.toLongOrNull()?.takeIf { it > 0L } else null
        }
    }
}

internal suspend fun writePersistedAniListAutoMatchAttemptAt(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
    attemptAtMs: Long,
) = withContext(Dispatchers.IO) {
    require(attemptAtMs > 0L) { "AniList auto-match attempt timestamp must be positive." }
    val values = ContentValues().apply {
        put("key", aniListAutoMatchAttemptPreferenceKey(animeId))
        put("value", attemptAtMs.toString())
        put("updated_at", System.currentTimeMillis() / 1000.0)
    }
    database.transaction { connection ->
        connection.insertWithOnConflict(
            "preferences",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

internal suspend fun clearPersistedAniListAutoMatchAttemptAt(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
) = withContext(Dispatchers.IO) {
    database.transaction { connection ->
        connection.delete(
            "preferences",
            "key=?",
            arrayOf(aniListAutoMatchAttemptPreferenceKey(animeId)),
        )
    }
}

/** Persisted cursor for bounded automatic metadata reconciliation, using canonical SQLite preferences. */
internal suspend fun readPersistedAniListReconciliationCursor(
    database: ReiAnixSqliteDatabase,
): Long = withContext(Dispatchers.IO) {
    database.read { connection ->
        connection.rawQuery(
            "SELECT value FROM preferences WHERE key=? LIMIT 1",
            arrayOf(ANI_LIST_RECONCILIATION_CURSOR_PREFERENCE),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.toLongOrNull()?.takeIf { it > 0L } ?: 0L else 0L
        }
    }
}

internal suspend fun writePersistedAniListReconciliationCursor(
    database: ReiAnixSqliteDatabase,
    animeId: Long,
) = withContext(Dispatchers.IO) {
    require(animeId >= 0L) { "AniList reconciliation cursor cannot be negative." }
    database.transaction { connection ->
        val values = ContentValues().apply {
            put("key", ANI_LIST_RECONCILIATION_CURSOR_PREFERENCE)
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
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length in 2..120 }
        .filter { seen.add(it.lowercase(Locale.ROOT)) }
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
        val lock = autoMatchLocks.computeIfAbsent(animeId) { Mutex() }
        return lock.withLock { autoMatchLocked(animeId, force) }
    }

    private suspend fun autoMatchLocked(
        animeId: Long,
        force: Boolean,
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
        validAniListCatalogId(local.anilistId)?.let { id ->
            return AniListResult.Success(AniListAutoMatchResult.AlreadyMatched(id))
        }
        val now = System.currentTimeMillis()
        val persistedAttempt = readPersistedAniListAutoMatchAttemptAt(library.database, animeId)
        val previousAttempt = listOfNotNull(autoMatchAttempts[animeId], persistedAttempt).maxOrNull()
        if (shouldDeferAniListAutoMatchAttempt(previousAttempt, now, AUTO_MATCH_COOLDOWN_MS, force)) {
            return AniListResult.Success(AniListAutoMatchResult.RecentlyAttempted)
        }
        autoMatchAttempts[animeId] = now
        // Persist before any remote search so a process restart cannot immediately repeat it.
        writePersistedAniListAutoMatchAttemptAt(library.database, animeId, now)
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
            clearPersistedAniListAutoMatchAttemptAt(library.database, animeId)
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
                            clearPersistedAniListAutoMatchAttemptAt(library.database, animeId)
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
                val usableLocalCover = withContext(Dispatchers.IO) {
                    isUsableLocalCoverFile(cachedPath)
                }
                val eligibleForDiscovery = shouldDiscoverMissingArtwork(
                    coverUrl = local.coverUrl,
                    hasUsableLocalCover = usableLocalCover,
                    hasAniListId = validAniListCatalogId(local.anilistId) != null,
                    hasExhaustedArtworkFailure = local.id.toString() in exhaustedPosterIds,
                    manualMetadata = local.anilistMatchManual == true ||
                        local.metadataSource == "manual" || local.metadataStatus == "manual",
                    matchStatus = local.anilistMatchStatus,
                    metadataFetchedAtSeconds = local.metadataFetchedAt,
                    nowSeconds = nowSeconds,
                )
                if (!eligibleForDiscovery) {
                    Log.d(TAG, "Artwork discovery skipped for animeId=${local.id}: cover is usable or metadata/protection cooldown applies.")
                    continue
                }
                if (shouldDeferArtworkDiscoveryAttempt(persistedAttemptTimes[local.id], nowSeconds)) {
                    Log.d(TAG, "Artwork discovery deferred for animeId=${local.id}: persisted attempt cooldown has not expired.")
                    continue
                }
                if (examined >= maxRecords) break
                val attemptAt = nowSeconds.toLong()
                val previousAttempt = missingArtworkLookupAttempts.putIfAbsent(local.id, attemptAt)
                if (previousAttempt != null) {
                    if (nowSeconds - previousAttempt < MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS ||
                        !missingArtworkLookupAttempts.replace(local.id, previousAttempt, attemptAt)
                    ) {
                        Log.d(TAG, "Artwork discovery skipped for animeId=${local.id}: another in-process lookup owns the cooldown.")
                        continue
                    }
                }
                examined++
                try {
                    // Persist before the request starts so a process restart after a network
                    // failure/crash cannot immediately repeat the same lookup.
                    val attemptAtSeconds = attemptAt.toDouble()
                    writeArtworkDiscoveryAttemptAt(local.id, attemptAtSeconds)
                    persistedAttemptTimes[local.id] = attemptAtSeconds
                    val lookupKind = if (validAniListCatalogId(local.anilistId) != null) {
                        "anilist_id"
                    } else {
                        "conservative_title_match"
                    }
                    Log.i(TAG, "Artwork discovery lookup started for animeId=${local.id}; lookup=$lookupKind.")
                    val remoteId = validAniListCatalogId(local.anilistId)
                if (remoteId != null) {
                    val remote = when (val result = client.getAnime(remoteId.toInt())) {
                        is AniListResult.Success -> result.value
                        is AniListResult.Failure -> {
                            Log.w(TAG, "Missing-artwork AniList lookup failed for animeId=${local.id}: ${result.error}")
                            null
                        }
                    }
                    if (remote == null) {
                        Log.w(TAG, "Missing-artwork AniList lookup returned no usable media record for animeId=${local.id}")
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
                                Log.i(TAG, "AniList poster URL persisted for animeId=${local.id}; existing artwork request was dispatched by metadata commit.")
                            } else {
                                Log.w(TAG, "AniList media record has no newly persisted poster URL for animeId=${local.id}")
                            }
                        }
                        is AniListResult.Failure -> Log.w(TAG, "Missing-artwork metadata update failed for animeId=${local.id}: ${applied.error}")
                    }
                } else {
                    if (local.anilistMatchManual == true || local.metadataSource == "manual" || local.metadataStatus == "manual") continue
                    when (val result = autoMatch(local.id)) {
                        is AniListResult.Success -> {
                            val matched = result.value as? AniListAutoMatchResult.Matched
                            if (matched != null) {
                                val remotePosterUrl = matched.remote.coverExtraLargeUrl ?: matched.remote.coverLargeUrl
                                if (!remotePosterUrl.isNullOrBlank() && matched.anime.coverUrl == remotePosterUrl) {
                                    recovered++
                                    Log.i(TAG, "AniList poster URL persisted after conservative title match for animeId=${local.id}; existing artwork request was dispatched by metadata commit.")
                                } else {
                                    Log.w(TAG, "AniList title match has no newly persisted poster URL for animeId=${local.id}")
                                }
                            }
                        }
                        is AniListResult.Failure -> Log.w(TAG, "Missing-artwork title discovery failed for animeId=${local.id}: ${result.error}")
                    }
                }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // One corrupt row, transient database problem, or unexpected service error
                    // must not prevent the remaining canonical IDs from being considered.
                    missingArtworkLookupAttempts.remove(local.id, attemptAt)
                    Log.e(TAG, "Artwork discovery failed for animeId=${local.id}; continuing with the next catalogue row.", error)
                }
            }
            if (lastVisitedCursor > 0L && lastVisitedCursor != discoveryCursor) {
                writeArtworkDiscoveryCursor(lastVisitedCursor)
                Log.i(TAG, "Artwork discovery batch completed: attempted=$examined recovered=$recovered cursor=$lastVisitedCursor.")
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
    suspend fun reconcileUnmatchedLibraryMetadata(maxRecords: Int = 12): Int {
        if (!isAniListEnabled() || !settingEnabled("metadata.auto_match", defaultValue = true)) return 0
        require(maxRecords in 1..24) { "AniList reconciliation budget must be between 1 and 24." }
        val rows = library.loadLibraryCatalogRows()
        if (rows.isEmpty()) return 0
        val rowsById = rows.associateBy { it.anime.id }
        val cursor = readPersistedAniListReconciliationCursor(library.database)
        val orderedRows = rotateArtworkDiscoveryIds(rowsById.keys.toList(), cursor)
            .mapNotNull(rowsById::get)
        var lastVisitedCursor = cursor
        var examined = 0
        var changedOutcomes = 0
        for (row in orderedRows) {
            if (examined >= maxRecords) break
            currentCoroutineContext().ensureActive()
            val local = row.anime
            if (local.id <= 0L) continue
            lastVisitedCursor = local.id
            if (validAniListCatalogId(local.anilistId) != null ||
                local.anilistMatchManual == true ||
                local.metadataSource == "manual" ||
                local.metadataStatus == "manual" ||
                local.anilistMatchStatus in AUTO_MATCH_TERMINAL_STATUSES
            ) continue

            examined++
            try {
                when (val result = autoMatch(local.id)) {
                    is AniListResult.Success -> when (result.value) {
                        is AniListAutoMatchResult.Matched -> changedOutcomes++
                        AniListAutoMatchResult.NoMatch -> changedOutcomes++
                        is AniListAutoMatchResult.Ambiguous -> changedOutcomes++
                        else -> Unit
                    }
                    is AniListResult.Failure -> if (result.error !is AniListError.InvalidRequest) {
                        changedOutcomes++
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "AniList metadata reconciliation failed for animeId=${local.id}; continuing with the next catalogue row.", error)
            }
        }
        if (lastVisitedCursor > 0L && lastVisitedCursor != cursor) {
            writePersistedAniListReconciliationCursor(library.database, lastVisitedCursor)
            Log.i(TAG, "AniList metadata reconciliation batch completed: attempted=$examined changed=$changedOutcomes cursor=$lastVisitedCursor.")
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
        // Shared across service instances so UI-initiated matching and background discovery
        // cannot issue the same anime lookup concurrently.
        private val autoMatchLocks = ConcurrentHashMap<Long, Mutex>()
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
