package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReiAnixAniMapCatalogIdResolverTest {
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
    fun resolvesExistingAniListIdIntoVerifiedCrossCatalogMappingAndCachesIt() = runBlocking {
        val transport = FakeTransport(
            listOf(
                ReiAnixCatalogHttpResponse(
                    200,
                    """
                    {
                      "sources": [
                        "https://anidb.net/anime/23",
                        "https://anilist.co/anime/1",
                        "https://kitsu.app/anime/1",
                        "https://myanimelist.net/anime/1",
                        "https://www.animenewsnetwork.com/encyclopedia/anime.php?id=13"
                      ],
                      "title": "Cowboy Bebop",
                      "type": "TV",
                      "episodes": 26,
                      "animeSeason": {"season": "SPRING", "year": 1998},
                      "synonyms": ["カウボーイビバップ"]
                    }
                    """.trimIndent(),
                    mapOf("Cache-Control" to "max-age=300"),
                ),
            ),
        )
        val resolver = ReiAnixAniMapCatalogIdResolver(transport)
        val registry = ReiAnixCatalogProviderRegistry(
            providers = emptyList(),
            requestCoordinator = ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 1),
        )
        val coordinated = registry.coordinate(resolver)
        val known = ReiAnixExternalCatalogIds(aniListId = 1L)

        val first = coordinated.resolveIds(known)
        val second = coordinated.resolveIds(known)

        assertTrue(first is ReiAnixCatalogResult.Success)
        val success = first as ReiAnixCatalogResult.Success
        assertTrue(success.expiresAtMillis!! > success.fetchedAtMillis)
        assertTrue(success.expiresAtMillis!! - success.fetchedAtMillis <= 300_000L)
        val mapping = success.value!!
        assertEquals("Cowboy Bebop", mapping.title)
        assertEquals(1998, mapping.releaseYear)
        assertEquals("TV", mapping.format)
        assertEquals(ReiAnixCatalogMediaKind.SERIES, mapping.mediaKind)
        assertEquals(26, mapping.episodes)
        assertEquals(1L, mapping.ids.aniListId)
        assertEquals(1L, mapping.ids.myAnimeListId)
        assertEquals("1", mapping.ids.kitsuId)
        assertEquals(23L, mapping.ids.aniDbId)
        assertEquals("13", mapping.ids.additionalIds["ann"])
        assertTrue(second is ReiAnixCatalogResult.Success)
        assertEquals(1, transport.calls)
        assertEquals("https://animap.id/api/v1/map/anilist/1", transport.urls.single())
        registry.cancelPendingRequests()
    }

    @Test
    fun missingMappingsAndUnknownIdsDoNotCreateGuessedResults() = runBlocking {
        val transport = FakeTransport(
            listOf(ReiAnixCatalogHttpResponse(404, "not found", emptyMap())),
        )
        val resolver = ReiAnixAniMapCatalogIdResolver(transport)

        val noKnownId = resolver.resolveIds(ReiAnixExternalCatalogIds())
        val missing = resolver.resolveIds(ReiAnixExternalCatalogIds(aniListId = 999_999L))

        assertTrue(noKnownId is ReiAnixCatalogResult.Success)
        assertNull((noKnownId as ReiAnixCatalogResult.Success).value)
        assertTrue(missing is ReiAnixCatalogResult.Success)
        assertNull((missing as ReiAnixCatalogResult.Success).value)
        assertEquals(1, transport.calls)
    }

    @Test
    fun malformedAndConflictingMappingsFailClosed() = runBlocking {
        val malformed = ReiAnixAniMapCatalogIdResolver(
            FakeTransport(listOf(ReiAnixCatalogHttpResponse(200, """{"title":"No sources"}""", emptyMap()))),
        ).resolveIds(ReiAnixExternalCatalogIds(aniListId = 1L))
        assertTrue(malformed is ReiAnixCatalogResult.Failure)
        assertTrue((malformed as ReiAnixCatalogResult.Failure).error is ReiAnixCatalogError.InvalidResponse)

        val conflicting = ReiAnixAniMapCatalogIdResolver(
            FakeTransport(
                listOf(
                    ReiAnixCatalogHttpResponse(
                        200,
                        """
                        {
                          "sources": [
                            "https://anilist.co/anime/2",
                            "https://myanimelist.net/anime/22"
                          ],
                          "title": "Different Anime",
                          "type": "TV",
                          "episodes": 12,
                          "animeSeason": {"year": 2020}
                        }
                        """.trimIndent(),
                        emptyMap(),
                    ),
                ),
            ),
        ).resolveIds(ReiAnixExternalCatalogIds(aniListId = 1L))
        assertTrue(conflicting is ReiAnixCatalogResult.Failure)
        assertTrue((conflicting as ReiAnixCatalogResult.Failure).error is ReiAnixCatalogError.InvalidResponse)
    }
}
