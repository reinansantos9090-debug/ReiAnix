package com.reianix.reianix_local.data.personal

import android.util.Log
import com.reianix.reianix_local.data.anilist.AniListError
import com.reianix.reianix_local.data.anilist.AniListResult
import com.reianix.reianix_local.data.anilist.ReiAnixAniListClient
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogIdentityMatcher
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogIdentity
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogIdentityDecision
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogAnime
import com.reianix.reianix_local.data.catalog.ReiAnixAniMapCatalogIdResolver
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogResult
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogError
import com.reianix.reianix_local.data.catalog.ReiAnixKitsuCharacterPage
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogSource
import com.reianix.reianix_local.data.catalog.ReiAnixExternalCatalogIds
import com.reianix.reianix_local.data.catalog.ReiAnixKitsuCatalogProvider
import com.reianix.reianix_local.data.sqlite.ReiAnixAnimeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import com.reianix.reianix_local.data.sqlite.getExternalCatalogIds
import com.reianix.reianix_local.data.sqlite.toExternalCatalogIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Repairs missing/partial character casts independently of ALL canonical
 * metadata and artwork transactions. Metadata responses still supply the
 * first characters immediately; only missing pages / previously imported
 * anime use this deferred lane.
 *
 * One worker, no concurrent image requests, and strict source-ID verification
 * avoid stealing bandwidth from posters or attaching a wrong anime's cast.
 */
