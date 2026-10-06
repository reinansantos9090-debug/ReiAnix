import concurrent.futures
import os
import tempfile
import threading
import io
import time
import unittest
from urllib.error import HTTPError
from pathlib import Path

from core.artwork import (
    ArtworkEngine,
    STATUS_FAILED,
    STATUS_READY,
    STATUS_RETRY_WAIT,
)
from core.library_service import LibraryService
from PIL import Image
from core.library_store import LibraryStore


def _valid_jpeg():
    buffer = io.BytesIO()
    Image.new("RGB", (2, 2), (255, 255, 255)).save(buffer, format="JPEG")
    return buffer.getvalue()


JPEG = _valid_jpeg()


class ArtworkEngineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.engine = ArtworkEngine(self.store)

    def tearDown(self):
        self.engine.shutdown()
        self.tmp.cleanup()

    def _media(self, title="Ação 進撃", media_kind="series"):
        return self.store.upsert_anime("local", {"title": title, "genres": "[]", "media_kind": media_kind})

    def _episode(self, anime, path, name, season=1, number=1, episode_type="regular", media_identity=None):
        with self.store._conn() as con:
            cur = con.execute(
                """INSERT INTO episodes(anime_id,path,file_name,season,number,mime_type,file_size,modified_at,
                                         source_folder,missing,media_identity,absolute_number,episode_type,episode_title)
                   VALUES(?,?,?,?,?,?,?,?,?,0,?,?,?,?)""",
                (anime, path, name, season, number, None, None, None, str(Path(path).parent),
                 media_identity, None, episode_type, None),
            )
            return cur.lastrowid

    def _remote(self, anime, url="https://example/cover.jpg", artwork_type="poster"):
        self.engine.sync_anime_metadata(
            anime,
            {"anilist_id": 16498, "cover_url": url, "banner_url": "https://example/banner.jpg"}
            if artwork_type == "poster"
            else {"cover_url": url},
        )

    def test_schema_and_persistence(self):
        self.assertEqual(self.store.SCHEMA_VERSION, 29)
        anime = self._media()
        path = Path(self.tmp.name) / "poster.jpg"
        path.write_bytes(JPEG)
        self.assertTrue(self.engine.add_local("anime", anime, "poster", path))
        reopened_store = LibraryStore(self.tmp.name)
        reopened = ArtworkEngine(reopened_store)
        try:
            row = reopened.resolve("anime", anime, "poster", allow_network=False)
            self.assertEqual(row["local_path"], str(path))
        finally:
            reopened.shutdown()

    def test_types_and_fallbacks(self):
        anime = self._media()
        for kind in ("poster", "backdrop", "thumbnail", "season_poster", "episode_thumbnail"):
            self.assertIsNone(self.engine.resolve("anime", anime, kind, allow_network=False))
        path = Path(self.tmp.name) / "cover.webp"
        path.write_bytes(JPEG)
        self.engine.add_local("anime", anime, "poster", path)
        thumbnail = self.engine.resolve("anime", anime, "thumbnail", allow_network=False)
        self.assertTrue(thumbnail["fallback"])

    def test_manual_artwork_has_priority(self):
        anime = self._media()
        manual = Path(self.tmp.name) / "manual.jpg"
        remote = Path(self.tmp.name) / "remote.jpg"
        manual.write_bytes(JPEG)
        remote.write_bytes(JPEG)
        self.engine.set_manual("anime", anime, "poster", path=manual)
        self.engine.add_local("anime", anime, "poster", remote)
        self.engine.sync_anime_metadata(anime, {"cover_cache": str(remote), "cover_url": "https://example/cover.jpg"})
        row = self.engine.resolve("anime", anime, "poster", allow_network=False)
        self.assertEqual(row["source"], "manual")
        self.assertEqual(row["local_path"], str(manual))

    def test_local_conservative_discovery(self):
        anime = self._media()
        folder = Path(self.tmp.name) / "Show"
        folder.mkdir()
        video = folder / "Show S01E01.mkv"
        video.write_bytes(b"video")
        (folder / "poster.jpg").write_bytes(JPEG)
        (folder / "random-photo.jpg").write_bytes(JPEG)
        ep = self._episode(anime, str(video), video.name)
        self.engine.reindex_episode(ep)
        rows = self.engine.list_for("anime", anime, "poster")
        self.assertEqual(len(rows), 1)
        self.assertTrue(rows[0]["local_path"].endswith("poster.jpg"))

    def test_unicode_filename_is_discoverable(self):
        anime = self._media("進撃の巨人")
        folder = Path(self.tmp.name) / "進撃"
        folder.mkdir()
        video = folder / "進撃の巨人 S01E01.mkv"
        video.write_bytes(b"video")
        poster = folder / "Poster.jpg"
        poster.write_bytes(JPEG)
        ep = self._episode(anime, str(video), video.name)
        self.engine.reindex_episode(ep)
        self.assertTrue(self.engine.resolve("anime", anime, "poster", allow_network=False)["local_path"].endswith("Poster.jpg"))

    def test_episode_and_season_artwork(self):
        anime = self._media()
        folder = Path(self.tmp.name) / "Show"
        folder.mkdir()
        video = folder / "Show S01E01.mkv"
        video.write_bytes(b"video")
        (folder / "thumbnail.jpg").write_bytes(JPEG)
        (folder / "season.jpg").write_bytes(JPEG)
        ep = self._episode(anime, str(video), video.name)
        self.engine.reindex_episode(ep)
        self.assertIsNotNone(self.engine.resolve("episode", ep, "episode_thumbnail", allow_network=False))
        self.assertIsNotNone(self.engine.resolve("season", f"{anime}:season:1", "season_poster", allow_network=False))

    def test_generated_native_thumbnail_does_not_persist_as_anime_poster(self):
        anime = self._media("Thumb")
        episode_path = str(Path(self.tmp.name) / "Thumb S01E01.mkv")
        ep = self._episode(anime, episode_path, "Thumb S01E01.mkv")
        thumb = Path(self.tmp.name) / "native.jpg"
        thumb.write_bytes(JPEG)
        self.assertTrue(self.engine.register_generated_thumbnail(episode_path, thumb, size=123, modified_at=456))
        episode_art = self.engine.resolve("episode", ep, "episode_thumbnail", allow_network=False)
        anime_art = self.engine.resolve("anime", anime, "poster", allow_network=False)
        self.assertEqual(episode_art["source"], "generated")
        self.assertIsNone(anime_art)
        self.assertEqual([], self.engine.list_for("anime", anime, "poster"))

    def test_legacy_generated_poster_is_never_used_as_fallback(self):
        anime = self._media("Legacy thumbnail poster")
        legacy = Path(self.tmp.name) / "legacy-thumbnail.jpg"
        legacy.write_bytes(JPEG)
        with self.store._conn() as con:
            con.execute(
                "INSERT INTO artwork(entity_type,entity_id,artwork_type,source,source_ref,local_path,status,discovered_at,updated_at,priority) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (
                    "anime", str(anime), "poster", "generated",
                    "native:legacy-episode|123|456", str(legacy), "ready",
                    time.time(), time.time(), 50,
                ),
            )

        self.assertIsNone(self.engine.resolve("anime", anime, "poster", allow_network=False))
        self.assertIsNone(self.engine._first_usable("anime", anime, "poster", allow_network=False))

    def test_cache_and_anilist_external_reference(self):
        anime = self._media()
        cached = Path(self.tmp.name) / "cached.jpg"
        cached.write_bytes(JPEG)
        self.engine.sync_anime_metadata(
            anime,
            {"cover_cache": str(cached), "cover_url": "https://example/cover.jpg", "banner_url": "https://example/banner.jpg"},
        )
        row = self.engine.resolve("anime", anime, "poster", allow_network=False)
        self.assertEqual(row["source"], "cache")
        backdrop = self.engine.resolve("anime", anime, "backdrop", allow_network=False)
        self.assertTrue(backdrop["fallback"])

    def test_metadata_sync_preserves_valid_downloaded_cache(self):
        anime = self._media("Metadata refresh")
        cached = self.engine.cache_dir / "stable-cover.jpg"
        cached.write_bytes(JPEG)
        self.engine.sync_anime_metadata(
            anime,
            {"anilist_id": 16498, "cover_url": "https://example/cover.jpg", "cover_cache": str(cached)},
        )
        first = self.engine.resolve("anime", anime, "poster", allow_network=False)
        self.assertEqual(first["local_path"], str(cached))
        self.engine.sync_anime_metadata(
            anime,
            {"anilist_id": 16498, "cover_url": "https://example/cover.jpg"},
        )
        second = self.engine.resolve("anime", anime, "poster", allow_network=False)
        self.assertEqual(second["local_path"], str(cached))
        self.assertEqual(second["status"], STATUS_READY)
        self.assertEqual(second["source"], "cache")
        self.assertEqual(Path(second["local_path"]).resolve(), cached.resolve())

    def test_success_publishes_incremental_artwork_event(self):
        anime = self._media("Artwork events")
        self._remote(anime)
        events = []
        self.engine.set_change_listener(lambda name, payload: events.append((name, payload)))
        self.engine._downloader = lambda _url: (JPEG, "image/jpeg", 200)
        result = self.engine.request("anime", anime, "poster", blocking=True)
        names = [name for name, _ in events]
        self.assertEqual(result["status"], STATUS_READY)
        self.assertIn("ARTWORK_REQUESTED", names)
        self.assertIn("ARTWORK_DOWNLOAD_STARTED", names)
        self.assertIn("ARTWORK_DOWNLOAD_SUCCEEDED", names)
        self.assertIn("ARTWORK_PUBLISHED", names)
        published = next(payload for name, payload in events if name == "ARTWORK_PUBLISHED")
        self.assertEqual(int(published["entity_id"]), anime)
        self.assertEqual(published["artwork_type"], "poster")
        self.assertTrue(Path(published["local_path"]).is_file())

    def test_download_success_is_atomic_and_persistent(self):
        anime = self._media()
        self._remote(anime)
        calls = []
        def downloader(url):
            calls.append(url)
            return JPEG, "image/jpeg", 200
        self.engine._downloader = downloader
        result = self.engine.request("anime", anime, "poster", priority=100, blocking=True)
        self.assertEqual(len(calls), 1)
        self.assertEqual(result["status"], STATUS_READY)
        self.assertTrue(Path(result["local_path"]).is_file())
        self.assertEqual(Path(result["local_path"]).read_bytes(), JPEG)
        self.assertFalse(any(p.name.endswith(".tmp") for p in self.engine.cache_dir.iterdir()))
        self.assertEqual(self.store.anime_metadata("local")["cover_cache"], result["local_path"])

    def test_corrupt_local_artwork_is_not_accepted_as_ready(self):
        anime = self._media("Corrupt local")
        bad = Path(self.tmp.name) / "corrupt.jpg"
        bad.write_bytes(b"JFIF but not a decodable image")
        self.assertFalse(self.engine.add_local("anime", anime, "poster", bad))
        self.assertIsNone(self.engine.resolve("anime", anime, "poster", allow_network=False))

    def test_http_200_non_image_payload_does_not_enter_retry_wait(self):
        anime = self._media("HTML response")
        self._remote(anime, "https://example/html.jpg")
        calls = []
        self.engine._downloader = lambda url: (calls.append(url) or (b"<html>server page</html>", "text/html", 200))
        self.assertIsNone(self.engine.request("anime", anime, "poster", blocking=True))
        row = self.engine.list_for("anime", anime, "poster")[0]
        self.assertEqual(row["status"], STATUS_FAILED)
        self.assertIsNone(row["next_retry_at"])
        self.assertEqual(len(calls), 1)

    def test_invalid_download_payload_does_not_enter_retry_wait(self):
        anime = self._media("Invalid remote")
        self._remote(anime, "https://example/invalid.jpg")
        calls = []
        self.engine._downloader = lambda url: (calls.append(url) or (b"not-an-image", "image/jpeg", 200))
        self.assertIsNone(self.engine.request("anime", anime, "poster", blocking=True))
        row = self.engine.list_for("anime", anime, "poster")[0]
        self.assertEqual(row["status"], STATUS_FAILED)
        self.assertIsNone(row["next_retry_at"])
        self.assertEqual(len(calls), 1)

    def test_cache_hit_does_not_download(self):
        anime = self._media()
        self._remote(anime)
        path = Path(self.tmp.name) / "existing.jpg"
        path.write_bytes(JPEG)
        self.engine.sync_anime_metadata(anime, {"anilist_id": 16498, "cover_url": "https://example/cover.jpg", "cover_cache": str(path)})
        self.engine._downloader = lambda _url: (_ for _ in ()).throw(AssertionError("network"))
        result = self.engine.request("anime", anime, "poster", blocking=True)
        self.assertEqual(result["local_path"], str(path))

    def test_duplicate_requests_share_one_physical_download(self):
        anime = self._media()
        self._remote(anime)
        calls = []
        lock = threading.Lock()
        def downloader(url):
            with lock:
                calls.append(url)
            time.sleep(0.08)
            return JPEG, "image/jpeg", 200
        self.engine._downloader = downloader
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [
                pool.submit(self.engine.request, "anime", anime, "poster", priority=100, blocking=True),
                pool.submit(self.engine.request, "anime", anime, "poster", priority=100, blocking=True),
            ]
            results = [future.result(timeout=3) for future in futures]
        self.assertEqual(len(calls), 1)
        self.assertEqual(results[0]["local_path"], results[1]["local_path"])

    def test_priority_is_recorded_and_prefetch_is_bounded(self):
        anime = self._media()
        self._remote(anime)
        futures = self.engine.prefetch(
            [{"entity_type": "anime", "entity_id": anime, "artwork_type": "poster", "priority": 500}] * 200
        )
        self.assertLessEqual(len(futures), 100)

    def test_url_change_keeps_old_artwork_until_new_download(self):
        anime = self._media()
        old = Path(self.tmp.name) / "old.jpg"
        old.write_bytes(JPEG)
        self.engine.sync_anime_metadata(
            anime,
            {"anilist_id": 16498, "cover_url": "https://example/old.jpg", "cover_cache": str(old)},
        )
        self.engine.sync_anime_metadata(
            anime,
            {"anilist_id": 16498, "cover_url": "https://example/new.jpg"},
        )
        before = self.engine.resolve("anime", anime, "poster", allow_network=False)
        self.assertEqual(before["local_path"], str(old))
        self.engine._downloader = lambda url: (JPEG + b"new", "image/jpeg", 200)
        self.engine.request("anime", anime, "poster", blocking=True)
        after = self.engine.resolve("anime", anime, "poster", allow_network=False)
        self.assertNotEqual(after["local_path"], str(old))
        self.assertTrue(Path(after["local_path"]).is_file())

    def test_corrupt_cache_is_repaired(self):
        anime = self._media()
        bad = Path(self.tmp.name) / "bad.jpg"
        bad.write_bytes(b"not an image")
        self.engine.sync_anime_metadata(anime, {"anilist_id": 16498, "cover_url": "https://example/cover.jpg", "cover_cache": str(bad)})
        self.engine._downloader = lambda _url: (JPEG, "image/jpeg", 200)
        result = self.engine.request("anime", anime, "poster", blocking=True, force=True)
        self.assertEqual(result["status"], STATUS_READY)
        self.assertEqual(Path(result["local_path"]).read_bytes(), JPEG)

    def test_retry_backoff_is_bounded(self):
        anime = self._media()
        self._remote(anime, "https://example/retry.jpg")
        calls = []
        def downloader(url):
            calls.append(url)
            raise TimeoutError("timeout")
        self.engine._downloader = downloader
        first = self.engine.request("anime", anime, "poster", blocking=True)
        self.assertIsNone(first)
        row = self.engine.list_for("anime", anime, "poster")[0]
        self.assertEqual(row["status"], STATUS_RETRY_WAIT)
        self.assertGreater(row["next_retry_at"], time.time())
        second = self.engine.request("anime", anime, "poster", blocking=True)
        self.assertEqual(second["status"], STATUS_RETRY_WAIT)
        self.assertEqual(len(calls), 1)

    def test_404_enters_failed_without_retry_loop(self):
        anime = self._media()
        self._remote(anime, "https://example/missing.jpg")
        def downloader(_url):
            raise HTTPError(_url, 404, "missing", {}, None)
        self.engine._downloader = downloader
        self.assertIsNone(self.engine.request("anime", anime, "poster", blocking=True))
        self.assertEqual(self.engine.get_status("anime", anime, "poster"), STATUS_FAILED)

    def test_manual_retry_ignores_cooldown(self):
        anime = self._media()
        self._remote(anime, "https://example/retry-manual.jpg")
        calls = []
        self.engine._downloader = lambda url: (calls.append(url) or (JPEG, "image/jpeg", 200))
        with self.store._conn() as con:
            con.execute("UPDATE artwork SET status=?,next_retry_at=? WHERE entity_id=?", (STATUS_RETRY_WAIT, time.time() + 3600, str(anime)))
        self.engine.retry("anime", anime, "poster", priority=500)
        deadline = time.time() + 1.0
        while not calls and time.time() < deadline:
            time.sleep(0.02)
        self.assertEqual(len(calls), 1)

    def test_clear_and_cleanup_never_touch_videos(self):
        anime = self._media()
        video = Path(self.tmp.name) / "video.mkv"
        video.write_bytes(b"video")
        cached = self.engine.cache_dir / "managed.jpg"
        cached.write_bytes(JPEG)
        self.engine.sync_anime_metadata(anime, {"cover_cache": str(cached), "cover_url": "https://example/cover.jpg"})
        removed = self.engine.clear()
        self.assertGreaterEqual(removed, 0)
        self.assertTrue(video.exists())
        self.assertTrue(Path(self.store.db_path).exists())

    def test_offline_missing_artwork_never_raises(self):
        anime = self._media()
        self.assertIsNone(self.engine.resolve("anime", anime, "backdrop", allow_network=False))
        self.assertEqual(self.store.catalog(), [])

    def test_movie_uses_stable_entity(self):
        movie = self._media("Filme", "movie")
        path = Path(self.tmp.name) / "movie.jpg"
        path.write_bytes(JPEG)
        self.engine.add_local("movie", movie, "poster", path)
        self.assertEqual(self.engine.resolve("movie", movie, "poster", allow_network=False)["local_path"], str(path))

    def test_generated_thumbnail_reuses_observation_identity_across_uri_change(self):
        anime = self._media("Stable")
        identity = "stable-media-id"
        uri_a = "content://provider/item/one"
        uri_b = "content://provider/item/two"
        ep = self._episode(anime, uri_a, "Stable S01E01.mkv", media_identity=identity)
        self.store.record_observation(
            ep,
            source_kind="mediastore",
            scope_kind="volume",
            scope_ref="external_primary",
            uri=uri_a,
            volume_id="external_primary",
        )
        thumb_a = Path(self.tmp.name) / "native-stable-a.jpg"
        thumb_a.write_bytes(JPEG)
        metadata = {
            "durationMs": 91234,
            "width": 1920,
            "height": 1080,
            "rotation": 90,
            "mimeType": "video/x-matroska",
        }
        self.assertTrue(self.engine.register_generated_thumbnail(
            uri_a, thumb_a, size=123, modified_at=456,
            media_identity=identity, metadata=metadata,
        ))
        self.store.upsert_episode(
            anime, uri_b, "Stable S01E01.mkv", 1, 1,
            source_folder="mediastore",
            media_identity=identity,
        )
        self.store.record_observation(
            ep,
            source_kind="mediastore",
            scope_kind="volume",
            scope_ref="external_primary",
            uri=uri_b,
            volume_id="external_primary",
        )
        thumb_b = Path(self.tmp.name) / "native-stable-b.jpg"
        thumb_b.write_bytes(JPEG + b"b")
        self.assertTrue(self.engine.register_generated_thumbnail(
            uri_b, thumb_b, size=123, modified_at=456,
            media_identity=identity, metadata=metadata,
        ))
        row = self.engine.resolve("episode", ep, "episode_thumbnail", allow_network=False)
        self.assertEqual(row["local_path"], str(thumb_b))
        self.assertEqual(row["width"], 1920)
        self.assertEqual(row["height"], 1080)
        with self.store._conn() as con:
            episode = con.execute(
                "SELECT media_identity, path, duration FROM episodes WHERE id=?",
                (ep,),
            ).fetchone()
            observations = con.execute(
                "SELECT uri FROM episode_observations WHERE episode_id=? ORDER BY uri",
                (ep,),
            ).fetchall()
        self.assertEqual(episode["media_identity"], identity)
        self.assertEqual(episode["path"], uri_b)
        self.assertAlmostEqual(episode["duration"], 91.234, places=3)
        self.assertEqual([row["uri"] for row in observations], [uri_a, uri_b])
        self.assertFalse(self.engine.register_generated_thumbnail(
            "content://provider/item/unrelated",
            thumb_b,
            size=123,
            modified_at=456,
            media_identity="different-media-id",
            metadata=metadata,
        ))

    def test_local_artwork_batch_prefers_thumbnail_and_falls_back_to_poster(self):
        anime = self._media("Batch")
        first_path = str(Path(self.tmp.name) / "Batch S01E01.mkv")
        second_path = str(Path(self.tmp.name) / "Batch S01E02.mkv")
        first = self._episode(anime, first_path, "Batch S01E01.mkv")
        second = self._episode(anime, second_path, "Batch S01E02.mkv")

        first_thumb = Path(self.tmp.name) / "first-thumb.jpg"
        second_poster = Path(self.tmp.name) / "second-poster.jpg"
        first_thumb.write_bytes(JPEG)
        second_poster.write_bytes(JPEG)

        self.assertTrue(self.engine.register_generated_thumbnail(first_path, first_thumb, size=100, modified_at=1))
        with self.store._conn() as con:
            con.execute(
                "INSERT INTO artwork(entity_type,entity_id,artwork_type,source,source_ref,local_path,status,discovered_at,updated_at,priority) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                ("episode", str(second), "poster", "manual", "batch-test", str(second_poster), "ready", time.time(), time.time(), 10),
            )

        result = self.engine.resolve_local_batch(
            "episode",
            [first, second],
            ("episode_thumbnail", "poster"),
        )

        self.assertEqual(result[str(first)]["artwork_type"], "episode_thumbnail")
        self.assertEqual(result[str(first)]["local_path"], str(first_thumb))
        self.assertEqual(result[str(second)]["artwork_type"], "poster")
        self.assertEqual(result[str(second)]["local_path"], str(second_poster))

    def test_metadata_integration_uses_existing_engine(self):
        service = LibraryService(self.store)
        anime = self._media()
        metadata = {"cover_url": "https://example/a.jpg", "cover_cache": "", "banner_url": "", "anilist_id": 1}
        service.artwork.sync_anime_metadata(anime, metadata)
        self.assertIsNone(self.store.association("local"))
        self.assertEqual(
            self.engine.resolve("anime", anime, "poster", allow_network=True)["external_url"],
            "https://example/a.jpg",
        )


    def test_large_downloaded_artwork_is_resized_before_cache(self):
        anime = self._media("Large poster")
        self._remote(anime)
        raw = io.BytesIO()
        Image.new("RGB", (3000, 4500), (24, 48, 72)).save(raw, format="JPEG", quality=92)
        payload = raw.getvalue()
        self.engine._downloader = lambda _url: (payload, "image/jpeg", 200)

        result = self.engine.request("anime", anime, "poster", blocking=True)
        self.assertEqual(result["status"], STATUS_READY)
        with Image.open(result["local_path"]) as image:
            cached_size = (image.width, image.height)
        self.assertLessEqual(cached_size[0], 960)
        self.assertLessEqual(cached_size[1], 1440)
        self.assertEqual(result["width"], cached_size[0])
        self.assertEqual(result["height"], cached_size[1])

    def test_startup_enforces_existing_cache_limit(self):
        anime = self._media("Cache limit")
        for index in range(2):
            path = self.engine.cache_dir / f"oversized-{index}.jpg"
            path.write_bytes(JPEG + (b"x" * 900))
            self.engine._upsert(
                entity_type="anime",
                entity_id=anime,
                artwork_type="poster",
                source="cache",
                source_ref=f"startup-{index}",
                local_path=str(path),
                status=STATUS_READY,
                byte_size=path.stat().st_size,
            )

        self.engine.shutdown()
        reopened_store = LibraryStore(self.tmp.name)
        self.engine = ArtworkEngine(reopened_store, cache_limit_bytes=1024)
        stats = self.engine.cache_stats()
        self.assertLessEqual(stats["bytes"], 1024)


    def test_generation_invalidation_waits_for_atomic_cache_commit(self):
        anime = self._media("Generation commit")
        self._remote(anime, "https://example/generation.jpg")
        raw = io.BytesIO()
        Image.new("RGB", (1200, 1800), (90, 40, 20)).save(raw, format="JPEG")
        payload = raw.getvalue()
        self.engine._downloader = lambda _url: (payload, "image/jpeg", 200)

        original_replace = os.replace
        observed = []

        def replace_and_invalidate(source, target):
            original_replace(source, target)
            observed.append(True)
            self.engine.invalidate_generation("during_commit")

        os.replace = replace_and_invalidate
        try:
            generation = self.engine._generation
            result = self.engine._download_row(
                self.engine.list_for("anime", anime, "poster")[0],
                generation=generation,
            )
        finally:
            os.replace = original_replace

        self.assertTrue(observed)
        self.assertIsNotNone(result)
        self.assertEqual(result["status"], STATUS_READY)
        self.assertTrue(Path(result["local_path"]).is_file())

    def test_stale_generation_does_not_delete_existing_cache_entry(self):
        anime = self._media("Stale refresh")
        self._remote(anime)
        existing = self.engine.cache_dir / "existing-cache.jpg"
        existing.write_bytes(JPEG)
        row_id = self.engine._upsert(
            entity_type="anime",
            entity_id=anime,
            artwork_type="poster",
            source="cache",
            source_ref="stale-existing",
            external_url="https://example.test/poster.jpg",
            local_path=str(existing),
            status=STATUS_READY,
            byte_size=existing.stat().st_size,
            width=64,
            height=64,
        )
        row = self.engine.list_for("anime", anime, "poster")[0]
        raw = io.BytesIO()
        Image.new("RGB", (1200, 1800), (80, 40, 20)).save(raw, format="JPEG")
        self.engine._downloader = lambda _url: (raw.getvalue(), "image/jpeg", 200)

        generation = self.engine._generation
        self.engine.invalidate_generation("test-stale")
        result = self.engine._download_row(row, generation=generation)

        self.assertIsNone(result)
        self.assertTrue(existing.is_file())
        self.assertEqual(existing.read_bytes(), JPEG)
        with self.engine.store._conn() as con:
            stored = con.execute(
                "SELECT local_path,status FROM artwork WHERE id=?",
                (row_id,),
            ).fetchone()
        self.assertEqual(stored["local_path"], str(existing))
        self.assertEqual(stored["status"], STATUS_READY)


if __name__ == "__main__":
    unittest.main()
