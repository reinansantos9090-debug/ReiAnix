package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.library.ReiAnixLibraryRepository
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilters
import com.reiflix.reiflix_local.ui.library.ReiAnixLibrarySort
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPagedUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMetadataAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLibraryPaginationReconciliationTest {

    @Test
    fun snapshot_preserves_36_loaded_items() {
        val current = state(36, loadedPage = 0, total = 500, hasMore = true)
        val reconciled = reconcile(current, current.animes)

        assertEquals(36, reconciled.animes.size)
        assertEquals(0, reconciled.loadedPage)
        assertTrue(reconciled.hasMore)
    }

    @Test
    fun snapshot_preserves_72_loaded_items() {
        val current = state(72, loadedPage = 1, total = 500, hasMore = true)
        val reconciled = reconcile(current, current.animes)

        assertEquals(72, reconciled.animes.size)
        assertEquals(1, reconciled.loadedPage)
        assertTrue(reconciled.hasMore)
    }

    @Test
    fun snapshot_preserves_108_loaded_items() {
        val current = state(108, loadedPage = 2, total = 500, hasMore = true)
        val reconciled = reconcile(current, current.animes)

        assertEquals(108, reconciled.animes.size)
        assertEquals(2, reconciled.loadedPage)
        assertTrue(reconciled.hasMore)
    }

    @Test
    fun removed_items_are_deleted_without_resetting_loaded_window() {
        val current = state(108, loadedPage = 2, total = 108, hasMore = false)
        val canonical = current.animes.filterNot { it.id in setOf(106L, 107L, 108L) }

        val reconciled = reconcile(current, canonical, recountTotal = true)

        assertEquals(105, reconciled.animes.size)
        assertFalse(reconciled.animes.any { it.id in setOf(106L, 107L, 108L) })
        assertEquals(2, reconciled.loadedPage)
        assertFalse(reconciled.hasMore)
    }

    @Test
    fun new_compatible_item_enters_without_duplicate_and_keeps_order() {
        val current = state(
            count = 108,
            loadedPage = 2,
            total = 108,
            hasMore = false,
            sort = ReiAnixLibrarySort.TITLE_ASC.label,
        )
        val newcomer = anime(1000L, "AAA - New Anime")
        val canonical = listOf(newcomer) + current.animes

        val reconciled = reconcile(current, canonical, includeNewCandidates = true)

        assertEquals(108, reconciled.animes.size)
        assertEquals(1000L, reconciled.animes.first().id)
        assertEquals(108, reconciled.animes.map { it.id }.distinct().size)
        assertEquals(2, reconciled.loadedPage)
    }

    @Test
    fun stale_generation_page_result_is_rejected() {
        val current = state(108, loadedPage = 2, total = 500, hasMore = true).copy(generation = 11L)
        val stale = com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec.LibraryPageResult(
            generation = 10L,
            page = 3,
            pageSize = 36,
            total = 500,
            hasMore = true,
            items = listOf(anime(999L, "Stale Result")),
        )

        assertEquals(null, ReiAnixLibraryRepository.applyLibraryPage(current, stale))
        assertEquals(108, current.animes.size)
        assertEquals(2, current.loadedPage)
        assertEquals(11L, current.generation)
    }

    @Test
    fun explicit_refresh_reset_clears_old_pages_then_page_one_loads_again() {
        val current = state(108, loadedPage = 2, total = 500, hasMore = true, generation = 10L)
        val reset = ReiAnixLibraryRepository.resetPagedState(
            current = current,
            generation = 11L,
            query = "Naruto",
            genre = "Action",
            sort = ReiAnixLibrarySort.TITLE_ASC.label,
            favoritesOnly = true,
            watchingOnly = false,
            completedOnly = false,
        )

        assertEquals(0, reset.animes.size)
        assertEquals(-1, reset.loadedPage)
        assertFalse(reset.hasMore)
        assertEquals(11L, reset.generation)
        assertEquals("Naruto", reset.query)
        assertEquals("Action", reset.genreKey)
        assertTrue(reset.favoritesOnly)

        val page0 = com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec.LibraryPageResult(
            generation = 11L,
            page = 0,
            pageSize = 36,
            total = 72,
            hasMore = true,
            items = (1L..36L).map { anime(it, "Naruto ${it.toString().padStart(3, '0')}") },
        )
        val loaded = ReiAnixLibraryRepository.applyLibraryPage(reset, page0)
        assertNotNull(loaded)
        assertEquals(36, loaded!!.animes.size)
        assertEquals(0, loaded.loadedPage)
        assertTrue(loaded.hasMore)

        val page1 = com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec.LibraryPageResult(
            generation = 11L,
            page = 1,
            pageSize = 36,
            total = 72,
            hasMore = false,
            items = (37L..72L).map { anime(it, "Naruto ${it.toString().padStart(3, '0')}") },
        )
        val loadedAgain = ReiAnixLibraryRepository.applyLibraryPage(loaded, page1)
        assertNotNull(loadedAgain)
        assertEquals(72, loadedAgain!!.animes.size)
        assertEquals(1, loadedAgain.loadedPage)
        assertFalse(loadedAgain.hasMore)
    }

    @Test
    fun artwork_update_does_not_drop_loaded_items() {
        val current = state(108, loadedPage = 2, total = 500, hasMore = true)
        val target = current.animes.first()
        val changed = target.copy(artwork = ReiAnixArtworkUiModel("/cache/new.jpg", null))
        val canonical = listOf(changed) + current.animes.drop(1)

        val reconciled = reconcile(current, canonical)

        assertEquals(108, reconciled.animes.size)
        assertEquals("/cache/new.jpg", reconciled.animes.first().artwork?.localPath)
    }

    @Test
    fun progress_update_does_not_drop_loaded_items() {
        val current = state(108, loadedPage = 2, total = 500, hasMore = true)
        val target = current.animes.first()
        val season = target.seasons.first()
        val episode = season.episodes.first()
        val changedEpisode = episode.copy(progressSeconds = 87.0)
        val changedSeason = season.copy(episodes = listOf(changedEpisode))
        val changed = target.copy(seasons = listOf(changedSeason))

        val reconciled = reconcile(current, listOf(changed) + current.animes.drop(1))

        assertEquals(108, reconciled.animes.size)
        assertEquals(87.0, reconciled.animes.first().seasons.first().episodes.first().progressSeconds, 0.0)
    }

    @Test
    fun favorites_filter_blocks_non_favorite_snapshot_items() {
        val current = state(
            count = 108,
            loadedPage = 2,
            total = 108,
            hasMore = false,
            filters = ReiAnixLibraryFilters(favoritesOnly = true),
        ).copy(
            animes = state(108, loadedPage = 2, total = 108, hasMore = false)
                .animes
                .map { it.copy(favorite = true) },
        )
        val nonFavoriteNew = anime(1000L, "Not A Favorite", favorite = false)
        val favoriteNew = anime(1001L, "Favorite New", favorite = true)
        val canonical = listOf(nonFavoriteNew, favoriteNew) + current.animes

        val reconciled = reconcile(
            current = current,
            canonical = canonical,
            includeNewCandidates = true,
        )

        assertFalse(reconciled.animes.any { it.id == nonFavoriteNew.id })
        assertTrue(reconciled.animes.any { it.id == favoriteNew.id })
        assertEquals(108, reconciled.animes.size)
        assertEquals(108, reconciled.animes.map { it.id }.distinct().size)
    }

    private fun reconcile(
        current: ReiAnixLibraryPagedUiState,
        canonical: List<ReiAnixAnimeUiModel>,
        recountTotal: Boolean = false,
        includeNewCandidates: Boolean = false,
    ) = ReiAnixLibraryRepository.reconcilePagedState(
        current = current,
        canonicalAnimes = canonical,
        recountTotal = recountTotal,
        includeNewCandidates = includeNewCandidates,
    )

    private fun state(
        count: Int,
        loadedPage: Int,
        total: Int,
        hasMore: Boolean,
        generation: Long = 7L,
        sort: String = ReiAnixLibrarySort.DEFAULT.label,
        filters: ReiAnixLibraryFilters = ReiAnixLibraryFilters(sort = sort),
    ): ReiAnixLibraryPagedUiState {
        return ReiAnixLibraryPagedUiState(
            status = ReiAnixLibraryLoadStatus.READY,
            animes = (1L..count.toLong()).map { anime(it, "Anime ${it.toString().padStart(3, '0')}") },
            totalCount = total,
            hasMore = hasMore,
            loadedPage = loadedPage,
            isLoading = false,
            generation = generation,
            query = filters.query,
            genreKey = filters.selectedGenreKey,
            favoritesOnly = filters.favoritesOnly,
            watchingOnly = filters.watchingOnly,
            completedOnly = filters.completedOnly,
            sort = filters.sort,
        )
    }

    private fun anime(
        id: Long,
        title: String,
        favorite: Boolean = false,
        genre: String? = null,
        episodeState: ReiAnixConsumptionState = ReiAnixConsumptionState.UNWATCHED,
    ) = ReiAnixAnimeUiModel(
        id = id,
        title = title,
        year = 2026,
        genres = genre?.let { listOf(ReiAnixGenreUiModel("genre:${it}", it)) } ?: emptyList(),
        favorite = favorite,
        mediaKind = ReiAnixMediaKind.SERIES,
        artwork = ReiAnixArtworkUiModel(null, null),
        metadataAvailability = ReiAnixMetadataAvailability.UNRESOLVED,
        seasons = listOf(
            ReiAnixSeasonUiModel(
                animeId = id,
                number = 1,
                title = "Season 1",
                episodes = listOf(
                    ReiAnixEpisodeUiModel(
                        id = id * 10 + 1,
                        animeId = id,
                        seasonNumber = 1,
                        number = 1.0,
                        title = "Episode 1",
                        fileName = "episode.mkv",
                        media = ReiAnixLocalMediaUiModel(
                            reference = "content://example/${id}",
                            uri = "content://example/${id}",
                            path = null,
                            mediaIdentity = "identity-${id}",
                            sourceAvailabilityState = "available",
                            availability = ReiAnixMediaAvailability.AVAILABLE,
                        ),
                        progressSeconds = if (episodeState == ReiAnixConsumptionState.IN_PROGRESS) 10.0 else null,
                        durationSeconds = 100.0,
                        watched = episodeState != ReiAnixConsumptionState.UNWATCHED,
                        consumptionState = episodeState,
                        artwork = null,
                    ),
                ),
            ),
        ),
        specials = emptyList(),
        mediaFiles = emptyList(),
    )
}
