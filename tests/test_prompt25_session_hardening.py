import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
BRIDGE = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
REQUEST = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativePlayerRequest.kt").read_text(encoding="utf-8")
MAIN_ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")


class Prompt25SessionHardeningTests(unittest.TestCase):
    def test_single_session_identity_path_exists(self):
        for token in (
            "player_active_session_id",
            "player_active_player_generation",
            "player_session_active",
            "playerSessionId",
            "PLAYER_SESSION_CREATED",
            "PLAYER_SESSION_INVALIDATED",
        ):
            self.assertIn(token, MAIN + PLAYER + MAIN_ACTIVITY)

    def test_command_validity_is_session_generation_aware(self):
        self.assertIn("player_callback_is_current", MAIN)
        self.assertIn("stale_session", MAIN)
        self.assertIn("stale_player_generation", MAIN)
        self.assertIn("stale_transition_generation", MAIN)
        self.assertIn("PLAYER_COMMAND_REJECTED", MAIN)

    def test_session_invalidation_clears_python_player_state(self):
        start = MAIN.index("def invalidate_player_session")
        end = MAIN.index("def player_callback_is_current", start)
        block = MAIN[start:end]
        for token in (
            "cancel_player_transition(reason)",
            'player_session_active["value"] = False',
            'player_active_session_id["value"] = None',
            'player_active_request_id["value"] = None',
            'player_active_episode_id["value"] = None',
            'player_active_anime_id["value"] = None',
            'player_active_uri["value"] = None',
            'player_active_player_generation["value"] = 0',
        ):
            self.assertIn(token, block)

    def test_stale_mailbox_events_are_consumed_and_discarded(self):
        self.assertIn("MAILBOX_STALE_COMMAND_DISCARDED", MAIN)
        self.assertIn("bridge.drain()", MAIN)
        self.assertIn("bridge.acknowledge()", MAIN)

    def test_progress_callbacks_are_session_checked(self):
        start = MAIN.index("elif event_type in {'player_progress', 'player_paused', 'player_completed'}:")
        end = MAIN.index("elif event_type == 'player_error':", start)
        block = MAIN[start:end]
        self.assertIn("player_callback_is_current", block)
        self.assertIn("PLAYER_TASK_STALE", block)

    def test_player_error_is_ignored_when_stale(self):
        start = MAIN.index("elif event_type == 'player_error':")
        end = MAIN.index("elif event_type == 'player_exited':", start)
        block = MAIN[start:end]
        self.assertIn("PLAYER_ERROR_STALE_IGNORED", block)
        self.assertIn("player_callback_is_current", block)

    def test_player_exit_invalidates_session(self):
        start = MAIN.index("elif event_type == 'player_exited':")
        end = MAIN.index("elif event_type == 'google_sign_in_started':", start)
        block = MAIN[start:end]
        self.assertIn("invalidate_player_session(", block)
        self.assertIn("stale_player_exit", block)
        self.assertIn("MAILBOX_STALE_COMMAND_DISCARDED", block)

    def test_initial_player_launch_gets_a_session_identity(self):
        start = MAIN.index("def play_episode(")
        end = MAIN.index("def open_marathon(", start)
        block = MAIN[start:end]
        self.assertIn("launch_session_id = uuid.uuid4().hex", block)
        self.assertIn('player_active_session_id["value"] = launch_session_id', block)
        self.assertIn('player_session_active["value"] = True', block)
        self.assertIn("player_session_id=launch_session_id", block)
        self.assertIn("origin_player_session_id=launch_session_id", block)

    def test_initial_launch_is_authorized_by_live_session(self):
        start = MAIN.index("def play_episode(")
        end = MAIN.index("def open_marathon(", start)
        block = MAIN[start:end]
        self.assertIn('player_active_session_id["value"] == launch_session_id', block)
        self.assertIn('player_session_active["value"]', block)

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
        self.assertIn('diagnostic_session == str(context.get("player_session_id") or "")', MAIN)
        self.assertIn('frame_generation == int(context.get("target_player_generation") or 0)', MAIN)
        self.assertIn('frame_transition_generation == int(context.get("target_transition_generation") or 0)', MAIN)

    def test_media_item_transition_is_not_confirmation(self):
        start = PLAYER.index("override fun onMediaItemTransition")
        end = PLAYER.index("override fun onPlaybackStateChanged", start)
        block = PLAYER[start:end]
        self.assertNotIn("PREVIOUS_TRANSITION_COMMITTED", block)
        self.assertNotIn("NEXT_TRANSITION_COMMITTED", block)

    def test_session_invalidated_callbacks_are_observable(self):
        self.assertIn("PLAYER_CALLBACK_STALE", MAIN)
        self.assertIn("current_player_session_id", MAIN)

    def test_observability_contract(self):
        for token in (
            "PLAYER_SESSION_CREATED",
            "PLAYER_SESSION_INVALIDATED",
            "PLAYER_COMMAND_ACCEPTED",
            "PLAYER_COMMAND_REJECTED",
            "PLAYER_CALLBACK_STALE",
            "PLAYER_TASK_STALE",
            "MAILBOX_STALE_COMMAND_DISCARDED",
            "PLAYER_ERROR_STALE_IGNORED",
        ):
            self.assertIn(token, MAIN + PLAYER + MAIN_ACTIVITY)

    def test_no_long_sleep_based_tests(self):
        source = Path(__file__).read_text(encoding="utf-8")
        self.assertNotIn("time" + ".sleep(", source)
        self.assertNotIn("asyncio" + ".sleep(", source)


if __name__ == "__main__":
    unittest.main()
