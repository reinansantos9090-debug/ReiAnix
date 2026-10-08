import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MAIN = ROOT / "main.py"
BRIDGE = ROOT / "core/android_bridge.py"


class PlayerForensicTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.bridge = BRIDGE.read_text(encoding="utf-8")

    def test_player_has_distinct_activity_instance_identity(self):
        self.assertIn("private val activityInstanceId = UUID.randomUUID().toString()", self.player)
        self.assertIn('.put("activityInstanceId", activityInstanceId)', self.player)
        self.assertIn("MainActivity.notePlayerActivityCreated(", self.player)
        self.assertIn("activityInstanceId", self.player)
        self.assertIn("transitionGeneration", self.player)
        self.assertIn("MainActivity.notePlayerActivityDestroyed(activityInstanceId, playerSessionId)", self.player)

    def test_destroy_publishes_exit_before_clearing_activity_identity(self):
        destroy = self.player[
            self.player.index("override fun onDestroy()"):
            self.player.index("/**", self.player.index("override fun onDestroy()"))
        ]
        self.assertLess(
            destroy.index('reportPlayerExit("activity_finish")'),
            destroy.index("MainActivity.notePlayerActivityDestroyed(activityInstanceId, playerSessionId)"),
        )

    def test_authorized_successor_activity_preserves_player_session(self):
        self.assertIn("MainActivity.isCurrentPlayerHandoff(", self.player)
        self.assertIn("if (originRequestId.isNotBlank() && isEpisodeSuccessor)", self.player)
        self.assertIn("episodeChangePending = true", self.player)
        self.assertIn("PLAYER_EPISODE_TRANSITION", self.player)
        self.assertIn("authorized_successor_activity", self.player)

    def test_stale_successor_still_closes_itself_without_emitting_exit(self):
        start = self.player.index("if (originRequestId.isNotBlank() && !isEpisodeSuccessor && !recreatedPlayer)")
        end = self.player.index("val traceEpisodeId", start)
        stale = self.player[start:end]
        self.assertIn("PLAYER_NEXT_STALE_REJECTED", stale)
        self.assertIn("suppressExitEvent = true", stale)
        self.assertIn("finish()", stale)
        self.assertNotIn("reportPlayerExit(", stale)

    def test_next_previous_transition_never_calls_finish(self):
        start = self.player.index("private fun requestEpisode")
        end = self.player.index("private fun seekToSavedPosition", start)
        transition = self.player[start:end]
        self.assertNotIn("finish()", transition)
        self.assertIn("episodeChangePending", transition)
        self.assertIn("NEXT_TRANSITION_STARTED", transition)
        self.assertIn("PREVIOUS_TRANSITION_STARTED", transition)
        self.assertIn("playbackWorker.submit", transition)

    def test_watchdog_is_generation_and_session_bound_and_diagnostic_only(self):
        start = self.player.index("private fun armEpisodeChangeTimeout(reason: String)")
        end = self.player.index("private val episodeChangeTimeout", start)
        helper = self.player[start:end]
        for token in (
            "episodeChangeTimeoutRequestId = requestId",
            "episodeChangeTimeoutUri = uri.toString()",
            "episodeChangeTimeoutGeneration = transitionGeneration",
            "episodeChangeTimeoutSessionId = playerSessionId",
            "episodeChangeTimeoutPlayerGeneration = playerGeneration",
            "handler.removeCallbacks(episodeChangeTimeout)",
            "handler.postDelayed(episodeChangeTimeout, 5_000L)",
        ):
            self.assertIn(token, helper)
        watchdog = self.player[
            self.player.index("private val episodeChangeTimeout"):
            self.player.index("private var retryCount", self.player.index("private val episodeChangeTimeout"))
        ]
        self.assertIn("NEXT_TRANSITION_STALLED", watchdog)
        self.assertIn("PREVIOUS_TRANSITION_STALLED", watchdog)
        self.assertNotIn("finish()", watchdog)
        self.assertNotIn("episodeChangePending = false", watchdog)

    def test_python_exit_and_lifecycle_callbacks_are_activity_scoped(self):
        for token in (
            "player_active_activity_instance_id",
            "stale_activity_instance",
            "stale_activity_instance_lifecycle",
            "activityInstanceId",
        ):
            self.assertIn(token, self.main)
        exit_block = self.main[
            self.main.index("elif event_type == 'player_exited':"):
            self.main.index("elif event_type == 'google_sign_in_started':", self.main.index("elif event_type == 'player_exited':"))
        ]
        self.assertIn("exit_activity_instance_id", exit_block)
        self.assertIn("player_active_activity_instance_id", exit_block)

    def test_next_and_previous_use_symmetric_session_validation(self):
        transition = self.main[
            self.main.index("elif event_type in {'player_next_request', 'player_previous_request'}:"):
            self.main.index("elif event_type == 'player_error':")
        ]
        self.assertIn('if not source_player_session_id:', transition)
        self.assertIn('NEXT_REQUEST_REJECTED" if is_next else "PREVIOUS_REQUEST_REJECTED', transition)
        self.assertIn('PLAYER_NEXT_STALE_REJECTED" if is_next else "PLAYER_PREVIOUS_STALE_REJECTED', transition)

    def test_android_host_tracks_authorized_handoff_and_activity_identity(self):
        for token in (
            "activePlayerActivityInstanceId",
            "notePlayerActivityCreated",
            "notePlayerActivityDestroyed",
            "isCurrentPlayerHandoff",
            "lastPlayerExitAtMs",
            "isPlayerTransitionRevoked",
        ):
            self.assertIn(token, self.main_activity)
        open_player = self.main_activity[
            self.main_activity.index("private fun openPlayer"):
            self.main_activity.index("private fun clearPendingPlay")
        ]
        self.assertIn("activePlayerActivityInstanceId", open_player)
        self.assertIn("PLAYER_HANDOFF_DISPATCHED", open_player)

    def test_unlisted_player_transition_is_not_treated_as_revoked(self):
        block = self.main_activity[
            self.main_activity.index("fun isPlayerTransitionRevoked"):
            self.main_activity.index("fun notePlayerExit")
        ]
        self.assertIn("revokedPlayerTransitions[request] ?: return false", block)
        self.assertNotIn("?: return true", block)

    def test_bridge_keeps_transition_origin_correlation(self):
        for token in (
            "origin_request_id",
            "origin_transition_generation",
            "origin_player_session_id",
            "transition_direction",
        ):
            self.assertIn(token, self.bridge)

    def test_media3_runtime_diagnostics_cover_state_surface_and_codec_signals(self):
        for token in (
            "onPlayWhenReadyChanged",
            "onPlaybackSuppressionReasonChanged",
            "onIsPlayingChanged",
            "onRenderedFirstFrame",
            "onVideoSizeChanged",
            "onSurfaceSizeChanged",
            "onDroppedVideoFrames",
            "onVideoCodecError",
            "playbackSuppressionReason",
            "PLAYER_EXIT_CLASSIFICATION",
        ):
            self.assertIn(token, self.player)

    def test_existing_media3_error_path_does_not_finish_activity(self):
        start = self.player.index("private fun showPlayerError")
        end = self.player.index("private fun userFailureDetailForFailure", start)
        error_path = self.player[start:end]
        self.assertIn("PLAYER_ERROR_STALE_IGNORED", error_path)
        self.assertIn("playbackErrorForGeneration = true", error_path)
        self.assertIn("publishPlayerError(", error_path)
        self.assertNotIn("finish()", error_path)

    def test_python_sources_remain_valid(self):
        for relative in ("main.py",):
            ast.parse((ROOT / relative).read_text(encoding="utf-8"), filename=relative)


if __name__ == "__main__":
    unittest.main()
