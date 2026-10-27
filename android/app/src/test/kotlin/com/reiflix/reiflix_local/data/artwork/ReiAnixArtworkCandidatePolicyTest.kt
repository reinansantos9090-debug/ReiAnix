package com.reiflix.reiflix_local.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReiAnixArtworkCandidatePolicyTest {
    @Test
    fun coverVariantsPreferExtraLargeThenLargeAndDiscardInvalidUrls() {
        assertEquals(
            listOf(
                ReiAnixAniListCoverCandidate("extraLarge", "https://img.example/large.jpg"),
                ReiAnixAniListCoverCandidate("large", "https://img.example/small.jpg"),
            ),
            resolveAniListCoverCandidates(
                extraLargeUrl = " https://img.example/large.jpg ",
                largeUrl = "https://img.example/small.jpg",
            ),
        )
        assertEquals(
            listOf(ReiAnixAniListCoverCandidate("large", "https://img.example/small.jpg")),
            resolveAniListCoverCandidates("ftp://img.example/invalid.jpg", "https://img.example/small.jpg"),
        )
    }

    @Test
    fun emptyInvalidAndDuplicateUrlsNeverBecomeCandidates() {
        assertEquals(emptyList<ReiAnixAniListCoverCandidate>(), resolveAniListCoverCandidates("", null))
        assertEquals(emptyList<ReiAnixAniListCoverCandidate>(), resolveAniListCoverCandidates("https://", "no-url"))
        assertEquals(
            listOf(ReiAnixAniListCoverCandidate("extraLarge", "https://img.example/same.jpg")),
            resolveAniListCoverCandidates(
                "https://img.example/same.jpg",
                " https://img.example/same.jpg ",
            ),
        )
        assertNull(validReiAnixArtworkHttpUrl("https://user:pass@img.example/cover.jpg"))
        assertNull(validReiAnixArtworkHttpUrl("https://img.example/with space.jpg"))
        assertNull(validReiAnixArtworkHttpUrl("file:///tmp/cover.jpg"))
    }

    @Test
    fun candidateKeysAreStableAndDifferentForLocalAnimeAndImageUrl() {
        val first = aniListArtworkCandidateKey(10L, 16498L, "https://img.example/cover.jpg")
        assertEquals(64, first.length)
        assertEquals(first, aniListArtworkCandidateKey(10L, 16498L, "https://img.example/cover.jpg"))
        assertNotEquals(first, aniListArtworkCandidateKey(11L, 16498L, "https://img.example/cover.jpg"))
        assertNotEquals(first, aniListArtworkCandidateKey(10L, 1535L, "https://img.example/cover.jpg"))
        assertNotEquals(first, aniListArtworkCandidateKey(10L, 16498L, "https://img.example/other-cover.jpg"))
    }
}
