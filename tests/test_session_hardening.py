import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
REQUEST = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8")
MAIN_ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
MAILBOX = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt").read_text(encoding="utf-8")
CONSUMPTION = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixConsumptionService.kt").read_text(encoding="utf-8")


class SessionHardeningTests(unittest.TestCase):
    def test_single_session_identity_path_exists(self):
        for token in ("playerSessionId", "PLAYER_SESSION_CREATED", "PLAYER_SESSION_INVALIDATED"):
            self.assertIn(token, MAIN_ACTIVITY + PLAYER + REQUEST)

    def test_command_validity_is_session_generation_aware(self):
        for token in ("isCurrentPreparation", "playerGeneration", "transitionGeneration", "expectedTransitionGeneration"):
            self.assertIn(token, PLAYER)
        self.assertIn("isCurrentPlayerHandoff(", MAIN_ACTIVITY)
        self.assertIn("PLAYER_HANDOFF_REJECTED", MAIN_ACTIVITY)
        self.assertIn("PLAYER_ERROR_STALE_IGNORED", PLAYER)

    def test_session_invalidation_clears_native_player_state(self):
        self.assertIn("private fun invalidateTransition(", PLAYER)
        self.assertIn("episodeChangePending = false", PLAYER)
        self.assertIn("sessionState = SessionState.DESTROYED", PLAYER)
        self.assertIn("reportPlayerExit(", PLAYER)
        self.assertIn("PLAYER_SESSION_INVALIDATED", PLAYER)

    def test_stale_mailbox_events_are_consumed_and_discarded(self):
        for token in ("eventId", "eventVersion", "writeInternal(", "durable = true"):
            self.assertIn(token, MAILBOX)
        self.assertIn("MAILBOX_STALE_COMMAND_DISCARDED", MAIN_ACTIVITY + PLAYER + BRIDGE)

    def test_progress_callbacks_are_session_checked(self):
        self.assertIn("saveProgress(", PLAYER)
        self.assertIn("playerSessionId", PLAYER)
        self.assertIn("playerGeneration", PLAYER)
        self.assertIn("transitionGeneration", PLAYER)
        self.assertIn("ReiAnixConsumptionService.playbackChanges", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8"))

    def test_player_error_is_ignored_when_stale(self):
        self.assertIn("PLAYER_ERROR_STALE_IGNORED", PLAYER)
        self.assertIn("suppliedSession == playerSessionId", PLAYER)
        self.assertIn("suppliedPlayerGeneration == playerGeneration", PLAYER)
        self.assertIn("suppliedTransitionGeneration == transitionGeneration", PLAYER)

    def test_player_exit_invalidates_session(self):
        self.assertIn("reportPlayerExit(", PLAYER)
        self.assertIn("PLAYER_SESSION_INVALIDATED", PLAYER)
        self.assertIn("notePlayerExit(", MAIN_ACTIVITY)
        self.assertIn("isCurrentPlayerHandoff(", MAIN_ACTIVITY)

    def test_initial_player_launch_gets_a_session_identity(self):
        self.assertIn("playerSessionId", REQUEST)
        self.assertIn('putExtra("playerSessionId"', REQUEST)
        self.assertIn("performOpenPlayer(", MAIN_ACTIVITY)
        self.assertIn("PLAYER_HANDOFF_DISPATCHED", MAIN_ACTIVITY)

    def test_initial_launch_is_authorized_by_live_session(self):
        self.assertIn("isCurrentPlayerHandoff(", MAIN_ACTIVITY)
        self.assertIn("activePlayerSessionId", MAIN_ACTIVITY)
        self.assertIn("originPlayerSessionId", PLAYER)

    def test_android_request_carries_player_session(self):
        self.assertIn("val playerSessionId: String", REQUEST)
        self.assertIn('"playerSessionId"', REQUEST)
        self.assertIn('get("player_session_id")', REQUEST)
        self.assertIn("player_session_id", BRIDGE)

    def test_native_activity_restores_session_and_generation(self):
        self.assertIn('savedInstanceState?.getString("player_session_id")', PLAYER)
        self.assertIn('savedInstanceState?.getLong("player_generation"', PLAYER)
        self.assertIn('outState.putString("player_session_id", playerSessionId)', PLAYER)
        self.assertIn('outState.putLong("player_generation", playerGeneration)', PLAYER)

    def test_reused_activity_must_match_same_session(self):
        self.assertIn("incomingPlayerSessionId == playerSessionId", PLAYER)
        self.assertIn("incomingOriginPlayerSessionId == playerSessionId", PLAYER)
        self.assertIn("incomingOriginTransitionGeneration == transitionPendingGeneration", PLAYER)

    def test_preparation_is_bound_to_player_and_transition_generation(self):
        self.assertIn("isCurrentPreparation", PLAYER)
        self.assertIn("generation == playerGeneration", PLAYER)
        self.assertIn("transitionGeneration == expectedTransitionGeneration", PLAYER)
        self.assertIn("pendingPreparation?.cancel(true)", PLAYER)

    def test_timeout_is_session_and_generation_bound(self):
        self.assertIn("episodeChangeTimeoutSessionId", PLAYER)
        self.assertIn("episodeChangeTimeoutPlayerGeneration", PLAYER)
        self.assertIn("episodeChangeTimeoutSessionId == playerSessionId", PLAYER)
        self.assertIn("episodeChangeTimeoutPlayerGeneration == playerGeneration", PLAYER)
        self.assertIn("PLAYER_TIMEOUT_STALE", PLAYER)

    def test_feedback_is_session_scoped(self):
        self.assertIn("feedbackSessionId", PLAYER)
        self.assertIn("feedbackRequestId", PLAYER)
        self.assertIn("feedbackPlayerGeneration", PLAYER)
        self.assertIn("feedbackSessionId == playerSessionId", PLAYER)

    def test_stale_player_error_is_not_shown_over_new_context(self):
        self.assertIn("PLAYER_ERROR_STALE_IGNORED", PLAYER)
        self.assertIn("sessionState == SessionState.ACTIVE", PLAYER)
        self.assertIn("suppliedSession == playerSessionId", PLAYER)
        self.assertIn("suppliedPlayerGeneration == playerGeneration", PLAYER)
        self.assertIn("suppliedTransitionGeneration == transitionGeneration", PLAYER)

    def test_first_frame_requires_current_session_and_generation(self):
        self.assertIn("FIRST_FRAME_RENDERED", PLAYER)
        self.assertIn("playerSessionId", PLAYER)
        self.assertIn("playerGeneration", PLAYER)
        self.assertIn("transitionGeneration", PLAYER)
        self.assertIn("isCurrentTransition(", PLAYER)

    def test_media_item_transition_is_not_confirmation(self):
        start = PLAYER.index("override fun onMediaItemTransition")
        end = PLAYER.index("override fun onPlaybackStateChanged", start)
        block = PLAYER[start:end]
        self.assertNotIn("PREVIOUS_TRANSITION_COMMITTED", block)
        self.assertNotIn("NEXT_TRANSITION_COMMITTED", block)

    def test_session_invalidated_callbacks_are_observable(self):
        for token in ("PLAYER_ERROR_STALE_IGNORED", "PLAYER_PREVIOUS_STALE_REJECTED", "PLAYER_NEXT_STALE_REJECTED"):
            self.assertIn(token, PLAYER + MAIN_ACTIVITY)

    def test_observability_contract(self):
        for token in ("PLAYER_SESSION_CREATED", "PLAYER_SESSION_INVALIDATED", "PLAYER_HANDOFF_REJECTED", "PLAYER_HANDOFF_DISPATCHED", "PLAYER_ERROR_STALE_IGNORED", "PLAYER_PREVIOUS_STALE_REJECTED", "PLAYER_NEXT_STALE_REJECTED"):
            self.assertIn(token, MAIN_ACTIVITY + PLAYER)

    def test_no_long_sleep_based_tests(self):
        source = Path(__file__).read_text(encoding="utf-8")
        self.assertNotIn("time" + ".sleep(", source)
        self.assertNotIn("asyncio" + ".sleep(", source)


if __name__ == "__main__":
    unittest.main()
