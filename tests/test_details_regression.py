import json
import tempfile
import unittest
from pathlib import Path

from core.compose_library_bridge import ComposeLibraryBridge
from core.library_store import LibraryStore


class DetailsRegressionTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_sqlite_progress_survives_bridge_republish_and_store_reopen(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "fixture_24-details",
                {"title": "earlier validation stage 24 Details", "genres": "[]"},
                source="local",
            )
            episode_ids = []
            for number in range(1, 11):
                path = f"content://stage24/episode-{number}"
                store.upsert_episode(
                    anime_id,
                    path,
                    f"Episode-{number}.mkv",
                    1,
                    number,
                    episode_type="regular",
                    media_identity=f"stage24:episode:{number}",
                )
                episode_ids.append(store.physical_row(path)["id"])

            self.assertEqual(10, len(episode_ids))
            self.assertEqual(7, episode_ids[6])

            self.assertTrue(
                store.save_progress(
                    "content://stage24/episode-7",
                    18,
                    100,
                    episode_id=episode_ids[6],
                    event_created_at=1700000000,
                )
            )

            bridge = ComposeLibraryBridge(directory, store, store)
            bridge.request_publish("fixture_24_progress")
            await bridge.wait_for_idle()

            snapshot = json.loads(
                (Path(directory) / "reianix-compose/library.json").read_text(
                    encoding="utf-8"
                )
            )
            anime = snapshot["animes"][0]
            episodes = anime["seasons"][0]["episodes"]

            self.assertEqual(10, len(episodes))
            self.assertEqual(list(range(1, 11)), [item["id"] for item in episodes])
            self.assertEqual(episode_ids[6], anime["playback_target_episode_id"])
            self.assertEqual(18, episodes[6]["progress"])
            self.assertEqual("in_progress", episodes[6]["consumption_state"])
            self.assertEqual([], [item for item in episodes if item["id"] not in range(1, 11)])

            reopened = LibraryStore(directory)
            reopened_bridge = ComposeLibraryBridge(directory, reopened, reopened)
            reopened_bridge.request_publish("fixture_24_reopen")
            await reopened_bridge.wait_for_idle()

            reopened_snapshot = json.loads(
                (Path(directory) / "reianix-compose/library.json").read_text(
                    encoding="utf-8"
                )
            )
            reopened_anime = reopened_snapshot["animes"][0]
            reopened_episodes = reopened_anime["seasons"][0]["episodes"]

            self.assertEqual(
                [episode_ids[5], episode_ids[6], episode_ids[7]],
                [item["id"] for item in reopened_episodes if item["id"] in episode_ids[5:8]],
            )
            self.assertEqual(episode_ids[6], reopened_anime["playback_target_episode_id"])
            self.assertEqual(18, reopened_episodes[6]["progress"])
            self.assertEqual("in_progress", reopened_episodes[6]["consumption_state"])


if __name__ == "__main__":
    unittest.main()
