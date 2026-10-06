import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
BRIDGE = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
REQUEST = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8")
ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")


class PreviousTransitionTests(unittest.TestCase):
    def test_required_previous_events_exist(self):
        required = [
            "PREVIOUS_REQUEST_RECEIVED",
            "PREVIOUS_REQUEST_ACCEPTED",
            "PREVIOUS_REQUEST_REJECTED",
            "PREVIOUS_REQUEST_STALE",
            "PREVIOUS_REQUEST_DUPLICATE",
            "PREVIOUS_REQUEST_CANCELLED",
            "PREVIOUS_TRANSITION_STARTED",
            "PREVIOUS_TRANSITION_INVALIDATED",
            "PREVIOUS_TRANSITION_READY",
            "PREVIOUS_TRANSITION_FIRST_FRAME",
            "PREVIOUS_TRANSITION_COMMITTED",
            "PREVIOUS_TRANSITION_FAILED",
        ]
        for token in required:
            self.assertIn(token, MAIN + PLAYER)

    def test_previous_single_flight_and_generation(self):
        self.assertIn("pending_previous_transition", MAIN)
        self.assertIn('player_transition_inflight["value"]', MAIN)
        self.assertIn('player_transition_generation["value"]', MAIN)
        self.assertIn("python_transition_generation", MAIN)

    def test_previous_session_and_stale_fencing(self):
        self.assertIn('player_session_active["value"]', MAIN)
        self.assertIn('player_active_session_id["value"]', MAIN)
        self.assertIn("source_player_session_id", MAIN)
        self.assertIn("PLAYER_PREVIOUS_STALE_REJECTED", MAIN + ACTIVITY + PLAYER)

    def test_previous_cross_direction_identity(self):
        self.assertIn('"direction": direction_name', MAIN)
        self.assertIn('"transitionDirection"', PLAYER)
        self.assertIn("transition_direction", BRIDGE)
        self.assertIn("transitionDirection", REQUEST)
        self.assertIn("originMonotonicNs", REQUEST + ACTIVITY + PLAYER)

    def test_previous_uses_canonical_navigation(self):
        self.assertIn("library.player_navigation", MAIN)
        self.assertNotIn("episode.number - 1", MAIN)
        self.assertNotIn("episode_number - 1", MAIN)

    def test_previous_sqlite_instrumentation(self):
        self.assertIn("PREVIOUS_SQLITE_QUERY_STARTED", MAIN)
        self.assertIn("PREVIOUS_SQLITE_QUERY_FINISHED", MAIN)
        self.assertIn("current_row_id", MAIN)
        self.assertIn("current_row_anime", MAIN)
        self.assertIn("current_row_path", MAIN)

    def test_previous_no_target_is_cancelled(self):
        self.assertIn("previous_no_target", MAIN)
        self.assertIn("PREVIOUS_TRANSITION_FAILED", MAIN)

    def test_previous_ready_waits_for_first_frame(self):
        self.assertIn("PREVIOUS_TRANSITION_READY", PLAYER)
        self.assertIn("PREVIOUS_TRANSITION_FIRST_FRAME", MAIN + PLAYER)
        self.assertIn("PREVIOUS_TRANSITION_COMMITTED", MAIN + PLAYER)

    def test_media_item_transition_is_not_confirmation(self):
        start = PLAYER.index("override fun onMediaItemTransition")
        end = PLAYER.index("override fun onPlaybackStateChanged", start)
        block = PLAYER[start:end]
        self.assertNotIn("PREVIOUS_TRANSITION_COMMITTED", block)
        self.assertNotIn("NEXT_TRANSITION_COMMITTED", block)

    def test_previous_timeout_is_fenced(self):
        start = PLAYER.index("private val episodeChangeTimeout")
        end = PLAYER.index("private var retryCount", start)
        block = PLAYER[start:end]
        self.assertIn("PREVIOUS_TRANSITION_STALLED", block)
        self.assertIn("timeoutContextValid", block)
        self.assertIn("episodeChangeTimeoutSessionId == playerSessionId", block)
        self.assertIn("episodeChangeTimeoutPlayerGeneration == playerGeneration", block)

    def test_previous_invalidation_clears_pending(self):
        start = PLAYER.index("private fun invalidateTransition")
        end = PLAYER.index("private fun isCurrentTransition", start)
        block = PLAYER[start:end]
        self.assertIn("PREVIOUS_TRANSITION_INVALIDATED", block)
        self.assertIn("PREVIOUS_REQUEST_CANCELLED", block)
        self.assertIn("previousTransitionActive = false", block)

    def test_previous_exit_rejects_old_request(self):
        self.assertIn("invalidate_player_session(", MAIN)
        self.assertIn('"player_exited"', MAIN)
        self.assertIn('player_session_active["value"] = False', MAIN)
        self.assertIn("PLAYER_PREVIOUS_STALE_REJECTED", ACTIVITY + PLAYER)

    def test_previous_reused_activity_requires_origin_fencing(self):
        self.assertIn("incomingOriginTransitionGeneration", PLAYER)
        self.assertIn("incomingOriginPlayerSessionId", PLAYER)
        self.assertIn("incomingOriginMonotonicNs", PLAYER)
        self.assertIn("incomingOriginTransitionDirection", PLAYER)

    def test_previous_new_activity_rejects_old_origin(self):
        self.assertIn("origin_on_new_activity", PLAYER)
        self.assertIn("PREVIOUS_REQUEST_STALE", PLAYER)
        self.assertIn("PLAYER_PREVIOUS_STALE_REJECTED", PLAYER)

    def test_previous_progress_save_before_navigation(self):
        request = PLAYER[PLAYER.index("private fun requestEpisode"):PLAYER.index("private fun seekToSavedPosition")]
        self.assertIn('saveProgress("player_progress", force = true)', request)
        self.assertIn('currentEpisodeId()', request)

    def test_previous_prepare_callbacks_are_generation_bound(self):
        self.assertIn("expectedTransitionGeneration", PLAYER)
        self.assertIn("transitionGeneration == expectedTransitionGeneration", PLAYER)

    def test_previous_does_not_use_time_only_staleness(self):
        self.assertNotIn("mailbox_latency_ms > 5000", MAIN)
        self.assertNotIn("mailbox_latency_ms >= 5000", MAIN)

    def test_previous_tests_do_not_sleep(self):
        source = Path(__file__).read_text(encoding="utf-8")
        self.assertNotIn("time" + ".sleep(", source)
        self.assertNotIn("asyncio" + ".sleep(", source)

    def test_previous_and_next_are_direction_fenced(self):
        self.assertIn('"NEXT_REQUEST_STALE" if is_next else "PREVIOUS_REQUEST_STALE"', MAIN)
        self.assertIn('"NEXT_REQUEST_DUPLICATE" if is_next else "PREVIOUS_REQUEST_DUPLICATE"', MAIN)
        self.assertIn('"NEXT_TRANSITION_STARTED" if is_next else "PREVIOUS_TRANSITION_STARTED"', MAIN)

    def test_previous_bridge_identity_survives_handoff(self):
        self.assertIn("origin_monotonic_ns", BRIDGE)
        self.assertIn("transition_direction", BRIDGE)
        self.assertIn("originMonotonicNs", REQUEST)
        self.assertIn("transitionDirection", REQUEST)


if __name__ == "__main__":
    unittest.main()
