package com.reiflix.reiflix_local.data.library

import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixArtworkRecord
import com.reiflix.reiflix_local.ui.mapper.LibraryUiMappers
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReiAnixNativeLibraryCatalogProjectorArtworkTest {

    @Test
    fun targetedProjectionKeepsCurrentCoverFirstAndSeparatesBackdrop() {
        val projected = ReiAnixNativeLibraryCatalogProjector.projectArtwork(
            anime = animeRecord(),
            artworkRows = listOf(
                artwork(
                    id = 1L,
                    artworkType = "poster",
                    localPath = "/cache/alternate-poster.jpg",
                    externalUrl = "https://cdn.example/alternate.jpg",
                ),
                artwork(
                    id = 2L,
                    artworkType = "backdrop",
                    localPath = "/cache/backdrop.jpg",
                    externalUrl = "https://cdn.example/backdrop.jpg",
                ),
            ),
        )

        assertEquals("/cache/alternate-poster.jpg", projected?.localPath)
        assertEquals(
            listOf("/cache/alternate-poster.jpg", "/cache/cover-cache.jpg"),
            projected?.localCandidates,
        )
        assertEquals("https://cdn.example/current.jpg", projected?.externalUrl)
        assertEquals(
            listOf("https://cdn.example/current.jpg", "https://cdn.example/alternate.jpg"),
            projected?.externalCandidates,
        )
        assertEquals("/cache/backdrop.jpg", projected?.backdropLocalPath)
        assertEquals("https://cdn.example/backdrop.jpg", projected?.backdropExternalUrl)
    }

    @Test
    fun metadataBannerRemainsBackdropFallbackWhenNoBackdropRowExists() {
        val projected = ReiAnixNativeLibraryCatalogProjector.projectArtwork(
            anime = animeRecord(bannerUrl = "https://cdn.example/banner.jpg"),
            artworkRows = emptyList(),
        )

        assertEquals("https://cdn.example/banner.jpg", projected?.backdropExternalUrl)
    }

    @Test
    fun changedMetadataUrlIsProjectedWithoutKeepingTheOldUrlAsPrimary() {
        val first = ReiAnixNativeLibraryCatalogProjector.projectArtwork(
            anime = animeRecord(),
            artworkRows = emptyList(),
        )
        val changed = ReiAnixNativeLibraryCatalogProjector.projectArtwork(
            anime = animeRecord().copy(coverUrl = "https://cdn.example/new.jpg", coverCache = null),
            artworkRows = emptyList(),
        )

        assertEquals("https://cdn.example/current.jpg", first?.externalUrl)
        assertEquals("https://cdn.example/new.jpg", changed?.externalUrl)
        assertEquals(listOf("https://cdn.example/new.jpg"), changed?.externalCandidates)
    }

    @Test
    fun missingArtworkDoesNotRemoveAnimeOrChangeItsStableIdentity() {
        val first = LibraryUiMappers.anime(mapOf("id" to 10L, "title" to "Anime A"))
        val second = LibraryUiMappers.anime(mapOf("id" to 20L, "title" to "Anime B"))
        val source = listOf(first, second)
        val withCover = ReiAnixNativeLibraryCatalogProjector.replaceArtworkForAnime(
            animes = source,
            animeId = first.id,
            artwork = ReiAnixArtworkUiModel(
                localPath = "/cache/anime-a.jpg",
                externalUrl = "https://cdn.example/anime-a.jpg",
            ),
        )
        val withoutCover = ReiAnixNativeLibraryCatalogProjector.replaceArtworkForAnime(
            animes = withCover,
            animeId = first.id,
            artwork = null,
        )

        assertEquals(2, withoutCover.size)
        assertEquals(first.id, withoutCover.first().id)
        assertEquals(first.stableKey, withoutCover.first().stableKey)
        assertNull(withoutCover.first().artwork)
        assertSame(second, withoutCover[1])
    }

    @Test
    fun newerCanonicalMissingArtworkClearsStalePageArtwork() {
        val pageAnime = LibraryUiMappers.anime(mapOf("id" to 10L, "title" to "Anime A"))
            .copy(
                artwork = ReiAnixArtworkUiModel(
                    localPath = "/cache/stale.jpg",
                    externalUrl = "https://cdn.example/stale.jpg",
                ),
            )
        val secondPageAnime = LibraryUiMappers.anime(mapOf("id" to 20L, "title" to "Anime B"))
        val canonicalAnime = pageAnime.copy(artwork = null)
        val canonicalSecondAnime = secondPageAnime.copy(
            artwork = ReiAnixArtworkUiModel(
                localPath = "/cache/current.jpg",
                externalUrl = "https://cdn.example/current.jpg",
            ),
        )

        val merged = ReiAnixNativeLibraryCatalogProjector.mergeLatestArtwork(
            pageAnimes = listOf(pageAnime, secondPageAnime),
            canonicalAnimes = listOf(canonicalAnime, canonicalSecondAnime),
        )

        assertNull(merged[0].artwork)
        assertEquals(pageAnime.stableKey, merged[0].stableKey)
        assertEquals(canonicalSecondAnime.artwork, merged[1].artwork)
    }

    @Test
    fun unknownAnimeIdentityLeavesTheListUntouched() {
        val anime = LibraryUiMappers.anime(mapOf("id" to 10L, "title" to "Anime A"))
        val animes = listOf(anime)

        val result = ReiAnixNativeLibraryCatalogProjector.replaceArtworkForAnime(
            animes = animes,
            animeId = 999L,
            artwork = null,
        )

        assertSame(animes, result)
    }

    private fun animeRecord(
        coverUrl: String? = "https://cdn.example/current.jpg",
        coverCache: String? = "/cache/cover-cache.jpg",
        bannerUrl: String? = null,
    ) = ReiAnixAnimeRecord(
        id = 42L,
        lookupTitle = "anime",
        anilistId = 123L,
        title = "Anime",
        romaji = null,
        english = null,
        nativeTitle = null,
        aliasesJson = null,
        description = null,
        descriptionOriginal = null,
        coverUrl = coverUrl,
        coverCache = coverCache,
        bannerUrl = bannerUrl,
        genresJson = null,
        year = 2024,
        season = null,
        status = null,
        episodesCount = null,
        duration = null,
        score = null,
        format = null,
        studio = null,
        metadataUpdatedAt = null,
        metadataFetchedAt = null,
        metadataSource = null,
        metadataConfidence = null,
        metadataStatus = null,
        metadataManualFieldsJson = null,
        favorite = false,
        userTagsJson = null,
        mediaKind = "tv",
        isPinned = false,
        personalNote = null,
        addedAt = 1.0,
        anilistMatchStatus = null,
        anilistMatchScore = null,
        anilistMatchMargin = null,
        anilistMatchManual = null,
    )

    private fun artwork(
        id: Long,
        artworkType: String,
        localPath: String?,
        externalUrl: String?,
    ) = ReiAnixArtworkRecord(
        id = id,
        entityType = "anime",
        entityId = "42",
        artworkType = artworkType,
        source = "anilist",
        sourceRef = null,
        localPath = localPath,
        externalUrl = externalUrl,
        manual = false,
        priority = 100,
        status = "ready",
        discoveredAt = 1.0,
        updatedAt = 2.0,
        lastAttemptAt = null,
        failureCount = 0,
        artworkKey = null,
        variant = null,
        byteSize = null,
        width = null,
        height = null,
        checksum = null,
        contentType = null,
        lastAccess = null,
        nextRetryAt = null,
        httpStatus = null,
    )
}
