package com.reiflix.reiflix_local.data.artwork

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.graphics.BitmapFactory
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.SafScanner
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
)

/** Candidate artwork URLs remain separate from the local identity association. */
data class ReiAnixArtworkCandidate(
    val anilistId: Long,
    val posterUrl: String?,
    val bannerUrl: String?,
    val posterFallbackUrl: String? = null,
    /** Origin of a candidate-only URL; normal AniList requests keep the default. */
    val source: String = "anilist",
    /** Explicit wide Backdrop URL; kept distinct from Banner. */
    val backdropUrl: String? = null,
) {
    init {
        require(source in setOf("anilist", "kitsu", "anipub"))
    }
}

/** Attempts ranked candidates and their variants without letting one failed URL stop the next. */
internal suspend fun firstSuccessfulArtworkCandidate(
    candidates: List<ReiAnixArtworkCandidate>,
    artworkUrls: (ReiAnixArtworkCandidate) -> List<String?>,
    attempt: suspend (ReiAnixArtworkCandidate, String) -> Boolean,
): Boolean {
    for (candidate in candidates) {
        for (rawUrl in artworkUrls(candidate)) {
            val url = rawUrl?.trim()?.takeIf(String::isNotEmpty) ?: continue
            if (attempt(candidate, url)) return true
        }
    }
    return false
}

/** Returns only URLs belonging to the requested semantic artwork type. */
internal fun artworkCandidateUrlsForType(
    candidate: ReiAnixArtworkCandidate,
    artworkType: String,
): List<String?> = when (artworkType) {
    "poster" -> listOf(candidate.posterUrl, candidate.posterFallbackUrl)
    "backdrop" -> listOf(candidate.backdropUrl)
    "banner" -> listOf(candidate.bannerUrl)
    else -> emptyList()
}

/**
 * Bounded publication signal for the existing Compose projection.
 * Only a committed association with an existing image file can be published.
 */
