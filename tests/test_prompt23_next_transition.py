import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
BRIDGE = ROOT / "core/android_bridge.py"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
REQUEST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt"


class Prompt23NextTransitionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.bridge = BRIDGE.read_text(encoding="utf-8")
        cls.main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.request = REQUEST.read_text(encoding="utf-8")
        cls.native_request_state = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt").read_text(encoding="utf-8")

    def test_native_transition_machine_has_explicit_runtime_phases(self):
        for token in (
            "TransitionPhase",
            "REQUESTED",
            "HANDOFF_DISPATCHED",
            "TARGET_ACTIVITY_ACTIVE",
            "PREPARING",
            "READY",
            "FIRST_FRAME",
            "COMMITTED",
            "FAILED",
            "setTransitionPhase",
            'transitionPhase.name',
        ):
            self.assertIn(token, self.player)

    def test_required_next_state_machine_markers_exist(self):
        for token in (
            "NEXT_REQUEST_ACCEPTED",
            "NEXT_REQUEST_REJECTED",
            "NEXT_REQUEST_STALE",
            "NEXT_REQUEST_DUPLICATE",
            "NEXT_REQUEST_CANCELLED",
            "NEXT_TRANSITION_STARTED",
            "NEXT_TRANSITION_INVALIDATED",
            "NEXT_TRANSITION_READY",
            "NEXT_TRANSITION_FIRST_FRAME",
            "NEXT_TRANSITION_COMMITTED",
            "NEXT_TRANSITION_FAILED",
            "PLAYER_NEXT_STALE_REJECTED",
        ):
            self.assertIn(token, self.main + self.player + self.main_activity)

    def test_native_timeout_is_diagnostic_only(self):
        start = self.player.index("private val episodeChangeTimeout")
        end = self.player.index("private var retryCount", start)
        watchdog = self.player[start:end]
        self.assertIn("NEXT_TRANSITION_STALLED", watchdog)
        self.assertIn("PREVIOUS_TRANSITION_STALLED", watchdog)
        self.assertIn("EPISODE_CHANGE_WATCHDOG", watchdog)
        self.assertNotIn("episodeChangePending = false", watchdog)

    def test_native_next_is_single_flight_and_session_scoped(self):
        start = self.player.index("private fun requestEpisode")
        end = self.player.index("private fun seekToSavedPosition", start)
        request = self.player[start:end]
        self.assertIn("episodeChangePending", request)
        self.assertIn("NEXT_REQUEST_DUPLICATE", request)
        self.assertIn("NEXT_REQUEST_ACCEPTED", request)
        self.assertIn("NEXT_TRANSITION_STARTED", request)
        self.assertIn("playerSessionId", request)
        self.assertIn("transitionGeneration", request)

    def test_transition_identity_checks_request_uri_generation_and_session(self):
        start = self.player.index("private fun isCurrentTransition")
        end = self.player.index("private fun requestEpisode", start)
        block = self.player[start:end]
        for token in (
            "generation == transitionGeneration",
            "requestId == expectedRequestId",
            "uri.toString() == expectedUri",
            "playerSessionId == expectedSessionId",
            "sessionState == SessionState.ACTIVE",
        ):
            self.assertIn(token, block)

    def test_reuse_requires_original_player_session(self):
        reuse = self.player[
            self.player.index("val expectedSuccessor"):
            self.player.index("autoplayNext =", self.player.index("val expectedSuccessor"))
        ]
        self.assertIn("originPlayerSessionId", reuse)
        self.assertIn("incomingOriginPlayerSessionId == playerSessionId", self.player)

    def test_ready_does_not_commit_and_first_frame_does(self):
        ready_start = self.player.index("Player.STATE_READY -> {")
        ready_end = self.player.index("Player.STATE_BUFFERING -> {", ready_start)
        ready = self.player[ready_start:ready_end]
        self.assertIn("NEXT_TRANSITION_READY", ready)
        self.assertNotIn("EPISODE_CHANGE_COMMITTED", ready)

        first_start = self.player.index('if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME))')
        first_end = self.player.index("override fun onMediaItemTransition", first_start)
        first = self.player[first_start:first_end]
        self.assertIn("NEXT_TRANSITION_FIRST_FRAME", first)
        self.assertIn("NEXT_TRANSITION_COMMITTED", first)
        self.assertIn("PREVIOUS_TRANSITION_FIRST_FRAME", first)
        self.assertIn("PREVIOUS_TRANSITION_COMMITTED", first)

    def test_duplicate_command_rejects_both_next_and_previous_before_transition(self):
        transition = self.main[
            self.main.index("duplicate_request = bool("):
            self.main.index("elif event_request_id:", self.main.index("duplicate_request = bool("))
        ]
        self.assertIn('"NEXT_REQUEST_DUPLICATE" if is_next else "PREVIOUS_REQUEST_DUPLICATE"', transition)
        self.assertEqual(
            transition.count("continue"),
            1,
            "duplicate player commands must exit before transition acceptance",
        )

    def test_handoff_diagnostic_preserves_transition_direction(self):
        open_player = self.main_activity[
            self.main_activity.index("private fun openPlayer"):
            self.main_activity.index("private fun clearPendingPlay")
        ]
        self.assertIn("transitionDirection = playerRequest.transitionDirection", open_player)
        self.assertIn('payload.put("transitionDirection", transitionDirection.uppercase())', self.main_activity)

    def test_stale_handoff_cannot_mutate_player_session_state(self):
        handoff = self.main[
            self.main.index('elif diagnostic_event == "PLAYER_HANDOFF_DISPATCHED"'):
            self.main.index('elif diagnostic_event == "PLAYER_ACTIVITY_RESULT"', self.main.index('elif diagnostic_event == "PLAYER_HANDOFF_DISPATCHED"'))
        ]
        self.assertIn('current_session_id = player_active_session_id["value"]', handoff)
        self.assertIn("not session_id", handoff)
        self.assertIn('not player_session_active["value"]', handoff)
        self.assertIn('current_session_id != session_id', handoff)
        self.assertIn('continue', handoff)
        self.assertLess(
            handoff.index('current_session_id = player_active_session_id["value"]'),
            handoff.index('player_active_request_id["value"] = event_request_id'),
        )
        self.assertIn('"PLAYER_NEXT_STALE_REJECTED"', handoff)
        self.assertIn('"PLAYER_PREVIOUS_STALE_REJECTED"', handoff)

    def test_python_next_has_pre_sqlite_post_sqlite_and_pre_bridge_guards(self):
        transition = self.main[
            self.main.index("elif event_type in {'player_next_request', 'player_previous_request'}:"):
            self.main.index("elif event_type == 'player_error':")
        ]
        self.assertIn("source_player_session_id", transition)
        self.assertIn("await asyncio.to_thread(", transition)
        self.assertIn("library.player_navigation", transition)
        self.assertIn("current_row_id", transition)
        self.assertIn("transition_guard=current_is_valid", transition)
        self.assertIn("target_request_id = await start_native_player", transition)

    def test_python_cancellation_invalidates_pending_next_without_canceling_itself(self):
        start = self.main.index("def cancel_player_transition")
        end = self.main.index("async def start_native_player", start)
        block = self.main[start:end]
        self.assertIn("current_task = asyncio.current_task()", block)
        self.assertIn("task is not current_task", block)
        self.assertIn("pending_next_transition", block)
        self.assertIn("NEXT_TRANSITION_INVALIDATED", block)
        self.assertIn("NEXT_REQUEST_CANCELLED", block)

    def test_new_activity_authorizes_valid_episode_successor_without_forcing_player_exit(self):
        create = self.player[
            self.player.index("override fun onCreate(savedInstanceState"):
            self.player.index("val traceEpisodeId")
        ]
        self.assertIn("MainActivity.isCurrentPlayerHandoff(", create)
        self.assertIn("if (originRequestId.isNotBlank() && isEpisodeSuccessor)", create)
        self.assertIn("PLAYER_EPISODE_TRANSITION", create)
        self.assertIn("authorized_successor_activity", create)
        successor = create[create.index("if (originRequestId.isNotBlank() && isEpisodeSuccessor)"):]
        self.assertNotIn("finish()", successor[:successor.index("publishPlayerLifecycle") if "publishPlayerLifecycle" in successor else len(successor)])

    def test_new_activity_still_rejects_stale_origin(self):
        create = self.player[
            self.player.index("override fun onCreate(savedInstanceState"):
            self.player.index("val traceEpisodeId")
        ]
        stale = create[
            create.index("if (originRequestId.isNotBlank() && !isEpisodeSuccessor && !recreatedPlayer)"):]
        self.assertIn('NEXT_REQUEST_STALE', stale)
        self.assertIn('PLAYER_NEXT_STALE_REJECTED', stale)
        self.assertIn('origin_on_new_activity', stale)
        self.assertIn("finish()", stale)
        self.assertIn("suppressExitEvent = true", stale)

    def test_activity_instance_identity_is_carried_through_lifecycle_and_exit(self):
        for token in (
            "activityInstanceId",
            "MainActivity.notePlayerActivityCreated",
            "MainActivity.notePlayerActivityDestroyed",
            "MainActivity.notePlayerExit(",
        ):
            self.assertIn(token, self.player)
        self.assertIn("isCurrentPlayerHandoff", self.main_activity)
        self.assertIn("originTransitionGeneration", self.main_activity)
        self.assertIn("originTransitionGeneration != activePlayerTransitionGeneration + 1L", self.main_activity)
        self.assertIn("activePlayerActivityInstanceId", self.main_activity)
        self.assertIn("activePlayerTransitionGeneration = 0L", self.main_activity)

    def test_python_player_callbacks_are_activity_instance_scoped(self):
        for token in (
            "player_active_activity_instance_id",
            "stale_activity_instance",
            "activityInstanceId",
            "stale_activity_instance_lifecycle",
            "current_activity_instance_id",
        ):
            self.assertIn(token, self.main)

    def test_recreation_restores_transition_state_before_origin_fencing(self):
        create = self.player[
            self.player.index("override fun onCreate(savedInstanceState"):
            self.player.index("publishPlayerLifecycle(\"onCreate\")")
        ]
        self.assertIn('transitionGeneration = savedInstanceState?.getLong("transition_generation", transitionGeneration)', create)
        self.assertIn('val recreatedPlayer = savedInstanceState?.getString("session_request_id")', create)
        self.assertIn('if (recreatedPlayer)', create)
        self.assertIn('episodeChangePending = savedInstanceState.getBoolean("episode_change_pending", false)', create)
        self.assertIn('TransitionPhase.valueOf(savedInstanceState.getString("transition_phase").orEmpty())', create)
        self.assertIn('if (recreatedPlayer) {', create)
        self.assertIn('if (originRequestId.isNotBlank() && !isEpisodeSuccessor && !recreatedPlayer)', create)
        save = self.player[
            self.player.index("override fun onSaveInstanceState"):
            self.player.index("override fun onDestroy", self.player.index("override fun onSaveInstanceState"))
        ]
        for token in (
            '"episode_change_pending"',
            '"transition_phase"',
            '"transition_source_request_id"',
            '"transition_source_generation"',
            '"transition_source_direction"',
            '"transition_source_monotonic_ns"',
            '"next_transition_active"',
            '"previous_transition_active"',
        ):
            self.assertIn(token, save)

    def test_activity_exit_and_recreation_have_native_session_authorization(self):
        for token in (
            "notePlayerSession",
            "notePlayerExit",
            "activePlayerSessionId",
            "activePlayerTransitionGeneration",
            "originTransitionGeneration",
            "stale_origin_player_session",
            "lastPlayerExitAtMs",
            "PLAYER_NEXT_STALE_REJECTED",
        ):
            self.assertIn(token, self.main_activity)
        self.assertIn("originPlayerSessionId", self.request)
        self.assertIn('putExtra("originPlayerSessionId", originPlayerSessionId)', self.request)
        self.assertIn("originPlayerSessionId", self.player)
        self.assertIn("MainActivity.notePlayerSession", self.player)
        self.assertIn("MainActivity.notePlayerExit", self.player)

    def test_bridge_propagates_origin_player_session(self):
        self.assertIn("origin_player_session_id", self.bridge)
        self.assertIn("created_monotonic_ns", self.bridge)
        self.assertIn('expected_event = "PLAYER_HANDOFF_DISPATCHED" if action == "play"', self.bridge)

    def test_timed_out_play_has_native_revocation_path(self):
        self.assertIn('"cancel_player_transition"', self.bridge)
        self.assertIn("NATIVE_PLAYER_TRANSITION_CANCEL_SENT", self.bridge)
        self.assertIn("revokePlayerTransition", self.main_activity)
        self.assertIn("isPlayerTransitionRevoked", self.main_activity)
        self.assertIn('"cancel_player_transition" ->', self.main_activity)
        self.assertIn('"cancel_player_transition"', self.native_request_state)

    def test_no_time_based_stale_rejection_for_active_slow_transition(self):
        transition = self.main[
            self.main.index("elif event_type in {'player_next_request', 'player_previous_request'}:"):
            self.main.index("elif event_type == 'player_error':")
        ]
        self.assertIn("mailbox_latency_ms", transition)
        self.assertNotIn("mailbox_latency_ms > 5000", transition)
        self.assertNotIn("mailbox_latency_ms >= 5000", transition)

    def test_next_stale_logs_carry_origin_and_current_generation(self):
        self.assertIn("PLAYER_NEXT_STALE_REJECTED", self.main + self.player + self.main_activity)
        self.assertTrue("origin_generation" in self.main or "originGeneration" in self.player or "originGeneration" in self.main_activity)
        self.assertTrue("current_generation" in self.main or "currentGeneration" in self.player or "currentGeneration" in self.main_activity)

    def test_tests_are_sleep_free(self):
        source = Path(__file__).read_text(encoding="utf-8")
        executable_source = "\n".join(
            line for line in source.splitlines()
            if "assertNotIn(" not in line
        )
        self.assertNotIn("time.sleep(", executable_source)
        self.assertNotIn("asyncio.sleep(", executable_source)


if __name__ == "__main__":
    unittest.main()
