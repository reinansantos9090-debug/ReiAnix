package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLibraryViewModelContractTest {
    @Test
    fun homeProjectionExcludesEpisodeProgressButDetailsProjectionRetainsIt() {
        val state = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":7,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
               "animes":[{"id":7,"main_title":"Local","media_kind":"series","favorite":false,
               "year":2026,"genres":[],"genre_ids":[],"meta":{},
               "seasons":[{"season":1,"season_name":"Season 1","episodes":[
                 {"id":72,"anime_id":7,"season":1,"number":7,"episode_title":"E07","file_name":"E07.mkv",
                  "path":"content://episode/72","media_identity":"episode-72","availability_state":"available",
                  "missing":false,"progress":18.0,"duration":100.0,"watched":false,
                  "consumption_state":"in_progress"}]}],
               "specials":[],"media_files":[]}],
               "continue_watching":[{"episode_id":72,"anime_id":7,"anime_title":"Local","season":1,"number":7,
               "episode_title":"E07","file_name":"E07.mkv","progress":18.0,"duration":100.0}]}""".trimIndent(),
        )

        assertEquals(ReiAnixLibraryLoadStatus.READY, state.status)

        val home = ReiAnixLibraryViewModel.projectHomeState(state)
        val details = ReiAnixLibraryViewModel.projectDetailsState(state, 7L)

        assertTrue(home.animes.single().seasons.single().episodes.isEmpty())
        assertEquals(72L, details.anime?.seasons?.single()?.episodes?.single()?.id)
        assertEquals(18.0, details.anime?.seasons?.single()?.episodes?.single()?.progressSeconds ?: -1.0, 0.0)
    }
}