class ReiAnixCharacterCompletionService(
    private val store: ReiAnixMetadataCharactersStore,
    private val library: ReiAnixSqliteLibraryRepository,
    private val database: ReiAnixSqliteDatabase,
    private val settings: ReiAnixSqliteSettingsRepository,
    private val aniList: ReiAnixAniListClient,
    private val kitsu: ReiAnixKitsuCatalogProvider = ReiAnixKitsuCatalogProvider(),
    private val aniMap: ReiAnixAniMapCatalogIdResolver = ReiAnixAniMapCatalogIdResolver(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val priority = Channel<Long>(Channel.UNLIMITED)
    private val backlog = Channel<Long>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val priorityPromoted = ConcurrentHashMap.newKeySet<Long>()
    private val startupScheduled = AtomicBoolean(false)

    init {
        scope.launch {
            // Newly resolved metadata can enqueue characters immediately; the
            // worker yields to active poster/backdrop transfers before any HTTP.
            while (isActive) {
                val id = priority.tryReceive().getOrNull()
                    ?: backlog.tryReceive().getOrNull()
                    ?: select {
                        priority.onReceive { it }
                        backlog.onReceive { it }
                    }
                // An item promoted out of the backlog may still have an old
                // backlog token. Consume its work once, never twice.
                if (!pending.remove(id)) continue
                priorityPromoted.remove(id)
                try {
                    completeAnime(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not complete character metadata for anime=$id", error)
                } finally {
                    priorityPromoted.remove(id)
                }
            }
        }
    }

    /** A new confirmed metadata association uses this non-blocking notification. */
    fun schedule(animeId: Long, prioritize: Boolean = false) {
        if (animeId <= 0L) return
        val fresh = pending.add(animeId)
        if (!fresh && !prioritize) return
        if (!fresh && !priorityPromoted.add(animeId)) return
        val queued = if (prioritize) priority.trySend(animeId) else backlog.trySend(animeId)
        if (queued.isFailure) {
            if (fresh) pending.remove(animeId)
            priorityPromoted.remove(animeId)
        }
    }

    /** Old anime with full metadata but NO cast must be included in backfill. */
    fun scheduleStartupBackfill() {
        if (!startupScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(25_000L)
            enqueueCatalogIds()
        }
    }

    /** New anime inserted by a later scan must not be skipped. */
    fun scheduleAfterScan() {
        scope.launch {
            delay(15_000L)
            enqueueCatalogIds()
        }
    }

    private suspend fun enqueueCatalogIds() {
        try {
            val ids = database.read { db ->
                db.rawQuery("SELECT id FROM anime ORDER BY id DESC", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }
            }
            for (id in ids) {
                if (!scope.coroutineContext.isActive) break
                schedule(id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Character backfill postponed", error)
        }
    }

    private suspend fun completeAnime(animeId: Long) {
        store.restore(animeId)
        val local = library.getAnime(animeId) ?: return
        val ids = runCatching { library.getExternalCatalogIds(animeId) }.getOrNull()
            ?: ReiAnixExternalCatalogIds(
                aniListId = local.anilistId?.takeIf { it > 0L },
            )
        val associatedAniList = ids.aniListId?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val anilistAllowed = settings.getPreference("metadata.anilist_enabled")?.value
            ?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes") } ?: true

        if (associatedAniList != null && anilistAllowed) {
            val current = store.entries.value[animeId]
            if (!(current?.source == "anilist" &&
                current.sourceId == associatedAniList.toString() && current.complete)
            ) loadAniListPages(animeId, associatedAniList)
        }

        // Kitsu is a fallback if AniList has no registered characters or is
        // unavailable. Do not replace an existing, non-empty AniList cast.
        val current = store.entries.value[animeId]
        if (current?.characters?.isNotEmpty() == true && current.complete) return
        val kitsuId = ids.kitsuId?.takeIf(String::isNotBlank)
            ?: current?.takeIf { it.source == "kitsu" }?.sourceId
            ?: resolveCrosswalkKitsuId(animeId, local, ids)
        if (kitsuId != null) {
            if (current?.source == "kitsu" && current.sourceId == kitsuId &&
                current.complete && current.characters.isNotEmpty()
            ) return
            loadKitsuPages(animeId, kitsuId)
        } else if (current?.characters.isNullOrEmpty()) {
            // Some pre-existing catalog data is candidate-only. An exact
            // verified Kitsu cover can anchor a cast even when year/format
            // were deliberately excluded from identity matching.
            tryVerifiedKitsuSearch(animeId, local, ids)
        }
    }

    private suspend fun loadAniListPages(animeId: Long, anilistId: Int) {
        var page = 1
        while (scope.coroutineContext.isActive && page <= MAX_PAGES) {
            awaitQuietArtwork()
            val result = requestAniListPage(anilistId, page)
            if (result !is AniListResult.Success) return
            val info = result.value
            store.publish(
                animeId, "anilist", anilistId.toString(), info.characters,
                complete = !info.hasNextPage,
            )
            if (!info.hasNextPage) return
            page++
            delay(BETWEEN_PAGES_MS)
        }
    }

    private suspend fun requestAniListPage(
        anilistId: Int,
        page: Int,
    ): AniListResult<com.reianix.reianix_local.data.anilist.ReiAnixCharacterPage> {
        repeat(3) { attempt ->
            when (val result = aniList.getCharacterPage(anilistId, page)) {
                is AniListResult.Success -> return result
                is AniListResult.Failure -> {
                    if (attempt == 2) return result
                    val seconds = (result.error as? AniListError.RateLimited)
                        ?.retryAfterSeconds?.coerceIn(5L, 90L)
                        ?: (5L shl attempt)
                    delay(seconds * 1_000L)
                }
            }
        }
        return AniListResult.Failure(AniListError.InvalidResponse("Character pages exhausted"))
    }

    private suspend fun loadKitsuPages(animeId: Long, kitsuId: String) {
        var page = 0
        while (scope.coroutineContext.isActive && page < MAX_PAGES) {
            val result = requestKitsuPage(kitsuId, page * KITSU_PAGE_SIZE)
            if (result !is ReiAnixCatalogResult.Success) {
                Log.w(TAG, "Kitsu character page failed: anime=$animeId page=$page " +
                    "error=" + (result as ReiAnixCatalogResult.Failure).error)
                return // Keep incomplete cache so another session can retry.
            }
            val info = result.value
            store.publish(animeId, "kitsu", kitsuId, info.characters, !info.hasNextPage)
            if (!info.hasNextPage) return
            page++
            delay(BETWEEN_PAGES_MS)
        }
    }

    private suspend fun requestKitsuPage(
        kitsuId: String, offset: Int,
    ): ReiAnixCatalogResult<ReiAnixKitsuCharacterPage> {
        var last: ReiAnixCatalogResult<ReiAnixKitsuCharacterPage> =
            ReiAnixCatalogResult.Failure(ReiAnixCatalogError.Unavailable)
        repeat(3) { attempt ->
            awaitQuietArtwork()
            val response = kitsu.getCharacterPage(kitsuId, offset, KITSU_PAGE_SIZE)
            if (response is ReiAnixCatalogResult.Success) return response
            last = response
            val error = (response as ReiAnixCatalogResult.Failure).error
            if (error is ReiAnixCatalogError.InvalidRequest ||
                error is ReiAnixCatalogError.InvalidResponse
            ) return response
            if (attempt < 2) {
                val pause = (error as? ReiAnixCatalogError.RateLimited)
                    ?.retryAfterMillis?.coerceIn(2_000L, 60_000L)
                    ?: (2_000L * (attempt + 1))
                delay(pause)
            }
        }
        return last
    }

    private suspend fun resolveCrosswalkKitsuId(
        animeId: Long,
        local: ReiAnixAnimeRecord,
        known: ReiAnixExternalCatalogIds,
    ): String? {
        if (!known.hasAnyId) return null
        awaitQuietArtwork()
        val response = aniMap.resolveIds(known)
        if (response !is ReiAnixCatalogResult.Success) return null
        val mapping = response.value ?: return null
        // AniMap must contain an already-verified ID and match this exact
        // local anime. No title-only association is accepted here.
        return ReiAnixCatalogIdentityMatcher.evaluateMapping(
            animeId, local.toExternalCatalogIdentity(), mapping, known,
        ).value?.externalIds?.kitsuId
    }

    private suspend fun tryVerifiedKitsuSearch(
        animeId: Long,
        local: ReiAnixAnimeRecord,
        known: ReiAnixExternalCatalogIds,
    ) {
        val identity = local.toExternalCatalogIdentity()
        val readyKitsuPosters = database.read { db ->
            db.rawQuery(
                "SELECT DISTINCT external_url FROM artwork " +
                    "WHERE entity_id=? AND entity_type IN ('anime','movie') " +
                    "AND artwork_type='poster' AND LOWER(source)='kitsu' " +
                    "AND status='ready' AND TRIM(COALESCE(local_path,''))!='' " +
                    "AND TRIM(COALESCE(external_url,''))!=''",
                arrayOf(animeId.toString()),
            ).use { cursor ->
                buildSet<String> {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let(::add)
                    }
                }
            }
        }

        // A ready poster from Kitsu's own CDN encodes the provider anime ID.
        // Verify that lightweight anime record against BOTH the poster URL
        // and the local title before loading its character relationship.
        // This bypasses slow/ambiguous text search for previously imported
        // anime with complete or provisional editorial metadata.
        val embeddedKitsuId = readyKitsuPosters
            .mapNotNull(::kitsuAnimeIdFromPosterUrl).distinct().singleOrNull()
        if (embeddedKitsuId != null) {
            awaitQuietArtwork()
            val detail = kitsu.getAnime(embeddedKitsuId)
            val remote = (detail as? ReiAnixCatalogResult.Success)?.value
            if (remote != null) {
                val verified = selectKitsuCastCandidate(
                    animeId, identity, listOf(remote), known, readyKitsuPosters,
                )
                if (verified != null) {
                    loadKitsuPages(animeId, embeddedKitsuId)
                    return
                }
            }
        }

        // Previously this used searchAnimeWithCharacters for up to ten
        // candidates, potentially transferring hundreds of included people
        // and timing out before ANY cast was published. Search metadata
        // without included relations, then fetch the selected ID's small
        // character pages. All of this is outside poster/metadata loading.
        val queries = (listOf(local.lookupTitle, local.title) + identity.titles)
            .map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(4)
        for (query in queries) {
            awaitQuietArtwork()
            val result = kitsu.searchAnime(query, 10)
            if (result !is ReiAnixCatalogResult.Success) continue
            val selected = selectKitsuCastCandidate(
                animeId, identity, result.value, known, readyKitsuPosters,
            ) ?: continue
            val kitsuId = selected.ids.kitsuId ?: continue
            loadKitsuPages(animeId, kitsuId)
            return
        }
    }

    /** Artwork is always the higher priority network lane. */
    private suspend fun awaitQuietArtwork() {
        while (scope.coroutineContext.isActive) {
            val active = database.read { db ->
                db.rawQuery(
                    "SELECT 1 FROM artwork WHERE artwork_type IN ('poster','backdrop') " +
                        "AND status='downloading' AND last_attempt_at>=? LIMIT 1",
                    arrayOf((System.currentTimeMillis() / 1_000.0 - 120.0).toString()),
                ).use { it.moveToFirst() }
            }
            if (!active) return
            delay(3_000L)
        }
    }

    private companion object {
        const val TAG = "ReiAnixCharacters"
        const val KITSU_PAGE_SIZE = 20
        const val MAX_PAGES = 125 // 2500 Kitsu or 3125 AniList credits, not the old 10/12 cap.
        const val BETWEEN_PAGES_MS = 1_250L
    }
}

/**
 * Kitsu's official poster URLs carry a stable numeric anime ID. Accept only
 * its known media CDN and exact path shape: arbitrary metadata URLs are never
 * treated as trusted external identifiers.
 */
internal fun kitsuAnimeIdFromPosterUrl(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null ||
        uri.host?.lowercase() !in setOf("media.kitsu.app", "media.kitsu.io")
    ) return null
    val path = uri.path.split('/').filter(String::isNotEmpty)
    if (path.size < 4 || path[0] != "anime" || path[1] != "poster_images") return null
    return path[2].takeIf {
        it.matches(Regex("[1-9][0-9]{0,17}"))
    }
}

/**
 * Title-only matches aren't sufficient to attach characters. But an already
 * downloaded Kitsu poster URL and an exact compatible title provide a second
 * independent anchor even for legacy candidate-only metadata records whose
 * year/format must not be trusted as identity evidence.
 */
internal fun selectKitsuCastCandidate(
    animeId: Long,
    identity: ReiAnixCatalogIdentity,
    candidates: List<ReiAnixCatalogAnime>,
    knownIds: ReiAnixExternalCatalogIds,
    readyKitsuPosterUrls: Set<String>,
): ReiAnixCatalogAnime? {
    val verified = ReiAnixCatalogIdentityMatcher.evaluateCandidates(
        animeId, identity, candidates, knownIds,
    ).value?.remote
    if (verified != null) return verified
    if (readyKitsuPosterUrls.isEmpty()) return null
    return candidates.filter { candidate ->
        candidate.source == ReiAnixCatalogSource.KITSU &&
            candidate.coverUrl in readyKitsuPosterUrls &&
            ReiAnixCatalogIdentityMatcher.evaluate(
                animeId, identity, candidate, knownIds,
            ).decision in setOf(
                ReiAnixCatalogIdentityDecision.CONFIRMED,
                ReiAnixCatalogIdentityDecision.PROBABLE,
            )
    }.distinctBy { it.ids.kitsuId }.singleOrNull()
}
