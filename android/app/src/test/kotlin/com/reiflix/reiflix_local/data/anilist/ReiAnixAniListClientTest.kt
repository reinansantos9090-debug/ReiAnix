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
    fun blankSearchReturnsEmptyWithoutNetworkRequest() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, "{}"))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("   ")
        assertEquals(AniListResult.Success(emptyList()), result)
        assertEquals(0, transport.calls)
    }

    @Test
    fun emptyResultsAreSuccessNotNetworkFailure() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[]}}}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("does not exist")
        assertEquals(AniListResult.Success(emptyList()), result)
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
}
