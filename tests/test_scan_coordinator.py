import asyncio
import unittest
from dataclasses import dataclass
from pathlib import Path

from core.scan_coordinator import (
    ScanCoordinator,
    ScanOrigin,
    ScanState,
    ScanTarget,
)


@dataclass
class FakeStore:
    status: str = "completed"

    def last_scan(self):
        return {"status": self.status}


class FakeBridge:
    def __init__(self):
        self.calls = []
        self.cancel_calls = 0
        self._next = 0

    async def _request(self, source, scope=None):
        self._next += 1
        request_id = f"native-{self._next}"
        self.calls.append((source, scope, request_id))
        return request_id

    async def scan_media_store(self):
        return await self._request("mediastore")

    async def scan_all_storage(self):
        return await self._request("broad_storage")

    async def rescan_tree(self, tree_uri):
        return await self._request("saf", tree_uri)

    async def cancel_scans(self):
        self.cancel_calls += 1
        return "cancel-request"


class ScanCoordinatorTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.store = FakeStore()
        self.bridge = FakeBridge()
        self.targets = [
            ScanTarget("mediastore"),
            ScanTarget("broad_storage"),
            ScanTarget("saf", "tree://anime"),
        ]
        self.snapshots = []

        def target_provider(source, scope_ref):
            if source == "mediastore":
                return [ScanTarget("mediastore")]
            if source == "broad_storage":
                return [ScanTarget("broad_storage")]
            if source == "saf":
                return [ScanTarget("saf", scope_ref or "tree://anime")]
            return list(self.targets)

        self.coordinator = ScanCoordinator(
            self.bridge,
            self.store,
            target_provider,
            on_state=self.snapshots.append,
        )

    async def test_broken_state_listener_cannot_break_scan_request(self):
        def broken_listener(_snapshot):
            raise RuntimeError("diagnostic listener failed")

        coordinator = ScanCoordinator(
            self.bridge,
            self.store,
            self.coordinator.target_provider,
            on_state=broken_listener,
        )
        transition = await coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")

        self.assertEqual("started", transition.kind)
        self.assertEqual(1, len(self.bridge.calls))
        self.assertEqual(ScanState.RUNNING, coordinator.snapshot.state)

    async def test_startup_does_not_rescan_an_already_indexed_catalog(self):
        transition = await self.coordinator.request(ScanOrigin.STARTUP)
        self.assertEqual("deduped", transition.kind)
        self.assertEqual([], self.bridge.calls)
        self.assertEqual(ScanState.IDLE, self.coordinator.snapshot.state)

    async def test_startup_runs_when_no_completed_scan_exists(self):
        self.store.status = "running"
        transition = await self.coordinator.request(ScanOrigin.STARTUP, source="mediastore")
        self.assertEqual("started", transition.kind)
        self.assertEqual(1, len(self.bridge.calls))
        self.assertEqual(ScanState.RUNNING, self.coordinator.snapshot.state)

    async def test_two_refresh_requests_are_deduplicated(self):
        first = await self.coordinator.request(ScanOrigin.USER_REFRESH)
        second = await self.coordinator.request(ScanOrigin.USER_REFRESH)
        self.assertEqual("started", first.kind)
        self.assertEqual("deduped", second.kind)
        self.assertEqual(3, len(self.bridge.calls))
        self.assertEqual(first.request_id, second.request_id)

    async def test_media_change_bursts_are_coalesced_while_running(self):
        await self.coordinator.request(ScanOrigin.MEDIA_CHANGE, source="mediastore")
        queued = await self.coordinator.request(ScanOrigin.MEDIA_CHANGE, source="mediastore")
        queued_again = await self.coordinator.request(ScanOrigin.MEDIA_CHANGE, source="mediastore")
        self.assertEqual("deduped", queued.kind)
        self.assertEqual("deduped", queued_again.kind)
        self.assertEqual(0, len(self.coordinator._pending))
        self.assertEqual(1, len(self.bridge.calls))

    async def test_user_refresh_precedes_pending_media_change(self):
        await self.coordinator.request(ScanOrigin.MEDIA_CHANGE, source="mediastore")
        refresh = await self.coordinator.request(ScanOrigin.USER_REFRESH)
        self.assertEqual("queued", refresh.kind)
        self.assertEqual(1, len(self.coordinator._pending))
        self.assertEqual(ScanOrigin.USER_REFRESH, self.coordinator._pending[0].origin)
        self.assertGreater(
            self.coordinator._pending[0].priority,
            self.coordinator._pending[-1].priority if len(self.coordinator._pending) > 1 else 0,
        )

    async def test_pending_request_runs_after_current_scan_finishes(self):
        first = await self.coordinator.request(ScanOrigin.MEDIA_CHANGE, source="mediastore")
        queued = await self.coordinator.request(ScanOrigin.USER_REFRESH)
        self.assertEqual("queued", queued.kind)
        finished = await self.coordinator.handle_native_event(
            "mediastore_scan",
            "native-1",
            {"requestId": "native-1", "status": "COMPLETED"},
        )
        self.assertEqual("chained", finished.kind)
        self.assertEqual(4, len(self.bridge.calls))

    async def test_only_one_logical_scan_runs_at_a_time(self):
        await self.coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        await self.coordinator.request(ScanOrigin.USER_REFRESH, source="broad_storage")
        self.assertEqual(1, len(self.coordinator._pending))
        self.assertEqual(1, sum(1 for _ in self.bridge.calls))

    async def test_cancel_requests_real_native_cancellation(self):
        await self.coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        transition = await self.coordinator.cancel()
        self.assertEqual("cancelling", transition.kind)
        self.assertEqual(1, self.bridge.cancel_calls)
        self.assertEqual(ScanState.CANCELLING, self.coordinator.snapshot.state)
        self.assertEqual(0, len(self.coordinator._pending))

    async def test_cancelled_child_finishes_without_resetting_catalog(self):
        await self.coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        transition = await self.coordinator.handle_native_event(
            "mediastore_scan",
            "native-1",
            {"requestId": "native-1", "status": "CANCELLED"},
        )
        self.assertTrue(transition.logical_finished)
        self.assertEqual(ScanState.CANCELLED, self.coordinator.snapshot.state)

    async def test_failed_child_recovers_to_terminal_state(self):
        await self.coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        transition = await self.coordinator.handle_native_event(
            "mediastore_error",
            "native-1",
            {"requestId": "native-1", "status": "FAILED"},
        )
        self.assertTrue(transition.logical_finished)
        self.assertEqual(ScanState.FAILED, self.coordinator.snapshot.state)
        second = await self.coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        self.assertEqual("started", second.kind)

    async def test_partial_child_does_not_corrupt_coordinator_state(self):
        await self.coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        transition = await self.coordinator.handle_native_event(
            "mediastore_scan",
            "native-1",
            {"requestId": "native-1", "status": "PARTIAL"},
        )
        self.assertTrue(transition.logical_finished)
        self.assertEqual(ScanState.PARTIAL, self.coordinator.snapshot.state)

    async def test_duplicate_terminal_event_is_ignored(self):
        await self.coordinator.request(ScanOrigin.USER_REFRESH, source="mediastore")
        first = await self.coordinator.handle_native_event(
            "mediastore_scan",
            "native-1",
            {"requestId": "native-1", "status": "COMPLETED"},
        )
        second = await self.coordinator.handle_native_event(
            "mediastore_scan",
            "native-1",
            {"requestId": "native-1", "status": "COMPLETED"},
        )
        self.assertTrue(first.logical_finished)
        self.assertEqual("unmatched", second.kind)

    async def test_multiple_native_children_refresh_only_after_last_child(self):
        await self.coordinator.request(ScanOrigin.USER_REFRESH)
        first = await self.coordinator.handle_native_event(
            "mediastore_scan",
            "native-1",
            {"requestId": "native-1", "status": "COMPLETED"},
        )
        self.assertEqual("child_completed", first.kind)
        self.assertFalse(first.refresh_required)
        second = await self.coordinator.handle_native_event(
            "broad_storage_scan",
            "native-2",
            {"requestId": "native-2", "status": "COMPLETED"},
        )
        self.assertEqual("child_completed", second.kind)
        self.assertFalse(second.refresh_required)
        third = await self.coordinator.handle_native_event(
            "saf_scan",
            "native-3",
            {"requestId": "native-3", "status": "COMPLETED"},
        )
        self.assertTrue(third.refresh_required)
        self.assertTrue(third.logical_finished)

    async def test_one_hundred_media_events_do_not_create_one_hundred_scans(self):
        await self.coordinator.request(ScanOrigin.MEDIA_CHANGE, source="mediastore")
        for _ in range(100):
            await self.coordinator.request(ScanOrigin.MEDIA_CHANGE, source="mediastore")
        self.assertEqual(1, len(self.bridge.calls))
        self.assertLessEqual(len(self.coordinator._pending), 1)

    async def test_player_return_does_not_create_a_scan(self):
        await asyncio.sleep(0)
        self.assertFalse(self.coordinator.active)
        self.assertEqual([], self.bridge.calls)

    async def test_background_foreground_without_change_is_not_a_scan(self):
        transition = await self.coordinator.request(
            ScanOrigin.BACKGROUND_RECONCILIATION,
            source="mediastore",
        )
        self.assertEqual("ignored", transition.kind)
        self.assertEqual([], self.bridge.calls)

    async def test_no_authorized_target_is_blocked(self):
        empty = ScanCoordinator(
            self.bridge,
            self.store,
            lambda _source, _scope: [],
        )
        transition = await empty.request(ScanOrigin.USER_REFRESH)
        self.assertEqual("blocked", transition.kind)
        self.assertEqual(ScanState.BLOCKED, empty.snapshot.state)


