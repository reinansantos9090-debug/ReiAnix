import tempfile
import unittest

from core.library_store import LibraryStore


class EpisodePersistenceTests(unittest.TestCase):
    def _episodes(self, store, anime_id):
        rows = []
        for number in range(1, 6):
            path = f"content://stage42/episode{number}"
            episode_id = store.upsert_episode(
                anime_id,
                path,
                f"Show S01E{number:02d}.mkv",
                1,
                number,
                mime_type="video/mp4",
                file_size=1000 + number,
                modified_at=1000 + number,
                source_folder="fixture_42-source",
                media_identity=f"stage42:episode:{number}",
                episode_type="regular",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            rows.append(store.physical_row(path))
            self.assertEqual(episode_id, rows[-1]["id"])
        return rows

    def test_service_rescan_with_weaker_season_evidence_keeps_episode_in_original_season(self):
        from unittest.mock import patch
        from core.library_service import LibraryService

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            metadata = {"title": "earlier validation stage 42 Show", "genres": "[]"}
            first_scan = []
            for number in range(1, 6):
                first_scan.append({
                    "uri": f"content://stage42/service-{number}",
                    "stableId": f"shared:primary:stage42/Show/{number}",
                    "name": f"Show S01E{number:02d}.mkv",
                    "relativePath": f"Show/Season 1/Show S01E{number:02d}.mkv",
                    "mimeType": "video/x-matroska",
                    "size": 1000 + number,
                    "modifiedAt": 1000 + number,
                })

            with patch.object(service, "_identify", return_value=metadata):
                service.ingest_documents("content://tree/stage42", first_scan, folder_name="stage42")
            before = store.catalog()[0]
            first = before["seasons"][0]["episodes"][0]
            self.assertTrue(store.save_progress(first["path"], 37, 100, episode_id=first["id"], event_created_at=1000))

            second_scan = list(first_scan)
            second_scan[0] = {
                **second_scan[0],
                "name": "Show 07.mkv",
                "relativePath": "Show/Season 2/Show 07.mkv",
                "modifiedAt": 2000,
            }
            with patch.object(service, "_identify", return_value=metadata):
                service.ingest_documents("content://tree/stage42", second_scan, folder_name="stage42")

            reopened = LibraryStore(directory)
            group = reopened.catalog()[0]
            season_1 = next(season for season in group["seasons"] if int(season["season"]) == 1)
            episodes = season_1["episodes"]
            self.assertEqual([int(item["number"]) for item in episodes], [1, 2, 3, 4, 5])
            persisted = reopened.episode_by_id(first["id"])
            self.assertEqual(1, persisted["season"])
            self.assertEqual(1, persisted["number"])
            self.assertEqual(37, persisted["progress"])
            self.assertEqual([first["id"]], [item["id"] for item in reopened.continue_watching()])

    def test_five_episode_restart_keeps_canonical_collection_continue_and_target(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "fixture_42-show",
                {"title": "earlier validation stage 42 Show", "media_kind": "series", "genres": "[]"},
                source="local",
            )
            before = self._episodes(store, anime_id)
            first = before[0]
            self.assertTrue(
                store.save_progress(
                    first["path"],
                    37,
                    100,
                    episode_id=first["id"],
                    event_created_at=1000,
                )
            )

            reopened = LibraryStore(directory)
            group = reopened.catalog(anime_ids=[anime_id])[0]
            episodes = [
                episode
                for season in group["seasons"]
                for episode in season.get("episodes", [])
            ]
            self.assertEqual([row["id"] for row in before], [row["id"] for row in episodes])
            self.assertEqual([1, 2, 3, 4, 5], [int(episode["number"]) for episode in episodes])

            continuing = reopened.continue_watching()
            self.assertEqual([first["id"]], [item["id"] for item in continuing])
            self.assertEqual(37, continuing[0]["progress"])

            target = reopened.playback_target(anime_id)
            self.assertIsNotNone(target)
            self.assertEqual(first["id"], target["id"])
            self.assertEqual(37, target["progress"])

    def test_duplicate_identity_reconciliation_keeps_stronger_semantic_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first_anime = store.upsert_anime(
                "fixture_42-dup-a",
                {"title": "earlier validation stage 42 Duplicate A", "media_kind": "series", "genres": "[]"},
                source="local",
            )
            second_anime = store.upsert_anime(
                "fixture_42-dup-b",
                {"title": "earlier validation stage 42 Duplicate B", "media_kind": "series", "genres": "[]"},
                source="local",
            )
            stable = "stage42:duplicate"
            old_id = store.upsert_episode(
                first_anime,
                "content://stage42/old",
                "Show S01E01.mkv",
                1,
                1,
                source_folder="fixture_42-source",
                media_identity=stable,
                episode_type="regular",
                identification_source="sxxexx",
                identification_confidence="high",
                absolute_number=1,
            )
            store.save_progress("content://stage42/old", 37, 100, episode_id=old_id, event_created_at=1000)
            second_id = store.upsert_episode(
                second_anime,
                "content://stage42/new",
                "Show 07.mkv",
                2,
                7,
                source_folder="fixture_42-source",
                media_identity=stable,
                episode_type="regular",
                identification_source="numeric_suffix",
                identification_confidence="medium",
                absolute_number=7,
            )
            self.assertNotEqual(old_id, second_id)
            row = store.episode_by_id(old_id)
            incoming = store.episode_by_id(second_id)
            self.assertEqual(second_anime, incoming["anime_id"])
            self.assertNotEqual(row["media_identity"], incoming["media_identity"])
            self.assertIn("#owner-conflict:", incoming["media_identity"])
            self.assertEqual(first_anime, row["anime_id"])
            self.assertEqual(1, row["season"])
            self.assertEqual(1, row["number"])
            self.assertEqual(1, row["absolute_number"])
            self.assertEqual(37, row["progress"])

    def test_lower_confidence_apply_identification_cannot_undo_canonical_state(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "fixture_42-apply",
                {"title": "earlier validation stage 42 Apply", "media_kind": "series", "genres": "[]"},
                source="local",
            )
            path = "content://stage42/apply"
            episode_id = store.upsert_episode(
                anime_id,
                path,
                "Apply S01E01.mkv",
                1,
                1,
                source_folder="fixture_42-source",
                media_identity="stage42:apply",
                episode_type="regular",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            store.apply_episode_identification(
                path,
                absolute_number=None,
                relative_path="Apply S01E01.mkv",
                episode_type="special",
                episode_title="wrong weaker classification",
                identification_source="legacy",
                identification_confidence="low",
            )
            row = store.episode_by_id(episode_id)
            self.assertEqual(1, row["season"])
            self.assertEqual(1, row["number"])
            self.assertEqual("regular", row["episode_type"])
            self.assertEqual("high", row["identification_confidence"])

    def test_lower_confidence_rescan_does_not_reclassify_existing_stable_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "fixture_42-stable",
                {"title": "earlier validation stage 42 Stable", "media_kind": "series", "genres": "[]"},
                source="local",
            )
            path = "content://stage42/stable"
            episode_id = store.upsert_episode(
                anime_id,
                path,
                "Stable S01E01.mkv",
                1,
                1,
                source_folder="fixture_42-source",
                media_identity="stage42:stable",
                episode_type="regular",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            self.assertTrue(
                store.save_progress(
                    path,
                    37,
                    100,
                    episode_id=episode_id,
                    event_created_at=1000,
                )
            )

            store.upsert_episode(
                anime_id,
                path,
                "Stable S01E01.mkv",
                2,
                7,
                source_folder="fixture_42-source",
                media_identity="stage42:stable",
                episode_type="regular",
                identification_source="numeric_suffix",
                identification_confidence="medium",
            )

            row = store.episode_by_id(episode_id)
            self.assertIsNotNone(row)
            self.assertEqual(episode_id, row["id"])
            self.assertEqual(1, row["season"])
            self.assertEqual(1, row["number"])
            self.assertEqual(37, row["progress"])
            self.assertEqual("regular", row["episode_type"])


    def test_stale_progress_event_cannot_roll_back_canonical_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "fixture_42-stale",
                {"title": "earlier validation stage 42 Stale", "media_kind": "series", "genres": "[]"},
                source="local",
            )
            first_id = store.upsert_episode(
                anime_id,
                "content://stage42/stale-1",
                "Show S01E01.mkv",
                1,
                1,
                source_folder="fixture_42-source",
                media_identity="stage42:stale:1",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            second_id = store.upsert_episode(
                anime_id,
                "content://stage42/stale-2",
                "Show S01E02.mkv",
                1,
                2,
                source_folder="fixture_42-source",
                media_identity="stage42:stale:2",
                identification_source="sxxexx",
                identification_confidence="high",
            )

            self.assertTrue(
                store.save_progress(
                    "content://stage42/stale-1",
                    37,
                    100,
                    episode_id=first_id,
                    event_created_at=2000,
                    session_id="fixture_42-session",
                )
            )
            self.assertFalse(
                store.save_progress(
                    "content://stage42/stale-1",
                    2,
                    100,
                    episode_id=first_id,
                    event_created_at=1500,
                    session_id="fixture_42-session",
                )
            )
            self.assertTrue(
                store.save_progress(
                    "content://stage42/stale-2",
                    12,
                    100,
                    episode_id=second_id,
                    event_created_at=2100,
                    session_id="fixture_42-session",
                )
            )

            first = store.episode_by_id(first_id)
            second = store.episode_by_id(second_id)
            self.assertEqual(37, first["progress"])
            self.assertEqual(12, second["progress"])

    def test_reconciliation_merge_preserves_stronger_identity_on_valid_target(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "fixture_42-reconcile",
                {"title": "earlier validation stage 42 Reconcile", "media_kind": "series", "genres": "[]"},
                source="local",
            )
            strong_id = store.upsert_episode(
                anime_id,
                "content://stage42/reconcile-strong",
                "Show S01E01.mkv",
                1,
                1,
                source_folder="fixture_42-source",
                media_identity="stage42:reconcile",
                episode_type="regular",
                identification_source="sxxexx",
                identification_confidence="high",
                absolute_number=1,
            )
            store.save_progress(
                "content://stage42/reconcile-strong",
                37,
                100,
                episode_id=strong_id,
                event_created_at=1000,
            )

            with store._conn() as connection:
                # Legacy databases can contain duplicate identities from before
                # the current unique index. Recreate that historical condition
                # explicitly so the migration/reconciliation path is tested.
                connection.execute("DROP INDEX IF EXISTS idx_episodes_media_identity")
                connection.execute(
                    """
                    INSERT INTO episodes(
                        anime_id,path,file_name,season,number,duration,progress,watched,
                        mime_type,file_size,modified_at,source_folder,media_identity,
                        missing,last_played_at,episode_type,episode_title,
                        identification_source,identification_confidence,manual_override,
                        availability_state,absolute_number
                    )
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    (
                        anime_id,
                        "content://stage42/reconcile-valid",
                        "Show 07.mkv",
                        2,
                        7,
                        100,
                        5,
                        0,
                        "video/mp4",
                        1000,
                        2000,
                        "fixture_42-source",
                        "stage42:reconcile",
                        0,
                        2000,
                        "regular",
                        None,
                        "numeric_suffix",
                        "medium",
                        0,
                        "available",
                        7,
                    ),
                )

            valid = store.physical_row("content://stage42/reconcile-valid")
            self.assertIsNotNone(valid)
            result = store.apply_library_reconciliation(
                [],
                [{"source_id": strong_id, "target_id": valid["id"]}],
            )

            self.assertGreaterEqual(result["duplicates_merged"], 1)
            survivor = store.physical_row("content://stage42/reconcile-valid")
            self.assertIsNotNone(survivor)
            self.assertEqual(1, survivor["season"])
            self.assertEqual(1, survivor["number"])
            self.assertEqual(1, survivor["absolute_number"])
            self.assertEqual("high", survivor["identification_confidence"])
            self.assertEqual(37, survivor["progress"])
            self.assertIsNone(store.episode_by_id(strong_id))


if __name__ == "__main__":
    unittest.main()
