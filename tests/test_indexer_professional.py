import tempfile
import unittest

from core.library_service import LibraryService
from core.library_store import LibraryStore


class ProfessionalIndexerTests(unittest.TestCase):
    def _service(self, directory):
        store = LibraryStore(directory)
        service = LibraryService(store)
        service._identify = lambda lookup, display, on_status, **kwargs: {"title": display, "genres": "[]"}
        return store, service

    def test_same_native_document_twice_is_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            doc = {
                "uri": "content://media/1",
                "name": "Show S01E01.mkv",
                "relativePath": "Show/Show S01E01.mkv",
                "mimeType": "video/x-matroska",
                "size": 100,
                "modifiedAt": 10,
                "volumeId": "external_primary",
            }
            service.ingest_documents("mediastore:external:video", [doc], source_kind="mediastore")
            service.ingest_documents("mediastore:external:video", [doc], source_kind="mediastore")
            rows = store.catalog()[0]["seasons"][0]["episodes"]
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["path"], doc["uri"])
            self.assertGreaterEqual(store.last_scan()["unchanged_files"], 1)

    def test_same_physical_file_from_media_saf_and_broad_is_one_catalog_entity(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            media = {"uri":"content://media/external_primary/video/1","name":"Show S01E01.mkv","relativePath":"Movies/Show/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            broad = {"uri":"file:///storage/emulated/0/Movies/Show/Show S01E01.mkv","name":"Show S01E01.mkv","relativePath":"Movies/Show/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            saf = {"uri":"content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FShow%2FShow%20S01E01.mkv","treeUri":"content://com.android.externalstorage.documents/tree/primary%3AMovies","documentId":"primary:Movies/Show/Show S01E01.mkv","name":"Show S01E01.mkv","relativePath":"Show/Show S01E01.mkv","volumeId":"primary","size":100,"modifiedAt":10}
            service.ingest_documents("mediastore:external:video",[media],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary")
            service.ingest_documents("broad-storage",[broad],source_kind="broad_storage",scope_kind="volume",scope_ref="external_primary")
            service.ingest_documents(saf["treeUri"],[saf],source_kind="saf",scope_kind="root",scope_ref=saf["treeUri"])
            rows=[e for a in store.catalog() for s in a["seasons"] for e in s["episodes"]]
            self.assertEqual(len(rows),1)
            with store._conn() as con:
                observations=con.execute("SELECT source_kind FROM episode_observations WHERE episode_id=?",(rows[0]["id"],)).fetchall()
            self.assertEqual({row["source_kind"] for row in observations},{"mediastore","broad_storage","saf"})
            self.assertEqual(len(observations),3)

    def test_empty_complete_reconciles_only_that_scope(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            doc={"uri":"content://media/1","name":"Show S01E01.mkv","relativePath":"Shows/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            service.ingest_documents("mediastore:external:video",[doc],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary")
            service.ingest_documents("mediastore:external:video",[],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary",scan_stats={"status":"empty_complete"})
            row=[e for a in store.catalog() for s in a["seasons"] for e in s["episodes"]][0]
            self.assertTrue(row["missing"])
            self.assertEqual(row["availability_state"],"missing")
            self.assertEqual(store.last_scan()["generation_status"],"EMPTY_COMPLETE")

    def test_partial_cancelled_failed_and_unavailable_never_reconcile_absence(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            doc={"uri":"content://media/1","name":"Show S01E01.mkv","relativePath":"Shows/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            service.ingest_documents("mediastore:external:video",[doc],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary")
            for scan_stats,scan_errors in [({"status":"partial"},["permission"]),({"status":"cancelled","cancelled":True},[]),({"status":"failed"},[]),({"status":"unavailable"},[])]:
                service.ingest_documents("mediastore:external:video",[],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary",scan_stats=scan_stats,scan_errors=scan_errors)
            row=[e for a in store.catalog() for s in a["seasons"] for e in s["episodes"]][0]
            self.assertFalse(row["missing"])

    def test_older_native_generation_cannot_override_newer_completed_scope(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            newer={"uri":"content://media/2","name":"Show S01E01.mkv","relativePath":"Shows/Show S01E01.mkv","volumeId":"external_primary","size":200,"modifiedAt":20}
            older=dict(newer,uri="content://media/1",size=100,modifiedAt=10)
            service.ingest_documents("mediastore:external:video",[newer],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary",scan_generation=2)
            service.ingest_documents("mediastore:external:video",[older],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary",scan_generation=1)
            rows=[e for a in store.catalog() for s in a["seasons"] for e in s["episodes"]]
            self.assertEqual(len(rows),1)
            self.assertEqual(rows[0]["file_size"],200)

    def test_stale_volume_event_cannot_roll_back_newer_snapshot(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            newer={"current":[{"volumeId":"SD","state":"mounted","available":True}],"removed":[],"eventTimestamp":200.0}
            older={"current":[],"removed":[{"volumeId":"SD"}],"eventTimestamp":100.0}
            service.ingest_native_volume_change(newer)
            service.ingest_native_volume_change(older)
            state=store.native_volume_states()["SD"]
            self.assertTrue(state["available"])
            self.assertEqual(state["state"],"mounted")

    def test_cross_source_observation_keeps_item_available_when_one_source_is_reconciled_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            doc_media={"uri":"content://media/external_primary/1","name":"Show S01E01.mkv","relativePath":"Shows/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            doc_broad={"uri":"file:///storage/emulated/0/Shows/Show S01E01.mkv","name":"Show S01E01.mkv","relativePath":"Shows/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            service.ingest_documents("mediastore:external:video",[doc_media],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary")
            service.ingest_documents("broad-storage",[doc_broad],source_kind="broad_storage",scope_kind="volume",scope_ref="external_primary")
            service.ingest_documents("mediastore:external:video",[],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary",scan_stats={"status":"empty_complete"})
            row=[e for a in store.catalog() for s in a["seasons"] for e in s["episodes"]][0]
            self.assertFalse(row["missing"])
            self.assertEqual(row["availability_state"],"available")

    def test_removing_one_source_keeps_cross_source_entity_playable(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            media={"uri":"content://media/remove-source","name":"Show S01E01.mkv","relativePath":"Shows/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            broad={"uri":"file:///storage/emulated/0/Shows/Show S01E01.mkv","name":"Show S01E01.mkv","relativePath":"Shows/Show S01E01.mkv","volumeId":"external_primary","size":100,"modifiedAt":10}
            service.ingest_documents("mediastore:external:video",[media],source_kind="mediastore",scope_kind="volume",scope_ref="external_primary")
            service.ingest_documents("broad-storage",[broad],source_kind="broad_storage",scope_kind="volume",scope_ref="external_primary")
            store.remove_folder("mediastore:external:video")
            row=store.physical_row(broad["uri"])
            self.assertIsNotNone(row)
            self.assertFalse(row["missing"])
            self.assertEqual("available",row["availability_state"])

    def test_same_name_on_different_volumes_is_not_merged(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            docs = [
                {
                    "uri": "content://media/primary/1",
                    "name": "Show S01E01.mkv",
                    "relativePath": "Show/Show S01E01.mkv",
                    "size": 100,
                    "modifiedAt": 10,
                    "volumeId": "external_primary",
                },
                {
                    "uri": "content://media/sd/1",
                    "name": "Show S01E01.mkv",
                    "relativePath": "Show/Show S01E01.mkv",
                    "size": 100,
                    "modifiedAt": 10,
                    "volumeId": "ABCD-1234",
                },
            ]
            service.ingest_documents("mediastore:external:video", docs, source_kind="mediastore")
            episodes = store.catalog()[0]["seasons"][0]["episodes"]
            self.assertEqual(len(episodes), 2)
            self.assertNotEqual(episodes[0]["volume_id"], episodes[1]["volume_id"])

    def test_partial_scan_does_not_mark_unseen_rows_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            docs = [
                {"uri": "content://tree/1", "name": "Show S01E01.mkv", "relativePath": "Show/S01E01.mkv", "size": 100, "modifiedAt": 10},
                {"uri": "content://tree/2", "name": "Show S01E02.mkv", "relativePath": "Show/S01E02.mkv", "size": 100, "modifiedAt": 10},
            ]
            service.ingest_documents("content://tree/show", docs)
            service.ingest_documents(
                "content://tree/show",
                [docs[0]],
                scan_errors=["subpasta inacessível"],
            )
            rows = store.catalog()[0]["seasons"][0]["episodes"]
            self.assertEqual({row["path"] for row in rows}, {"content://tree/1", "content://tree/2"})
            self.assertFalse(any(row["missing"] for row in rows))
            self.assertEqual(store.last_scan()["status"], "partial")

    def test_complete_scan_reconciles_only_that_source(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            first = {"uri": "content://tree/1", "name": "Show S01E01.mkv", "relativePath": "Show/S01E01.mkv", "size": 100, "modifiedAt": 10}
            second = {"uri": "content://tree/2", "name": "Show S01E02.mkv", "relativePath": "Show/S01E02.mkv", "size": 100, "modifiedAt": 10}
            other = {"uri": "content://other/1", "name": "Other S01E01.mkv", "relativePath": "Other/S01E01.mkv", "size": 100, "modifiedAt": 10}
            service.ingest_documents("content://tree/show", [first, second])
            service.ingest_documents("content://tree/other", [other])
            service.ingest_documents("content://tree/show", [first])
            rows = {row["path"]: row for anime in store.catalog() for season in anime["seasons"] for row in season["episodes"]}
            self.assertTrue(rows["content://tree/2"]["missing"])
            self.assertFalse(rows["content://other/1"]["missing"])

    def test_modified_file_updates_physical_state_without_destroying_user_state(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            doc = {"uri": "file:///library/Show S01E01.mkv", "name": "Show S01E01.mkv", "relativePath": "Show/Show S01E01.mkv", "size": 100, "modifiedAt": 10}
            service.ingest_documents("/library", [doc], source_kind="broad_storage")
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            store.save_progress(50 and doc["uri"], 50, 100)
            store.toggle_favorite(store.catalog()[0]["id"])
            store.set_user_tags(store.catalog()[0]["id"], ["keep"])
            store.set_episode_identification(doc["uri"], season=2, number=17)
            changed = dict(doc)
            changed["size"] = 200
            changed["modifiedAt"] = 20
            service.ingest_documents("/library", [changed], source_kind="broad_storage")
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            anime = store.catalog()[0]
            self.assertEqual(episode["file_size"], 200)
            self.assertEqual(episode["season"], 2)
            self.assertEqual(episode["number"], 17)
            self.assertEqual(episode["progress"], 50)
            self.assertTrue(anime["favorite"])
            self.assertEqual(anime["user_tags"], ["keep"])

    def test_unknown_file_is_not_forced_into_episode_one(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            doc = {"uri": "content://tree/unknown", "name": "video_final.mkv", "relativePath": "Unknown/video_final.mkv", "size": 100, "modifiedAt": 10}
            service.ingest_documents("content://tree/unknown", [doc])
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            self.assertIsNone(episode["number"])
            self.assertEqual(store.catalog()[0]["media_kind"], "unknown")

    def test_scan_ids_are_durable_and_distinct(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            doc = {"uri": "content://tree/1", "name": "Show S01E01.mkv", "relativePath": "Show/S01E01.mkv", "size": 100, "modifiedAt": 10}
            service.ingest_documents("content://tree/show", [doc])
            first = store.last_scan()["scan_id"]
            service.ingest_documents("content://tree/show", [doc])
            second = store.last_scan()["scan_id"]
            self.assertTrue(first)
            self.assertTrue(second)
            self.assertNotEqual(first, second)

    def test_schema_18_adds_physical_and_scan_fields(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            with store._conn() as con:
                episode_columns = {row[1] for row in con.execute("PRAGMA table_info(episodes)")}
                scan_columns = {row[1] for row in con.execute("PRAGMA table_info(scan_runs)")}
                observation_columns = {row[1] for row in con.execute("PRAGMA table_info(episode_observations)")}
            self.assertTrue({"relative_path", "volume_id", "volume_uuid"} <= episode_columns)
            self.assertTrue({"source_kind", "scope_kind", "scope_ref", "uri", "first_seen", "last_seen", "state"} <= observation_columns)
            self.assertTrue({"scan_id", "scope_kind", "scope_ref", "new_files", "unchanged_files"} <= scan_columns)


if __name__ == "__main__":
    unittest.main()
