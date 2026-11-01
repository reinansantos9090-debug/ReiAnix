package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReiAnixKitsuCatalogProviderTest {
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
    fun searchParsesJsonApiTitlesMetadataRatingsAndArtworkVariants() = runBlocking {
        val transport = FakeTransport(
            ReiAnixCatalogHttpResponse(200, """
                {
                  "data": [{
                    "id": "kitsu-42",
                    "type": "anime",
                    "attributes": {
                      "canonicalTitle": "Sample Anime",
                      "titles": {
                        "en": "Sample Anime",
                        "en_jp": "Sample Anime Japanese",
                        "ja_jp": "サンプルアニメ"
                      },
                      "abbreviatedTitles": ["Sample"],
                      "synopsis": "A complete sample synopsis for the catalog.",
                      "subtype": "TV",
                      "startDate": "2020-04-01",
                      "endDate": "2020-06-20",
                      "episodeCount": 12,
                      "episodeLength": 24,
                      "status": "finished",
                      "averageRating": "83.5",
                      "ageRatingGuide": "PG",
                      "posterImage": {
                        "tiny": "https://media.kitsu.io/tiny.jpg",
                        "large": "https://media.kitsu.io/large.jpg",
                        "original": "https://media.kitsu.io/original.jpg"
                      },
                      "coverImage": {
                        "large": "https://media.kitsu.io/cover-large.jpg",
                        "original": "https://media.kitsu.io/cover-original.jpg"
                      }
                    }
                  }]
                }
            """.trimIndent(), mapOf("Cache-Control" to "max-age=120")),
        )
        val provider = ReiAnixKitsuCatalogProvider(transport)

        val result = provider.searchAnime("Sample Anime & More", 8)
        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value.single()
        assertEquals(ReiAnixCatalogSource.KITSU, anime.source)
        assertEquals("kitsu-42", anime.ids.kitsuId)
        assertEquals("Sample Anime", anime.title)
        assertTrue("Sample Anime Japanese" in anime.alternativeTitles)
        assertTrue("サンプルアニメ" in anime.alternativeTitles)
        assertTrue("Sample" in anime.alternativeTitles)
        assertEquals("A complete sample synopsis for the catalog.", anime.synopsis)
        assertEquals(2020, anime.releaseYear)
        assertEquals("TV", anime.format)
        assertEquals(ReiAnixCatalogMediaKind.SERIES, anime.mediaKind)
        assertEquals(12, anime.episodes)
        assertEquals(24, anime.durationMinutes)
        assertEquals("finished", anime.status)
        assertEquals(83, anime.averageScore)
        assertEquals("PG", anime.contentRating)
        assertEquals("2020-04-01", anime.startDate)
        assertEquals("2020-06-20", anime.endDate)
        assertEquals("https://media.kitsu.io/original.jpg", anime.coverUrl)
        assertEquals("posterImage", anime.coverImageField)
        assertNull(anime.backdropUrl)
        assertEquals("https://media.kitsu.io/cover-original.jpg", anime.bannerUrl)
        assertEquals("coverImage", anime.bannerImageField)
        assertEquals("original", anime.bannerVariant)
        assertTrue(anime.unclassifiedArtworkFields.isEmpty())
        val candidates = anime.artworkCandidates()
        assertEquals(listOf("poster", "banner"), candidates.map { it.type.key })
        assertEquals(listOf("posterImage", "coverImage"), candidates.map { it.originalField })
        assertTrue(transport.requestedUrl.orEmpty().contains("filter%5Btext%5D=Sample%20Anime%20%26%20More"))
        assertTrue(transport.requestedUrl.orEmpty().contains("page%5Blimit%5D=8"))
        assertTrue(transport.accept.orEmpty().contains("application/vnd.api+json"))
        val success = result as ReiAnixCatalogResult.Success
        assertTrue(success.expiresAtMillis!! > success.fetchedAtMillis)
    }

    @Test
    fun detailReadsPublicKitsuIdAndNullableFieldsWithoutInventingMetadata() = runBlocking {
        val transport = FakeTransport(
            ReiAnixCatalogHttpResponse(
                200,
                """{"data":{"id":"abc_123","type":"anime","attributes":{"canonicalTitle":"Bare Title","posterImage":null,"coverImage":null}}}""",
                emptyMap(),
            ),
        )
        val result = ReiAnixKitsuCatalogProvider(transport).getAnime("abc_123")
        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value!!
        assertEquals("abc_123", anime.ids.kitsuId)
        assertEquals("Bare Title", anime.title)
        assertNull(anime.synopsis)
        assertNull(anime.releaseYear)
        assertNull(anime.episodes)
        assertNull(anime.coverUrl)
        assertNull(anime.backdropUrl)
        assertNull(anime.bannerUrl)
        assertTrue(anime.artworkCandidates().isEmpty())
        assertTrue(transport.requestedUrl.orEmpty().endsWith("/anime/abc_123"))
    }

    @Test
    fun emptySearchIsSuccessfulWithoutNetworkAndInvalidIdIsRejected() = runBlocking {
        val transport = FakeTransport(ReiAnixCatalogHttpResponse(200, """{"data":[]}""", emptyMap()))
        val provider = ReiAnixKitsuCatalogProvider(transport)
        val empty = provider.searchAnime("  ", 10)
        assertTrue(empty is ReiAnixCatalogResult.Success)
        assertTrue((empty as ReiAnixCatalogResult.Success).value.isEmpty())
        assertEquals(0, transport.calls)

        val invalid = provider.getAnime("../1")
        assertTrue(invalid is ReiAnixCatalogResult.Failure)
        assertEquals(0, transport.calls)
    }

    @Test
    fun httpErrorsRetryAfterAndMalformedJsonAreReturnedAsFailures() = runBlocking {
        val rateLimitedTransport = FakeTransport(
            ReiAnixCatalogHttpResponse(429, """{"errors":[{"title":"Slow down"}]}""", mapOf("Retry-After" to "2")),
        )
        val limited = ReiAnixKitsuCatalogProvider(rateLimitedTransport).searchAnime("Sample", 5)
        assertTrue(limited is ReiAnixCatalogResult.Failure)
        assertEquals(2_000L, (limited as ReiAnixCatalogResult.Failure).error.let {
            (it as ReiAnixCatalogError.RateLimited).retryAfterMillis
        })

        val missing = ReiAnixKitsuCatalogProvider(
            FakeTransport(ReiAnixCatalogHttpResponse(503, "temporarily unavailable", emptyMap())),
        ).getAnime("42")
        assertEquals(ReiAnixCatalogError.Server(503), (missing as ReiAnixCatalogResult.Failure).error)

        val malformed = ReiAnixKitsuCatalogProvider(
            FakeTransport(ReiAnixCatalogHttpResponse(200, "{broken", emptyMap())),
        ).searchAnime("Sample", 5)
        assertTrue(malformed is ReiAnixCatalogResult.Failure)
        assertTrue((malformed as ReiAnixCatalogResult.Failure).error is ReiAnixCatalogError.InvalidResponse)
    }
    @Test
    fun invalidImageUrlsAreOmittedWithoutRejectingKitsuMetadata() = runBlocking {
        val transport = FakeTransport(
            ReiAnixCatalogHttpResponse(
                200,
                """{"data":[{"id":"kitsu-77","type":"anime","attributes":{"canonicalTitle":"Valid metadata","posterImage":{"original":"javascript:alert(1)","large":"https://media.kitsu.io/poster.jpg"},"coverImage":{"original":"https://user:pass@media.kitsu.io/banner.jpg"}}}]}""",
                emptyMap(),
            ),
        )

        val result = ReiAnixKitsuCatalogProvider(transport).searchAnime("Valid metadata", 5)

        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value.single()
        assertEquals("Valid metadata", anime.title)
        assertEquals("https://media.kitsu.io/poster.jpg", anime.coverUrl)
        assertNull(anime.bannerUrl)
        assertNull(anime.backdropUrl)
    }

}
