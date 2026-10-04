import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
POLICY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/PlayerMediaPolicy.kt"
GRADLE = ROOT / "android/app/build.gradle.kts"


class PlaybackHardeningTests(unittest.TestCase):
    def test_main_player_handoff_has_no_provider_io_on_ui_thread(self):
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        block = main[main.index("    private fun openPlayer"):main.index("    private fun clearPendingPlay", main.index("    private fun openPlayer"))]
        self.assertNotIn("SafScanner.isAuthorizedDocument", block)
        self.assertNotIn("MediaStoreScanner.isAuthorizedDocument", block)
        self.assertNotIn("validatePlayerSource(", block)
        self.assertIn("playerActivityLauncher.launch(intent)", block)

    def test_player_uses_local_media3_without_parallel_decoder_stack(self):
        player = PLAYER.read_text(encoding="utf-8")
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn("ExoPlayer.Builder(this).build()", player)
        self.assertIn("MediaItem.Builder()", player)
        self.assertIn("player.prepare()", player)
        self.assertIn("player.setAudioAttributes(", player)
        self.assertIn("Media3", player)
        self.assertNotIn("FFmpeg", gradle)
        self.assertNotIn("media3-exoplayer-ffmpeg", gradle)
        self.assertNotIn("IjkPlayer", player)
        self.assertNotIn("VlcPlayer", player)

    def test_player_resolves_mime_and_prepares_local_io_off_main(self):
        player = PLAYER.read_text(encoding="utf-8")
        policy = POLICY.read_text(encoding="utf-8")
        self.assertIn("playbackWorker", player)
        self.assertIn("PREFLIGHT_ASYNC_START", player)
        self.assertIn("PREFLIGHT_ASYNC_OK", player)
        self.assertIn("LocalSubtitleResolver.resolve(this@NativePlayerActivity, localUri)", player)
        self.assertIn("contentResolver.getType(localUri)", player)
        self.assertIn("PlayerMediaPolicy.resolveVideoMimeType", player)
        self.assertIn("setMimeType(it)", player)
        self.assertIn("localSizeBytes(localUri)", player)
        self.assertIn("sizeBytes == 0L", player)
        self.assertIn("video/x-matroska", policy)
        self.assertIn("video/webm", policy)
        self.assertIn("video/x-msvideo", policy)

    def test_player_waits_for_ready_before_resume_and_tracks(self):
        player = PLAYER.read_text(encoding="utf-8")
        self.assertIn("Player.STATE_READY", player)
        self.assertIn("if (!initialSeekApplied)", player)
        self.assertIn("seekToSavedPosition(restoredPositionMs ?: savedPosition)", player)
        self.assertIn("onTracksChanged", player)
        self.assertIn("TRACKS_NO_AUDIO", player)
        self.assertIn("TRACKS_NO_SUBTITLE", player)
        self.assertIn("it.type == C.TRACK_TYPE_AUDIO", player)
        self.assertIn("it.type == C.TRACK_TYPE_TEXT", player)
        self.assertIn("it.isSupported", player)

    def test_player_has_decoder_diagnostics_and_categorized_recovery(self):
        player = PLAYER.read_text(encoding="utf-8")
        policy = POLICY.read_text(encoding="utf-8")
        for token in (
            "AnalyticsListener",
            "onVideoDecoderInitialized",
            "onAudioDecoderInitialized",
            "decoderVideoName",
            "decoderAudioName",
            "diagnosticPayload()",
            "ErrorCategory",
            "DECODER_UNSUPPORTED",
            "SOURCE_UNAVAILABLE",
            "TRANSIENT",
            "MAX_RETRY_ATTEMPTS",
        ):
            self.assertIn(token, player + policy)
        self.assertIn("classification.retryable", player)
        self.assertIn("showTechnicalInfo()", player)

    def test_resume_policy_clamps_invalid_positions(self):
        policy = POLICY.read_text(encoding="utf-8")
        self.assertIn("fun safeResumePosition", policy)
        self.assertIn("requestedMs <= 0L", policy)
        self.assertIn("durationMs <= 0L", policy)
        self.assertIn("requestedMs.coerceIn(0L, lastPlayable)", policy)

    def test_gesture_contract_remains_present(self):
        player = PLAYER.read_text(encoding="utf-8")
        for token in (
            "GESTURE_HORIZONTAL_IGNORED",
            "PlayerGesturePolicy",
            "systemUiController",
            "restoreSystemUiBeforeExit",
            "onBackPressedDispatcher",
            "onPictureInPictureModeChanged",
        ):
            self.assertIn(token, player)


if __name__ == "__main__":
    unittest.main()
