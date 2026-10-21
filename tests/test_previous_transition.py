import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
REQUEST = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8")
ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
MAILBOX = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt").read_text(encoding="utf-8")
NAVIGATION = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixPlayerEpisodeNavigation.kt").read_text(encoding="utf-8")


class PreviousTransitionTests(unittest.TestCase):
    def test_required_previous_events_exist(self):
        for token in (
            "PREVIOUS_REQUEST_ACCEPTED", "PREVIOUS_REQUEST_REJECTED",
            "PREVIOUS_REQUEST_STALE", "PREVIOUS_REQUEST_DUPLICATE",
            "PREVIOUS_REQUEST_CANCELLED", "PREVIOUS_TRANSITION_STARTED",
            "PREVIOUS_TRANSITION_INVALIDATED", "PREVIOUS_TRANSITION_READY",
            "PREVIOUS_TRANSITION_FIRST_FRAME", "PREVIOUS_TRANSITION_COMMITTED",
            "PREVIOUS_TRANSITION_FAILED",
        ):
            self.assertIn(token, PLAYER + ACTIVITY)

    def test_previous_single_flight_and_generation(self):
        for token in ("episodeChangePending", "previousTransitionActive", "transitionGeneration", "playerGeneration"):
            self.assertIn(token, PLAYER)
        self.assertIn("playbackWorker.submit", PLAYER)
        self.assertIn("isCurrentTransition(", PLAYER)

    def test_previous_session_and_stale_fencing(self):
        self.assertIn("originPlayerSessionId", PLAYER + ACTIVITY)
        self.assertIn("activePlayerSessionId", ACTIVITY)
        self.assertIn("isCurrentPlayerHandoff(", ACTIVITY)
        self.assertIn("PLAYER_PREVIOUS_STALE_REJECTED", ACTIVITY + PLAYER)

    def test_previous_cross_direction_identity(self):
        self.assertIn('transitionDirection', PLAYER)
        self.assertIn("transitionSourceDirection", PLAYER)
        self.assertIn("originMonotonicNs", REQUEST + ACTIVITY + PLAYER)
        self.assertIn("transition_direction", BRIDGE)

    def test_previous_uses_canonical_navigation(self):
        repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
        self.assertIn("playerNavigation(", repository)
        self.assertIn("ReiAnixPlayerEpisodeNavigator.resolve(", repository)
        self.assertIn("ReiAnixPlayerEpisodeNavigationSnapshot", NAVIGATION)
        self.assertNotIn("episode.number - 1", PLAYER + ACTIVITY)
        self.assertNotIn("episode_number - 1", PLAYER + ACTIVITY)

    def test_previous_native_navigation_uses_sqlite_repository(self):
        repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
        self.assertIn("playerNavigation(", repository)
        self.assertIn("ReiAnixPlayerEpisodeNavigator.resolve(current, availableEpisodes)", repository)
        self.assertIn("ReiAnixPlayerEpisodeNavigationSnapshot", NAVIGATION)
        self.assertIn("episodeId: Long", repository)

    def test_previous_no_target_is_cancelled(self):
        self.assertIn("PREVIOUS_TRANSITION_FAILED", PLAYER)
        self.assertIn("PREVIOUS_REQUEST_REJECTED", PLAYER)
        self.assertIn("invalidateTransition(", PLAYER)

    def test_previous_ready_waits_for_first_frame(self):
        self.assertIn("PREVIOUS_TRANSITION_READY", PLAYER)
        self.assertIn("PREVIOUS_TRANSITION_FIRST_FRAME", PLAYER)
        self.assertIn("PREVIOUS_TRANSITION_COMMITTED", PLAYER)
        self.assertIn("FIRST_FRAME_RENDERED", PLAYER)

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
        self.assertIn("episodeChangePending = false", block)

    def test_previous_exit_rejects_old_request(self):
        self.assertIn("reportPlayerExit(", PLAYER)
        self.assertIn("PLAYER_SESSION_INVALIDATED", PLAYER)
        self.assertIn("PLAYER_PREVIOUS_STALE_REJECTED", ACTIVITY + PLAYER)
        self.assertIn("isCurrentPlayerHandoff(", ACTIVITY)

    def test_previous_reused_activity_requires_origin_fencing(self):
        for token in ("incomingOriginTransitionGeneration", "incomingOriginPlayerSessionId", "incomingOriginMonotonicNs", "incomingOriginTransitionDirection"):
            self.assertIn(token, PLAYER)

    def test_previous_new_activity_rejects_old_origin(self):
        self.assertIn("origin_on_new_activity", PLAYER)
        self.assertIn("PREVIOUS_REQUEST_STALE", PLAYER)
        self.assertIn("PLAYER_PREVIOUS_STALE_REJECTED", PLAYER)

    def test_previous_progress_save_before_navigation(self):
        request = PLAYER[PLAYER.index("private fun requestEpisode"):PLAYER.index("private fun seekToSavedPosition")]
        self.assertIn('saveProgress("player_progress", force = true)', request)
        self.assertIn("currentEpisodeId()", request)
        self.assertLess(request.index('saveProgress("player_progress", force = true)'), request.index("NativeMailbox.write("))

    def test_previous_prepare_callbacks_are_generation_bound(self):
        self.assertIn("expectedTransitionGeneration", PLAYER)
        self.assertIn("transitionGeneration == expectedTransitionGeneration", PLAYER)

    def test_previous_does_not_use_time_only_staleness(self):
        self.assertNotIn("mailbox_latency_ms > 5000", PLAYER + ACTIVITY)
        self.assertNotIn("mailbox_latency_ms >= 5000", PLAYER + ACTIVITY)

    def test_previous_tests_do_not_sleep(self):
        source = Path(__file__).read_text(encoding="utf-8")
        self.assertNotIn("time" + ".sleep(", source)
        self.assertNotIn("asyncio" + ".sleep(", source)

    def test_previous_and_next_are_direction_fenced(self):
        for token in ("NEXT_REQUEST_STALE", "PREVIOUS_REQUEST_STALE", "NEXT_REQUEST_DUPLICATE", "PREVIOUS_REQUEST_DUPLICATE", "NEXT_TRANSITION_STARTED", "PREVIOUS_TRANSITION_STARTED"):
            self.assertIn(token, PLAYER)

    def test_previous_bridge_identity_survives_handoff(self):
        for token in ("origin_monotonic_ns", "transition_direction"):
            self.assertIn(token, BRIDGE)
        for token in ("originMonotonicNs", "transitionDirection"):
            self.assertIn(token, REQUEST)
