import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
).read_text(encoding="utf-8")
POLICY = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/PlayerMediaPolicy.kt"
).read_text(encoding="utf-8")
POLICY_TEST = (
    ROOT
    / "android/app/src/test/kotlin/com/reiflix/reiflix_local/PlayerMediaPolicyTest.kt"
).read_text(encoding="utf-8")
MAIN_ACTIVITY = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
).read_text(encoding="utf-8")


class Media3ErrorTests(unittest.TestCase):
    def test_media3_error_pipeline_is_structured(self):
        for token in (
            "PlaybackFailureKind",
            "MEDIA_NOT_FOUND",
            "PERMISSION",
            "SOURCE",
            "PARSER",
            "DECODER",
            "CODEC",
            "RENDERER",
            "TIMEOUT",
            "LIFECYCLE",
            "STALE",
            "classifyPlaybackFailure",
            "isRetryableFailure",
        ):
            self.assertIn(token, POLICY)

    def test_player_error_records_root_cause_and_media3_context(self):
        for token in (
            "ExoPlaybackException",
            "throwableChain(error)",
            "rootCauseClass",
            "rootCauseMessage",
            "causeChain",
            "rendererIndex",
            "rendererType",
            "rendererName",
            "rendererFormatMimeType",
            "mediaPeriodId",
            "dataSourceUri",
            "lastLoadDataType",
            "lastLoadTrackType",
            "errorCodeName",
            "errorMessage",
        ):
            self.assertIn(token, PLAYER)

    def test_analytics_listener_captures_load_and_player_error_context(self):
        for token in (
            "override fun onPlayerError(",
            "override fun onLoadError(",
            "LoadEventInfo",
            "MediaLoadData",
            "MEDIA3_ANALYTICS_PLAYER_ERROR",
            "MEDIA3_ANALYTICS_LOAD_ERROR",
        ):
            self.assertIn(token, PLAYER)

    def test_open_handoff_and_media3_prepare_are_separate_stages(self):
        self.assertIn("PLAYER_HANDOFF_FAILED", MAIN_ACTIVITY)
        self.assertIn("PLAYER_HANDOFF_REJECTED", MAIN_ACTIVITY)
        self.assertIn('.put("stage", "handoff")', MAIN_ACTIVITY)
        self.assertIn("MEDIA3_PREPARE_DISPATCHED", PLAYER)
        self.assertIn("player.prepare()", PLAYER)
        self.assertIn("PLAYER_ERROR_BEFORE_READY", PLAYER)
        self.assertIn("PLAYER_ERROR_AFTER_READY_BEFORE_FIRST_FRAME", PLAYER)
        self.assertIn("PLAYBACK_AFTER_FIRST_FRAME", PLAYER)

    def test_first_frame_timeout_is_diagnostic_only(self):
        start = PLAYER.index('"event", "FIRST_FRAME_TIMEOUT"')
        end = PLAYER.index("private fun armFirstFrameDiagnostics", start)
        block = PLAYER[start:end]
        self.assertIn('"failureStage", "FIRST_FRAME_WAIT"', block)
        self.assertIn('"diagnosticOnly", true', block)
        self.assertNotIn("showPlayerError(", block)

    def test_retry_is_bounded_per_media_generation(self):
        retry_start = PLAYER.index("private fun retryCurrentMedia()")
        retry_end = PLAYER.index("private fun showPlayerError(", retry_start)
        retry_block = PLAYER[retry_start:retry_end]
        self.assertIn("currentFailureRetryable", retry_block)
        self.assertIn("MAX_RETRY_ATTEMPTS", retry_block)
        self.assertIn("retryCount += 1", retry_block)
        self.assertIn('prepareCurrentMedia(\n            "retry"', retry_block)
        self.assertIn('if (reason != "retry")', PLAYER)
        ready_start = PLAYER.index("Player.STATE_READY")
        ready_end = PLAYER.index("Player.STATE_BUFFERING", ready_start)
        self.assertNotIn("retryCount = 0", PLAYER[ready_start:ready_end])

    def test_error_generation_is_terminal_until_a_new_prepare_generation(self):
        for token in (
            "playbackErrorForGeneration",
            "playbackErrorForGeneration = true",
            "!playbackErrorForGeneration",
            "playbackErrorForGeneration = false",
            "PLAYER_ERROR_STALE_IGNORED",
        ):
            self.assertIn(token, PLAYER)

    def test_no_secondary_player_or_ffmpeg_was_introduced(self):
        self.assertEqual(1, PLAYER.count("ExoPlayer.Builder(this).build()"))
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn("FFmpeg", gradle)
        self.assertNotIn("media3-exoplayer-ffmpeg", gradle)

    def test_policy_unit_tests_cover_stage28_classes(self):
        for token in (
            "preciseFailureClassificationSeparatesLocalAndMedia3Failures",
            "preciseRetryPolicyNeverRetriesDeterministicSourceOrDecoderFailures",
            "FileNotFoundException",
            "DecoderInitializationException",
            "UnrecognizedInputFormatException",
            "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
        ):
            self.assertIn(token, POLICY_TEST)

    def test_race_guards_remain_in_place(self):
        for token in (
            "isCurrentPreparation(generation, localUri, preparationTransitionGeneration)",
            "generation == playerGeneration",
            "sessionState == SessionState.ACTIVE",
            "pendingPreparation?.cancel(true)",
            "transitionGeneration == expectedTransitionGeneration",
            "PLAYER_TIMEOUT_STALE",
            'invalidateTransition("player_error")',
            "PLAYER_ERROR_STALE_IGNORED",
        ):
            self.assertIn(token, PLAYER)


if __name__ == "__main__":
    unittest.main()
