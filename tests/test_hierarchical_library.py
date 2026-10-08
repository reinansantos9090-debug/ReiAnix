import sqlite3
import tempfile
import unittest

from core.library_parser import parse_video_path
from core.library_store import LibraryStore


class HierarchicalLibraryTests(unittest.TestCase):
    def test_parser_keeps_ambiguous_numeric_filename_unknown(self):
        parsed = parse_video_path("001.mkv")
        self.assertIsNone(parsed.episode)
        self.assertIsNone(parsed.season)

    def test_parser_supports_special_movie_and_explicit_absolute_marker(self):
        ova = parse_video_path("Example OVA 01.mkv")
        self.assertEqual((ova.episode_type, ova.episode), ("ova", 1))

        movie = parse_video_path("Example Movie.mkv")
        self.assertEqual(movie.episode_type, "movie")

        absolute = parse_video_path("Example ABS101.mkv")
        self.assertEqual(absolute.absolute_number, 101)

    def test_catalog_separates_regular_special_and_movie_media(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            series = store.upsert_anime("example", {"title": "Example", "genres": "[]"})
            movie = store.upsert_anime("example movie", {"title": "Example Movie", "genres": "[]", "media_kind": "movie"})
            store.upsert_episode(series, "/example-01.mkv", "Example S01E01.mkv", 1, 1)
            store.upsert_episode(series, "/example-ova.mkv", "Example OVA 01.mkv", 0, 1, episode_type="ova")
            store.upsert_episode(movie, "/example-movie.mkv", "Example Movie.mkv", 0, None, episode_type="movie")

            catalog = store.catalog()
            show = next(item for item in catalog if item["main_title"] == "Example")
            film = next(item for item in catalog if item["main_title"] == "Example Movie")

            self.assertEqual(show["media_kind"], "series")
            self.assertEqual(show["seasons"][0]["episodes"][0]["number"], 1)
            self.assertEqual(show["specials"][0]["episodes"][0]["episode_type"], "ova")
            self.assertEqual(film["media_kind"], "movie")
            self.assertEqual(film["seasons"], [])
            self.assertEqual(film["media_files"][0]["episode_type"], "movie")

    def test_absolute_number_is_independent_from_episode_number(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime("absolute", {"title": "Absolute", "genres": "[]"})
            store.upsert_episode(anime, "/absolute.mkv", "Absolute S01E01.mkv", 1, 1, absolute_number=101)
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            self.assertEqual(episode["number"], 1)
            self.assertEqual(episode["absolute_number"], 101)

    def test_existing_data_survives_additive_schema_migration(self):
        with tempfile.TemporaryDirectory() as d:
            db = sqlite3.connect(f"{d}/library.sqlite3")
            db.executescript("""
                CREATE TABLE anime (
                  id INTEGER PRIMARY KEY, lookup_title TEXT UNIQUE NOT NULL, anilist_id INTEGER,
                  title TEXT NOT NULL, romaji TEXT, english TEXT, native TEXT, aliases TEXT DEFAULT '[]', description TEXT,
                  cover_url TEXT, cover_cache TEXT, banner_url TEXT, genres TEXT, year INTEGER,
                  season TEXT, status TEXT, episodes_count INTEGER, duration INTEGER, score INTEGER, studio TEXT,
                  metadata_updated_at REAL, favorite INTEGER NOT NULL DEFAULT 0, user_tags TEXT NOT NULL DEFAULT '[]',
                  is_pinned INTEGER NOT NULL DEFAULT 0, personal_note TEXT, added_at REAL NOT NULL
                );
                CREATE TABLE episodes (
                  id INTEGER PRIMARY KEY, anime_id INTEGER NOT NULL, path TEXT UNIQUE NOT NULL,
                  file_name TEXT NOT NULL, season INTEGER NOT NULL, number REAL, duration REAL DEFAULT 0,
                  progress REAL DEFAULT 0, watched INTEGER DEFAULT 0, mime_type TEXT, file_size INTEGER,
                  modified_at REAL, source_folder TEXT, identity_key TEXT, missing INTEGER DEFAULT 0,
                  last_played_at REAL, episode_type TEXT NOT NULL DEFAULT 'regular', episode_title TEXT,
                  identification_source TEXT NOT NULL DEFAULT 'legacy',
                  identification_confidence TEXT NOT NULL DEFAULT 'medium',
                  manual_override INTEGER NOT NULL DEFAULT 0
                );
                INSERT INTO anime(lookup_title,title,genres,user_tags,is_pinned,personal_note,added_at)
                VALUES ('legacy','Legacy','[]','["keep"]',1,'preserve me',1);
                INSERT INTO episodes(anime_id,path,file_name,season,number,progress,duration,watched,episode_type,manual_override)
                VALUES (1,'/legacy.mkv','Legacy S01E01.mkv',1,1,50,100,0,'regular',1);
            """)
            db.commit()
            db.close()

            store = LibraryStore(d)
            with store._conn() as con:
                anime_columns = {row[1] for row in con.execute("PRAGMA table_info(anime)")}
                episode_columns = {row[1] for row in con.execute("PRAGMA table_info(episodes)")}
            self.assertIn("media_kind", anime_columns)
            self.assertIn("absolute_number", episode_columns)
            row = store.catalog()[0]
            self.assertTrue(row["is_pinned"])
            self.assertEqual(row["personal_note"], "preserve me")
            self.assertEqual(row["user_tags"], ["keep"])
            self.assertEqual(row["seasons"][0]["episodes"][0]["progress"], 50)


if __name__ == "__main__":
    unittest.main()
