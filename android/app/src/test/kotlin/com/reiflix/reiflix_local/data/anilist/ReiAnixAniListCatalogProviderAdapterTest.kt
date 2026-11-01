package com.reiflix.reiflix_local.data.anilist

import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogResult
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class ReiAnixAniListCatalogProviderAdapterTest {
    @Test
    fun existingRealAniListClientIsAdaptedToTheSharedCatalogContract() = runBlocking {
        val transport = AniListTransport { _, _ ->
            AniListHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "data": {
                        "Page": {
                          "media": [{
                            "id": 123,
                            "title": {
                              "romaji": "Example Anime",
                              "english": "Example Anime",
                              "native": "例アニメ"
                            },
                            "synonyms": ["Example Alias"],
                            "format": "TV",
                            "seasonYear": 2021,
                            "season": "SPRING",
                            "coverImage": {
                              "large": "https://cdn.example/large.jpg",
                              "extraLarge": "https://cdn.example/extra-large.jpg"
                            },
                            "bannerImage": "https://cdn.example/banner.jpg"
                          }]
                        }
                      }
                    }
                """.trimIndent(),
            )
        }
        val client = ReiAnixAniListClient(
            transport = transport,
            minimumRequestIntervalMs = 0L,
        )
        val adapter = ReiAnixAniListCatalogProviderAdapter(client)

        val result = adapter.searchAnime("Example Anime", 5)
        assertTrue(result is ReiAnixCatalogResult.Success)
        val anime = (result as ReiAnixCatalogResult.Success).value.single()
        assertEquals(ReiAnixCatalogSource.ANILIST, anime.source)
        assertEquals(123L, anime.ids.aniListId)
        assertEquals("Example Anime", anime.title)
        assertTrue("Example Alias" in anime.alternativeTitles)
        assertEquals(2021, anime.releaseYear)
        assertEquals("https://cdn.example/extra-large.jpg", anime.coverUrl)
        assertEquals("https://cdn.example/banner.jpg", anime.bannerUrl)
        assertNull(anime.backdropUrl)
        val candidates = anime.artworkCandidates()
        assertEquals(2, candidates.size)
        assertEquals(setOf("poster", "banner"), candidates.map { it.type.key }.toSet())
        assertEquals("bannerImage", candidates.single { it.type.key == "banner" }.originalField)
        assertEquals("anilist:123:bannerImage", candidates.single { it.type.key == "banner" }.sourceReference)
    }

    @Test
    fun invalidAniListExternalIdIsReturnedAsAStandardizedFailure() = runBlocking {
        val adapter = ReiAnixAniListCatalogProviderAdapter(
            ReiAnixAniListClient(
                transport = AniListTransport { _, _ -> error("Network must not be called for invalid IDs.") },
                minimumRequestIntervalMs = 0L,
            ),
        )
        val result = adapter.getAnime("not-an-id")
        assertTrue(result is ReiAnixCatalogResult.Failure)
    }
}
