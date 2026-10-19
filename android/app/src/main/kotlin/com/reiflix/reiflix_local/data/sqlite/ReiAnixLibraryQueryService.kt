package com.reiflix.reiflix_local.data.sqlite

import android.content.Context

/**
 * Backward-compatible query facade. ReiAnixLibraryService owns the canonical
 * query contracts and all reads continue to use the existing SQLite repository.
 * The former direct repository.searchAnimeIds( call is intentionally centralized
 * in ReiAnixLibraryService so callers share one query/error contract.
 */
class ReiAnixLibraryQueryService(
    context: Context,
    private val repository: ReiAnixSqliteLibraryRepository = ReiAnixSqliteLibraryRepository(context),
    private val libraryService: ReiAnixLibraryService = ReiAnixLibraryService(repository),
) {
    suspend fun animeById(id: Long): ReiAnixLibraryQueryResult<ReiAnixAnimeRecord> =
        libraryService.animeById(id)

    suspend fun animePage(
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): ReiAnixLibraryQueryResult<ReiAnixLibraryPage<ReiAnixAnimeRecord>> =
        libraryService.animePage(limit, offset)

    suspend fun searchAnime(
        searchText: String,
        genreKey: String? = null,
        favoritesOnly: Boolean = false,
        watchingOnly: Boolean = false,
        completedOnly: Boolean = false,
        sortLabel: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        libraryOnly: Boolean = false,
    ): ReiAnixLibraryQueryResult<ReiAnixSqliteSearchPage> =
        libraryService.searchAnime(
            searchText, genreKey, favoritesOnly, watchingOnly, completedOnly,
            sortLabel, limit, offset, libraryOnly,
        )

    suspend fun episodesForAnime(
        animeId: Long,
    ): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> =
        libraryService.episodesForAnime(animeId)

    suspend fun episodeById(id: Long): ReiAnixLibraryQueryResult<ReiAnixEpisodeRecord> =
        libraryService.episodeById(id)

    suspend fun recentlyPlayed(
        limit: Int = DEFAULT_PAGE_SIZE,
    ): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> =
        libraryService.recentlyPlayed(limit)

    suspend fun folders(): ReiAnixLibraryQueryResult<List<ReiAnixFolderRecord>> =
        libraryService.folders()

    suspend fun artworkForAnime(
        animeId: Long,
    ): ReiAnixLibraryQueryResult<List<ReiAnixArtworkRecord>> =
        libraryService.artworkForAnime(animeId)

    suspend fun libraryCatalogRows(): ReiAnixLibraryQueryResult<List<ReiAnixLibraryCatalogRow>> =
        libraryService.libraryCatalogRows()

    companion object {
        const val DEFAULT_PAGE_SIZE = 36
        const val MAX_PAGE_SIZE = 199
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
