package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.artwork.ReiAnixAniListCoverCandidate
import android.app.Application
import org.robolectric.annotation.Config

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReiAnixAniListClientTest {
    @Test
    fun parsesTypedAnimeMetadataAndAliases() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """
            {
              "data": {"Page": {"media": [{
                "id": 16498,
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
    fun cleartextExtraLargeCoverDoesNotHideSecureLargeVariant() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """
            {"data":{"Media":{
              "id":16498,
              "title":{"romaji":"Shingeki no Kyojin"},
              "coverImage":{
                "large":"https://img.example/secure-large.jpg",
                "extraLarge":"http://img.example/cleartext-extra-large.jpg"
              }
            }}}
        """.trimIndent()))

        val result = ReiAnixAniListClient(transport, 0).getAnime(16498)
        assertTrue(result is AniListResult.Success)
        val anime = (result as AniListResult.Success).value!!

        assertEquals(null, anime.coverExtraLargeUrl)
        assertEquals("https://img.example/secure-large.jpg", anime.coverLargeUrl)
        assertEquals(
            listOf(ReiAnixAniListCoverCandidate("large", "https://img.example/secure-large.jpg")),
            anime.coverCandidates,
        )
    }

    @Test
    fun cleartextOnlyCoverVariantsAreDiscarded() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """
            {"data":{"Media":{
              "id":16498,
              "title":{"romaji":"Shingeki no Kyojin"},
              "coverImage":{
                "large":"http://img.example/cleartext-large.jpg",
                "extraLarge":"http://img.example/cleartext-extra-large.jpg"
              }
            }}}
        """.trimIndent()))

        val result = ReiAnixAniListClient(transport, 0).getAnime(16498)
        assertTrue(result is AniListResult.Success)
        val anime = (result as AniListResult.Success).value!!
        assertEquals(null, anime.coverLargeUrl)
        assertEquals(null, anime.coverExtraLargeUrl)
        assertTrue(anime.coverCandidates.isEmpty())
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
    fun http429IncludesRetryAfter() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(429, "{}", 30))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("anime")
        assertEquals(AniListError.RateLimited(30), (result as AniListResult.Failure).error)
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
    fun invalidSearchMediaEntriesAreRejectedInsteadOfSilentlyDropped() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[{"title":{}}]}}}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("anime")
        assertTrue(result is AniListResult.Failure)
        assertTrue((result as AniListResult.Failure).error is AniListError.InvalidResponse)
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
    fun batchSearchQueriesEveryTitleAliasInOneGraphQlRequest() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"data":{
                    "search0":{"media":[{"id":101,"title":{"romaji":"First Work"},"coverImage":{"extraLarge":"https://img.example/first.jpg"}}]},
                    "search1":{"media":[{"id":202,"title":{"english":"Second Work"},"coverImage":{"large":"https://img.example/second.jpg"}}]}
                }}""".trimIndent(),
                rateLimitLimit = 30,
                rateLimitRemaining = 29,
            ),
        )

        val result = ReiAnixAniListClient(transport, 0).searchAnimeBatch(
            listOf("First Work", "Second Work", " first work "),
        )

        assertTrue(result is AniListResult.Success)
        val byTitle = (result as AniListResult.Success).value
        assertEquals(setOf("First Work", "Second Work"), byTitle.keys)
        assertEquals(101, byTitle.getValue("First Work").single().id)
        assertEquals(202, byTitle.getValue("Second Work").single().id)
        assertEquals(1, transport.calls)

        val request = requireNotNull(transport.requestBody)
        val query = request.getString("query")
        val variables = request.getJSONObject("variables")
        assertTrue(query.contains("search0: Page"))
        assertTrue(query.contains("search1: Page"))
        assertFalse(query.contains("search2: Page"))
        assertEquals("First Work", variables.getString("term0"))
        assertEquals("Second Work", variables.getString("term1"))
    }


    @Test
    fun batchArtworkSearchKeepsSuccessfulAliasesWhenAnotherAliasReturnsGraphQlError() = runBlocking {
        val transport = FakeTransport(
            AniListHttpResponse(
                200,
                """{"errors":[{"message":"One alias failed"}],"data":{
                    "search0":{"media":[{"id":101,"title":{"romaji":"First Work"},"coverImage":{"large":"https://img.example/first.jpg"}}]},
                    "search1":null
                }}""".trimIndent(),
            ),
        )

        val result = ReiAnixAniListClient(transport, 0).searchAnimeBatch(
            listOf("First Work", "Second Work"),
            includeFullMetadata = false,
        )

        assertTrue(result is AniListResult.Success)
        val byTitle = (result as AniListResult.Success).value
        assertEquals(setOf("First Work"), byTitle.keys)
        assertEquals("https://img.example/first.jpg", byTitle.getValue("First Work").single().coverLargeUrl)

        val query = requireNotNull(transport.requestBody).getString("query")
        assertTrue(query.contains("coverImage"))
        assertFalse(query.contains("description(asHtml"))
        assertFalse(query.contains("studios(isMain"))
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
