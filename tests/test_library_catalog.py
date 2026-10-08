from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from core.artwork import ArtworkEngine
from core.library_service import LibraryService
from core.library_store import LibraryStore

import io
from PIL import Image


def _valid_jpeg_bytes():
    output = io.BytesIO()
    Image.new("RGB", (8, 8), (24, 48, 72)).save(output, format="JPEG", quality=85)
    return output.getvalue()


VALID_JPEG = _valid_jpeg_bytes()


class LibraryCatalogTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.series = self.store.upsert_anime(
            "series",
            {"title": "Series", "genres": '["Action"]', "media_kind": "series"},
        )

    def tearDown(self):
        self.tmp.cleanup()

    def test_catalog_exposes_authoritative_content_buckets(self):
        self.store.upsert_episode(self.series, "content://p15/e1", "E01.mkv", 1, 1)
        self.store.upsert_episode(self.series, "content://p15/e2", "E02.mkv", 1, 2, episode_type="ova")
        self.store.upsert_episode(self.series, "content://p15/e3", "E03.mkv", 1, 3)
        with self.store._conn() as con:
            con.execute("UPDATE episodes SET missing=1 WHERE path=?", ("content://p15/e3",))
        row = self.store.catalog()[0]
        self.assertEqual(3, row["content_count"])
        self.assertEqual(2, row["regular_count"])
        self.assertEqual(1, row["special_count"])
        self.assertEqual(0, row["movie_file_count"])
        self.assertEqual(2, row["available_count"])
        self.assertEqual(1, row["missing_count"])

    def test_catalog_exposes_generated_episode_thumbnail(self):
        self.store.upsert_episode(self.series, "content://p15/thumb", "E01.mkv", 1, 1)
        episode = self.store.catalog()[0]["seasons"][0]["episodes"][0]
        thumbnail = Path(self.tmp.name) / "episode-thumb.jpg"
        thumbnail.write_bytes(VALID_JPEG)
        engine = ArtworkEngine(self.store)
        try:
            self.assertTrue(
                engine.register_generated_thumbnail(
                    episode["path"],
                    thumbnail,
                    size=123,
                    modified_at=456,
                )
            )
            refreshed = self.store.catalog()[0]["seasons"][0]["episodes"][0]
            self.assertEqual(str(thumbnail), refreshed["artwork_local_path"])
            self.assertIsNone(refreshed["artwork_external_url"])
        finally:
            engine.shutdown()

    def test_movie_catalog_has_file_bucket_and_no_season_bucket(self):
        movie = self.store.upsert_anime(
            "movie",
            {"title": "Movie", "genres": "[]", "media_kind": "movie"},
        )
        self.store.upsert_episode(movie, "content://p15/movie", "Movie.mkv", 0, 1, episode_type="movie")
        row = [item for item in self.store.catalog() if item["id"] == movie][0]
        self.assertEqual(1, row["content_count"])
        self.assertEqual(0, row["regular_count"])
        self.assertEqual(1, row["movie_file_count"])
        self.assertEqual([], row["seasons"])
        self.assertEqual(1, len(row["media_files"]))

    def test_home_can_reuse_a_preloaded_catalog_without_second_projection(self):
        self.store.upsert_episode(self.series, "content://p15/e1", "E01.mkv", 1, 1)
        catalog = self.store.catalog()
        service = LibraryService(self.store)
        with patch.object(self.store, "catalog", side_effect=AssertionError("catalog re-read")):
            home = service.media_center_home(catalog=catalog)
        self.assertEqual(
            {"continue_watching", "favorites", "pinned", "movies"},
            set(home),
        )
        self.assertEqual([], home["movies"])

    def test_organize_summary_uses_catalog_aggregates_and_keeps_state_contract(self):
        self.store.upsert_episode(self.series, "content://p15/e1", "E01.mkv", 1, 1)
        favorite = self.store.upsert_anime(
            "favorite", {"title": "Favorite", "genres": '["Action"]', "media_kind": "series"},
        )
        self.store.upsert_episode(favorite, "content://p15/f1", "F01.mkv", 1, 1)
        self.store.save_progress("content://p15/e1", 50, 100)
        self.store.save_progress("content://p15/f1", 100, 100)
        catalog = self.store.catalog()
        summary = LibraryService.organize_summary(catalog)
        self.assertEqual(
            [
                {"name": "Todos", "count": 2},
                {"name": "Favoritos", "count": 0},
                {"name": "Em andamento", "count": 1},
                {"name": "Concluídos", "count": 1},
            ],
            summary["states"],
        )

    def test_movie_artwork_external_reference_uses_movie_entity(self):
        movie = self.store.upsert_anime(
            "movie-art", {"title": "Movie Art", "genres": "[]", "media_kind": "movie"},
        )
        engine = ArtworkEngine(self.store)
        engine.sync_anime_metadata(movie, {"cover_url": "https://example.invalid/movie.jpg"}, request_download=False)
        rows = engine.list_for("movie", movie, "poster")
        self.assertEqual(1, len(rows))
        self.assertEqual("anilist", rows[0]["source"])

    def test_reindex_entity_discovers_poster_and_season_once_per_entity(self):
        with tempfile.TemporaryDirectory() as media:
            episode_path = f"{media}/S01E01.mkv"
            season_path = f"{media}/Season 1.jpg"
            poster_path = f"{media}/poster.jpg"
            Path(episode_path).write_bytes(b"video")
            Path(season_path).write_bytes(VALID_JPEG)
            Path(poster_path).write_bytes(VALID_JPEG)
            anime = self.store.upsert_anime(
                "art", {"title": "Art", "genres": "[]", "media_kind": "series"},
            )
            self.store.upsert_episode(anime, episode_path, "S01E01.mkv", 1, 1)
            engine = ArtworkEngine(self.store)
            found = engine.reindex_entity(anime)
            self.assertIn(poster_path, found)
            self.assertEqual(
                poster_path,
                engine.resolve("anime", anime, "poster", allow_network=False)["local_path"],
            )


if __name__ == "__main__":
    unittest.main()
