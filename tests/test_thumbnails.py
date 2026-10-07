"""earlier validation stage 36 regression coverage for deterministic episode thumbnail generation."""
from pathlib import Path
import tempfile
import unittest

from PIL import Image

from core.artwork import ArtworkEngine
from core.library_service import LibraryService
from core.library_store import LibraryStore


ROOT = Path(__file__).resolve().parents[1]


class ThumbnailTests(unittest.TestCase):
    def _image(self, path: Path, size=(32, 32)):
        Image.new("RGB", size, (32, 64, 96)).save(path, format="JPEG", quality=90)

    def test_poster_fallback_does_not_satisfy_thumbnail_reconciliation(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id = store.upsert_anime(
                "stage36",
                {"title": "earlier validation stage 36", "genres": "[]", "media_kind": "series"},
            )
            episode_id = store.upsert_episode(
                anime_id,
                "file:///tmp/fixture_36-e01.mkv",
                "stage36 E01.mkv",
                1,
                1,
                media_identity="fixture_36-e01",
            )
            poster = Path(directory) / "poster.jpg"
            self._image(poster)
            service.artwork.add_local("episode", episode_id, "poster", poster)

            candidates = service.thumbnail_candidates()

            self.assertEqual([episode_id], [item["id"] for item in candidates])

    def test_generated_thumbnail_persists_real_duration_in_seconds(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id = store.upsert_anime(
                "fixture_57-duration",
                {"title": "Duration Fixture", "genres": "[]", "media_kind": "series"},
            )
            episode_id = store.upsert_episode(
                anime_id,
                "file:///tmp/fixture_57-duration-e01.mkv",
                "E01.mkv",
                1,
                1,
                media_identity="fixture_57-duration-e01",
            )
            thumb = Path(directory) / "duration.jpg"
            self._image(thumb)

            self.assertTrue(
                service.register_generated_thumbnail(
                    "file:///tmp/fixture_57-duration-e01.mkv",
                    thumb,
                    size=123,
                    modified_at=456,
                    media_identity="fixture_57-duration-e01",
                    metadata={"mimeType": "video/mp4", "durationMs": 123456},
                )
            )

            with store._conn() as con:
                row = con.execute(
                    "SELECT duration FROM episodes WHERE id=?",
                    (episode_id,),
                ).fetchone()
            self.assertAlmostEqual(123.456, float(row["duration"]), places=6)

    def test_valid_episode_thumbnail_without_duration_stays_reconcilable_for_repair(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id = store.upsert_anime(
                "fixture_57-repair",
                {"title": "Repair Fixture", "genres": "[]", "media_kind": "series"},
            )
            episode_id = store.upsert_episode(
                anime_id,
                "file:///tmp/fixture_57-repair-e01.mkv",
                "E01.mkv",
                1,
                1,
                media_identity="fixture_57-repair-e01",
            )
            thumb = Path(directory) / "repair.jpg"
            self._image(thumb)

            self.assertTrue(
                service.register_generated_thumbnail(
                    "file:///tmp/fixture_57-repair-e01.mkv",
                    thumb,
                    size=100,
                    modified_at=200,
                    media_identity="fixture_57-repair-e01",
                    metadata={"mimeType": "video/mp4"},
                )
            )

            candidates = service.thumbnail_candidates()
            self.assertEqual([episode_id], [item["id"] for item in candidates])
            self.assertTrue(candidates[0]["thumbnail_ready"])
            self.assertEqual(0.0, float(candidates[0]["duration"] or 0.0))

    def test_valid_episode_thumbnail_removes_episode_from_reconciliation(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id = store.upsert_anime(
                "fixture_36-ready",
                {"title": "earlier validation stage 36 Ready", "genres": "[]", "media_kind": "series"},
            )
            episode_id = store.upsert_episode(
                anime_id,
                "file:///tmp/fixture_36-ready-e01.mkv",
                "stage36 Ready E01.mkv",
                1,
                1,
                media_identity="fixture_36-ready",
            )
            thumb = Path(directory) / "thumb.jpg"
            self._image(thumb)
            self.assertTrue(
                service.register_generated_thumbnail(
                    "file:///tmp/fixture_36-ready-e01.mkv",
                    thumb,
                    size=100,
                    modified_at=200,
                    media_identity="fixture_36-ready",
                    metadata={"mimeType": "video/mp4", "durationMs": 123000},
                )
            )
            self.assertEqual([], service.thumbnail_candidates())
            resolved = service.resolve_artwork(
                "episode", episode_id, "episode_thumbnail", allow_network=False
            )
            self.assertEqual(str(thumb), resolved["local_path"])
            self.assertNotIn("fallback", resolved)
            with store._conn() as con:
                poster_rows = con.execute(
                    "SELECT * FROM artwork WHERE entity_type='anime' AND entity_id=? AND artwork_type='poster'",
                    (str(anime_id),),
                ).fetchall()
                cover_cache = con.execute(
                    "SELECT cover_cache FROM anime WHERE id=?",
                    (anime_id,),
                ).fetchone()["cover_cache"]
            self.assertEqual([], poster_rows)
            self.assertFalse(cover_cache)

    def test_stable_media_identity_registers_after_uri_representation_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            engine = ArtworkEngine(store, max_workers=1)
            try:
                anime_id = store.upsert_anime(
                    "fixture_36-identity",
                    {"title": "earlier validation stage 36 Identity", "genres": "[]", "media_kind": "series"},
                )
                episode_id = store.upsert_episode(
                    anime_id,
                    "file:///storage/emulated/0/Anime/E01.mkv",
                    "E01.mkv",
                    1,
                    1,
                    media_identity="shared:primary:anime/e01.mkv",
                )
                store.record_observation(
                    episode_id,
                    source_kind="broad_storage",
                    scope_kind="volume",
                    scope_ref="external_primary",
                    uri="file:///storage/emulated/0/Anime/E01.mkv",
                    volume_id="external_primary",
                )
                thumb = Path(directory) / "identity.jpg"
                self._image(thumb)
                self.assertTrue(
                    engine.register_generated_thumbnail(
                        "content://media/external/video/media/42",
                        thumb,
                        size=100,
                        modified_at=200,
                        media_identity="shared:primary:anime/e01.mkv",
                        metadata={},
                    )
                )
                resolved = engine.resolve(
                    "episode", episode_id, "episode_thumbnail", allow_network=False
                )
                self.assertEqual(str(thumb), resolved["local_path"])
            finally:
                engine.shutdown()

    def test_corrupt_generated_thumbnail_is_reconciled(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id = store.upsert_anime(
                "fixture_36-corrupt",
                {"title": "earlier validation stage 36 Corrupt", "genres": "[]", "media_kind": "series"},
            )
            episode_id = store.upsert_episode(
                anime_id,
                "file:///tmp/fixture_36-corrupt-e01.mkv",
                "stage36 Corrupt E01.mkv",
                1,
                1,
                media_identity="fixture_36-corrupt",
            )
            bad = Path(directory) / "bad.jpg"
            bad.write_bytes(b"not-a-jpeg")
            with store._conn() as con:
                store._conn()
                con.execute(
                    """INSERT INTO artwork(
                        entity_type,entity_id,artwork_type,source,source_ref,local_path,
                        status,discovered_at,updated_at,artwork_key,variant
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        "episode", str(episode_id), "episode_thumbnail", "generated",
                        "native:fixture_36-corrupt", str(bad), "ready", 1.0, 1.0,
                        "fixture_36-corrupt-key", "small",
                    ),
                )
            candidates = service.thumbnail_candidates()
            self.assertEqual([episode_id], [item["id"] for item in candidates])

    def test_thumbnail_candidate_pagination_covers_all_eligible_episodes(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            anime_id = store.upsert_anime(
                "fixture_36-many",
                {"title": "earlier validation stage 36 Many", "genres": "[]", "media_kind": "series"},
            )
            for number in range(1, 131):
                store.upsert_episode(
                    anime_id,
                    f"file:///tmp/fixture_36-many-e{number:03d}.mkv",
                    f"E{number:03d}.mkv",
                    1,
                    number,
                    media_identity=f"fixture_36-many-{number}",
                )

            first = service.thumbnail_candidates(after_id=0, limit=128)
            second = service.thumbnail_candidates(
                after_id=first[-1]["id"],
                limit=128,
            )

            self.assertEqual(128, len(first))
            self.assertEqual(2, len(second))
            self.assertEqual(
                130,
                len({item["id"] for item in first + second}),
            )

    def test_static_pipeline_contract(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        service_source = (ROOT / "core" / "library_service.py").read_text(encoding="utf-8")
        home = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
        details = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
        extractor = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt"
        ).read_text(encoding="utf-8")
        activity = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("asyncio.PriorityQueue(maxsize=128)", main)
        self.assertIn("thumbnail_reconciliation_pending", main)
        self.assertIn("library.thumbnail_candidates", main)
        self.assertIn("media_identity", main)
        self.assertNotIn("len(thumbnail_requests) >= 32", main)
        self.assertIn("details_state.get('_update_thumbnail')", main)
        self.assertIn('view_state["_update_thumbnail"] = update_thumbnail_in_place', details)
        self.assertIn("media_identity and item_identity == media_identity", home)
        self.assertIn("Semaphore(2)", extractor)
        self.assertIn("isValidCachedThumbnail", extractor)
        self.assertIn("readCachedResult", extractor)
        self.assertIn("writeCacheMetadata", extractor)
        self.assertIn("duration_known", main)
        self.assertIn('float(row.get("duration") or 0) <= 0.0', service_source)
        self.assertIn('"THUMBNAIL_DURATION_REPAIRED"', extractor)
        self.assertIn("f\"thumbnail_ready:{payload.get('episodeId') or media_identity or uri}\"", main)
        self.assertIn('"THUMBNAIL_PUBLISHED"', main)
        self.assertIn('put("mediaIdentity", mediaIdentity)', activity)
        self.assertIn("count < 2", main)
        self.assertIn("retryable = status == 'EXTRACTION_FAILED'", main)


if __name__ == "__main__":
    unittest.main()
