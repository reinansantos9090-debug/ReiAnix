package com.reiflix.reiflix_local.data.tracemoe

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReiAnixHybridTraceMoeClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun usesIndependentIndexWhenPublicSearchReturnsNoMatch() = runBlocking {
        val image = sampleImage()
        val expected = listOf(TraceMoeSearchMatch(anilistId = 1234, similarity = 0.97))
        var fallbackCalls = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider { emptyList() },
            independentProvider = ReiAnixTraceMoeSearchProvider {
                fallbackCalls += 1
                expected
            },
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(expected, client.search(image))
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun usesIndependentIndexWhenPublicSearchFails() = runBlocking {
        val image = sampleImage()
        val expected = listOf(TraceMoeSearchMatch(anilistId = 5678, similarity = 0.95))
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider { throw IOException("Public endpoint unavailable") },
            independentProvider = ReiAnixTraceMoeSearchProvider { expected },
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(expected, client.search(image))
    }

    @Test
    fun cachesSuccessfulResultByExactImageContent() = runBlocking {
        val image = sampleImage()
        val expected = listOf(TraceMoeSearchMatch(anilistId = 9876, similarity = 0.96))
        var publicCalls = 0
        val client = ReiAnixHybridTraceMoeClient(
            publicProvider = ReiAnixTraceMoeSearchProvider {
                publicCalls += 1
                expected
            },
            independentProvider = null,
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        assertEquals(expected, client.search(image))
        assertEquals(expected, client.search(image))
        assertEquals(1, publicCalls)
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
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        repeat(2) {
            assertThrows(IOException::class.java) { runBlocking { client.search(image) } }
        }
        assertEquals(2, publicCalls)
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
            cacheDirectory = File(temporaryFolder.root, "cache"),
        )

        repeat(2) {
            assertThrows(IOException::class.java) { runBlocking { client.search(image) } }
        }
        assertEquals(2, publicCalls)
    }

    private fun sampleImage(): File = File(temporaryFolder.root, "episode-thumb.jpg").apply {
        writeBytes(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x12))
    }
}
