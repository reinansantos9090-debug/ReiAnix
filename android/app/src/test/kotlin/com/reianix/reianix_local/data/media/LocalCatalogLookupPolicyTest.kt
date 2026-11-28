package com.reianix.reianix_local.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCatalogLookupPolicyTest {
    @Test fun editedPersonalVideoNamesAreNotAnimeMovies() {
        for (name in listOf("YouCut_20261114_235138077.mp4", "CapCut-20261128-123456.mp4", "InShot_20261128_123456.mp4")) {
            assertEquals(LocalCatalogLookup.PERSONAL, LocalCatalogLookupPolicy.classify(name, "movie", listOf(name)))
        }
        assertEquals(LocalCatalogLookup.UNCERTAIN, LocalCatalogLookupPolicy.classify("Look Back", "movie", listOf("Look Back.mp4")))
    }

    @Test fun personalCaptureNamesAreLocalOnly() {
        for ((title, name) in listOf(
            "line" to "line_123456.mp4",
            "vid" to "VID_20261128_080000.mp4",
            "whatsapp video" to "WhatsApp Video 20261128.mp4",
            "screen recording" to "Screen_Recording_20261128.mp4",
        )) assertEquals(LocalCatalogLookup.PERSONAL,
            LocalCatalogLookupPolicy.classify(title, "movie", listOf(name)))
    }

    @Test fun ordinaryAnimeAndNumberedFoldersKeepFastPath() {
        for ((title, name) in listOf(
            "One Piece" to "One Piece - EP 01.mp4",
            "Sakamoto Days" to "Sakamoto Days - 02.mp4",
            "Naruto" to "01.mp4",
            "Line" to "Line EP 01.mp4",
        )) assertEquals(LocalCatalogLookup.NORMAL,
            LocalCatalogLookupPolicy.classify(title, "anime", listOf(name)))
        assertTrue(LocalCatalogLookupPolicy.allowsCandidate(
            LocalCatalogLookup.NORMAL, "local title", listOf("unrelated translation")))
    }

    @Test fun uncertainTitlesRejectUnrelatedCandidatesButAcceptAliases() {
        val mode = LocalCatalogLookupPolicy.classify("Line", "movie", listOf("Line.mp4"))
        assertEquals(LocalCatalogLookup.UNCERTAIN, mode)
        assertFalse(LocalCatalogLookupPolicy.allowsCandidate(mode, "Line", listOf("REDLINE")))
        assertTrue(LocalCatalogLookupPolicy.allowsCandidate(mode, "Line", listOf("Other", "Line")))
        assertTrue(LocalCatalogLookupPolicy.allowsCandidate(mode, "Kimi no Na wa", listOf("Kimi no Na wa Movie")))
        assertFalse(LocalCatalogLookupPolicy.allowsCandidate(LocalCatalogLookup.PERSONAL, "Line", listOf("Line")))
    }
}
