package com.reiflix.reiflix_local.data.artwork

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ReiAnixParallelArtworkSearchTest {
    @Test
    fun titleCollectionKeepsEveryDistinctProviderVariantWithoutNormalizationOrTruncation() {
        val supplied = listOf(
            "Hanazakari no Kimitachi e",
            "Maou Gakuin no Futekigousha",
            "Sakamoto Days Part 2",
            "Futsutsuka na Akujo de Gozaimasu ga, Suuguu Chou...",
            "Otome Kaijuu Carameliser",
            "Omae Gotoki ga Maou ni Kateru to Omou na",
            "Saigo ni Hitotsu dake Onegai shitemo Yoroshii deshou ka",
            "Sakamoto Days",
            "Prism Rondo",
            "Rising Impact",
            "Hanagiri-san wa Tensei shitemo Kenka ga Shitai",
            "Tensai Ouji no Akaji Kokka Saisei Jutsu",
            "BULLET/BULLET",
            "Akuyaku Reijou to Kyouketsu Koushaku",
            "Pluto",
            "Attack on Titan",
            " attack on titan ",
            "進撃の巨人",
            "Attack on Titan",
            "",
            "   ",
        )

        val terms = allDistinctArtworkSearchTitles(supplied)

        assertEquals(18, terms.size)
        assertTrue(supplied.filter(String::isNotBlank).all { original ->
            terms.any { it == original.trim() }
        })
        assertTrue(terms.contains("Attack on Titan"))
        assertTrue(terms.contains("attack on titan"))
        assertTrue(terms.contains("進撃の巨人"))
    }

    @Test
    fun firstCompletedVariantIsDeliveredBeforeSlowVariantFinishes() = runBlocking {
        val slowResponse = CompletableDeferred<String>()
        val firstPublished = CompletableDeferred<Boolean>()
        val job = launch {
            runParallelArtworkSearches(
                titles = listOf("fast title", "slow title"),
                maxConcurrent = 2,
                search = { title ->
                    if (title == "fast title") "valid-image-url" else slowResponse.await()
                },
                onCompleted = { title, result ->
                    if (title == "fast title" && result.getOrNull() == "valid-image-url") {
                        firstPublished.complete(true)
                    }
                },
            )
        }

        assertTrue(withTimeout(1_000) { firstPublished.await() })
        assertTrue("The slow alias must still be running", job.isActive)
        slowResponse.complete("later-result")
        job.join()
    }

    @Test
    fun aFailedVariantDoesNotCancelOtherSearches() = runBlocking {
        val outcomes = mutableMapOf<String, Result<String>>()
        runParallelArtworkSearches(
            titles = listOf("network failure", "valid title"),
            maxConcurrent = 2,
            search = { title ->
                if (title == "network failure") throw IOException("temporary offline")
                "poster"
            },
            onCompleted = { title, result -> outcomes[title] = result },
        )

        assertFalse(outcomes.getValue("network failure").isSuccess)
        assertEquals("poster", outcomes.getValue("valid title").getOrThrow())
    }

    @Test
    fun searchWorkerPoolBoundsConcurrencyInsteadOfCreatingOneJobPerTitle() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val titles = (1..40).map { "variant-$it" }

        runParallelArtworkSearches(
            titles = titles,
            maxConcurrent = 3,
            search = {
                val now = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                try {
                    kotlinx.coroutines.delay(5)
                    it
                } finally {
                    active.decrementAndGet()
                }
            },
            onCompleted = { _, _ -> Unit },
        )

        assertTrue(peak.get() <= 3)
        assertTrue(peak.get() >= 2)
    }
}
