import tempfile
import unittest
from unittest.mock import patch

from core.library_service import LibraryService
from core.library_store import LibraryStore


class RescanTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    @staticmethod
    def doc(uri, relative, *, size=100, mtime=1000, name=None, volume="primary"):
        return {
            "uri": uri,
            "name": name or relative.rsplit("/", 1)[-1],
            "relativePath": relative,
            "mimeType": "video/x-matroska",
            "size": size,
            "modifiedAt": mtime,
            "volumeId": volume,
            "volumeUuid": volume,
        }

    def ingest(self, source, documents, **kwargs):
        return self.service.ingest_documents(
            source,
            documents,
            folder_name="Test",
            source_kind=kwargs.pop("source_kind", "saf"),
            **kwargs,
        )

    def test_unchanged_document_is_not_reprocessed(self):
        source = "content://tree/unchanged"
        document = self.doc("content://media/1", "Show/Show S01E01.mkv")
        first = self.service.ingest_documents(source, [document], scan_id="scan-a", scope_kind="root", scope_ref="")
        second = self.service.ingest_documents(source, [document], scan_id="scan-b", scope_kind="root", scope_ref="")
        row = self.store.physical_row(document["uri"])
        self.assertEqual(1, len(first[0]["seasons"][0]["episodes"]))
        self.assertEqual(1, len(second[0]["seasons"][0]["episodes"]))
        self.assertEqual(0, row["progress"])
        self.assertEqual("completed", self.store.last_scan()["status"])

    def test_partial_scan_does_not_mark_unseen_rows_missing(self):
        source = "content://tree/partial"
        first = self.doc("content://media/p1", "Show/Show S01E01.mkv")
        second = self.doc("content://media/p2", "Show/Show S01E02.mkv")
        self.ingest(source, [first, second], scan_id="partial-a")
        self.ingest(source, [first], scan_id="partial-b", scan_errors=["provider timeout"])
        self.assertFalse(self.store.physical_row(second["uri"])["missing"])
        self.assertEqual("partial", self.store.last_scan()["status"])

    def test_explicit_partial_scan_flag_blocks_reconciliation(self):
        source = "content://tree/partial-flag"
        first = self.doc("content://media/pf1", "Show/Show S01E01.mkv")
        second = self.doc("content://media/pf2", "Show/Show S01E02.mkv")
        self.ingest(source, [first, second], scan_id="pf-a")
        self.service.ingest_documents(
            source,
            [first],
            folder_name="Test",
            source_kind="saf",
            scan_id="pf-b",
            scan_errors=[],
            scan_stats={"partial": True, "errors": []},
            scope_kind="root",
            scope_ref="",
        )
        self.assertFalse(self.store.physical_row(second["uri"])["missing"])
        self.assertEqual("partial", self.store.last_scan()["status"])

    def test_move_and_rename_preserve_media_identity_and_progress(self):
        source = "content://tree/move"
        old = self.doc("content://media/old", "Show/Show S01E01.mkv", size=777, mtime=1234)
        self.ingest(source, [old], scan_id="move-a")
        self.store.save_progress(old["uri"], 45, 100)
        anime_id = self.store.catalog()[0]["id"]
        self.store.toggle_pinned(anime_id)
        self.store.toggle_favorite(anime_id)
        self.store.set_user_tags(anime_id, ["favorite-local"])
        self.store.set_personal_note(anime_id, "keep this")
        self.store.set_episode_identification(old["uri"], season=1, number=1, episode_type="regular", title="Manual E01")

        moved = self.doc(
            "content://media/new",
            "Moved/Show S01E01-renamed.mkv",
            size=777,
            mtime=1234,
        )
        self.ingest(source, [moved], scan_id="move-b")
        row = self.store.physical_row(moved["uri"])
        self.assertIsNotNone(row)
        self.assertIsNone(self.store.physical_row(old["uri"]))
        self.assertEqual(45, row["progress"])
        self.assertTrue(row["manual_override"])
        self.assertEqual("Manual E01", row["episode_title"])
        self.assertTrue(self.store.is_favorite(anime_id))
        catalog = self.store.catalog()
        self.assertTrue(catalog[0]["is_pinned"])
        self.assertEqual(["favorite-local"], catalog[0]["user_tags"])
        self.assertEqual("keep this", catalog[0]["personal_note"])

    def test_duplicate_scan_id_is_idempotent(self):
        source = "content://tree/duplicate-scan"
        document = self.doc("content://media/dup", "Show/Show S01E01.mkv")
        first = self.service.ingest_documents(source, [document], scan_id="same-scan")
        second = self.service.ingest_documents(source, [document], scan_id="same-scan")
        self.assertEqual(first, second)
        rows = self.store.catalog()[0]["seasons"][0]["episodes"]
        self.assertEqual(1, len(rows))

    def test_native_event_claim_is_idempotent(self):
        self.assertTrue(self.store.claim_native_event("event-1"))
        self.assertFalse(self.store.claim_native_event("event-1"))
        self.assertTrue(self.store.claim_native_event("event-2"))

    def test_native_event_presence_is_observable_without_claiming(self):
        self.assertFalse(self.store.has_native_event("event-read-before-claim"))
        self.assertTrue(self.store.claim_native_event("event-read-before-claim"))
        self.assertTrue(self.store.has_native_event("event-read-before-claim"))

    def test_older_native_generation_cannot_overwrite_newer_completed_scan(self):
        source = "mediastore:external:video"
        document = self.doc("content://media/generation", "Show/Show S01E01.mkv")
        self.service.ingest_documents(source, [document], source_kind="mediastore", scan_id="generation-new", scope_kind="volume", scope_ref="external_primary", scan_generation=10)
        self.service.ingest_documents(source, [document], source_kind="mediastore", scan_id="generation-old", scope_kind="volume", scope_ref="external_primary", scan_generation=5)
        scan = self.store.scan_by_id("generation-old")
        self.assertIsNone(scan)
        self.assertFalse(self.store.physical_row(document["uri"])["missing"])

    def test_native_stable_identity_is_used_for_cross_source_deduplication(self):
        media = self.doc("content://media/cross", "Movies/Show S01E01.mkv")
        media["stableId"] = "shared:external_primary:Movies/Show S01E01.mkv"
        broad = self.doc("file:///storage/emulated/0/Movies/Show S01E01.mkv", "Movies/Show S01E01.mkv")
        broad["stableId"] = media["stableId"]
        self.ingest("mediastore:external:video", [media], source_kind="mediastore", scan_id="cross-media")
        self.ingest("broad-storage", [broad], source_kind="broad_storage", scan_id="cross-broad")
        rows = [episode for anime in self.store.catalog() for season in anime["seasons"] for episode in season["episodes"]]
        self.assertEqual(1, len(rows))

    def test_failed_filesystem_stat_is_not_treated_as_removal(self):
        import os
        with tempfile.TemporaryDirectory() as source:
            path = os.path.join(source, "Show S01E01.mkv")
            with open(path, "wb") as handle:
                handle.write(b"x")
            self.store.add_folder(source, name="Local")
            self.service.scan()
            self.assertFalse(self.store.physical_row(path)["missing"])

            original_stat = os.stat

            def failing_stat(value):
                if value == path:
                    raise OSError("temporary I/O failure")
                return original_stat(value)

            with patch("core.library_service.os.stat", side_effect=failing_stat):
                result = self.service.scan()
            self.assertEqual("partial", result.status)
            self.assertFalse(self.store.physical_row(path)["missing"])

    def test_progress_completion_and_manual_state_survive_modified_file(self):
        source = "content://tree/modified"
        document = self.doc("content://media/mod", "Show/Show S01E01.mkv", size=100, mtime=1)
        self.ingest(source, [document], scan_id="modified-a")
        self.store.save_progress(document["uri"], 100, 100)
        self.store.set_episode_identification(document["uri"], season=1, number=1, episode_type="regular", title="Manual")
        changed = dict(document)
        changed["size"] = 200
        changed["modifiedAt"] = 2
        self.ingest(source, [changed], scan_id="modified-b")
        row = self.store.physical_row(document["uri"])
        self.assertTrue(row["watched"])
        self.assertEqual(100, row["progress"])
        self.assertTrue(row["manual_override"])
        self.assertEqual("Manual", row["episode_title"])

    def test_missing_episode_is_restored_without_creating_a_second_entity(self):
        source = "content://tree/recovery"
        original = self.doc("content://media/recover", "Show/Show S01E01.mkv")
        self.ingest(source, [original], scan_id="recovery-a")
        self.store.save_progress(original["uri"], 35, 100)
        self.ingest(source, [], scan_id="recovery-b")
        missing = self.store.physical_row(original["uri"])
        self.assertTrue(missing["missing"])
        self.ingest(source, [original], scan_id="recovery-c")
        restored = self.store.physical_row(original["uri"])
        self.assertFalse(restored["missing"])
        self.assertEqual(35, restored["progress"])
        self.assertEqual(1, len(self.store.catalog()[0]["seasons"][0]["episodes"]))

    def test_manual_metadata_survives_rescan_without_network(self):
        source = "content://tree/metadata"
        document = self.doc("content://media/meta", "Show/Show S01E01.mkv")
        self.ingest(source, [document], scan_id="metadata-a")
        self.service.set_manual_metadata("show", {"title": "Título Manual"})
        self.ingest(source, [document], scan_id="metadata-b")
        row = self.store.anime_metadata("show")
        self.assertEqual("Título Manual", row["title"])
        self.assertEqual("manual", row["metadata_status"])

    def test_scope_does_not_mark_other_source_missing(self):
        source_a = "content://tree/a"
        source_b = "content://tree/b"
        a = self.doc("content://media/a", "Show/Show S01E01.mkv")
        b = self.doc("content://media/b", "Show/Show S01E01.mkv", volume="sdcard")
        self.ingest(source_a, [a], scan_id="scope-a")
        self.ingest(source_b, [b], scan_id="scope-b")
        self.ingest(source_a, [], scan_id="scope-c")
        self.assertTrue(self.store.physical_row(a["uri"])["missing"])
        self.assertFalse(self.store.physical_row(b["uri"])["missing"])


