package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMetadataAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import com.reiflix.reiflix_local.ui.search.ReiAnixSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixSearchEngineTest {

    @Test
    fun searchMatchesTitleIgnoringCaseAccentsAndPunctuation() {
        val index = ReiAnixSearchEngine.buildIndex(
            listOf(
                anime(7L, "São-Paulo Show"),
                anime(8L, "Other Show"),
            ),
        )

        assertEquals(listOf(7L), index.search("sao paulo").map { it.id })
        assertEquals(listOf(7L), index.search("SAO-PAULO").map { it.id })
    }

    @Test
    fun searchUsesPersistedGenresYearAndEpisodeFields() {
        val index = ReiAnixSearchEngine.buildIndex(
            listOf(
                anime(
                    11L,
                    "Local Anime",
                    year = 2026,
                    genres = listOf("Ação", "Drama"),
                    episodes = listOf(episode(101L, 2, 7.0, "Festival")),
                ),
            ),
        )

        assertEquals(listOf(11L), index.search("acao").map { it.id })
        assertEquals(listOf(11L), index.search("2026").map { it.id })
        assertEquals(listOf(11L), index.search("s02e07").map { it.id })
        assertEquals(listOf(11L), index.search("festival").map { it.id })
        assertEquals(listOf(11L), index.search("episode 7").map { it.id })
    }

    @Test
    fun emptyQueryReturnsNoSyntheticHistory() {
        val index = ReiAnixSearchEngine.buildIndex(listOf(anime(21L, "Real Local Anime")))
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun resultsUseStableDeterministicOrder() {
        val index = ReiAnixSearchEngine.buildIndex(
            listOf(anime(30L, "Alpha"), anime(10L, "Alpha"), anime(20L, "Beta")),
        )
        assertEquals(listOf(10L, 30L, 20L), index.search("a").map { it.id })
    }

    @Test
    fun stableKeysAreNotListPositionIdentity() {
        val models = listOf(anime(41L, "First"), anime(42L, "Second"))
        assertEquals("anime:41", models[0].stableKey)
        assertEquals("anime:42", models[1].stableKey)
    }

    private fun anime(
        id: Long,
        title: String,
        year: Int? = null,
        genres: List<String> = emptyList(),
        episodes: List<ReiAnixEpisodeUiModel> = emptyList(),
    ): ReiAnixAnimeUiModel =
        ReiAnixAnimeUiModel(
            id = id,
            title = title,
            year = year,
            genres = genres.mapIndexed { index, name -> ReiAnixGenreUiModel("genre-" + index, name) },
            favorite = false,
            mediaKind = ReiAnixMediaKind.SERIES,
            artwork = null,
            metadataAvailability = ReiAnixMetadataAvailability.UNRESOLVED,
            seasons = if (episodes.isEmpty()) emptyList() else listOf(
                ReiAnixSeasonUiModel(id, 2, "Temporada 2", episodes),
            ),
            specials = emptyList(),
            mediaFiles = emptyList(),
        )

    private fun episode(
        id: Long,
        season: Int,
        number: Double,
        title: String,
    ): ReiAnixEpisodeUiModel =
        ReiAnixEpisodeUiModel(
            id = id,
            animeId = 11L,
            seasonNumber = season,
            number = number,
            title = title,
            fileName = "Local Anime S" + season.toString().padStart(2, '0') + "E" + number.toInt().toString().padStart(2, '0') + ".mkv",
            media = ReiAnixLocalMediaUiModel(
                reference = "content://media/" + id,
                uri = "content://media/" + id,
                path = null,
                mediaIdentity = "identity-" + id,
                sourceAvailabilityState = "available",
                availability = ReiAnixMediaAvailability.AVAILABLE,
            ),
            progressSeconds = 0.0,
            durationSeconds = 100.0,
            watched = false,
            consumptionState = null,
            artwork = null,
        )
}