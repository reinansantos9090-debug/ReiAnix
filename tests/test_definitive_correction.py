import ast
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from core.library_service import LibraryService
from core.library_store import LibraryStore


ROOT = Path(__file__).resolve().parents[1]


class DefinitiveCorrectionTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")


    def _seed_five_episodes(self, store):
        anime_id = store.upsert_anime(
            "fixture_43-show",
            {"title": "Local earlier validation stage 43 Show", "media_kind": "series", "genres": "[]"},
            source="local",
        )
        rows = []
        for number in range(1, 6):
            path = f"content://stage43/episode{number}"
            episode_id = store.upsert_episode(
                anime_id,
                path,
                f"Local Show S01E{number:02d}.mkv",
                1,
                number,
                mime_type="video/mp4",
                file_size=1000 + number,
                modified_at=1000 + number,
                source_folder="fixture_43-source",
                media_identity=f"stage43:stable:{number}",
                episode_type="regular",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            rows.append(store.physical_row(path))
            self.assertEqual(episode_id, rows[-1]["id"])
        return anime_id, rows

    def _snapshot(self, store, anime_id):
        with store._conn() as connection:
            anime = dict(connection.execute("SELECT * FROM anime WHERE id=?", (anime_id,)).fetchone())
            episodes = [
                dict(row)
                for row in connection.execute(
                    "SELECT id,anime_id,path,media_identity,progress,watched,last_played_at,number,season "
                    "FROM episodes WHERE anime_id=? ORDER BY id",
                    (anime_id,),
                )
            ]
        return {
            "anime_id": anime["id"],
            "lookup_title": anime["lookup_title"],
            "title": anime["title"],
            "anilist_id": anime["anilist_id"],
            "episode_ids": [row["id"] for row in episodes],
            "episode_anime_ids": [row["anime_id"] for row in episodes],
            "episode_paths": [row["path"] for row in episodes],
            "media_identities": [row["media_identity"] for row in episodes],
            "progress": [row["progress"] for row in episodes],
            "watched": [row["watched"] for row in episodes],
            "last_played_at": [row["last_played_at"] for row in episodes],
            "numbers": [row["number"] for row in episodes],
            "seasons": [row["season"] for row in episodes],
        }

    def _assert_local_snapshot_invariants(self, before, after):
        self.assertEqual(before["anime_id"], after["anime_id"])
        self.assertEqual(before["lookup_title"], after["lookup_title"])
        self.assertEqual(before["episode_ids"], after["episode_ids"])
        self.assertEqual(before["episode_anime_ids"], after["episode_anime_ids"])
        self.assertEqual(before["episode_paths"], after["episode_paths"])
        self.assertEqual(before["media_identities"], after["media_identities"])
        self.assertEqual(before["progress"], after["progress"])
        self.assertEqual(before["watched"], after["watched"])
        self.assertEqual(before["last_played_at"], after["last_played_at"])
        self.assertEqual(before["numbers"], after["numbers"])
        self.assertEqual(before["seasons"], after["seasons"])

    def _assert_local_identity_invariants(self, before, after):
        self.assertEqual(before["anime_id"], after["anime_id"])
        self.assertEqual(before["episode_ids"], after["episode_ids"])
        self.assertEqual(before["episode_anime_ids"], after["episode_anime_ids"])
        self.assertEqual(before["episode_paths"], after["episode_paths"])
        self.assertEqual(before["media_identities"], after["media_identities"])
        self.assertEqual(before["progress"], after["progress"])
        self.assertEqual(before["watched"], after["watched"])
        self.assertEqual(before["numbers"], after["numbers"])
        self.assertEqual(before["seasons"], after["seasons"])

    def _ani_list_media(self, anilist_id=16498):
        return {
            "id": anilist_id,
            "title": {
                "romaji": "earlier validation stage 43 Remote",
                "english": "earlier validation stage 43 Remote",
                "native": "earlier validation stage 43 Remote",
            },
            "description": "metadata fixture",
            "genres": ["Action"],
            "episodes": 12,
            "duration": 24,
            "format": "TV",
            "status": "FINISHED",
        }

    def _ani_list_metadata(self, anilist_id=16498):
        return {
            "title": "earlier validation stage 43 Remote",
            "romaji": "earlier validation stage 43 Remote",
            "english": "earlier validation stage 43 Remote",
            "native": "earlier validation stage 43 Remote",
            "aliases": "[]",
            "description": "metadata fixture",
            "description_original": "metadata fixture",
            "cover_url": "",
            "cover_cache": "",
            "banner_url": "",
            "genres": '["Action"]',
            "year": 2024,
            "status": "FINISHED",
            "episodes_count": 12,
            "duration": 24,
            "format": "TV",
            "studio": "stage Studio",
            "anilist_id": anilist_id,
            "media_kind": "series",
        }


    def _scan_five(self, service, title):
        documents = [
            {
                "uri": f"content://stage43/matrix/{number}",
                "stableId": f"stage43:stable:{number}",
                "name": f"{title} S01E{number:02d}.mkv",
                "relativePath": f"{title}/Season 1/{title} S01E{number:02d}.mkv",
                "mimeType": "video/mp4",
                "size": 2000 + number,
                "modifiedAt": 2000 + number,
            }
            for number in range(1, 6)
        ]
        with patch.object(
            service,
            "_identify",
            return_value={"title": title, "genres": "[]", "media_kind": "series"},
        ):
            service.ingest_documents(
                "content://stage43/matrix-tree",
                documents,
                folder_name="Matrix",
                source_kind="saf",
            )

    def _apply_metadata_fixture(self, service, anime_id):
        candidate = dict(self._ani_list_media())
        candidate["match_score"] = 1.0
        with patch.object(service.anilist, "search", return_value=[candidate]), \
             patch.object(service.anilist, "by_id", return_value=self._ani_list_media()), \
             patch.object(service.anilist, "metadata_from_media", return_value=self._ani_list_metadata()):
            service.refresh_metadata(
                "fixture_43-show",
                "earlier validation stage 43 Remote",
                force=True,
                bypass_request_dedupe=True,
                local_anime_id=anime_id,
                request_id="fixture_43-matrix-refresh",
            )

    def test_metadata_authorization_and_refresh_preserve_five_episode_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id, episodes = self._seed_five_episodes(store)

            for row in episodes[:4]:
                self.assertTrue(
                    store.save_progress(
                        row["path"],
                        37,
                        100,
                        episode_id=row["id"],
                        event_created_at=1000 + row["id"],
                    )
                )

            store.set_pending_match(
                "fixture_43-show",
                "Local earlier validation stage 43 Show",
                [{"id": 16498, "title": "earlier validation stage 43 Remote"}],
            )
            before = self._snapshot(store, anime_id)

            with patch.object(service.anilist, "by_id", return_value=self._ani_list_media()), \
                 patch.object(service.anilist, "metadata_from_media", return_value=self._ani_list_metadata()):
                service.resolve_match(
                    "fixture_43-show",
                    16498,
                    local_anime_id=anime_id,
                    request_id="fixture_43-manual",
                )
                service.refresh_metadata(
                    "fixture_43-show",
                    "earlier validation stage 43 Remote",
                    force=True,
                    bypass_request_dedupe=True,
                    local_anime_id=anime_id,
                    request_id="fixture_43-refresh",
                )
                service.hydrate_catalog_metadata(service.catalog())

            after = self._snapshot(store, anime_id)
            self._assert_local_snapshot_invariants(before, after)
            self.assertEqual("earlier validation stage 43 Remote", after["title"])
            self.assertEqual(16498, after["anilist_id"])

            group = store.catalog(anime_ids=[anime_id])[0]
            detail_episodes = [
                episode
                for season in group["seasons"]
                for episode in season.get("episodes", [])
            ]
            self.assertEqual(5, len(detail_episodes))
            self.assertEqual(before["episode_ids"], [episode["id"] for episode in detail_episodes])

            continue_ids = {item["id"] for item in store.continue_watching(limit=10)}
            self.assertEqual(set(before["episode_ids"][:4]), continue_ids)
            self.assertEqual(before["episode_ids"][3], store.playback_target(anime_id)["id"])
            self.assertEqual(before["episode_ids"][1], store.next_episode(before["episode_paths"][0])["id"])
            self.assertEqual(before["episode_ids"][0], store.previous_episode(before["episode_paths"][1])["id"])

            for row in episodes[:4]:
                before_repeat = self._snapshot(store, anime_id)
                self.assertTrue(
                    store.save_progress(
                        row["path"],
                        37,
                        100,
                        episode_id=row["id"],
                        event_created_at=5000 + row["id"],
                    )
                )
                with patch.object(service.anilist, "by_id", return_value=self._ani_list_media()), \
                     patch.object(service.anilist, "metadata_from_media", return_value=self._ani_list_metadata()):
                    service.refresh_metadata(
                        "fixture_43-show",
                        "earlier validation stage 43 Remote",
                        force=True,
                        bypass_request_dedupe=True,
                        local_anime_id=anime_id,
                        request_id=f"fixture_43-repeat-{row['id']}",
                    )
                after_repeat = self._snapshot(store, anime_id)
                self._assert_local_identity_invariants(before_repeat, after_repeat)

            reopened = LibraryStore(directory)
            restart_snapshot = self._snapshot(reopened, anime_id)
            self._assert_local_identity_invariants(before, restart_snapshot)
            self.assertEqual(37, restart_snapshot["progress"][0])
            self.assertEqual(37, restart_snapshot["progress"][1])
            self.assertEqual(37, restart_snapshot["progress"][2])
            self.assertEqual(37, restart_snapshot["progress"][3])
            self.assertEqual(37, restart_snapshot["progress"][0])
            self.assertEqual(
                set(before["episode_ids"][:4]),
                {item["id"] for item in reopened.continue_watching(limit=10)},
            )
            reopened_group = reopened.catalog(anime_ids=[anime_id])[0]
            reopened_episodes = [
                episode
                for season in reopened_group["seasons"]
                for episode in season.get("episodes", [])
            ]
            self.assertEqual(5, len(reopened_episodes))

    def test_scan_reuses_local_owner_when_title_lookup_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            first_scan = [
                {
                    "uri": f"content://stage43/initial/{number}",
                    "stableId": f"stage43:stable:{number}",
                    "name": f"Local Show S01E{number:02d}.mkv",
                    "relativePath": f"Local Show/Season 1/Local Show S01E{number:02d}.mkv",
                    "mimeType": "video/mp4",
                    "size": 1000 + number,
                    "modifiedAt": 1000 + number,
                }
                for number in range(1, 6)
            ]
            local_metadata = {"title": "Local earlier validation stage 43 Show", "genres": "[]", "media_kind": "series"}

            with patch.object(service, "_identify", return_value=local_metadata):
                service.ingest_documents(
                    "content://stage43/tree",
                    first_scan,
                    folder_name="stage43",
                    source_kind="saf",
                )

            group_before = store.catalog()[0]
            anime_id = group_before["id"]
            episodes_before = [
                episode
                for season in group_before["seasons"]
                for episode in season.get("episodes", [])
            ]
            ids_before = [episode["id"] for episode in episodes_before]
            self.assertEqual(5, len(ids_before))

            remote_metadata = {**local_metadata, "title": "earlier validation stage 43 Remote", "anilist_id": 16498}
            second_scan = [
                {
                    **document,
                    "uri": f"content://stage43/renamed/{number}",
                    "stableId": f"stage43:stable:{number}",
                    "name": f"earlier validation stage 43 Remote S01E{number:02d}.mkv",
                    "relativePath": f"earlier validation stage 43 Remote/Season 1/earlier validation stage 43 Remote S01E{number:02d}.mkv",
                    "modifiedAt": 2000 + number,
                }
                for number, document in enumerate(first_scan, 1)
            ]

            with patch.object(service, "_identify", return_value=remote_metadata):
                service.ingest_documents(
                    "content://stage43/tree",
                    second_scan,
                    folder_name="stage43",
                    source_kind="saf",
                )

            group_after = store.catalog()
            self.assertEqual(1, len(group_after))
            self.assertEqual(anime_id, group_after[0]["id"])
            episodes_after = [
                episode
                for season in group_after[0]["seasons"]
                for episode in season.get("episodes", [])
            ]
            self.assertEqual(5, len(episodes_after))
            self.assertEqual(ids_before, [episode["id"] for episode in episodes_after])
            self.assertTrue(all(store.episode_by_id(episode["id"])["anime_id"] == anime_id for episode in episodes_after))
            self.assertEqual(
                {f"content://stage43/renamed/{number}" for number in range(1, 6)},
                {episode["path"] for episode in episodes_after},
            )
            self.assertEqual("local show", store.anime_metadata_by_id(anime_id)["lookup_title"])
            self.assertEqual("Local earlier validation stage 43 Show", store.anime_metadata_by_id(anime_id)["title"])

    def test_conflicting_cross_anime_media_identity_never_transfers_episode_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first_anime = store.upsert_anime("fixture_43-a", {"title": "earlier validation stage 43 A", "genres": "[]"})
            second_anime = store.upsert_anime("fixture_43-b", {"title": "earlier validation stage 43 B", "genres": "[]"})

            first_id = store.upsert_episode(
                first_anime,
                "content://stage43/conflict-a",
                "A S01E01.mkv",
                1,
                1,
                media_identity="stage43:conflict",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            second_id = store.upsert_episode(
                second_anime,
                "content://stage43/conflict-b",
                "B S01E01.mkv",
                1,
                1,
                media_identity="stage43:conflict-other",
                identification_source="sxxexx",
                identification_confidence="high",
            )

            with store._conn() as connection:
                # Recreate the legacy duplicate-identity state that older databases
                # could contain before idx_episodes_media_identity became enforced.
                connection.execute("DROP INDEX IF EXISTS idx_episodes_media_identity")
                connection.execute(
                    "UPDATE episodes SET media_identity=? WHERE id=?",
                    ("stage43:conflict", second_id),
                )

            result = store.upsert_episode(
                second_anime,
                "content://stage43/conflict-b",
                "B S01E01.mkv",
                1,
                2,
                media_identity="stage43:conflict",
                identification_source="numeric_suffix",
                identification_confidence="medium",
            )

            self.assertEqual(second_id, result)
            first_row = store.episode_by_id(first_id)
            second_row = store.episode_by_id(second_id)
            self.assertEqual(first_anime, first_row["anime_id"])
            self.assertEqual(second_anime, second_row["anime_id"])
            self.assertNotEqual(first_row["media_identity"], second_row["media_identity"])
            self.assertIn("#owner-conflict:", second_row["media_identity"])



    def test_metadata_scan_restart_order_matrix_preserves_identity(self):
        scenarios = (
            "metadata_before_scan",
            "scan_before_metadata",
            "scan_metadata_scan",
            "restart_metadata",
            "metadata_restart",
            "metadata_restart_scan",
        )
        for scenario in scenarios:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                store = LibraryStore(directory)
                service = LibraryService(store)
                if scenario == "metadata_before_scan":
                    anime_id, _ = self._seed_five_episodes(store)
                    first = store.episode_by_id(
                        store.catalog(anime_ids=[anime_id])[0]["seasons"][0]["episodes"][0]["id"]
                    )
                    store.save_progress(
                        first["path"],
                        37,
                        100,
                        episode_id=first["id"],
                        event_created_at=3000,
                    )
                    self._apply_metadata_fixture(service, anime_id)
                    self._scan_five(service, "earlier validation stage 43 Remote")
                else:
                    self._scan_five(service, "Local earlier validation stage 43 Show")
                    anime_id = store.catalog()[0]["id"]
                    first = store.catalog(anime_ids=[anime_id])[0]["seasons"][0]["episodes"][0]
                    store.save_progress(
                        first["path"],
                        37,
                        100,
                        episode_id=first["id"],
                        event_created_at=3000,
                    )
                    if scenario in {"scan_before_metadata", "scan_metadata_scan", "restart_metadata", "metadata_restart", "metadata_restart_scan"}:
                        self._apply_metadata_fixture(service, anime_id)
                    if scenario in {"restart_metadata", "metadata_restart", "metadata_restart_scan"}:
                        store = LibraryStore(directory)
                        service = LibraryService(store)
                        anime_id = store.catalog()[0]["id"]
                    if scenario == "restart_metadata":
                        self._apply_metadata_fixture(service, anime_id)
                    if scenario in {"scan_metadata_scan", "metadata_restart_scan"}:
                        self._scan_five(service, "earlier validation stage 43 Remote")

                snapshot = self._snapshot(store, anime_id)
                self.assertEqual(5, len(snapshot["episode_ids"]))
                self.assertEqual(
                    snapshot["episode_ids"],
                    [
                        episode["id"]
                        for season in store.catalog(anime_ids=[anime_id])[0]["seasons"]
                        for episode in season["episodes"]
                    ],
                )
                self.assertTrue(all(owner == anime_id for owner in snapshot["episode_anime_ids"]))
                self.assertEqual(37, snapshot["progress"][0])

    def test_anilist_failures_never_remove_local_episodes(self):
        failures = (
            RuntimeError("network failure"),
            TimeoutError("timeout"),
            ValueError("http 429"),
            ValueError("empty response"),
        )
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as directory:
                store = LibraryStore(directory)
                service = LibraryService(store)
                anime_id, _ = self._seed_five_episodes(store)
                first = store.episode_by_id(
                    store.catalog(anime_ids=[anime_id])[0]["seasons"][0]["episodes"][0]["id"]
                )
                store.save_progress(
                    first["path"],
                    37,
                    100,
                    episode_id=first["id"],
                    event_created_at=6000,
                )
                before = self._snapshot(store, anime_id)
                with patch.object(service.anilist, "search", side_effect=failure), \
                     patch.object(service.anilist, "by_id", side_effect=failure):
                    service.refresh_metadata(
                        "fixture_43-show",
                        "earlier validation stage 43 Failure Case",
                        force=True,
                        bypass_request_dedupe=True,
                        local_anime_id=anime_id,
                        request_id=f"fixture_43-failure-{type(failure).__name__}",
                    )
                after = self._snapshot(store, anime_id)
                self._assert_local_snapshot_invariants(before, after)


    def test_cross_anime_media_identity_conflict_with_new_path_creates_scoped_local_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first_anime = store.upsert_anime(
                "fixture_43-conflict-a",
                {"title": "earlier validation stage 43 Conflict A", "genres": "[]"},
            )
            second_anime = store.upsert_anime(
                "fixture_43-conflict-b",
                {"title": "earlier validation stage 43 Conflict B", "genres": "[]"},
            )
            first_id = store.upsert_episode(
                first_anime,
                "content://stage43/conflict/existing",
                "Conflict A S01E01.mkv",
                1,
                1,
                media_identity="stage43:shared-conflict",
                identification_source="sxxexx",
                identification_confidence="high",
            )

            second_id = store.upsert_episode(
                second_anime,
                "content://stage43/conflict/new",
                "Conflict B S01E01.mkv",
                1,
                1,
                media_identity="stage43:shared-conflict",
                identification_source="sxxexx",
                identification_confidence="high",
            )

            self.assertNotEqual(first_id, second_id)
            first_row = store.episode_by_id(first_id)
            second_row = store.episode_by_id(second_id)
            self.assertEqual(first_anime, first_row["anime_id"])
            self.assertEqual(second_anime, second_row["anime_id"])
            self.assertEqual("stage43:shared-conflict", first_row["media_identity"])
            self.assertNotEqual(first_row["media_identity"], second_row["media_identity"])
            self.assertIn("#owner-conflict:", second_row["media_identity"])

            repeated_id = store.upsert_episode(
                second_anime,
                "content://stage43/conflict/new",
                "Conflict B S01E01.mkv",
                1,
                1,
                media_identity="stage43:shared-conflict",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            self.assertEqual(second_id, repeated_id)

    def test_catalog_metadata_hydration_remains_bound_to_local_anime_id_after_lookup_change(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id = store.upsert_anime(
                "fixture_43-canonical-lookup",
                {
                    "title": "earlier validation stage 43 Local",
                    "genres": "[]",
                    "metadata_source": "local",
                    "metadata_status": "unresolved",
                },
            )
            episode_id = store.upsert_episode(
                anime_id,
                "content://stage43/hydration/1",
                "earlier validation stage 43 Local S01E01.mkv",
                1,
                1,
                media_identity="stage43:hydration:1",
                identification_source="sxxexx",
                identification_confidence="high",
            )
            self.assertGreater(episode_id, 0)
            catalog = store.catalog(anime_ids=[anime_id])
            self.assertEqual(1, len(catalog))
            catalog[0]["meta"]["lookup_title"] = "stale-editorial-lookup"
            catalog[0]["main_title"] = "earlier validation stage 43 Renamed"

            with patch.object(
                service,
                "refresh_metadata",
                return_value=store.anime_metadata_by_id(anime_id),
            ):
                hydrated = service.hydrate_catalog_metadata(catalog)

            self.assertEqual(1, len(hydrated))
            self.assertEqual(anime_id, hydrated[0]["id"])
            self.assertEqual("fixture_43-canonical-lookup", hydrated[0]["lookup_title"])
            self.assertEqual(anime_id, store.anime_metadata_by_id(anime_id)["id"])
            self.assertEqual(
                1,
                len(
                    [
                        episode
                        for season in store.catalog(anime_ids=[anime_id])[0]["seasons"]
                        for episode in season["episodes"]
                    ]
                ),
            )

    def test_metadata_title_and_anilist_id_changes_preserve_local_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime(
                "fixture_43-stable-local",
                {"title": "Original Local Title", "anilist_id": 111, "genres": "[]"},
                source="anilist",
            )
            episode_id = store.upsert_episode(
                anime_id,
                "content://stage43/stable-local",
                "Original Local Title S01E01.mkv",
                1,
                1,
                media_identity="stage43:stable-local",
                identification_confidence="high",
            )
            store.save_progress(
                "content://stage43/stable-local",
                37,
                100,
                episode_id=episode_id,
                event_created_at=1000,
            )

            store.upsert_anime(
                "fixture_43-remote-title",
                {
                    "title": "Remote Editorial Title",
                    "romaji": "Remote Editorial Title",
                    "english": "Remote Editorial Title",
                    "native": "Remote Editorial Title",
                    "anilist_id": 222,
                    "genres": "[]",
                    "metadata_source": "anilist",
                    "metadata_status": "available",
                },
                source="anilist",
                local_anime_id=anime_id,
            )

            anime = store.anime_metadata_by_id(anime_id)
            episode = store.episode_by_id(episode_id)
            self.assertEqual(anime_id, anime["id"])
            self.assertEqual("fixture_43-stable-local", anime["lookup_title"])
            self.assertEqual("Remote Editorial Title", anime["title"])
            self.assertEqual(222, anime["anilist_id"])
            self.assertEqual(anime_id, episode["anime_id"])
            self.assertEqual(episode_id, episode["id"])
            self.assertEqual(37, episode["progress"])
            self.assertEqual("stage43:stable-local", episode["media_identity"])

    def test_owner_resolution_prioritizes_local_identity_before_lookup_title(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first_anime = store.upsert_anime("same-title", {"title": "Same Title", "genres": "[]"})
            second_anime = store.upsert_anime("other", {"title": "Other", "genres": "[]"})

            episode_id = store.upsert_episode(
                first_anime,
                "content://stage43/local",
                "Same Title S01E01.mkv",
                1,
                1,
                media_identity="stage43:owner-priority",
                source_folder="fixture_43-source",
                identification_confidence="high",
            )

            self.assertEqual(
                first_anime,
                store.resolve_local_anime_owner(
                    media_identity="stage43:owner-priority",
                    path="content://stage43/unknown",
                    source_folder="fixture_43-source",
                    relative_path="Same Title/Season 1/Same Title S01E01.mkv",
                    lookup_title="other",
                ),
            )
            self.assertEqual(first_anime, store.episode_by_id(episode_id)["anime_id"])
            self.assertNotEqual(first_anime, second_anime)

    def test_metadata_identity_contract_is_explicitly_owner_bound(self):
        store_source = self.read("core/library_store.py")
        service_source = self.read("core/library_service.py")
        main_source = self.read("main.py")
        details_source = self.read("views/details_view.py")
        home_source = self.read("views/home_view.py")

        self.assertIn("def resolve_local_anime_owner(", store_source)
        self.assertIn("local_anime_id=None", store_source)
        self.assertIn('effective_anime_id = existing["anime_id"]', store_source)
        self.assertIn("resolve_local_anime_owner(", service_source)
        self.assertIn("local_anime_id=owner_id", service_source)
        self.assertIn("local_anime_id=anime_id", main_source)
        self.assertIn("library.catalog", details_source + main_source)
        self.assertIn("library.browse_catalog_page", home_source)
        self.assertIn("METADATA_ACTION_START", main_source + service_source)
        self.assertIn("METADATA_ACTION_DB_WRITE", main_source + service_source)
        self.assertIn("METADATA_ACTION_CATALOG_REFRESH", main_source + service_source)
        self.assertIn("METADATA_ACTION_UI_COMMIT", main_source + service_source)
        self.assertIn("navigation.current != \"details\"", main_source)
        self.assertIn("details_instance_generation[0] != details_token", main_source)
        self.assertIn("settings_generation_provider", self.read("views/settings_view.py"))
        self.assertIn("register_settings_task", self.read("views/settings_view.py"))
        self.assertIn("selected.get(\"episodes\", [])", details_source)
        self.assertIn("visible_regular = season_items[:visible_episode_count[0]]", details_source)
        self.assertIn("new_controls.extend(", details_source)
        self.assertIn("episode_item(item, scope=\"episode\")", details_source)
        self.assertIn("library.browse_catalog_page", home_source)

    def test_home_metadata_hydration_uses_the_canonical_artwork_resolver(self):
        source = self.read("views/home_view.py")
        start = source.index("async def hydrate_metadata_and_artwork")
        end = source.index("async def refresh_home_sections", start)
        block = source[start:end]
        self.assertIn("_queue_artwork_resolution(entity, item_id, 'poster', target)", block)
        self.assertNotIn("_apply_artwork(holder, width, height, cover_path)", block)
        self.assertNotIn("cover_cache)", block)

    def test_home_artwork_resolution_token_is_identity_scoped(self):
        source = self.read("views/home_view.py")
        self.assertIn("artwork_resolution_token = (", source)
        self.assertIn("entity,", source)
        self.assertIn("normalized_id,", source)
        self.assertIn("kind,", source)
        self.assertIn("artwork_token_counter[0]", source)
        batch = source[source.index("async def _flush_artwork_batch"):source.index("def schedule_artwork_batch_prefetch")]
        self.assertIn("snapshot_tokens.get(key) != artwork_request_tokens.get(key)", batch)
        self.assertIn("STALE_ARTWORK_IGNORED", batch)

    def test_generated_thumbnail_never_promotes_to_anime_poster(self):
        source = self.read("core/artwork.py")
        start = source.index("def register_generated_thumbnail")
        end = source.index("def set_manual", start)
        block = source[start:end]
        self.assertIn('entity_type="episode"', block)
        self.assertIn('artwork_type="episode_thumbnail"', block)
        self.assertIn('source="generated"', block)
        self.assertNotIn("artwork_type='poster'", block)
        self.assertNotIn("entity_type='anime'", block)
        self.assertNotIn("UPDATE anime SET cover_cache", block)

    def test_home_refresh_has_one_intent_entry_point_and_explicit_phases(self):
        source = self.read("main.py")
        self.assertIn("async def request_home_refresh", source)
        self.assertIn("async def refresh_home_library", source)
        self.assertIn("on_refresh_library=request_home_refresh", source)
        self.assertIn("on_refresh_library=request_home_refresh", source)
        for phase in (
            "IDLE",
            "REQUESTED",
            "RUNNING",
            "CATALOG_UPDATING",
            "UI_COMMIT",
            "SUCCESS",
            "ERROR",
            "CANCELLED",
        ):
            self.assertIn(f'"{phase}"', source)
        self.assertIn("def _set_home_refresh_phase", source)
        self.assertIn("HOME_REFRESH_PHASE_CHANGED", source)

    def test_home_refresh_catalog_commit_is_bound_to_the_scan_request(self):
        source = self.read("main.py")
        self.assertIn("refresh_request_id=refresh_request_id", source)
        start = source.index("def on_catalog_changed")
        end = source.index("async def refresh_current_metadata", start) if "async def refresh_current_metadata" in source[start:] else len(source)
        block = source[start:end]
        self.assertIn("refresh_request_id is not None", block)
        self.assertIn("current_refresh_request_id == refresh_request_id", block)
        self.assertIn('_set_home_refresh_phase(', block)
        self.assertIn('"CATALOG_UPDATING"', block)

    def test_player_exit_classification_has_forensic_categories(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
        for label in (
            "USER_BACK",
            "USER_BUTTON",
            "ANDROID_BACK",
            "ERROR_PANEL_BACK",
            "VALID_PLAYER_TRANSITION",
            "STALE_HANDOFF",
            "INVALID_HANDOFF",
            "PLAYER_ERROR",
            "ACTIVITY_LIFECYCLE",
            "SYSTEM_TASK",
            "PROCESS_DEATH",
            "UNKNOWN",
        ):
            self.assertIn(f'"{label}"', source)
        self.assertIn("PLAYER_FINISH_REQUEST", source)
        self.assertIn("PLAYER_EXIT_CLASSIFICATION=STALE_HANDOFF", source)

    def test_source_files_parse(self):
        ast.parse(self.read("main.py"), filename="main.py")
        ast.parse(self.read("views/home_view.py"), filename="views/home_view.py")


if __name__ == "__main__":
    unittest.main()