if __name__ == "__main__":
    unittest.main()


class TestNativeVolumeStateIntegration(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    def test_volume_change_persists_without_touching_catalog(self):
        document = {
            "uri": "content://media/volume-safe",
            "name": "Show S01E01.mkv",
            "relativePath": "Show/Show S01E01.mkv",
            "mimeType": "video/x-matroska",
            "size": 100,
            "modifiedAt": 1000,
            "volumeId": "external_primary",
            "volumeUuid": "primary",
        }
        self.service.ingest_documents(
            "mediastore:external:video",
            [document],
            source_kind="mediastore",
            scan_id="volume-catalog",
            scope_kind="volume",
            scope_ref="external_primary",
            scan_generation=1,
        )
        before = self.store.catalog()
        state = self.service.ingest_native_volume_change({
            "current": [{
                "volumeId": "external_primary",
                "uuid": "primary",
                "state": "unmounted",
                "removable": False,
                "emulated": True,
                "primary": True,
                "available": False,
            }],
            "removed": [],
            "added": [],
            "changedVolumes": [],
        })
        self.assertEqual("unmounted", state["external_primary"]["state"])
        self.assertEqual(before[0]["id"], self.store.catalog()[0]["id"])
        self.assertTrue(self.store.physical_row(document["uri"])["missing"])
        self.assertEqual("volume_unavailable", self.store.physical_row(document["uri"])["availability_state"])
