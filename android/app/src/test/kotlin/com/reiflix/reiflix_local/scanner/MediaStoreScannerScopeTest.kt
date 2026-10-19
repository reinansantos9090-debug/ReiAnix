package com.reiflix.reiflix_local.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreScannerScopeTest {
    @Test
    fun physicalScopeIncludesOnlyTheExactDirectoryOrItsDescendants() {
        val scope = "/storage/emulated/0/Anime"

        assertTrue(MediaStoreScanner.isWithinPhysicalLibraryScope(scope, scope))
        assertTrue(
            MediaStoreScanner.isWithinPhysicalLibraryScope(
                scope,
                "/storage/emulated/0/Anime/Season 01/episode 01.mkv",
            ),
        )
        assertFalse(
            MediaStoreScanner.isWithinPhysicalLibraryScope(
                scope,
                "/storage/emulated/0/Anime2/episode 01.mkv",
            ),
        )
        assertFalse(
            MediaStoreScanner.isWithinPhysicalLibraryScope(
                scope,
                "/storage/emulated/0/Movies/episode 01.mkv",
            ),
        )
    }

    @Test
    fun missingPathsAndTraversalOutsideTheScopeFailClosed() {
        val scope = "/storage/emulated/0/Anime"

        assertFalse(MediaStoreScanner.isWithinPhysicalLibraryScope(null, "/storage/emulated/0/Anime/video.mkv"))
        assertFalse(MediaStoreScanner.isWithinPhysicalLibraryScope(scope, null))
        assertFalse(MediaStoreScanner.isWithinPhysicalLibraryScope(" ", "/storage/emulated/0/Anime/video.mkv"))
        assertFalse(
            MediaStoreScanner.isWithinPhysicalLibraryScope(
                scope,
                "/storage/emulated/0/Anime/../Anime2/video.mkv",
            ),
        )
    }
}
