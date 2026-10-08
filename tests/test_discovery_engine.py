import os
import tempfile
import unittest

from core.library_service import LibraryService
from core.library_store import LibraryStore


class DiscoveryEngineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    @staticmethod
    def doc(uri, relative, *, volume="external_primary", size=100, mtime=1000, stable_id=None):
        item = {
            "uri": uri,
            "name": relative.rsplit("/", 1)[-1],
            "relativePath": relative,
            "mimeType": "video/x-matroska",
            "size": size,
            "modifiedAt": mtime,
            "volumeId": volume,
            "volumeUuid": volume,
        }
        if stable_id:
            item["stableId"] = stable_id
        return item

    def ingest(self, source, docs, *, source_kind="saf", scan_id=None, scope_kind="root", scope_ref=None,
               scan_generation=None, scan_stats=None, scan_errors=None, scope_scans=None):
        return self.service.ingest_documents(
            source,
            docs,
            folder_name="DiscoverySample",
            source_kind=source_kind,
            scan_id=scan_id,
            scope_kind=scope_kind,
            scope_ref=scope_ref,
            scan_generation=scan_generation,
            scan_stats=scan_stats or {},
            scan_errors=scan_errors or [],
            scope_scans=scope_scans,
        )

    def test_complete_scan_removes_only_items_proven_missing(self):
        source = "mediastore:external:video"
        a = self.doc("content://media/a", "Movies/Show S01E01.mkv")
        b = self.doc("content://media/b", "Movies/Show S01E02.mkv")
        self.ingest(
            source,
            [a, b],
            source_kind="mediastore",
            scan_id="full-1",
            scope_kind="volume",
            scope_ref="external_primary",
            scan_generation=1,
        )
        self.ingest(
            source,
            [a],
            source_kind="mediastore",
            scan_id="full-2",
            scope_kind="volume",
            scope_ref="external_primary",
            scan_generation=2,
        )
        self.assertFalse(self.store.physical_row(a["uri"])["missing"])
        self.assertTrue(self.store.physical_row(b["uri"])["missing"])
        self.assertEqual("missing", self.store.physical_row(b["uri"])["availability_state"])

    def test_partial_scan_never_reconciles_unseen_items(self):
        source = "content://tree/partial"
        a = self.doc("content://media/p1", "Show/Show S01E01.mkv")
        b = self.doc("content://media/p2", "Show/Show S01E02.mkv")
        self.ingest(source, [a, b], scan_id="partial-1", scan_stats={"status": "completed"})
        self.ingest(
            source,
            [a],
            scan_id="partial-2",
            scan_stats={"status": "partial", "partial": True},
            scan_errors=["provider timeout"],
        )
        self.assertFalse(self.store.physical_row(b["uri"])["missing"])

    def test_cancelled_scan_is_explicit_and_preserves_catalog(self):
        source = "content://tree/cancel"
        a = self.doc("content://media/c1", "Show/Show S01E01.mkv")
        self.ingest(source, [a], scan_id="cancel-1", scan_stats={"status": "completed"})
        self.store.save_progress(a["uri"], 61, 100)
        self.ingest(
            source,
            [],
            scan_id="cancel-2",
            scan_stats={"status": "cancelled", "cancelled": True},
        )
        row = self.store.physical_row(a["uri"])
        self.assertFalse(row["missing"])
        self.assertEqual(61, row["progress"])
        scan = self.store.scan_by_id("cancel-2")
        self.assertEqual("cancelled", scan["status"])
        self.assertEqual("CANCELLED", scan["generation_status"])
        self.assertEqual(1, scan["cancelled"])

    def test_failed_scan_is_not_recorded_as_completed(self):
        source = "content://tree/fail"
        a = self.doc("content://media/f1", "Show/Show S01E01.mkv")
        self.ingest(source, [a], scan_id="fail-1", scan_stats={"status": "completed"})
        self.ingest(
            source,
            [],
            scan_id="fail-2",
            scan_stats={"status": "failed"},
            scan_errors=["provider failure"],
        )
        scan = self.store.scan_by_id("fail-2")
        self.assertEqual("error", scan["status"])
        self.assertEqual("FAILED", scan["generation_status"])
        self.assertFalse(self.store.physical_row(a["uri"])["missing"])

    def test_generation_metadata_contains_id_and_status(self):
        run_id = self.store.begin_scan(
            scan_id="generation-run",
            source_kind="mediastore",
            scope_kind="volume",
            scope_ref="external_primary",
            native_generation=42,
            generation_id="native:42",
        )
        self.store.finish_scan(run_id, {"status": "completed", "videos": 2})
        row = self.store.scan_by_id("generation-run")
        self.assertEqual("native:42", row["generation_id"])
        self.assertEqual("COMPLETED", row["generation_status"])
        self.assertEqual("completed", row["status"])

    def test_volume_disconnect_marks_items_unavailable_without_deleting_user_state(self):
        source = "mediastore:external:video"
        doc = self.doc(
            "content://media/volume-1",
            "Movies/Show S01E01.mkv",
            volume="sdcard-123",
            stable_id="shared:sdcard-123:Movies/Show S01E01.mkv",
        )
        self.ingest(
            source,
            [doc],
            source_kind="mediastore",
            scan_id="volume-1",
            scope_kind="volume",
            scope_ref="sdcard-123",
            scan_generation=1,
        )
        self.store.save_progress(doc["uri"], 77, 120)
        anime_id = self.store.catalog()[0]["id"]
        self.store.toggle_favorite(anime_id)
        self.store.toggle_pinned(anime_id)
        self.store.set_user_tags(anime_id, ["keep"])
        self.store.set_personal_note(anime_id, "volume safe")

        self.service.ingest_native_volume_change({
            "current": [],
            "removed": [{"volumeId": "sdcard-123", "uuid": "sdcard-123"}],
        })
        row = self.store.physical_row(doc["uri"])
        self.assertTrue(row["missing"])
        self.assertEqual("volume_unavailable", row["availability_state"])
        self.assertEqual(77, row["progress"])
        self.assertTrue(self.store.is_favorite(anime_id))
        self.assertTrue(self.store.catalog()[0]["is_pinned"])

        self.service.ingest_native_volume_change({
            "current": [{
                "volumeId": "sdcard-123",
                "uuid": "sdcard-123",
                "state": "mounted",
                "available": True,
                "removable": True,
            }],
            "removed": [],
        })
        row = self.store.physical_row(doc["uri"])
        # A remount is only a discovery signal. The item becomes playable again
        # after a successful scan observes the resource on the mounted volume.
        self.assertTrue(row["missing"])
        self.assertEqual("volume_unavailable", row["availability_state"])
        self.service.ingest_documents(
            "mediastore:external:video",
            [doc],
            source_kind="mediastore",
            scan_id="volume-remount-rescan",
            scope_kind="volume",
            scope_ref="sdcard-123",
            scan_generation=2,
        )
        row = self.store.physical_row(doc["uri"])
        self.assertFalse(row["missing"])
        self.assertEqual("available", row["availability_state"])
        self.assertEqual(77, row["progress"])

    def test_unmounted_volume_does_not_become_a_file_deletion(self):
        doc = self.doc("content://media/unmounted", "Movies/Show S01E01.mkv", volume="sdcard-x")
        self.ingest(
            "mediastore:external:video",
            [doc],
            source_kind="mediastore",
            scan_id="unmounted-1",
            scope_kind="volume",
            scope_ref="sdcard-x",
            scan_generation=1,
        )
        self.service.ingest_native_volume_change({
            "current": [{
                "volumeId": "sdcard-x",
                "uuid": "sdcard-x",
                "state": "unmounted",
                "available": False,
                "removable": True,
            }],
            "removed": [],
        })
        row = self.store.physical_row(doc["uri"])
        self.assertEqual("volume_unavailable", row["availability_state"])
        self.assertTrue(row["missing"])

    def test_scope_reconciliation_does_not_touch_other_volume(self):
        source = "broad-storage"
        a = self.doc("file:///storage/a/Show S01E01.mkv", "Show/Show S01E01.mkv", volume="volume-a")
        b = self.doc("file:///storage/b/Show S01E01.mkv", "Show/Show S01E01.mkv", volume="volume-b")
        self.ingest(source, [a], source_kind="broad_storage", scan_id="scope-a1", scope_kind="volume", scope_ref="volume-a", scan_generation=1)
        self.ingest(source, [b], source_kind="broad_storage", scan_id="scope-b1", scope_kind="volume", scope_ref="volume-b", scan_generation=1)
        self.ingest(source, [], source_kind="broad_storage", scan_id="scope-a2", scope_kind="volume", scope_ref="volume-a", scan_generation=2)
        self.assertTrue(self.store.physical_row(a["uri"])["missing"])
        self.assertFalse(self.store.physical_row(b["uri"])["missing"])

    def test_scope_scans_allow_complete_volume_to_reconcile_even_when_another_is_partial(self):
        source = "broad-storage"
        a1 = self.doc("file:///storage/a/Show S01E01.mkv", "Show/Show S01E01.mkv", volume="volume-a")
        a2 = self.doc("file:///storage/a/Show S01E02.mkv", "Show/Show S01E02.mkv", volume="volume-a")
        b1 = self.doc("file:///storage/b/Show S01E01.mkv", "Show/Show S01E01.mkv", volume="volume-b")
        self.ingest(
            source,
            [a1, a2, b1],
            source_kind="broad_storage",
            scan_id="scoped-1",
            scope_kind="global",
            scope_ref=source,
            scan_generation=1,
        )
        self.ingest(
            source,
            [a1, b1],
            source_kind="broad_storage",
            scan_id="scoped-2",
            scope_kind="global",
            scope_ref=source,
            scan_generation=2,
            scope_scans=[
                {"scopeKind": "volume", "scopeRef": "volume-a", "volumeId": "volume-a", "complete": True, "status": "completed"},
                {"scopeKind": "volume", "scopeRef": "volume-b", "volumeId": "volume-b", "complete": False, "status": "partial"},
            ],
        )
        self.assertTrue(self.store.physical_row(a2["uri"])["missing"])
        self.assertFalse(self.store.physical_row(b1["uri"])["missing"])

    def test_dedupe_mediastore_and_saf(self):
        stable = "shared:external_primary:Movies/Show S01E01.mkv"
        media = self.doc("content://media/123", "Movies/Show S01E01.mkv", stable_id=stable)
        saf = self.doc("content://tree/document-123", "Movies/Show S01E01.mkv", stable_id=stable)
        self.ingest("mediastore:external:video", [media], source_kind="mediastore", scan_id="dedupe-ms", scope_kind="volume", scope_ref="external_primary")
        self.ingest("content://tree/root", [saf], source_kind="saf", scan_id="dedupe-saf", scope_kind="root", scope_ref="content://tree/root")
        rows = [
            episode
            for anime in self.store.catalog()
            for season in anime["seasons"]
            for episode in season["episodes"]
        ]
        self.assertEqual(1, len(rows))

    def test_dedupe_mediastore_and_broad(self):
        stable = "shared:external_primary:Movies/Show S01E01.mkv"
        media = self.doc("content://media/456", "Movies/Show S01E01.mkv", stable_id=stable)
        broad = self.doc("file:///storage/emulated/0/Movies/Show S01E01.mkv", "Movies/Show S01E01.mkv", stable_id=stable)
        self.ingest("mediastore:external:video", [media], source_kind="mediastore", scan_id="dedupe-ms-broad", scope_kind="volume", scope_ref="external_primary")
        self.ingest("broad-storage", [broad], source_kind="broad_storage", scan_id="dedupe-broad", scope_kind="volume", scope_ref="external_primary")
        rows = [
            episode
            for anime in self.store.catalog()
            for season in anime["seasons"]
            for episode in season["episodes"]
        ]
        self.assertEqual(1, len(rows))

    def test_dedupe_saf_and_broad(self):
        stable = "shared:external_primary:Movies/Show S01E01.mkv"
        saf = self.doc("content://tree/document-789", "Movies/Show S01E01.mkv", stable_id=stable)
        broad = self.doc("file:///storage/emulated/0/Movies/Show S01E01.mkv", "Movies/Show S01E01.mkv", stable_id=stable)
        self.ingest("content://tree/root2", [saf], source_kind="saf", scan_id="dedupe-saf-broad", scope_kind="root", scope_ref="content://tree/root2")
        self.ingest("broad-storage", [broad], source_kind="broad_storage", scan_id="dedupe-broad-2", scope_kind="volume", scope_ref="external_primary")
        rows = [
            episode
            for anime in self.store.catalog()
            for season in anime["seasons"]
            for episode in season["episodes"]
        ]
        self.assertEqual(1, len(rows))

    def test_nomedia_directory_is_excluded_from_filesystem_scan(self):
        with tempfile.TemporaryDirectory() as library_root:
            self.store.add_folder(library_root, name="Local", kind="path", authorization="granted")
            visible = os.path.join(library_root, "Show S01E01.mkv")
            hidden_dir = os.path.join(library_root, "Hidden")
            os.makedirs(hidden_dir, exist_ok=True)
            hidden = os.path.join(hidden_dir, "Show S01E02.mkv")
            with open(visible, "wb") as handle:
                handle.write(b"video")
            with open(hidden, "wb") as handle:
                handle.write(b"video")
            with open(os.path.join(hidden_dir, ".nomedia"), "w", encoding="utf-8") as handle:
                handle.write("")
            result = self.service.scan()
            self.assertEqual("completed", result.status)
            self.assertEqual(1, result.videos)
            self.assertGreaterEqual(result.nomedia_directories, 1)
            self.assertIsNotNone(self.store.physical_row(visible))
            self.assertIsNone(self.store.physical_row(hidden))

    def test_restart_preserves_catalog_and_availability_state(self):
        doc = self.doc("content://media/restart", "Show/Show S01E01.mkv", volume="external_primary")
        self.ingest(
            "mediastore:external:video",
            [doc],
            source_kind="mediastore",
            scan_id="restart-1",
            scope_kind="volume",
            scope_ref="external_primary",
            scan_generation=1,
        )
        self.store.save_progress(doc["uri"], 33, 100)
        self.store.mark_volume_unavailable("external_primary", "test")
        reopened = LibraryStore(self.tmp.name)
        row = reopened.physical_row(doc["uri"])
        self.assertEqual(33, row["progress"])
        self.assertEqual("volume_unavailable", row["availability_state"])
        self.assertTrue(row["missing"])

    def test_native_generation_stays_monotonic_per_scope(self):
        # Native generations are represented on the Python side by native_generation.
        self.ingest(
            "mediastore:external:video",
            [self.doc("content://media/g1", "Show S01E01.mkv")],
            source_kind="mediastore",
            scan_id="g1",
            scope_kind="volume",
            scope_ref="external_primary",
            scan_generation=10,
        )
        self.ingest(
            "mediastore:external:video",
            [self.doc("content://media/g0", "Show S01E01.mkv")],
            source_kind="mediastore",
            scan_id="g0",
            scope_kind="volume",
            scope_ref="external_primary",
            scan_generation=9,
        )
        self.assertIsNone(self.store.scan_by_id("g0"))

    def test_reindexing_same_observation_is_idempotent(self):
        doc = self.doc("content://media/reindex", "Show/Show S01E01.mkv")
        first = self.ingest(
            "content://tree/reindex",
            [doc],
            source_kind="saf",
            scan_id="reindex-1",
            scope_kind="root",
            scope_ref="content://tree/reindex",
            scan_stats={"status": "completed"},
        )
        second = self.ingest(
            "content://tree/reindex",
            [doc],
            source_kind="saf",
            scan_id="reindex-2",
            scope_kind="root",
            scope_ref="content://tree/reindex",
            scan_stats={"status": "completed"},
        )
        self.assertEqual(first, second)
        episodes = [
            episode
            for anime in self.store.catalog()
            for season in anime["seasons"]
            for episode in season["episodes"]
        ]
        self.assertEqual(1, len(episodes))


if __name__ == "__main__":
    unittest.main()
