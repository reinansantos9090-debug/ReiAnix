package com.reianix.reianix_local.data.personal

import android.util.Log
import com.reianix.reianix_local.data.anilist.AniListError
import com.reianix.reianix_local.data.anilist.AniListResult
import com.reianix.reianix_local.data.anilist.ReiAnixAniListClient
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogIdentityMatcher
import com.reianix.reianix_local.data.catalog.ReiAnixCatalogResult
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val priority = Channel<Long>(Channel.UNLIMITED)
    private val backlog = Channel<Long>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val startupScheduled = AtomicBoolean(false)

    init {
        scope.launch {
            delay(12_000L) // Keep initial poster/metadata publishing uncontended.
            while (isActive) {
                val id = priority.tryReceive().getOrNull()
                    ?: backlog.tryReceive().getOrNull()
                    ?: select {
                        priority.onReceive { it }
                        backlog.onReceive { it }
                    }
                try {
                    completeAnime(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not complete character metadata for anime=$id", error)
                } finally {
                    pending.remove(id)
                }
            }
        }
    }

    /** A new confirmed metadata association uses this non-blocking notification. */
    fun schedule(animeId: Long, prioritize: Boolean = false) {
        if (animeId <= 0L || !pending.add(animeId)) return
        val queued = if (prioritize) priority.trySend(animeId) else backlog.trySend(animeId)
        if (queued.isFailure) pending.remove(animeId)
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
        if (kitsuId != null) {
            if (current?.source == "kitsu" && current.sourceId == kitsuId &&
                current.complete
            ) return
            loadKitsuPages(animeId, kitsuId)
        } else if (current?.characters.isNullOrEmpty()) {
            // For local metadata that predates a saved external Kitsu ID,
            // match strictly before showing anyone else's cast.
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
            awaitQuietArtwork()
            val result = kitsu.getCharacterPage(kitsuId, page * KITSU_PAGE_SIZE, KITSU_PAGE_SIZE)
            if (result !is ReiAnixCatalogResult.Success) return
            val info = result.value
            store.publish(animeId, "kitsu", kitsuId, info.characters, !info.hasNextPage)
            if (!info.hasNextPage) return
            page++
            delay(BETWEEN_PAGES_MS)
        }
    }

    private suspend fun tryVerifiedKitsuSearch(
        animeId: Long,
        local: ReiAnixAnimeRecord,
        known: ReiAnixExternalCatalogIds,
    ) {
        val title = local.lookupTitle.takeIf(String::isNotBlank) ?: local.title
        if (title.isBlank()) return
        awaitQuietArtwork()
        val result = kitsu.searchAnimeWithCharacters(title, 10)
        if (result !is ReiAnixCatalogResult.Success) return
        val selected = ReiAnixCatalogIdentityMatcher.evaluateCandidates(
            animeId, local.toExternalCatalogIdentity(), result.value, known,
        ).value?.remote ?: return
        val kitsuId = selected.ids.kitsuId ?: return
        store.publish(
            animeId, "kitsu", kitsuId, selected.characters, selected.charactersComplete,
        )
        if (!selected.charactersComplete) loadKitsuPages(animeId, kitsuId)
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
