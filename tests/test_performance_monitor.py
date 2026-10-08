import asyncio
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

from core.android_bridge import AndroidBridge
from core.library_store import LibraryStore
from core.performance import PerformanceMonitor


class PerformanceMonitorTests(unittest.TestCase):
    def test_monitor_can_be_disabled(self):
        monitor = PerformanceMonitor(enabled=False)
        monitor.counter("example")
        snapshot = monitor.snapshot()
        self.assertFalse(snapshot["enabled"])
        self.assertEqual({}, snapshot["counters"])

    def test_interaction_span_and_task_tracking_are_aggregated(self):
        monitor = PerformanceMonitor(enabled=True)
        with monitor.interaction("settings_open", source="home", target="settings"):
            with monitor.span("settings.build", screen="settings"):
                monitor.counter("settings_focus_events", 2)

        async def run():
            async with monitor.task_scope("settings.restore_scroll", screen="settings"):
                await asyncio.sleep(0)

        asyncio.run(run())
        snapshot = monitor.snapshot()
        self.assertEqual(2, snapshot["counters"]["settings_focus_events"])
        self.assertEqual(1, snapshot["counters"]["tasks.created"])
        self.assertEqual(1, snapshot["counters"]["tasks.completed"])
        names = {event["name"] for event in snapshot["events"]}
        self.assertIn("interaction.settings_open.start", names)
        self.assertIn("interaction.settings_open.end", names)
        self.assertIn("task.created", names)
        self.assertIn("task.finished", names)

    def test_production_integration_contract_is_present(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        settings = (ROOT / "views/settings_view.py").read_text(encoding="utf-8")
        store = (ROOT / "core/library_store.py").read_text(encoding="utf-8")
        bridge = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        player = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
        host = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        for source, tokens in (
            (main, ("get_performance_monitor", "ui.render_current", "interaction.back", "performance_metrics")),
            (settings, ("settings_focus_events", "settings_scroll_requests", "settings.build_sections", "task_scope")),
            (store, ("record_sqlite", "library_summary", "save_progress", "catalog_page")),
            (bridge, ("android.launch_url", "android.command_delivery", "COMMAND_RECEIVED")),
            (player, ("PerformanceDiagnostics.markPlayer", "first_frame", "playing")),
            (host, ("PerformanceDiagnostics.attach", "PLAY_HANDOFF_DISPATCHED")),
        ):
            for token in tokens:
                self.assertIn(token, source)
        self.assertIn("PerformanceDiagnostics.kt", str(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/PerformanceDiagnostics.kt"))

    def test_two_percent_round_trip_keeps_same_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("stage2", {"title": "earlier validation stage 2", "genres": "[]"})
            path = "content://media/fixture_2-e01"
            store.upsert_episode(anime, path, "earlier validation stage 2 E01", 1, 1,
                                  media_identity="media:stage2:e01")
            self.assertTrue(store.save_progress(path, 2, 100, event_created_at=1000))
            target = store.playback_target(anime)
            self.assertEqual(path, target["path"])
            self.assertEqual("media:stage2:e01", target["media_identity"])
            store.upsert_episode(anime, path, "earlier validation stage 2 E01", 1, 1,
                                  media_identity="media:stage2:e01")
            target_after_rescan = store.playback_target(anime)
            self.assertEqual(path, target_after_rescan["path"])
            self.assertEqual(2, store.physical_row(path)["progress"])
            self.assertEqual(AndroidBridge.normalize_local_media_reference(path),
                             AndroidBridge.normalize_local_media_reference(path))

    def test_next_previous_contract_remains_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("nav", {"title": "Nav", "genres": "[]"})
            e1 = "content://media/nav-01"
            e2 = "content://media/nav-02"
            store.upsert_episode(anime, e1, "Nav 01", 1, 1)
            store.upsert_episode(anime, e2, "Nav 02", 1, 2)
            self.assertEqual(e2, store.next_episode(e1)["path"])
            self.assertEqual(e1, store.previous_episode(e2)["path"])


if __name__ == "__main__":
    unittest.main()
