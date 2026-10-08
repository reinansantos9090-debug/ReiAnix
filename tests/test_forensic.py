import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt"


class ForensicTests(unittest.TestCase):
    def test_player_reuse_watchdog_binds_to_new_player_generation(self):
        source = PLAYER.read_text(encoding="utf-8")
        self.assertIn("private fun armEpisodeChangeTimeout(reason: String)", source)
        helper_start = source.index("private fun armEpisodeChangeTimeout(reason: String)")
        helper_end = source.index("private val episodeChangeTimeout", helper_start)
        helper = source[helper_start:helper_end]
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

    def test_prepare_rearms_reuse_watchdog_after_generation_increment(self):
        source = PLAYER.read_text(encoding="utf-8")
        prepare_start = source.index("private fun prepareCurrentMedia(")
        prepare = source[prepare_start:prepare_start + 1200]
        self.assertIn("beginPlayerGeneration(reason)", prepare)
        self.assertIn('if (episodeChangePending) {\n            armEpisodeChangeTimeout("prepare_" + reason)\n        }', prepare)

    def test_reuse_intent_does_not_arm_watchdog_before_new_generation_exists(self):
        source = PLAYER.read_text(encoding="utf-8")
        reuse_start = source.index("override fun onNewIntent(newIntent: Intent)")
        reuse_end = source.index("private fun currentEpisodeId", reuse_start)
        reuse = source[reuse_start:reuse_end]
        self.assertNotIn("handler.postDelayed(episodeChangeTimeout", reuse)
        self.assertIn("prepareCurrentMedia(" + '"' + "reuse" + '"' + ")", reuse)

    def test_player_destroy_cancels_lock_affordance_callback(self):
        source = PLAYER.read_text(encoding="utf-8")
        destroy_start = source.index("override fun onDestroy()")
        destroy_end = source.index("/**", destroy_start)
        destroy = source[destroy_start:destroy_end]
        self.assertIn("handler.removeCallbacks(lockAffordanceHider)", destroy)

    def test_scan_coordinator_rechecks_exclusive_state_inside_lock(self):
        source = (ROOT / "core" / "scan_coordinator.py").read_text(encoding="utf-8")
        lock_start = source.index("async with self._lock:")
        lock_end = source.index("if self._active_request is not None:", lock_start)
        locked_prefix = source[lock_start:lock_end]
        self.assertIn("if self._exclusive_reason is not None:", locked_prefix)
        self.assertIn('message=f"exclusive_operation:{self._exclusive_reason}"', locked_prefix)


if __name__ == "__main__":
    unittest.main()
