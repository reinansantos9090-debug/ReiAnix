package com.reiflix.reiflix_local.data.tracemoe

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixHybridTraceMoeClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startsTraceAndWebDetectionInParallelForTheSameThumbnail() = runBlocking {
        val image = sampleImage()
        val traceStarted = CompletableDeferred<Unit>()
        val webStarted = CompletableDeferred<Unit>()
        val traceMatch = TraceMoeSearchMatch(1234, 0.97)
        val webMatch = TraceMoeSearchMatch(5678, 0.86, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION)
        val client = client(
            public = ReiAnixTraceMoeSearchProvider {
                traceStarted.complete(Unit)
                withTimeout(2_000) { webStarted.await() }
                listOf(traceMatch)
            },
            web = ReiAnixTraceMoeSearchProvider {
                webStarted.complete(Unit)
                withTimeout(2_000) { traceStarted.await() }
                listOf(webMatch)
            },
        )

        assertEquals(listOf(traceMatch, webMatch), withTimeout(3_000) { client.search(image) })
    }

    @Test
    fun webDetectionStartsWhilePublicQuotaCheckIsPending() = runBlocking {
        val image = sampleImage()
        val quotaStarted = CompletableDeferred<Unit>()
        val releaseQuota = CompletableDeferred<Boolean>()
        val webStarted = CompletableDeferred<Unit>()
        val expected = TraceMoeSearchMatch(5678, 0.86, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION)
        val client = client(
            public = ReiAnixTraceMoeSearchProvider { error("Public search must be skipped") },
            web = ReiAnixTraceMoeSearchProvider {
                webStarted.complete(Unit)
                listOf(expected)
            },
        )

        val search = async {
            client.search(image) {
                quotaStarted.complete(Unit)
                releaseQuota.await()
            }
        }
        quotaStarted.await()
        withTimeout(2_000) { webStarted.await() }
        releaseQuota.complete(false)

        assertEquals(listOf(expected), search.await())
    }

    @Test
    fun keepsTraceMoeSourceWhenEqualScoresAreReturnedForTheSameAnime() = runBlocking {
        val image = sampleImage()
        val traceMatch = TraceMoeSearchMatch(1234, 0.90)
        val webMatch = TraceMoeSearchMatch(1234, 0.90, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION)
        val client = client(
            public = ReiAnixTraceMoeSearchProvider { listOf(traceMatch) },
            web = ReiAnixTraceMoeSearchProvider { listOf(webMatch) },
        )

        assertEquals(listOf(traceMatch), client.search(image))
    }

    @Test
    fun cachesSuccessfulSearchWithEvidenceSource() = runBlocking {
        val image = sampleImage()
        var traceCalls = 0
        var webCalls = 0
        val expected = TraceMoeSearchMatch(9876, 0.86, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION)
        val client = client(
            public = ReiAnixTraceMoeSearchProvider { traceCalls++; emptyList() },
            web = ReiAnixTraceMoeSearchProvider { webCalls++; listOf(expected) },
        )

        assertEquals(listOf(expected), client.search(image))
        assertEquals(listOf(expected), client.search(image))
        assertEquals(1, traceCalls)
        assertEquals(1, webCalls)
    }

    @Test
    fun doesNotCachePositivePartialResultWhenAnotherEnabledSourceFails() = runBlocking {
        val image = sampleImage()
        var webCalls = 0
        val expected = TraceMoeSearchMatch(9876, 0.86, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION)
        val client = client(
            public = ReiAnixTraceMoeSearchProvider { listOf(expected) },
            web = ReiAnixTraceMoeSearchProvider {
                webCalls++
                throw IOException("Google Vision unavailable")
            },
        )

        assertEquals(listOf(expected), client.search(image))
        assertEquals(listOf(expected), client.search(image))
        assertEquals(2, webCalls)
    }

    @Test
    fun incompleteNoMatchIsRetriedInsteadOfCachedAsEmpty() {
        val image = sampleImage()
        var traceCalls = 0
        var webCalls = 0
        val client = client(
            public = ReiAnixTraceMoeSearchProvider { traceCalls++; emptyList() },
            web = ReiAnixTraceMoeSearchProvider {
                webCalls++
                throw IOException("Google Vision unavailable")
            },
        )

        repeat(2) {
            assertThrows(IOException::class.java) { runBlocking { client.search(image) } }
        }
        assertEquals(2, traceCalls)
        assertEquals(2, webCalls)
    }

    @Test
    fun aTraceOnlyInstallationDoesNotRequireGoogleConfiguration() = runBlocking {
        val image = sampleImage()
        val expected = TraceMoeSearchMatch(4321, 0.95)
        var traceCalls = 0
        val client = client(
            public = ReiAnixTraceMoeSearchProvider { traceCalls++; listOf(expected) },
            web = null,
        )

        assertEquals(listOf(expected), client.search(image))
        assertEquals(1, traceCalls)
    }

    @Test
    fun completeNoMatchCanBeCachedOnlyAfterAllEnabledSourcesFinish() = runBlocking {
        val image = sampleImage()
        var traceCalls = 0
        var webCalls = 0
        val client = client(
            public = ReiAnixTraceMoeSearchProvider { traceCalls++; emptyList() },
            web = ReiAnixTraceMoeSearchProvider { webCalls++; emptyList() },
        )

        assertTrue(client.search(image).isEmpty())
        assertTrue(client.search(image).isEmpty())
        assertEquals(1, traceCalls)
        assertEquals(1, webCalls)
    }

    private fun client(
        public: ReiAnixTraceMoeSearchProvider,
        web: ReiAnixTraceMoeSearchProvider?,
    ) = ReiAnixHybridTraceMoeClient(
        publicProvider = public,
        webDetectionProvider = web,
        cacheDirectory = File(temporaryFolder.root, "cache"),
    )

    private fun sampleImage(): File = File(temporaryFolder.root, "episode-thumb.jpg").apply {
        writeBytes(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x12))
    }
}
