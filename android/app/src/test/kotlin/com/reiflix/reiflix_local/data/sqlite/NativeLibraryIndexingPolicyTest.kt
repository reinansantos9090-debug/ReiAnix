package com.reiflix.reiflix_local.data.sqlite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLibraryIndexingPolicyTest {
    private fun snapshot(
        status: String = "COMPLETED",
        complete: Boolean = true,
        cancelled: Boolean = false,
        authorized: Boolean = true,
        failed: Set<String> = emptySet(),
        unprocessed: Set<String> = emptySet(),
        processed: Set<String> = setOf("primary:Anime"),
        items: List<NativeDiscoveredMedia> = emptyList(),
    ) = NativeLibraryScanSnapshot(
        operationId = "scan-1",
        source = "saf",
        scopeRoot = "primary:Anime",
        expectedLocations = setOf("primary:Anime"),
        processedLocations = processed,
        failedLocations = failed,
        unprocessedLocations = unprocessed,
        status = status,
        complete = complete,
        cancelled = cancelled,
        authorizationValid = authorized,
        items = items,
    )

    @Test fun onlyExplicitCompleteAuthorizedScopeCanReconcile() {
        assertTrue(snapshot().mayReconcile)
        assertTrue(snapshot(status = "EMPTY_COMPLETE").mayReconcile)
        assertFalse(snapshot(status = "PARTIAL", complete = false).mayReconcile)
        assertFalse(snapshot(status = "FAILED").mayReconcile)
        assertFalse(snapshot(cancelled = true).mayReconcile)
        assertFalse(snapshot(authorized = false).mayReconcile)
        assertFalse(snapshot(failed = setOf("primary:Anime/Season 2")).mayReconcile)
        assertFalse(snapshot(unprocessed = setOf("primary:Anime/Season 3")).mayReconcile)
        assertFalse(snapshot(processed = emptySet()).mayReconcile)
    }

    @Test fun siblingOrOutOfScopeDocumentsBlockReconciliation() {
        assertFalse(snapshot(items = listOf(NativeDiscoveredMedia(
            location = "content://provider/doc/1",
            scopeLocation = "primary:AnimeBackup/e01.mkv",
            fileName = "e01.mkv",
        ))).mayReconcile)
    }

    @Test fun segmentAwareScopeAllowsRootAndDescendants() {
        assertTrue("primary:Anime".isWithinScope("primary:Anime"))
        assertTrue("primary:Anime/Season 1/e01.mkv".isWithinScope("primary:Anime"))
        assertFalse("primary:AnimeBackup/e01.mkv".isWithinScope("primary:Anime"))
        assertFalse("primary:Other/e01.mkv".isWithinScope("primary:Anime"))
    }

    @Test fun opaqueProviderDocumentIdsNeedExplicitTreeMembershipEvidence() {
        val unverified = NativeDiscoveredMedia(
            location = "content://provider/document/child",
            scopeLocation = "provider-opaque-child-id",
            fileName = "episode.mkv",
            scopeVerified = false,
        )
        assertFalse(snapshot(items = listOf(unverified)).mayReconcile)

        val providerVerified = unverified.copy(scopeVerified = true)
        assertTrue(snapshot(items = listOf(providerVerified)).mayReconcile)
    }

    @Test fun weakOrAmbiguousEpisodeIdentityCannotAuthorizeInsertion() {
        val weak = NativeDiscoveredMedia(
            location = "content://provider/doc/1",
            fileName = "e01.mkv",
            animeTitle = "Example",
            season = 1,
            episodeNumber = 1.0,
            identificationConfidence = "low",
        )
        assertFalse(weak.hasConfidentEpisodeIdentity)
        assertTrue(weak.copy(identificationConfidence = "high").hasConfidentEpisodeIdentity)
        assertFalse(weak.copy(ambiguous = true, identificationConfidence = "high").hasConfidentEpisodeIdentity)
    }

    @Test
    fun highConfidenceStandaloneMovieDoesNotRequireAnEpisodeNumber() {
        val movie = NativeDiscoveredMedia(
            location = "content://provider/document/look-back",
            fileName = "Look Back.mp4",
            scopeLocation = "look-back",
            scopeVerified = true,
            animeTitle = "Look Back",
            episodeType = "movie",
            identificationSource = "standalone_root_movie",
            identificationConfidence = "high",
        )

        assertTrue(movie.hasConfidentEpisodeIdentity)
    }

    @Test
    fun repeatedTrailingEpisodeNumberIsRemovedOnlyWithConsistentSiblingEvidence() {
        val seihantai = listOf(9, 10, 11).map { number ->
            NativeDiscoveredMedia(
                location = "content://provider/seihantai/$number",
                fileName = "Seihantai na Kimi to Boku $number Dublado %02d.mp4".format(number),
                animeTitle = "Seihantai na Kimi to Boku $number",
                season = 1,
                episodeNumber = number.toDouble(),
                episodeType = "regular",
                identificationSource = "release_language_episode",
                identificationConfidence = "high",
                sourceFolder = "saf://adm",
                relativePath = "Seihantai na Kimi/episode-$number.mp4",
            )
        }
        val normalizedSeihantai = normalizeDuplicateReleaseEpisodeTitleSuffixes(seihantai)
        assertTrue(normalizedSeihantai.all { it.animeTitle == "Seihantai na Kimi to Boku" })

        val yozakura = listOf(2, 3, 12).map { number ->
            NativeDiscoveredMedia(
                location = "content://provider/yozakura/$number",
                fileName = "Yozakura-san Chi no Daisakusen 2 Dublado %02d.mp4".format(number),
                animeTitle = "Yozakura-san Chi no Daisakusen 2",
                season = 1,
                episodeNumber = number.toDouble(),
                episodeType = "regular",
                identificationSource = "release_language_episode",
                identificationConfidence = "high",
                sourceFolder = "saf://adm",
                relativePath = "Yozakura-san Chi no Daisakusen 2/episode-$number.mp4",
            )
        }
        val normalizedYozakura = normalizeDuplicateReleaseEpisodeTitleSuffixes(yozakura)
        assertTrue(normalizedYozakura.all { it.animeTitle == "Yozakura-san Chi no Daisakusen 2" })
    }
}
