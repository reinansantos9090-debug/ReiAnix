import asyncio
import json
import tempfile
import unittest
from pathlib import Path

from core.compose_library_bridge import ComposeLibraryBridge


class FakeStore:
    def __init__(self, folders=None):
        self._folders = list(folders or [])

    def folders(self):
        return list(self._folders)


class FakeLibrary:
    def __init__(self, catalog, continue_watching=None):
        self._catalog = catalog
        self._continue_watching = list(continue_watching or [])

    def catalog(self):
        return list(self._catalog)

    def continue_watching(self, limit=12):
        return list(self._continue_watching)[:limit]


class ComposeLibraryBridgeTests(unittest.IsolatedAsyncioTestCase):
    def test_anime_projection_preserves_real_score_metadata(self):
        source = {
            "id": 1,
            "main_title": "Score Anime",
            "meta": {
                "score": 86,
                "added_at": 1729000000.5,
                "cover_cache": None,
                "cover_url": None,
                "banner_url": None,
                "description": "A real synopsis.",
                "romaji": "Score Anime",
                "status": "FINISHED",
                "format": "TV",
                "duration": 24,
                "studio": "ReiAnix Studio",
            },
            "seasons": [],
            "specials": [],
            "media_files": [],
        }

        projected = ComposeLibraryBridge._project_anime(source)

        self.assertEqual(86, projected["meta"]["score"])
        self.assertEqual(1729000000.5, projected["meta"]["added_at"])

    def anime_fixture(self):
        return {
            "id": 7,
            "main_title": "ReiAnix Test",
            "lookup_title": "reianix-test",
            "favorite": True,
            "media_kind": "series",
            "genres": ["Action"],
            "genre_ids": ["action"],
            "meta": {
                "year": 2026,
                "metadata_status": "available",
                "cover_cache": "/cache/reianix.jpg",
                "description": "MUST NOT CROSS THE COMPOSE BRIDGE",
            },
            "current_episode": {
                "id": 71,
                "anime_id": 7,
            },
            "seasons": [{
                "season": 1,
                "season_name": "Season 1",
                "episodes": [{
                    "id": 71,
                    "anime_id": 7,
                    "season": 1,
                    "number": 1,
                    "episode_title": "Episode 1",
                    "file_name": "episode-01.mkv",
                    "path": "content://media/external/video/71",
                    "media_identity": "identity-71",
                    "availability_state": "available",
                    "missing": False,
                    "progress": 12.5,
                    "duration": 100.0,
                    "watched": False,
                    "consumption_state": "in_progress",
                    "cover_cache": "/cache/episode-71.jpg",
                }],
            }],
            "specials": [],
            "media_files": [],
        }

    def test_episode_projection_preserves_duration_and_artwork(self):
        projected = ComposeLibraryBridge._project_episode(
            {
                "id": 71,
                "duration": 123.456,
                "artwork": {
                    "artwork_type": "episode_thumbnail",
                    "local_path": "/cache/episode-71.jpg",
                    "status": "ready",
                },
            }
        )
        self.assertEqual(123.456, projected["duration"])
        self.assertEqual("/cache/episode-71.jpg", projected["artwork_local_path"])

    async def test_snapshot_is_real_derived_compact_projection_and_atomic_file(self):
        with tempfile.TemporaryDirectory() as directory:
            library = FakeLibrary([self.anime_fixture()])

            def resolve_artwork_batch(entity_type, entity_ids, artwork_types):
                if entity_type == "episode":
                    return {
                        "71": {
                            "artwork_type": "episode_thumbnail",
                            "local_path": "/cache/episode-71.jpg",
                            "status": "ready",
                        }
                    }
                return {}

            library.resolve_artwork_batch = resolve_artwork_batch
            bridge = ComposeLibraryBridge(
                directory,
                library,
                FakeStore([{"path": "content://tree", "status": "granted", "authorization": "granted"}]),
            )
            bridge.request_publish("test")
            await bridge.wait_for_idle()

            snapshot = json.loads((Path(directory) / "reianix-compose/library.json").read_text())
            self.assertEqual(1, snapshot["schemaVersion"])
            self.assertEqual(1, snapshot["revision"])
            self.assertEqual("READY", snapshot["status"])
            self.assertEqual("MUST NOT CROSS THE COMPOSE BRIDGE", snapshot["animes"][0]["meta"].get("description"))
            self.assertEqual("AVAILABLE", snapshot["sourceState"])
            self.assertTrue(snapshot["sourceAvailable"])
            self.assertEqual([7], [item["id"] for item in snapshot["animes"]])
            episode = snapshot["animes"][0]["seasons"][0]["episodes"][0]
            self.assertEqual(71, episode["id"])
            self.assertEqual("content://media/external/video/71", episode["path"])
            self.assertEqual("identity-71", episode["media_identity"])
            self.assertEqual(12.5, episode["progress"])
            self.assertEqual(100.0, episode["duration"])
            self.assertEqual("/cache/episode-71.jpg", episode["artwork_local_path"])
            self.assertEqual(71, snapshot["animes"][0]["playback_target_episode_id"])
            self.assertEqual([], snapshot["continue_watching"])
            self.assertEqual("MUST NOT CROSS THE COMPOSE BRIDGE", snapshot["animes"][0]["meta"].get("description"))
            self.assertEqual([], list((Path(directory) / "reianix-compose").glob(".*.tmp*")))

    def test_external_cover_url_is_not_published_as_local_artwork(self):
        source = self.anime_fixture()
        source["meta"]["cover_cache"] = "/cache/does-not-exist.jpg"
        source["meta"]["cover_url"] = "https://img.example/poster.jpg"
        projected = ComposeLibraryBridge._project_anime(source)
        self.assertIsNone(projected["artwork_local_path"])
        self.assertEqual("https://img.example/poster.jpg", projected["artwork_external_url"])

    async def test_empty_and_unavailable_states_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            empty = ComposeLibraryBridge(directory, FakeLibrary([]), FakeStore([]))
            empty.request_publish("empty")
            await empty.wait_for_idle()
            payload = json.loads((Path(directory) / "reianix-compose/library.json").read_text())
            self.assertEqual("EMPTY", payload["status"])
            self.assertEqual("NOT_CONFIGURED", payload["sourceState"])
            self.assertFalse(payload["sourceAvailable"])

            unavailable = ComposeLibraryBridge(
                directory,
                FakeLibrary([]),
                FakeStore([{"path": "tree", "status": "revoked", "authorization": "revoked"}]),
            )
            unavailable.request_publish("unavailable")
            await unavailable.wait_for_idle()
            payload = json.loads((Path(directory) / "reianix-compose/library.json").read_text())
            self.assertEqual("EMPTY", payload["status"])
            self.assertEqual("UNAVAILABLE", payload["sourceState"])
            self.assertFalse(payload["sourceAvailable"])

    async def test_scanner_state_is_published_from_real_state_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            scan = {"state": "SCANNING"}
            bridge = ComposeLibraryBridge(
                directory,
                FakeLibrary([]),
                FakeStore([]),
            )
            bridge.set_scan_state_provider(lambda: scan)
            bridge.request_publish("scan_started")
            await bridge.wait_for_idle()

            payload = json.loads((Path(directory) / "reianix-compose/library.json").read_text())
            self.assertTrue(payload["scanInProgress"])
            self.assertEqual("SCANNING", payload["scanState"])

            scan["state"] = "COMPLETED"
            bridge.request_publish("scan_completed")
            await bridge.wait_for_idle()
            payload = json.loads((Path(directory) / "reianix-compose/library.json").read_text())
            self.assertFalse(payload["scanInProgress"])
            self.assertEqual("COMPLETED", payload["scanState"])

    async def test_projection_error_is_explicit(self):
        class BrokenLibrary:
            def catalog(self):
                raise RuntimeError("sqlite unavailable")

        with tempfile.TemporaryDirectory() as directory:
            bridge = ComposeLibraryBridge(directory, BrokenLibrary(), FakeStore())
            bridge.request_publish("error")
            await bridge.wait_for_idle()
            payload = json.loads((Path(directory) / "reianix-compose/library.json").read_text())
            self.assertEqual("ERROR", payload["status"])
            self.assertEqual("sqlite unavailable", payload["error"])
            self.assertEqual([], payload["animes"])

    async def test_revision_requests_coalesce_without_losing_latest_state(self):
        with tempfile.TemporaryDirectory() as directory:
            library = FakeLibrary([self.anime_fixture()])
            bridge = ComposeLibraryBridge(directory, library, FakeStore())
            bridge.request_publish("first")
            bridge.request_publish("second")
            bridge.request_publish("third")
            await bridge.wait_for_idle()
            payload = json.loads((Path(directory) / "reianix-compose/library.json").read_text())
            self.assertEqual(3, payload["revision"])
            self.assertEqual("third", payload["reason"])

    def test_command_results_are_small_and_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = ComposeLibraryBridge(directory, FakeLibrary([]), FakeStore())
            bridge.write_command_result("req-1", "toggle_favorite", "COMPLETED")
            files = list((Path(directory) / "reianix-compose/command-results").glob("command-*.json"))
            self.assertEqual(1, len(files))
            payload = json.loads(files[0].read_text())
            self.assertEqual("req-1", payload["requestId"])
            self.assertEqual("toggle_favorite", payload["action"])
            self.assertEqual("COMPLETED", payload["status"])


if __name__ == "__main__":
    unittest.main()
