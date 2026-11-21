package com.reianix.reianix_local.data.personal

import android.util.Log
import com.reianix.reianix_local.data.anilist.AniListError
import com.reianix.reianix_local.data.anilist.AniListResult
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reianix.reianix_local.data.sqlite.ReiAnixSqliteSettingsRepository
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
import kotlinx.coroutines.withContext

internal fun validCharacterAniListId(value: Long?): Int? =
    value?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()

/**
 * An independent, best-effort enrichment lane AFTER AniList metadata is committed.
 *
 * Neither metadata search/commit nor cover acquisition awaits character requests.
 * Confirmed matches take priority over gradual backfill of existing associations.
 * The same process-wide cache is used by Details, so the resulting data is ready
 * when Details is opened and a concurrent Details request does not refetch.
 */
class ReiAnixCharacterPrefetcher(
    private val cache: ReiAnixCharacterCache,
    private val database: ReiAnixSqliteDatabase,
    private val settings: ReiAnixSqliteSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val urgent = Channel<Int>(Channel.UNLIMITED)
    private val background = Channel<Int>(Channel.BUFFERED)
    private val urgentPending = ConcurrentHashMap.newKeySet<Int>()
    private val backgroundPending = ConcurrentHashMap.newKeySet<Int>()
    private val backfillStarted = AtomicBoolean(false)

    init {
        scope.launch {
            while (isActive) {
                val urgentId = urgent.tryReceive().getOrNull()
                val id = urgentId ?: background.tryReceive().getOrNull() ?: select {
                    urgent.onReceive { it }
                    background.onReceive { it }
                }
                try {
                    if (!aniListEnabled() || cache.isCached(id)) continue
                    // This optional request starts after the canonical metadata and
                    // artwork publication; space out API traffic to protect AniList.
                    delay(2_500L)
                    when (val response = cache.load(id)) {
                        is AniListResult.Success -> Unit
                        is AniListResult.Failure -> {
                            if (response.error is AniListError.RateLimited) {
                                val wait = response.error.retryAfterSeconds
                                    ?.coerceIn(15L, 120L) ?: 45L
                                delay(wait * 1_000L)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Character enrichment deferred: ${error::class.java.simpleName}")
                } finally {
                    urgentPending.remove(id)
                    backgroundPending.remove(id)
                }
            }
        }
    }

    /** Called only after the existing AniList metadata commit and cover notification. */
    fun scheduleConfirmedAssociation(anilistId: Int) {
        if (anilistId <= 0) return
        // Prioritize the latest association even if already waiting in backfill.
        if (urgentPending.add(anilistId) && urgent.trySend(anilistId).isFailure) {
            urgentPending.remove(anilistId)
        }
    }

    /** Slowly fill existing missing character caches after startup UI work completes. */
    fun scheduleExistingAssociationsBackfill() {
        if (!backfillStarted.compareAndSet(false, true)) return
        scope.launch {
            delay(30_000L) // Existing poster/metadata startup must keep its fast path.
            try {
                if (!aniListEnabled()) return@launch
                val ids = withContext(Dispatchers.IO) {
                    database.read { db ->
                        db.rawQuery(
                            "SELECT DISTINCT anilist_id FROM anime " +
                                "WHERE anilist_id BETWEEN 1 AND 2147483647 ORDER BY anilist_id",
                            null,
                        ).use { cursor ->
                            buildList {
                                while (cursor.moveToNext()) {
                                    validCharacterAniListId(cursor.getLong(0))?.let(::add)
                                }
                            }
                        }
                    }
                }
                for (id in ids) {
                    if (!isActive) break
                    if (backgroundPending.add(id)) background.send(id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Character cache backfill deferred: ${error::class.java.simpleName}")
            }
        }
    }

    private suspend fun aniListEnabled(): Boolean =
        settings.getPreference("metadata.anilist_enabled")?.value?.trim()?.lowercase()
            ?.let { it == "true" || it == "1" || it == "yes" } ?: true

    private companion object {
        const val TAG = "ReiAnixCharacters"
    }
}
