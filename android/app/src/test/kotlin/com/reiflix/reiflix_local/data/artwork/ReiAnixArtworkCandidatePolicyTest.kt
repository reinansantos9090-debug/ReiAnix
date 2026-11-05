package com.reiflix.reiflix_local.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun cleartextAndMalformedUrlsAreNotUsableArtworkSources() {
        assertNull(validReiAnixArtworkHttpUrl("http://img.example/cover.jpg"))
        assertNull(validReiAnixArtworkHttpUrl("file:///tmp/cover.jpg"))
        assertNull(validReiAnixArtworkHttpUrl("https://user:pass@img.example/cover.jpg"))
        assertNull(validReiAnixArtworkHttpUrl("https://img.example/with space.jpg"))
        assertEquals(
            listOf(ReiAnixAniListCoverCandidate("large", "https://img.example/secure.jpg")),
            resolveAniListCoverCandidates("http://img.example/cleartext.jpg", "https://img.example/secure.jpg"),
        )
    }

    @Test
    fun emptyAndDuplicateUrlsNeverBecomeExtraCandidates() {
        assertTrue(resolveAniListCoverCandidates("", null).isEmpty())
        assertEquals(
            listOf(ReiAnixAniListCoverCandidate("extraLarge", "https://img.example/same.jpg")),
            resolveAniListCoverCandidates(
                "https://img.example/same.jpg",
                " https://img.example/same.jpg ",
            ),
        )
    }

    @Test
    fun fallbackIsAllowedOnlyAfterPrimaryFailureAndNeverForUserOwnedArtwork() {
        assertTrue(
            canMaterializeAniListFallbackCandidate(
                "https://img.example/main.jpg",
                "failed",
                "https://img.example/alternate.jpg",
                "source_candidate",
                false,
            ),
        )
        assertTrue(
            canMaterializeAniListFallbackCandidate(
                null,
                null,
                "https://img.example/alternate.jpg",
                "source_candidate",
                false,
            ),
        )
        assertFalse(
            canMaterializeAniListFallbackCandidate(
                "https://img.example/main.jpg",
                "ready",
                "https://img.example/alternate.jpg",
                "source_candidate",
                false,
            ),
        )
        assertFalse(
            canMaterializeAniListFallbackCandidate(
                "https://img.example/main.jpg",
                "failed",
                "https://img.example/alternate.jpg",
                "source_candidate",
                true,
            ),
        )
        assertFalse(
            canMaterializeAniListFallbackCandidate(
                "https://img.example/main.jpg",
                "failed",
                "https://img.example/alternate.jpg",
                "obsolete",
                false,
            ),
        )
    }

    @Test
    fun laterPosterCandidatesAreDiscardedAfterTheFirstPosterIsPublished() {
        val firstPoster = ReiAnixArtworkCandidate(
            anilistId = 0L,
            posterUrl = "https://img.example/first.jpg",
            bannerUrl = null,
            source = "kitsu",
        )
        val laterPoster = ReiAnixArtworkCandidate(
            anilistId = 0L,
            posterUrl = "https://img.example/later.jpg",
            bannerUrl = null,
            source = "anipub",
        )
        val backdrop = ReiAnixArtworkCandidate(
            anilistId = 0L,
            posterUrl = null,
            bannerUrl = "https://img.example/backdrop.jpg",
            source = "kitsu",
        )

        assertEquals(
            listOf(backdrop),
            artworkCandidatesAfterPosterPublication(
                listOf(firstPoster, laterPoster, backdrop),
                posterAlreadyAvailable = true,
            ),
        )
        assertEquals(
            listOf(firstPoster, laterPoster, backdrop),
            artworkCandidatesAfterPosterPublication(
                listOf(firstPoster, laterPoster, backdrop),
                posterAlreadyAvailable = false,
            ),
        )
    }

    @Test
    fun candidateKeysAreStableAndScopedToAnimeAniListAndUrl() {
        val first = aniListArtworkCandidateKey(10L, 16498L, "https://img.example/cover.jpg")
        assertEquals(64, first.length)
        assertEquals(first, aniListArtworkCandidateKey(10L, 16498L, "https://img.example/cover.jpg"))
        assertNotEquals(first, aniListArtworkCandidateKey(11L, 16498L, "https://img.example/cover.jpg"))
        assertNotEquals(first, aniListArtworkCandidateKey(10L, 1535L, "https://img.example/cover.jpg"))
        assertNotEquals(first, aniListArtworkCandidateKey(10L, 16498L, "https://img.example/other.jpg"))
    }
}
