package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReiAnixSeasonIdentityTest {

    @Test
    fun numberedSeasonsHaveDistinctStableKeys() {
        val season1 = season(number = 1, title = "Temporada 1", episodeIds = listOf(101L))
        val season2 = season(number = 2, title = "Temporada 2", episodeIds = listOf(201L))

        assertNotEquals(season1.stableKey, season2.stableKey)
        assertEquals("anime:123:season:number:1", season1.stableKey)
        assertEquals("anime:123:season:number:2", season2.stableKey)
    }

    @Test
    fun differentTitlesProduceDistinctStableKeys() {
        val especial = season(number = null, title = "Especial", episodeIds = listOf(301L))
        val ova = season(number = null, title = "OVA", episodeIds = listOf(401L))

        assertNotEquals(especial.stableKey, ova.stableKey)
        assertEquals("anime:123:season:title:especial:episodes:301", especial.stableKey)
        assertEquals("anime:123:season:title:ova:episodes:401", ova.stableKey)
    }

    @Test
    fun duplicateTitlesAreDisambiguatedByRealEpisodeIds() {
        val first = season(number = null, title = "Especial", episodeIds = listOf(501L, 502L))
        val second = season(number = null, title = "Especial", episodeIds = listOf(601L, 602L))

        assertNotEquals(first.stableKey, second.stableKey)
    }

    @Test
    fun missingNumberAndTitleUseDeterministicRealEpisodeSignature() {
        val first = season(number = null, title = "", episodeIds = listOf(701L, 702L))
        val second = season(number = null, title = "", episodeIds = listOf(801L, 802L))

        assertNotEquals(first.stableKey, second.stableKey)
        assertEquals(
            "anime:123:season:episodes:701-702",
            first.stableKey,
        )
        assertEquals(
            "anime:123:season:episodes:801-802",
            second.stableKey,
        )
    }

    @Test
    fun sameSeasonKeepsStableKeyWhenItsPositionChanges() {
        val first = season(number = null, title = "", episodeIds = listOf(901L, 902L))
        val reorderedSeason = season(number = null, title = "", episodeIds = listOf(901L, 902L))

        val originalList = listOf(
            season(number = 1, title = "Temporada 1", episodeIds = listOf(801L)),
            first,
        )
        val movedList = listOf(
            first,
            season(number = 1, title = "Temporada 1", episodeIds = listOf(801L)),
        )

        assertEquals(originalList[1].stableKey, movedList[0].stableKey)
        assertEquals(first.stableKey, reorderedSeason.stableKey)
    }

    @Test
    fun episodeSignatureIsIndependentOfEpisodeOrder() {
        val ordered = season(
            number = null,
            title = "",
            episodeIds = listOf(1003L, 1001L, 1002L),
        )
        val shuffled = season(
            number = null,
            title = "",
            episodeIds = listOf(1002L, 1003L, 1001L),
        )

        assertEquals(ordered.stableKey, shuffled.stableKey)
        assertEquals(
            "anime:123:season:episodes:1001-1002-1003",
            ordered.stableKey,
        )
    }

    private fun season(
        number: Int?,
        title: String,
        episodeIds: List<Long>,
    ): ReiAnixSeasonUiModel =
        ReiAnixSeasonUiModel(
            animeId = 123L,
            number = number,
            title = title,
            episodes = episodeIds.map { id ->
                ReiAnixEpisodeUiModel(
                    id = id,
                    animeId = 123L,
                    seasonNumber = number,
                    number = id.toDouble(),
                    title = "Episode $id",
                    fileName = "Episode-$id.mkv",
                    media = ReiAnixLocalMediaUiModel(
                        reference = "content://episode/$id",
                        uri = "content://episode/$id",
                        path = null,
                        mediaIdentity = "episode:$id",
                        sourceAvailabilityState = "available",
                        availability = ReiAnixMediaAvailability.AVAILABLE,
                    ),
                    progressSeconds = 0.0,
                    durationSeconds = 100.0,
                    watched = false,
                    consumptionState = null,
                    artwork = null,
                )
            },
        )
}
