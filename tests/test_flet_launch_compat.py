import asyncio
import inspect
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit

import flet as ft

from core.android_bridge import AndroidBridge


class FletLaunchCompatibilityTests(unittest.IsolatedAsyncioTestCase):
    def test_real_flet_page_launch_url_has_no_unsupported_mode_parameter(self):
        self.assertEqual("0.86.5", getattr(ft, "__version__", None))
        launch_url = inspect.signature(ft.Page.launch_url)
        self.assertNotIn("mode", launch_url.parameters)

    async def test_android_bridge_uses_flet_0865_non_browser_url_launcher_and_confirms_receipt(self):
        class StrictLauncher:
            def __init__(self, page):
                self.page = page
                self.calls = []

            async def launch_url(self, value, *, mode):
                self.calls.append((value, mode))
                params = parse_qs(urlsplit(value).query)
                request_id = params["request_id"][0]
                self.page.bridge.observe_native_event(
                    {
                        "type": "diagnostic",
                        "requestId": request_id,
                        "payload": {
                            "event": "COMMAND_RECEIVED",
                            "requestId": request_id,
                            "action": params["action"][0],
                            "timestamp": 123456789,
                        },
                    }
                )

        class StrictPage:
            platform = "android"

            def __init__(self):
                self.launcher = StrictLauncher(self)
                self.bridge = None

            @property
            def url_launcher(self):
                return self.launcher

            async def launch_url(self, value):
                raise AssertionError("AndroidBridge must not use Page.launch_url for native commands")

        with tempfile.TemporaryDirectory() as data_dir:
            page = StrictPage()
            bridge = AndroidBridge(data_dir, page)
            page.bridge = bridge
            request_id = await bridge.select_tree()

        self.assertEqual(1, len(page.launcher.calls))
        value, mode = page.launcher.calls[0]
        self.assertEqual(ft.LaunchMode.EXTERNAL_NON_BROWSER_APPLICATION, mode)
        self.assertIn("reiflix://native?action=select_tree&request_id=", value)
        self.assertIn("protocol_version=2", value)
        self.assertIn("created_at=", value)
        self.assertEqual(request_id, parse_qs(urlsplit(value).query)["request_id"][0])

    async def test_android_bridge_play_waits_for_player_handoff_confirmation(self):
        class Page:
            platform = "android"
            url_launcher = None

        class TestBridge(AndroidBridge):
            def _write_internal_command(self, *, request_id, action, created_at, url):
                super()._write_internal_command(
                    request_id=request_id,
                    action=action,
                    created_at=created_at,
                    url=url,
                )
                self.observe_native_event({
                    "type": "diagnostic",
                    "requestId": request_id,
                    "payload": {
                        "event": "COMMAND_RECEIVED",
                        "requestId": request_id,
                        "action": action,
                        "timestamp": created_at,
                    },
                })

        with tempfile.TemporaryDirectory() as data_dir:
            bridge = TestBridge(data_dir, Page())
            bridge._command_delivery_timeout_s = 0.01
            with self.assertRaisesRegex(RuntimeError, "não foi confirmado"):
                await bridge.play(
                    "content://media/external/video/1",
                    "Episódio",
                    episode_id=42,
                )

    async def test_android_bridge_play_confirms_only_after_player_handoff(self):
        class Page:
            platform = "android"
            url_launcher = None

        class TestBridge(AndroidBridge):
            def _write_internal_command(self, *, request_id, action, created_at, url):
                super()._write_internal_command(
                    request_id=request_id,
                    action=action,
                    created_at=created_at,
                    url=url,
                )
                self.observe_native_event({
                    "type": "diagnostic",
                    "requestId": request_id,
                    "payload": {
                        "event": "COMMAND_RECEIVED",
                        "requestId": request_id,
                        "action": action,
                        "timestamp": created_at,
                    },
                })
                self.observe_native_event({
                    "type": "diagnostic",
                    "requestId": request_id,
                    "payload": {
                        "event": "PLAYER_HANDOFF_DISPATCHED",
                        "requestId": request_id,
                        "action": action,
                        "timestamp": created_at + 1,
                        "result": "direct_native_player",
                    },
                })

        with tempfile.TemporaryDirectory() as data_dir:
            bridge = TestBridge(data_dir, Page())
            request_id = await bridge.play(
                "content://media/external/video/1",
                "Episódio",
                episode_id=42,
            )

        self.assertTrue(request_id)
        self.assertEqual({}, bridge._command_delivery_waiters)
        self.assertEqual({}, bridge._command_delivery_expected_events)

    async def test_android_bridge_rejects_play_without_episode_id(self):
        class Page:
            platform = "android"
            url_launcher = None

        with tempfile.TemporaryDirectory() as data_dir:
            bridge = AndroidBridge(data_dir, Page())
            with self.assertRaisesRegex(ValueError, "episode_id válido"):
                await bridge.play("content://media/external/video/1", "Episódio")

    async def test_android_bridge_does_not_treat_launcher_return_as_delivery(self):
        class SilentLauncher:
            async def launch_url(self, value, *, mode):
                return None

        class SilentPage:
            platform = "android"
            url_launcher = SilentLauncher()

        with tempfile.TemporaryDirectory() as data_dir:
            bridge = AndroidBridge(data_dir, SilentPage())
            bridge._command_delivery_timeout_s = 0.01
            with self.assertRaisesRegex(RuntimeError, "não chegou à MainActivity"):
                await bridge.select_tree()
            self.assertEqual({}, bridge._command_delivery_waiters)

    async def test_android_bridge_caller_cancellation_cleans_delivery_waiter(self):
        class SilentLauncher:
            async def launch_url(self, value, *, mode):
                return None

        class SilentPage:
            platform = "android"
            url_launcher = SilentLauncher()

        with tempfile.TemporaryDirectory() as data_dir:
            bridge = AndroidBridge(data_dir, SilentPage())
            bridge._command_delivery_timeout_s = 30.0
            task = asyncio.create_task(bridge.select_tree())
            await asyncio.sleep(0)
            self.assertEqual(1, len(bridge._command_delivery_waiters))
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertEqual({}, bridge._command_delivery_waiters)

    async def test_android_bridge_generates_distinct_request_ids_for_retries(self):
        class StrictLauncher:
            def __init__(self, page):
                self.page = page
                self.calls = []

            async def launch_url(self, value, *, mode):
                self.calls.append((value, mode))
                params = parse_qs(urlsplit(value).query)
                request_id = params["request_id"][0]
                self.page.bridge.observe_native_event(
                    {
                        "type": "diagnostic",
                        "requestId": request_id,
                        "payload": {
                            "event": "COMMAND_RECEIVED",
                            "requestId": request_id,
                            "action": params["action"][0],
                            "timestamp": 123456789,
                        },
                    }
                )

        class StrictPage:
            platform = "android"

            def __init__(self):
                self.bridge = None
                self.launcher = StrictLauncher(self)

            @property
            def url_launcher(self):
                return self.launcher

        with tempfile.TemporaryDirectory() as data_dir:
            page = StrictPage()
            bridge = AndroidBridge(data_dir, page)
            page.bridge = bridge
            await bridge.select_tree()
            await bridge.select_tree()

        self.assertEqual(2, len(page.launcher.calls))
        first = parse_qs(urlsplit(page.launcher.calls[0][0]).query)["request_id"][0]
        second = parse_qs(urlsplit(page.launcher.calls[1][0]).query)["request_id"][0]
        self.assertTrue(first)
        self.assertTrue(second)
        self.assertNotEqual(first, second)
        self.assertEqual({}, bridge._command_delivery_waiters)

    def test_android_bridge_normalizes_legacy_events_with_stable_ids(self):
        event = AndroidBridge._normalize_event(
            {"type": "native_error", "message": "failed"},
            "event-abc.consumed",
            0,
        )
        self.assertIsNotNone(event)
        self.assertTrue(event["eventId"].startswith("legacy:event-abc.consumed:0:"))
        self.assertEqual(24, len(event["eventId"].rsplit(":", 1)[-1]))
        self.assertIn("createdAt", event)


if __name__ == "__main__":
    unittest.main()
