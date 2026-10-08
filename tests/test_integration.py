import tempfile
import unittest
from unittest.mock import patch

from core.library_service import LibraryService
from core.library_store import LibraryStore


class LibraryIntegrationTests(unittest.TestCase):
    def test_incomplete_native_document_is_partial_not_a_scan_crash_or_destructive_reconcile(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            service._identify = lambda lookup, display, on_status: {"title": display}
            source = "content://tree/incomplete"
            valid = {"uri": "content://media/complete-1", "name": "Anime S01E01.mkv", "relativePath": "Anime/Anime S01E01.mkv"}
            service.ingest_documents(source, [valid])
            # A malformed mailbox event used to call ``None.startswith`` and
            # abort ingestion. It must now keep the known item available.
            service.ingest_documents(source, [valid, {"uri": None, "name": "broken.mkv"}])
            episodes = store.catalog()[0]["seasons"][0]["episodes"]
            self.assertEqual(1, len(episodes))
            self.assertFalse(episodes[0]["missing"])
            self.assertIn("incompleto", store.folders()[0]["last_error"])

    def test_saf_ingest_to_progress_navigation_and_reopen(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            metadata = {
                "title": "Attack on Titan",
                "anilist_id": 16498,
                "genres": "[\"Action\"]",
            }
            documents = [
                {
                    "uri": "content://media/attack-01",
                    "name": "E01.mkv",
                    "relativePath": "Attack on Titan/Season 1/E01.mkv",
                    "mimeType": "video/x-matroska",
                },
                {
                    "uri": "content://media/attack-02",
                    "name": "E02.mkv",
                    "relativePath": "Attack on Titan/Season 1/E02.mkv",
                    "mimeType": "video/x-matroska",
                },
            ]

            with patch.object(service, "_identify", return_value=metadata):
                catalog = service.ingest_documents(
                    "content://tree/attack",
                    documents,
                    folder_name="Attack",
                )

            self.assertEqual(len(catalog), 1)
            anime_id = catalog[0]["id"]
            episodes = catalog[0]["seasons"][0]["episodes"]
            self.assertEqual([episode["path"] for episode in episodes], [
                "content://media/attack-01",
                "content://media/attack-02",
            ])

            store.save_progress("content://media/attack-01", 100, 100)
            target = store.playback_target(anime_id)
            self.assertEqual(target["path"], "content://media/attack-02")

            reopened = LibraryStore(directory)
            resumed = reopened.playback_target(anime_id)
            self.assertEqual(resumed["path"], "content://media/attack-02")
            self.assertTrue(reopened.catalog()[0]["current_episode"]["path"].endswith("attack-02"))

    def test_saf_ingest_keeps_local_episodes_when_anilist_is_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            documents = [{
                "uri": "content://media/offline-01",
                "name": "E01.mkv",
                "relativePath": "Offline Anime/S01E01.mkv",
                "mimeType": "video/x-matroska",
            }]

            with patch.object(
                service,
                "_identify",
                side_effect=RuntimeError("AniList indisponível"),
            ):
                catalog = service.ingest_documents(
                    "content://tree/offline",
                    documents,
                    folder_name="Offline",
                )

            self.assertEqual(len(catalog), 1)
            episode = catalog[0]["seasons"][0]["episodes"][0]
            self.assertEqual(episode["path"], "content://media/offline-01")
            self.assertFalse(episode["missing"])
            scan = store.last_scan()
            self.assertEqual(scan["videos"], 1)
            self.assertEqual(scan["episodes"], 1)

    def test_complete_then_partial_saf_scan_preserves_unseen_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            metadata = {"title": "Naruto", "anilist_id": 20, "genres": "[]"}

            first_scan = [
                {"uri": "content://media/naruto-01", "name": "01.mkv",
                 "relativePath": "Naruto/S01E01.mkv"},
                {"uri": "content://media/naruto-02", "name": "02.mkv",
                 "relativePath": "Naruto/S01E02.mkv"},
            ]
            with patch.object(service, "_identify", return_value=metadata):
                service.ingest_documents("content://tree/naruto", first_scan)

                service.ingest_documents(
                    "content://tree/naruto",
                    first_scan[:1],
                    scan_errors=["Não foi possível acessar uma subpasta"],
                )

            rows = store.catalog()[0]["seasons"][0]["episodes"]
            self.assertEqual(
                {episode["path"] for episode in rows},
                {"content://media/naruto-01", "content://media/naruto-02"},
            )
            self.assertFalse(any(episode["missing"] for episode in rows))
            self.assertTrue(store.folders()[0]["last_error"])
            

if __name__ == "__main__":
    unittest.main()
