import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
GRADLE = ROOT / "android/app/build.gradle.kts"


class NativePlayerLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        cls.gradle = GRADLE.read_text(encoding="utf-8")

    def test_single_exoplayer_creation_and_release_contract(self):
        self.assertEqual(1, self.player.count("ExoPlayer.Builder(this).build()"))
        self.assertIn("playerView.player = player", self.player)
        self.assertIn("if (::playerView.isInitialized && playerView.player === player)", self.player)
        self.assertIn("playerView.player = null", self.player)
        self.assertIn("player.release()", self.player)

    def test_player_listeners_are_replaced_per_generation(self):
        self.assertIn("activePlayerListener?.let { player.removeListener(it) }", self.player)
        self.assertIn("activeAnalyticsListener?.let { player.removeAnalyticsListener(it) }", self.player)
        self.assertIn("activePlayerListener = createPlayerListener(generation)", self.player)
        self.assertIn("activeAnalyticsListener = createAnalyticsListener(generation)", self.player)
        self.assertIn("player.addListener(activePlayerListener!!)", self.player)
        self.assertIn("player.addAnalyticsListener(activeAnalyticsListener!!)", self.player)

    def test_stale_async_preparation_cannot_touch_current_player_generation(self):
        self.assertIn("pendingPreparation?.cancel(true)", self.player)
        self.assertIn("generation == playerGeneration", self.player)
        self.assertIn("sessionState == SessionState.ACTIVE", self.player)
        self.assertIn("uri == localUri", self.player)
        self.assertIn("isCurrentPreparation(generation, localUri, preparationTransitionGeneration)", self.player)

    def test_resume_position_is_preloaded_before_ready(self):
        prepare = self.player[
            self.player.index("private fun prepareCurrentMedia"):
            self.player.index("private fun createPlayerListener")
        ]
        ready = self.player[
            self.player.index("Player.STATE_READY -> {"):
            self.player.index("Player.STATE_BUFFERING -> {")
        ]
        self.assertIn("player.setMediaItem(mediaItem, initialPositionMsForGeneration)", prepare)
        self.assertIn("initialSeekApplied = true", prepare)
        self.assertNotIn("seekToSavedPosition(restoredPositionMs ?: savedPosition)", ready)
        self.assertIn("RESUME_POSITION_ALREADY_PRELOADED", ready)
    def test_media_reset_rebinds_texture_view_between_media_items(self):
        prepare = self.player[
            self.player.index("private fun prepareCurrentMedia"):
            self.player.index("private fun createPlayerListener")
        ]
        detach_index = prepare.index("detachPlayerViewForMediaReset(reason)")
        set_media_index = prepare.index("player.setMediaItem(mediaItem, initialPositionMsForGeneration)")
        reattach_index = prepare.index("reattachPlayerViewAfterMediaReset(reason)")
        self.assertLess(detach_index, set_media_index)
        self.assertLess(set_media_index, reattach_index)
        initial_guard = prepare.index('if (reason != "initial")')
        self.assertLess(initial_guard, detach_index)
        self.assertLess(initial_guard, set_media_index)
        self.assertLess(initial_guard, reattach_index)
        self.assertIn("private fun detachPlayerViewForMediaReset(reason: String)", self.player)
        self.assertIn("private fun reattachPlayerViewAfterMediaReset(reason: String)", self.player)
        self.assertIn("playerView.player = null", self.player)
        self.assertIn("playerView.player = player", self.player)

    def test_initial_prepare_keeps_fresh_player_view_attached(self):
        prepare = self.player[
            self.player.index("private fun prepareCurrentMedia"):
            self.player.index("private fun createPlayerListener")
        ]
        guards = []
        cursor = 0
        while True:
            found = prepare.find('if (reason != "initial")', cursor)
            if found < 0:
                break
            guards.append(found)
            cursor = found + 1
        set_media = prepare.index("player.setMediaItem(mediaItem, initialPositionMsForGeneration)")
        detach = prepare.index("detachPlayerViewForMediaReset(reason)")
        reattach = prepare.index("reattachPlayerViewAfterMediaReset(reason)")
        self.assertEqual(2, len(guards))
        self.assertLess(guards[0], detach)
        self.assertLess(detach, set_media)
        self.assertLess(set_media, guards[1])
        self.assertLess(guards[1], reattach)

    def test_new_intent_cannot_inherit_stale_foreground_resume_state(self):
        reuse = self.player[
            self.player.index("override fun onNewIntent"):
            self.player.index("private fun currentEpisodeId")
        ]
        self.assertIn("playbackWasRequestedBeforeStop = false", reuse)

    def test_background_lifecycle_pauses_without_affecting_pip(self):
        stop = self.player[
            self.player.index("override fun onStop()"):
            self.player.index("override fun onWindowFocusChanged")
        ]
        resume = self.player[
            self.player.index("override fun onResume()"):
            self.player.index("override fun onPause()")
        ]
        self.assertIn("!inPictureInPicture", stop)
        self.assertIn("playbackWasRequestedBeforeStop", stop)
        self.assertIn("player.pause()", stop)
        self.assertIn("PLAYER_BACKGROUND_PAUSE", stop)
        self.assertIn("!inPictureInPicture", resume)
        self.assertIn("playbackWasRequestedBeforeStop", resume)
        self.assertIn("player.playWhenReady = true", resume)
        self.assertIn("PLAYER_FOREGROUND_RESUME", resume)

    def test_completion_does_not_create_a_second_player_or_playlist(self):
        playback = self.player[
            self.player.index("override fun onPlaybackStateChanged"):
            self.player.index("override fun onPlaybackParametersChanged")
        ]
        self.assertIn("Player.STATE_ENDED -> {", playback)
        self.assertIn('saveProgress("player_completed", force = true)', playback)
        self.assertIn('requestEpisode("player_next_request")', playback)
        self.assertNotIn("ExoPlayer.Builder(this).build()", playback)
        self.assertNotIn("player.setMediaItems(", self.player)
        self.assertNotIn("player.addMediaItem(", self.player)

    def test_media3_commands_are_dispatched_from_ui_handler_after_io_preflight(self):
        prepare = self.player[
            self.player.index("private fun prepareCurrentMedia"):
            self.player.index("private fun createPlayerListener")
        ]
        self.assertIn("playbackWorker.submit", prepare)
        self.assertIn("handler.post {", prepare)
        self.assertIn("player.setMediaItem(mediaItem, initialPositionMsForGeneration)", prepare)
        self.assertIn("player.prepare()", prepare)

    def test_android_host_compilation_contracts_used_by_player_diagnostics(self):
        duplicate = self.main_activity[
            self.main_activity.index('if (requestId.isNotBlank() && seenPlayerRequestIds.contains(requestId))'):
            self.main_activity.index('activePlayerRequestId = requestId.takeIf', self.main_activity.index('if (requestId.isNotBlank() && seenPlayerRequestIds.contains(requestId))'))
        ]
        self.assertIn('PLAYER_HANDOFF_DUPLICATE', duplicate)
        self.assertIn('result = "ignored_same_request"', duplicate)
        self.assertIn("return true", duplicate)

        self.assertIn("buildFeatures", self.gradle)
        self.assertIn("buildConfig = true", self.gradle)

        template_script = (ROOT / "scripts/prepare_flet_template.py").read_text(encoding="utf-8")
        self.assertIn("ReiAnix BuildConfig generation contract", template_script)
        self.assertIn("buildConfig = true", template_script)
        self.assertIn("gradle.write_text(existing, encoding=\"utf-8\")", template_script)

    def test_first_frame_is_generation_correlated(self):
        self.assertIn("generation == playerGeneration && sessionState == SessionState.ACTIVE", self.player)
        self.assertIn("events.contains(Player.EVENT_RENDERED_FIRST_FRAME)", self.player)
        self.assertIn("armFirstFrameDiagnostics(generation)", self.player)
        self.assertIn('cancelFirstFrameDiagnostics("first_frame")', self.player)


if __name__ == "__main__":
    unittest.main()
