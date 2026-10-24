package com.reiflix.reiflix_local.data.artwork

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixArtworkPublicationSignalTest {
    private fun publication() = ReiAnixArtworkPublication(
        requestId = "request-test-17",
        entityType = "anime",
        entityId = "812",
        artworkType = "poster",
        sourceKind = "anilist",
    )

    @Test
    fun publishedFileWithoutCommittedAssociationIsRejected() {
        val signal = ReiAnixArtworkPublicationSignal()

        val accepted = signal.publishIfCommitted(
            publication = publication(),
            associationCommitted = false,
            sourceFileExists = true,
        )

        assertFalse(accepted)
        assertTrue(signal.events.replayCache.isEmpty())
    }

    @Test
    fun missingArtworkOriginIsNotReportedAsPublished() {
        val signal = ReiAnixArtworkPublicationSignal()

        val accepted = signal.publishIfCommitted(
            publication = publication(),
            associationCommitted = true,
            sourceFileExists = false,
        )

        assertFalse(accepted)
        assertTrue(signal.events.replayCache.isEmpty())
    }

    @Test
    fun missingNativeArtworkCacheDirectoryIsCreatedOnDemand() {
        val root = File(
            System.getProperty("java.io.tmpdir"),
            "reianix-artwork-test-" + UUID.randomUUID(),
        )
        val cache = File(root, "data/covers/artwork")
        try {
            assertFalse(cache.exists())
            assertTrue(ensureArtworkCacheDirectory(cache))
            assertTrue(cache.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun committedArtworkPublicationCanBeObservedAfterConsumerStarts() = runBlocking {
        val signal = ReiAnixArtworkPublicationSignal()
        assertTrue(
            signal.publishIfCommitted(
                publication = publication(),
                associationCommitted = true,
                sourceFileExists = true,
            ),
        )

        val received = withTimeout(1_000L) { signal.events.first() }
        assertEquals("request-test-17", received.requestId)
        assertEquals("anime", received.entityType)
        assertEquals("812", received.entityId)
        assertEquals("poster", received.artworkType)
        assertEquals("anilist", received.sourceKind)
    }

}
