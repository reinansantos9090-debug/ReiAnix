package com.reiflix.reiflix_local.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilenameParserTest {
    @Test
    fun simpleTitleRemainsPartialWithoutInventingEpisodeOrSeason() {
        val parsed = MediaFilenameParser.parseVideoPath("Cowboy Bebop.mkv")

        assertEquals("Cowboy Bebop", parsed.animeTitle)
        assertEquals("Cowboy Bebop.mkv", parsed.originalFileName)
        assertEquals("Cowboy Bebop", parsed.baseName)
        assertNull(parsed.season)
        assertNull(parsed.episodeNumber)
        assertEquals(ParsedMediaKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.unknownFields.contains(ParsedMediaField.SEASON))
        assertTrue(parsed.unknownFields.contains(ParsedMediaField.EPISODE_NUMBER))
    }

    @Test
    fun explicitEpisodeMarkersSupportOneAndMultipleDigits() {
        val oneDigit = MediaFilenameParser.parseVideoPath("Frieren Episode 1.mkv")
        val multipleDigits = MediaFilenameParser.parseVideoPath("One Piece EP1100.mkv")

        assertEquals(1.0, oneDigit.episodeNumber!!, 0.0)
        assertEquals(1, oneDigit.season)
        assertEquals("episode_marker", oneDigit.identificationSource)
        assertEquals(1100.0, multipleDigits.episodeNumber!!, 0.0)
    }

    @Test
    fun seasonEpisodeAndAlternateSeparatorsAreRecognized() {
        val sxxexx = MediaFilenameParser.parseVideoPath("Anime S02E03.mkv")
        val tempEpisode = MediaFilenameParser.parseVideoPath("Anime Temporada_02_EP-003v2.mkv")
        val cross = MediaFilenameParser.parseVideoPath("Anime 2x03.mkv")

        assertEquals(2, sxxexx.season)
        assertEquals(3.0, sxxexx.episodeNumber!!, 0.0)
        assertEquals(2, tempEpisode.season)
        assertEquals(3.0, tempEpisode.episodeNumber!!, 0.0)
        assertEquals(2, cross.season)
        assertEquals(3.0, cross.episodeNumber!!, 0.0)
    }

    @Test
    fun releaseGroupResolutionCodecAndAudioTokensAreExposed() {
        val parsed = MediaFilenameParser.parseVideoPath(
            "[SubsPlease] One.Piece.S02E03.Title.1080p.WEB-DL.x265.Dual.Audio.pt-BR.mkv"
        )

        assertEquals("One Piece", parsed.animeTitle)
        assertEquals("Title", parsed.displayTitle)
        assertTrue(parsed.technicalTokens.any { it.equals("1080p", ignoreCase = true) })
        assertTrue(parsed.technicalTokens.any { it.equals("WEB-DL", ignoreCase = true) })
        assertTrue(parsed.technicalTokens.any { it.equals("x265", ignoreCase = true) })
        assertTrue(parsed.technicalTokens.any { it.contains("audio", ignoreCase = true) })
        assertTrue(parsed.technicalTokens.any { it.equals("pt-BR", ignoreCase = true) })
        assertEquals(MediaIdentificationConfidence.HIGH, parsed.fieldConfidence[ParsedMediaField.EPISODE_NUMBER])
    }

    @Test
    fun everyLegacyVideoExtensionIsRecognizedCaseInsensitively() {
        val expected = setOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".flv", ".wmv")

        assertEquals(expected, MediaFilenameParser.supportedVideoExtensions)
        expected.forEach { extension ->
            assertTrue(
                "$extension should be supported",
                MediaFilenameParser.parseVideoPath("Show Episode 01${extension.uppercase()}").isSupportedVideoExtension
            )
        }
        assertFalse(MediaFilenameParser.parseVideoPath("Show Episode 01.xyz").isSupportedVideoExtension)
        assertFalse(MediaFilenameParser.parseVideoPath("Show Episode 01").isSupportedVideoExtension)
    }

    @Test
    fun unknownExtensionDoesNotPreventSafeNameParsing() {
        val parsed = MediaFilenameParser.parseVideoPath("Show Episode 12.xyz")

        assertEquals("Show", parsed.animeTitle)
        assertEquals(12.0, parsed.episodeNumber!!, 0.0)
        assertEquals(".xyz", parsed.extension)
        assertFalse(parsed.isSupportedVideoExtension)
    }

    @Test
    fun episodeDisplayTitlePreservesOrdinaryWordsWhileRemovingTechnicalTokens() {
        val parsed = MediaFilenameParser.parseVideoPath(
            "Show Episode 03 - Complete Batch 1080p.WEB-DL.mkv"
        )

        // core.library_parser._episode_title_after removes release tokens, not
        // words such as "Complete" or "Batch" that happen to also be title text.
        assertEquals("Complete Batch", parsed.displayTitle)
    }

    @Test
    fun episodeDisplayTitleFallsBackWhenSuffixHasOnlyVersionNumber() {
        val parsed = MediaFilenameParser.parseVideoPath("Show Episode 03 - v2.mkv")

        assertEquals("Show", parsed.displayTitle)
    }

    @Test
    fun titleAndTechnicalNormalizationPreserveUnicodeText() {
        val accented = MediaFilenameParser.parseVideoPath("Café des Étoiles Episode 02.mkv")
        val japanese = MediaFilenameParser.parseVideoPath("/library/進撃の巨人/Season 01/teaser_日本.mkv", "/library")

        assertEquals("Café des Étoiles", accented.animeTitle)
        assertEquals(2.0, accented.episodeNumber!!, 0.0)
        assertEquals("進撃の巨人", japanese.animeTitle)
        assertNull(japanese.episodeNumber)
        assertEquals("Café des Étoiles Episode 02.mkv", accented.originalFileName)
    }

    @Test
    fun yearsAndPureNumericFilenamesAreNotPromotedToEpisodes() {
        listOf(
            "001.mkv", "01.mkv", "2024.mkv", "Anime 2024.mkv",
            "Anime.2160p.mkv", "Anime.10bit.mkv", "Anime.x265.mkv",
            "Anime.Vol.01.mkv", "Anime.Disc.01.mkv"
        ).forEach { name ->
            val parsed = MediaFilenameParser.parseVideoPath(name)
            assertNull("$name must not fabricate an episode", parsed.episodeNumber)
        }
    }

    @Test
    fun codecNumbersAndVolumeDiscNumbersMatchLegacyPythonExclusions() {
        listOf(
            "Show h.10.mkv",
            "Show codec 4320.mkv",
            "Show x 8.mkv",
            "Show Vol.02 and 03.mkv",
            "Show Disc 01 and 02.mkv",
        ).forEach { name ->
            assertNull("$name must not become an episode under Python-compatible filtering",
                MediaFilenameParser.parseVideoPath(name).episodeNumber)
        }
    }

    @Test
    fun ambiguousNumericSuffixIsReportedRatherThanHidden() {
        val parsed = MediaFilenameParser.parseVideoPath("Series - 02 - 03.mkv")

        assertEquals(3.0, parsed.episodeNumber!!, 0.0)
        assertEquals(MediaIdentificationConfidence.MEDIUM, parsed.confidence)
        assertTrue(parsed.unresolved.contains("multiple_numeric_candidates"))
        assertTrue(parsed.ambiguities.contains("multiple_numeric_candidates"))
    }

    @Test
    fun fileSeasonWinsButConflictWithFolderSeasonRemainsExplicit() {
        val parsed = MediaFilenameParser.parseVideoPath(
            "/library/Anime/Season 2/Anime.S01E03.mkv", "/library"
        )

        assertEquals(1, parsed.season)
        assertEquals(3.0, parsed.episodeNumber!!, 0.0)
        assertEquals(MediaIdentificationConfidence.MEDIUM, parsed.confidence)
        assertTrue(parsed.ambiguities.any { it.startsWith("season_conflict:") })
    }

    @Test
    fun specialsMoviesAbsoluteNumbersAndMultiEpisodeRangesRemainDistinct() {
        val special = MediaFilenameParser.parseVideoPath("Show OVA 01.mkv")
        val seasonZero = MediaFilenameParser.parseVideoPath("Show S00E01.mkv")
        val movie = MediaFilenameParser.parseVideoPath("Show Movie.mkv")
        val absolute = MediaFilenameParser.parseVideoPath("One Piece ABS1122.mkv")
        val multi = MediaFilenameParser.parseVideoPath("Show S01E01E02.mkv")

        assertEquals(ParsedMediaKind.OVA, special.kind)
        assertEquals(1.0, special.episodeNumber!!, 0.0)
        assertEquals(ParsedMediaKind.SPECIAL, seasonZero.kind)
        assertEquals(1.0, seasonZero.episodeNumber!!, 0.0)
        assertEquals(ParsedMediaKind.MOVIE, movie.kind)
        assertNull(movie.episodeNumber)
        assertEquals(1122, absolute.absoluteNumber)
        assertNull(absolute.episodeNumber)
        assertEquals(1.0, multi.episodeNumber!!, 0.0)
        assertTrue(multi.ambiguities.contains("multi_episode_range"))
    }

    @Test
    fun folderContextAndEmptyInputsRemainConservative() {
        val fromFolder = MediaFilenameParser.parseVideoPath("/library/One Piece/Season 03/teaser.mkv", "/library")
        val empty = MediaFilenameParser.parseVideoPath("")

        assertEquals("One Piece", fromFolder.animeTitle)
        assertEquals(3, fromFolder.season)
        assertNull(fromFolder.episodeNumber)
        assertTrue(fromFolder.unresolved.contains("episode_not_identified"))
        assertEquals("Arquivo não identificado", empty.animeTitle)
        assertTrue(empty.unknownFields.contains(ParsedMediaField.TITLE))
        assertTrue(empty.unknownFields.contains(ParsedMediaField.EXTENSION))
    }

    @Test
    fun extensionAndFieldConfidenceAreExplicitEvenForPartialResults() {
        val parsed = MediaFilenameParser.parseVideoPath("Show S01E02.mkv")

        assertEquals("Show S01E02", parsed.baseName)
        assertEquals(".mkv", parsed.extension)
        assertTrue(parsed.isSupportedVideoExtension)
        assertEquals(MediaIdentificationConfidence.HIGH, parsed.fieldConfidence[ParsedMediaField.ORIGINAL_FILE_NAME])
        assertEquals(MediaIdentificationConfidence.HIGH, parsed.fieldConfidence[ParsedMediaField.BASE_NAME])
        assertEquals(MediaIdentificationConfidence.HIGH, parsed.fieldConfidence[ParsedMediaField.SEASON])
        assertEquals(MediaIdentificationConfidence.HIGH, parsed.fieldConfidence[ParsedMediaField.EPISODE_NUMBER])
    }
}
