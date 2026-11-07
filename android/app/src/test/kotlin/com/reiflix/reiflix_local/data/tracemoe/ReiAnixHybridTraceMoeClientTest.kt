package com.reiflix.reiflix_local.data.tracemoe

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReiAnixHybridTraceMoeClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startsPublicAndIndependentIndexesBeforeWaitingForEitherResult() = runBlocking {
        val image = sampleImage()
        val publicStarted = CompletableDeferred<Unit>()
        val independentStarted = CompletableDeferred<Unit>()
        val publicMatch = TraceMoeSearchMatch(anilistId = 1234, similarity = 0.97)
        val independentMatch = TraceMoeSearchMatch(anilistId = 5678, similarity = 0.95)
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider {
                publicStarted.complete(Unit)
                withTimeout(2_000) { independentStarted.await() }
                listOf(publicMatch)
            },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                independentStarted.complete(Unit)
                withTimeout(2_000) { publicStarted.await() }
                listOf(independentMatch)
            },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        val matches = withTimeout(3_000) { client.search(image) }

        assertEquals(listOf(publicMatch, independentMatch), matches)
    }

    @Test
    fun combinesPublicAndIndependentCandidatesEvenWhenPublicHasStrongMatch() = runBlocking {
        val image = sampleImage()
        val publicMatch = TraceMoeSearchMatch(anilistId = 1234, similarity = 0.97)
        val independentMatch = TraceMoeSearchMatch(anilistId = 5678, similarity = 0.95)
        var independentCalls = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider { listOf(publicMatch) },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                independentCalls += 1
                listOf(independentMatch)
            },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(listOf(publicMatch, independentMatch), client.search(image))
        assertEquals(1, independentCalls)
    }

    @Test
    fun mergesSameAnimeByKeepingTheStrongestProviderScore() = runBlocking {
        val image = sampleImage()
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider {
                listOf(TraceMoeSearchMatch(anilistId = 1234, similarity = 0.91))
            },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                listOf(TraceMoeSearchMatch(anilistId = 1234, similarity = 0.97))
            },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(
            listOf(TraceMoeSearchMatch(anilistId = 1234, similarity = 0.97)),
            client.search(image),
        )
    }

    @Test
    fun keepsIndependentResultsWhenPublicSearchFails() = runBlocking {
        val image = sampleImage()
        val expected = listOf(TraceMoeSearchMatch(anilistId = 5678, similarity = 0.95))
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider { throw IOException("Public endpoint unavailable") },
            independentProvider = ReiAnixTraceMoeSearchProvider { expected },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(expected, client.search(image))
    }

    @Test
    fun keepsPublicResultsWhenIndependentSearchFails() = runBlocking {
        val image = sampleImage()
        val expected = listOf(TraceMoeSearchMatch(anilistId = 1234, similarity = 0.96))
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider { expected },
            independentProvider = ReiAnixTraceMoeSearchProvider { throw IOException("Independent endpoint unavailable") },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(expected, client.search(image))
    }

    @Test
    fun keepsIndependentIndexActiveWhenPublicQuotaIsExhausted() = runBlocking {
        val image = sampleImage()
        var publicCalls = 0
        var independentCalls = 0
        val expected = listOf(TraceMoeSearchMatch(anilistId = 5678, similarity = 0.97))
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider {
                publicCalls += 1
                emptyList()
            },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                independentCalls += 1
                expected
            },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(expected, client.search(image) { false })
        assertEquals(0, publicCalls)
        assertEquals(1, independentCalls)
    }

    @Test
    fun publicQuotaExhaustionDoesNotTurnAnIncompleteSearchIntoNoMatch() {
        val image = sampleImage()
        var independentCalls = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider { emptyList() },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                independentCalls += 1
                emptyList()
            },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        repeat(2) {
            assertThrows(IOException::class.java) {
                runBlocking { client.search(image, allowPublicSearch = false) }
            }
        }
        assertEquals(2, independentCalls)
    }

    @Test
    fun cachesSuccessfulResultByExactImageContent() = runBlocking {
        val image = sampleImage()
        val expected = listOf(TraceMoeSearchMatch(anilistId = 9876, similarity = 0.96))
        var publicCalls = 0
        var publicQuotaReservations = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider {
                publicCalls += 1
                expected
            },
            independentProvider = null,
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(expected, client.search(image) {
            publicQuotaReservations += 1
            true
        })
        assertEquals(expected, client.search(image) {
            publicQuotaReservations += 1
            true
        })
        assertEquals(1, publicCalls)
        assertEquals(1, publicQuotaReservations)
    }

    @Test
    fun doesNotCachePublicNoMatchWhenIndependentIndexFails() {
        val image = sampleImage()
        var publicCalls = 0
        var independentCalls = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider {
                publicCalls += 1
                emptyList()
            },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                independentCalls += 1
                throw IOException("Independent endpoint unavailable")
            },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        repeat(2) {
            assertThrows(IOException::class.java) { runBlocking { client.search(image) } }
        }
        assertEquals(2, publicCalls)
        assertEquals(2, independentCalls)
    }

    @Test
    fun doesNotAcceptPartialNoMatchWhenPublicFailsAndIndependentReturnsEmpty() {
        val image = sampleImage()
        var independentCalls = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider { throw IOException("Public endpoint unavailable") },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                independentCalls += 1
                emptyList()
            },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        repeat(2) {
            assertThrows(IOException::class.java) { runBlocking { client.search(image) } }
        }
        assertEquals(2, independentCalls)
    }

    @Test
    fun doesNotCacheTwoProviderFailuresAsNoMatch() {
        val image = sampleImage()
        var publicCalls = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider {
                publicCalls += 1
                throw IOException("Public endpoint unavailable")
            },
            independentProvider = ReiAnixTraceMoeSearchProvider { throw IOException("Independent endpoint unavailable") },
            localReferenceIndex = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        repeat(2) {
            assertThrows(IOException::class.java) { runBlocking { client.search(image) } }
        }
        assertEquals(2, publicCalls)
    }

    @Test
    fun selfHostedEndpointRequiresAValidRootHttpsOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixTraceMoeClient.selfHosted("http://visual-index.example.net")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReiAnixTraceMoeClient.selfHosted("https://visual-index.example.net/search")
        }
        ReiAnixTraceMoeClient.selfHosted("https://visual-index.example.net")
    }

    private fun sampleImage(): File = File(temporaryFolder.root, "episode-thumb.jpg").apply {
        writeBytes(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x12))
    }
}