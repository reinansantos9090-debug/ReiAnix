package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReiAnixAniPubCatalogProviderTest {
    private class FakeTransport(
        private val responses: List<ReiAnixCatalogHttpResponse>,
    ) : ReiAnixCatalogGetTransport {
        val urls = mutableListOf<String>()
        var calls = 0

        override suspend fun get(url: String, accept: String): ReiAnixCatalogHttpResponse {
            urls += url
            calls++
            return responses[(calls - 1).coerceAtMost(responses.lastIndex)]
        }
    }

    @Test
    fun searchLoadsDocumentedFullDetailsAndMapsPosterBannerAndJikanIdentity() = runBlocking {
        val transport = FakeTransport(
            listOf(
                ReiAnixCatalogHttpResponse(
                    200,
                    """[{"Name":"Sample Anime","Id":91,"Image":"Sample.jpg","finder":"sample-anime"}]""",
                    emptyMap(),
                ),
                ReiAnixCatalogHttpResponse(
                    200,
                    """
                    {
                      "local": {
                        "_id": 91,
                        "Name": "Sample Anime",
                        "ImagePath": "images/sample.jpg",
                        "Cover": "covers/sample-banner.jpg",
                        "Synonyms": "Sample Alias",
                        "Aired": "Oct 3, 2016 to Mar 30, 2017",
                        "DescripTion": "A complete AniPub synopsis for the sample anime.",
                        "Duration": "24m",
                        "MALScore": "8.6",
                        "Status": "Finished Airing",
                        "epCount": 12
                      },
                      "jikan": {
                        "mal_id": 321,
                        "title": "Sample Anime",
                        "type": "TV",
                        "episodes": 12,
                        "score": 8.5,
                        "aired": {"from": "2016-10-03T00:00:00+00:00"},
                        "synopsis": "Jikan synopsis"
                      },
                      "characters": []
                    }
                    """.trimIndent(),
                    emptyMap(),
                ),
            ),
        )
        val provider = ReiAnixAniPubCatalogProvider(transport)

        val result = provider.searchAnime("Sample Anime", 5)

        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value.single()
        assertEquals(ReiAnixCatalogSource.ANIPUB, anime.source)
        assertEquals("91", anime.ids.additionalIds["anipub"])
        assertEquals(321L, anime.ids.myAnimeListId)
        assertEquals("Sample Anime", anime.title)
        assertTrue("Sample Alias" in anime.alternativeTitles)
        assertEquals(2016, anime.releaseYear)
        assertEquals("TV", anime.format)
        assertEquals(ReiAnixCatalogMediaKind.SERIES, anime.mediaKind)
        assertEquals(12, anime.episodes)
        assertEquals(24, anime.durationMinutes)
        assertEquals(86, anime.averageScore)
        assertEquals("A complete AniPub synopsis for the sample anime.", anime.synopsis)
        assertEquals("https://anipub.org/images/sample.jpg", anime.coverUrl)
        assertEquals("https://anipub.org/covers/sample-banner.jpg", anime.backdropUrl)
        assertEquals("https://anipub.org/api/search/Sample%20Anime", transport.urls[0])
        assertEquals("https://anipub.org/anime/api/details/91", transport.urls[1])
    }

    @Test
    fun infoFallbackReturnsOnlyActuallyAvailableFieldsAndDoesNotInventFormat() = runBlocking {
        val transport = FakeTransport(
            listOf(
                ReiAnixCatalogHttpResponse(
                    503,
                    "temporarily unavailable",
                    emptyMap(),
                ),
                ReiAnixCatalogHttpResponse(
                    200,
                    """
                    {
                      "_id": 61,
                      "Name": "Black Clover",
                      "ImagePath": "Black-Clover.jpg",
                      "Cover": "Cover/Black-Clover.jpg",
                      "Aired": "Oct 3, 2017 to Mar 30, 2021",
                      "Premiered": "Fall 2017",
                      "DescripTion": "A documented synopsis from AniPub.",
                      "Duration": "25m",
                      "MALScore": "8.88",
                      "Status": "Finished Airing",
                      "epCount": 170
                    }
                    """.trimIndent(),
                    emptyMap(),
                ),
            ),
        )

        val result = ReiAnixAniPubCatalogProvider(transport).getAnime("61")

        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value!!
        assertEquals("Black Clover", anime.title)
        assertEquals(2017, anime.releaseYear)
        assertEquals(170, anime.episodes)
        assertEquals(89, anime.averageScore)
        assertEquals("https://anipub.org/Black-Clover.jpg", anime.coverUrl)
        assertEquals("https://anipub.org/Cover/Black-Clover.jpg", anime.backdropUrl)
        assertNull(anime.format)
        assertNull(anime.mediaKind)
        assertEquals("https://anipub.org/api/info/61", transport.urls.last())
    }

    @Test
    fun invalidIdsAndBadHttpOrJsonResponsesAreFailures() = runBlocking {
        val transport = FakeTransport(listOf(ReiAnixCatalogHttpResponse(503, "down", emptyMap())))
        val provider = ReiAnixAniPubCatalogProvider(transport)

        val invalid = provider.getAnime("../91")
        assertTrue(invalid is ReiAnixCatalogResult.Failure)
        assertEquals(0, transport.calls)

        val unavailable = provider.getAnime("91")
        assertEquals(ReiAnixCatalogError.Server(503), (unavailable as ReiAnixCatalogResult.Failure).error)

        val malformed = ReiAnixAniPubCatalogProvider(
            FakeTransport(listOf(ReiAnixCatalogHttpResponse(200, "not-json", emptyMap()))),
        ).searchAnime("Anime", 5)
        assertTrue(malformed is ReiAnixCatalogResult.Failure)
    }
}
