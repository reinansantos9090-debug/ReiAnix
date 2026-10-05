package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.mapper.LibraryUiMappers
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiStateProjection
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMetadataAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUiMappersTest {

    @Test
    fun animeMappingPreservesIdentityOrderProgressStateAndMetadata() {
        val first = episode(101, 1, 0.0, 1200.0, "unwatched")
        val partial = episode(102, 2, 300.0, 1200.0, "in_progress")
        val completed = episode(103, 3, 1200.0, 1200.0, "completed")
        val watched = episode(104, 4, 1200.0, 1200.0, "watched")
        val source = animeSource(
            id = 10L,
            seasons = listOf(
                mapOf<String, Any?>(
                    "season" to 1,
                    "season_name" to "Temporada 1",
                    "episodes" to listOf(first, partial, completed, watched),
                ),
            ),
        ).toMutableMap().apply {
            this["playback_target_episode_id"] = first["id"]
        }

        val model = LibraryUiMappers.anime(source)

        assertEquals(10L, model.id)
        assertEquals("Example Anime", model.title)
        assertEquals(101L, model.playbackTargetEpisodeId)
        assertEquals(2026, model.year)
        assertTrue(model.favorite)
        assertEquals(ReiAnixMetadataAvailability.AVAILABLE, model.metadataAvailability)
        assertEquals("/cache/poster.jpg", model.artwork?.localPath)
        assertEquals(listOf("Ação", "Drama"), model.genres.map { it.name })
        assertEquals(listOf("action", "drama"), model.genres.map { it.id })

        val episodes = model.seasons.single().episodes
        assertEquals(listOf(101L, 102L, 103L, 104L), episodes.map { it.id })
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), episodes.map { it.number })
        assertEquals(0.0, episodes[0].progressSeconds!!, 0.0)
        assertEquals(ReiAnixConsumptionState.UNWATCHED, episodes[0].consumptionState)
        assertEquals(ReiAnixConsumptionState.IN_PROGRESS, episodes[1].consumptionState)
        assertFalse(episodes[1].isCompleted)
        assertEquals(ReiAnixConsumptionState.COMPLETED, episodes[2].consumptionState)
        assertTrue(episodes[2].isCompleted)
        assertEquals(ReiAnixConsumptionState.WATCHED, episodes[3].consumptionState)
        assertTrue(episodes[3].isCompleted)
        assertEquals("content://media/102", episodes[1].media.reference)
        assertEquals("content://media/102", episodes[1].media.uri)
        assertNull(episodes[1].media.path)
        assertEquals("identity-102", episodes[1].media.mediaIdentity)
        assertEquals("anime:10:season:number:1", model.seasons.single().stableKey)
    }

    @Test
    fun scoreIsMappedOnlyWhenPresentInTheRealMetadataProjection() {
        val withScore = animeSource(
            id = 12L,
            meta = mapOf(
                "year" to 2026,
                "metadata_status" to "available",
                "cover_cache" to null,
                "score" to 86,
            ),
            seasons = emptyList(),
        )

        val withoutScore = animeSource(
            id = 13L,
            meta = mapOf(
                "year" to 2026,
                "metadata_status" to "unresolved",
                "cover_cache" to null,
            ),
            seasons = emptyList(),
        )

        assertEquals(86.0, LibraryUiMappers.anime(withScore).score!!, 0.0)
        assertNull(LibraryUiMappers.anime(withoutScore).score)
    }

    @Test
    fun detailsProjectionPreservesEpisodeIdentityAcrossProgressTicks() {
        val episode = episode(1201, 1, 18.0, 100.0, "in_progress")
        val source = animeSource(
            id = 120L,
            seasons = listOf(
                mapOf<String, Any?>(
                    "season" to 1,
                    "season_name" to "Temporada 1",
                    "episodes" to listOf(episode),
                ),
            ),
        ).toMutableMap().apply {
            this["playback_target_episode_id"] = 1201L
        }

        val first = ReiAnixDetailsUiStateProjection.from(
            readyDetailsLibraryState(LibraryUiMappers.anime(source)),
            120L,
        )

        val progressed = episode.toMutableMap().apply { this["progress"] = 19.0 }
        val progressedSource = animeSource(
            id = 120L,
            seasons = listOf(
                mapOf<String, Any?>(
                    "season" to 1,
                    "season_name" to "Temporada 1",
                    "episodes" to listOf(progressed),
                ),
            ),
        ).toMutableMap().apply {
            this["playback_target_episode_id"] = 1201L
        }

        val second = ReiAnixDetailsUiStateProjection.from(
            readyDetailsLibraryState(LibraryUiMappers.anime(progressedSource)),
            120L,
        )

        val firstEpisode = first.anime!!.seasons.single().episodes.single()
        val secondEpisode = second.anime!!.seasons.single().episodes.single()

        assertEquals(firstEpisode.id, secondEpisode.id)
        assertEquals(firstEpisode.stableKey, secondEpisode.stableKey)
        assertEquals(18.0, firstEpisode.progressSeconds!!, 0.0)
        assertEquals(19.0, secondEpisode.progressSeconds!!, 0.0)
        assertEquals(first.anime!!.playbackTargetEpisodeId, second.anime!!.playbackTargetEpisodeId)
    }

    private fun readyDetailsLibraryState(
        anime: com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel,
    ) = ReiAnixLibraryUiState(
        status = ReiAnixLibraryLoadStatus.READY,
        revision = 1L,
        animes = listOf(anime),
        sourceAvailable = true,
        sourceState = "AVAILABLE",
    )

    @Test
    fun detailsProjectionDropsStaleOrUnavailablePlaybackTarget() {
        val staleEpisode = episode(
            id = 209,
            number = 9,
            progress = 90.0,
            duration = 100.0,
            state = "in_progress",
            path = "/storage/emulated/0/stale-209.mkv",
            missing = true,
            availabilityState = "missing",
        )
        val source = animeSource(
            id = 209L,
            seasons = listOf(
                mapOf<String, Any?>(
                    "season" to 1,
                    "season_name" to "Temporada 1",
                    "episodes" to listOf(staleEpisode),
                ),
            ),
        ).toMutableMap().apply {
            this["playback_target_episode_id"] = 209L
        }

        val details = ReiAnixDetailsUiStateProjection.from(
            readyDetailsLibraryState(LibraryUiMappers.anime(source)),
            209L,
        )

        assertNull(details.anime?.playbackTargetEpisodeId)
        assertFalse(details.anime?.shouldContinue ?: true)
    }

    @Test
    fun detailsProjectionKeepsCanonicalPlayableTarget() {
        val special = episode(
            id = 310,
            number = 1,
            progress = 0.0,
            duration = 100.0,
            state = "unwatched",
            availabilityState = "available",
        )
        val source = animeSource(
            id = 310L,
            seasons = emptyList(),
        ).toMutableMap().apply {
            this["specials"] = listOf(
                mapOf<String, Any?>(
                    "season" to null,
                    "season_name" to "Especiais",
                    "episodes" to listOf(special),
                ),
            )
            this["playback_target_episode_id"] = 310L
        }

        val details = ReiAnixDetailsUiStateProjection.from(
            readyDetailsLibraryState(LibraryUiMappers.anime(source)),
            310L,
        )

        assertEquals(310L, details.anime?.playbackTargetEpisodeId)
        assertFalse(details.anime?.shouldContinue ?: true)
    }

    @Test
    fun availabilityStateAvailableMapsExactly() {
        val model = LibraryUiMappers.episode(
            episode(201, 1, 0.0, 100.0, "unwatched", availabilityState = "available"),
        )
        assertEquals(ReiAnixMediaAvailability.AVAILABLE, model.media.availability)
        assertEquals("available", model.media.sourceAvailabilityState)
    }

    @Test
    fun availabilityStateMissingMapsExactly() {
        val model = LibraryUiMappers.episode(
            episode(
                202, 2, 0.0, 100.0, "unwatched",
                path = "/storage/emulated/0/removed.mkv",
                missing = true,
                availabilityState = "missing",
            ),
        )
        assertEquals(ReiAnixMediaAvailability.MISSING, model.media.availability)
        assertEquals("missing", model.media.sourceAvailabilityState)
        assertEquals(202L, model.id)
        assertEquals("/storage/emulated/0/removed.mkv", model.media.path)
        assertEquals("identity-202", model.media.mediaIdentity)
    }

    @Test
    fun availabilityStateScopeUnavailableRemainsSpecific() {
        val model = LibraryUiMappers.episode(
            episode(203, 3, 0.0, 100.0, "unwatched", availabilityState = "scope_unavailable"),
        )
        assertEquals(ReiAnixMediaAvailability.SCOPE_UNAVAILABLE, model.media.availability)
        assertEquals("scope_unavailable", model.media.sourceAvailabilityState)
    }

    @Test
    fun availabilityStateVolumeUnavailableRemainsSpecific() {
        val model = LibraryUiMappers.episode(
            episode(204, 4, 0.0, 100.0, "unwatched", availabilityState = "volume_unavailable"),
        )
        assertEquals(ReiAnixMediaAvailability.VOLUME_UNAVAILABLE, model.media.availability)
        assertEquals("volume_unavailable", model.media.sourceAvailabilityState)
    }

    @Test
    fun availabilityStateUnknownDoesNotInventMeaning() {
        val source = episode(205, 5, 0.0, 100.0, "unwatched", availabilityState = "future_library_state")
            .toMutableMap()
        source.remove("missing")

        val model = LibraryUiMappers.episode(source)

        assertEquals(ReiAnixMediaAvailability.UNKNOWN, model.media.availability)
        assertEquals("future_library_state", model.media.sourceAvailabilityState)
    }

    @Test
    fun availabilityStateAbsentRemainsUnknown() {
        val source = episode(206, 6, 0.0, 100.0, "unwatched").toMutableMap()
        source.remove("availability_state")

        val model = LibraryUiMappers.episode(source)

        assertEquals(ReiAnixMediaAvailability.UNKNOWN, model.media.availability)
        assertNull(model.media.sourceAvailabilityState)
    }

    @Test
    fun removedFileRetainsIdentityAfterMissingProjection() {
        val removed = episode(
            id = 207,
            number = 7,
            progress = 250.0,
            duration = 1200.0,
            state = "in_progress",
            path = "/storage/emulated/0/removed-207.mkv",
            missing = true,
            availabilityState = "missing",
        )

        val model = LibraryUiMappers.episode(removed)

        assertEquals(ReiAnixMediaAvailability.MISSING, model.media.availability)
        assertEquals("/storage/emulated/0/removed-207.mkv", model.media.reference)
        assertEquals("/storage/emulated/0/removed-207.mkv", model.media.path)
        assertEquals("identity-207", model.media.mediaIdentity)
        assertEquals(207L, model.id)
        assertEquals(250.0, model.progressSeconds!!, 0.0)
        assertEquals(ReiAnixConsumptionState.IN_PROGRESS, model.consumptionState)
    }

    @Test
    fun libraryStoreCatalogProjectionContractPreservesOrderAndNulls() {
        // This fixture mirrors the exact shape emitted by LibraryStore.catalog():
        // anime-level fields plus meta, ordered seasons, ordered episode rows,
        // specials, media_files, genres and stable media identity fields.
        val source = mapOf<String, Any?>(
            "id" to 900L,
            "main_title" to "Catalog Contract Anime",
            "favorite" to false,
            "media_kind" to "series",
            "meta" to mapOf<String, Any?>(
                "year" to null,
                "metadata_status" to "unresolved",
                "cover_cache" to null,
                "cover_url" to null,
                "banner_url" to null,
            ),
            "genres" to listOf("Drama", "Ação"),
            "genre_ids" to listOf("drama", "action"),
            "seasons" to listOf(
                mapOf<String, Any?>(
                    "season" to 2,
                    "season_name" to "Temporada 2",
                    "episodes" to listOf(
                        episode(902, 2, 0.0, 0.0, "unwatched"),
                        episode(901, 1, 120.0, 1200.0, "in_progress"),
                    ),
                ),
                mapOf<String, Any?>(
                    "season" to 1,
                    "season_name" to "Temporada 1",
                    "episodes" to listOf(
                        episode(903, 1, 1200.0, 1200.0, "completed"),
                    ),
                ),
            ),
            "specials" to listOf(
                mapOf<String, Any?>(
                    "season_name" to "Especiais",
                    "season" to null,
                    "episodes" to listOf(
                        episode(904, 1, 0.0, 400.0, "unwatched"),
                    ),
                ),
            ),
            "media_files" to listOf(
                episode(905, 1, 400.0, 500.0, "in_progress"),
            ),
        )

        val model = LibraryUiMappers.anime(source)

        assertEquals(listOf(2, 1), model.seasons.map { it.number })
        assertEquals(listOf(902L, 901L), model.seasons[0].episodes.map { it.id })
        assertEquals(listOf(903L), model.seasons[1].episodes.map { it.id })
        assertEquals(listOf(904L), model.specials.map { it.id })
        assertEquals(listOf(905L), model.mediaFiles.map { it.id })
        assertEquals(listOf("Drama", "Ação"), model.genres.map { it.name })
        assertEquals(listOf("drama", "action"), model.genres.map { it.id })
        assertNull(model.year)
        assertEquals(ReiAnixMetadataAvailability.UNRESOLVED, model.metadataAvailability)
        assertNull(model.artwork)
    }

    @Test
    fun consumptionStateRemainsSourceDriven() {
        val source = episode(208, 8, 1199.0, 1200.0, "in_progress").toMutableMap()
        source["watched"] = false

        val model = LibraryUiMappers.episode(source)

        assertEquals(ReiAnixConsumptionState.IN_PROGRESS, model.consumptionState)
        assertFalse(model.watched ?: true)
        assertEquals(1199.0, model.progressSeconds!!, 0.0)
        assertFalse(model.isCompleted)
    }

    @Test
    fun missingMetadataAndArtworkRemainExplicitlyAbsent() {
        val source = animeSource(
            id = 11L,
            meta = emptyMap(),
            genres = emptyList(),
            genreIds = emptyList(),
            seasons = listOf(
                mapOf<String, Any?>(
                    "season" to 1,
                    "episodes" to listOf(
                        episode(111, 1, 0.0, 0.0, "unwatched")
                            .toMutableMap()
                            .also { it.remove("episode_title") },
                    ),
                ),
            ),
        )

        val model = LibraryUiMappers.anime(source)

        assertEquals(ReiAnixMetadataAvailability.UNRESOLVED, model.metadataAvailability)
        assertNull(model.artwork)
        assertNull(model.year)
        assertTrue(model.genres.isEmpty())
        assertEquals("Episode-1.mkv", model.seasons.single().episodes.single().displayTitle)
        assertNull(model.seasons.single().episodes.single().artwork)
        assertNull(model.seasons.single().episodes.single().media.uri)
    }

    @Test
    fun unknownConsumptionStateDoesNotGetDerivedFromProgress() {
        val source = episode(106, 6, 5.0, 1200.0, "future_state").toMutableMap()
        source.remove("watched")

        val model = LibraryUiMappers.episode(source)

        assertEquals(ReiAnixConsumptionState.UNKNOWN, model.consumptionState)
        assertNull(model.watched)
        assertEquals(5.0, model.progressSeconds!!, 0.0)
        assertEquals("content://media/106", model.media.reference)
    }

    @Test
    fun animeAndEpisodeStableKeysUseExistingIds() {
        val source = animeSource(
            id = 42L,
            seasons = listOf(
                mapOf<String, Any?>(
                    "season" to 3,
                    "episodes" to listOf(episode(4201, 1, 0.0, 100.0, "unwatched")),
                ),
            ),
        )

        val model = LibraryUiMappers.anime(source)

        assertEquals("anime:42", model.stableKey)
        assertEquals("anime:42:season:number:3", model.seasons.single().stableKey)
        assertEquals(4201L, model.seasons.single().episodes.single().id)
    }

    private fun animeSource(
        id: Long,
        meta: Map<String, Any?> = mapOf(
            "year" to 2026,
            "metadata_status" to "available",
            "cover_cache" to "/cache/poster.jpg",
        ),
        genres: List<String> = listOf("Ação", "Drama"),
        genreIds: List<String> = listOf("action", "drama"),
        seasons: List<Map<String, Any?>>,
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "main_title" to "Example Anime",
        "media_kind" to "series",
        "favorite" to true,
        "meta" to meta,
        "genre_ids" to genreIds,
        "genres" to genres,
        "seasons" to seasons,
        "specials" to emptyList<Map<String, Any?>>(),
        "media_files" to emptyList<Map<String, Any?>>(),
    )

    private fun episode(
        id: Long,
        number: Int,
        progress: Double,
        duration: Double,
        state: String,
        path: String = "content://media/$id",
        missing: Boolean = false,
        availabilityState: String = if (missing) "missing" else "available",
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "anime_id" to 10L,
        "season" to 1,
        "number" to number,
        "episode_title" to "Episode $number",
        "file_name" to "Episode-$number.mkv",
        "path" to path,
        "media_identity" to "identity-$id",
        "progress" to progress,
        "duration" to duration,
        "watched" to (state == "watched"),
        "consumption_state" to state,
        "missing" to missing,
        "availability_state" to availabilityState,
    )
}
