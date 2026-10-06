import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MAIN = ROOT / "main.py"
STORE = ROOT / "core/library_store.py"


class PlayerTransitionContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.store = STORE.read_text(encoding="utf-8")

    def test_transition_gate_blocks_second_request_until_media3_ready(self):
        request = self.player[self.player.index("private fun requestEpisode"):self.player.index("private fun seekToSavedPosition")]
        self.assertIn("if (episodeChangePending)", request)
        self.assertIn("if (errorVisible)", request)
        self.assertIn("episodeChangePending = true", request)
        self.assertIn("updateEpisodeNavigationButtons()", request)

        reuse = self.player[self.player.index("override fun onNewIntent"):self.player.index("private fun currentEpisodeId")]
        self.assertIn("val transitionPending = episodeChangePending", reuse)
        self.assertIn("val transitionPendingRequestId = transitionSourceRequestId", reuse)
        self.assertIn("incomingOriginRequestId == transitionPendingRequestId", reuse)
        self.assertIn("incomingOriginTransitionGeneration == transitionPendingGeneration", reuse)
        self.assertIn("episodeChangeTimeoutRequestId = requestId", reuse)
        self.assertIn("episodeChangeTimeoutUri = uri.toString()", reuse)
        self.assertIn("sessionState = SessionState.ACTIVE", reuse)
        self.assertIn("episodeChangePending = false", reuse)

        ready = self.player[
            self.player.index("Player.STATE_READY -> {"):
            self.player.index("Player.STATE_BUFFERING -> {")
        ]
        self.assertIn("nextTransitionActive &&", ready)
        self.assertIn("previousTransitionActive &&", ready)
        self.assertIn("episodeChangePending", ready)
        self.assertIn("transitionReadyGeneration", ready)
        self.assertIn("NEXT_TRANSITION_READY", ready)
        self.assertIn("PREVIOUS_TRANSITION_READY", ready)
        self.assertNotIn("episodeChangePending = false", ready)
        self.assertNotIn("EPISODE_CHANGE_COMMITTED", ready)
        self.assertNotIn("NEXT_TRANSITION_COMMITTED", ready)
        self.assertNotIn("PREVIOUS_TRANSITION_COMMITTED", ready)

        first_frame = self.player[
            self.player.index('if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME))'):
            self.player.index("override fun onMediaItemTransition", self.player.index('if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME))'))
        ]
        self.assertIn("NEXT_TRANSITION_FIRST_FRAME", first_frame)
        self.assertIn("NEXT_TRANSITION_COMMITTED", first_frame)
        self.assertIn("EPISODE_CHANGE_COMMITTED", first_frame)
        self.assertIn("episodeChangePending = false", first_frame)

    def test_transition_watchdog_and_error_paths_release_gate(self):
        self.assertIn("timeoutContextValid", self.player)
        self.assertIn("episodeChangeTimeoutSessionId == playerSessionId", self.player)
        self.assertIn("episodeChangeTimeoutPlayerGeneration == playerGeneration", self.player)
        self.assertIn("episodeChangeTimeoutSessionId == playerSessionId", self.player)
        self.assertIn("episodeChangeTimeoutPlayerGeneration == playerGeneration", self.player)
        self.assertIn("episodeChangePending = false", self.player)
        self.assertIn("updateEpisodeNavigationButtons()", self.player)

        error = self.player[
            self.player.index("private fun showPlayerError("):
            self.player.index("private fun publishPlayerError")
        ]
        self.assertIn('invalidateTransition("player_error")', error)
        self.assertNotIn("handler.removeCallbacks(episodeChangeTimeout)", error)

        finish = self.player[
            self.player.index("private fun finishPlayer("):
            self.player.index("private fun updateEpisodeNavigationButtons")
        ]
        self.assertIn('invalidateTransition("finish_player")', finish)
        self.assertNotIn("handler.removeCallbacks(episodeChangeTimeout)", finish)

    def test_python_transition_uses_canonical_library_direction_and_identity(self):
        block = self.main[
            self.main.index("elif event_type in {'player_next_request', 'player_previous_request'}:"):
            self.main.index("elif event_type == 'player_error':")
        ]
        self.assertIn('direction = 1 if event_type == "player_next_request" else -1', block)
        self.assertIn("await asyncio.to_thread(", block)
        self.assertIn("library.player_navigation", block)
        self.assertIn('player_transition_task["task"]', block)
        self.assertIn("asyncio.create_task(", block)
        self.assertIn("episode_id=target.get(\"id\")", block)
        self.assertIn("anime_id=target.get(\"anime_id\")", block)
        self.assertIn('target.get("progress")', block)
        self.assertIn("player_transition_inflight[\"value\"] = True", block)
        self.assertIn("player_transition_generation[\"value\"] += 1", block)
        self.assertIn("player_transition_inflight[\"value\"] = False", block)
        self.assertIn('origin_request_id=event_request_id', block)

    def test_autoplay_completion_is_single_transition_source(self):
        playback = self.player[
            self.player.index("override fun onPlaybackStateChanged"):
            self.player.index("override fun onPlaybackParametersChanged")
        ]
        self.assertIn("Player.STATE_ENDED -> {", playback)
        self.assertIn('saveProgress("player_completed", force = true)', playback)
        self.assertIn("if (autoplayNext && intent.getBooleanExtra(\"canNext\", false))", playback)
        self.assertIn('requestEpisode("player_next_request")', playback)

    def test_media3_transition_replaces_one_current_item_without_playlist_dual_source(self):
        prepare = self.player[
            self.player.index("private fun prepareCurrentMedia"):
            self.player.index("private fun createPlayerListener")
        ]
        self.assertIn(player.setMediaItem(mediaItem, initialPositionMsForGeneration), prepare)
        self.assertIn("player.prepare()", prepare)
        self.assertNotIn("player.setMediaItems(", prepare)
        self.assertNotIn("player.addMediaItem(", prepare)

    def test_navigation_order_is_canonical_and_skips_unavailable_specials(self):
        self.assertIn("current_key = LibraryStore._episode_order_key(current)", self.store)
        self.assertIn("ordered = sorted(available, key=LibraryStore._episode_order_key)", self.store)
        self.assertIn("e.missing=0", self.store)
        self.assertIn(
            "e.episode_type NOT IN ('movie','special','ova','oad','ona','extra')",
            self.store,
        )
        self.assertIn('WHERE e.anime_id=?', self.store)
        self.assertIn("def next_episode(self, path)", self.store)
        self.assertIn("def previous_episode(self, path)", self.store)


if __name__ == "__main__":
    unittest.main()
