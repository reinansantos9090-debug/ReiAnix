package com.reianix.reianix_local.data.anilist

import com.reianix.reianix_local.data.artwork.ReiAnixAniListCoverCandidate
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
        assertFalse(query.contains("relations {"))
    }

    @Test
    fun repairSearchDeduplicatesAliasesAndResultsInOneHttpRequest() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """
            {"data":{"alias0":{"media":[{"id":42,"title":{"romaji":"Example"}}]},
                     "alias1":{"media":[{"id":42,"title":{"romaji":"Example"}},{"id":43,"title":{"romaji":"Other"}}]}}}
        """.trimIndent()))
        val result = ReiAnixAniListClient(transport, 0).searchAnimeAliases(listOf("Example", "Alternative", "Example"))
        assertTrue(result is AniListResult.Success)
        assertEquals(listOf(42, 43), (result as AniListResult.Success).value.map { it.id })
        assertEquals(1, transport.calls)
        val body = transport.requestBody!!
        assertEquals("Example", body.getJSONObject("variables").getString("term0"))
        assertEquals("Alternative", body.getJSONObject("variables").getString("term1"))
        assertTrue(body.getString("query").contains("alias0: Page"))
        assertTrue(body.getString("query").contains("alias1: Page"))
        assertTrue(body.getString("query").contains("format_not: MUSIC"))
        assertTrue(body.getString("query").contains("perPage: 20"))
        assertTrue(body.getString("query").contains("relationType(version: 2)"))

        // Compare batched search with the original repair for one anime, while
        // sharing an alias with another anime. Neither may see unrelated results.
        val groupedTransport = FakeTransport(AniListHttpResponse(200, """
            {"data":{
              "alias0":{"media":[{"id":42,"title":{"romaji":"Example"}}]},
              "alias1":{"media":[{"id":42,"title":{"romaji":"Example"}},{"id":43,"title":{"romaji":"Other"}}]},
              "alias2":{"media":[{"id":90,"title":{"romaji":"Different Anime II"}}]},
              "alias3":{"media":[{"id":91,"isAdult":true}]}
            }}
        """.trimIndent()))
        val grouped = ReiAnixAniListClient(groupedTransport, 0).searchUpgradeAliasesByTerm(
            listOf("Example", "Alternative", "Different Anime II", "Empty", "Alternative"),
        )
        assertTrue(grouped is AniListResult.Success)
        val byTerm = (grouped as AniListResult.Success).value
        assertEquals(listOf("Example", "Alternative", "Different Anime II", "Empty"), byTerm.keys.toList())
        assertEquals(listOf(42, 43), orderedUpgradeCandidates(listOf("Example", "Alternative", "Example"), byTerm)?.map { it.id })
        assertEquals(listOf(90, 42, 43), orderedUpgradeCandidates(listOf("Different Anime II", "Alternative"), byTerm)?.map { it.id })
        assertEquals(emptyList<AniListAnime>(), orderedUpgradeCandidates(listOf("Empty"), byTerm))
        assertEquals(null, orderedUpgradeCandidates(listOf("Example", "Missing"), byTerm))
        assertEquals(1, groupedTransport.calls)
        assertEquals(4, groupedTransport.requestBody!!.getJSONObject("variables").length())
        assertTrue(groupedTransport.requestBody!!.getString("query").contains("alias3: Page"))
        assertTrue(groupedTransport.requestBody!!.getString("query").contains("perPage: 20"))

        // The automatic path permits grouping more than eight aliases while
        // a single anime's original eight-term ranking budget remains unchanged.
        val terms = (0 until 16).map { "Term $it" }
        val pages = JSONObject()
        terms.indices.forEach { i -> pages.put("alias$i", JSONObject().put("media", org.json.JSONArray())) }
        val wideTransport = FakeTransport(AniListHttpResponse(200, JSONObject().put("data", pages).toString()))
        val wide = ReiAnixAniListClient(wideTransport, 0).searchUpgradeAliasesByTerm(terms)
        assertEquals(16, (wide as AniListResult.Success).value.size)
        assertEquals(1, wideTransport.calls)
        assertEquals(16, wideTransport.requestBody!!.getJSONObject("variables").length())
    }

    @Test
    fun repairSearchRejectsPartialAliasResponse() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"alias0":{"media":[]}}}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnimeAliases(listOf("Original", "Alternative"))
        assertTrue(result is AniListResult.Failure)
        assertEquals(1, transport.calls)

        val partial = FakeTransport(AniListHttpResponse(200, """{"data":{"alias0":{"media":[]}}}"""))
        val partialResult = ReiAnixAniListClient(partial, 0).searchUpgradeAliasesByTerm(listOf("Original", "Alternative"))
        assertTrue(partialResult is AniListResult.Failure)
        val limited = FakeTransport(AniListHttpResponse(429, "{}", 30))
        val limitedResult = ReiAnixAniListClient(limited, 0).searchUpgradeAliasesByTerm(listOf("Original", "Alternative"))
        assertEquals(AniListError.RateLimited(30), (limitedResult as AniListResult.Failure).error)

        val relationTransport = FakeTransport(AniListHttpResponse(200, """
            {"data":{"Page":{"media":[{"id":43,"title":{"romaji":"Example II"},"format":"TV",
              "relations":{"edges":[{"relationType":"PREQUEL","node":{"id":42,"format":"TV","title":{"romaji":"Example"}}}]}}]}}}
        """.trimIndent()))
        val related = ReiAnixAniListClient(relationTransport, 0).lookupRepairRelatedAnime(listOf(43, 43))
        assertTrue(related is AniListResult.Success)
        assertEquals(42, (related as AniListResult.Success).value.single().repairRelations?.single()?.id)
        val relationBody = relationTransport.requestBody!!
        assertEquals(43, relationBody.getJSONObject("variables").getJSONArray("ids").getInt(0))
        assertEquals(1, relationBody.getJSONObject("variables").getJSONArray("ids").length())
        assertTrue(relationBody.getString("query").contains("id_in: " + '$' + "ids"))
    }

    @Test
    fun idLookupSendsLiteralIdVariableAndExpectedJsonValue() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Media":{"id":42,"title":{}}}}"""))
        val result = ReiAnixAniListClient(transport, 0).getAnime(42)

        assertTrue(result is AniListResult.Success)
        assertEquals(42, (result as AniListResult.Success).value?.id)
        val body = transport.requestBody!!
        assertTrue(body.getString("query").contains("\$id"))
        assertTrue(body.getString("query").contains("Media(id: \$id, type: ANIME, isAdult: false)"))
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
    fun explicitlyAdultSearchResultsAreExcluded() = runBlocking {
        val transport = FakeTransport(AniListHttpResponse(200, """{"data":{"Page":{"media":[{"id":17,"isAdult":true,"title":{"romaji":"Blocked"}},{"id":18,"isAdult":false,"title":{"romaji":"Allowed"}}]}}}"""))
        val result = ReiAnixAniListClient(transport, 0).searchAnime("sample")
        assertTrue(result is AniListResult.Success)
        assertEquals(listOf(18), (result as AniListResult.Success).value.map { it.id })
        assertTrue(transport.requestBody!!.getString("query").contains("isAdult: false"))
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
