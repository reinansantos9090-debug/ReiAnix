package com.reianix.reianix_local.ui.home

import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixGenreUiModel
import com.reianix.reianix_local.ui.model.ReiAnixHomeAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixLocalRecommendationPolicyTest {
    @Test
    fun ranksUnstartedTitlesByGenresFromLocalViewingSignals() {
        val watchingAction = anime(
            id = 1,
            title = "Watching action",
            genres = listOf("Action", "Adventure"),
            watching = true,
            action = "Continuar",
        )
        val favoriteComedy = anime(
            id = 2,
            title = "Favorite comedy",
            genres = listOf("Comedy"),
            favorite = true,
            action = "Reassistir",
        )
        val actionPick = anime(id = 3, title = "Action pick", genres = listOf(" action "))
        val comedyPick = anime(id = 4, title = "Comedy pick", genres = listOf("COMEDY"))
        val unrelated = anime(id = 5, title = "Unrelated", genres = listOf("Mystery"))

        val result = ReiAnixLocalRecommendationPolicy.recommend(
            listOf(watchingAction, favoriteComedy, actionPick, comedyPick, unrelated),
        )

        assertEquals(listOf(3L, 4L, 5L), result.map { it.id })
    }

    @Test
    fun excludesStartedCompletedUnavailableAndDuplicateTitles() {
        val watching = anime(
            id = 1,
            title = "Already watching",
            genres = listOf("Action"),
            watching = true,
            action = "Continuar",
        )
        val completed = anime(
            id = 2,
            title = "Completed",
            genres = listOf("Action"),
            action = "Reassistir",
        )
        val unavailable = anime(
            id = 3,
            title = "Unavailable",
            genres = listOf("Action"),
            available = 0,
        )
        val pick = anime(id = 4, title = "Pick", genres = listOf("Action"))

        val result = ReiAnixLocalRecommendationPolicy.recommend(
            listOf(watching, completed, unavailable, pick, pick.copy(title = "Duplicate row")),
        )

        assertEquals(listOf(4L), result.map { it.id })
    }

    @Test
    fun usesRecentlyAddedOrderWhenNoLocalTasteSignalsExistAndHonorsLimit() {
        val older = anime(id = 1, title = "Older", addedAt = 10.0)
        val newer = anime(id = 2, title = "Newer", addedAt = 20.0)
        val alphabeticallyLast = anime(id = 3, title = "Z title", addedAt = 20.0)

        val result = ReiAnixLocalRecommendationPolicy.recommend(
            listOf(older, newer, alphabeticallyLast),
            limit = 2,
        )

        assertEquals(listOf(2L, 3L), result.map { it.id })
        assertEquals(emptyList<ReiAnixHomeAnimeUiModel>(), ReiAnixLocalRecommendationPolicy.recommend(emptyList(), limit = 0))
    }

    private fun anime(
        id: Long,
        title: String,
        genres: List<String> = emptyList(),
        favorite: Boolean = false,
        watching: Boolean = false,
        action: String = "Assistir",
        available: Int = 1,
        addedAt: Double? = null,
    ): ReiAnixHomeAnimeUiModel = ReiAnixHomeAnimeUiModel(
        id = id,
        title = title,
        year = null,
        genres = genres.map { ReiAnixGenreUiModel(id = null, name = it) },
        favorite = favorite,
        mediaKind = ReiAnixMediaKind.SERIES,
        artwork = null,
        playbackTargetEpisodeId = id * 10,
        availableContentCount = available,
        playbackActionLabel = action,
        isWatching = watching,
        addedAt = addedAt,
    )
}
