package com.reiflix.reiflix_local.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafTreeChangeDetectionTest {

    @Test
    fun supportedAnimeVideoExtensionsAreRecognizedWithoutMimeMetadata() {
        for (name in listOf(
            "Episode 01.mkv",
            "episode 02.MP4",
            "OVA.webm",
            "movie.m2ts",
            "clip.avi",
        )) {
            assertTrue(
                "Expected $name to be treated as a video document.",
                SafScanner.isPotentialVideoDocument(name, null),
            )
        }
    }

    @Test
    fun videoMimeTypeIsSufficientEvenForUnknownExtensions() {
        assertTrue(SafScanner.isPotentialVideoDocument("episode.bin", "video/mp4"))
        assertTrue(SafScanner.isPotentialVideoDocument("episode", "VIDEO/X-MATROSKA"))
    }

    @Test
    fun nonVideoDocumentsDoNotTriggerAutomaticSafScans() {
        for (name in listOf(
            "poster.jpg",
            "cover.png",
            "info.txt",
            "subtitles.srt",
            "metadata.nfo",
            "README",
        )) {
            assertFalse(
                "Expected $name to be ignored by the video change detector.",
                SafScanner.isPotentialVideoDocument(name, "application/octet-stream"),
            )
        }
        assertFalse(SafScanner.isPotentialVideoDocument(null, null))
    }

    @Test
    fun persistedInventoryDetectsNewOrChangedVideosAfterProcessRestart() {
        val previous = mapOf(
            "root:Show/episode-01.mkv" to "episode-01.mkv|video/x-matroska|1000|100",
            "root:Show/episode-02.mkv" to "episode-02.mkv|video/x-matroska|2000|200",
        )
        val afterAddingEpisode = previous + (
            "root:Show/episode-03.mkv" to "episode-03.mkv|video/x-matroska|3000|300"
        )
        assertTrue(hasNewOrChangedVideoDocuments(previous, afterAddingEpisode))
    }

    @Test
    fun changedVideoMetadataIsDetectedButDeletionAloneDoesNotStartScan() {
        val previous = mapOf(
            "root:Show/episode-01.mkv" to "episode-01.mkv|video/x-matroska|1000|100",
            "root:Show/episode-02.mkv" to "episode-02.mkv|video/x-matroska|2000|200",
        )
        val changed = previous + (
            "root:Show/episode-01.mkv" to "episode-01.mkv|video/x-matroska|1500|150"
        )
        val deletedOnly = previous - "root:Show/episode-02.mkv"

        assertTrue(hasNewOrChangedVideoDocuments(previous, changed))
        assertFalse(hasNewOrChangedVideoDocuments(previous, deletedOnly))
        assertFalse(hasNewOrChangedVideoDocuments(previous, previous.toMap()))
    }

    @Test
    fun videoMimeIsNotRequiredWhenARecognizedExtensionExists() {
        assertTrue(SafScanner.isPotentialVideoDocument("Episode 03.mkv", "application/octet-stream"))
        assertFalse(SafScanner.isPotentialVideoDocument("Episode 03.txt", "application/octet-stream"))
    }
}
