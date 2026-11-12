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
    fun rootOnePieceMkvJoinsNumberedMp4WithoutConvertingARealFilm() {
        val root="Download/ReiAnix/"
        val movie=NativeDiscoveredMedia(
            location="content://media/file/11",fileName="One piece.mkv",
            sourceFolder="Download/ReiAnix",relativePath=root+"One piece.mkv",
            animeTitle="One piece",episodeType="movie",
            identificationSource="standalone_root_movie",identificationConfidence="high",
        )
        val numbered=NativeDiscoveredMedia(
            location="content://media/file/12",
            fileName="One Piece Dublado Episódio 3 - Animes Online.mp4",
            sourceFolder="Download/ReiAnix",
            relativePath=root+"One Piece Dublado Episódio 3 - Animes Online.mp4",
            animeTitle="One Piece Dublado",season=1,episodeNumber=3.0,
            episodeType="regular",identificationSource="episode_marker",
            identificationConfidence="high",
        )
        val actualFilm=movie.copy(
            location="content://media/file/13",fileName="One Piece Film Red.mkv",
            relativePath=root+"One Piece Film Red.mkv",animeTitle="One Piece Film Red",
            identificationSource="movie_marker",
        )
        val results=recognizeUnnumberedSeriesSiblings(listOf(movie,numbered,actualFilm))
        assertTrue(results[0].episodeType=="regular")
        assertTrue(results[0].episodeNumber==1.0)
        assertTrue(results[0].animeTitle=="One Piece Dublado")
        assertTrue(results[1]==numbered)
        assertTrue(results[2]==actualFilm)
    }

    @Test
    fun unknownFilesWithoutUniqueMatchingSerialSiblingsKeepTheirOriginalKind() {
        val standalone=NativeDiscoveredMedia(
            location="content://media/file/1",fileName="Look Back.mkv",
            relativePath="Download/ReiAnix/Look Back.mkv",
            animeTitle="Look Back",episodeType="movie",
            identificationSource="standalone_root_movie",
            identificationConfidence="high",
        )
        assertTrue(recognizeUnnumberedSeriesSiblings(listOf(standalone)).single()==standalone)
    }

    @Test
    fun repeatedTrailingEpisodeNumberIsRemovedPerAnimeEvenWhenRootContainsMixedSeries() {
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
                relativePath = "episode-$number.mp4",
            )
        }
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
                relativePath = "episode-$number.mp4",
            )
        }

        // Both series share the same SAF root and immediate folder (the root itself).
        val mixedRoot = seihantai + yozakura
        val normalized = normalizeDuplicateReleaseEpisodeTitleSuffixes(mixedRoot)

        assertTrue(normalized.take(3).all { it.animeTitle == "Seihantai na Kimi to Boku" })
        assertTrue(normalized.drop(3).all { it.animeTitle == "Yozakura-san Chi no Daisakusen 2" })
    }
}
