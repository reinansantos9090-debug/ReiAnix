package com.reiflix.reiflix_local.data.sqlite

import com.reiflix.reiflix_local.data.media.AnimeRecordId
import com.reiflix.reiflix_local.data.media.EpisodeRecordId
import com.reiflix.reiflix_local.data.media.LocalMediaIdentity
import com.reiflix.reiflix_local.data.media.LocalMediaReference
import com.reiflix.reiflix_local.data.media.MediaLocationKind
import com.reiflix.reiflix_local.data.media.MediaReferenceSyntax
import com.reiflix.reiflix_local.data.media.MediaFilenameParser
import com.reiflix.reiflix_local.data.media.MediaIdentificationConfidence
import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer
import com.reiflix.reiflix_local.data.media.ParsedMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDomainAndIdentityTest {
    @Test
    fun fileMediaStoreAndPrimarySafShareStablePhysicalIdentity() {
        val file = LocalMediaIdentity.identityFromDocument(
            uri = "file:///storage/emulated/0/Movies/Anime/E01.mkv",
            relativePath = "/storage/emulated/0/Movies/Anime/E01.mkv",
        )
        val mediaStore = LocalMediaIdentity.identityFromDocument(
            uri = "content://media/external/video/media/7",
            relativePath = "Movies/Anime/E01.mkv",
            volumeName = "external_primary",
        )
        val saf = LocalMediaIdentity.identityFromDocument(
            uri = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FAnime%2FE01.mkv",
            relativePath = "Anime/E01.mkv",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
        )

        assertEquals("shared:primary:movies/anime/e01.mkv", file)
        assertEquals(file, mediaStore)
        assertEquals(file, saf)
    }

    @Test
    fun emulatedStorageVolumeAliasesAndPathNormalizationAreStable() {
        assertEquals(
            "shared:primary:movies/series/episode 1.mkv",
            LocalMediaIdentity.identityFromRelativePath(
                "/storage/emulated/0/Movies/Series/Episode%201.mkv",
            ),
        )
        assertEquals(
            LocalMediaIdentity.identityFromRelativePath("Movies/Series/E01.mkv", "primary"),
            LocalMediaIdentity.identityFromRelativePath("Movies/Series/E01.mkv", "external"),
        )
        assertNotEquals(
            LocalMediaIdentity.identityFromRelativePath("Movies/Series/E01.mkv", "ABCD-1234"),
            LocalMediaIdentity.identityFromRelativePath("Movies/Series/E01.mkv", "WXYZ-5678"),
        )
    }

    @Test
    fun cloudDocumentsUriDoesNotMasqueradeAsSharedStorage() {
        val identity = LocalMediaIdentity.identityFromDocument(
            uri = "content://com.google.android.apps.docs.storage/document/root%3Afoo",
            relativePath = "Movies/Anime/E01.mkv",
            treeUri = "content://com.google.android.apps.docs.storage/tree/root%3Afoo",
        )

        assertEquals("uri:com.google.android.apps.docs.storage:document/root:foo", identity)
        assertNotEquals("shared:primary:movies/anime/e01.mkv", identity)
    }

    @Test
    fun unknownOrNonLocalReferenceRemainsUnresolved() {
        assertNull(LocalMediaIdentity.identityFromDocument(uri = null))
        assertNull(LocalMediaIdentity.identityFromDocument(uri = "https://example.invalid/video.mkv"))
    }

    @Test
    fun titleNormalizationIsUnicodeAwareAndPunctuationControlled() {
        assertEquals("dragon ball z", MediaTitleNormalizer.normalizeTitle("  Dragon_Ball.Z  "))
        assertEquals("épisode café", MediaTitleNormalizer.normalizeTitle("Épisode Café"))
        assertEquals(
            "naruto",
            MediaTitleNormalizer.normalizeMediaTitle("[SubsPlease] Naruto 1080p WEB-DL"),
        )
        assertNotEquals(
            MediaTitleNormalizer.normalizeTitle("Fullmetal Alchemist"),
            MediaTitleNormalizer.normalizeTitle("Fullmetal Alchemist Brotherhood"),
        )
    }

    @Test
    fun explicitSeasonAndEpisodeMarkerTakesPriorityOverReleaseNumbers() {
        val parsed = MediaFilenameParser.parseVideoPath(
            "/library/Naruto/Season 2/Naruto.S02E03.Title.1080p.WEB-DL.mkv",
            "/library",
        )

        assertEquals("Naruto", parsed.animeTitle)
        assertEquals(2, parsed.season)
        assertEquals(3.0, parsed.episodeNumber!!, 0.0)
        assertEquals(".mkv", parsed.extension)
        assertEquals(ParsedMediaKind.REGULAR, parsed.kind)
        assertEquals(MediaIdentificationConfidence.HIGH, parsed.confidence)
        assertTrue(parsed.evidence.any { it.startsWith("EXPLICIT_SEASON_EPISODE:") })
    }

    @Test
    fun unknownEpisodeInSeasonFolderUsesTheProbableAnimeFolder() {
        val parsed = MediaFilenameParser.parseVideoPath("/library/Naruto/Season 2/teaser.mkv", "/library")

        assertEquals("Naruto", parsed.animeTitle)
        assertEquals(2, parsed.season)
        assertEquals(ParsedMediaKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.unresolved.contains("episode_not_identified"))
    }

    @Test
    fun conflictingExplicitAndFolderSeasonsRemainUnresolved() {
        val parsed = MediaFilenameParser.parseVideoPath(
            "/library/Naruto/Season 2/Naruto.S01E03.mkv",
            "/library",
        )

        assertEquals(1, parsed.season)
        assertEquals(3.0, parsed.episodeNumber!!, 0.0)
        assertEquals(MediaIdentificationConfidence.MEDIUM, parsed.confidence)
        assertTrue(parsed.unresolved.any { it.startsWith("season_conflict:") })
    }

    @Test
    fun numericOnlyFilenameIsNotPromotedToAnEpisodeIdentity() {
        val parsed = MediaFilenameParser.parseVideoPath("/library/Naruto/Season 1/03.mkv")

        assertEquals(ParsedMediaKind.UNKNOWN, parsed.kind)
        assertNull(parsed.episodeNumber)
        assertTrue(parsed.unresolved.contains("numeric_filename_without_episode_marker"))
        assertEquals(MediaIdentificationConfidence.LOW, parsed.confidence)
    }

    @Test
    fun ambiguousNumericSuffixIsLowerConfidenceAndExposesUncertainty() {
        val parsed = MediaFilenameParser.parseVideoPath("/library/Series/Series - 02 - 03.mkv")

        assertEquals(ParsedMediaKind.REGULAR, parsed.kind)
        assertEquals(3.0, parsed.episodeNumber!!, 0.0)
        assertEquals(MediaIdentificationConfidence.MEDIUM, parsed.confidence)
        assertTrue(parsed.unresolved.contains("multiple_numeric_candidates"))
    }

    @Test
    fun mappedAnimeRetainsPersistedAndExternalIdentitiesAndUserData() {
        val mapped = animeRecord().toDomain()

        assertEquals(AnimeRecordId(12L), mapped.id)
        assertEquals("canonical-lookup-key", mapped.lookupKey)
        assertEquals(34000L, mapped.aniListId!!.value)
        assertEquals("[\"legacy-alias\"]", mapped.aliasesJson)
        assertEquals(true, mapped.favorite)
        assertEquals("[\"watch-later\"]", mapped.userTagsJson)
        assertEquals("do not overwrite", mapped.personalNote)
        assertEquals("manual", mapped.metadataStatus)
        assertEquals(true, mapped.aniListMatchManual)
    }

    @Test
    fun mappedEpisodeSeparatesRecordIdentityFromItsUriAndMediaIdentity() {
        val mapped = episodeRecord(
            path = "content://media/external/video/media/7",
            mediaIdentity = "shared:primary:movies/anime/e01.mkv",
        ).toDomain()

        assertEquals(EpisodeRecordId(7L), mapped.id)
        assertEquals(AnimeRecordId(12L), mapped.animeId)
        assertEquals(EpisodeRecordId(7L), mapped.media.episodeRecordId)
        assertEquals("content://media/external/video/media/7", mapped.media.uri)
        assertNull(mapped.media.filesystemPath)
        assertEquals("shared:primary:movies/anime/e01.mkv", mapped.media.identity!!.value)
        assertEquals(120.0, mapped.progressSeconds!!, 0.0)
        assertEquals(3.0, mapped.logicalIdentity!!.episodeNumber!!, 0.0)
        assertEquals(1, mapped.seasonNumber)
    }

    @Test
    fun incompleteEpisodeRemainsRepresentableWithoutInventingLogicalIdentity() {
        val mapped = episodeRecord(number = null, absoluteNumber = null).toDomain()

        assertNull(mapped.logicalIdentity)
        assertNull(mapped.media.identity)
        assertNull(mapped.media.sizeBytes)
        assertNull(mapped.watched)
    }


    @Test
    fun mediaReferenceSyntaxClassifiesSafConservativelyAndPreservesStoredLocation() {
        val cases = listOf(
            "" to MediaLocationKind.UNRESOLVED,
            "  \t  " to MediaLocationKind.UNRESOLVED,
            "/storage/emulated/0/Movies/Show/E01.mkv" to MediaLocationKind.FILESYSTEM_PATH,
            "relative/Show/E01.mkv" to MediaLocationKind.FILESYSTEM_PATH,
            "file://" to MediaLocationKind.FILE_URI,
            "file:///storage/emulated/0/Movies/Show/E01.mkv" to MediaLocationKind.FILE_URI,
            "content://com.android.externalstorage.documents/document/primary%3AMovies%2FShow%2FE01.mkv" to MediaLocationKind.SAF_DOCUMENT_URI,
            "content://com.android.externalstorage.documents/tree/primary%3AMovies" to MediaLocationKind.SAF_TREE_URI,
            "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FShow%2FE01.mkv" to MediaLocationKind.SAF_DOCUMENT_URI,
            "content://unknown.example.provider/tree/primary%3AMovies" to MediaLocationKind.CONTENT_URI,
            "content://unknown.example.provider/document/primary%3AMovies%2FShow%2FE01.mkv" to MediaLocationKind.CONTENT_URI,
            "content://com.android.externalstorage.documents/tree/primary%3AMovies/metadata/id" to MediaLocationKind.CONTENT_URI,
            "content://com.android.providers.downloads.documents/document/" to MediaLocationKind.CONTENT_URI,
            "content:malformed value" to MediaLocationKind.CONTENT_URI,
            "https://example.invalid/Show/E01.mkv" to MediaLocationKind.OTHER_URI,
            "ftp://example.invalid/Show/E01.mkv" to MediaLocationKind.OTHER_URI,
        )

        cases.forEach { (value, expected) ->
            assertEquals("Unexpected syntax classification for '$value'", expected, MediaReferenceSyntax.classify(value))
        }

        val stored = " content://unknown.example.provider/tree/primary%3AMovies "
        val reference = localMediaReference(stored)
        assertEquals(MediaLocationKind.CONTENT_URI, reference.locationKind)
        assertEquals(stored, reference.storedLocation)
        assertEquals(stored, reference.uri)
        assertEquals(stored, reference.contentUri)
        assertNull(reference.safTreeUri)
        assertNull(reference.safDocumentUri)
    }

    private fun localMediaReference(storedLocation: String) = LocalMediaReference(
        episodeRecordId = null,
        storedLocation = storedLocation,
        fileName = "E01.mkv",
        mimeType = null,
        sizeBytes = null,
        modifiedAtEpochSeconds = null,
        sourceFolder = null,
        relativePath = null,
        volumeId = null,
        volumeUuid = null,
        identity = null,
        availabilityState = null,
    )

    private fun animeRecord() = ReiAnixAnimeRecord(
        id = 12L,
        lookupTitle = "canonical-lookup-key",
        anilistId = 34000L,
        title = "Original Title",
        romaji = "Romaji",
        english = "English",
        nativeTitle = "Native",
        aliasesJson = "[\"legacy-alias\"]",
        description = "description",
        descriptionOriginal = "original description",
        coverUrl = "https://example.invalid/cover.jpg",
        coverCache = "covers/12.jpg",
        bannerUrl = "https://example.invalid/banner.jpg",
        genresJson = "[\"Action\"]",
        year = 2020,
        season = "SPRING",
        status = "FINISHED",
        episodesCount = 12,
        duration = 24,
        score = 83,
        format = "TV",
        studio = "Studio",
        metadataUpdatedAt = 100.0,
        metadataFetchedAt = 101.0,
        metadataSource = "anilist",
        metadataConfidence = "high",
        metadataStatus = "manual",
        metadataManualFieldsJson = "[\"title\"]",
        favorite = true,
        userTagsJson = "[\"watch-later\"]",
        mediaKind = "series",
        isPinned = false,
        personalNote = "do not overwrite",
        addedAt = 80.0,
        anilistMatchStatus = "matched",
        anilistMatchScore = 0.99,
        anilistMatchMargin = 0.2,
        anilistMatchManual = true,
    )

    private fun episodeRecord(
        path: String = "/library/Naruto/S01E03.mkv",
        number: Double? = 3.0,
        absoluteNumber: Double? = null,
        mediaIdentity: String? = null,
    ) = ReiAnixEpisodeRecord(
        id = 7L,
        animeId = 12L,
        path = path,
        fileName = "S01E03.mkv",
        season = 1,
        number = number,
        duration = 600.0,
        progress = 120.0,
        watched = null,
        mimeType = "video/x-matroska",
        fileSize = null,
        modifiedAt = 100.0,
        sourceFolder = "mediastore",
        absoluteNumber = absoluteNumber,
        relativePath = null,
        volumeId = null,
        volumeUuid = null,
        episodeType = "regular",
        episodeTitle = null,
        identificationSource = "sxxexx",
        identificationConfidence = "high",
        manualOverride = false,
        missing = false,
        lastPlayedAt = 99.0,
        mediaIdentity = mediaIdentity,
        availabilityState = "available",
    )
}
