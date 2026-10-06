import asyncio
import json
import tempfile
import unittest
from pathlib import Path

from core.compose_library_bridge import ComposeLibraryBridge


class DetailsActionsProgressTests(unittest.TestCase):
    def test_compose_projection_uses_canonical_special_only_playback_target(self):
        class FakeLibrary:
            def __init__(self):
                self.playback_target_calls = []

            def catalog(self):
                return [{
                    "id": 310,
                    "main_title": "Special Only",
                    "favorite": False,
                    "media_kind": "series",
                    "current_episode": None,
                    "specials": [{
                        "season": None,
                        "season_name": "Especiais",
                        "episodes": [{
                            "id": 3101,
                            "anime_id": 310,
                            "season": None,
                            "number": 1,
                            "episode_title": "Special 1",
                            "file_name": "special-1.mkv",
                            "path": "content://special/1",
                            "media_identity": "special-3101",
                            "availability_state": "available",
                            "missing": False,
                            "progress": 0,
                            "duration": 100,
                            "watched": False,
                            "consumption_state": "unwatched",
                        }],
                    }],
                    "seasons": [],
                    "media_files": [],
                    "genres": [],
                    "genre_ids": [],
                    "meta": {},
                }]

            def playback_target(self, anime_id):
                self.playback_target_calls.append(anime_id)
                return {
                    "id": 3101,
                    "anime_id": 310,
                    "path": "content://special/1",
                    "availability_state": "available",
                }

            def continue_watching(self, limit=12):
                return []

        class FakeStore:
            def folders(self):
                return [{
                    "status": "granted",
                    "authorization": "granted",
                }]

        with tempfile.TemporaryDirectory() as directory:
            library = FakeLibrary()
            bridge = ComposeLibraryBridge(directory, library, FakeStore())

            async def publish():
                bridge.request_publish("stage13")
                await bridge.wait_for_idle()

            asyncio.run(publish())

            payload = json.loads(
                (Path(directory) / "reianix-compose" / "library.json").read_text(
                    encoding="utf-8"
                )
            )

        self.assertEqual([310], library.playback_target_calls)
        self.assertEqual(
            3101,
            payload["animes"][0]["playback_target_episode_id"],
        )


if __name__ == "__main__":
    unittest.main()
