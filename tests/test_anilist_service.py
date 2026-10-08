import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from core.library_service import LibraryService
from core.library_store import LibraryStore


class LibraryAniListCacheTests(unittest.TestCase):
    def test_recent_metadata_cache_avoids_network(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("attack on titan", {
                "title": "Attack on Titan",
                "anilist_id": 16498,
                "cover_url": "https://img.example/cover.jpg",
                "cover_cache": "",
                "genres": "[]",
            })
            with store._conn() as con:
                con.execute("UPDATE anime SET metadata_updated_at=? WHERE id=?", (time.time(), anime))
            service = LibraryService(store)
            with patch.object(service.anilist, "search") as search, patch.object(service.anilist, "by_id") as by_id:
                cached = service._identify("attack on titan", "Attack on Titan", lambda _: None)
            self.assertEqual(cached["anilist_id"], 16498)
            search.assert_not_called()
            by_id.assert_not_called()

    def test_stale_metadata_refreshes_by_existing_anilist_id(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("attack on titan", {
                "title": "Old title",
                "anilist_id": 16498,
                "genres": "[]",
            })
            with store._conn() as con:
                con.execute("UPDATE anime SET metadata_updated_at=? WHERE id=?", (time.time() - 31 * 24 * 60 * 60, anime))
            service = LibraryService(store)
            media = {"id": 16498, "title": {"english": "Attack on Titan", "romaji": "Shingeki no Kyojin"}}
            with patch.object(service.anilist, "by_id", return_value=media) as by_id:
                refreshed = service._identify("attack on titan", "Attack on Titan", lambda _: None)
            self.assertEqual(refreshed["anilist_id"], 16498)
            self.assertEqual(refreshed["title"], "Attack on Titan")
            by_id.assert_called_once_with(16498)

    def test_missing_cover_does_not_disable_metadata_indefinitely(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            missing_cover = Path(directory) / "covers" / "missing.jpg"
            service = LibraryService(store)
            now = 1_000_000.0

            recent = {
                "anilist_id": 20,
                "metadata_updated_at": now - 60,
                "cover_cache": str(missing_cover),
            }
            stale = {
                "anilist_id": 20,
                "metadata_updated_at": now - (LibraryService.COVER_RETRY_SECONDS + 1),
                "cover_cache": str(missing_cover),
            }

            with patch("core.library_service.time.time", return_value=now):
                self.assertFalse(missing_cover.is_file())
                self.assertTrue(service._cached_metadata_is_current(recent, 20))
                self.assertFalse(service._cached_metadata_is_current(stale, 20))

    def test_empty_cover_cache_remains_valid_until_metadata_ttl(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("naruto", {
                "title": "Naruto",
                "anilist_id": 20,
                "cover_url": "https://img.example/naruto.jpg",
                "cover_cache": "",
                "genres": "[]",
            })
            old = time.time() - (LibraryService.COVER_RETRY_SECONDS + 1)
            with store._conn() as con:
                con.execute("UPDATE anime SET metadata_updated_at=? WHERE id=?", (old, anime))
                cached = store.anime_metadata("naruto")
            service = LibraryService(store)
            self.assertTrue(service._cached_metadata_is_current(cached, 20))

    def test_stale_refresh_keeps_metadata_when_cover_download_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("attack on titan", {
                "title": "Old title",
                "anilist_id": 16498,
                "cover_url": "https://img.example/old.jpg",
                "cover_cache": "",
                "genres": "[]",
            })
            with store._conn() as con:
                con.execute(
                    "UPDATE anime SET metadata_updated_at=? WHERE id=?",
                    (time.time() - LibraryService.METADATA_CACHE_SECONDS - 1, anime),
                )
            service = LibraryService(store)
            media = {
                "id": 16498,
                "title": {"english": "Attack on Titan", "romaji": "Shingeki no Kyojin"},
                "coverImage": {"extraLarge": "https://img.example/new.jpg"},
                "genres": ["Action"],
            }
            with patch.object(service.anilist, "by_id", return_value=media), patch.object(
                service.anilist, "cache_cover", return_value=""
            ):
                refreshed = service._identify("attack on titan", "Attack on Titan", lambda _: None)
            self.assertEqual(refreshed["title"], "Attack on Titan")
            self.assertEqual(refreshed["anilist_id"], 16498)
            persisted = store.anime_metadata("attack on titan")
            self.assertEqual(persisted["title"], "Attack on Titan")

    def test_clear_anilist_cache_preserves_library_and_association(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime("naruto", {
                "title": "Naruto",
                "anilist_id": 20,
                "cover_cache": str(Path(directory) / "covers" / "naruto.jpg"),
                "genres": "[]",
            })
            store.set_association("naruto", 20)
            cover = Path(store.cache_dir) / "naruto.jpg"
            cover.write_bytes(b"cached-cover")
            service = LibraryService(store)

            removed = service.clear_anilist_cache()

            self.assertEqual(removed, 1)
            self.assertTrue(cover.parent.is_dir())
            persisted = store.anime_metadata("naruto")
            self.assertEqual(persisted["title"], "Naruto")
            self.assertIsNone(persisted["metadata_updated_at"])
            self.assertEqual(persisted["cover_cache"], "")
            self.assertEqual(store.association("naruto"), 20)
            self.assertEqual(store.library_summary()["animes"], 1)

    def test_association_mismatch_forces_refresh(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("naruto", {
                "title": "Naruto",
                "anilist_id": 20,
                "genres": "[]",
            })
            with store._conn() as con:
                con.execute("UPDATE anime SET metadata_updated_at=? WHERE id=?", (time.time(), anime))
                cached = store.anime_metadata("naruto")
            service = LibraryService(store)
            self.assertFalse(service._cached_metadata_is_current(cached, 21))


if __name__ == "__main__":
    unittest.main()
