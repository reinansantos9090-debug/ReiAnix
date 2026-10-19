package com.reiflix.reiflix_local.data.sqlite

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/** Canonical Kotlin entry point for library reads and mutations. */
class ReiAnixLibraryService(
    private val repository: ReiAnixSqliteLibraryRepository,
) {
    private val scanMutationMutex = Mutex()
    private val mutableChanges = MutableStateFlow(
        ReiAnixLibraryChange(revision = 0L, operation = "initial", committedAtMillis = 0L),
    )
    val changes: StateFlow<ReiAnixLibraryChange> = mutableChanges.asStateFlow()

    private suspend fun <T> serializeMutation(operation: suspend () -> T): T {
        scanMutationMutex.lock()
        return try { operation() } finally { scanMutationMutex.unlock() }
    }

    private fun publishChange(operation: String) {
        mutableChanges.value = ReiAnixLibraryChange(
            revision = mutableChanges.value.revision + 1L,
            operation = operation,
            committedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun <T> query(
        operation: String,
        block: suspend () -> ReiAnixLibraryQueryResult<T>,
    ): ReiAnixLibraryQueryResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(TAG, "Native library operation failed: " + operation, error)
        ReiAnixLibraryQueryResult.Failure(operation, error)
    }

    suspend fun animeById(id: Long): ReiAnixLibraryQueryResult<ReiAnixAnimeRecord> =
        query("animeById") {
            repository.getAnime(id)?.let { ReiAnixLibraryQueryResult.Success(it) }
                ?: ReiAnixLibraryQueryResult.NotFound("anime", id)
        }

    suspend fun animePage(
        limit: Int = ReiAnixLibraryQueryService.DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): ReiAnixLibraryQueryResult<ReiAnixLibraryPage<ReiAnixAnimeRecord>> =
        query("animePage") {
            require(limit in 1..ReiAnixLibraryQueryService.MAX_PAGE_SIZE) {
                "Page size must be between 1 and " + ReiAnixLibraryQueryService.MAX_PAGE_SIZE + "."
            }
            require(offset >= 0) { "Offset cannot be negative." }
            val rows = repository.listAnime(limit = limit + 1, offset = offset)
            if (rows.isEmpty()) ReiAnixLibraryQueryResult.Empty("animePage")
            else ReiAnixLibraryQueryResult.Success(
                ReiAnixLibraryPage(rows.take(limit), offset, limit, rows.size > limit),
            )
        }

    suspend fun searchAnime(
        searchText: String,
        genreKey: String? = null,
        favoritesOnly: Boolean = false,
        watchingOnly: Boolean = false,
        completedOnly: Boolean = false,
        sortLabel: String? = null,
        limit: Int = ReiAnixLibraryQueryService.DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        libraryOnly: Boolean = false,
    ): ReiAnixLibraryQueryResult<ReiAnixSqliteSearchPage> = query("searchAnime") {
        require(limit in 1..ReiAnixLibraryQueryService.MAX_PAGE_SIZE) {
            "Search page size must be between 1 and " + ReiAnixLibraryQueryService.MAX_PAGE_SIZE + "."
        }
        require(offset >= 0) { "Search offset cannot be negative." }
        val page = repository.searchAnimeIds(
            query = searchText, genreKey = genreKey, favoritesOnly = favoritesOnly,
            watchingOnly = watchingOnly, completedOnly = completedOnly,
            sortLabel = sortLabel, limit = limit, offset = offset, libraryOnly = libraryOnly,
        )
        if (page.animeIds.isEmpty()) ReiAnixLibraryQueryResult.Empty("searchAnime")
        else ReiAnixLibraryQueryResult.Success(page)
    }

    suspend fun episodesForAnime(
        animeId: Long,
    ): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> = query("episodesForAnime") {
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
        limit: Int = ReiAnixLibraryQueryService.DEFAULT_PAGE_SIZE,
    ): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> = query("recentlyPlayed") {
        require(limit in 1..ReiAnixLibraryQueryService.MAX_PAGE_SIZE) {
            "Page size must be between 1 and " + ReiAnixLibraryQueryService.MAX_PAGE_SIZE + "."
        }
        val rows = repository.listRecentlyPlayed(limit)
        if (rows.isEmpty()) ReiAnixLibraryQueryResult.Empty("recentlyPlayed")
        else ReiAnixLibraryQueryResult.Success(rows)
    }

    suspend fun folders(): ReiAnixLibraryQueryResult<List<ReiAnixFolderRecord>> = query("folders") {
        val rows = repository.listFolders()
        if (rows.isEmpty()) ReiAnixLibraryQueryResult.Empty("folders")
        else ReiAnixLibraryQueryResult.Success(rows)
    }

    suspend fun artworkForAnime(
        animeId: Long,
    ): ReiAnixLibraryQueryResult<List<ReiAnixArtworkRecord>> = query("artworkForAnime") {
        if (repository.getAnime(animeId) == null) {
            return@query ReiAnixLibraryQueryResult.NotFound("anime", animeId)
        }
        val rows = repository.artworkForAnime(animeId)
        if (rows.isEmpty()) ReiAnixLibraryQueryResult.Empty("artworkForAnime")
        else ReiAnixLibraryQueryResult.Success(rows)
    }

    suspend fun libraryCatalogRows(): ReiAnixLibraryQueryResult<List<ReiAnixLibraryCatalogRow>> =
        query("libraryCatalogRows") {
            val rows = repository.loadLibraryCatalogRows()
            if (rows.isEmpty()) ReiAnixLibraryQueryResult.Empty("libraryCatalogRows")
            else ReiAnixLibraryQueryResult.Success(rows)
        }

    suspend fun updateAnimeMetadata(
        patch: ReiAnixAnimeMetadataPatch,
    ): ReiAnixLibraryQueryResult<ReiAnixAnimeRecord> = query("updateAnimeMetadata") {
        val record = serializeMutation {
            repository.updateAnimeMetadata(patch).also { publishChange("updateAnimeMetadata") }
        }
        ReiAnixLibraryQueryResult.Success(record)
    }

    suspend fun updatePlaybackProgress(
        update: ReiAnixPlaybackProgressUpdate,
    ): ReiAnixLibraryQueryResult<ReiAnixEpisodeRecord> = query("updatePlaybackProgress") {
        val record = serializeMutation {
            repository.updatePlaybackProgress(update).also { publishChange("updatePlaybackProgress") }
        }
        ReiAnixLibraryQueryResult.Success(record)
    }

    suspend fun toggleAnimePinned(animeId: Long): Boolean = serializeMutation {
        repository.toggleAnimePinned(animeId).also { publishChange("toggleAnimePinned") }
    }

    suspend fun setAnimePersonalNote(animeId: Long, note: String?): String? = serializeMutation {
        repository.setAnimePersonalNote(animeId, note).also { publishChange("setAnimePersonalNote") }
    }

    suspend fun setAnimeUserTags(animeId: Long, tags: List<String>): List<String> = serializeMutation {
        repository.setAnimeUserTags(animeId, tags).also { publishChange("setAnimeUserTags") }
    }

    suspend fun setEpisodeManualIdentification(
        episodeId: Long,
        season: Int?,
        number: Double?,
        episodeType: String,
        title: String?,
    ): ReiAnixEpisodeRecord = serializeMutation {
        repository.setEpisodeManualIdentification(
            episodeId = episodeId, season = season, number = number,
            episodeType = episodeType, title = title,
        ).also { publishChange("setEpisodeManualIdentification") }
    }

    suspend fun indexDiscoveredMedia(
        snapshot: NativeLibraryScanSnapshot,
        onTransactionOutcome: ((
            ReiAnixSqliteTransactionOutcome, NativeLibraryIndexReport?,
        ) -> Unit)? = null,
    ): NativeLibraryIndexReport = serializeMutation {
        var committed = false
        // Keep the repository.indexDiscoveredMedia(snapshot, onTransactionOutcome)
        // contract explicit: this wrapper observes the outcome before publishing.
        val report = repository.indexDiscoveredMedia(snapshot) { outcome, committedReport ->
            if (outcome == ReiAnixSqliteTransactionOutcome.COMMITTED && committedReport != null) {
                committed = true
            }
            onTransactionOutcome?.invoke(outcome, committedReport)
        }
        if (committed) publishChange("indexDiscoveredMedia")
        report
    }

    suspend fun finishNativeScanRun(
        scanId: String,
        requestId: String?,
        sourceKind: String,
        scopeKind: String,
        scopeRef: String,
        nativeGeneration: Long?,
        generationId: String?,
        status: String,
        stats: JSONObject,
        indexReport: JSONObject?,
        startedAtMillis: Long?,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ): Boolean = serializeMutation {
        repository.finishNativeScanRun(
            scanId = scanId, requestId = requestId, sourceKind = sourceKind,
            scopeKind = scopeKind, scopeRef = scopeRef, nativeGeneration = nativeGeneration,
            generationId = generationId, status = status, stats = stats,
            indexReport = indexReport, startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
        )
    }

    suspend fun recordNativeVolumeChange(payload: JSONObject): JSONObject = serializeMutation {
        repository.recordNativeVolumeChange(payload).also { result ->
            if (!result.optBoolean("ignored", false)) publishChange("recordNativeVolumeChange")
        }
    }

    suspend fun reconcileSafDocumentIdentityChange(
        oldUri: String,
        newUri: String,
        name: String,
        treeUri: String,
    ): JSONObject = serializeMutation {
        repository.reconcileSafDocumentIdentityChange(
            oldUri = oldUri, newUri = newUri, name = name, treeUri = treeUri,
        ).also { result ->
            if (result.optString("status") in setOf("reconciled", "already_reconciled")) {
                publishChange("reconcileSafDocumentIdentityChange")
            }
        }
    }

    companion object {
        private const val TAG = "ReiAnixLibraryService"
    }
}

data class ReiAnixLibraryChange(
    val revision: Long,
    val operation: String,
    val committedAtMillis: Long,
)
