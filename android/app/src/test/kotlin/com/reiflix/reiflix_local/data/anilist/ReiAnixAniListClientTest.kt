package com.reiflix.reiflix_local.data.anilist

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReiAnixAniListClientTest {
    @Test
    fun parsesTypedAnimeMetadataAndAliases() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """
            {
              "data": {"Page": {"media": [{
                "id": 16498,
                "idMal": 21990,
                "title": {"romaji":"Shingeki no Kyojin","english":"Attack on Titan","native":"進撃の巨人"},
                "synonyms": ["Attack on Titan"],
                "description": "Humanity fights titans",
                "format": "TV",
                "episodes": 25,
                "status": "FINISHED",
                "averageScore": 84,
                "duration": 24,
                "season": "SPRING",
                "seasonYear": 2013,
                "genres": ["Action", "Drama"],
                "coverImage": {"large":"https://img.example/cover.jpg","extraLarge":"https://img.example/cover-large.jpg"},
                "bannerImage": "https://img.example/banner.jpg",
                "studios": {"nodes":[{"name":"Wit Studio"}]}
              }]}}
            }
        """.trimIndent()))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("Attack on Titan")
        assertTrue(result is AniListResult.Success)
        val anime = (result as AniListResult.Success).value.single()
        assertEquals(16498, anime.id)
        assertEquals(21990L, anime.myAnimeListId)
        assertEquals("Attack on Titan", anime.preferredTitle)
        assertTrue(anime.synonyms.contains("Shingeki no Kyojin"))
        assertEquals(25, anime.episodes)
        assertEquals(84, anime.averageScore)
        assertEquals(24, anime.durationMinutes)
        assertEquals(listOf("Action", "Drama"), anime.genres)
        assertEquals("https://img.example/cover.jpg", anime.coverLargeUrl)
        assertEquals(listOf("Wit Studio"), anime.studios)
        assertEquals(ReiAnixAniListClient.ENDPOINT, transport.endpoint)
        assertFalse(transport.requestBody?.optString("query").isNullOrBlank())
    }

    @Test
    fun searchSendsLiteralGraphQlVariablesAndExpectedJsonValues() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[]}}}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("Dorohedoro", limit = 7)

        assertEquals(AniListResult.Success<List<AniListAnime>>(emptyList()), result)
        val body = transport.requestBody!!
        val query = body.getString("query")
        assertTrue(query.contains("\$search"))
        assertTrue(query.contains("\$page"))
        assertTrue(query.contains("\$perPage"))
        assertTrue(query.contains("media(search: \$search, type: ANIME"))
        assertTrue(query.contains("idMal"))
        val variables = body.getJSONObject("variables")
        assertEquals("Dorohedoro", variables.getString("search"))
        assertEquals(1, variables.getInt("page"))
        assertEquals(7, variables.getInt("perPage"))
    }

    @Test
    fun idLookupSendsLiteralIdVariableAndExpectedJsonValue() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Media":{"id":42,"title":{}}}}"""))
        val result = ReiAnixAniListClient(transport, 0).getAnime(42)

        assertTrue(result is AniListResult.Success)
        assertEquals(42, (result as AniListResult.Success).value?.id)
        val body = transport.requestBody!!
        assertTrue(body.getString("query").contains("\$id"))
        assertTrue(body.getString("query").contains("Media(id: \$id, type: ANIME)"))
        assertEquals(42, body.getJSONObject("variables").getInt("id"))
    }

    @Test
    fun blankSearchReturnsEmptyWithoutNetworkRequest() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, "{}"))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("   ")
        assertEquals(AniListResult.Success<List<AniListAnime>>(emptyList()), result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun emptyResultsAreSuccessNotNetworkFailure() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[]}}}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("does not exist")
        assertEquals(AniListResult.Success<List<AniListAnime>>(emptyList()), result)
    }

    @Test
    fun graphqlErrorsAreReportedSeparately() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"errors":[{"message":"rate limit"}],"data":null}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("anime")
        assertTrue(result is AniListResult.Failure)
        assertTrue((result as AniListResult.Failure).error is AniListError.GraphQl)
    }

    @Test
    fun http429WaitsGloballyAndRetriesTheOriginalGraphQlOperation() = runBlocking {
        var now = 1_700_000_000_000L
        val resetAtSeconds = (now + 8_000L) / 1_000L
        val transport = SequenceFakeTransport(
            listOf(
                AniListHttpResponse(
                    429,
                    "{}",
                    headers = mapOf(
                        "Retry-After" to "5",
                        "X-RateLimit-Limit" to "30",
                        "X-RateLimit-Remaining" to "0",
                        "X-RateLimit-Reset" to resetAtSeconds.toString(),
                    ),
                ),
                AniListHttpResponse(200, """{"data":{"Page":{"media":[]}}}"""),
            ),
        )
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait -> now += wait },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )
        val result = ReiAnixAniListClient(transport, requestController = controller)
            .searchAnime("anime")

        assertEquals(AniListResult.Success<List<AniListAnime>>(emptyList()), result)
        assertEquals(2, transport.calls)
        assertEquals(8_000L, now - 1_700_000_000_000L)
    }

    @Test
    fun malformedJsonAndMissingDataAreSafeFailures() = runBlocking {
        val malformed = ReiAnixAniListClient(FakeTransport(AniListHttpResponse(200, "{")), 0)
            .searchAnime("anime")
        assertEquals(AniListError.InvalidResponse("Malformed JSON response"), (malformed as AniListResult.Failure).error)

        val missingData = ReiAnixAniListClient(FakeTransport(AniListHttpResponse(200, "{}")), 0)
            .searchAnime("anime")
        assertTrue((missingData as AniListResult.Failure).error is AniListError.InvalidResponse)
    }

    @Test
    fun missingSearchMediaArrayIsInvalidResponseNotEmptyResults() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{}}}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("anime")
        assertTrue(result is AniListResult.Failure)
        assertTrue((result as AniListResult.Failure).error is AniListError.InvalidResponse)
    }

    @Test
    fun invalidSearchMediaEntriesAreDroppedOnlyWithAnExplicitIncompleteResult() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"data":{"Page":{"media":[{"id":73,"title":{"english":"Valid anime"}},{"title":{}}]}}}""",
            ),
        )
        val result = ReiAnixAniListClient(transport, 0).searchAnime("anime")
        assertTrue(result is AniListResult.Success)
        val success = result as AniListResult.Success
        assertTrue(success.incomplete)
        assertEquals(listOf(73), success.value.map { it.id })
    }

    @Test
    fun partialGraphQlSearchRetainsValidRowsAndMarksResultIncomplete() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"errors":[{"message":"Banner resolver unavailable","path":["Page","media",0,"bannerImage"]}],"data":{"Page":{"media":[{"id":81,"title":{"english":"Valid anime"},"coverImage":{"large":"https://cdn.example/81.jpg"},"bannerImage":null},{"id":82,"title":{"english":"Second anime"}}]}}}""",
            ),
        )
        val result = ReiAnixAniListClient(transport, 0).searchAnime("anime")
        assertTrue(result is AniListResult.Success)
        val success = result as AniListResult.Success
        assertTrue(success.incomplete)
        assertEquals(listOf(81, 82), success.value.map { it.id })
    }

    @Test
    fun partialGraphQlDetailRetainsMediaButMarksItIncomplete() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"errors":[{"message":"Banner resolver unavailable","path":["Media","bannerImage"]}],"data":{"Media":{"id":91,"title":{"english":"Detail anime"},"coverImage":{"large":"https://cdn.example/91.jpg"}}}}""",
            ),
        )
        val result = ReiAnixAniListClient(transport, 0).getAnime(91)
        assertTrue(result is AniListResult.Success)
        val success = result as AniListResult.Success
        assertTrue(success.incomplete)
        assertEquals(91, success.value?.id)
        assertEquals("https://cdn.example/91.jpg", success.value?.coverLargeUrl)
    }

    @Test
    fun missingDetailFieldIsInvalidButExplicitNullMeansNoMatch() = runBlocking {
        val missing = ReiAnixAniListClient(
            FakeTransport(AniListHttpResponse(200, """{"data":{}}""")), 0,
        ).getAnime(42)
        assertTrue(missing is AniListResult.Failure)
        assertTrue((missing as AniListResult.Failure).error is AniListError.InvalidResponse)

        val absent = ReiAnixAniListClient(
            FakeTransport(AniListHttpResponse(200, """{"data":{"Media":null}}""")), 0,
        ).getAnime(42)
        assertEquals(AniListResult.Success<AniListAnime?>(null), absent)
    }

    @Test
    fun missingOptionalMetadataDoesNotRejectAnime() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Media":{"id":7,"title":{}}}}"""))
        val result = ReiAnixAniListClient(transport, 0).getAnime(7)
        assertTrue(result is AniListResult.Success)
        val anime = (result as AniListResult.Success).value!!
        assertEquals(7, anime.id)
        assertEquals("", anime.preferredTitle)
        assertEquals(null, anime.episodes)
        assertTrue(anime.genres.isEmpty())
    }


    @Test
    fun idBatchUsesOneRequestAndDeduplicatesValidIdsWithinFifteen() = runBlocking {
        val ids = (1..15).toList()
        val media = ids.joinToString(",") { id ->
            """{"id":$id,"title":{"romaji":"Anime $id"},"synonyms":["Alias $id"],"coverImage":{"large":"https://img.example/$id.jpg","extraLarge":"https://img.example/${id}_large.jpg"}}"""
        }
        val transport = FakeTransport(
            AniListHttpResponse(200, """{"data":{"Page":{"media":[$media]}}}"""),
        )

        val result = ReiAnixAniListClient(transport, 0)
            .getAnimeBatch(ids + listOf(1, 1, 0, -4))

        assertTrue(result is AniListResult.Success)
        val batch = (result as AniListResult.Success).value
        assertEquals(15, batch.items.size)
        assertTrue(batch.isComplete)
        assertEquals(1, transport.calls)
        val body = transport.requestBody!!
        val query = body.getString("query")
        assertTrue(query.contains("media(id_in: " + '$' + "ids"))
        val requested = body.getJSONObject("variables").getJSONArray("ids")
        assertEquals(15, requested.length())
        assertEquals(1, requested.getInt(0))
        assertEquals(15, requested.getInt(14))
        assertEquals("Anime 15", batch.items.last().titleRomaji)
        assertEquals("https://img.example/15_large.jpg", batch.items.last().coverExtraLargeUrl)
    }

    @Test
    fun idBatchRejectsMoreThanFifteenUniqueValidIdsWithoutNetworkRequest() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[]}}}"""))

        val result = ReiAnixAniListClient(transport, 0).getAnimeBatch((1..16).toList())

        assertTrue(result is AniListResult.Failure)
        assertTrue((result as AniListResult.Failure).error is AniListError.InvalidRequest)
        assertEquals(0, transport.calls)
    }

    @Test
    fun emptyOrOnlyInvalidIdBatchDoesNotSendNetworkRequest() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[]}}}"""))

        val empty = ReiAnixAniListClient(transport, 0).getAnimeBatch(listOf(0, -1, -9, 0))

        assertEquals(AniListResult.Success<AniListBatchResult<List<AniListAnime>>>(AniListBatchResult(emptyList())), empty)
        assertEquals(0, transport.calls)
    }

    @Test
    fun titleBatchUsesDistinctAliasesAndOwnVariablesInOneRequest() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"data":{"term0":{"media":[{"id":21,"title":{"romaji":"Attack on Titan"}}]},"term1":{"media":[{"id":22,"title":{"romaji":"Naruto"}}]}}}""",
            ),
        )

        val result = ReiAnixAniListClient(transport, 0)
            .searchAnimeBatch(listOf(" Attack on Titan ", "attack ON titan", "Naruto"), perTermLimit = 7)

        assertTrue(result is AniListResult.Success)
        val batch = (result as AniListResult.Success).value
        assertTrue(batch.isComplete)
        assertEquals(setOf("Attack on Titan", "Naruto"), batch.items.keys)
        assertEquals(21, batch.items["Attack on Titan"]!!.single().id)
        assertEquals(22, batch.items["Naruto"]!!.single().id)
        assertEquals(1, transport.calls)
        val body = transport.requestBody!!
        val query = body.getString("query")
        assertTrue(query.contains("term0: Page"))
        assertTrue(query.contains("term1: Page"))
        assertTrue(query.contains("media(search: " + '$' + "term0"))
        assertTrue(query.contains("media(search: " + '$' + "term1"))
        assertFalse(query.contains("Attack on Titan"))
        val variables = body.getJSONObject("variables")
        assertEquals("Attack on Titan", variables.getString("term0"))
        assertEquals("Naruto", variables.getString("term1"))
        assertEquals(7, variables.getInt("perPage"))
    }

    @Test
    fun titleBatchPreservesValidAliasesWhenOneAliasReturnsGraphQlError() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"errors":[{"message":"Resolver temporarily unavailable","path":["term1","media"]}],"data":{"term0":{"media":[{"id":31,"title":{"romaji":"Alpha"}}]},"term1":{"media":null},"term2":{"media":[{"id":33,"title":{"romaji":"Gamma"}}]}}}""",
            ),
        )

        val result = ReiAnixAniListClient(transport, 0)
            .searchAnimeBatch(listOf("Alpha", "Beta", "Gamma"))

        assertTrue(result is AniListResult.Success)
        val batch = (result as AniListResult.Success).value
        assertEquals(setOf("Beta"), batch.incompleteKeys)
        assertEquals(setOf("Alpha", "Gamma"), batch.items.keys)
        assertEquals(31, batch.items["Alpha"]!!.single().id)
        assertEquals(33, batch.items["Gamma"]!!.single().id)
        assertEquals(1, transport.calls)
    }


    @Test
    fun idBatchUsesRequestedPageSizeForSmallBatchesAndMissingIdsAreDefinitiveOnlyOnCompleteResponse() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"data":{"Page":{"media":[{"id":45,"title":{"english":"Returned anime"}}]}}}""",
            ),
        )
        val result = ReiAnixAniListClient(transport, 0).getAnimeBatch(listOf(45, 46, 45))
        assertTrue(result is AniListResult.Success)
        val batch = (result as AniListResult.Success).value
        assertTrue(batch.isComplete)
        assertEquals(listOf(45), batch.items.map { it.id })
        assertEquals(2, transport.requestBody!!.getJSONObject("variables").getJSONArray("ids").length())
        assertEquals(2, transport.requestBody!!.getJSONObject("variables").getInt("perPage"))
    }

    @Test
    fun idBatchRetainsValidRowsButMarksTheBatchIncompleteAfterGraphQlError() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"errors":[{"message":"Partial resolver failure","path":["Page","media",0]}],"data":{"Page":{"media":[{"id":51,"title":{"english":"Valid result"}}]}}}""",
            ),
        )
        val result = ReiAnixAniListClient(transport, 0).getAnimeBatch(listOf(51, 52))
        assertTrue(result is AniListResult.Success)
        val batch = (result as AniListResult.Success).value
        assertEquals(listOf(51), batch.items.map { it.id })
        assertFalse(batch.isComplete)
        assertEquals(setOf("ids"), batch.incompleteKeys)
    }

    @Test
    fun titleBatchRejectsMoreThanFifteenUniqueTermsWithoutNetworkRequest() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{}}"""))
        val result = ReiAnixAniListClient(transport, 0)
            .searchAnimeBatch((1..16).map { "Anime title $it" })
        assertTrue(result is AniListResult.Failure)
        assertTrue((result as AniListResult.Failure).error is AniListError.InvalidRequest)
        assertEquals(0, transport.calls)
    }

    private class SequenceFakeTransport(
        private val responses: List<AniListHttpResponse>,
    ) : AniListTransport {
        var calls: Int = 0

        override suspend fun post(endpoint: String, body: JSONObject): AniListHttpResponse {
            val response = responses.getOrElse(calls) { responses.last() }
            calls++
            return response
        }
    }

    private class FakeTransport(
        private val response: AniListHttpResponse,
    ) : AniListTransport {
        var calls: Int = 0
        var endpoint: String? = null
        var requestBody: JSONObject? = null

        override suspend fun post(endpoint: String, body: JSONObject): AniListHttpResponse {
            calls++
            this.endpoint = endpoint
            requestBody = body
            return response
        }
    }

    @Test
    fun malformedAniListFieldTypesAreReportedAsInvalidResponse() = runBlocking {
        val malformedId = ReiAnixAniListClient(
            FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[{"id":1.5,"title":{}}]}}}""")),
            0,
        ).searchAnime("anime")
        assertTrue(malformedId is AniListResult.Failure)
        assertTrue((malformedId as AniListResult.Failure).error is AniListError.InvalidResponse)

        val malformedGenres = ReiAnixAniListClient(
            FakeTransport(AniListHttpResponse(200, """{"data":{"Media":{"id":7,"title":{},"genres":"Action"}}}""")),
            0,
        ).getAnime(7)
        assertTrue(malformedGenres is AniListResult.Failure)
        assertTrue((malformedGenres as AniListResult.Failure).error is AniListError.InvalidResponse)

        val fractionalEpisodes = ReiAnixAniListClient(
            FakeTransport(AniListHttpResponse(200, """{"data":{"Media":{"id":7,"title":{},"episodes":12.5}}}""")),
            0,
        ).getAnime(7)
        assertTrue(fractionalEpisodes is AniListResult.Failure)
        assertTrue((fractionalEpisodes as AniListResult.Failure).error is AniListError.InvalidResponse)

        val malformedGenreItem = ReiAnixAniListClient(
            FakeTransport(AniListHttpResponse(200, """{"data":{"Media":{"id":7,"title":{},"genres":["Action",4]}}}""")),
            0,
        ).getAnime(7)
        assertTrue(malformedGenreItem is AniListResult.Failure)
        assertTrue((malformedGenreItem as AniListResult.Failure).error is AniListError.InvalidResponse)
    }

}