class ScanCoordinatorSourceContractTests(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[1]

    def read(self, path):
        return (self.ROOT / path).read_text(encoding="utf-8")

    def test_main_resume_requests_coordinator_not_scanner(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
        start = source.index("override fun onResume()")
        end = source.index("override fun onPause()", start)
        resume = source[start:end]
        self.assertIn('publishScanRequest(', resume)
        self.assertIn('"STARTUP"', resume)
        self.assertNotIn("scanMediaStore(null)", resume)
        self.assertNotIn("scanAllStorage(null)", resume)
        self.assertNotIn("scanTree(tree, null)", resume)

    def test_mediastore_observer_forwards_change_to_coordinator(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
        start = source.index("private fun scheduleMediaStoreIncrementalRescan()")
        end = source.index("private val storageReceiver", start)
        block = source[start:end]
        scheduler = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreRetryScheduler.kt")
        self.assertIn("MediaStoreRetryScheduler.schedule(", block)
        self.assertIn("fun schedule(", scheduler)
        self.assertIn('"content_observer_debounce"', block)
        self.assertIn("applicationContext", block)
        self.assertNotIn("scanMediaStore(null)", block)

    def test_storage_receiver_does_not_start_scanners_directly(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
        start = source.index("private val storageReceiver")
        end = source.index("private fun registerStorageReceiver", start)
        block = source[start:end]
        self.assertIn('publishScanRequest(', block)
        self.assertNotIn("scanAllStorage(null)", block)
        self.assertNotIn("scanMediaStore(null)", block)

    def test_refresh_library_uses_scan_coordinator(self):
        source = self.read("main.py")
        start = source.index("async def refresh_library")
        end = source.index("async def login", start)
        block = source[start:end]
        self.assertIn("scan_coordinator.request(", block)
        self.assertIn("ScanOrigin.USER_REFRESH", block)
        self.assertNotIn("bridge.scan_all_storage()", block)
        self.assertNotIn("bridge.scan_media_store()", block)
        self.assertNotIn("bridge.rescan_tree(", block)

    def test_native_scan_terminal_events_flow_through_coordinator(self):
        source = self.read("main.py")
        self.assertIn("scan_coordinator.handle_native_event(", source)
        hook = source[source.index("if event_type in {'saf_scan', 'broad_storage_scan', 'mediastore_scan',"): ]
        self.assertIn("on_catalog_changed(refresh_request_id=refresh_request_id)", hook)

    def test_media_observer_has_single_register_unregister_contract(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt")
        self.assertIn("if (changeObserver != null) return", source)
        self.assertIn("registerContentObserver", source)
        self.assertIn("unregisterContentObserver", source)


if __name__ == "__main__":
    unittest.main()
