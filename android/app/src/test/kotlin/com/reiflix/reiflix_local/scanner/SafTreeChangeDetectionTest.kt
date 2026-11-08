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
    fun videoMimeIsNotRequiredWhenARecognizedExtensionExists() {
        assertTrue(SafScanner.isPotentialVideoDocument("Episode 03.mkv", "application/octet-stream"))
        assertFalse(SafScanner.isPotentialVideoDocument("Episode 03.txt", "application/octet-stream"))
    }
}
