package com.reiflix.reiflix_local.data.artwork

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.graphics.BitmapFactory
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max

/**
 * Successful artwork materialization signal. It contains no path or URL: consumers
 * need only the stable SQLite identity to invalidate their existing projection.
 */
internal data class ReiAnixArtworkPublication(
    val requestId: String,
    val entityType: String,
    val entityId: String,
    val artworkType: String,
    val sourceKind: String,
    /** Stable identity of the committed bytes, never a synthetic timestamp. */
    val revision: String,
)

/**
 * Retained publication state for the existing Compose projection.
 *
 * Every entity/artwork key keeps its latest committed revision, so one consumer
 * cannot discard another cover's update and late collectors receive current state.
 */
internal class ReiAnixArtworkPublicationSignal {
    private val mutableEvents =
        MutableStateFlow<Map<String, ReiAnixArtworkPublication>>(emptyMap())

    val events: StateFlow<Map<String, ReiAnixArtworkPublication>> = mutableEvents.asStateFlow()

    @Synchronized
    fun publishIfCommitted(
        publication: ReiAnixArtworkPublication,
        associationCommitted: Boolean,
        sourceFileExists: Boolean,
    ): Boolean {
        if (!associationCommitted || !sourceFileExists) return false
        val identity = listOf(
            publication.entityType,
            publication.entityId,
            publication.artworkType,
        ).joinToString("|")
        if (mutableEvents.value[identity]?.revision == publication.revision) return false
        mutableEvents.value = mutableEvents.value + (identity to publication)
        return true
    }
}

/** Ensure the on-disk cache used by the native AniList artwork materializer exists. */
internal fun ensureArtworkCacheDirectory(directory: File): Boolean =
    directory.isDirectory || runCatching { directory.mkdirs() || directory.isDirectory }.getOrDefault(false)

/**
 * Keep only paths proven visually invalid; a valid older thumbnail must survive
 * invalidation of a different row. This pure selection rule is unit-testable.
 */
internal fun invalidGeneratedThumbnailPaths(
    paths: List<String>,
    isUsable: (String) -> Boolean,
): List<String> = paths
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .filterNot(isUsable)

internal const val PERMANENT_ARTWORK_RETRY_DELAY_SECONDS = 24L * 60L * 60L
internal const val BASE_ARTWORK_RETRY_BACKOFF_SECONDS = 60L
internal const val MAX_ARTWORK_RETRY_BACKOFF_SECONDS = 6L * 60L * 60L
internal const val MAX_AUTOMATIC_ARTWORK_FAILURES = 5

/** HTTP and local persistence failures that should keep an automatic recovery path. */
internal fun isRetryableArtworkHttpStatus(statusCode: Int): Boolean =
    statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599

internal fun isRetryableArtworkFailure(
    httpStatus: Int?,
    transportOrFileIoFailure: Boolean,
    sqlitePersistenceFailure: Boolean,
): Boolean {
    if (httpStatus != null) return isRetryableArtworkHttpStatus(httpStatus)
    return transportOrFileIoFailure || sqlitePersistenceFailure
}

/**
 * Honors delta-seconds and RFC 1123 HTTP-date Retry-After values.
 * A new formatter is created per call because SimpleDateFormat is not thread-safe.
 */
internal fun parseArtworkRetryAfterSeconds(value: String?, nowMillis: Long): Long? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    raw.toLongOrNull()?.takeIf { it >= 0L }?.let {
        return it.coerceAtMost(Long.MAX_VALUE / 1_000L)
    }
    val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("GMT")
    }
    val retryAtMillis = runCatching { formatter.parse(raw)?.time }.getOrNull() ?: return null
    val remainingSeconds = (retryAtMillis.toDouble() - nowMillis.toDouble()) / 1_000.0
    return ceil(remainingSeconds.coerceAtLeast(0.0))
        .toLong()
        .coerceAtMost(Long.MAX_VALUE / 1_000L)
}

/** Exponential backoff for transient errors and a re-evaluation cooldown for permanent ones. */
internal fun artworkRetryDelaySeconds(
    failureCount: Int,
    retryable: Boolean,
    retryAfterSeconds: Long? = null,
): Long {
    val attempt = failureCount.coerceAtLeast(1)
    val exponential = if (retryable) {
        val exponent = (attempt - 1).coerceIn(0, 10)
        minOf(
            MAX_ARTWORK_RETRY_BACKOFF_SECONDS,
            BASE_ARTWORK_RETRY_BACKOFF_SECONDS * (1L shl exponent),
        )
    } else {
        PERMANENT_ARTWORK_RETRY_DELAY_SECONDS
    }
    val serverDelay = retryAfterSeconds
        ?.coerceAtLeast(0L)
        ?.coerceAtMost(Long.MAX_VALUE / 1_000L)
        ?: 0L
    return max(exponential, serverDelay)
}

/** The declared length is advisory when absent, but must match when supplied. */
internal fun artworkResponseMatchesContentLength(expectedBytes: Long, receivedBytes: Long): Boolean =
    expectedBytes <= 0L || expectedBytes == receivedBytes

/** Selects abandoned temporary files without touching files owned by active downloads. */
internal fun abandonedArtworkTemporaryFiles(directory: File, activePaths: Set<String>): List<File> =
    directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".tmp", ignoreCase = true) && it.absolutePath !in activePaths }
        .orEmpty()

/**
 * A persisted retry timestamp is authoritative. No attempt counter may turn a needed
 * artwork row into a terminal state; failed rows are re-evaluated when their cooldown ends.
 */
@Suppress("UNUSED_PARAMETER")
internal fun shouldRetryArtworkRequest(
    status: String?,
    retryAtSeconds: Double?,
    failureCount: Int,
    nowSeconds: Double,
    force: Boolean,
): Boolean {
    if (force) return true
    if (failureCount >= MAX_AUTOMATIC_ARTWORK_FAILURES) return false
    val normalizedStatus = status?.trim()?.lowercase(Locale.ROOT)
    if (normalizedStatus != "failed" && normalizedStatus != "retry_wait") return true
    val retryAt = retryAtSeconds?.takeIf { it.isFinite() } ?: return true
    return retryAt <= nowSeconds
}

