package com.reiflix.reiflix_local.data.metadata

import com.reiflix.reiflix_local.data.anilist.AniListHttpResponse
import com.reiflix.reiflix_local.data.anilist.AniListTransport
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReiAnixMetadataProvidersTest {
    @Test
    fun normalizesAccentsPunctuationAndWhitespace() {
        assertEquals("cafe naruto", normalizeReiAnixMetadataTitle("  Café—NARUTO! "))
    }

    @Test
    fun buildsOnlySafePosterUrls() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            tmdbPosterImageUrl("/poster.jpg"),
        )
        assertNull(tmdbPosterImageUrl(null))
        assertNull(tmdbPosterImageUrl("poster.jpg"))
        assertNull(tmdbPosterImageUrl("//attacker.example/poster.jpg"))
        assertNull(tmdbPosterImageUrl("/../poster.jpg"))
        assertNull(tmdbPosterImageUrl("/poster.jpg?other=1"))
    }

    @Test
    fun rejectsHomonymsAndWrongSeasonTitles() {
        val query = ReiAnixMetadataQuery(title = "Demon Slayer", year = 2019, mediaKind = "tv")
        assertNotNull(rankReiAnixMetadataCandidate(query, candidate(title = "Demon Slayer", year = 2019)))
        assertNull(rankReiAnixMetadataCandidate(query, candidate(title = "Demon Slayer: Season 2", year = 2021)))
        assertNull(rankReiAnixMetadataCandidate(query, candidate(title = "Demon Slayer", year = 2009)))
    }

    @Test
    fun missingTmdbTokenSkipsProviderWithoutNetwork() = runBlocking {
        val provider = ReiAnixTmdbMetadataProvider("")
        assertFalse(provider.isConfigured)
        val resolution = ReiAnixMetadataProviderRegistry(listOf(provider))
            .search(ReiAnixMetadataQuery(title = "Cowboy Bebop"))
        assertTrue(resolution.candidates.isEmpty())
        assertTrue(resolution.failures.isEmpty())
        assertEquals(listOf("tmdb"), resolution.skippedProviders)
    }

    @Test
    fun existingAniListProviderWorksWithoutTmdbCredentials() = runBlocking {
        val payload = """
            {"data":{"Page":{"media":[{"id":123,"title":{"romaji":"Cowboy Bebop",
            "english":"Cowboy Bebop","native":"カウボーイビバップ"},"synonyms":["Cowboy Bebop"],
            "format":"TV","episodes":26,"duration":24,"status":"FINISHED",
            "averageScore":80,"season":"FALL","seasonYear":1998,"genres":["Action"],
            "coverImage":{"large":"https://s4.anilist.co/file/anime/123.jpg",
            "extraLarge":"https://s4.anilist.co/file/anime/123.jpg"},
            "bannerImage":null,"studios":{"nodes":[]}}]}}}
        """.trimIndent()
        val client = ReiAnixAniListClient(
            transport = AniListTransport { _, _ -> AniListHttpResponse(statusCode = 200, body = payload) },
            minimumRequestIntervalMs = 0L,
        )
        val registry = ReiAnixMetadataProviderRegistry(
            listOf(ReiAnixAniListMetadataProvider(client), ReiAnixTmdbMetadataProvider("")),
        )

        val result = registry.search(ReiAnixMetadataQuery(title = "Cowboy Bebop", year = 1998))

        assertEquals("anilist", result.candidates.single().providerId)
        assertEquals("123", result.candidates.single().externalId)
        assertEquals("https://s4.anilist.co/file/anime/123.jpg", result.candidates.single().posterUrl)
        assertEquals(listOf("tmdb"), result.skippedProviders)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun tmdbIsUsedWhenAniListReturnsAnHttpFailure() = runBlocking {
        val aniListClient = ReiAnixAniListClient(
            transport = AniListTransport { _, _ ->
                AniListHttpResponse(statusCode = 503, body = """{"error":"unavailable"}""")
            },
            minimumRequestIntervalMs = 0L,
        )
        val tmdb = ReiAnixTmdbMetadataProvider(
            readAccessToken = "configured-token",
            connectionFactory = { url ->
                FakeHttpConnection(
                    url,
                    200,
                    """{"results":[{"id":123,"name":"Cowboy Bebop","original_name":"Cowboy Bebop",
                    "first_air_date":"1998-10-24","poster_path":"/cowboy.jpg","adult":false}]}""",
                )
            },
        )
        val registry = ReiAnixMetadataProviderRegistry(
            listOf(ReiAnixAniListMetadataProvider(aniListClient), tmdb),
        )

        val resolution = registry.search(
            ReiAnixMetadataQuery(title = "Cowboy Bebop", year = 1998, mediaKind = "tv"),
        )

        assertEquals("tmdb", resolution.candidates.single().providerId)
        assertEquals("123", resolution.candidates.single().externalId)
        assertEquals("http_error", resolution.failures.single().category)
        assertFalse(resolution.failures.toString().contains("configured-token"))
    }

    @Test
    fun tmdbTvSearchUsesBearerAndPosterPathOnly() = runBlocking {
        val body = """
            {"results":[{"id":123,"name":"Cowboy Bebop","original_name":"Cowboy Bebop",
            "first_air_date":"1998-10-24","poster_path":"/cowboy.jpg",
            "backdrop_path":"/wide-image.jpg","adult":false}]}
        """.trimIndent()
        val reference = AtomicReference<FakeHttpConnection?>()
        val provider = ReiAnixTmdbMetadataProvider(
            readAccessToken = "test-secret-token",
            connectionFactory = { url ->
                FakeHttpConnection(url, 200, body).also(reference::set)
            },
        )

        val results = provider.search(
            ReiAnixMetadataQuery(title = "Cowboy Bebop", year = 1998, mediaKind = "tv"),
        )

        assertEquals(1, results.size)
        assertEquals("123", results.single().externalId)
        assertEquals("tmdb", results.single().providerId)
        assertEquals("https://image.tmdb.org/t/p/w500/cowboy.jpg", results.single().posterUrl)
        assertFalse(results.single().posterUrl!!.contains("wide-image"))
        val connection = reference.get()
        assertNotNull(connection)
        assertEquals("Bearer test-secret-token", connection!!.getRequestProperty("Authorization"))
        assertTrue(connection.url.path.endsWith("/3/search/tv"))
        assertTrue(connection.url.query.contains("first_air_date_year=1998"))
        assertFalse(connection.url.toString().contains("test-secret-token"))
    }

    @Test
    fun providerFailureDoesNotDiscardAnotherProvidersResultsOrLeakToken() = runBlocking {
        val token = "do-not-log-this-token"
        val failingTmdb = ReiAnixTmdbMetadataProvider(
            readAccessToken = token,
            connectionFactory = { url -> FakeHttpConnection(url, 401, """{"status_code":7}""") },
        )
        val registry = ReiAnixMetadataProviderRegistry(
            listOf(failingTmdb, FixedProvider("anilist", 400, listOf(candidate(title = "Cowboy Bebop", year = 1998, providerId = "anilist")))),
        )

        val result = registry.search(ReiAnixMetadataQuery(title = "Cowboy Bebop", year = 1998))

        assertEquals(1, result.candidates.size)
        assertEquals("anilist", result.candidates.single().providerId)
        assertEquals("auth_rejected", result.failures.single().category)
        assertFalse(result.failures.toString().contains(token))
    }

    @Test
    fun invalidJsonIsReportedAsProviderFailure() = runBlocking {
        val provider = ReiAnixTmdbMetadataProvider(
            readAccessToken = "configured-token",
            connectionFactory = { url -> FakeHttpConnection(url, 200, "{not-json") },
        )
        val result = ReiAnixMetadataProviderRegistry(listOf(provider))
            .search(ReiAnixMetadataQuery(title = "Cowboy Bebop"))

        assertTrue(result.candidates.isEmpty())
        assertEquals("invalid_json", result.failures.single().category)
    }

    @Test
    fun responseWithoutResultsArrayIsReportedAsInvalidResponse() = runBlocking {
        val provider = ReiAnixTmdbMetadataProvider(
            readAccessToken = "configured-token",
            connectionFactory = { url -> FakeHttpConnection(url, 200, """{"page":1}""") },
        )
        val result = ReiAnixMetadataProviderRegistry(listOf(provider))
            .search(ReiAnixMetadataQuery(title = "Cowboy Bebop"))

        assertTrue(result.candidates.isEmpty())
        assertEquals("invalid_response", result.failures.single().category)
    }

    @Test
    fun retriesTmdbRateLimitAndUsesSuccessfulPosterResponse() = runBlocking {
        val calls = AtomicInteger()
        val body = """{"results":[{"id":123,"name":"Cowboy Bebop","original_name":"Cowboy Bebop",
            "first_air_date":"1998-10-24","poster_path":"/cowboy.jpg","adult":false}]}"""
        val provider = ReiAnixTmdbMetadataProvider(
            readAccessToken = "configured-token",
            connectionFactory = { url ->
                if (calls.incrementAndGet() == 1) FakeHttpConnection(url, 429, "")
                else FakeHttpConnection(url, 200, body)
            },
        )

        val candidates = provider.search(ReiAnixMetadataQuery(title = "Cowboy Bebop", year = 1998, mediaKind = "tv"))

        assertEquals(2, calls.get())
        assertEquals("https://image.tmdb.org/t/p/w500/cowboy.jpg", candidates.single().posterUrl)
    }

    @Test
    fun registryBoundsConcurrentProviderCalls() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val providers = (0 until 5).map { index ->
            object : ReiAnixMetadataProvider {
                override val providerId: String = "test-$index"
                override val priority: Int = index
                override suspend fun search(query: ReiAnixMetadataQuery): List<ReiAnixMetadataCandidate> {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { old -> maxOf(old, current) }
                    try {
                        delay(25L)
                        return emptyList()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }

        ReiAnixMetadataProviderRegistry(
            providers = providers,
            maxConcurrentProviders = 2,
            providerTimeoutMillis = 1_000L,
        ).search(ReiAnixMetadataQuery(title = "Concurrency"))

        assertTrue(peak.get() <= 2)
    }

    @Test
    fun registryPropagatesCallerCancellation() = runBlocking {
        val providerCancelled = AtomicBoolean(false)
        val provider = object : ReiAnixMetadataProvider {
            override val providerId = "cancellable"
            override val priority = 1
            override suspend fun search(query: ReiAnixMetadataQuery): List<ReiAnixMetadataCandidate> {
                try {
                    awaitCancellation()
                } finally {
                    providerCancelled.set(true)
                }
            }
        }
        val request = launch {
            ReiAnixMetadataProviderRegistry(listOf(provider), providerTimeoutMillis = 30_000L)
                .search(ReiAnixMetadataQuery(title = "Cancel"))
        }
        yield()
        request.cancelAndJoin()
        assertTrue(providerCancelled.get())
    }

    @Test
    fun emptySearchIsNotTreatedAsTransportFailure() = runBlocking {
        val provider = ReiAnixTmdbMetadataProvider(
            readAccessToken = "configured-token",
            connectionFactory = { url -> FakeHttpConnection(url, 200, """{"results":[]}""") },
        )
        val result = ReiAnixMetadataProviderRegistry(listOf(provider))
            .search(ReiAnixMetadataQuery(title = "No Matching Title"))
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.failures.isEmpty())
    }

    private fun candidate(title: String, year: Int, providerId: String = "tmdb") = ReiAnixMetadataCandidate(
        providerId = providerId,
        externalId = "123",
        title = title,
        originalTitle = title,
        year = year,
        mediaKind = "tv",
        posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
        provenance = "test",
        matchedQueryTitle = title,
        confidence = 0.0,
    )

    private class FixedProvider(
        override val providerId: String,
        override val priority: Int,
        private val candidates: List<ReiAnixMetadataCandidate>,
    ) : ReiAnixMetadataProvider {
        override suspend fun search(query: ReiAnixMetadataQuery): List<ReiAnixMetadataCandidate> = candidates
    }

    private class FakeHttpConnection(
        url: URL,
        private val status: Int,
        private val responseBody: String,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = null
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
    }
}
