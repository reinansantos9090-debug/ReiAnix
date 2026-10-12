package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Read-oriented library service over the existing canonical SQLite repository.
 *
 * The Compose snapshot/NativeMailbox adapter remains responsible for commands
 * and features whose mutation semantics still belong to LibraryStore. This
 * service deliberately performs no writes and never treats an unavailable or
 * incompatible database as an empty library.
 */
class ReiAnixLibraryQueryService(
    context: Context,
    private val repository: ReiAnixSqliteLibraryRepository = ReiAnixSqliteLibraryRepository(context),
) {
    suspend fun animeById(id: Long): ReiAnixLibraryQueryResult<ReiAnixAnimeRecord> =
        query("animeById") {
            repository.getAnime(id)?.let { ReiAnixLibraryQueryResult.Success(it) }
                ?: ReiAnixLibraryQueryResult.NotFound("anime", id)
        }

    suspend fun animePage(
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): ReiAnixLibraryQueryResult<ReiAnixLibraryPage<ReiAnixAnimeRecord>> =
        query("animePage") {
            require(limit in 1..MAX_PAGE_SIZE) {
                "Page size must be between 1 and $MAX_PAGE_SIZE."
            }
            require(offset >= 0) { "Offset cannot be negative." }
            val rows = repository.listAnime(limit = limit + 1, offset = offset)
            if (rows.isEmpty()) {
                ReiAnixLibraryQueryResult.Empty("animePage")
            } else {
                ReiAnixLibraryQueryResult.Success(
                    ReiAnixLibraryPage(
                        items = rows.take(limit),
                        offset = offset,
                        limit = limit,
                        hasMore = rows.size > limit,
                    ),
                )
            }
        }

    suspend fun searchAnime(
        query: String,
        genreKey: String? = null,
        favoritesOnly: Boolean = false,
        watchingOnly: Boolean = false,
        completedOnly: Boolean = false,
        sortLabel: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): ReiAnixLibraryQueryResult<ReiAnixSqliteSearchPage> =
        query("searchAnime") {
            require(limit in 1..MAX_PAGE_SIZE) {
                "Search page size must be between 1 and $MAX_PAGE_SIZE."
            }
            require(offset >= 0) { "Search offset cannot be negative." }
            val page = repository.searchAnimeIds(
                query = query,
                genreKey = genreKey,
                favoritesOnly = favoritesOnly,
                watchingOnly = watchingOnly,
                completedOnly = completedOnly,
                sortLabel = sortLabel,
                limit = limit,
                offset = offset,
            )
            if (page.animeIds.isEmpty()) ReiAnixLibraryQueryResult.Empty("searchAnime")
            else ReiAnixLibraryQueryResult.Success(page)
        }

    suspend fun episodesForAnime(
        animeId: Long,
    ): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> =
        query("episodesForAnime") {
            // Keep the explicit parent lookup so a missing anime differs from an
            // existing anime that legitimately has no episodes.
            if (repository.getAnime(animeId) == null) {
                return@query ReiAnixLibraryQueryResult.NotFound("anime", animeId)
            }
            val episodes = repository.listEpisodes(animeId)
            if (episodes.isEmpty()) ReiAnixLibraryQueryResult.Empty("episodesForAnime")
            else ReiAnixLibraryQueryResult.Success(episodes)
        }

    suspend fun episodeById(id: Long): ReiAnixLibraryQueryResult<ReiAnixEpisodeRecord> =
        query("episodeById") {
            repository.getEpisode(id)?.let { ReiAnixLibraryQueryResult.Success(it) }
                ?: ReiAnixLibraryQueryResult.NotFound("episode", id)
        }

    suspend fun recentlyPlayed(
        limit: Int = DEFAULT_PAGE_SIZE,
    ): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> =
        query("recentlyPlayed") {
            require(limit in 1..MAX_PAGE_SIZE) {
                "Page size must be between 1 and $MAX_PAGE_SIZE."
            }
            val episodes = repository.listRecentlyPlayed(limit)
            if (episodes.isEmpty()) ReiAnixLibraryQueryResult.Empty("recentlyPlayed")
            else ReiAnixLibraryQueryResult.Success(episodes)
        }

    suspend fun folders(): ReiAnixLibraryQueryResult<List<ReiAnixFolderRecord>> =
        query("folders") {
            val rows = repository.listFolders()
            if (rows.isEmpty()) ReiAnixLibraryQueryResult.Empty("folders")
            else ReiAnixLibraryQueryResult.Success(rows)
        }

    suspend fun artworkForAnime(
        animeId: Long,
    ): ReiAnixLibraryQueryResult<List<ReiAnixArtworkRecord>> =
        query("artworkForAnime") {
            if (repository.getAnime(animeId) == null) {
                return@query ReiAnixLibraryQueryResult.NotFound("anime", animeId)
            }
            val artwork = repository.artworkForAnime(animeId)
            if (artwork.isEmpty()) ReiAnixLibraryQueryResult.Empty("artworkForAnime")
            else ReiAnixLibraryQueryResult.Success(artwork)
        }

    private suspend fun <T> query(
        operation: String,
        block: suspend () -> ReiAnixLibraryQueryResult<T>,
    ): ReiAnixLibraryQueryResult<T> =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Native library query failed: $operation", error)
            ReiAnixLibraryQueryResult.Failure(operation, error)
        }

    companion object {
        const val DEFAULT_PAGE_SIZE = 36
        const val MAX_PAGE_SIZE = 199
        private const val TAG = "ReiAnixLibraryQueries"
    }
}

data class ReiAnixLibraryPage<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

sealed interface ReiAnixLibraryQueryResult<out T> {
    data class Success<T>(val value: T) : ReiAnixLibraryQueryResult<T>
    data class Empty(val operation: String) : ReiAnixLibraryQueryResult<Nothing>
    data class NotFound(val entity: String, val id: Long) : ReiAnixLibraryQueryResult<Nothing>
    data class Failure(val operation: String, val cause: Throwable) : ReiAnixLibraryQueryResult<Nothing>
}
