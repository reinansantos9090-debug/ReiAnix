import asyncio
import json
import tempfile
import unittest
from pathlib import Path

from core.android_bridge import AndroidBridge


ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "core/android_bridge.py"
DISPATCHER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeCommandDispatcher.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"


class PlayerRoutingTests(unittest.TestCase):
    def test_internal_player_actions_use_private_command_channel(self):
        source = BRIDGE.read_text(encoding="utf-8")
        dispatcher = DISPATCHER.read_text(encoding="utf-8")
        action_set_start = source.index("        self._internal_command_actions = {")
        action_set_end = source.index("        self._recover_unacknowledged_batches()", action_set_start)
        action_set = source[action_set_start:action_set_end]
        launch_start = source.index("            if action in self._internal_command_actions and self.available:")
        direct_end = source.index("            else:", launch_start)
        direct_block = source[launch_start:direct_end]
        for action in ("play", "extract_thumbnail", "cancel_player_transition"):
            self.assertIn(f'"{action}"', action_set)
            self.assertIn("self._write_internal_command(", direct_block)
        self.assertIn('reiflix-native-commands', source)
        self.assertIn('FileObserver', dispatcher)
        self.assertIn('FILE_PREFIX = "command-"', dispatcher)
        self.assertIn('Intent.FLAG_ACTIVITY_REORDER_TO_FRONT', dispatcher)
        self.assertIn('Intent.FLAG_ACTIVITY_SINGLE_TOP', dispatcher)
        self.assertNotIn("url_launcher.launch_url(url", direct_block)

    def test_main_activity_only_starts_dispatcher_and_keeps_host_route_intact(self):
        main = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("NativeCommandDispatcher.start(this)", main)
        self.assertIn('private fun handleNativeIntent', main)
        self.assertIn('android.intent.action.VIEW', (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"))

    def test_internal_command_file_is_atomic_and_self_contained(self):
        bridge = object.__new__(AndroidBridge)
        with tempfile.TemporaryDirectory() as temp_dir:
            bridge.data_dir = Path(temp_dir)
            request_id = "fixture_3-atomic"
            bridge._write_internal_command(
                request_id=request_id,
                action="play",
                created_at=123456789,
                url="reiflix://native?action=play&request_id=fixture_3-atomic",
            )
            queue = Path(temp_dir) / "reiflix-native-commands"
            target = queue / f"command-{request_id}.json"
            temp = queue / f".command-{request_id}.tmp"
            self.assertTrue(target.is_file())
            self.assertFalse(temp.exists())
            payload = json.loads(target.read_text(encoding="utf-8"))
            self.assertEqual(1, payload["version"])
            self.assertEqual(request_id, payload["requestId"])
            self.assertEqual("play", payload["action"])
            self.assertEqual(123456789, payload["createdAt"])
            self.assertTrue(payload["url"].startswith("reiflix://native?"))

    def test_internal_command_writer_has_no_retry_or_sleep_logic(self):
        source = BRIDGE.read_text(encoding="utf-8")
        helper = source[source.index("    def _write_internal_command"):source.index("    async def select_tree")]
        self.assertNotIn("sleep(", helper)
        self.assertNotIn("retry", helper.lower())
        self.assertNotIn("while ", helper)

    def test_bridge_public_play_still_requires_canonical_local_episode(self):
        source = BRIDGE.read_text(encoding="utf-8")
        play = source[source.index("    async def play("):source.index("    @staticmethod", source.index("    async def play("))]
        self.assertIn("normalize_local_media_reference", play)
        self.assertIn("episode_id válido", play)

    def test_internal_channel_is_awaitable_at_same_delivery_boundary(self):
        source = BRIDGE.read_text(encoding="utf-8")
        launch = source[source.index("    async def _launch"):source.index("    def _write_internal_command")]
        self.assertIn("delivery_waiter", launch)
        self.assertIn("PLAYER_HANDOFF_DISPATCHED", launch)
        self.assertIn("wait_for", launch)


if __name__ == "__main__":
    unittest.main()
