import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MAIN = ROOT / "main.py"
BRIDGE = ROOT / "core/android_bridge.py"
STORE = ROOT / "core/library_store.py"
SERVICE = ROOT / "core/library_service.py"
REQUEST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt"


class TransitionHardeningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.bridge = BRIDGE.read_text(encoding="utf-8")
        cls.store = STORE.read_text(encoding="utf-8")
        cls.service = SERVICE.read_text(encoding="utf-8")
        cls.request = REQUEST.read_text(encoding="utf-8")

    def test_next_previous_accept_one_transition_and_never_write_on_ui_path(self):
        start = self.player.index("private fun requestEpisode")
        end = self.player.index("private fun seekToSavedPosition", start)
        block = self.player[start:end]
        self.assertIn("episodeChangePending", block)
        self.assertIn("transitionGeneration", block)
        self.assertIn("playbackWorker.submit", block)
        self.assertIn("val published = NativeMailbox.write(", block)
        self.assertIn("handler.post", block)
        self.assertIn("requestEvent(\"NEXT_TRANSITION_STARTED\", \"PREVIOUS_TRANSITION_STARTED\")", block)
        self.assertNotIn("Thread.sleep(", block)
        self.assertIn("saveProgress(\"player_progress\", force = true)", block)

    def test_transition_identity_is_session_scoped_and_invalidated(self):
        for token in (
            "playerSessionId",
            "transitionGeneration",
            "transitionSourceRequestId",
            "transitionSourceCreatedAtMs",
            "transitionSourceGeneration",
            "invalidateTransition",
            "PLAYER_TRANSITION_INVALIDATED",
        ):
            self.assertIn(token, self.player)
        for token in (
            "originRequestId",
            "originCreatedAtMs",
            "originTransitionGeneration",
        ):
            self.assertIn(token, self.request)

    def test_back_stop_destroy_and_new_intent_cannot_reuse_an_old_transition(self):
        self.assertIn('invalidateTransition("finish_player")', self.player)
        self.assertIn('invalidateTransition("onStop_finishing")', self.player)
        self.assertIn('invalidateTransition("destroy")', self.player)
        reuse = self.player[
            self.player.index("override fun onNewIntent"):
            self.player.index("private fun currentEpisodeId")
        ]
        self.assertIn("transitionPendingRequestId", reuse)
        self.assertIn("incomingOriginRequestId == transitionPendingRequestId", reuse)
        self.assertIn("incomingOriginCreatedAtMs == transitionPendingCreatedAtMs", reuse)
        self.assertIn("incomingOriginTransitionGeneration == transitionPendingGeneration", reuse)

    def test_main_activity_rejects_stale_origin_after_player_exit(self):
        for token in (
            "lastPlayerExitRequestId",
            "lastPlayerExitAtMs",
            "notePlayerExit",
            "stale_after_player_exit",
            "stale_origin_session",
        ):
            self.assertIn(token, self.main_activity)
        handoff = self.main_activity[
            self.main_activity.index("private fun openPlayer"):
            self.main_activity.index("private fun clearPendingPlay")
        ]
        self.assertIn("playerRequest.originRequestId", handoff)
        self.assertIn("lastPlayerExitAtMs >= originCreatedAtMs", handoff)
        self.assertIn("return false", handoff)

    def test_python_transition_runs_outside_mailbox_loop_and_is_cancellable(self):
        for token in (
            "player_transition_task",
            "player_transition_generation",
            "asyncio.create_task(",
            "task.cancel()",
            "cancel_player_transition",
            "player_transition_is_current",
            'player_active_request_id["value"]',
        ):
            self.assertIn(token, self.main)
        block = self.main[
            self.main.index("elif event_type in {'player_next_request', 'player_previous_request'}:"):
            self.main.index("elif event_type == 'player_error':")
        ]
        self.assertIn("run_player_transition", block)
        self.assertIn("asyncio.to_thread", block)
        self.assertIn("origin_request_id=event_request_id", block)
        self.assertIn("asyncio.CancelledError", block)

    def test_exit_and_activity_result_cancel_only_the_current_player_session(self):
        exit_block = self.main[
            self.main.index("elif event_type == 'player_exited':"):
            self.main.index("elif event_type == 'google_sign_in_started':", self.main.index("elif event_type == 'player_exited':"))
        ]
        self.assertIn("PLAYER_CALLBACK_STALE", exit_block)
        self.assertIn("MAILBOX_STALE_COMMAND_DISCARDED", exit_block)
        self.assertIn("invalidate_player_session(", exit_block)
        self.assertIn('player_session_active["value"] = False', self.main)
        self.assertIn('cancel_player_transition("player_activity_result")', self.main)

    def test_mailbox_polling_has_a_bounded_low_latency_lane(self):
        self.assertIn("bridge.drain()", self.main)
        self.assertIn("bridge.requeue_event_ids(failed_event_ids)", self.main)
        self.assertIn("bridge.acknowledge()", self.main)
        self.assertIn("poll_interval = 0.08", self.main)
        self.assertIn("min(0.25", self.main)
        self.assertIn('"android.mailbox.drain"', self.main)
        self.assertIn('"android.mailbox.backlog"', self.main)

    def test_navigation_snapshot_eliminates_duplicate_transition_queries(self):
        self.assertIn("def player_navigation(self, path):", self.store)
        self.assertIn("def player_navigation(self, path): return self.store.player_navigation(path)", self.service)
        self.assertIn("await asyncio.to_thread(library.player_navigation", self.main)
        transition = self.main[
            self.main.index("elif event_type in {'player_next_request', 'player_previous_request'}:"):
            self.main.index("elif event_type == 'player_error':")
        ]
        self.assertNotIn("library.next_episode, current_path", transition)
        self.assertNotIn("library.previous_episode, current_path", transition)
        self.assertIn('navigation_snapshot.get("next")', transition)
        self.assertIn('navigation_snapshot.get("previous")', transition)
        self.assertIn('navigation_snapshot.get("next")', transition)
        self.assertIn('navigation_snapshot.get("previous")', transition)

    def test_reuse_metadata_read_is_off_ui_thread_and_generation_guarded(self):
        self.assertIn("loadLocalMetadataAsync(transitionGeneration, resolvedUri)", self.player)
        self.assertIn("loadLocalMetadataAsync(transitionGeneration, uri)", self.player)
        start = self.player.index("private fun loadLocalMetadataAsync")
        end = self.player.index("private fun updateMetadataControls", start)
        block = self.player[start:end]
        self.assertIn("playbackWorker.submit", block)
        self.assertIn("localMetadataStore.get(localUri.toString())", block)
        self.assertIn("handler.post", block)
        self.assertIn("generation != transitionGeneration", block)
        self.assertIn("uri != localUri", block)

    def test_progress_is_canonical_and_published_off_ui_thread(self):
        start = self.player.index("private fun buildProgressEvent")
        end = self.player.index("override fun onStart()", start)
        block = self.player[start:end]
        self.assertIn("episodeId", block)
        self.assertIn("mediaId", block)
        self.assertIn("animeId", block)
        self.assertIn("positionMs", block)
        self.assertIn("durationMs", block)
        self.assertIn("progressWorker.submit", block)
        self.assertIn("NativeMailbox.write(this@NativePlayerActivity, event)", block)
        self.assertIn("playerSessionId", block)

    def test_latency_instrumentation_covers_button_mailbox_handoff_and_ready(self):
        for token in (
            "buttonPressedAtMs",
            "player.mailbox.consume",
            "PLAYER_HANDOFF_DISPATCHED",
            "transition_ready",
            "PLAYER_TRANSITION_READY",
            "originCreatedAtMs",
            "handoffDispatchedAtMs",
        ):
            self.assertIn(token, self.main + self.main_activity + self.player)

    def test_required_scenarios_are_guarded(self):
        scenario_tokens = (
            "episodeChangePending",
            "canNext",
            "canPrevious",
            "player_transition_inflight",
            "player_transition_generation",
            "episodeChangeTimeout",
            "PLAYER_TRANSITION_INVALIDATED",
            "PLAYER_CALLBACK_STALE",
            "PLAYER_OPENED_IGNORED",
            "PLAYER_HANDOFF_REJECTED",
            "stale_after_player_exit",
            "stale_origin_session",
            "Player.STATE_ENDED",
            'requestEpisode("player_next_request")',
            "playbackWorker.shutdown()",
        )
        combined = self.player + self.main + self.main_activity
        for token in scenario_tokens:
            self.assertIn(token, combined)


if __name__ == "__main__":
    unittest.main()
