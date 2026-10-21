import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
BRIDGE = ROOT / "core/android_bridge.py"
STORE = ROOT / "core/library_store.py"
SERVICE = ROOT / "core/library_service.py"
REQUEST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt"


class TransitionHardeningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
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


    def test_native_transition_runs_off_ui_thread_and_is_cancellable(self):
        transition_start = self.player.index("private fun dispatchNativeEpisodeTransition(")
        transition_end = self.player.index("private fun seekToSavedPosition", transition_start)
        transition = self.player[transition_start:transition_end]
        for token in ("transitionGeneration", "isCurrentTransition(", "NEXT_TRANSITION", "PREVIOUS_TRANSITION", "PLAYER_NEXT_STALE_REJECTED", "PLAYER_PREVIOUS_STALE_REJECTED"):
            self.assertIn(token, transition + self.player)
        self.assertIn("dependencies.sqliteLibraryRepository.playerNavigation(episodeId)", transition)
        self.assertIn("playbackWorker.submit", self.player)
        self.assertIn("NativePlayerActivity", self.main_activity)

    def test_exit_and_activity_result_invalidate_only_the_current_player_session(self):
        for token in ("activityInstanceId", "playerSessionId", "stale_activity_instance", "notePlayerActivityDestroyed", "PLAYER_EXIT_CLASSIFICATION"):
            self.assertIn(token, self.player + self.main_activity)
        self.assertIn("activePlayerActivityInstanceId", self.main_activity)
        self.assertIn("isCurrentPlayerHandoff", self.main_activity)
        self.assertIn("isFinishing", self.player)
        self.assertIn("isChangingConfigurations", self.player)

    def test_native_mailbox_has_bounded_background_telemetry_and_durable_control_events(self):
        mailbox = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt").read_text(encoding="utf-8")
        for token in ("BEST_EFFORT_QUEUE_CAPACITY = 128", "ArrayBlockingQueue(BEST_EFFORT_QUEUE_CAPACITY)", "ThreadPoolExecutor.AbortPolicy()", "StandardCopyOption.ATOMIC_MOVE", "fun writeBestEffort(", "fun writeOrThrow("):
            self.assertIn(token, mailbox)
        self.assertIn("NativeMailbox.writeBestEffort(this@NativePlayerActivity, event)", self.player)
        self.assertIn("NativeMailbox.write(this@NativePlayerActivity, event)", self.player)
        self.assertNotIn("@Synchronized\n    fun write(", mailbox)

    def test_navigation_snapshot_eliminates_duplicate_transition_queries(self):
        transition_start = self.player.index("private fun dispatchNativeEpisodeTransition(")
        transition_end = self.player.index("private fun seekToSavedPosition", transition_start)
        transition = self.player[transition_start:transition_end]
        self.assertEqual(1, transition.count("playerNavigation(episodeId)"))
        self.assertIn("navigationSnapshot", transition)
        self.assertIn("NEXT_TRANSITION", transition)
        self.assertIn("PREVIOUS_TRANSITION", transition)
        self.assertNotIn("nextEpisode(", transition)
        self.assertNotIn("previousEpisode(", transition)
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


    def test_latency_instrumentation_covers_native_handoff_and_first_frame(self):
        performance = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/PerformanceDiagnostics.kt").read_text(encoding="utf-8")
        for token in ("commandCreatedAtMs", "activityCreatedAtMs", "prepareAtMs", "firstFrameAtMs", "first_frame_ms", "tapToFirstFrameMs"):
            self.assertIn(token, performance + self.player)
        self.assertIn("PLAYER_HANDOFF_DISPATCHED", self.main_activity)
        self.assertIn("FIRST_FRAME_RENDERED", self.player)
        self.assertIn("PLAYER_PERFORMANCE_MILESTONE", performance)

    def test_required_native_player_scenarios_are_guarded(self):
        combined = self.player + self.main_activity
        scenario_tokens = (
            "episodeChangePending", "canNext", "canPrevious", "transitionGeneration",
            "episodeChangeTimeout", "PLAYER_TRANSITION_INVALIDATED", "PLAYER_CALLBACK_STALE",
            "PLAYER_OPENED_IGNORED", "PLAYER_HANDOFF_REJECTED", "stale_after_player_exit",
            "stale_origin_session", "Player.STATE_ENDED", 'requestEpisode("player_next_request")',
            "playbackWorker.shutdown()",
        )
        for token in scenario_tokens:
            self.assertIn(token, combined)

if __name__ == "__main__":
    unittest.main()
