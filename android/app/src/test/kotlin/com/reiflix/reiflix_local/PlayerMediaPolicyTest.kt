package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.player.PlayerMediaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMediaPolicyTest {
    @Test
    fun resolvesFallbackMimeWhenProviderIsGeneric() {
        assertEquals(
            "video/x-matroska",
            PlayerMediaPolicy.resolveVideoMimeType("application/octet-stream", "episode.mkv"),
        )
        assertEquals(
            "video/webm",
            PlayerMediaPolicy.resolveVideoMimeType(null, "episode.webm"),
        )
        assertEquals(
            "video/x-msvideo",
            PlayerMediaPolicy.resolveVideoMimeType("binary/octet-stream", "episode.avi"),
        )
    }

    @Test
    fun normalizesCommonApplicationContainerMimeTypes() {
        assertEquals(
            "video/x-matroska",
            PlayerMediaPolicy.resolveVideoMimeType("application/x-matroska", "episode.mkv"),
        )
        assertEquals(
            "video/webm",
            PlayerMediaPolicy.resolveVideoMimeType("application/webm", "episode.webm"),
        )
        assertEquals(
            "video/mp4",
            PlayerMediaPolicy.resolveVideoMimeType("application/mp4", "episode.mp4"),
        )
    }

    @Test
    fun preservesValidProviderVideoMime() {
        assertEquals(
            "video/mp4",
            PlayerMediaPolicy.resolveVideoMimeType("video/mp4", "episode.mkv"),
        )
    }

    @Test
    fun doesNotGuessUnknownOrNonVideoProviderMime() {
        assertEquals(
            null,
            PlayerMediaPolicy.resolveVideoMimeType("application/json", "episode.mkv"),
        )
        assertEquals(
            null,
            PlayerMediaPolicy.resolveVideoMimeType(null, "episode.xyz"),
        )
    }

    @Test
    fun seekUsesMedia3CommandAvailabilityAsTheAuthoritativeContract() {
        assertTrue("Unknown duration must not block a seek Media3 explicitly allows",
            PlayerMediaPolicy.isSeekAllowed(true, true))
        assertTrue("An inconsistent seekable flag must not override an available seek command",
            PlayerMediaPolicy.isSeekAllowed(false, true))
        assertFalse("Never call seekTo when Media3 does not expose the seek command",
            PlayerMediaPolicy.isSeekAllowed(true, false))
        assertFalse(PlayerMediaPolicy.isSeekAllowed(false, false))
    }

    @Test
    fun media3DurationTakesPrecedenceOverLocalMetadataFallback() {
        assertEquals(12_000L, PlayerMediaPolicy.resolveDurationMs(12_000L, 10_000L))
        assertEquals(10_000L, PlayerMediaPolicy.resolveDurationMs(0L, 10_000L))
        assertEquals(10_000L, PlayerMediaPolicy.resolveDurationMs(-1L, 10_000L))
        assertEquals(0L, PlayerMediaPolicy.resolveDurationMs(0L, null))
        assertEquals(0L, PlayerMediaPolicy.resolveDurationMs(-1L, 0L))
    }

    @Test
    fun seekTargetIsClampedOnlyWhenDurationIsKnown() {
        assertEquals(0L, PlayerMediaPolicy.clampSeekPosition(-500L, 60_000L))
        assertEquals(60_000L, PlayerMediaPolicy.clampSeekPosition(70_000L, 60_000L))
        assertEquals(3_000L, PlayerMediaPolicy.clampSeekPosition(3_000L, 0L))
        assertEquals(3_000L, PlayerMediaPolicy.clampSeekPosition(3_000L, -1L))
    }

    @Test
    fun resumePositionIsAlwaysSafe() {
        assertEquals(0L, PlayerMediaPolicy.safeResumePosition(-1L, 3_000L))
        assertEquals(0L, PlayerMediaPolicy.safeResumePosition(0L, 3_000L))
        assertEquals(1_000L, PlayerMediaPolicy.safeResumePosition(1_000L, 3_000L))
        assertEquals(2_999L, PlayerMediaPolicy.safeResumePosition(5_000L, 3_000L))
        assertEquals(0L, PlayerMediaPolicy.safeResumePosition(5_000L, 0L))
    }

    @Test
    fun preciseFailureClassificationSeparatesLocalAndMedia3Failures() {
        assertEquals(
            PlayerMediaPolicy.PlaybackFailureKind.MEDIA_NOT_FOUND,
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_IO_FILE_NOT_FOUND",
                listOf("FileNotFoundException"),
            ).kind,
        )
        assertEquals(
            PlayerMediaPolicy.PlaybackFailureKind.PERMISSION,
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_IO_UNSPECIFIED",
                listOf("SecurityException", "SAF_PERMISSION_MISSING"),
            ).kind,
        )
        assertEquals(
            PlayerMediaPolicy.PlaybackFailureKind.PARSER,
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
                listOf("UnrecognizedInputFormatException"),
            ).kind,
        )
        assertEquals(
            PlayerMediaPolicy.PlaybackFailureKind.DECODER,
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_DECODER_INIT_FAILED",
                listOf("DecoderInitializationException"),
            ).kind,
        )
        assertEquals(
            PlayerMediaPolicy.PlaybackFailureKind.RENDERER,
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
                emptyList(),
            ).kind,
        )
    }

    @Test
    fun preciseRetryPolicyNeverRetriesDeterministicSourceOrDecoderFailures() {
        assertFalse(
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_IO_FILE_NOT_FOUND",
                listOf("FileNotFoundException"),
            ).retryable,
        )
        assertFalse(
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_DECODER_INIT_FAILED",
                listOf("DecoderInitializationException"),
            ).retryable,
        )
        assertTrue(
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_IO_UNSPECIFIED",
                emptyList(),
            ).retryable,
        )
        assertTrue(
            PlayerMediaPolicy.classifyPlaybackFailure(
                "ERROR_CODE_TIMEOUT",
                emptyList(),
            ).retryable,
        )
    }

    @Test
    fun errorClassificationDistinguishesDecoderSourceAndTransientFailures() {
        assertEquals(
            PlayerMediaPolicy.ErrorCategory.DECODER_UNSUPPORTED,
            PlayerMediaPolicy.classifyError("ERROR_CODE_DECODER_INIT_FAILED", listOf("DecoderInitializationException")),
        )
        assertEquals(
            PlayerMediaPolicy.ErrorCategory.SOURCE_UNAVAILABLE,
            PlayerMediaPolicy.classifyError("ERROR_CODE_IO_FILE_NOT_FOUND", listOf("FileNotFoundException")),
        )
        assertEquals(
            PlayerMediaPolicy.ErrorCategory.TRANSIENT,
            PlayerMediaPolicy.classifyError("ERROR_CODE_IO_UNSPECIFIED", emptyList()),
        )
        assertTrue(PlayerMediaPolicy.isRetryable(PlayerMediaPolicy.ErrorCategory.TRANSIENT))
        assertFalse(PlayerMediaPolicy.isRetryable(PlayerMediaPolicy.ErrorCategory.DECODER_UNSUPPORTED))
    }
}