internal class ReiAnixArtworkPublicationSignal {
    private val mutableEvents = MutableSharedFlow<ReiAnixArtworkPublication>(
        replay = 1,
        extraBufferCapacity = 31,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<ReiAnixArtworkPublication> = mutableEvents.asSharedFlow()

    fun publishIfCommitted(
        publication: ReiAnixArtworkPublication,
        associationCommitted: Boolean,
        sourceFileExists: Boolean,
    ): Boolean {
        if (!associationCommitted || !sourceFileExists) return false
        return mutableEvents.tryEmit(publication)
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
    val seconds = ceil(((retryAtMillis.toDouble() - nowMillis.toDouble()) / 1_000.0).coerceAtLeast(0.0))
    return seconds.toLong().coerceAtMost(Long.MAX_VALUE / 1_000L)
}

internal fun artworkRetryDelaySeconds(
    failureCount: Int,
    retryable: Boolean,
    retryAfterSeconds: Long? = null,
): Long {
    val attempt = failureCount.coerceAtLeast(1)
    val exponential = if (retryable) {
        val exponent = (attempt - 1).coerceIn(0, 10)
        minOf(MAX_ARTWORK_RETRY_BACKOFF_SECONDS, BASE_ARTWORK_RETRY_BACKOFF_SECONDS * (1L shl exponent))
    } else {
        PERMANENT_ARTWORK_RETRY_DELAY_SECONDS
    }
    return max(exponential, retryAfterSeconds?.coerceAtLeast(0L) ?: 0L)
}

/** Persisted cooldown, not an ever-growing failure count, controls automatic retries. */
@Suppress("UNUSED_PARAMETER")
internal fun shouldRetryArtworkRequest(
    status: String?,
    retryAtSeconds: Double?,
    failureCount: Int,
    nowSeconds: Double,
    force: Boolean,
): Boolean {
    if (force) return true
    val normalizedStatus = status?.trim()?.lowercase(Locale.ROOT)
    if (normalizedStatus != "failed" && normalizedStatus != "retry_wait") return true
    return retryAtSeconds == null || !retryAtSeconds.isFinite() || retryAtSeconds <= nowSeconds
}

/** Native persistent artwork writer for Compose, backed by the canonical v29 SQLite. */
class ReiAnixArtworkService(context: Context, private val database: ReiAnixSqliteDatabase) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(File(appContext.filesDir, "data/covers"), "artwork")
    private val publicationSignal = ReiAnixArtworkPublicationSignal()
    internal val publishedArtworks: SharedFlow<ReiAnixArtworkPublication> = publicationSignal.events
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val retryJobs = ConcurrentHashMap<Long, Job>()
    private val forceFollowUpIds = ConcurrentHashMap.newKeySet<Long>()
    private val candidateFollowUpRequests = ConcurrentHashMap<Long, List<ReiAnixArtworkCandidate>>()
    private val permits = Semaphore(2)
    private val backfillRunning = AtomicBoolean(false)
    private val cacheClearInProgress = AtomicBoolean(false)
    @Volatile private var backfillJob: Job? = null

    private data class Anime(
        val anilistId: Long?,
        val kind: String?,
        val coverUrl: String?,
        val coverCache: String?,
        val bannerUrl: String?,
        val metadataManualFields: String?,
    )
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
    private data class LegacyCoverCacheReconstruction(
        val key: String,
        val manual: Boolean,
        val contentType: String,
    )
    private data class LocalSidecarCandidate(
        val uri: Uri,
        val name: String,
        val size: Long?,
        val modifiedAt: Long?,
        val parentIdentity: String,
    )
    private data class ExistingLocalSidecar(
        val id: Long,
        val source: String,
        val sourceRef: String?,
        val localPath: String?,
        val key: String?,
        val manual: Boolean,
    )
    private data class SavedLocalSidecar(val key: String, val oldPath: String?)

    private class HttpFailure(val code: Int, val retryAfterSeconds: Long?) : IOException("Artwork HTTP " + code)
    private class InvalidImage(message: String) : Exception(message)

    fun requestAnimeArtwork(animeId: Long, force: Boolean = false): Boolean =
        requestAnimeArtworkInternal(animeId, force, candidateCandidates = null)

    /**
     * Runs ranked candidate URLs through the existing cache, SQLite and publication
     * pipeline without assigning a guessed AniList ID or replacing metadata URLs.
     */
    fun requestAnimeArtworkCandidates(
        animeId: Long,
        candidates: List<ReiAnixArtworkCandidate>,
        force: Boolean = false,
    ): Boolean {
        val usable = candidates.asSequence()
            .filter {
                it.anilistId >= 0L &&
                    (!it.posterUrl.isNullOrBlank() || !it.posterFallbackUrl.isNullOrBlank() ||
                        !it.bannerUrl.isNullOrBlank() || !it.backdropUrl.isNullOrBlank())
            }
            .distinct()
            .take(MAX_CANDIDATE_ARTWORKS)
            .toList()
        if (usable.isEmpty()) return false
        return requestAnimeArtworkInternal(animeId, force, usable)
    }

    private fun requestAnimeArtworkInternal(
        animeId: Long,
        force: Boolean,
        candidateCandidates: List<ReiAnixArtworkCandidate>?,
    ): Boolean {
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
            if (cacheClearInProgress.get()) return false
            if (jobs[animeId]?.isActive == true) {
                if (candidateCandidates != null) {
                    candidateFollowUpRequests.compute(animeId) { _, pending ->
                        (pending.orEmpty() + candidateCandidates).distinct().take(MAX_CANDIDATE_ARTWORKS)
                    }
                    if (force) forceFollowUpIds.add(animeId)
                    return true
                }
                if (force) {
                    forceFollowUpIds.add(animeId)
                    return true
                }
                return false
            }
            retryJobs.remove(animeId)?.cancel()
            val task = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val anime = loadAnime(animeId) ?: return@launch
                    val entityType = if (anime.kind.equals("movie", true)) "movie" else "anime"
                    if (candidateCandidates == null) {
                        val posterUrl = anime.coverUrl?.trim()?.takeIf(String::isNotEmpty)
                        val bannerUrl = anime.bannerUrl?.trim()?.takeIf(String::isNotEmpty)
                        var posterMaterialized = posterUrl?.let {
                            materialize(animeId, entityType, "poster", it, anime.anilistId, force, requestId)
                        } ?: false

                        // Keep the fast path unchanged: secondary AniList URLs are only read and
                        // attempted after the primary URL is absent or could not be materialized.
                        if (!posterMaterialized) {
                            val alternatives = loadAlternativePosterCandidates(
                                animeId = animeId,
                                anime = anime,
                                primaryUrl = posterUrl,
                                force = force,
                            )
                            if (alternatives.isNotEmpty()) {
                                posterMaterialized = firstSuccessfulArtworkCandidate(
                                    candidates = alternatives,
                                    artworkUrls = { listOf(it.posterUrl) },
                                ) { candidate, url ->
                                    materialize(
                                        animeId,
                                        entityType,
                                        "poster",
                                        url,
                                        candidate.anilistId.takeIf { it > 0L },
                                        force,
                                        requestId,
                                        requireCurrentUrl = false,
                                        retryCandidates = alternatives,
                                        sourceKind = candidate.source,
                                    )
                                }
                                if (posterMaterialized) {
                                    ReiAnixDiagnosticTimeline.record(
                                        name = "NATIVE_ARTWORK_ALTERNATIVE_SOURCE_SELECTED",
                                        source = "ReiAnixArtworkService",
                                        result = "ALTERNATE_ANILIST_VARIANT_MATERIALIZED",
                                        requestId = requestId,
                                        context = mapOf(
                                            "entity_type" to entityType,
                                            "entity_id" to animeId,
                                            "artwork_type" to "poster",
                                            "candidate_count" to alternatives.size,
                                            "source_kind" to "anilist_alternative",
                                        ),
                                    )
                                }
                            }
                        }

                        // The old build also recovered pre-migration cover_cache paths and
                        // same-folder poster/cover/folder images next to persisted episodes.
                        // Run this only after all remote candidates fail and only when no valid
                        // poster is already available, so current AniList posters keep their
                        // existing fast, two-permit download path.
                        if (!posterMaterialized && !hasUsablePersistedPoster(animeId, entityType)) {
                            if (!anime.coverCache.isNullOrBlank()) {
                                restoreLegacyCoverCachePointer(
                                    animeId = animeId,
                                    entity = entityType,
                                    coverCache = anime.coverCache,
                                    requestId = requestId,
                                )
                            }
                            if (!hasUsablePersistedPoster(animeId, entityType) &&
                                !ownsManualField(anime.metadataManualFields, "cover_url") &&
                                !ownsManualField(anime.metadataManualFields, "cover_cache")
                            ) {
                                discoverAndPersistLocalSidecars(
                                    animeId = animeId,
                                    entity = entityType,
                                    requestId = requestId,
                                    artworkTypes = listOf(POSTER),
                                )
                            }
                        }
                        bannerUrl?.let {
                            materialize(animeId, entityType, BANNER, it, anime.anilistId, force, requestId)
                        }
                    } else {
                        // Candidate URL downloads run in a two-worker pool, matching the
                        // artwork service's existing global Semaphore(2). Every completed image
                        // is persisted and published inside materialize(), without waiting for
                        // slower candidates or the remaining title searches.
                        val recoveredTypes = materializeCandidateArtworkSet(
                            animeId = animeId,
                            entityType = entityType,
                            requestId = requestId,
                            candidates = candidateCandidates,
                            force = force,
                        )
                        val posterRecovered = POSTER in recoveredTypes
                        val backdropRecovered = BACKDROP in recoveredTypes
                        val bannerRecovered = BANNER in recoveredTypes
                        ReiAnixDiagnosticTimeline.record(
                            name = "NATIVE_ARTWORK_CANDIDATE_FALLBACK_FINISHED",
                            source = "ReiAnixArtworkService",
                            result = if (recoveredTypes.isNotEmpty()) "ARTWORK_RECOVERED" else "NO_CANDIDATE_ARTWORK_RECOVERED",
                            requestId = requestId,
                            context = mapOf(
                                "entity_type" to entityType,
                                "entity_id" to animeId,
                                "candidate_count" to candidateCandidates.size,
                                "poster_recovered" to posterRecovered,
                                "backdrop_recovered" to backdropRecovered,
                                "banner_recovered" to bannerRecovered,
                                "identity_associated" to false,
                            ),
                        )
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
                    val candidateFollowUp = candidateFollowUpRequests.remove(animeId)
                    val forceFollowUp = forceFollowUpIds.remove(animeId)
                    if (!cacheClearInProgress.get()) {
                        when {
                            candidateFollowUp != null -> {
                                // Preserve a forced canonical refresh alongside candidate-only lookup.
                                if (forceFollowUp) requestAnimeArtwork(animeId, force = true)
                                requestAnimeArtworkCandidates(animeId, candidateFollowUp, forceFollowUp)
                            }
                            forceFollowUp -> requestAnimeArtwork(animeId, force = true)
                            else -> schedulePersistedArtworkRetry(animeId)
                        }
                    }
                }
            }
            jobs[animeId] = task
            task.start()
        }
        return true
    }

    /**
     * Materializes every distinct semantic candidate URL concurrently, with no more than
     * two active workers. Downloads publish independently from their completion callback in
     * materialize(), so a slow Poster cannot hold back an already-downloaded Banner/Backdrop.
     */
    private suspend fun materializeCandidateArtworkSet(
        animeId: Long,
        entityType: String,
        requestId: String,
        candidates: List<ReiAnixArtworkCandidate>,
        force: Boolean,
    ): Set<String> {
        data class Work(
            val candidate: ReiAnixArtworkCandidate,
            val artworkType: String,
            val url: String,
        )

        val workItems = buildList {
            candidates.forEach { candidate ->
                listOf(POSTER, BACKDROP, BANNER).forEach { type ->
                    artworkCandidateUrlsForType(candidate, type)
                        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                        .forEach { url -> add(Work(candidate, type, url)) }
                }
            }
        }.distinctBy { it.artworkType + "\u001f" + it.url }
        if (workItems.isEmpty()) return emptySet()

        val workByKey = workItems.mapIndexed { index, work -> index.toString() to work }.toMap()
        val recovered = linkedSetOf<String>()
        runParallelArtworkSearches(
            titles = workByKey.keys.toList(),
            maxConcurrent = 2,
            search = { key ->
                val work = workByKey.getValue(key)
                materialize(
                    animeId = animeId,
                    entity = entityType,
                    type = work.artworkType,
                    url = work.url,
                    aniId = work.candidate.anilistId.takeIf { it > 0L },
                    force = force,
                    requestId = requestId,
                    requireCurrentUrl = false,
                    retryCandidates = candidates,
                    sourceKind = work.candidate.source,
                )
            },
            onCompleted = { key, result ->
                if (result.getOrNull() == true) {
                    recovered.add(workByKey.getValue(key).artworkType)
                }
            },
        )
        return recovered
    }

    /** Repair existing persisted image URLs in bounded asynchronous batches. */
    fun requestPendingArtwork(): Boolean {
        if (cacheClearInProgress.get() || !backfillRunning.compareAndSet(false, true)) return false
        backfillJob = scope.launch {
            try {
                if (cacheClearInProgress.get()) return@launch
                var afterId = 0L
                while (currentCoroutineContext().isActive && !cacheClearInProgress.get()) {
                    val ids = database.read { db ->
                        db.rawQuery(
                            "SELECT id FROM anime a WHERE id>? AND (" +
                                "TRIM(COALESCE(cover_url,''))!='' OR TRIM(COALESCE(banner_url,''))!='' OR " +
                                "EXISTS (SELECT 1 FROM episodes e WHERE e.anime_id=a.id AND COALESCE(e.missing,0)=0 " +
                                "AND TRIM(COALESCE(e.path,''))!='') OR " +
                                "EXISTS (SELECT 1 FROM artwork art WHERE art.entity_id=CAST(a.id AS TEXT) " +
                                "AND art.entity_type IN ('anime','movie') AND art.artwork_type IN ('poster','backdrop','banner') " +
                                "AND art.status IN ('failed','retry_wait') AND art.next_retry_at IS NOT NULL " +
                                "AND TRIM(COALESCE(art.external_url,''))!='')) ORDER BY id LIMIT 16",
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
        requireCurrentUrl: Boolean = true,
        retryCandidates: List<ReiAnixArtworkCandidate>? = null,
        sourceKind: String = "anilist",
    ): Boolean {
        if (!safeUrl(url)) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_SOURCE_REJECTED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = "INVALID_SOURCE",
                requestId = requestId,
                context = mapOf("entity_type" to entity, "entity_id" to id, "artwork_type" to type),
            )
            return false
        }
        if (requireCurrentUrl && !isUrlCurrent(id, type, url)) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_SOURCE_REJECTED",
                severity = "WARN",
                source = "ReiAnixArtworkService",
                result = "ASSOCIATION_CHANGED",
                requestId = requestId,
                context = mapOf("entity_type" to entity, "entity_id" to id, "artwork_type" to type),
            )
            return false
        }
        return permits.withPermit {
            currentCoroutineContext().ensureActive()
            if (reuseLocal(id, entity, type, url, aniId, requestId, requireCurrentUrl)) return@withPermit true
            val key = remoteKey(aniId, url, type)
            val rowId = prepareRow(id, entity, type, url, key, force, requireCurrentUrl, sourceKind) ?: return@withPermit false
            var image: Downloaded? = null
            try {
                val completedImage = download(key, url)
                image = completedImage
                currentCoroutineContext().ensureActive()
                val saved = persistDownloaded(rowId, id, type, url, key, completedImage)
                if (!saved.saved) {
                    removeIfUnreferenced(completedImage.file)
                    return@withPermit false
                }
                if (saved.current || !requireCurrentUrl) publish(
                    "artwork_published", entity, id.toString(), type,
                    completedImage.file.absolutePath, url, key, url, completedImage.mime, requestId,
                )
                trimCache()
                saved.current || !requireCurrentUrl
            } catch (cancelled: CancellationException) {
                setCancelled(rowId)
                image?.let { removeIfUnreferenced(it.file) }
                throw cancelled
            } catch (error: Exception) {
                val retryDelaySeconds = runCatching {
                    setFailure(
                        rowId,
                        error,
                        (error as? HttpFailure)?.code,
                        (error as? HttpFailure)?.retryAfterSeconds,
                    )
                }.onFailure { persistenceError ->
                    Log.w(TAG, "Could not persist artwork retry state for animeId=$id", persistenceError)
                }.getOrNull()
                if (retryDelaySeconds != null) scheduleArtworkRetry(id, retryDelaySeconds, retryCandidates)
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
                        "source_kind" to sourceKind,
                        "association_status" to if (requireCurrentUrl) "CHECKED_BEFORE_DOWNLOAD" else "CANDIDATE_ONLY",
                        "http_status" to ((error as? HttpFailure)?.code ?: -1),
                    ),
                )
                image?.let { removeIfUnreferenced(it.file) }
                Log.w(TAG, "ARTWORK_DOWNLOAD_FAILED entity=" + entity + ":" + id + " type=" + type, error)
                false
            }
        }
    }

    private fun reuseLocal(
        id: Long,
        entity: String,
        type: String,
        url: String,
        aniId: Long?,
        requestId: String,
        requireCurrentUrl: Boolean,
    ): Boolean {
        val exactRows = candidates(entity, id.toString(), type, url)
        // Releases before typed Banner support persisted anime.banner_url images as Backdrop.
        // Reuse that exact canonical URL instead of downloading it again after the type split.
        val legacyBannerRows = if (type == BANNER) {
            candidates(entity, id.toString(), BACKDROP, url)
        } else {
            emptyList()
        }
        val rows = exactRows + legacyBannerRows
        if (rows.any { it.manual || it.source.equals("manual", true) }) return true
        if (rows.any { it.source.equals("local", true) && validImage(it.path) }) return true
        val existing = rows.firstOrNull { it.url == url && validImage(it.path) }
        if (existing != null) {
            val path = existing.path ?: return false
            if (markReady(existing.id, id, type, url, path, requireCurrentUrl)) {
                publish("artwork_published", entity, id.toString(), type, path, url,
                    existing.key ?: remoteKey(aniId, url, type), url, existing.mime ?: mime(path), requestId)
            }
            return true
        }
        // The legacy cover pointer belongs to the canonical URL, never to a speculative
        // candidate. Candidate images are registered as independent artwork rows.
        if (type != "poster" || !requireCurrentUrl) return false
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

    private fun prepareRow(
        id: Long,
        entity: String,
        type: String,
        url: String,
        key: String,
        force: Boolean,
        requireCurrentUrl: Boolean,
        sourceKind: String = "anilist",
    ): Long? = database.transaction { db ->
            if (requireCurrentUrl && !urlCurrent(db, id, type, url)) return@transaction null
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
                    put("source", sourceKind); put("source_ref", url); put("external_url", url)
                    put("status", "downloading"); put("priority", if (requireCurrentUrl) 400 else minOf(exact.priority, 350))
                    put("updated_at", now); put("last_attempt_at", now)
                    put("failure_count", if (force) 0 else exact.failureCount)
                    putNull("next_retry_at"); putNull("http_status"); put("artwork_key", key); put("variant", "large")
                }
                return@transaction if (db.update("artwork", values, "id=?", arrayOf(exact.id.toString())) > 0) exact.id else null
            }
            if (requireCurrentUrl) {
                db.execSQL(
                    "UPDATE artwork SET priority=MIN(COALESCE(priority,350),350),updated_at=? WHERE entity_type=? AND entity_id=? " +
                        "AND artwork_type=? AND external_url IS NOT NULL AND external_url!=? AND manual=0 " +
                        "AND LOWER(COALESCE(source,'')) NOT IN ('manual','local')",
                    arrayOf(now, entity, id.toString(), type, url),
                )
            }
            val values = ContentValues().apply {
                put("entity_type", entity); put("entity_id", id.toString()); put("artwork_type", type)
                put("source", sourceKind); put("source_ref", url); put("external_url", url); put("manual", 0)
                put("priority", if (requireCurrentUrl) 400 else 350); put("status", "downloading")
                put("discovered_at", now); put("updated_at", now); put("last_attempt_at", now)
                put("failure_count", 0); put("artwork_key", key); put("variant", "large")
            }
            runCatching { db.insertOrThrow("artwork", null, values) }.getOrNull()
        }

    private suspend fun download(key: String, rawUrl: String): Downloaded {
        check(ensureArtworkCacheDirectory(cacheDirectory)) { "Artwork cache directory unavailable." }
        val url = URL(rawUrl)
        val conn = url.openConnection() as? HttpURLConnection ?: throw InvalidImage("Not an HTTP connection.")
        val tmp = File(cacheDirectory, "." + key + "-" + UUID.randomUUID() + ".tmp")
        try {
            conn.requestMethod = "GET"; conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ReiAnix/ArtworkEngine-Kotlin")
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                runCatching { conn.errorStream?.close() }
                throw HttpFailure(code, parseArtworkRetryAfterSeconds(conn.getHeaderField("Retry-After"), System.currentTimeMillis()))
            }
            if (url.protocol.equals("https", true) && !conn.url.protocol.equals("https", true)) {
                throw InvalidImage("HTTPS artwork redirected to HTTP.")
            }
            val declared = conn.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (declared.isNotEmpty() && !declared.startsWith("image/")) throw InvalidImage("Content-Type is not image.")
            if (conn.getHeaderFieldLong("Content-Length", -1L) > MAX_DOWNLOAD_BYTES) throw InvalidImage("Artwork exceeds 32 MiB.")
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
            tmp.delete(); conn.disconnect()
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

    private fun markReady(
        rowId: Long,
        id: Long,
        type: String,
        url: String,
        path: String,
        requireCurrentUrl: Boolean,
    ): Boolean = database.transaction { db ->
            if (requireCurrentUrl && !urlCurrent(db, id, type, url)) return@transaction false
            val row = artworkById(db, rowId) ?: return@transaction false
            if (row.manual || row.source.equals("manual", true) || row.source.equals("local", true)) return@transaction false
            val now = nowSeconds()
            val values = ContentValues().apply {
                put("source", "cache"); put("local_path", path); put("external_url", url); put("status", "ready")
                put("last_access", now); putNull("next_retry_at"); putNull("http_status")
                if (row.status != "ready" || row.path != path) put("updated_at", now)
            }
            db.update("artwork", values, "id=?", arrayOf(rowId.toString()))
            if (requireCurrentUrl && type == "poster") updateCoverPointer(db, id, url, path)
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
            val previousCount = db.rawQuery("SELECT failure_count FROM artwork WHERE id=?", arrayOf(rowId.toString()))
                .use { if (it.moveToFirst()) it.getInt(0).coerceAtLeast(0) else 0 }
            val count = if (previousCount == Int.MAX_VALUE) Int.MAX_VALUE else previousCount + 1
            val retryDelay = artworkRetryDelaySeconds(count, retryable, retryAfterSeconds)
            val now = nowSeconds()
            val values = ContentValues().apply {
                put("status", if (retryable) "retry_wait" else "failed")
                put("updated_at", now); put("last_attempt_at", now); put("failure_count", count)
                put("next_retry_at", now + retryDelay)
                if (httpStatus == null) putNull("http_status") else put("http_status", httpStatus)
            }
            db.update("artwork", values, "id=?", arrayOf(rowId.toString()))
            retryDelay
        }
    }

    private fun loadPersistedRetryCandidates(animeId: Long): List<ReiAnixArtworkCandidate> =
        database.read { db ->
            db.rawQuery(
                "SELECT art.artwork_type,art.external_url FROM artwork art " +
                    "JOIN anime a ON CAST(a.id AS TEXT)=art.entity_id " +
                    "WHERE a.id=? AND art.entity_type IN ('anime','movie') " +
                    "AND art.artwork_type IN ('poster','backdrop','banner') " +
                    "AND art.status IN ('failed','retry_wait') AND art.next_retry_at IS NOT NULL " +
                    "AND art.next_retry_at<=? AND TRIM(COALESCE(art.external_url,''))!='' " +
                    "AND ((art.artwork_type='poster' AND TRIM(COALESCE(art.external_url,''))!=TRIM(COALESCE(a.cover_url,''))) " +
                    "OR (art.artwork_type IN ('backdrop','banner') AND TRIM(COALESCE(art.external_url,''))!=TRIM(COALESCE(a.banner_url,'')))) " +
                    "ORDER BY art.priority DESC,art.updated_at DESC,art.id DESC LIMIT ?",
                arrayOf(animeId.toString(), nowSeconds().toString(), MAX_CANDIDATE_ARTWORKS.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val type = cursor.getString(0)
                        val url = cursor.getString(1)?.trim()?.takeIf(String::isNotEmpty) ?: continue
                        add(
                            ReiAnixArtworkCandidate(
                                anilistId = 0L,
                                posterUrl = url.takeIf { type == "poster" },
                                backdropUrl = url.takeIf { type == BACKDROP },
                                bannerUrl = url.takeIf { type == BANNER },
                            ),
                        )
                    }
                }.distinct()
            }
        }

    /** Restores canonical URL and candidate-only retries using persisted v29 timestamps. */
    private fun schedulePersistedArtworkRetry(animeId: Long) {
        if (animeId <= 0L || cacheClearInProgress.get() || retryJobs[animeId]?.isActive == true) return
        val nextRetryAt = runCatching {
            database.read { db ->
                db.rawQuery(
                    "SELECT MIN(next_retry_at) FROM artwork WHERE entity_id=? " +
                        "AND entity_type IN ('anime','movie') AND artwork_type IN ('poster','backdrop','banner') " +
                        "AND status IN ('failed','retry_wait') AND next_retry_at IS NOT NULL",
                    arrayOf(animeId.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
                }
            }
        }.onFailure { Log.w(TAG, "Could not restore persisted artwork retry for animeId=$animeId", it) }
            .getOrNull() ?: return
        if (!nextRetryAt.isFinite()) return
        val delaySeconds = ceil(nextRetryAt - nowSeconds()).toLong().coerceAtLeast(1L)
        scheduleArtworkRetry(animeId, delaySeconds, loadPersistedRetryCandidates(animeId))
    }

    private fun scheduleArtworkRetry(
        animeId: Long,
        delaySeconds: Long,
        candidates: List<ReiAnixArtworkCandidate>? = null,
    ) {
        if (animeId <= 0L || delaySeconds <= 0L || cacheClearInProgress.get()) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            delay(delaySeconds * 1000L)
            currentCoroutineContext()[Job]?.let { retryJobs.remove(animeId, it) }
            if (!cacheClearInProgress.get()) {
                requestAnimeArtwork(animeId)
                if (!candidates.isNullOrEmpty()) requestAnimeArtworkCandidates(animeId, candidates)
            }
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
        val column = when (type) {
            POSTER -> "cover_url"
            BANNER -> "banner_url"
            // Backdrop is a provider-supplied artwork type with no canonical anime URL column.
            BACKDROP -> return false
            else -> return false
        }
        return db.rawQuery("SELECT $column FROM anime WHERE id=? LIMIT 1", arrayOf(id.toString()))
            .use { it.moveToFirst() && it.getString(0)?.trim() == url }
    }
    private fun discoverAndPersistLocalSidecars(
        animeId: Long,
        entity: String,
        requestId: String,
        artworkTypes: List<String> = listOf(POSTER, BACKDROP),
    ) {
        val episodePaths = runCatching {
            database.read { db ->
                db.rawQuery(
                    "SELECT path FROM episodes WHERE anime_id=? AND COALESCE(missing,0)=0 " +
                        "AND TRIM(COALESCE(path,''))!='' ORDER BY id LIMIT ?",
                    arrayOf(animeId.toString(), MAX_LOCAL_SIDECAR_EPISODES.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
                        }
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not read persisted episode paths for local artwork animeId=$animeId", error)
            return
        }
        if (episodePaths.isEmpty()) return

        val visitedDirectories = mutableSetOf<String>()
        var examined = 0
        var persisted = 0
        for (episodePath in episodePaths) {
            for (type in artworkTypes) {
                if (examined >= MAX_LOCAL_SIDECAR_CANDIDATES) break
                val candidates = resolveLocalSidecarCandidates(episodePath, type)
                val directoryIdentity = candidates.firstOrNull()?.parentIdentity ?: continue
                if (!visitedDirectories.add("$type:$directoryIdentity")) continue
                for (candidate in candidates.take(MAX_LOCAL_SIDECARS_PER_DIRECTORY)) {
                    if (examined >= MAX_LOCAL_SIDECAR_CANDIDATES) break
                    examined++
                    if (persistLocalSidecar(animeId, entity, type, candidate, requestId)) persisted++
                }
            }
            if (examined >= MAX_LOCAL_SIDECAR_CANDIDATES) break
        }
        if (persisted > 0 || examined > 0) {
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_ARTWORK_LOCAL_SIDECAR_DISCOVERY",
                source = "ReiAnixArtworkService",
                result = if (persisted > 0) "CANDIDATES_PERSISTED" else "NO_NEW_CANDIDATES",
                requestId = requestId,
                context = mapOf(
                    "entity_type" to entity,
                    "entity_id" to animeId,
                    "episode_paths_checked" to episodePaths.size,
                    "sidecars_examined" to examined,
                    "sidecars_persisted" to persisted,
                    "source" to "existing_file_mediastore_or_authorized_saf",
                ),
            )
        }
    }

    private fun resolveLocalSidecarCandidates(
        episodeReference: String,
        artworkType: String,
    ): List<LocalSidecarCandidate> {
        val raw = episodeReference.trim()
        if (raw.isEmpty()) return emptyList()
        if (raw.startsWith("/") && !raw.startsWith("//")) {
            return resolveFileSidecars(File(raw), artworkType)
        }
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return emptyList()
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.takeIf(String::isNotBlank)?.let { resolveFileSidecars(File(it), artworkType) }
                ?: emptyList()
            "content" -> when {
                uri.authority == MediaStore.AUTHORITY -> resolveMediaStoreSidecars(uri, artworkType)
                uri.authority == "com.android.externalstorage.documents" ->
                    resolveSafSidecars(uri, artworkType)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun resolveFileSidecars(
        videoFile: File,
        artworkType: String,
    ): List<LocalSidecarCandidate> {
        val video = runCatching { videoFile.canonicalFile }.getOrNull()
            ?.takeIf { it.isFile && it.canRead() } ?: return emptyList()
        val parent = video.parentFile ?: return emptyList()
        val parentIdentity = runCatching { parent.canonicalPath }.getOrNull() ?: return emptyList()
        return runCatching { parent.listFiles()?.asSequence().orEmpty() }
            .getOrNull()
            ?.filter { file ->
                file.isFile && file.canRead() && file.length() in 1L..MAX_DOWNLOAD_BYTES &&
                    isLegacyArtworkSidecarCandidate(file.name, artworkType)
            }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.take(MAX_LOCAL_SIDECARS_PER_DIRECTORY)
            ?.map { file ->
                LocalSidecarCandidate(
                    uri = Uri.fromFile(file.canonicalFile),
                    name = file.name,
                    size = file.length(),
                    modifiedAt = file.lastModified().takeIf { it > 0L },
                    parentIdentity = "file:$parentIdentity",
                )
            }
            ?.toList()
            .orEmpty()
    }

    private fun resolveMediaStoreSidecars(
        videoUri: Uri,
        artworkType: String,
    ): List<LocalSidecarCandidate> {
        if (android.os.Build.VERSION.SDK_INT < 29 || !MediaStoreScanner.hasReadPermission(appContext)) {
            return emptyList()
        }
        val metadata = runCatching {
            appContext.contentResolver.query(
                videoUri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.VOLUME_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val volumeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                if (nameIndex < 0 || pathIndex < 0 || volumeIndex < 0) return@use null
                val relativePath = cursor.getString(pathIndex)?.takeIf(String::isNotBlank) ?: return@use null
                val volume = cursor.getString(volumeIndex)?.takeIf(String::isNotBlank)
                    ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                Triple(cursor.getString(nameIndex).orEmpty(), relativePath, volume)
            }
        }.getOrNull() ?: return emptyList()
        val (_, relativePath, volumeName) = metadata
        val collection = runCatching { MediaStore.Files.getContentUri(volumeName) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            val selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=?"
            appContext.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                selection,
                arrayOf(relativePath, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()),
                MediaStore.MediaColumns.DISPLAY_NAME + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (idIndex < 0 || nameIndex < 0) return@use emptyList<LocalSidecarCandidate>()
                buildList {
                    var inspected = 0
                    while (cursor.moveToNext() && inspected < MAX_LOCAL_SIDECAR_DIRECTORY_ENTRIES) {
                        inspected++
                        val name = cursor.getString(nameIndex)?.takeIf(String::isNotBlank) ?: continue
                        if (!isLegacyArtworkSidecarCandidate(name, artworkType)) continue
                        val id = cursor.getLong(idIndex)
                        val itemUri = ContentUris.withAppendedId(collection, id)
                        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0L } else null
                        val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex).takeIf { it > 0L } else null
                        add(
                            LocalSidecarCandidate(
                                uri = itemUri,
                                name = name,
                                size = size,
                                modifiedAt = modified,
                                parentIdentity = "mediastore:$volumeName:$relativePath",
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse { error ->
            Log.w(TAG, "Could not list MediaStore artwork sidecars", error)
            emptyList()
        }
    }

    private fun resolveSafSidecars(
        videoUri: Uri,
        artworkType: String,
    ): List<LocalSidecarCandidate> {
        if (!DocumentsContract.isDocumentUri(appContext, videoUri)) return emptyList()
        val documentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: return emptyList()
        // Only the ExternalStorageProvider has a documented path-shaped ID. For other providers
        // parentage cannot safely be guessed; those trees are skipped rather than traversed blindly.
        val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentId.isBlank()) return emptyList()
        val tree = appContext.contentResolver.persistedUriPermissions.firstOrNull { permission ->
            permission.isReadPermission &&
                permission.uri.authority.equals(videoUri.authority, ignoreCase = true) &&
                runCatching {
                    SafScanner.isAuthorizedDocumentForTree(appContext, permission.uri, videoUri)
                }.getOrDefault(false)
        }?.uri ?: return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        }.getOrNull() ?: return emptyList()
        return runCatching {
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) return@use emptyList<LocalSidecarCandidate>()
                buildList {
                    var inspected = 0
                    while (cursor.moveToNext() && inspected < MAX_LOCAL_SIDECAR_DIRECTORY_ENTRIES) {
                        inspected++
                        val name = cursor.getString(nameIndex)?.takeIf(String::isNotBlank) ?: continue
                        if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR ||
                            !isLegacyArtworkSidecarCandidate(name, artworkType)
                        ) continue
                        val id = cursor.getString(idIndex)?.takeIf(String::isNotBlank) ?: continue
                        val itemUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }
                            .getOrNull() ?: continue
                        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0L } else null
                        val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex).takeIf { it > 0L } else null
                        add(
                            LocalSidecarCandidate(
                                uri = itemUri,
                                name = name,
                                size = size,
                                modifiedAt = modified,
                                parentIdentity = "saf:$tree:$parentId",
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Could not list authorized SAF artwork sidecars", error)
            emptyList()
        }
    }

    private fun persistLocalSidecar(
        animeId: Long,
        entity: String,
        artworkType: String,
        candidate: LocalSidecarCandidate,
        requestId: String,
    ): Boolean {
        val sourceRef = candidate.uri.toString()
        val key = sha256(
            "reianix|local-sidecar|$entity:$animeId|$artworkType|$sourceRef|" +
                "${candidate.size ?: -1L}|${candidate.modifiedAt ?: -1L}",
        )
        val existingBeforeCopy = findLocalSidecar(entity, animeId, artworkType, sourceRef, key)
        if (existingBeforeCopy?.manual == true || existingBeforeCopy?.source.equals("manual", true)) return false
        if (existingBeforeCopy?.key == key && validImage(existingBeforeCopy.localPath)) return false

        if (!ensureArtworkCacheDirectory(cacheDirectory)) return false
        val temporary = File(cacheDirectory, ".sidecar-${UUID.randomUUID()}.tmp")
        var copiedBytes = 0L
        try {
            val stream = openSidecarInputStream(candidate.uri)
                ?: throw IOException("Local artwork sidecar is no longer readable.")
            stream.buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(IO_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copiedBytes += read
                        if (copiedBytes > MAX_DOWNLOAD_BYTES) throw IOException("Local artwork sidecar exceeds 32 MiB.")
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (error: Exception) {
            runCatching { temporary.delete() }
            if (error is CancellationException) throw error
            Log.w(TAG, "Could not copy an authorized local artwork sidecar", error)
            return false
        }
        if (copiedBytes <= 0L || !validImage(temporary)) {
            runCatching { temporary.delete() }
            return false
        }
        val imageExt = header(temporary)?.let(::imageExtension) ?: run {
            runCatching { temporary.delete() }
            return false
        }
        val digest = checksum(temporary) ?: run {
            runCatching { temporary.delete() }
            return false
        }
        val dimensions = dimensions(temporary.absolutePath, imageExt)?.takeIf {
            it.first > 0 && it.second > 0
        } ?: run {
            runCatching { temporary.delete() }
            return false
        }
        var target = File(cacheDirectory, "sidecar-${key.take(20)}-${digest.take(16)}$imageExt")
        if (target.exists() && !validImage(target, imageExt)) {
            target = File(cacheDirectory, "sidecar-${key.take(20)}-${digest.take(16)}-${UUID.randomUUID()}$imageExt")
        }
        if (target.exists() && validImage(target, imageExt)) {
            runCatching { temporary.delete() }
        } else if (!temporary.renameTo(target)) {
            runCatching { temporary.delete() }
            return false
        }
        if (!validImage(target, imageExt)) {
            removeIfUnreferenced(target)
            return false
        }

        val result = runCatching {
            database.transaction { db ->
                val existing = findLocalSidecar(db, entity, animeId, artworkType, sourceRef, key)
                if (existing?.manual == true || existing?.source.equals("manual", true)) {
                    return@transaction null
                }
                if (existing != null && existing.sourceRef != sourceRef) {
                    // A key hit cannot authorize replacing a row bound to a different origin URI.
                    return@transaction null
                }
                val targetId = existing?.id
                val oldPath = existing?.localPath
                val now = nowSeconds()
                val values = ContentValues().apply {
                    put("entity_type", entity)
                    put("entity_id", animeId.toString())
                    put("artwork_type", artworkType)
                    put("source", "local")
                    put("source_ref", sourceRef)
                    put("local_path", target.absolutePath)
                    putNull("external_url")
                    put("manual", 0)
                    put("priority", 300)
                    put("status", "ready")
                    put("updated_at", now)
                    putNull("last_attempt_at")
                    put("failure_count", 0)
                    put("artwork_key", key)
                    put("variant", "large")
                    put("byte_size", copiedBytes)
                    put("width", dimensions.first.takeIf { it > 0 })
                    put("height", dimensions.second.takeIf { it > 0 })
                    put("checksum", digest)
                    put("content_type", mimeFromExtension(imageExt))
                    put("last_access", now)
                    putNull("next_retry_at")
                    putNull("http_status")
                    if (targetId == null) put("discovered_at", now)
                }
                val saved = if (targetId != null) {
                    db.update("artwork", values, "id=?", arrayOf(targetId.toString())) == 1
                } else {
                    db.insertOrThrow("artwork", null, values) > 0L
                }
                if (!saved) return@transaction null
                SavedLocalSidecar(key = key, oldPath = oldPath)
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not persist local artwork sidecar animeId=$animeId", error)
            null
        }
        if (result == null) {
            removeIfUnreferenced(target)
            return false
        }
        publish(
            "artwork_published",
            entity,
            animeId.toString(),
            artworkType,
            target.absolutePath,
            url = null,
            key = result.key,
            ref = sourceRef,
            contentType = mimeFromExtension(imageExt),
            requestId = requestId,
        )
        result.oldPath?.takeIf { it != target.absolutePath }?.let { removeIfUnreferenced(File(it)) }
        return true
    }

    private fun findLocalSidecar(
        entity: String,
        animeId: Long,
        artworkType: String,
        sourceRef: String,
        key: String,
    ): ExistingLocalSidecar? = database.read { db ->
        findLocalSidecar(db, entity, animeId, artworkType, sourceRef, key)
    }

    private fun findLocalSidecar(
        db: SQLiteDatabase,
        entity: String,
        animeId: Long,
        artworkType: String,
        sourceRef: String,
        key: String,
    ): ExistingLocalSidecar? {
        val row = db.rawQuery(
            "SELECT id, source, source_ref, local_path, artwork_key, manual FROM artwork WHERE entity_type IN ('anime','movie') " +
                "AND entity_id=? AND artwork_type=? AND (source_ref=? OR artwork_key=?) " +
                "ORDER BY manual DESC, priority DESC, updated_at DESC, id DESC LIMIT 1",
            arrayOf(animeId.toString(), artworkType, sourceRef, key),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else ExistingLocalSidecar(
                id = cursor.getLong(0),
                source = cursor.getString(1).orEmpty(),
                sourceRef = cursor.getString(2),
                localPath = cursor.getString(3),
                key = cursor.getString(4),
                manual = !cursor.isNull(5) && cursor.getInt(5) != 0,
            )
        }
        if (row != null && (row.source.equals("manual", true) || row.manual)) return row
        // Existing non-manual rows can be refreshed only when their identity query matched
        // this exact source URI. The transactional writer rechecks this before updating.
        return row?.takeIf { it.sourceRef == sourceRef }
    }

    private fun openSidecarInputStream(uri: Uri): InputStream? = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> uri.path?.takeIf(String::isNotBlank)?.let { path ->
            runCatching { File(path).inputStream() }.getOrNull()
        }
        "content" -> runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
        else -> null
    }

    /**
     * Restore a valid pre-migration cover_cache path into the existing artwork table when its
     * auxiliary artwork row was lost. The path is bound to the local anime only: without a URL
     * revision, it must not be labelled as a materialization of the current remote URL.
     */
    private fun restoreLegacyCoverCachePointer(
        animeId: Long,
        entity: String,
        coverCache: String?,
        requestId: String,
    ) {
        val path = coverCache?.trim()?.takeIf(String::isNotEmpty) ?: return
        val file = File(path)
        if (!validImage(file)) return
        val dims = dimensions(path) ?: return
        // On APIs without an AVIF decoder, the existing cache policy may accept a signature
        // without decoding; recovery must be stricter because it is rebuilding trusted identity.
        if (dims.first <= 0 || dims.second <= 0) return
        val digest = checksum(file) ?: return
        val imageExtension = header(file)?.let(::imageExtension) ?: return
        val contentType = mimeFromExtension(imageExtension)
        val now = nowSeconds()

        val restored = runCatching {
            database.transaction { db ->
                val current = db.rawQuery(
                    "SELECT cover_cache, cover_url, metadata_manual_fields FROM anime WHERE id=? LIMIT 1",
                    arrayOf(animeId.toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else Triple(cursor.getString(0)?.trim(), cursor.getString(1)?.trim(), cursor.getString(2))
                } ?: return@transaction null
                val pointerStillMatchesAnime = current.first == path

                val pathRows = db.rawQuery(
                    "SELECT * FROM artwork WHERE entity_type IN ('anime','movie') AND entity_id=? " +
                        "AND artwork_type='poster' AND local_path=? ORDER BY manual DESC, priority DESC, updated_at DESC, id DESC",
                    arrayOf(animeId.toString(), path),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.toArtwork()) }
                }

                // Manual/local rows are already authoritative. Never reclassify or replace them.
                if (pathRows.any {
                        it.manual || it.source.equals("manual", true) || it.source.equals("local", true)
                    }
                ) return@transaction null

                // Preserve a row that already proves this exact local path belongs to the current
                // URL. Normal reuseLocal() will revalidate and repair its status if necessary.
                val currentCoverUrl = validReiAnixArtworkHttpUrl(current.second)
                if (currentCoverUrl != null && pathRows.any { it.url == currentCoverUrl }) {
                    return@transaction null
                }

                val ownerConflict = db.rawQuery(
                    "SELECT 1 FROM anime WHERE id<>? AND TRIM(COALESCE(cover_cache,''))=? LIMIT 1",
                    arrayOf(animeId.toString(), path),
                ).use { it.moveToFirst() } || db.rawQuery(
                    "SELECT 1 FROM artwork WHERE local_path=? " +
                        "AND NOT (entity_type IN ('anime','movie') AND entity_id=? AND artwork_type='poster') LIMIT 1",
                    arrayOf(path, animeId.toString()),
                ).use { it.moveToFirst() }

                if (!shouldReconstructLegacyCoverCache(
                        pointerStillMatchesAnime = pointerStillMatchesAnime,
                        imageIsValid = true,
                        hasConflictingOwner = ownerConflict,
                    )
                ) return@transaction null

                // Only migrate rows whose prior role was an automatic remote/cache candidate.
                val prior = pathRows.firstOrNull {
                    !it.manual && (
                        it.source.equals("cache", true) || it.source.equals("anilist", true)
                    )
                }
                if (pathRows.isNotEmpty() && prior == null) return@transaction null

                val manualCacheOwned = ownsManualField(current.third, "cover_cache")
                // A manually selected remote URL must not make an unrelated legacy cache path
                // look like a manual cover; let that URL materialize on its own.
                if (ownsManualField(current.third, "cover_url") && !manualCacheOwned) {
                    return@transaction null
                }
                val manualOwned = manualCacheOwned
                val key = sha256("reianix|$entity:$animeId|legacy-cover-cache|$path|poster")
                val alreadyRestored = db.rawQuery(
                    "SELECT 1 FROM artwork WHERE entity_type=? AND entity_id=? AND artwork_type='poster' " +
                        "AND source=? AND local_path=? AND external_url IS NULL AND manual=? " +
                        "AND status='ready' AND variant='legacy_cache_pointer' AND checksum=? " +
                        "AND artwork_key=? LIMIT 1",
                    arrayOf(
                        entity,
                        animeId.toString(),
                        if (manualOwned) "manual" else "cache",
                        path,
                        if (manualOwned) "1" else "0",
                        digest,
                        key,
                    ),
                ).use { it.moveToFirst() }
                if (alreadyRestored) return@transaction null
                // Reuse an existing row for this path where possible, so migration never
                // manufactures duplicate artwork rows for one legacy pointer.
                val existingKeyRow = db.rawQuery(
                    "SELECT id, local_path FROM artwork WHERE entity_type=? AND entity_id=? " +
                        "AND artwork_type='poster' AND artwork_key=? LIMIT 1",
                    arrayOf(entity, animeId.toString(), key),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
                }
                if (existingKeyRow != null && existingKeyRow.second?.trim() != path) {
                    return@transaction null
                }
                val targetId = prior?.takeIf { it.url.isNullOrBlank() }?.id ?: existingKeyRow?.first
                val values = ContentValues().apply {
                    put("entity_type", entity)
                    put("entity_id", animeId.toString())
                    put("artwork_type", "poster")
                    put("source", if (manualOwned) "manual" else "cache")
                    putNull("source_ref")
                    put("local_path", path)
                    putNull("external_url")
                    put("manual", if (manualOwned) 1 else 0)
                    put("priority", if (manualOwned) 1000 else 350)
                    put("status", "ready")
                    put("updated_at", now)
                    putNull("last_attempt_at")
                    put("failure_count", 0)
                    putNull("next_retry_at")
                    putNull("http_status")
                    put("artwork_key", key)
                    put("variant", "legacy_cache_pointer")
                    put("byte_size", file.length())
                    put("width", dims.first.takeIf { it > 0 })
                    put("height", dims.second.takeIf { it > 0 })
                    put("checksum", digest)
                    put("content_type", contentType)
                    put("last_access", now)
                    if (targetId == null) put("discovered_at", now)
                }

                val saved = if (targetId != null) {
                    db.update("artwork", values, "id=?", arrayOf(targetId.toString())) == 1
                } else {
                    db.insertOrThrow("artwork", null, values) > 0L
                }
                if (!saved) return@transaction null
                LegacyCoverCacheReconstruction(
                    key = key,
                    manual = manualOwned,
                    contentType = contentType,
                )
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not restore legacy artwork cache association animeId=$animeId", error)
            null
        } ?: return

        publish(
            "artwork_published",
            entity,
            animeId.toString(),
            "poster",
            path,
            url = null,
            key = restored.key,
            ref = null,
            contentType = restored.contentType,
            requestId = requestId,
        )
        ReiAnixDiagnosticTimeline.record(
            name = "NATIVE_ARTWORK_LEGACY_CACHE_RECONSTRUCTED",
            source = "ReiAnixArtworkService",
            result = if (restored.manual) "MANUAL_POINTER_RESTORED" else "UNBOUND_LOCAL_POINTER_RESTORED",
            requestId = requestId,
            context = mapOf(
                "entity_type" to entity,
                "entity_id" to animeId,
                "artwork_type" to "poster",
                "source_kind" to "legacy_cover_cache",
                "association_status" to "COMMITTED",
            ),
        )
    }

    private fun loadAnime(id: Long): Anime? = database.read { db ->
        db.rawQuery(
            "SELECT anilist_id,media_kind,cover_url,cover_cache,banner_url,metadata_manual_fields FROM anime WHERE id=? LIMIT 1",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val index = c.getColumnIndexOrThrow("anilist_id")
            Anime(
                anilistId = if (c.isNull(index)) null else c.getLong(index),
                kind = c.getString(1),
                coverUrl = c.getString(2),
                coverCache = c.getString(3),
                bannerUrl = c.getString(4),
                metadataManualFields = c.getString(5),
            )
        }
    }

    /**
     * Read persisted secondary AniList poster variants. The caller reaches this helper only
     * after the primary URL failed or was absent, so successful primary downloads retain the
     * existing single-request fast path.
     */
    private suspend fun loadAlternativePosterCandidates(
        animeId: Long,
        anime: Anime,
        primaryUrl: String?,
        force: Boolean,
    ): List<ReiAnixArtworkCandidate> {
        val aniListId = anime.anilistId?.takeIf { it > 0L } ?: return emptyList()
        if (ownsManualField(anime.metadataManualFields, "cover_url") ||
            ownsManualField(anime.metadataManualFields, "cover_cache")
        ) return emptyList()

        val now = nowSeconds()
        return database.read { db ->
            val currentPrimary = validReiAnixArtworkHttpUrl(primaryUrl)
            val primaryStatus = currentPrimary?.let { url ->
                db.rawQuery(
                    "SELECT status FROM artwork WHERE entity_type IN ('anime','movie') AND entity_id=? " +
                        "AND artwork_type='poster' AND external_url=? ORDER BY priority DESC,updated_at DESC,id DESC LIMIT 1",
                    arrayOf(animeId.toString(), url),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }
            db.rawQuery(
                "SELECT external_url,status,next_retry_at,failure_count,variant,manual,source,artwork_key FROM artwork " +
                    "WHERE entity_type IN ('anime','movie') AND entity_id=? AND artwork_type='poster' " +
                    "AND LOWER(COALESCE(source,'')) IN ('anilist','cache') AND COALESCE(manual,0)=0 " +
                    "AND external_url IS NOT NULL AND TRIM(external_url)!='' " +
                    "AND external_url!=COALESCE(?, '') AND LOWER(COALESCE(variant,''))!='obsolete' " +
                    "AND LOWER(COALESCE(status,'')) IN ('source_candidate','not_requested','pending','ready','failed','retry_wait') " +
                    "ORDER BY CASE LOWER(COALESCE(status,'')) WHEN 'source_candidate' THEN 0 WHEN 'ready' THEN 1 ELSE 2 END, " +
                    "priority DESC,CASE LOWER(COALESCE(variant,'')) WHEN 'large' THEN 0 WHEN 'extralarge' THEN 1 ELSE 2 END," +
                    "updated_at DESC,id DESC",
                arrayOf(animeId.toString(), primaryUrl.orEmpty()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val url = validReiAnixArtworkHttpUrl(cursor.getString(0)) ?: continue
                        val status = cursor.getString(1)
                        val retryAt = if (cursor.isNull(2)) null else cursor.getDouble(2)
                        val failures = cursor.getInt(3)
                        val variant = cursor.getString(4)
                        val manual = cursor.getInt(5) != 0
                        val source = cursor.getString(6)
                        val key = cursor.getString(7)
                        if (variant.equals("obsolete", ignoreCase = true)) continue
                        if (key != aniListArtworkCandidateKey(animeId, aniListId, url)) continue
                        if (!canMaterializeAniListFallbackCandidate(
                                primaryUrl = primaryUrl,
                                primaryStatus = primaryStatus,
                                candidateUrl = url,
                                candidateStatus = status,
                                artworkIsUserOwned = manual ||
                                    source.equals("manual", true) || source.equals("local", true),
                            )
                        ) continue
                        val normalizedStatus = status?.lowercase(Locale.ROOT)
                        if (!force && normalizedStatus in setOf("failed", "retry_wait") &&
                            !shouldRetryArtworkRequest(status, retryAt, failures, now, force = false)
                        ) continue
                        add(
                            ReiAnixArtworkCandidate(
                                anilistId = aniListId,
                                posterUrl = url,
                                bannerUrl = null,
                                source = "anilist",
                            ),
                        )
                    }
                }.distinct()
            }
        }
    }

    private fun hasUsablePersistedPoster(animeId: Long, entityType: String): Boolean {
        val paths = database.read { db ->
            db.rawQuery(
                "SELECT local_path FROM artwork WHERE entity_type=? AND entity_id=? " +
                    "AND artwork_type='poster' AND status='ready' AND local_path IS NOT NULL " +
                    "AND TRIM(local_path)!='' ORDER BY manual DESC,priority DESC,updated_at DESC,id DESC",
                arrayOf(entityType, animeId.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
                    }
                }
            }
        }
        return paths.any(::validImage)
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
        val sourceFileExists = runCatching { File(path).isFile }.getOrDefault(false)
        val publication = ReiAnixArtworkPublication(
            requestId = requestId,
            entityType = entity,
            entityId = id,
            artworkType = type,
            sourceKind = sourceKind,
        )
        if (!publicationSignal.publishIfCommitted(
                publication = publication,
                associationCommitted = true,
                sourceFileExists = sourceFileExists,
            )
        ) {
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
    private fun remoteKey(aniId: Long?, url: String, type: String): String {
        val provider = if (aniId != null) "anilist" else "url"
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
        const val BANNER = "banner"
        const val STATUS_READY = "ready"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_FAILED = "failed"
        const val STATUS_RETRY_WAIT = "retry_wait"
        const val STATUS_NOT_REQUESTED = "not_requested"
        const val MAX_DOWNLOAD_BYTES = 32L * 1024L * 1024L
        const val MAX_CANDIDATE_ARTWORKS = 5
        const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
        const val BACKFILL_BATCH_SIZE = 16
        const val IO_BUFFER_BYTES = 64 * 1024
        const val POSTER_PRIORITY = 400
        const val STALE_PRIORITY = 350
        const val BASE_RETRY_BACKOFF_SECONDS = 60L
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    }
}
