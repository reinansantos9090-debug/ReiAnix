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

    def test_player_defers_nonessential_media_io_until_after_prepare(self):
        player = PLAYER.read_text(encoding="utf-8")
        policy = POLICY.read_text(encoding="utf-8")
        prepare = player[player.index("private fun prepareCurrentMedia"):player.index("private fun createPlayerListener")]
        self.assertIn("playbackWorker", prepare)
        self.assertIn("PREFLIGHT_ASYNC_START", prepare)
        self.assertIn("PREFLIGHT_ASYNC_OK", prepare)
        self.assertIn("hydrateLocalMediaReferencesAsync(", prepare)
        self.assertIn("player.setMediaItem(mediaItem, initialPositionMsForGeneration)", prepare)
        self.assertIn("player.prepare()", prepare)
        self.assertNotIn("LocalSubtitleResolver.resolve(this@NativePlayerActivity, localUri)", prepare)
        self.assertNotIn("contentResolver.getType(localUri)", prepare)
        self.assertNotIn("localSizeBytes(localUri)", prepare)
        self.assertIn("LocalSubtitleResolver.resolve(this@NativePlayerActivity, localUri)", player)
        self.assertIn("contentResolver.getType(localUri)", player)
        self.assertIn("PlayerMediaPolicy.resolveVideoMimeType", player)
        self.assertIn("localSizeBytes(localUri)", player)
        self.assertIn("video/x-matroska", policy)
        self.assertIn("video/webm", policy)
        self.assertIn("video/x-msvideo", policy)

    def test_player_preloads_resume_position_and_requests_autoplay_before_prepare(self):
        player = PLAYER.read_text(encoding="utf-8")
        prepare = player[player.index("private fun prepareCurrentMedia"):player.index("private fun createPlayerListener")]
        self.assertIn("Player.STATE_READY", player)
        self.assertIn("initialPositionMsForGeneration", prepare)
        self.assertIn("player.setMediaItem(mediaItem, initialPositionMsForGeneration)", prepare)
        play_index = prepare.index("player.playWhenReady = shouldPlayWhenReady")
        prepare_index = prepare.index("player.prepare()")
        self.assertLess(play_index, prepare_index)
        self.assertIn("if (!initialSeekApplied)", player)
        self.assertIn("RESUME_POSITION_ALREADY_PRELOADED", player)
        self.assertNotIn("seekToSavedPosition(restoredPositionMs ?: savedPosition)", prepare)
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

    def test_ready_state_clears_loading_and_first_frame_remains_authoritative(self):
        player = PLAYER.read_text(encoding="utf-8")
        state_start = player.index("override fun onPlaybackStateChanged(state: Int)")
        ready = player[player.index("Player.STATE_READY ->", state_start):player.index("Player.STATE_BUFFERING ->", state_start)]
        buffering = player[player.index("Player.STATE_BUFFERING ->", state_start):player.index("Player.STATE_ENDED ->", state_start)]
        self.assertIn("playerReadyAtMs = System.currentTimeMillis()", ready)
        self.assertIn("preparingIndicator.visibility = View.GONE", ready)
        self.assertIn("preparingIndicator.visibility = View.VISIBLE", buffering)
        self.assertIn("events.contains(Player.EVENT_RENDERED_FIRST_FRAME)", player)

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
