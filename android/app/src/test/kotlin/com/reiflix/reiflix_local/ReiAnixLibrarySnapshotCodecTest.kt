package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.data.library.ReiAnixLibrarySnapshotCodec
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReiAnixLibrarySnapshotCodecTest {

    @Test
    fun decodesRealLibraryOrderIdentityProgressArtworkAndNulls() {
        val raw = """
            {
              "schemaVersion":1,
              "revision":4,
              "generatedAt":1700000000000,
              "status":"READY",
              "sourceState":"AVAILABLE",
              "sourceAvailable":true,
              "scanInProgress":true,
              "scanState":"SCANNING",
              "error":null,
              "continue_watching":[
                {"episode_id":72,"anime_id":7,"anime_title":"Example","season":2,"number":2,
                 "episode_title":"E02","file_name":"E02.mkv","progress":10.0,"duration":100.0,
                 "artwork_local_path":"/cache/continue.jpg","artwork_external_url":"https://example.invalid/continue.jpg"}
              ],
              "animes":[
                {
                  "id":7,
                  "main_title":"Example",
                  "favorite":true,
                  "media_kind":"series",
                  "year":2026,
                  "genres":["Action","Drama"],
                  "genre_ids":["action","drama"],
                  "meta":{"year":2026,"metadata_status":"available","cover_cache":"/cache/poster.jpg"},
                  "playback_target_episode_id":72,
                  "seasons":[
                    {"season":2,"season_name":"Season 2","episodes":[
                      {"id":72,"anime_id":7,"season":2,"number":2,"episode_title":"E02",
                       "file_name":"E02.mkv","path":"content://media/72","media_identity":"identity-72",
                       "availability_state":"available","missing":false,"progress":10.0,"duration":100.0,
                       "watched":false,"consumption_state":"in_progress"}
                    ]},
                    {"season":1,"season_name":"Season 1","episodes":[
                      {"id":71,"anime_id":7,"season":1,"number":1,"episode_title":"E01",
                       "file_name":"E01.mkv","path":"/storage/emulated/0/E01.mkv","media_identity":"identity-71",
                       "availability_state":"missing","missing":true,"progress":100.0,"duration":100.0,
                       "watched":true,"consumption_state":"watched","artwork_local_path":null}
                    ]}
                  ],
                  "specials":[],
                  "media_files":[]
                }
              ]
            }
        """.trimIndent()

        val state = ReiAnixLibrarySnapshotCodec.decode(raw)

        assertEquals(ReiAnixLibraryLoadStatus.READY, state.status)
        assertEquals(4L, state.revision)
        assertEquals(true, state.scanInProgress)
        assertEquals("SCANNING", state.scanState)
        assertEquals(listOf(2, 1), state.animes.single().seasons.map { it.number })
        assertEquals(listOf(72L), state.animes.single().seasons[0].episodes.map { it.id })
        assertEquals("content://media/72", state.animes.single().seasons[0].episodes.single().media.uri)
        assertEquals("identity-72", state.animes.single().seasons[0].episodes.single().media.mediaIdentity)
        assertEquals(10.0, state.animes.single().seasons[0].episodes.single().progressSeconds!!, 0.0)
        assertEquals(72L, state.animes.single().playbackTargetEpisodeId)
        assertEquals(listOf(72L), state.continueWatching.map { it.episodeId })
        assertEquals("/cache/continue.jpg", state.continueWatching.single().artwork?.localPath)
        assertEquals(ReiAnixConsumptionState.IN_PROGRESS, state.animes.single().seasons[0].episodes.single().consumptionState)
        assertEquals("/storage/emulated/0/E01.mkv", state.animes.single().seasons[1].episodes.single().media.path)
        assertEquals("identity-71", state.animes.single().seasons[1].episodes.single().media.mediaIdentity)
        assertEquals(ReiAnixConsumptionState.WATCHED, state.animes.single().seasons[1].episodes.single().consumptionState)
    }

    @Test
    fun homeProjectionIgnoresContinueWatchingProgressChanges() {
        val base = ReiAnixLibraryUiState(
            status = ReiAnixLibraryLoadStatus.READY,
            revision = 1L,
            continueWatching = listOf(
                com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel(
                    episodeId = 72L,
                    animeId = 7L,
                    animeTitle = "Example",
                    seasonNumber = 1,
                    number = 7.0,
                    title = "E07",
                    fileName = "E07.mkv",
                    progressSeconds = 100.0,
                    durationSeconds = 1000.0,
                    artwork = null,
                ),
            ),
        )
        val advanced = base.copy(
            continueWatching = listOf(
                base.continueWatching.single().copy(progressSeconds = 200.0),
            ),
        )

        assertEquals(
            ReiAnixHomeLibraryUiState.from(base),
            ReiAnixHomeLibraryUiState.from(advanced),
        )
    }

    @Test
    fun homeProjectionIgnoresEpisodeLevelProgressChanges() {
        val first = """
            {"schemaVersion":1,"revision":1,"status":"READY","sourceState":"AVAILABLE","sourceAvailable":true,
             "animes":[{"id":7,"main_title":"Example","media_kind":"series","favorite":false,
                       "year":2026,"genres":[],"genre_ids":[],
                       "meta":{"year":2026,"metadata_status":"available","cover_cache":null},
                       "seasons":[{"season":1,"season_name":"Season 1","episodes":[
                         {"id":71,"anime_id":7,"season":1,"number":7,"episode_title":"E07",
                          "file_name":"E07.mkv","path":"content://media/71","media_identity":"identity-71",
                          "availability_state":"available","missing":false,
                          "progress":100.0,"duration":1000.0,"watched":false,
                          "consumption_state":"in_progress"}]}],
                       "specials":[],"media_files":[]}],
             "continue_watching":[{"episode_id":71,"anime_id":7,"anime_title":"Example","season":1,"number":7,
                                  "episode_title":"E07","file_name":"E07.mkv","progress":100.0,"duration":1000.0}]
            }
        """.trimIndent()
        val second = first.replace("\"progress\":100.0", "\"progress\":200.0")

        val firstHome = ReiAnixHomeLibraryUiState.from(
            ReiAnixLibrarySnapshotCodec.decode(first),
        )
        val secondHome = ReiAnixHomeLibraryUiState.from(
            ReiAnixLibrarySnapshotCodec.decode(second),
        )

        assertEquals(firstHome, secondHome)
    }

    @Test
    fun scanStateDefaultsToIdleWhenSnapshotOmitsOptionalFields() {
        val state = ReiAnixLibrarySnapshotCodec.decode(
            """{"schemaVersion":1,"revision":1,"status":"EMPTY","sourceState":"NOT_CONFIGURED","sourceAvailable":false,"animes":[]}"""
        )

        assertEquals(false, state.scanInProgress)
        assertEquals("IDLE", state.scanState)
    }

    @Test
    fun emptyAndUnavailableStatesRemainDistinct() {
        val empty = """
            {"schemaVersion":1,"revision":1,"status":"EMPTY","sourceState":"NOT_CONFIGURED","sourceAvailable":false,"animes":[]}
        """.trimIndent()
        val unavailable = """
            {"schemaVersion":1,"revision":2,"status":"EMPTY","sourceState":"UNAVAILABLE","sourceAvailable":false,"animes":[]}
        """.trimIndent()

        assertEquals(ReiAnixLibraryLoadStatus.EMPTY, ReiAnixLibrarySnapshotCodec.decode(empty).status)
        assertEquals(ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE, ReiAnixLibrarySnapshotCodec.decode(unavailable).status)
    }

    @Test
    fun unsupportedSchemaAndStaleRevisionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixLibrarySnapshotCodec.decode(
                """{"schemaVersion":2,"revision":1,"status":"EMPTY","sourceState":"UNKNOWN","animes":[]}"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixLibrarySnapshotCodec.decode(
                """{"schemaVersion":1,"revision":2,"status":"EMPTY","sourceState":"UNKNOWN","animes":[]}""",
                previousRevision = 3,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixLibrarySnapshotCodec.decode(
                """{"schemaVersion":1,"revision":3,"status":"EMPTY","sourceState":"UNKNOWN","animes":[]}""",
                previousRevision = 3,
            )
        }
    }

    @Test
    fun commandResultsDecodeWithoutEmbeddingDomainState() {
        val result = ReiAnixLibrarySnapshotCodec.decodeCommandResult(
            """{"schemaVersion":1,"requestId":"req-77","action":"refresh","status":"QUEUED","error":null}"""
        )
        assertEquals("req-77", result.requestId)
        assertEquals("refresh", result.action)
        assertEquals("QUEUED", result.status)
    }
}