/** Native persistent artwork writer for Compose, backed by the canonical v29 SQLite. */
class ReiAnixArtworkService(context: Context, private val database: ReiAnixSqliteDatabase) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(File(appContext.filesDir, "data/covers"), "artwork")
    private val publicationSignal = ReiAnixArtworkPublicationSignal()
    internal val publishedArtworks: StateFlow<Map<String, ReiAnixArtworkPublication>> = publicationSignal.events
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val retryJobs = ConcurrentHashMap<Long, Job>()
    private val activeTemporaryFiles = ConcurrentHashMap.newKeySet<String>()
    private val activeConnections = ConcurrentHashMap<Long, HttpURLConnection>()
    @Volatile
    internal var httpConnectionFactory: (URL) -> HttpURLConnection = { source ->
        source.openConnection() as? HttpURLConnection
            ?: throw IOException("Artwork source did not provide an HTTP connection.")
    }
    private val forceFollowUpIds = ConcurrentHashMap.newKeySet<Long>()
    private val permits = Semaphore(2)
    private val backfillRunning = AtomicBoolean(false)
    private val cacheClearInProgress = AtomicBoolean(false)
    @Volatile private var backfillJob: Job? = null

    private data class Anime(val anilistId: Long?, val kind: String?, val coverUrl: String?, val coverCache: String?, val bannerUrl: String?)
    private data class Artwork(
        val id: Long, val source: String, val sourceRef: String?, val path: String?, val url: String?,
        val manual: Boolean, val priority: Int, val status: String, val key: String?,
        val mime: String?, val retryAt: Double?, val failureCount: Int,
    )
    private data class Downloaded(
        val file: File, val bytes: Long, val width: Int, val height: Int,
        val checksum: String, val mime: String, val status: Int,
    )
    private data class SaveResult(val saved: Boolean, val current: Boolean)
    private class HttpFailure(val code: Int, val retryAfterSeconds: Long?) : IOException("Artwork HTTP " + code)
    private class InvalidImage(message: String) : Exception(message)

    fun requestAnimeArtwork(animeId: Long, force: Boolean = false): Boolean {
        val requestId = UUID.randomUUID().toString()
        if (animeId <= 0L || cacheClearInProgress.get()) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_REQUEST_REJECTED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = if (animeId <= 0L) "INVALID_ENTITY_ID" else "CACHE_CLEAR_IN_PROGRESS",
                requestId = requestId,
                context = mapOf("entity_id" to animeId, "force" to force),
            )
            return false
        }
        synchronized(jobs) {
            if (cacheClearInProgress.get()) {
                ReiAnixDiagnosticTimeline.record(
                    name = "NATIVE_ARTWORK_REQUEST_SKIPPED",
                    source = "ReiAnixArtworkService",
                    result = "CACHE_CLEAR_IN_PROGRESS",
                    requestId = requestId,
                    context = mapOf("entity_id" to animeId, "force" to force),
                )
                return false
            }
            if (jobs[animeId]?.isActive == true) {
                if (force) {
                    forceFollowUpIds.add(animeId)
                    ReiAnixDiagnosticTimeline.record(
                        name = "NATIVE_ARTWORK_FORCE_FOLLOW_UP_QUEUED",
                        source = "ReiAnixArtworkService",
                        result = "AFTER_ACTIVE_REQUEST",
                        requestId = requestId,
                        context = mapOf("entity_id" to animeId),
                    )
                    return true
                }
                ReiAnixDiagnosticTimeline.record(
                    name = "NATIVE_ARTWORK_REQUEST_SKIPPED",
                    source = "ReiAnixArtworkService",
                    result = "ALREADY_RUNNING",
                    requestId = requestId,
                    context = mapOf("entity_id" to animeId, "force" to false),
                )
                return false
            }
            retryJobs.remove(animeId)?.cancel()
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_REQUESTED",
                source = "ReiAnixArtworkService",
                result = "QUEUED",
                requestId = requestId,
                context = mapOf("entity_id" to animeId, "force" to force),
            )
            val task = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val anime = loadAnime(animeId)
                    if (anime == null) {
                        ReiAnixDiagnosticTimeline.record(
                            name = "NATIVE_ARTWORK_ASSOCIATION_MISSING",
                            severity = "WARN",
                            source = "ReiAnixArtworkService",
                            result = "LOCAL_ANIME_NOT_FOUND",
                            requestId = requestId,
                            context = mapOf("entity_id" to animeId),
                        )
                        return@launch
                    }
                    val entityType = if (anime.kind.equals("movie", true)) "movie" else "anime"
                    val posterUrl = anime.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
                    val backdropUrl = anime.bannerUrl?.trim()?.takeIf { it.isNotEmpty() }
                    ReiAnixDiagnosticTimeline.record(
                        name = "NATIVE_ARTWORK_SOURCES_RESOLVED",
                        source = "ReiAnixArtworkService",
                        result = if (posterUrl != null || backdropUrl != null) "SOURCE_CONFIGURED" else "NO_SOURCE_CONFIGURED",
                        requestId = requestId,
                        context = mapOf(
                            "entity_type" to entityType,
                            "entity_id" to animeId,
                            "poster_source_configured" to (posterUrl != null),
                            "backdrop_source_configured" to (backdropUrl != null),
                            "anilist_id_present" to (anime.anilistId != null),
                        ),
                    )
                    posterUrl?.let {
                        materialize(animeId, entityType, "poster", it, anime.anilistId, force, requestId)
                    }
                    backdropUrl?.let {
                        materialize(animeId, entityType, "backdrop", it, anime.anilistId, force, requestId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ReiAnixDiagnosticTimeline.record(
                        name = "NATIVE_ARTWORK_REQUEST_FAILED",
                        severity = "ERROR",
                        source = "ReiAnixArtworkService",
                        result = "FAILED",
                        requestId = requestId,
                        error = error,
                        context = mapOf("entity_id" to animeId),
                    )
                    Log.e(TAG, "Native artwork request failed for " + animeId, error)
                } finally {
                    currentCoroutineContext()[Job]?.let { jobs.remove(animeId, it) }
                    val forceFollowUp = forceFollowUpIds.remove(animeId)
                    if (!cacheClearInProgress.get()) {
                        if (forceFollowUp) {
                            requestAnimeArtwork(animeId, force = true)
                        } else {
                            // Re-read SQLite after poster/backdrop attempts. The earliest
                            // persisted retry remains authoritative across process restarts.
                            schedulePersistedArtworkRetry(animeId)
                        }
                    }
                }
            }
            jobs[animeId] = task
            task.start()
        }
        return true
    }

    /** Cancels the active materialization for an anime and closes its blocking HTTP stream. */
    internal fun cancelAnimeArtworkRequest(animeId: Long): Boolean {
        if (animeId <= 0L) return false
        val active = synchronized(jobs) { jobs[animeId]?.takeIf { it.isActive } } ?: return false
        active.cancel()
        activeConnections[animeId]?.disconnect()
        return true
    }

    /** Waits for a currently queued anime artwork pass without blocking the caller thread. */
    suspend fun awaitAnimeArtwork(animeId: Long, timeoutMillis: Long = 45_000L) {
        require(animeId > 0L)
        require(timeoutMillis in 1_000L..120_000L)
        withTimeoutOrNull(timeoutMillis) {
            jobs[animeId]?.join()
        }
    }

    /** True only when a decoded local image or protected manual artwork already exists. */
    suspend fun hasValidAnimeArtwork(animeId: Long, artworkType: String = "poster"): Boolean =
        withContext(Dispatchers.IO) {
            require(animeId > 0L)
            require(artworkType == "poster" || artworkType == "backdrop")
            val state = database.read { db ->
                val anime = db.rawQuery(
                    "SELECT cover_cache,media_kind FROM anime WHERE id=? LIMIT 1",
                    arrayOf(animeId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getString(1)
                } ?: return@read null
                val entity = if (anime.second.equals("movie", true)) "movie" else "anime"
                val rows = db.rawQuery(
                    "SELECT local_path,manual,source FROM artwork " +
                        "WHERE entity_type=? AND entity_id=? AND artwork_type=?",
                    arrayOf(entity, animeId.toString(), artworkType),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                Triple(
                                    cursor.getString(0),
                                    cursor.getInt(1) != 0,
                                    cursor.getString(2),
                                ),
                            )
                        }
                    }
                }
                Triple(anime.first, entity, rows)
            } ?: return@withContext false
            if (artworkType == "poster" && validImage(state.first)) return@withContext true
            state.third.any { (path, manual, source) ->
                manual || source.equals("manual", true) ||
                    (source.equals("local", true) && validImage(path)) ||
                    validImage(path)
            }
        }

    /**
     * Makes a verified external candidate eligible in the existing anime row only when
     * there is no valid local/manual image. The existing materializer remains the sole downloader.
     */
    suspend fun replaceExternalArtworkCandidate(
        animeId: Long,
        artworkType: String,
        rawUrl: String,
    ): Boolean = withContext(Dispatchers.IO) {
        require(animeId > 0L)
        require(artworkType == "poster" || artworkType == "backdrop")
        val url = rawUrl.trim()
        if (!safeUrl(url) || !url.startsWith("https://", ignoreCase = true)) return@withContext false
        database.transaction { db ->
            val row = db.rawQuery(
                "SELECT cover_cache,metadata_manual_fields,media_kind,cover_url,banner_url " +
                    "FROM anime WHERE id=? LIMIT 1",
                arrayOf(animeId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@transaction false
                arrayOf(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4),
                )
            }
            val targetField = if (artworkType == "poster") "cover_url" else "banner_url"
            val currentUrl = if (artworkType == "poster") row[3] else row[4]
            if (currentUrl?.trim() == url) return@transaction true

            // Any manual row is user-owned even if its file is currently unavailable.
            val entityType = if (row[2].equals("movie", true)) "movie" else "anime"
            val rows = db.rawQuery(
                "SELECT local_path,manual,source FROM artwork " +
                    "WHERE entity_type=? AND entity_id=? AND artwork_type=?",
                arrayOf(entityType, animeId.toString(), artworkType),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getString(0), cursor.getInt(1) != 0, cursor.getString(2)))
                    }
                }
            }
            if (rows.any { (path, manual, source) ->
                    manual || source.equals("manual", true) ||
                        (source.equals("local", true) && validImage(path)) ||
                        validImage(path)
                }
            ) return@transaction false
            if (artworkType == "poster" && validImage(row[0])) return@transaction false

            val manualFields = row[1]
            val fieldIsManual = if (manualFields.isNullOrBlank()) false else runCatching {
                val fields = JSONArray(manualFields)
                (0 until fields.length()).any { fields.optString(it) == targetField }
            }.getOrDefault(true)
            if (fieldIsManual) return@transaction false

            val values = ContentValues().apply { put(targetField, url) }
            db.update("anime", values, "id=?", arrayOf(animeId.toString())) == 1
        }
    }

    /** Repair existing persisted image URLs in bounded asynchronous batches. */
    fun requestPendingArtwork(): Boolean {
        if (cacheClearInProgress.get() || !backfillRunning.compareAndSet(false, true)) return false
        backfillJob = scope.launch {
            try {
                if (cacheClearInProgress.get()) return@launch
                cleanupAbandonedTemporaryFiles()
                var afterId = 0L
                while (currentCoroutineContext().isActive && !cacheClearInProgress.get()) {
                    val ids = database.read { db ->
                        db.rawQuery(
                            "SELECT id FROM anime WHERE id>? AND (TRIM(COALESCE(cover_url,''))!='' " +
                                "OR TRIM(COALESCE(banner_url,''))!='') ORDER BY id LIMIT 16",
                            arrayOf(afterId.toString()),
                        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
                    }
                    if (ids.isEmpty()) break
                    val batch = ArrayList<Job>()
                    ids.forEach { id ->
                        if (!cacheClearInProgress.get()) {
                            requestAnimeArtwork(id)
                            jobs[id]?.let(batch::add)
                        }
                    }
                    batch.distinct().joinAll()
                    afterId = ids.last()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Artwork backfill failed", error)
            } finally {
                backfillJob = null
                backfillRunning.set(false)
            }
        }
        return true
    }

    /**
     * Remove temporary files not owned by an active request in this process.
     * A force-stopped process leaves no active-path entries, so abandoned .tmp files
     * are collected by the next process-owned backfill.
     */
    private fun cleanupAbandonedTemporaryFiles() {
        if (!ensureArtworkCacheDirectory(cacheDirectory)) return
        val abandoned = abandonedArtworkTemporaryFiles(cacheDirectory, activeTemporaryFiles.toSet())
        var removed = 0
        abandoned.forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        if (removed > 0) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_TEMP_FILES_RECOVERED",
                source = "ReiAnixArtworkService",
                result = "ABANDONED_TEMP_FILES_REMOVED",
                context = mapOf("removed_count" to removed),
            )
        }
    }

    /**
     * Clears externally managed artwork through the canonical v29 SQLite store.
     * Local/manual artwork references are retained, and cache files are unlinked
     * only when no persisted artwork row still references them.
     */
    suspend fun clearManagedCache(): Int {
        check(cacheClearInProgress.compareAndSet(false, true)) {
            "Artwork cache clearing is already in progress."
        }
        try {
            val backfill = backfillJob
            backfill?.cancel()
            val scheduledRetries = retryJobs.values.toList()
            retryJobs.clear()
            scheduledRetries.forEach { it.cancel() }
            forceFollowUpIds.clear()
            val inFlight = synchronized(jobs) {
                jobs.values.toList().also { active -> active.forEach { it.cancel() } }
            }
            inFlight.joinAll()
            scheduledRetries.joinAll()
            backfill?.join()
            synchronized(jobs) {
                jobs.entries.toList()
                    .filter { !it.value.isActive }
                    .forEach { (id, job) -> jobs.remove(id, job) }
            }

            val removedPaths = database.transaction { db ->
                val paths = db.rawQuery(
                    "SELECT DISTINCT local_path FROM artwork " +
                        "WHERE source IN ('cache','anilist') AND manual=0 AND local_path IS NOT NULL",
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(0)?.trim().orEmpty()
                            if (path.isNotEmpty()) add(path)
                        }
                    }
                }
                db.delete("artwork", "source IN ('cache','anilist') AND manual=0", null)
                // Match Settings' existing cache-clear contract: expire remote metadata
                // and clear stale cover pointers without deleting the library records.
                db.execSQL("UPDATE anime SET metadata_updated_at=NULL, cover_cache=''")
                paths
            }

            var removed = 0
            val managedRoot = cacheDirectory.canonicalFile
            removedPaths.distinct().forEach { path ->
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@forEach
                if (isWithin(managedRoot, file) && !isArtworkPathReferenced(file.path) && file.isFile && file.delete()) {
                    removed++
                }
            }

            // Palette metadata and orphan files are disposable cache entries. Temporary
            // files are left alone, matching ArtworkEngine's orphan cleanup contract.
            cacheDirectory.listFiles()?.forEach { file ->
                if (!file.isFile || file.name.endsWith(".tmp")) return@forEach
                if (file.name.startsWith(".reiflix-palette-")) {
                    if (file.delete()) removed++
                } else if (!isArtworkPathReferenced(file.absolutePath) && file.delete()) {
                    removed++
                }
            }

            // Remove only legacy files directly under data/covers; never recurse into
            // managed subdirectories or delete a path still owned by a local/manual row.
            val legacyRoot = cacheDirectory.parentFile
            legacyRoot?.listFiles()?.forEach { file ->
                if (file.isFile && !isArtworkPathReferenced(file.absolutePath) && file.delete()) {
                    removed++
                }
            }
            return removed
        } finally {
            backfillJob = null
            backfillRunning.set(false)
            cacheClearInProgress.set(false)
        }
    }

    private fun isArtworkPathReferenced(path: String): Boolean = database.read { db ->
        db.rawQuery(
            "SELECT 1 FROM artwork WHERE local_path=? " +
                "UNION SELECT 1 FROM anime WHERE cover_cache=? LIMIT 1",
            arrayOf(path, path),
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun isWithin(root: File, candidate: File): Boolean {
        val rootPath = runCatching { root.canonicalFile.path }.getOrNull() ?: return false
        val candidatePath = runCatching { candidate.canonicalFile.path }.getOrNull() ?: return false
        return candidatePath.startsWith(rootPath + File.separator)
    }

    private suspend fun materialize(
        id: Long,
        entity: String,
        type: String,
        url: String,
        aniId: Long?,
        force: Boolean,
        requestId: String,
    ) {
        if (!safeUrl(url)) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_SOURCE_REJECTED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = "INVALID_SOURCE",
                requestId = requestId,
                context = mapOf("entity_type" to entity, "entity_id" to id, "artwork_type" to type),
            )
            return
        }
        if (!isUrlCurrent(id, type, url)) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_SOURCE_REJECTED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = "ASSOCIATION_CHANGED",
                requestId = requestId,
                context = mapOf("entity_type" to entity, "entity_id" to id, "artwork_type" to type),
            )
            return
        }
        permits.withPermit {
            currentCoroutineContext().ensureActive()
            if (reuseLocal(id, entity, type, url, aniId, requestId)) return@withPermit
            val key = remoteKey(aniId, url, type)
            val rowId = prepareRow(id, entity, type, url, key, force) ?: return@withPermit
            var image: Downloaded? = null
            try {
                // Re-associate a complete checksum-named file if the process stopped
                // after promotion but before the SQLite transaction committed.
                val completedImage = findCachedDownloadedImage(key) ?: download(id, key, url)
                image = completedImage
                currentCoroutineContext().ensureActive()
                val saved = persistDownloaded(rowId, id, type, url, key, completedImage)
                if (!saved.saved) {
                    removeIfUnreferenced(completedImage.file)
                    return@withPermit
                }
                if (saved.current) publish(
                    "artwork_published", entity, id.toString(), type,
                    completedImage.file.absolutePath, url, key, url, completedImage.mime, requestId,
                )
                trimCache()
            } catch (cancelled: CancellationException) {
                setCancelled(rowId)
                image?.let { removeIfUnreferenced(it.file) }
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                setFailure(
                    rowId = rowId,
                    error = error,
                    httpStatus = (error as? HttpFailure)?.code,
                    retryAfterSeconds = (error as? HttpFailure)?.retryAfterSeconds,
                )
                ReiAnixDiagnosticTimeline.record(
                    name = "NATIVE_ARTWORK_DOWNLOAD_FAILED",
                    severity = "ERROR",
                    source = "ReiAnixArtworkService",
                    result = "FAILED",
                    requestId = requestId,
                    error = error,
                    context = mapOf(
                        "entity_type" to entity,
                        "entity_id" to id,
                        "artwork_type" to type,
                        "source_kind" to artworkCatalogSource(url),
                        "association_status" to "CHECKED_BEFORE_DOWNLOAD",
                        "http_status" to ((error as? HttpFailure)?.code ?: -1),
                        "failure_kind" to artworkFailureKind(error),
                        "retry_after_seconds" to ((error as? HttpFailure)?.retryAfterSeconds ?: -1L),
                    ),
                )
                image?.let { removeIfUnreferenced(it.file) }
                Log.w(TAG, "ARTWORK_DOWNLOAD_FAILED entity=" + entity + ":" + id + " type=" + type, error)
            }
        }
    }

    private fun reuseLocal(id: Long, entity: String, type: String, url: String, aniId: Long?, requestId: String): Boolean {
        val rows = candidates(entity, id.toString(), type, url)
        if (rows.any { it.manual || it.source.equals("manual", true) }) return true
        if (rows.any { it.source.equals("local", true) && validImage(it.path) }) return true
        val existing = rows.firstOrNull { it.url == url && validImage(it.path) }
        if (existing != null) {
            val path = existing.path ?: return false
            if (markReady(existing.id, id, type, url, path)) {
                publish("artwork_published", entity, id.toString(), type, path, url,
                    existing.key ?: remoteKey(aniId, url, type), url, existing.mime ?: mime(path), requestId)
            }
            return true
        }
        if (type != "poster") return false
        val oldPath = loadAnime(id)?.coverCache?.trim().orEmpty()
        if (oldPath.isEmpty() || !validImage(oldPath)) return false
        val pathRows = rowsForPath(entity, id.toString(), type, oldPath)
        if (pathRows.isNotEmpty() && pathRows.none {
                it.status.equals("ready", true) && (it.source.equals("manual", true) ||
                    it.source.equals("local", true) || it.url == url)
            }) return false
        val file = File(oldPath)
        val dims = dimensions(oldPath) ?: return false
        val digest = checksum(file) ?: return false
        val key = legacyCoverKey(aniId, url, oldPath)
        val now = nowSeconds()
        val inserted = database.transaction { db ->
            if (!urlCurrent(db, id, type, url)) return@transaction false
            val old = findByIdentity(db, entity, id.toString(), type, key, url)
            val values = ContentValues().apply {
                put("source", "cache"); put("source_ref", url); put("local_path", oldPath); put("external_url", url)
                put("manual", 0); put("priority", 400); put("status", "ready"); put("updated_at", now)
                put("last_attempt_at", now); put("failure_count", 0); put("artwork_key", key); put("variant", "large")
                put("byte_size", file.length()); put("width", dims.first.takeIf { it > 0 })
                put("height", dims.second.takeIf { it > 0 }); put("checksum", digest); put("content_type", mime(oldPath))
                put("last_access", now); putNull("next_retry_at"); putNull("http_status")
                if (old == null) put("discovered_at", now)
            }
            val ok = if (old == null) db.insertOrThrow("artwork", null, values) > 0L
                else db.update("artwork", values, "id=?", arrayOf(old.id.toString())) > 0
            if (ok) updateCoverPointer(db, id, url, oldPath)
            ok
        }
        if (inserted) publish("artwork_published", entity, id.toString(), type, oldPath, url, key, url, mime(oldPath), requestId)
        return inserted
    }

    private fun prepareRow(id: Long, entity: String, type: String, url: String, key: String, force: Boolean): Long? =
        database.transaction { db ->
            if (!urlCurrent(db, id, type, url)) return@transaction null
            val rows = queryCandidates(db, entity, id.toString(), type, url)
            if (rows.any { it.manual || it.source.equals("manual", true) }) return@transaction null
            if (rows.any { it.source.equals("local", true) && validImage(it.path) }) return@transaction null
            val now = nowSeconds()
            val exact = rows.firstOrNull { it.sourceRef == url || it.key == key || it.url == url }
            if (exact != null) {
                if (!shouldRetryArtworkRequest(exact.status, exact.retryAt, exact.failureCount, now, force)) {
                    return@transaction null
                }
                val values = ContentValues().apply {
                    put("source", artworkCatalogSource(url)); put("source_ref", url); put("external_url", url)
                    put("status", "downloading"); put("priority", 400); put("updated_at", now); put("last_attempt_at", now)
                    put("failure_count", if (force) 0 else exact.failureCount)
                    putNull("next_retry_at"); putNull("http_status"); put("artwork_key", key); put("variant", "large")
                }
                return@transaction if (db.update("artwork", values, "id=?", arrayOf(exact.id.toString())) > 0) exact.id else null
            }
            db.execSQL(
                "UPDATE artwork SET priority=MIN(COALESCE(priority,350),350),updated_at=? WHERE entity_type=? AND entity_id=? " +
                    "AND artwork_type=? AND external_url IS NOT NULL AND external_url!=? AND manual=0 " +
                    "AND LOWER(COALESCE(source,'')) NOT IN ('manual','local')",
                arrayOf(now, entity, id.toString(), type, url),
            )
            val values = ContentValues().apply {
                put("entity_type", entity); put("entity_id", id.toString()); put("artwork_type", type)
                put("source", artworkCatalogSource(url)); put("source_ref", url); put("external_url", url); put("manual", 0)
                put("priority", 400); put("status", "downloading"); put("discovered_at", now); put("updated_at", now)
                put("last_attempt_at", now); put("failure_count", 0); put("artwork_key", key); put("variant", "large")
            }
            runCatching { db.insertOrThrow("artwork", null, values) }.getOrNull()
        }

    private fun findCachedDownloadedImage(key: String): Downloaded? {
        if (!cacheDirectory.isDirectory) return null
        val candidates = cacheDirectory.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.startsWith("$key-") &&
                    file.extension.lowercase(Locale.ROOT) in EXTENSIONS
            }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        for (file in candidates) {
            val extension = "." + file.extension.lowercase(Locale.ROOT)
            if (!validImage(file, extension)) {
                removeIfUnreferenced(file)
                continue
            }
            val digest = checksum(file) ?: continue
            if (file.name != "$key-$digest$extension") continue
            val dimensions = imageDimensions(file.absolutePath, extension) ?: continue
            return Downloaded(
                file = file,
                bytes = file.length(),
                width = dimensions.first,
                height = dimensions.second,
                checksum = digest,
                mime = mime(file.absolutePath),
                status = 200,
            )
        }
        return null
    }

    private fun artworkFailureKind(error: Exception): String = when (error) {
        is HttpFailure -> when {
            error.code == 429 -> "HTTP_429_RATE_LIMITED"
            error.code == 404 -> "HTTP_404_NOT_FOUND"
            error.code in 500..599 -> "HTTP_5XX_SERVER"
            error.code == 408 -> "HTTP_408_TIMEOUT"
            else -> "HTTP_CLIENT_OR_PROTOCOL"
        }
        is java.net.SocketTimeoutException -> "TRANSPORT_TIMEOUT"
        is InvalidImage -> "INVALID_IMAGE_CONTENT"
        is SQLiteException -> "SQLITE_PERSISTENCE"
        is IOException -> "NETWORK_OR_STORAGE_IO"
        else -> "PERSISTENCE_OR_UNEXPECTED"
    }

    private suspend fun download(animeId: Long, key: String, rawUrl: String): Downloaded {
        if (!ensureArtworkCacheDirectory(cacheDirectory)) throw IOException("Artwork cache directory unavailable.")
        val url = URL(rawUrl)
        val conn = httpConnectionFactory(url)
        activeConnections[animeId] = conn
        val tmp = File(cacheDirectory, "." + key + "-" + UUID.randomUUID() + ".tmp")
        activeTemporaryFiles.add(tmp.absolutePath)
        try {
            currentCoroutineContext().ensureActive()
            conn.requestMethod = "GET"; conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ReiAnix/ArtworkEngine-Kotlin")
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                val retryAfter = parseArtworkRetryAfterSeconds(
                    conn.getHeaderField("Retry-After"),
                    System.currentTimeMillis(),
                )
                runCatching { conn.errorStream?.close() }
                throw HttpFailure(code, retryAfter)
            }
            if (url.protocol.equals("https", true) && !conn.url.protocol.equals("https", true)) {
                throw InvalidImage("HTTPS artwork redirected to HTTP.")
            }
            val declared = conn.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (declared.isNotEmpty() && !declared.startsWith("image/")) throw InvalidImage("Content-Type is not image.")
            val expectedContentLength = conn.getHeaderFieldLong("Content-Length", -1L)
            if (expectedContentLength > MAX_DOWNLOAD_BYTES) throw InvalidImage("Artwork exceeds 32 MiB.")
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArrayOutputStream(32)
            var total = 0L
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        total += n.toLong()
                        if (total > MAX_DOWNLOAD_BYTES) throw InvalidImage("Artwork exceeds 32 MiB.")
                        val remaining = 32 - header.size()
                        if (remaining > 0) header.write(buffer, 0, minOf(remaining, n))
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                    output.flush(); output.fd.sync()
                }
            }
            if (total == 0L) throw InvalidImage("Empty image response.")
            if (!artworkResponseMatchesContentLength(expectedContentLength, total)) {
                throw IOException("Artwork response ended before its declared Content-Length.")
            }
            val ext = imageExtension(header.toByteArray()) ?: throw InvalidImage("Unknown image signature.")
            if (!validImage(tmp, ext)) throw InvalidImage("Image decoding validation failed.")
            val bounds = imageDimensions(tmp.absolutePath, ext) ?: throw InvalidImage("Invalid image dimensions.")
            val checksum = digest.digest().toHex()
            // Content-addressed names avoid replacing the previous valid image while an
            // update is materialized. Same-directory rename is atomic on filesystems that
            // support it. API 24-25 and unusual filesystems fall back to a copied file that
            // is validated before its path is ever committed to SQLite.
            val target = File(cacheDirectory, key + "-" + checksum + ext)
            if (target.exists()) {
                if (validImage(target, ext)) {
                    tmp.delete()
                    return Downloaded(
                        target, total, bounds.first, bounds.second, checksum,
                        declared.takeIf { it.startsWith("image/") } ?: mimeFromExtension(ext), code,
                    )
                }
                if (!target.delete()) throw IOException("Could not replace an invalid artwork cache file.")
            }
            if (!tmp.renameTo(target)) {
                try {
                    tmp.copyTo(target, overwrite = false)
                    if (!validImage(target, ext)) throw IOException("Promoted artwork copy failed validation.")
                } catch (error: Exception) {
                    target.delete()
                    throw IOException("Could not safely promote the downloaded artwork.", error)
                }
                tmp.delete()
            }
            return Downloaded(
                target, total, bounds.first, bounds.second, checksum,
                declared.takeIf { it.startsWith("image/") } ?: mimeFromExtension(ext), code,
            )
        } finally {
            tmp.delete()
            activeTemporaryFiles.remove(tmp.absolutePath)
            activeConnections.remove(animeId, conn)
            conn.disconnect()
        }
    }

    private fun persistDownloaded(rowId: Long, id: Long, type: String, url: String, key: String, image: Downloaded): SaveResult =
        database.transaction { db ->
            val row = artworkById(db, rowId) ?: return@transaction SaveResult(false, false)
            if (row.manual || row.source.equals("manual", true) || row.source.equals("local", true)) return@transaction SaveResult(false, false)
            val current = urlCurrent(db, id, type, url)
            val now = nowSeconds()
            val values = ContentValues().apply {
                put("source", "cache"); put("local_path", image.file.absolutePath); put("external_url", url)
                put("status", "ready"); put("priority", if (current) 400 else minOf(row.priority, 350))
                put("updated_at", now); put("last_attempt_at", now); put("failure_count", 0)
                put("artwork_key", key); put("variant", "large"); put("byte_size", image.bytes)
                put("width", image.width.takeIf { it > 0 }); put("height", image.height.takeIf { it > 0 })
                put("checksum", image.checksum); put("content_type", image.mime); put("last_access", now)
                putNull("next_retry_at"); put("http_status", image.status)
            }
            if (db.update("artwork", values, "id=?", arrayOf(rowId.toString())) != 1) return@transaction SaveResult(false, false)
            if (current && type == "poster") updateCoverPointer(db, id, url, image.file.absolutePath)
            SaveResult(true, current)
        }

    private fun markReady(rowId: Long, id: Long, type: String, url: String, path: String): Boolean =
        database.transaction { db ->
            if (!urlCurrent(db, id, type, url)) return@transaction false
            val row = artworkById(db, rowId) ?: return@transaction false
            if (row.manual || row.source.equals("manual", true) || row.source.equals("local", true)) return@transaction false
            val now = nowSeconds()
            val values = ContentValues().apply {
                put("source", "cache"); put("local_path", path); put("external_url", url); put("status", "ready")
                put("last_access", now); putNull("next_retry_at"); putNull("http_status")
                if (row.status != "ready" || row.path != path) put("updated_at", now)
            }
            db.update("artwork", values, "id=?", arrayOf(rowId.toString()))
            if (type == "poster") updateCoverPointer(db, id, url, path)
            true
        }

    private fun setFailure(
        rowId: Long,
        error: Exception,
        httpStatus: Int?,
        retryAfterSeconds: Long?,
    ): Long? {
        val retryable = isRetryableArtworkFailure(
            httpStatus = (error as? HttpFailure)?.let { httpStatus ?: it.code },
            transportOrFileIoFailure = error is IOException && error !is HttpFailure,
            sqlitePersistenceFailure = error is SQLiteException,
        )
        return database.transaction { db ->
            if (artworkById(db, rowId) == null) return@transaction null
            val previousCount = db.rawQuery(
                "SELECT failure_count FROM artwork WHERE id=?",
                arrayOf(rowId.toString()),
            ).use { if (it.moveToFirst()) it.getInt(0).coerceAtLeast(0) else 0 }
            val count = if (previousCount == Int.MAX_VALUE) Int.MAX_VALUE else previousCount + 1
            val retryDelay = artworkRetryDelaySeconds(count, retryable, retryAfterSeconds)
            val now = nowSeconds()
            val values = ContentValues().apply {
                // Permanent responses retain a retry timestamp so the required source
                // is re-evaluated after cooldown instead of becoming terminal forever.
                put("status", if (retryable) "retry_wait" else "failed")
                put("updated_at", now)
                put("last_attempt_at", now)
                put("failure_count", count)
                put("next_retry_at", now + retryDelay)
                if (httpStatus == null) putNull("http_status") else put("http_status", httpStatus)
            }
            db.update("artwork", values, "id=?", arrayOf(rowId.toString()))
            retryDelay
        }
    }

    /**
     * Schedule from canonical SQLite timestamps, not only an in-memory delay.
     * The process-start backfill rebuilds these jobs after process recreation.
     */
    private fun schedulePersistedArtworkRetry(animeId: Long) {
        if (animeId <= 0L || cacheClearInProgress.get()) return
        val retryAt = runCatching {
            database.read { db ->
                db.rawQuery(
                    "SELECT MIN(a.next_retry_at) FROM artwork a " +
                        "WHERE a.entity_id=? AND a.entity_type IN ('anime','movie') " +
                        "AND a.artwork_type IN ('poster','backdrop') " +
                        "AND a.status='retry_wait' AND a.failure_count < ? AND a.next_retry_at IS NOT NULL " +
                        "AND EXISTS (SELECT 1 FROM anime n WHERE n.id=? AND (" +
                        "(a.artwork_type='poster' AND TRIM(COALESCE(a.external_url,''))=TRIM(COALESCE(n.cover_url,''))) " +
                        "OR (a.artwork_type='backdrop' AND TRIM(COALESCE(a.external_url,''))=TRIM(COALESCE(n.banner_url,'')))))",
                    arrayOf(animeId.toString(), MAX_AUTOMATIC_ARTWORK_FAILURES.toString(), animeId.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not restore persisted artwork retry for animeId=$animeId", error)
        }.getOrNull() ?: return

        val now = nowSeconds()
        if (!retryAt.isFinite() || retryAt <= now) return
        val delaySeconds = ceil(retryAt - now)
            .toLong()
            .coerceAtLeast(1L)
            .coerceAtMost(Long.MAX_VALUE / 1_000L)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            delay(delaySeconds * 1_000L)
            currentCoroutineContext()[Job]?.let { retryJobs.remove(animeId, it) }
            if (!cacheClearInProgress.get()) requestAnimeArtwork(animeId)
        }
        val previous = retryJobs.put(animeId, task)
        previous?.cancel()
        if (cacheClearInProgress.get()) {
            retryJobs.remove(animeId, task)
            task.cancel()
        } else {
            task.start()
        }
    }

    private fun setCancelled(rowId: Long) {
        database.transaction { db ->
            db.update("artwork", ContentValues().apply {
                put("status", "not_requested"); put("updated_at", nowSeconds()); putNull("next_retry_at"); putNull("http_status")
            }, "id=?", arrayOf(rowId.toString()))
        }
    }

    private fun updateCoverPointer(db: SQLiteDatabase, id: Long, url: String, newPath: String): Boolean {
        val row = db.rawQuery(
            "SELECT cover_url,cover_cache,metadata_manual_fields,media_kind FROM anime WHERE id=? LIMIT 1",
            arrayOf(id.toString()),
        ).use { c -> if (!c.moveToFirst()) return false else arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) }
        if (row[0]?.trim() != url || ownsManualField(row[2], "cover_cache")) return false
        val oldPath = row[1]?.trim().orEmpty()
        if (oldPath == newPath) return false
        if (oldPath.isNotEmpty() && validImage(oldPath)) {
            val entity = if (row[3].equals("movie", true)) "movie" else "anime"
            val previous = queryRowsForPath(db, entity, id.toString(), "poster", oldPath)
            if (previous.isEmpty() || previous.any { it.manual || it.source.equals("manual", true) || it.source.equals("local", true) } ||
                previous.any { it.url == url }) return false
        }
        return db.update("anime", ContentValues().apply { put("cover_cache", newPath) },
            "id=? AND cover_url=?", arrayOf(id.toString(), url)) > 0
    }

    private fun ownsManualField(fields: String?, field: String): Boolean {
        if (fields.isNullOrBlank()) return false
        return try { val array = JSONArray(fields); (0 until array.length()).any { array.optString(it) == field } }
        catch (_: Exception) { true }
    }

    private fun isUrlCurrent(id: Long, type: String, url: String) = database.read { urlCurrent(it, id, type, url) }
    private fun urlCurrent(db: SQLiteDatabase, id: Long, type: String, url: String): Boolean {
        val column = if (type == "backdrop") "banner_url" else "cover_url"
        return db.rawQuery("SELECT $column FROM anime WHERE id=? LIMIT 1", arrayOf(id.toString()))
            .use { it.moveToFirst() && it.getString(0)?.trim() == url }
    }
    private fun loadAnime(id: Long): Anime? = database.read { db ->
        db.rawQuery("SELECT anilist_id,media_kind,cover_url,cover_cache,banner_url FROM anime WHERE id=? LIMIT 1",
            arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return@use null
            val index = c.getColumnIndexOrThrow("anilist_id")
            Anime(if (c.isNull(index)) null else c.getLong(index), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }
    }

    private fun candidates(entity: String, id: String, type: String, url: String): List<Artwork> =
        database.read { queryCandidates(it, entity, id, type, url) }
    private fun queryCandidates(db: SQLiteDatabase, entity: String, id: String, type: String, url: String): List<Artwork> =
        db.rawQuery("SELECT * FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=? " +
            "AND (external_url=? OR source_ref=? OR source IN ('manual','local') OR manual=1) " +
            "ORDER BY manual DESC,priority DESC,updated_at DESC,id DESC",
            arrayOf(entity, id, type, url, url)).use { c -> buildList { while (c.moveToNext()) add(c.toArtwork()) } }
    private fun rowsForPath(entity: String, id: String, type: String, path: String): List<Artwork> =
        database.read { queryRowsForPath(it, entity, id, type, path) }
    private fun queryRowsForPath(db: SQLiteDatabase, entity: String, id: String, type: String, path: String): List<Artwork> =
        db.rawQuery("SELECT * FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=? AND local_path=?",
            arrayOf(entity, id, type, path)).use { c -> buildList { while (c.moveToNext()) add(c.toArtwork()) } }
    private fun findByIdentity(db: SQLiteDatabase, entity: String, id: String, type: String, key: String, url: String) =
        db.rawQuery("SELECT * FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type=? " +
            "AND (artwork_key=? OR source_ref=? OR external_url=?) ORDER BY priority DESC,updated_at DESC,id DESC LIMIT 1",
            arrayOf(entity, id, type, key, url, url)).use { if (it.moveToFirst()) it.toArtwork() else null }
    private fun artworkById(db: SQLiteDatabase, id: Long): Artwork? =
        db.rawQuery("SELECT * FROM artwork WHERE id=? LIMIT 1", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toArtwork() else null }
    private fun Cursor.toArtwork() = Artwork(
        getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("source")),
        getString(getColumnIndexOrThrow("source_ref")), getString(getColumnIndexOrThrow("local_path")),
        getString(getColumnIndexOrThrow("external_url")), getInt(getColumnIndexOrThrow("manual")) != 0,
        getInt(getColumnIndexOrThrow("priority")), getString(getColumnIndexOrThrow("status")),
        getString(getColumnIndexOrThrow("artwork_key")), getString(getColumnIndexOrThrow("content_type")),
        getColumnIndex("next_retry_at").let { if (it < 0 || isNull(it)) null else getDouble(it) },
        getInt(getColumnIndexOrThrow("failure_count")),
    )

    /** Register thumbnails generated by VideoThumbnailExtractor in the canonical table. */
    fun registerGeneratedThumbnail(
        mediaUri: String, thumbnailPath: String, size: Long = 0L, modifiedAt: Long = 0L,
        mediaIdentity: String? = null, durationMs: Long = 0L,
    ): Boolean {
        val uri = mediaUri.trim()
        val file = File(thumbnailPath).absoluteFile
        if (uri.isEmpty() || !file.isFile || !validImage(file)) return false
        if (!com.reiflix.reiflix_local.storage.VideoThumbnailExtractor.isUsableThumbnailFile(file.absolutePath)) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_THUMBNAIL_REJECTED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = "VISUALLY_UNUSABLE",
                context = mapOf("stage" to "generated_thumbnail_registration", "file_bytes" to file.length()),
            )
            return false
        }
        // The extractor consumes file:// URIs, while canonical episode.path and
        // historical observations commonly store the same source as /absolute/path.
        // Match both forms before falling back to the stable media identity.
        val sourcePath = runCatching {
            Uri.parse(uri).takeIf { it.scheme.equals("file", ignoreCase = true) }?.path
        }.getOrNull()?.takeIf(String::isNotBlank) ?: uri
        val identity = mediaIdentity?.trim().takeUnless { it.isNullOrEmpty() } ?: uri
        val sourceRef = "native:" + identity + "|" + size.coerceAtLeast(0L) + "|" + modifiedAt.coerceAtLeast(0L)
        val key = sha256("native|" + sourceRef + "|episode_thumbnail|small")
        val digest = checksum(file) ?: return false
        val dims = imageDimensions(file.absolutePath) ?: return false
        val ids = database.read { db ->
            db.rawQuery("SELECT DISTINCT e.id FROM episodes e JOIN anime a ON a.id=e.anime_id " +
                "LEFT JOIN episode_observations o ON o.episode_id=e.id " +
                "WHERE e.path IN (?,?) OR o.uri IN (?,?) OR (? != '' AND e.media_identity=?) ORDER BY e.id",
                arrayOf(uri, sourcePath, uri, sourcePath, identity, identity)).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        }
        if (ids.isEmpty()) return false
        var registered = 0
        val now = nowSeconds()
        for (episodeId in ids) {
            val saved = database.transaction { db ->
                val matched = db.rawQuery("SELECT 1 FROM episodes e LEFT JOIN episode_observations o ON o.episode_id=e.id " +
                    "WHERE e.id=? AND (e.path IN (?,?) OR o.uri IN (?,?) OR (? != '' AND e.media_identity=?)) LIMIT 1",
                    arrayOf(episodeId.toString(), uri, sourcePath, uri, sourcePath, identity, identity)).use { it.moveToFirst() }
                if (!matched) return@transaction false
                db.delete("artwork", "entity_type='episode' AND entity_id=? AND artwork_type='episode_thumbnail' " +
                    "AND source='generated' AND NOT (source_ref=? AND local_path=?)",
                    arrayOf(episodeId.toString(), sourceRef, file.absolutePath))
                val existing = db.rawQuery("SELECT id FROM artwork WHERE entity_type='episode' AND entity_id=? " +
                    "AND artwork_type='episode_thumbnail' AND (source_ref=? OR artwork_key=?) ORDER BY id DESC LIMIT 1",
                    arrayOf(episodeId.toString(), sourceRef, key)).use { if (it.moveToFirst()) it.getLong(0) else null }
                val values = ContentValues().apply {
                    put("entity_type", "episode"); put("entity_id", episodeId.toString())
                    put("artwork_type", "episode_thumbnail"); put("source", "generated"); put("source_ref", sourceRef)
                    put("local_path", file.absolutePath); putNull("external_url"); put("manual", 0); put("priority", 50)
                    put("status", "ready"); put("updated_at", now); put("last_attempt_at", now); put("failure_count", 0)
                    put("artwork_key", key); put("variant", "small"); put("byte_size", file.length())
                    put("width", dims.first.takeIf { it > 0 }); put("height", dims.second.takeIf { it > 0 })
                    put("checksum", digest); put("content_type", mime(file.absolutePath)); put("last_access", now)
                    putNull("next_retry_at"); putNull("http_status"); if (existing == null) put("discovered_at", now)
                }
                val ok = if (existing == null) db.insertOrThrow("artwork", null, values) > 0L
                    else db.update("artwork", values, "id=?", arrayOf(existing.toString())) > 0
                if (ok && durationMs > 0L) {
                    val seconds = durationMs / 1000.0
                    db.execSQL("UPDATE episodes SET duration=CASE WHEN COALESCE(duration,0)<=0 AND ?>0 THEN ? ELSE duration END WHERE id=?",
                        arrayOf(seconds, seconds, episodeId))
                }
                ok
            }
            if (saved) {
                registered++
                publish("artwork_published", "episode", episodeId.toString(), "episode_thumbnail",
                    file.absolutePath, null, key, sourceRef, mime(file.absolutePath), UUID.randomUUID().toString())
            }
        }
        return registered > 0
    }

    /**
     * Hide only the exact generated episode thumbnail that failed visual validation.
     * The video and cache file are left intact; the extractor can regenerate and
     * register a replacement on this or a later Details visit.
     */
    fun invalidateGeneratedThumbnail(
        episodeId: Long,
        expectedLocalPath: String,
        reason: String,
    ): Boolean {
        val path = expectedLocalPath.trim()
        if (episodeId <= 0L || path.isEmpty()) return false
        val now = nowSeconds()
        val invalidated = runCatching {
            database.transaction { db ->
                val values = ContentValues().apply {
                    put("status", STATUS_FAILED)
                    putNull("local_path")
                    putNull("checksum")
                    put("byte_size", 0L)
                    put("updated_at", now)
                    put("last_attempt_at", now)
                }
                db.update(
                    "artwork",
                    values,
                    "entity_type='episode' AND entity_id=? AND artwork_type='episode_thumbnail' " +
                        "AND source='generated' AND local_path=?",
                    arrayOf(episodeId.toString(), path),
                ) > 0
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not invalidate generated thumbnail episodeId=$episodeId", error)
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_THUMBNAIL_INVALIDATION_FAILED",
                severity = "ERROR",
                source = "ReiAnixArtworkService",
                result = "FAILED",
                error = error,
                context = mapOf("episode_id" to episodeId, "reason" to reason),
            )
            false
        }
        if (invalidated) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_THUMBNAIL_INVALIDATED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = "REGENERATION_REQUIRED",
                context = mapOf("episode_id" to episodeId, "reason" to reason),
            )
        }
        return invalidated
    }

    /**
     * Validates all generated episode-thumbnail rows for a single episode, not only
     * the one currently winning the UI projection. Each invalid row is hidden by its
     * exact path, while valid alternatives and non-generated/manual artwork are kept.
     *
     * File decoding happens outside SQLite transactions; the transaction is opened
     * only when invalidating a path that has just failed the visual check.
     */
    fun invalidateVisuallyUnusableGeneratedThumbnails(
        episodeId: Long,
        reason: String,
    ): Int {
        if (episodeId <= 0L) return 0
        val paths: List<String> = try {
            database.read { db ->
                db.rawQuery(
                    "SELECT DISTINCT local_path FROM artwork " +
                        "WHERE entity_type='episode' AND entity_id=? " +
                        "AND artwork_type='episode_thumbnail' AND source='generated' " +
                        "AND status!='failed' AND local_path IS NOT NULL",
                    arrayOf(episodeId.toString()),
                ).use { cursor ->
                    buildList<String> {
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(0)
                            if (!path.isNullOrBlank()) add(path)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not inspect generated thumbnail rows episodeId=$episodeId", error)
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_THUMBNAIL_SCAN_FAILED",
                severity = "ERROR",
                source = "ReiAnixArtworkService",
                result = "FAILED",
                error = error,
                context = mapOf("episode_id" to episodeId, "reason" to reason),
            )
            return 0
        }
        val invalidPaths = invalidGeneratedThumbnailPaths(paths) {
            com.reiflix.reiflix_local.storage.VideoThumbnailExtractor.isUsableThumbnailFile(it)
        }
        var invalidatedCount = 0
        invalidPaths.forEach { path ->
            if (invalidateGeneratedThumbnail(episodeId, path, reason)) invalidatedCount++
        }
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_ARTWORK_THUMBNAIL_ROWS_VALIDATED",
            source = "ReiAnixArtworkService",
            result = if (invalidatedCount > 0) "INVALID_ROWS_HIDDEN" else "NO_INVALID_ROWS",
            context = mapOf(
                "episode_id" to episodeId,
                "rows_checked" to paths.distinct().size,
                "invalid_rows_hidden" to invalidatedCount,
                "reason" to reason,
            ),
        )
        return invalidatedCount
    }

    private fun publish(
        eventType: String,
        entity: String,
        id: String,
        type: String,
        path: String,
        url: String?,
        key: String,
        ref: String?,
        contentType: String?,
        requestId: String = UUID.randomUUID().toString(),
    ) {
        val sourceKind = when {
            type == "episode_thumbnail" -> "generated"
            url != null -> "anilist"
            else -> "local"
        }
        val sourceFile = File(path)
        val sourceFileExists = runCatching { sourceFile.isFile }.getOrDefault(false)
        if (!sourceFileExists) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_PUBLICATION_SOURCE_MISSING",
                severity = "ERROR",
                source = "ReiAnixArtworkService",
                result = "SOURCE_FILE_MISSING",
                requestId = requestId,
                context = mapOf(
                    "entity_type" to entity,
                    "entity_id" to id,
                    "artwork_type" to type,
                    "source_kind" to sourceKind,
                    "association_status" to "COMMITTED",
                    "source_file_exists" to false,
                ),
            )
            return
        }
        val fileIdentity = runCatching { sourceFile.canonicalPath }.getOrDefault(sourceFile.absolutePath)
        val publication = ReiAnixArtworkPublication(
            requestId = requestId,
            entityType = entity,
            entityId = id,
            artworkType = type,
            sourceKind = sourceKind,
            revision = listOf(
                key,
                fileIdentity,
                sourceFile.length().toString(),
                sourceFile.lastModified().toString(),
                checksum(sourceFile).orEmpty(),
            ).joinToString("|"),
        )
        if (!publicationSignal.publishIfCommitted(
                publication = publication,
                associationCommitted = true,
                sourceFileExists = true,
            )
        ) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_PUBLICATION_UNCHANGED",
                source = "ReiAnixArtworkService",
                result = "DUPLICATE_REVISION",
                requestId = requestId,
                context = mapOf(
                    "entity_type" to entity,
                    "entity_id" to id,
                    "artwork_type" to type,
                    "source_kind" to sourceKind,
                    "association_status" to "COMMITTED",
                ),
            )
            return
        }
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_ARTWORK_PUBLISHED",
            source = "ReiAnixArtworkService",
            result = "COMPLETED",
            requestId = requestId,
            context = mapOf(
                "entity_type" to entity,
                "entity_id" to id,
                "artwork_type" to type,
                "source_kind" to sourceKind,
                "association_status" to "COMMITTED",
                "source_file_exists" to true,
            ),
        )
        val body = JSONObject().put("event", "ARTWORK_PUBLISHED").put("entity_type", entity)
            .put("entity_id", id).put("artwork_type", type).put("cache_key", key).put("local_path", path)
            .put("source_ref", ref ?: JSONObject.NULL).put("external_url", url ?: JSONObject.NULL)
            .put("content_type", contentType ?: JSONObject.NULL).put("published_at", System.currentTimeMillis())
        val ok = runCatching {
            NativeMailbox.write(appContext, JSONObject().put("type", eventType)
                .put("requestId", requestId).put("payload", body))
        }.getOrElse { Log.w(TAG, "Artwork publication failed", it); false }
        if (!ok) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_MAILBOX_PUBLISH_FAILED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = "MAILBOX_WRITE_FAILED",
                requestId = requestId,
                context = mapOf("entity_type" to entity, "entity_id" to id, "artwork_type" to type),
            )
            Log.w(TAG, "ARTWORK_PUBLISHED event could not be queued")
        }
    }

    private fun trimCache() {
        if (!cacheDirectory.isDirectory) return
        val refs = runCatching {
            database.read { db ->
                db.rawQuery("SELECT local_path FROM artwork WHERE local_path IS NOT NULL UNION " +
                    "SELECT cover_cache FROM anime WHERE cover_cache IS NOT NULL", null).use { c ->
                    buildSet { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let {
                        add(runCatching { File(it).canonicalPath }.getOrDefault(it))
                    } }
                }
            }
        }.getOrElse { Log.w(TAG, "Skip cache cleanup because references cannot be read", it); return }
        val images = cacheDirectory.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in EXTENSIONS }
            ?.sortedBy { it.lastModified() } ?: return
        var bytes = images.sumOf { it.length() }
        images.forEach { file ->
            if (bytes <= MAX_CACHE_BYTES) return
            val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (path in refs) return@forEach
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) bytes -= size
        }
    }
    private fun removeIfUnreferenced(file: File) {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val used = runCatching { database.read { db ->
            db.rawQuery("SELECT 1 FROM artwork WHERE local_path=? UNION SELECT 1 FROM anime WHERE cover_cache=? LIMIT 1",
                arrayOf(path, path)).use { it.moveToFirst() }
        } }.getOrDefault(true)
        if (!used && file.absolutePath.startsWith(cacheDirectory.absolutePath + File.separator)) runCatching { file.delete() }
    }

    private fun validImage(path: String?): Boolean = if (path.isNullOrBlank()) false else validImage(File(path))
    private fun validImage(file: File, knownExtension: String? = null): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        val ext = knownExtension ?: header(file)?.let(::imageExtension) ?: return false
        if (ext == ".avif" && android.os.Build.VERSION.SDK_INT < 31) return true
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return false
        bitmap.recycle()
        return true
    }
    private fun imageDimensions(path: String, knownExtension: String? = null): Pair<Int, Int>? {
        val ext = knownExtension ?: header(File(path))?.let(::imageExtension) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth > 0 && options.outHeight > 0) return options.outWidth to options.outHeight
        return if (ext == ".avif" && android.os.Build.VERSION.SDK_INT < 31) 0 to 0 else null
    }
    private fun dimensions(path: String, knownExtension: String? = null) = imageDimensions(path, knownExtension)
    private fun header(file: File): ByteArray? = runCatching {
        file.inputStream().use { input -> val b = ByteArray(32); val n = input.read(b); if (n > 0) b.copyOf(n) else ByteArray(0) }
    }.getOrNull()
    private fun checksum(file: File): String? = runCatching {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val b = ByteArray(64 * 1024); while (true) {
            val n = input.read(b); if (n < 0) break; if (n > 0) d.update(b, 0, n)
        } }
        d.digest().toHex()
    }.getOrNull()
    private fun isSafeHttpUrl(s: String) = safeUrl(s)
    private fun safeUrl(s: String): Boolean {
        if (s.any { it.isWhitespace() || it.code == 0 }) return false
        val u = runCatching { URL(s) }.getOrNull() ?: return false
        return (u.protocol.equals("https", true) || u.protocol.equals("http", true)) && u.host.isNotBlank() && u.userInfo == null
    }
    private fun imageExtension(b: ByteArray): String? {
        if (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return ".jpg"
        val png = byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A)
        if (b.size >= 8 && png.indices.all { b[it] == png[it] }) return ".png"
        if (b.size >= 12 && String(b,0,4,Charsets.US_ASCII)=="RIFF" && String(b,8,4,Charsets.US_ASCII)=="WEBP") return ".webp"
        if (b.size >= 6) { val h=String(b,0,6,Charsets.US_ASCII); if (h=="GIF87a"||h=="GIF89a") return ".gif" }
        if (b.size >= 12 && String(b,4,4,Charsets.US_ASCII)=="ftyp") {
            val brand=String(b,8,4,Charsets.US_ASCII); if (brand=="avif"||brand=="avis") return ".avif"
        }
        return null
    }
    private fun sampleSize(w: Int, h: Int): Int { var n=1; while (max(w/n,h/n)>1024) n*=2; return n }
    private fun mime(path: String) = mimeFromExtension("." + File(path).extension.lowercase(Locale.ROOT))
    private fun mimeFromExtension(ext: String) = when(ext.lowercase(Locale.ROOT)) {
        ".jpg", ".jpeg" -> "image/jpeg"; ".png" -> "image/png"; ".webp" -> "image/webp"
        ".gif" -> "image/gif"; ".avif" -> "image/avif"; else -> "application/octet-stream"
    }
    private fun artworkCatalogSource(rawUrl: String): String {
        val host = runCatching { URL(rawUrl).host.lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            host == "kitsu.io" || host.endsWith(".kitsu.io") || host == "kitsu.app" || host.endsWith(".kitsu.app") -> "kitsu"
            host == "myanimelist.net" || host.endsWith(".myanimelist.net") || host.endsWith(".jikan.moe") -> "jikan"
            else -> "anilist"
        }
    }

    private fun remoteKey(aniId: Long?, url: String, type: String): String {
        val provider = artworkCatalogSource(url)
        val identity = (aniId?.toString() ?: url) + "|" + url
        return sha256(provider + "|" + identity + "|" + type + "|large")
    }
    private fun legacyCoverKey(aniId: Long?, url: String, path: String): String {
        val provider = if (aniId != null) "anilist" else "cache"
        val identity = (aniId?.toString() ?: url.ifBlank { path }) + "|" + url
        return sha256(provider + "|" + identity + "|poster|large")
    }
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun nowSeconds() = System.currentTimeMillis() / 1000.0

    private companion object {
        const val TAG = "ReiAnixArtwork"
        const val POSTER = "poster"
        const val BACKDROP = "backdrop"
        const val STATUS_READY = "ready"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_FAILED = "failed"
        const val STATUS_RETRY_WAIT = "retry_wait"
        const val STATUS_NOT_REQUESTED = "not_requested"
        const val MAX_DOWNLOAD_BYTES = 32L * 1024L * 1024L
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
        const val BACKFILL_BATCH_SIZE = 16
        const val IO_BUFFER_BYTES = 64 * 1024
        const val POSTER_PRIORITY = 400
        const val STALE_PRIORITY = 350
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    }
}
