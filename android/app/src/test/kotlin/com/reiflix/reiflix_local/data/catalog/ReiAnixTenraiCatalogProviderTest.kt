package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReiAnixTenraiCatalogProviderTest {
    private class FakeTransport(
        var response: ReiAnixCatalogHttpResponse,
    ) : ReiAnixCatalogGetTransport {
        var requestedUrl: String? = null
        var accept: String? = null
        var calls: Int = 0

        override suspend fun get(url: String, accept: String): ReiAnixCatalogHttpResponse {
            requestedUrl = url
            this.accept = accept
            calls++
            return response
        }
    }

    @Test
    fun searchParsesMalIdTitlesSynopsisDatesRatingsAndImages() = runBlocking {
        val transport = FakeTransport(
            ReiAnixCatalogHttpResponse(200, """
                {
                  "data": [{
                    "mal_id": 301,
                    "title": "Example Anime",
                    "title_english": "Example Anime English",
                    "title_japanese": "例アニメ",
                    "title_synonyms": ["Example Alias"],
                    "titles": [{"type":"French","title":"Anime Exemple"}],
                    "type": "TV",
                    "episodes": 12,
                    "status": "Finished Airing",
                    "aired": {
                      "from": "2021-01-10T00:00:00+00:00",
                      "to": "2021-03-28T00:00:00+00:00"
                    },
                    "duration": "1 hr 24 min per ep",
                    "rating": "PG-13 - Teens 13 or older",
                    "score": 8.2,
                    "synopsis": "A complete example synopsis from the public catalog.",
                    "images": {
                      "webp": {
                        "image_url": "https://cdn.myanimelist.net/images/301.webp",
                        "large_image_url": "https://cdn.myanimelist.net/images/301-large.webp"
                      },
                      "jpg": {"image_url":"https://cdn.myanimelist.net/images/301.jpg"}
                    }
                  }]
                }
            """.trimIndent(), mapOf("Cache-Control" to "max-age=300")),
        )
        val provider = ReiAnixTenraiCatalogProvider(transport)

        val result = provider.searchAnime("Example Anime", 10)
        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value.single()
        assertEquals(ReiAnixCatalogSource.TENRAI, anime.source)
        assertEquals(301L, anime.ids.myAnimeListId)
        assertEquals("Example Anime", anime.title)
        assertTrue("Example Anime English" in anime.alternativeTitles)
        assertTrue("例アニメ" in anime.alternativeTitles)
        assertTrue("Example Alias" in anime.alternativeTitles)
        assertTrue("Anime Exemple" in anime.alternativeTitles)
        assertEquals("A complete example synopsis from the public catalog.", anime.synopsis)
        assertEquals("TV", anime.format)
        assertEquals(ReiAnixCatalogMediaKind.SERIES, anime.mediaKind)
        assertEquals(12, anime.episodes)
        assertEquals("Finished Airing", anime.status)
        assertEquals(84, anime.durationMinutes)
        assertEquals(82, anime.averageScore)
        assertEquals("PG-13 - Teens 13 or older", anime.contentRating)
        assertEquals(2021, anime.releaseYear)
        assertEquals("https://cdn.myanimelist.net/images/301-large.webp", anime.coverUrl)
        assertNull(anime.backdropUrl)
        assertNull(anime.bannerUrl)
        assertEquals(listOf("poster"), anime.artworkCandidates().map { it.type.key })
        assertEquals(
            "images.webp.large_image_url",
            anime.artworkCandidates().single().originalField,
        )
        assertTrue(transport.requestedUrl.orEmpty().startsWith("https://api.tenrai.org/v1/anime?q=Example%20Anime"))
        assertTrue(transport.requestedUrl.orEmpty().contains("limit=10"))
        assertTrue(transport.requestedUrl.orEmpty().contains("sfw=true"))
        assertEquals("application/json", transport.accept)
        val success = result as ReiAnixCatalogResult.Success
        assertTrue(success.expiresAtMillis!! > success.fetchedAtMillis)
    }

    @Test
    fun detailAndMissingOptionalFieldsAreParsedWithoutInventingValues() = runBlocking {
        val transport = FakeTransport(
            ReiAnixCatalogHttpResponse(
                200,
                """{"data":{"mal_id":77,"title":"Bare Anime","type":"Movie","images":{"jpg":{"image_url":"https://cdn.myanimelist.net/77.jpg"}}}}""",
                emptyMap(),
            ),
        )
        val result = ReiAnixTenraiCatalogProvider(transport).getAnime("77")
        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value!!
        assertEquals(77L, anime.ids.myAnimeListId)
        assertEquals("Bare Anime", anime.title)
        assertNull(anime.backdropUrl)
        assertNull(anime.bannerUrl)
        assertEquals(listOf("poster"), anime.artworkCandidates().map { it.type.key })
        assertEquals("MOVIE", anime.format)
        assertEquals(ReiAnixCatalogMediaKind.MOVIE, anime.mediaKind)
        assertNull(anime.synopsis)
        assertNull(anime.releaseYear)
        assertNull(anime.episodes)
        assertNull(anime.averageScore)
        assertTrue(transport.requestedUrl.orEmpty().endsWith("/anime/77"))
    }

    @Test
    fun emptyDataAndInvalidIdsDoNotMakeUnexpectedRequests() = runBlocking {
        val transport = FakeTransport(ReiAnixCatalogHttpResponse(200, """{"data":[]}""", emptyMap()))
        val provider = ReiAnixTenraiCatalogProvider(transport)
        val empty = provider.searchAnime("  ", 10)
        assertTrue(empty is ReiAnixCatalogResult.Success)
        assertTrue((empty as ReiAnixCatalogResult.Success).value.isEmpty())
        assertEquals(0, transport.calls)

        val invalid = provider.getAnime("0")
        assertTrue(invalid is ReiAnixCatalogResult.Failure)
        assertEquals(0, transport.calls)
    }

    @Test
    fun rateLimitsServerErrorsAndInvalidJsonRemainObservable() = runBlocking {
        val limited = ReiAnixTenraiCatalogProvider(
            FakeTransport(ReiAnixCatalogHttpResponse(429, """{"message":"Too Many Requests"}""", mapOf("Retry-After" to "3"))),
        ).searchAnime("Anime", 5)
        assertTrue(limited is ReiAnixCatalogResult.Failure)
        assertEquals(3_000L, (((limited as ReiAnixCatalogResult.Failure).error) as ReiAnixCatalogError.RateLimited).retryAfterMillis)

        val unavailable = ReiAnixTenraiCatalogProvider(
            FakeTransport(ReiAnixCatalogHttpResponse(503, "service unavailable", emptyMap())),
        ).getAnime("15")
        assertEquals(ReiAnixCatalogError.Server(503), (unavailable as ReiAnixCatalogResult.Failure).error)

        val malformed = ReiAnixTenraiCatalogProvider(
            FakeTransport(ReiAnixCatalogHttpResponse(200, "not-json", emptyMap())),
        ).searchAnime("Anime", 5)
        assertTrue(malformed is ReiAnixCatalogResult.Failure)
        assertTrue((malformed as ReiAnixCatalogResult.Failure).error is ReiAnixCatalogError.InvalidResponse)
    }
    @Test
    fun invalidPosterUrlsAreIgnoredWithoutDiscardingTenraiMetadata() = runBlocking {
        val transport = FakeTransport(
            ReiAnixCatalogHttpResponse(
                200,
                """{"data":[{"mal_id":707,"title":"Metadata only","type":"TV","images":{"webp":{"large_image_url":"file:///tmp/poster.jpg"}}}]}""",
                emptyMap(),
            ),
        )

        val result = ReiAnixTenraiCatalogProvider(transport).searchAnime("Metadata only", 5)

        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value.single()
        assertEquals("Metadata only", anime.title)
        assertEquals(707L, anime.ids.myAnimeListId)
        assertNull(anime.coverUrl)
        assertNull(anime.bannerUrl)
        assertNull(anime.backdropUrl)
    }

    @Test
    fun malformedSearchEntryDoesNotProduceAnIncompleteSuccessList() = runBlocking {
        val transport = FakeTransport(
            ReiAnixCatalogHttpResponse(
                200,
                """{"data":[{"mal_id":1,"title":"Valid work"},{"title":"Missing MAL ID"}]}""",
                emptyMap(),
            ),
        )

        val result = ReiAnixTenraiCatalogProvider(transport).searchAnime("work", 5)

        assertTrue(result is ReiAnixCatalogResult.Failure)
    }

}
