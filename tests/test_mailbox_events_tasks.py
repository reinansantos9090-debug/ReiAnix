from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from core.android_bridge import AndroidBridge


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
HOME = ROOT / "views" / "home_view.py"
ORGANIZE = ROOT / "views" / "organize_view.py"
PLAYER = ROOT / "android" / "app" / "src" / "main" / "kotlin" / "com" / "reiflix" / "reiflix_local" / "NativePlayerActivity.kt"


class NativeMailboxstage14Tests(unittest.TestCase):
    @staticmethod
    def write_event(root: Path, event_id: str, created_at: int, event_type: str = "diagnostic") -> Path:
        queue = root / "reiflix-native-events"
        queue.mkdir(parents=True, exist_ok=True)
        path = queue / f"event-{event_id}.json"
        payload = {
            "eventId": event_id,
            "eventVersion": 2,
            "type": event_type,
            "eventType": event_type,
            "createdAt": created_at,
            "timestamp": created_at,
            "payload": {"event": "MAILBOX_TEST"},
        }
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_drain_is_time_ordered_and_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index in range(10):
                self.write_event(root, f"evt-{index:03d}", 10 + index)

            bridge = AndroidBridge(directory)
            drained = bridge.drain(max_events=3)

            self.assertEqual([10, 11, 12], [event["createdAt"] for event in drained])
            self.assertEqual(7, bridge.pending_count())
            self.assertEqual(3, len(list((root / "reiflix-native-events").glob("*.consumed"))))

            bridge.acknowledge()
            self.assertEqual(7, bridge.pending_count())

            remaining = bridge.drain(max_events=10)
            self.assertEqual(list(range(13, 20)), [event["createdAt"] for event in remaining])
            bridge.acknowledge()
            self.assertEqual(0, bridge.pending_count())

    def test_failed_event_is_requeued_while_successful_events_are_acknowledged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = self.write_event(root, "evt-failed", 10)
            self.write_event(root, "evt-ok", 11)

            bridge = AndroidBridge(directory)
            drained = bridge.drain(max_events=2)
            self.assertEqual(2, len(drained))

            bridge.requeue_event_ids({"evt-failed"})
            bridge.acknowledge()

            queue = root / "reiflix-native-events"
            self.assertEqual(1, bridge.pending_count())
            self.assertTrue(first.exists())
            self.assertFalse((queue / "event-evt-ok.json").exists())

            replayed = bridge.drain(max_events=1)
            self.assertEqual(["evt-failed"], [event["eventId"] for event in replayed])
            bridge.acknowledge()
            self.assertEqual(0, bridge.pending_count())

    def test_controlled_100_event_load_stays_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index in range(100):
                self.write_event(root, f"load-{index:03d}", 1000 + index)

            bridge = AndroidBridge(directory)
            first = bridge.drain(max_events=64)

            self.assertEqual(64, len(first))
            self.assertEqual(36, bridge.pending_count())
            self.assertEqual(list(range(1000, 1064)), [event["createdAt"] for event in first])

            bridge.acknowledge()
            second = bridge.drain(max_events=64)
            self.assertEqual(36, len(second))
            self.assertEqual(list(range(1064, 1100)), [event["createdAt"] for event in second])
            bridge.acknowledge()
            self.assertEqual(0, bridge.pending_count())


class EventAndTaskContractTests(unittest.TestCase):
    def test_mailbox_backlog_metrics_are_recorded(self):
        source = MAIN.read_text(encoding="utf-8")
        for token in (
            "bridge.pending_count()",
            'performance.gauge("android.mailbox.backlog"',
            'performance.counter("android.mailbox.events_drained"',
            '"android.mailbox.drain"',
            '"backlog_after"',
        ):
            self.assertIn(token, source)

    def test_home_tasks_are_tracked_and_cancelled_on_invalidation(self):
        source = HOME.read_text(encoding="utf-8")
        self.assertIn("view_tasks: set[object] = set()", source)
        self.assertIn("def _start_view_task", source)
        self.assertIn("def cancel_view_tasks", source)
        self.assertIn("cancel_view_tasks()", source)
        self.assertNotIn("loop.create_task(", source)
        self.assertNotIn("asyncio.create_task(", source)
        self.assertEqual(1, source.count("task = page.run_task(handler, *args)"))

    def test_organize_tasks_are_tracked_and_cancelled_on_invalidation(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        self.assertIn("view_tasks: set[object] = set()", source)
        self.assertIn("def _start_view_task", source)
        self.assertIn("def cancel_view_tasks", source)
        self.assertIn("cancel_view_tasks()", source)
        self.assertNotIn("loop.create_task(", source)
        self.assertNotIn("asyncio.create_task(", source)
        self.assertEqual(1, source.count("task = page.run_task(handler, *args)"))

    def test_player_progress_does_not_trigger_global_ui_refresh(self):
        main = MAIN.read_text(encoding="utf-8")
        start = main.index("elif event_type in {'player_progress', 'player_paused', 'player_completed'}:")
        end = main.index("elif event_type == 'player_mark_watched':", start)
        block = main[start:end]
        self.assertIn("store.save_progress", block)
        self.assertNotIn("safe_update()", block)
        self.assertIn("elif event_type == 'player_exited':", main)
        exit_start = main.index("elif event_type == 'player_exited':")
        exit_end = main.index("elif event_type == 'google_sign_in_started':", exit_start)
        exit_block = main[exit_start:exit_end]
        self.assertIn("on_catalog_changed()", exit_block)

    def test_previous_player_transition_and_completion_guards_remain(self):
        player = PLAYER.read_text(encoding="utf-8")
        for token in (
            "episodeChangePending",
            'saveProgress("player_completed", force = true)',
            'requestEpisode("player_next_request")',
            'if (!::player.isInitialized',
            'if (episodeChangePending)',
            'if (errorVisible)',
        ):
            self.assertIn(token, player)


if __name__ == "__main__":
    unittest.main()
