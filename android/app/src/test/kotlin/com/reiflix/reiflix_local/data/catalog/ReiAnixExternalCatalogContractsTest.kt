package com.reiflix.reiflix_local.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixExternalCatalogContractsTest {
    @Test
    fun sourceRolesKeepAniMapAsIdentifierResolverOnly() {
        assertTrue(ReiAnixCatalogSource.ANILIST.providesMetadata)
        assertTrue(ReiAnixCatalogSource.KITSU.providesArtwork)
        assertTrue(ReiAnixCatalogSource.JIKAN.providesMetadata)
        assertTrue(ReiAnixCatalogSource.ANIPUB.providesArtwork)
        assertTrue(ReiAnixCatalogSource.ANIMAP.resolvesIdentifiers)
        assertFalse(ReiAnixCatalogSource.ANIMAP.providesMetadata)
        assertFalse(ReiAnixCatalogSource.ANIMAP.providesArtwork)
        assertEquals(ReiAnixCatalogSource.KITSU, ReiAnixCatalogSource.fromKey("Kitsu"))
        assertNull(ReiAnixCatalogSource.fromKey("unknown"))
    }

    @Test
    fun idsValidateAndMergeWithoutReplacingVerifiedValues() {
        val first = ReiAnixExternalCatalogIds(myAnimeListId = 42L, kitsuId = "kitsu-uuid")
        val merged = first.mergeVerified(ReiAnixExternalCatalogIds(aniDbId = 72L))
        assertEquals(42L, merged.myAnimeListId)
        assertEquals(72L, merged.aniDbId)
        try {
            first.mergeVerified(ReiAnixExternalCatalogIds(kitsuId = "different-id"))
            throw AssertionError("A conflicting ID must not replace a verified mapping.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Conflicting verified Kitsu"))
        }
    }

    @Test
    fun animapCannotBeRegisteredAsAnAnimeMetadataProvider() {
        val provider = object : ReiAnixAnimeCatalogProvider {
            override val source = ReiAnixCatalogSource.ANIMAP
            override suspend fun searchAnime(query: String, limit: Int) =
                ReiAnixCatalogResult.Success(emptyList<ReiAnixCatalogAnime>())
            override suspend fun getAnime(externalId: String) =
                ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
        }
        try {
            ReiAnixCatalogProviderRegistry(listOf(provider))
            throw AssertionError("AniMap must remain ID-resolver-only.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Only metadata/artwork sources"))
        }
    }

    @Test
    fun rateLimitAndServerErrorsPreserveRetryAfter() {
        assertEquals(ReiAnixCatalogError.RateLimited(90_000L), reiAnixCatalogHttpError(429, "90", 1_000L))
        assertEquals(ReiAnixCatalogError.Server(503), reiAnixCatalogHttpError(503))
        assertEquals(ReiAnixCatalogError.Http(404), reiAnixCatalogHttpError(404))
        assertNull(parseCatalogRetryAfterMillis("invalid", 1_000L))
    }

    @Test
    fun matchingRequiresExactTitleYearFormatKindAndConsistentSeason() {
        val local = ReiAnixCatalogIdentity(
            titles = setOf("Kōtetsujou no Kabaneri", "Kabaneri of the Iron Fortress"),
            releaseYear = 2016, format = "TV", mediaKind = ReiAnixCatalogMediaKind.SERIES,
            seasonNumber = 1,
        )
        val remote = ReiAnixCatalogAnime(
            source = ReiAnixCatalogSource.KITSU,
            ids = ReiAnixExternalCatalogIds(kitsuId = "123"),
            title = "Kabaneri of the Iron Fortress",
            releaseYear = 2016, format = "TV", mediaKind = ReiAnixCatalogMediaKind.SERIES,
            seasonNumber = 1,
        )
        val match = ReiAnixCatalogIdentityMatcher.verify(12L, local, remote)
        assertEquals(12L, match?.localAnimeId)
        assertEquals("kabaneri of the iron fortress", match?.matchedTitle)
        assertNull(ReiAnixCatalogIdentityMatcher.verify(12L, local, remote.copy(releaseYear = 2019)))
        assertNull(ReiAnixCatalogIdentityMatcher.verify(12L, local, remote.copy(format = "MOVIE")))
        assertNull(ReiAnixCatalogIdentityMatcher.verify(12L, local, remote.copy(seasonNumber = 2)))
        assertNull(ReiAnixCatalogIdentityMatcher.verify(
            12L, local, remote.copy(title = "A Different Anime", alternativeTitles = emptySet()),
        ))
    }

    @Test
    fun artworkCandidatesRequireAValidHttpUrl() {
        val anime = ReiAnixCatalogAnime(
            source = ReiAnixCatalogSource.JIKAN,
            ids = ReiAnixExternalCatalogIds(myAnimeListId = 1L),
            title = "Anime", releaseYear = 2020, format = "TV",
            mediaKind = ReiAnixCatalogMediaKind.SERIES,
        )
        val withArtwork = anime.copy(coverUrl = "https://cdn.example/poster.jpg")
        assertEquals("https://cdn.example/poster.jpg", withArtwork.artworkCandidates().single().url)
        try {
            anime.copy(coverUrl = "file:///tmp/poster.jpg")
            throw AssertionError("Local files are not valid provider image URLs.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTP(S)"))
        }
    }
}
