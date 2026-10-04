import unittest
from pathlib import Path
import tempfile

from core.storage_access import (
    StorageAccessState,
    StorageCapabilities,
    storage_access_state,
    storage_source_states,
)
from core.library_store import LibraryStore
from core.library_service import LibraryService

ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MEDIA_STORE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
BRIDGE = ROOT / "core/android_bridge.py"


class TestStoragePermissionFlow(unittest.TestCase):
    def test_permission_states_are_explicit_and_distinct(self):
        self.assertEqual(StorageAccessState.MEDIA_DENIED.value, "media_denied")
        self.assertEqual(StorageAccessState.MEDIA_PARTIAL.value, "media_partial")
        self.assertEqual(StorageAccessState.MEDIA_FULL.value, "media_full")
        self.assertEqual(StorageAccessState.SAF_REVOKED.value, "saf_revoked")
        self.assertEqual(StorageAccessState.BROAD_STORAGE_AVAILABLE.value, "broad_storage_available")

    def test_partial_access_is_scan_capable_but_not_reconcilable(self):
        caps = StorageCapabilities(
            media_read_state="partial",
            reconciliation_capabilities=frozenset(),
            scanner_capabilities=frozenset({"mediastore"}),
            api=34,
        )
        self.assertTrue(caps.can_scan("mediastore"))
        self.assertFalse(caps.can_reconcile("mediastore"))

    def test_denied_access_is_not_reported_as_empty_library(self):
        self.assertEqual(
            storage_access_state("denied", False, False),
            StorageAccessState.NEEDS_MEDIA_PERMISSION,
        )

    def test_source_states_keep_saf_and_broad_independent(self):
        states = storage_source_states("partial", False, [], saf_revoked=True)
        self.assertEqual(states["media"], StorageAccessState.MEDIA_PARTIAL.value)
        self.assertEqual(states["saf"], StorageAccessState.SAF_REVOKED.value)
        self.assertEqual(states["broad"], StorageAccessState.BROAD_STORAGE_UNAVAILABLE.value)

    def test_main_activity_has_single_task_and_never_document_launch(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertIn('android:launchMode="singleTask"', manifest)
        self.assertIn('android:documentLaunchMode="never"', manifest)

    def test_permission_flow_uses_flet_0865_non_browser_url_launcher(self):
        source = BRIDGE.read_text(encoding="utf-8")
        self.assertIn("launcher = getattr(self.page, \"url_launcher\", None)", source)
        self.assertIn("EXTERNAL_NON_BROWSER_APPLICATION", source)
        self.assertIn("await launcher.launch_url(url, mode=external_non_browser)", source)
        self.assertNotIn("await self.page.launch_url(url)", source)
        self.assertNotIn("self.page.launch_url(url, mode=", source)

    def test_mediastore_partial_result_is_never_marked_reconcilable(self):
        source = MEDIA_STORE.read_text(encoding="utf-8")
        self.assertIn("canReconcile", source)
        self.assertIn('access!="full"', source)
        self.assertIn("var volumeVideos=0", source)

    def test_empty_check_is_scoped_to_current_volume(self):
        source = MEDIA_STORE.read_text(encoding="utf-8")
        self.assertIn("var volumeWaitingForMediaStore=false", source)
        self.assertIn("volumeName", source)
        self.assertIn("probeHasMedia", source)
        self.assertIn("volumeVideos==0", source)
        self.assertNotIn("Thread.sleep(300L)", source)

    def test_catalog_survives_failed_native_ingest(self):
        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            service = LibraryService(store)
            doc = {
                "uri": "content://provider/video/1",
                "name": "Show S01E01.mkv",
                "relativePath": "Show/S01E01.mkv",
                "mimeType": "video/x-matroska",
                "size": 100,
                "modifiedAt": 1000,
            }
            service.ingest_documents(
                "content://provider/tree/root",
                [doc],
                source_kind="saf",
                scan_id="ok",
                scan_stats={"status": "completed"},
            )
            service.ingest_documents(
                "content://provider/tree/root",
                [],
                source_kind="saf",
                scan_id="failed",
                scan_stats={"status": "failed"},
                scan_errors=["provider failure"],
            )
            row = store.physical_row(doc["uri"])
            self.assertFalse(row["missing"])
            self.assertEqual(row["availability_state"], "available")

    def test_manifest_declares_modern_media_permissions(self):
        source = MANIFEST.read_text(encoding="utf-8")
        self.assertIn("READ_MEDIA_VIDEO", source)
        self.assertIn("READ_MEDIA_VISUAL_USER_SELECTED", source)
        self.assertIn("MANAGE_EXTERNAL_STORAGE", source)

    def test_coordinator_preserves_partial_child_status_until_logical_finish(self):
        source = (ROOT / "core/scan_coordinator.py").read_text(encoding="utf-8")
        self.assertIn("_child_statuses", source)
        self.assertIn("ScanState.PARTIAL.value in statuses", source)
        self.assertIn("ScanState.FAILED.value in statuses", source)

if __name__ == "__main__":
    unittest.main()
