import tempfile
import time
import asyncio
import unittest
import sqlite3
import json
from pathlib import Path
from unittest.mock import patch
from urllib.error import URLError
from core.android_bridge import AndroidBridge
from core.anilist import AniListClient
from core.google_account import normalize_google_profile
from core.library_parser import parse_video_path
from core.library_store import LibraryStore
from core.library_service import LibraryService
from core.ui import count_label
from core.organizer_ai import AnimeOrganizer, normalize
from views.home_view import HomeView
from views.organize_view import OrganizeView
from views.details_view import DetailView
from tests.test_flet_page_harness import AsyncRunTaskMixin


def anilist_media(anilist_id=1, english='Jujutsu Kaisen', romaji=None, synonyms=None):
    return {
        'id': anilist_id,
        'title': {'english': english, 'romaji': romaji or english, 'native': None},
        'synonyms': synonyms or [], 'genres': ['Action'], 'seasonYear': 2020,
        'season': 'FALL', 'status': 'FINISHED', 'episodes': 24,
        'duration': 24, 'averageScore': 87, 'coverImage': {}, 'studios': {'nodes': []},
    }

class ParserTests(unittest.TestCase):
    def test_common_names(self):
        self.assertEqual(parse_video_path('Naruto - 001.mkv').anime_title, 'Naruto')
        self.assertEqual(parse_video_path('Naruto Shippuden - 023.mp4').episode, 23)
        self.assertEqual(parse_video_path('One Piece 1100.mkv').episode, 1100)
        p=parse_video_path('/videos/Attack on Titan/S04/E03.mkv','/videos')
        self.assertEqual((p.anime_title,p.season,p.episode),('Attack on Titan',4,3))
        p=parse_video_path('Jujutsu Kaisen - S02E15.mkv')
        self.assertEqual((p.anime_title,p.season,p.episode),('Jujutsu Kaisen',2,15))
        self.assertEqual(p.extension, '.mkv')
    def test_android_filename_patterns_and_recording_noise(self):
        temp = parse_video_path("Supernatural Temp07Ep06.mp4")
        self.assertEqual((temp.anime_title, temp.season, temp.episode), ("Supernatural", 7, 6))
        self.assertEqual((parse_video_path("Supernatural TEMP07EP06.mp4").season, parse_video_path("Supernatural TEMP07EP06.mp4").episode), (7, 6))
        x = parse_video_path("Supernatural 07x06.mkv")
        self.assertEqual((x.anime_title, x.season, x.episode), ("Supernatural", 7, 6))
        accented = parse_video_path("Supernatural - Episódio 06.mkv")
        self.assertEqual((accented.anime_title, accented.season, accented.episode), ("Supernatural", 1, 6))
        recording = parse_video_path("Recording 20260921 194102.mp4")
        self.assertNotIn("20260921", recording.anime_title)
        self.assertNotIn("194102", recording.anime_title)

    def test_s01e01_and_episode_prefixes(self):
        s01 = parse_video_path('Frieren S01E01.mkv')
        ep = parse_video_path('Frieren EP01.mp4')
        long_ep = parse_video_path('Frieren Episode 01.webm')
        dashed = parse_video_path('Frieren - 01.m4v')
        self.assertEqual((s01.anime_title, s01.season, s01.episode), ('Frieren', 1, 1))
        self.assertEqual((ep.anime_title, ep.season, ep.episode), ('Frieren', 1, 1))
        self.assertEqual((long_ep.anime_title, long_ep.season, long_ep.episode), ('Frieren', 1, 1))
        self.assertEqual((dashed.anime_title, dashed.season, dashed.episode), ('Frieren', 1, 1))

    def test_relative_subfolder_paths_and_temporada_folders(self):
        p1 = parse_video_path('Naruto/Temporada 1/01.mp4')
        p2 = parse_video_path('One Piece/Season 2/Episode 05.mkv')
        p3 = parse_video_path('Bleach/Temp 03/Bleach 50.mp4')
        self.assertEqual((p1.anime_title, p1.season, p1.episode), ('Naruto', 1, None))
        self.assertEqual((p2.anime_title, p2.season, p2.episode), ('One Piece', 2, 5))
        self.assertEqual((p3.anime_title, p3.season, p3.episode), ('Bleach', 3, 50))
    def test_invalid_file_is_safe(self):
        p=parse_video_path('sem-padrao.mkv')
        self.assertEqual(p.episode, None)

    def test_explicit_patterns_specials_movies_and_years_are_conservative(self):
        self.assertEqual((parse_video_path('Anime 1x01.mkv').season, parse_video_path('Anime 1x01.mkv').episode), (1, 1))
        self.assertEqual((parse_video_path('[Group] Anime - S02E03 [1080p][x265].mkv').anime_title, parse_video_path('[Group] Anime - S02E03 [1080p][x265].mkv').episode), ('Anime', 3))
        ova = parse_video_path('Anime - OVA 01.mkv')
        self.assertEqual((ova.episode_type, ova.episode), ('ova', 1))
        self.assertEqual(parse_video_path('Anime SP01.mkv').episode_type, 'special')
        movie = parse_video_path('One Piece Film Red 2022.mp4')
        self.assertEqual((movie.episode_type, movie.episode), ('movie', None))
        year = parse_video_path('Anime 2024 1080p.mp4')
        self.assertIsNone(year.episode)
        self.assertIsNone(year.season)
    def test_organizer_requires_review_for_uncertain_match(self):
        candidates=[{'id':1,'title':{'romaji':'Naruto'}},{'id':2,'title':{'romaji':'Boruto'}}]
        selected, confident, ranked=AnimeOrganizer.choose('Naruto Shipuden',candidates)
        self.assertEqual(selected['id'],1)
        self.assertFalse(confident)
        self.assertGreater(ranked[0]['match_score'], ranked[1]['match_score'])
    def test_normalize_and_alternative_title_match(self):
        candidate = anilist_media(16498, 'Attack on Titan', 'Shingeki no Kyojin', ['L Attaque des Titans'])
        self.assertEqual(normalize('Shingeki no Kyōjin!'), 'shingeki no kyojin')
        selected, confident, _ = AnimeOrganizer.choose('Shingeki no Kyojin', [candidate])
        self.assertEqual(selected['id'], 16498)
        self.assertTrue(confident)

class UiTextTests(unittest.TestCase):
    def test_portuguese_count_label_handles_zero_one_and_many(self):
        self.assertEqual(count_label(0, "episódio"), "0 episódios")
        self.assertEqual(count_label(1, "episódio"), "1 episódio")
        self.assertEqual(count_label(2, "episódio"), "2 episódios")
        self.assertEqual(count_label(1, "vídeo"), "1 vídeo")
        self.assertEqual(count_label(2, "vídeo"), "2 vídeos")


class StoreTests(unittest.TestCase):
    def test_manual_episode_identification_normalizes_integer_numbers(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime("summertime-render", {"title": "Summertime Render", "genres": "[]"})
            path = "/tmp/Summertime Render S01E06.mkv"
            store.upsert_episode(anime, path, "Summertime Render S01E06.mkv", 1, 6)
            store.set_episode_identification(path, season=1, number=6.0)
            with store._conn() as con:
                row = con.execute("SELECT season, number FROM episodes WHERE path=?", (path,)).fetchone()
            self.assertEqual((row["season"], row["number"]), (1, 6))
            self.assertIsInstance(row["number"], int)

            store.set_episode_identification(path, season=1, number=6.5)
            with store._conn() as con:
                row = con.execute("SELECT season, number FROM episodes WHERE path=?", (path,)).fetchone()
            self.assertEqual(row["number"], 6.5)
            self.assertIsInstance(row["number"], float)

            store.set_episode_identification(path, season=1, number="6.0")
            with store._conn() as con:
                row = con.execute("SELECT season, number FROM episodes WHERE path=?", (path,)).fetchone()
            self.assertEqual(row["number"], 6)
            self.assertIsInstance(row["number"], int)

            store.set_episode_identification(path, season=1, number=None)
            with store._conn() as con:
                row = con.execute("SELECT season, number FROM episodes WHERE path=?", (path,)).fetchone()
            self.assertIsNone(row["number"])

    def test_personal_tags_persist_normalize_and_are_searchable_offline(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime("frieren", {"title": "Frieren", "genres": "[]"})
            tags = store.set_user_tags(anime, ["  Prioridade  ", "prioridade", "Assistir com amigos", ""])
            self.assertEqual(tags, ["Prioridade", "Assistir com amigos"])
            store.upsert_episode(anime, "/library/frieren-01.mkv", "Frieren - 01.mkv", 1, 1)
            catalog = LibraryService(store).catalog()
            self.assertEqual(catalog[0]["user_tags"], tags)
            self.assertEqual(LibraryService.browse_catalog(catalog, query="amigos"), catalog)
            self.assertEqual(LibraryStore(d).catalog()[0]["user_tags"], tags)

    def test_library_persists_episode_and_missing_flag(self):
        with tempfile.TemporaryDirectory() as d:
            store=LibraryStore(d); anime=store.upsert_anime('naruto',{'title':'Naruto','genres':'[]'})
            store.upsert_episode(anime,'/tmp/naruto-001.mkv','naruto-001.mkv',1,1,source_folder='/tmp')
            self.assertEqual(len(store.catalog()[0]['seasons'][0]['episodes']),1)
            store.mark_missing('/tmp', [])
            episode = store.catalog()[0]['seasons'][0]['episodes'][0]
            self.assertTrue(episode['missing'])

    def test_catalog_exposes_sqlite_playback_progress(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, '/tmp/naruto-001.mkv', 'naruto-001.mkv', 1, 1)
            store.save_progress('/tmp/naruto-001.mkv', 95, 100)

            episode = store.catalog()[0]['seasons'][0]['episodes'][0]

            self.assertEqual(episode['progress'], 95)
            self.assertEqual(episode['duration'], 100)
            self.assertTrue(episode['watched'])

    def test_upsert_deduplicates_and_preserves_progress(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, 'content://episode/1', 'Naruto - 001.mkv', 1, 1,
                                 'video/x-matroska', 100, 10, 'content://tree/one')
            store.save_progress('content://episode/1', 12, 24)
            store.upsert_episode(anime, 'content://episode/1', 'Naruto - 001.mkv', 1, 1,
                                 'video/x-matroska', 200, 20, 'content://tree/one')
            with store._conn() as con:
                row = con.execute('SELECT COUNT(*), progress, duration, file_size, modified_at FROM episodes').fetchone()
            self.assertEqual(tuple(row), (1, 12, 24, 200, 20))

    def test_same_volume_identity_deduplicates_and_preserves_watch_progress(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            from core.media_identity import local_media_identity
            media_uri = 'content://media/external/video/media/10'
            broad_uri = 'file:///storage/emulated/0/Anime/Naruto - 001.mkv'
            # A MediaStore row alone does not disclose a stable StorageVolume
            # identity, so it is deliberately not merged with Broad Storage.
            # Two Broad discoveries of the *same* primary file can be merged.
            first_uri = 'file:///storage/emulated/0/Anime/Naruto - 001.mkv'
            media_identity = local_media_identity(uri=first_uri, source_kind='broad_storage', relative_path='Anime/Naruto - 001.mkv', size=150000000, modified_at=1000, volume_id='primary')
            broad_identity = local_media_identity(uri=broad_uri, source_kind='broad_storage', relative_path='Anime/Naruto - 001.mkv', size=150000000, modified_at=1000, volume_id='primary')
            store.upsert_episode(anime, first_uri, 'Naruto - 001.mkv', 1, 1,
                                 'video/x-matroska', 150000000, 1000, 'broad-storage', media_identity)
            store.save_progress(first_uri, 45, 100)

            store.upsert_episode(anime, broad_uri, 'Naruto - 001.mkv', 1, 1,
                                 'video/x-matroska', 150000000, 1000, 'broad-storage', broad_identity)

            catalog = store.catalog()
            self.assertEqual(len(catalog[0]['seasons'][0]['episodes']), 1)
            episode = catalog[0]['seasons'][0]['episodes'][0]
            self.assertEqual(episode['path'], 'file:///storage/emulated/0/Anime/Naruto - 001.mkv')
            self.assertEqual(episode['progress'], 45)
            self.assertEqual(episode['duration'], 100)

    def test_same_relative_path_on_two_volumes_is_not_merged(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            from core.media_identity import local_media_identity
            for volume, uri in [('primary', 'file:///storage/emulated/0/Anime/Naruto-01.mkv'), ('ABCD-1234', 'file:///storage/ABCD-1234/Anime/Naruto-01.mkv')]:
                identity = local_media_identity(uri=uri, source_kind='broad_storage', relative_path='Anime/Naruto-01.mkv', size=100, modified_at=10, volume_id=volume)
                store.upsert_episode(anime, uri, 'Naruto-01.mkv', 1, 1, identity_key=identity, source_folder='broad-storage')
            self.assertEqual(2, len(store.catalog()[0]['seasons'][0]['episodes']))

    def test_same_name_and_size_without_local_identity_are_not_merged(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, 'content://cloud.example/a', 'Naruto - 001.mkv', 1, 1,
                                 file_size=100, modified_at=10, source_folder='content://cloud.example/tree/a')
            store.upsert_episode(anime, 'content://cloud.example/b', 'Naruto - 001.mkv', 1, 1,
                                 file_size=100, modified_at=10, source_folder='content://cloud.example/tree/b')
            self.assertEqual(len(store.catalog()[0]['seasons'][0]['episodes']), 2)

    def test_missing_file_keeps_progress_and_is_recovered(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / 'Anime'; root.mkdir()
            video = root / 'Naruto - 001.mkv'; video.write_bytes(b'video')
            store = LibraryStore(str(Path(d) / 'data')); store.add_folder(str(root))
            service = LibraryService(store)
            with patch.object(service.anilist, 'search', return_value=[]):
                service.scan()
                store.save_progress(str(video), 30, 60)
                video.unlink(); service.scan()
                missing = store.catalog()[0]['seasons'][0]['episodes'][0]
                self.assertTrue(missing['missing'])
                self.assertEqual((missing['progress'], missing['duration']), (30, 60))
                video.write_bytes(b'video'); service.scan()
            recovered = store.catalog()[0]['seasons'][0]['episodes'][0]
            self.assertFalse(recovered['missing'])
            self.assertEqual((recovered['progress'], recovered['duration']), (30, 60))

    def test_unavailable_folder_does_not_mark_existing_episodes_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store=LibraryStore(d); anime=store.upsert_anime('naruto',{'title':'Naruto','genres':'[]'})
            store.upsert_episode(anime,'/previous/Naruto - 001.mkv','Naruto - 001.mkv',1,1)
            store.add_folder('/missing-folder')
            result=LibraryService(store).scan()
            self.assertEqual(result.catalog[0]['main_title'], 'Naruto')
            self.assertTrue(result.errors)

    def test_partial_saf_scan_does_not_mark_unseen_episode_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            service = LibraryService(store)
            anime = store.upsert_anime("naruto", {"title": "Naruto", "genres": "[]"})
            uri = "content://provider/tree/video%3A1/document/video%3A1%2FNaruto-001.mkv"
            store.upsert_episode(anime, uri, "Naruto - 001.mkv", 1, 1, source_folder="content://provider/tree/video%3A1")
            service.ingest_documents(
                "content://provider/tree/video%3A1",
                [],
                folder_name="Anime",
                scan_errors=["Não foi possível ler uma subpasta"],
                scan_stats={"files": 0, "videos": 0},
            )
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            self.assertFalse(episode["missing"])

    def test_saf_reference_is_not_converted_to_path(self):
        with tempfile.TemporaryDirectory() as d:
            store=LibraryStore(d); store.add_folder('content://com.android.providers.media.documents/tree/video%3A1',kind='saf')
            result=LibraryService(store).scan()
            self.assertEqual(result.videos, 0)
            self.assertIn('aguardando scanner Android', result.errors[0])
            self.assertEqual(store.folders()[0]['authorization'], 'granted')

    def test_anilist_failure_does_not_abort_scan(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'Anime'; root.mkdir(); (root/'Naruto - 001.mkv').write_bytes(b'')
            store=LibraryStore(str(Path(d)/'data')); store.add_folder(str(root)); service=LibraryService(store)
            with patch.object(service.anilist,'search',return_value=[]):
                result=service.scan()
            self.assertEqual(result.catalog[0]['main_title'],'Naruto')
            self.assertEqual((result.animes,result.episodes),(1,1))

    def test_multiple_folders_form_one_catalog_with_seasons(self):
        with tempfile.TemporaryDirectory() as d:
            first = Path(d) / 'Anime'; second = Path(d) / 'Downloads'
            first.mkdir(); second.mkdir()
            (first / 'Frieren S01E01.mkv').write_bytes(b'')
            (second / 'Frieren S02E01.mkv').write_bytes(b'')
            store = LibraryStore(str(Path(d) / 'data'))
            store.add_folder(str(first)); store.add_folder(str(second))
            service = LibraryService(store)
            with patch.object(service.anilist, 'search', return_value=[]):
                catalog = service.scan().catalog
            self.assertEqual(len(catalog), 1)
            self.assertEqual([season['season_name'] for season in catalog[0]['seasons']], ['Temporada 1', 'Temporada 2'])
            self.assertEqual(sum(len(season['episodes']) for season in catalog[0]['seasons']), 2)


class SettingsPersistenceTests(unittest.TestCase):
    def test_preferences_create_read_update_default_and_remove(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            self.assertEqual(store.get_preference('resume_playback', 'true'), 'true')
            store.set_preference('resume_playback', 'false')
            self.assertEqual(store.get_preference('resume_playback'), 'false')
            store.set_preference('resume_playback', 'true')
            self.assertEqual(store.get_preference('resume_playback'), 'true')
            store.remove_preference('resume_playback')
            self.assertIsNone(store.get_preference('resume_playback'))

    def test_saf_rescans_mark_missing_only_within_the_scanned_tree(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            first_tree = 'content://tree/first'
            second_tree = 'content://tree/second'
            first_doc = {'uri': 'content://document/first-1', 'name': 'Naruto - 001.mkv'}
            second_doc = {'uri': 'content://document/second-1', 'name': 'Bleach - 001.mkv'}
            with patch.object(service.anilist, 'search', return_value=[]):
                service.ingest_documents(first_tree, [first_doc])
                service.ingest_documents(second_tree, [second_doc])
                service.ingest_documents(first_tree, [])
            episodes = {
                episode['path']: episode
                for anime in store.catalog()
                for season in anime['seasons']
                for episode in season['episodes']
            }
            self.assertTrue(episodes[first_doc['uri']]['missing'])
            self.assertFalse(episodes[second_doc['uri']]['missing'])


    def test_partial_saf_rescan_preserves_unseen_documents_in_that_tree(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            tree = 'content://tree/partial'
            first = {'uri': 'content://document/partial-1', 'name': 'Naruto - 001.mkv'}
            second = {'uri': 'content://document/partial-2', 'name': 'Naruto - 002.mkv'}
            with patch.object(service.anilist, 'search', return_value=[]):
                service.ingest_documents(tree, [first, second])
                service.ingest_documents(tree, [first], scan_errors=['subpasta inacessível'])
            episodes = {
                episode['path']: episode
                for anime in store.catalog()
                for season in anime['seasons']
                for episode in season['episodes']
            }
            self.assertFalse(episodes[first['uri']]['missing'])
            self.assertFalse(episodes[second['uri']]['missing'])
            self.assertIn('subpasta inacessível', store.folders()[0]['last_error'])


    def test_phase_10_migration_preserves_existing_library_rows(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'library.sqlite3'
            with sqlite3.connect(path) as con:
                con.executescript("""
                    CREATE TABLE folders (path TEXT PRIMARY KEY, added_at REAL NOT NULL);
                    CREATE TABLE anime (id INTEGER PRIMARY KEY, lookup_title TEXT UNIQUE NOT NULL, anilist_id INTEGER, title TEXT NOT NULL, romaji TEXT, english TEXT, native TEXT, description TEXT, cover_url TEXT, cover_cache TEXT, banner_url TEXT, genres TEXT, year INTEGER, season TEXT, status TEXT, episodes_count INTEGER, duration INTEGER, studio TEXT, added_at REAL NOT NULL);
                    CREATE TABLE episodes (id INTEGER PRIMARY KEY, anime_id INTEGER NOT NULL, path TEXT UNIQUE NOT NULL, file_name TEXT NOT NULL, season INTEGER NOT NULL, number REAL, duration REAL DEFAULT 0, progress REAL DEFAULT 0, watched INTEGER DEFAULT 0, missing INTEGER DEFAULT 0);
                    INSERT INTO anime(id,lookup_title,title,genres,added_at) VALUES(1,'naruto','Naruto','[]',1);
                    INSERT INTO episodes(anime_id,path,file_name,season,number) VALUES(1,'/n.mkv','Naruto - 001.mkv',1,1);
                """)
            store = LibraryStore(d)
            self.assertEqual(store.catalog()[0]['main_title'], 'Naruto')
            self.assertEqual(store.get_preference('missing', 'default'), 'default')
            with store._conn() as con:
                self.assertEqual(con.execute('SELECT MAX(version) FROM schema_migrations').fetchone()[0], store.SCHEMA_VERSION)

    def test_manual_episode_identification_survives_rescan_and_migration_fields(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('demo', {'title': 'Demo', 'genres': '[]'})
            store.upsert_episode(anime, '/library/demo.mkv', 'Demo S01E01.mkv', 1, 1,
                                 episode_type='regular', identification_source='sxxexx', identification_confidence='high')
            store.save_progress('/library/demo.mkv', 30, 100)
            store.set_episode_identification('/library/demo.mkv', season=3, number=12, episode_type='special', title='Final alternativo')
            store.upsert_episode(anime, '/library/demo.mkv', 'Demo S01E01.mkv', 1, 1,
                                 episode_type='regular', identification_source='sxxexx', identification_confidence='high')
            episode = store.catalog()[0]['specials'][0]['episodes'][0]
            self.assertEqual((episode['season'], episode['number'], episode['episode_type']), (3, 12, 'special'))
            self.assertTrue(episode['manual_override'])
            self.assertEqual((episode['progress'], episode['duration']), (30, 100))

    def test_clear_anilist_cache_preserves_library_favorite_progress_history_and_association(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]', 'anilist_id': 20})
            store.upsert_episode(anime, '/n.mkv', 'Naruto - 001.mkv', 1, 1)
            store.toggle_favorite(anime); store.save_progress('/n.mkv', 20, 100)
            store.set_association('naruto', 20)
            cover = Path(store.cache_dir) / 'cover.jpg'; cover.write_bytes(b'cover')
            with store._conn() as con:
                con.execute("UPDATE anime SET cover_cache=?,metadata_updated_at=1 WHERE id=?", (str(cover), anime))
            self.assertEqual(service.clear_anilist_cache(), 1)
            item = store.catalog()[0]
            episode = item['seasons'][0]['episodes'][0]
            self.assertTrue(item['favorite'])
            self.assertEqual((episode['progress'], episode['duration']), (20, 100))
            self.assertTrue(store.playback_history())
            self.assertEqual(store.association('naruto'), 20)
            self.assertFalse(cover.exists())
            self.assertIsNone(store.anime_metadata('naruto')['metadata_updated_at'])

    def test_saf_folder_records_active_google_account_without_uploading_media(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            store.save_account({"id": "google-sub-123", "email": "user@example.com"})
            tree = "content://com.android.providers.media.documents/tree/video%3A1"
            store.add_folder(tree, name="Animes", kind="saf", authorization="granted", account_id=store.account().get("id"))
            folder = store.folders()[0]
            self.assertEqual(folder["account_id"], "google-sub-123")
            self.assertEqual(folder["path"], tree)
            self.assertTrue(folder["path"].startswith("content://"))
            self.assertFalse(Path(d, "uploads").exists())

    def test_playback_target_uses_filename_order_for_unnumbered_episodes(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime("sample", {"title": "Sample", "genres": "[]"})

            store.upsert_episode(anime, "/library/z.mkv", "Zeta.mkv", 1, None)
            store.upsert_episode(anime, "/library/a.mkv", "Alpha.mkv", 1, None)

            target = store.playback_target(anime)

            self.assertEqual(target["file_name"], "Alpha.mkv")

    def test_remove_folder_preserves_history_but_marks_source_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime("sample", {"title": "Sample", "genres": "[]"})

            store.add_folder("content://tree/removed", "Removed", kind="saf", authorization="granted")
            store.upsert_episode(anime, "content://tree/removed/doc-1", "Sample - 01.mkv", 1, 1,
                                 source_folder="content://tree/removed")
            store.save_progress("content://tree/removed/doc-1", 42, 100)

            store.remove_folder("content://tree/removed")

            self.assertEqual(store.folders(), [])
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            self.assertTrue(episode["missing"])
            self.assertEqual(episode["progress"], 42)
            self.assertFalse(episode["watched"])

    def test_saf_folder_authorization_and_ownership_survive_database_reopen(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            store.save_account({"id": "google-sub-reopen", "email": "user@example.com"})
            tree = "content://com.android.providers.media.documents/tree/video%3A42"
            store.add_folder(tree, name="Animes", kind="saf", authorization="granted",
                              account_id=store.account()["id"])
            reopened = LibraryStore(d)
            folder = reopened.folders()[0]
            self.assertEqual(folder["path"], tree)
            self.assertEqual(folder["kind"], "saf")
            self.assertEqual(folder["authorization"], "granted")
            self.assertEqual(folder["account_id"], "google-sub-reopen")

    def test_saf_folder_keeps_ownership_when_account_is_logged_out(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            store.save_account({"id": "google-sub-456", "email": "user@example.com"})
            tree = "content://provider/tree/animes"
            store.add_folder(tree, name="Animes", kind="saf", account_id=store.account()["id"])
            store.clear_account()
            folder = store.folders()[0]
            self.assertEqual(folder["account_id"], "google-sub-456")
            self.assertEqual(store.account(), {})

    def test_account_logout_and_folder_state_do_not_remove_library(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, '/n.mkv', 'Naruto - 001.mkv', 1, 1)
            store.add_folder('content://tree/private', name='Anime', kind='saf')
            store.save_account({'id': '123', 'email': 'user@example.com'})
            store.clear_account()
            self.assertEqual(store.account(), {})
            self.assertEqual(store.folders()[0]['name'], 'Anime')
            self.assertEqual(store.library_summary()['animes'], 1)

    def test_clear_anilist_cache_reports_storage_error_without_touching_library(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, '/n.mkv', 'Naruto - 001.mkv', 1, 1)
            with patch('core.library_service.os.scandir', side_effect=OSError('read-only')):
                with self.assertRaisesRegex(RuntimeError, 'cache de capas'):
                    service.clear_anilist_cache()
            self.assertEqual(store.library_summary()['episodes'], 1)

    def test_reopening_database_does_not_repeat_personal_tags_migration(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            LibraryStore(d)
            with store._conn() as con:
                self.assertEqual(
                    con.execute('SELECT COUNT(*) FROM schema_migrations WHERE version=?', (store.SCHEMA_VERSION,)).fetchone()[0],
                    1,
                )

    def test_invalid_progress_is_rejected_and_overflow_is_normalized(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, '/n.mkv', 'Naruto - 001.mkv', 1, 1)
            self.assertFalse(store.save_progress('/n.mkv', -1, 100))
            self.assertFalse(store.save_progress('/n.mkv', 1, -10))
            self.assertTrue(store.save_progress('/n.mkv', 500, 100))
            episode = store.catalog()[0]['seasons'][0]['episodes'][0]
            self.assertEqual((episode['progress'], episode['duration'], episode['watched']), (100, 100, True))

    def test_repeated_progress_updates_keep_one_history_row(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, '/n.mkv', 'Naruto - 001.mkv', 1, 1)
            store.save_progress('/n.mkv', 10, 100); store.save_progress('/n.mkv', 20, 100)
            history = store.playback_history()
            self.assertEqual(len(history), 1)
            self.assertEqual(history[0]['progress'], 20)

    def test_episode_navigation_handles_missing_episode_numbers(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {'title': 'Naruto', 'genres': '[]'})
            store.upsert_episode(anime, '/one.mkv', 'One.mkv', 1, None)
            store.upsert_episode(anime, '/two.mkv', 'Two.mkv', 1, None)
            store.upsert_episode(anime, '/three.mkv', 'Three.mkv', 1, 3)
            self.assertEqual(store.next_episode('/one.mkv')['path'], '/two.mkv')
            self.assertEqual(store.previous_episode('/three.mkv')['path'], '/two.mkv')


class AndroidBridgeTests(unittest.IsolatedAsyncioTestCase):
    def test_android_bridge_accepts_local_references_only(self):
        self.assertTrue(AndroidBridge.is_local_media_reference("content://com.android.providers.media.documents/document/video%3A1"))
        self.assertTrue(AndroidBridge.is_local_media_reference("file:///storage/emulated/0/Anime/ep.mkv"))
        self.assertTrue(AndroidBridge.is_local_media_reference("/storage/emulated/0/Anime/ep.mkv"))
        self.assertFalse(AndroidBridge.is_local_media_reference("https://example.com/ep.mkv"))

    def test_android_bridge_can_drain_multiple_batches(self):
        with tempfile.TemporaryDirectory() as d:
            bridge = AndroidBridge(d)
            bridge.mailbox.write_text(json.dumps([{"type": "first"}]), encoding="utf-8")
            self.assertEqual(bridge.drain()[0]["type"], "first")
            bridge.acknowledge()
            bridge.mailbox.write_text(json.dumps([{"type": "second"}]), encoding="utf-8")
            self.assertEqual(bridge.drain()[0]["type"], "second")
            bridge.acknowledge()

    def test_flet_page_platform_enum_is_recognized_on_real_android(self):
        class Platform:
            value = 'android'
        class Page:
            platform = Platform()
        with tempfile.TemporaryDirectory() as d:
            self.assertTrue(AndroidBridge(d, Page()).available)

    async def test_native_saf_actions_use_only_encoded_content_uris(self):
        class Launcher:
            def __init__(self, page):
                self.page = page

            async def launch_url(self, value, *, mode):
                self.page.urls.append(value)
                request_id = value.split("request_id=", 1)[1].split("&", 1)[0]
                self.page.bridge.observe_native_event({
                    "type": "diagnostic",
                    "requestId": request_id,
                    "payload": {
                        "event": "COMMAND_RECEIVED",
                        "requestId": request_id,
                        "action": value.split("action=", 1)[1].split("&", 1)[0],
                        "timestamp": 123456789,
                    },
                })

        class Page:
            platform = 'android'
            def __init__(self):
                self.urls = []
                self.bridge = None
                self.url_launcher = Launcher(self)

            async def launch_url(self, value):
                raise AssertionError("legacy Page.launch_url must not be used for native Android commands")

        with tempfile.TemporaryDirectory() as d:
            page = Page(); bridge = AndroidBridge(d, page); page.bridge = bridge
            tree = 'content://com.android.providers.media.documents/tree/video%3AAnime'
            await bridge.select_tree(); await bridge.verify_tree(tree); await bridge.rescan_tree(tree)
            self.assertTrue(page.urls[0].startswith('reiflix://native?action=select_tree&request_id='))
            self.assertIn('action=verify_tree', page.urls[1])
            self.assertIn('request_id=', page.urls[1])
            self.assertIn('tree_uri=content%3A%2F%2F', page.urls[1])
            self.assertIn('action=scan_tree', page.urls[2])
            self.assertNotIn('mode=', page.urls[0])


    async def test_player_bridge_rejects_remote_urls_but_keeps_local_references(self):
        class Page:
            platform = 'android'
            def __init__(self): self.urls = []
            async def launch_url(self, value): self.urls.append(value)

        with tempfile.TemporaryDirectory() as d:
            bridge = AndroidBridge(d, Page())
            self.assertTrue(bridge.is_local_media_reference('content://provider/document/1'))
            self.assertTrue(bridge.is_local_media_reference('/local/video.mkv'))
            self.assertTrue(bridge.is_local_media_reference('file:///local/video.mkv'))
            self.assertFalse(bridge.is_local_media_reference('https://example.invalid/video.m3u8'))
            self.assertEqual(
                bridge.normalize_local_media_reference('/local/video.mkv'),
                __import__('pathlib').Path('/local/video.mkv').resolve().as_uri(),
            )
            with self.assertRaisesRegex(ValueError, 'somente arquivos locais'):
                await bridge.play('https://example.invalid/video.m3u8', 'Remote')
            self.assertEqual(bridge.page.urls, [])

    def test_mailbox_acknowledges_only_after_the_claimed_batch_is_processed(self):
        with tempfile.TemporaryDirectory() as d:
            bridge = AndroidBridge(d)
            bridge.mailbox.write_text(json.dumps([{'type': 'unknown'}, 'bad', 3]), encoding='utf-8')
            self.assertEqual([event["type"] for event in bridge.drain()], ["unknown"])
            consumed = list(bridge.queue_dir.glob("event-*.consumed"))
            self.assertEqual(len(consumed), 1)
            self.assertFalse(bridge.mailbox.with_suffix(".consumed").exists())
            self.assertEqual(bridge.drain(), [])
            bridge.acknowledge()
            self.assertFalse(any(bridge.queue_dir.glob("event-*.consumed")))
            bridge.mailbox.write_text("{bad json", encoding="utf-8")
            self.assertEqual(bridge.drain(), [])
            # Invalid legacy JSON stays claimed as .consumed so the malformed
            # batch is not silently lost and can be diagnosed/recovered.
            self.assertTrue(bridge.mailbox.with_suffix(".consumed").exists())
            bridge.mailbox.with_suffix(".consumed").unlink()

    def test_native_events_written_while_batch_is_claimed_survive_acknowledgement(self):
        with tempfile.TemporaryDirectory() as d:
            bridge = AndroidBridge(d)
            bridge.mailbox.write_text(json.dumps([{'type': 'first'}]), encoding='utf-8')
            self.assertEqual(bridge.drain()[0]['type'], 'first')
            # NativeMailbox can publish the next batch while Python is still
            # processing the claimed .consumed file. Acknowledging the first
            # batch must not delete that newly published batch.
            bridge.mailbox.write_text(json.dumps([{'type': 'second'}]), encoding='utf-8')
            bridge.acknowledge()
            self.assertEqual(bridge.drain()[0]['type'], 'second')
            bridge.acknowledge()
            self.assertFalse(bridge.mailbox.exists())

    def test_unacknowledged_mailbox_batch_is_replayed_after_bridge_restart(self):
        with tempfile.TemporaryDirectory() as d:
            first = AndroidBridge(d)
            first.mailbox.write_text(json.dumps([{'type': 'saf_scan', 'payload': {'treeUri': 'content://tree/anime'}}]), encoding='utf-8')
            self.assertEqual(first.drain()[0]["type"], "saf_scan")
            first_consumed = list(first.queue_dir.glob("event-*.consumed"))
            self.assertEqual(len(first_consumed), 1)
            self.assertFalse(first.mailbox.with_suffix(".consumed").exists())
            restarted = AndroidBridge(d)
            self.assertEqual(restarted.drain()[0]["type"], "saf_scan")
            restarted.acknowledge()
            self.assertFalse(any(restarted.queue_dir.glob("event-*.consumed")))
            self.assertFalse(restarted.mailbox.with_suffix(".consumed").exists())

    def test_scalar_only_mailbox_batch_can_be_acknowledged_safely(self):
        with tempfile.TemporaryDirectory() as d:
            bridge = AndroidBridge(d)
            bridge.mailbox.write_text(json.dumps(["bad", 3]), encoding="utf-8")
            self.assertEqual(bridge.drain(), [])
            self.assertFalse(any(bridge.queue_dir.glob('event-*.consumed')))
            bridge.acknowledge()
            self.assertFalse(any(bridge.queue_dir.glob('event-*.consumed')))

    def test_native_saf_documents_are_persisted_as_uris(self):
        with tempfile.TemporaryDirectory() as d:
            store=LibraryStore(d); service=LibraryService(store)
            with patch.object(service.anilist, 'search', return_value=[]):
                catalog=service.ingest_documents('content://tree/anime', [{
                    'uri':'content://document/naruto-001', 'name':'Naruto - 001.mkv',
                    'mimeType':'video/x-matroska', 'size':1234, 'modifiedAt':99,
                }])
            self.assertEqual(catalog[0]['seasons'][0]['episodes'][0]['path'], 'content://document/naruto-001')
            with store._conn() as con:
                row=con.execute('SELECT mime_type,file_size,modified_at FROM episodes').fetchone()
            self.assertEqual(tuple(row), ('video/x-matroska', 1234, 99))

    def test_saf_rescan_marks_only_missing_documents_from_its_tree(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            documents = [
                {'uri': 'content://document/one', 'name': 'Naruto - 001.mkv'},
                {'uri': 'content://document/two', 'name': 'Naruto - 002.mkv'},
            ]
            with patch.object(service.anilist, 'search', return_value=[]):
                service.ingest_documents('content://tree/anime', documents)
                store.save_progress('content://document/two', 10, 20)
                service.ingest_documents('content://tree/anime', documents[:1])
            episodes = store.catalog()[0]['seasons'][0]['episodes']
            self.assertFalse(episodes[0]['missing'])
            self.assertTrue(episodes[1]['missing'])
            self.assertEqual((episodes[1]['progress'], episodes[1]['duration']), (10, 20))

    def test_partial_saf_scan_does_not_mark_unread_documents_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            document = {'uri': 'content://document/one', 'name': 'Naruto - 001.mkv'}
            with patch.object(service.anilist, 'search', return_value=[]):
                service.ingest_documents('content://tree/anime', [document])
                service.ingest_documents('content://tree/anime', [], scan_errors=['Sem acesso à subpasta'])
            episode = store.catalog()[0]['seasons'][0]['episodes'][0]
            self.assertFalse(episode['missing'])
            self.assertIn('Sem acesso', store.folders()[0]['last_error'])

    def test_saf_empty_scan_records_zero_videos_without_breaking_library(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            catalog = service.ingest_documents(
                'content://tree/empty', [], folder_name='Vazia',
                scan_stats={'files': 0, 'videos': 0, 'directories': 2},
            )
            self.assertEqual(catalog, [])
            self.assertEqual(store.folders()[0]['name'], 'Vazia')
            self.assertEqual(store.last_scan()['videos'], 0)
            self.assertEqual(store.last_scan()['files'], 0)

    def test_saf_duplicate_uri_preserves_progress_and_records_native_counts(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); service = LibraryService(store)
            document = {'uri': 'content://document/naruto-1', 'name': 'Naruto S01E01.mkv'}
            with patch.object(service.anilist, 'search', return_value=[]):
                service.ingest_documents('content://tree/anime', [document], scan_stats={'files': 3, 'videos': 1})
                store.save_progress(document['uri'], 30, 60)
                service.ingest_documents('content://tree/anime', [document], scan_stats={'files': 3, 'videos': 1})
            with store._conn() as con:
                count, progress, duration = con.execute('SELECT COUNT(*), progress, duration FROM episodes').fetchone()
            self.assertEqual((count, progress, duration), (1, 30, 60))
            self.assertEqual((store.last_scan()['files'], store.last_scan()['videos']), (3, 1))


class GoogleProfileTests(unittest.TestCase):
    def test_profile_whitelists_only_token_free_identity_fields(self):
        profile = normalize_google_profile({
            'id': 'google-subject', 'email': 'user@example.com', 'name': 'User',
            'picture': 'https://example.invalid/picture', 'id_token': 'must-not-persist',
        })
        self.assertEqual(profile, {
            'id': 'google-subject', 'email': 'user@example.com', 'name': 'User',
            'picture': 'https://example.invalid/picture',
        })

    def test_profile_rejects_missing_subject_or_invalid_email(self):
        self.assertIsNone(normalize_google_profile({'email': 'user@example.com'}))
        self.assertIsNone(normalize_google_profile({'id': 'subject', 'email': 'not-an-email'}))


class AniListResilienceTests(unittest.TestCase):
    def test_empty_cached_cover_file_is_not_reused(self):
        with tempfile.TemporaryDirectory() as d:
            client = AniListClient(d)
            url = 'https://example.invalid/cover.jpg'
            name = __import__('hashlib').sha256(url.encode()).hexdigest() + '.jpg'
            Path(d, name).touch()
            with patch('core.anilist.urllib.request.urlopen') as request:
                response = request.return_value.__enter__.return_value
                response.read.return_value = b'cover'
                result = client.cache_cover(url)
            self.assertEqual(result, str(Path(d, name)))
            self.assertEqual(Path(result).read_bytes(), b'cover')


    def test_incomplete_metadata_is_safe_without_anilist_id_or_cover(self):
        with tempfile.TemporaryDirectory() as d:
            client = AniListClient(d)
            metadata = client.metadata_from_media('Arquivo local', {
                'title': {'romaji': 'Arquivo local'}, 'studios': {'nodes': ['invalid']},
                'genres': None,
            })
        self.assertEqual(metadata['title'], 'Arquivo local')
        self.assertNotIn('anilist_id', metadata)
        self.assertEqual(metadata['cover_cache'], '')
        self.assertEqual(metadata['genres'], '[]')


class IdentificationTests(unittest.TestCase):
    def _service(self, directory):
        store = LibraryStore(directory)
        return store, LibraryService(store)

    def test_confident_result_is_cached_and_associated(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            media = anilist_media(113415, 'Jujutsu Kaisen', synonyms=['Sorcery Fight'])
            with patch.object(service.anilist, 'search', return_value=[media]):
                metadata = service._identify('jujutsu kaisen', 'Jujutsu Kaisen', lambda _: None)
            store.upsert_anime('jujutsu kaisen', metadata)
            cached = store.anime_metadata('jujutsu kaisen')
            self.assertEqual(store.association('jujutsu kaisen'), 113415)
            self.assertEqual((cached['title'], cached['score']), ('Jujutsu Kaisen', 87))
            self.assertIn('Sorcery Fight', cached['aliases'])

    def test_fresh_cache_works_offline_without_searching_again(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            media = anilist_media(1, 'Jujutsu Kaisen')
            with patch.object(service.anilist, 'search', return_value=[media]) as search:
                metadata = service._identify('jujutsu kaisen', 'Jujutsu Kaisen', lambda _: None)
                store.upsert_anime('jujutsu kaisen', metadata)
                cached = service._identify('jujutsu kaisen', 'Jujutsu Kaisen', lambda _: None)
            self.assertEqual(search.call_count, 1)
            self.assertEqual(cached['anilist_id'], 1)

    def test_manual_association_is_preserved_on_rescan(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            store.resolve_match('jujutsu kaisen', 99)
            media = anilist_media(99, 'Jujutsu Kaisen')
            with patch.object(service.anilist, 'by_id', return_value=media) as by_id, \
                 patch.object(service.anilist, 'search') as search:
                metadata = service._identify('jujutsu kaisen', 'Jujutsu Kaisen', lambda _: None)
                store.upsert_anime('jujutsu kaisen', metadata)
                service._identify('jujutsu kaisen', 'Jujutsu Kaisen', lambda _: None)
            self.assertEqual(store.association('jujutsu kaisen'), 99)
            self.assertEqual(by_id.call_count, 1)
            search.assert_not_called()

    def test_ambiguous_and_empty_results_stay_unidentified(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            candidates = [anilist_media(1, 'Naruto'), anilist_media(2, 'Boruto')]
            with patch.object(service.anilist, 'search', return_value=candidates):
                ambiguous = service._identify('naruto shipuden', 'Naruto Shipuden', lambda _: None)
            with patch.object(service.anilist, 'search', return_value=[]):
                missing = service._identify('arquivo local', 'Arquivo Local', lambda _: None)
            self.assertIsNone(ambiguous.get('anilist_id'))
            self.assertEqual(ambiguous.get('metadata_status'), 'ambiguous')
            self.assertEqual(store.pending_matches()[0]['lookup_title'], 'naruto shipuden')
            self.assertEqual(missing['title'], 'Arquivo Local')

    def test_rescan_keeps_local_seasons_and_does_not_repeat_metadata_request(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / 'Anime'; root.mkdir()
            (root / 'Jujutsu Kaisen S01E01.mkv').write_bytes(b'')
            (root / 'Jujutsu Kaisen S02E01.mkv').write_bytes(b'')
            store = LibraryStore(str(Path(d) / 'data')); store.add_folder(str(root))
            service = LibraryService(store); media = anilist_media(1, 'Jujutsu Kaisen')
            with patch.object(service.anilist, 'search', return_value=[media]) as search:
                first = service.scan().catalog
                second = service.scan().catalog
            self.assertEqual(search.call_count, 0)
            self.assertEqual([season['season_name'] for season in second[0]['seasons']], ['Temporada 1', 'Temporada 2'])
            self.assertEqual(sum(len(s['episodes']) for s in first[0]['seasons']), 2)

    def test_missing_cover_retries_after_short_cover_window(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            stale = time.time() - service.COVER_RETRY_SECONDS - 1
            store.upsert_anime('naruto', {
                'title': 'Naruto',
                'genres': '[]',
                'anilist_id': 1,
                'cover_url': 'https://example/cover.jpg',
                'cover_cache': str(Path(d) / 'missing.jpg'),
                'metadata_updated_at': stale,
            })
            store.set_association('naruto', 1)
            with patch.object(service.anilist, 'by_id', return_value=None) as by_id:
                metadata = service._identify('naruto', 'Naruto', lambda _: None)
            by_id.assert_called_once_with(1)
            self.assertEqual(metadata['title'], 'Naruto')

    def test_recent_missing_cover_does_not_trigger_remote_refresh(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            recent = time.time() - 60
            store.upsert_anime('naruto', {
                'title': 'Naruto',
                'genres': '[]',
                'anilist_id': 1,
                'cover_url': 'https://example/cover.jpg',
                'cover_cache': str(Path(d) / 'missing.jpg'),
                'metadata_updated_at': recent,
            })
            store.set_association('naruto', 1)
            with patch.object(service.anilist, 'by_id') as by_id:
                metadata = service._identify('naruto', 'Naruto', lambda _: None)
            by_id.assert_not_called()
            self.assertEqual(metadata['title'], 'Naruto')

    def test_expired_cached_metadata_is_preserved_when_refresh_fails(self):
        with tempfile.TemporaryDirectory() as d:
            store, service = self._service(d)
            store.upsert_anime('naruto', {'title': 'Naruto antigo', 'genres': '[]', 'anilist_id': 1, 'metadata_updated_at': 1})
            store.set_association('naruto', 1)
            with patch.object(service.anilist, 'by_id', return_value=None) as by_id:
                metadata = service._identify('naruto', 'Naruto', lambda _: None)
            by_id.assert_called_once_with(1)
            self.assertEqual(metadata['title'], 'Naruto antigo')

    def test_scan_survives_anilist_timeout_and_records_completion(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / 'Anime'; root.mkdir(); (root / 'Naruto - 001.mkv').write_bytes(b'')
            store = LibraryStore(str(Path(d) / 'data')); store.add_folder(str(root)); service = LibraryService(store)
            with patch.object(service.anilist, 'search', side_effect=URLError('offline')):
                result = service.scan()
            self.assertEqual(result.catalog[0]['main_title'], 'Naruto')
            self.assertIsNotNone(store.last_scan()['finished_at'])


class LibraryStateTests(unittest.TestCase):
    def _episodes(self, store, title='One Piece'):
        anime = store.upsert_anime(title.casefold(), {'title': title, 'genres': '[]'})
        paths = []
        for season, number in ((1, 1), (1, 2), (1, 3), (2, 1)):
            path = f'/library/{title}-{season}-{number}.mkv'
            store.upsert_episode(anime, path, Path(path).name, season, number, source_folder='/library')
            paths.append(path)
        return anime, paths

    def test_add_remove_and_list_favorites_persist_after_reopen(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); anime, _ = self._episodes(store)
            self.assertTrue(store.toggle_favorite(anime))
            reopened = LibraryStore(d)
            self.assertTrue(reopened.is_favorite(anime))
            self.assertEqual([item['id'] for item in reopened.catalog(favorites_only=True)], [anime])
            self.assertFalse(reopened.toggle_favorite(anime))
            self.assertEqual(reopened.catalog(favorites_only=True), [])

    def test_progress_and_history_persist_after_reopen(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); _, paths = self._episodes(store)
            store.save_progress(paths[1], 30, 100)
            reopened = LibraryStore(d)
            current = reopened.current_episode(1)
            self.assertEqual((current['path'], current['progress'], current['duration']), (paths[1], 30, 100))
            self.assertIsNotNone(current['last_played_at'])
            history = reopened.playback_history()
            self.assertEqual((history[0]['anime_id'], history[0]['path'], history[0]['season']), (1, paths[1], 1))

    def test_continue_watching_orders_recent_and_skips_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            first, first_paths = self._episodes(store, 'One Piece')
            second, second_paths = self._episodes(store, 'Jujutsu Kaisen')
            store.save_progress(first_paths[0], 20, 100)
            store.save_progress(second_paths[0], 40, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET last_played_at=10 WHERE path=?', (first_paths[0],))
                con.execute('UPDATE episodes SET last_played_at=20 WHERE path=?', (second_paths[0],))
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (first_paths[0],))
            items = store.continue_watching()
            self.assertEqual([item['anime_id'] for item in items], [second])
            self.assertEqual(items[0]['path'], second_paths[0])

    def test_next_episode_crosses_season_and_skips_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); _, paths = self._episodes(store)
            self.assertEqual(store.next_episode(paths[1])['path'], paths[2])
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (paths[2],))
            self.assertEqual(store.next_episode(paths[1])['path'], paths[3])

    def test_previous_episode_crosses_season_and_skips_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); _, paths = self._episodes(store)
            self.assertEqual(store.previous_episode(paths[2])['path'], paths[1])
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (paths[1],))
            self.assertEqual(store.previous_episode(paths[2])['path'], paths[0])
            self.assertIsNone(store.previous_episode(paths[0]))

    def test_completed_episode_continues_with_next_available(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d); anime, paths = self._episodes(store)
            store.save_progress(paths[1], 95, 100)
            current = store.current_episode(anime)
            self.assertEqual(current['path'], paths[2])
            continuation = store.continue_watching()
            self.assertEqual(continuation, [])
            self.assertEqual(store.next_episode(paths[1])['path'], paths[2])

    def test_metadata_refresh_preserves_cached_cover_when_new_download_fails(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('naruto', {
                'title': 'Naruto',
                'anilist_id': 20,
                'cover_url': 'https://example/old.jpg',
                'cover_cache': '/cache/old.jpg',
                'genres': '[\"Ação\"]',
            })
            store.upsert_anime('naruto', {
                'title': 'Naruto',
                'anilist_id': 20,
                'cover_url': '',
                'cover_cache': '',
                'genres': '[\"Ação\"]',
            })
            metadata = store.anime_metadata('naruto')
            self.assertEqual(metadata['id'], anime)
            self.assertEqual(metadata['cover_url'], 'https://example/old.jpg')
            self.assertEqual(metadata['cover_cache'], '/cache/old.jpg')

    def test_rescan_preserves_favorite_progress_and_watched(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d) / 'Anime'; root.mkdir(); video = root / 'Naruto - 001.mkv'; video.write_bytes(b'')
            store = LibraryStore(str(Path(d) / 'data')); store.add_folder(str(root)); service = LibraryService(store)
            with patch.object(service.anilist, 'search', return_value=[]):
                first = service.scan().catalog[0]
                store.toggle_favorite(first['id'])
                store.save_progress(str(video), 95, 100)
                rescanned = service.scan().catalog[0]
            episode = rescanned['seasons'][0]['episodes'][0]
            self.assertTrue(rescanned['favorite'])
            self.assertEqual((episode['progress'], episode['duration']), (95, 100))
            self.assertTrue(episode['watched'])


class LibraryBrowseTests(unittest.TestCase):
    def _catalog(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            action = store.upsert_anime('attack', {'title': 'Attack on Titan', 'romaji': 'Shingeki no Kyojin', 'aliases': '["AoT"]', 'genres': '["Ação"]'})
            comedy = store.upsert_anime('nichijou', {'title': 'Nichijou', 'genres': '["Comédia"]'})
            store.upsert_episode(action, '/a1.mkv', 'Attack - 001.mkv', 1, 1, source_folder='/')
            store.upsert_episode(comedy, '/n1.mkv', 'Nichijou - 001.mkv', 1, 1, source_folder='/')
            store.toggle_favorite(action)
            store.save_progress('/a1.mkv', 40, 100)
            return store.catalog()

    def test_browse_catalog_filters_states_genre_and_local_aliases(self):
        catalog = self._catalog()
        self.assertEqual(len(LibraryService.browse_catalog(catalog)), 2)
        self.assertEqual(LibraryService.browse_catalog(catalog, state='Favoritos')[0]['main_title'], 'Attack on Titan')
        self.assertEqual(LibraryService.browse_catalog(catalog, state='Em andamento')[0]['main_title'], 'Attack on Titan')
        self.assertEqual(LibraryService.browse_catalog(catalog, genre='Comédia')[0]['main_title'], 'Nichijou')
        self.assertEqual(LibraryService.browse_catalog(catalog, query='shingeki')[0]['main_title'], 'Attack on Titan')
        self.assertEqual(LibraryService.browse_catalog(catalog, query='aot')[0]['main_title'], 'Attack on Titan')

    def test_browse_catalog_sorts_without_inventing_state(self):
        catalog = self._catalog()
        self.assertEqual([item['main_title'] for item in LibraryService.browse_catalog(catalog, sort='Nome A-Z')], ['Attack on Titan', 'Nichijou'])
        self.assertEqual([item['main_title'] for item in LibraryService.browse_catalog(catalog, sort='Nome Z-A')], ['Nichijou', 'Attack on Titan'])
        self.assertEqual(LibraryService.browse_catalog(catalog, state='Concluídos'), [])

    def test_home_builds_for_an_empty_local_catalog(self):
        class FakePage(AsyncRunTaskMixin):
            def update(self): pass
            def run_thread(self, work): work()
        with tempfile.TemporaryDirectory() as d:
            view = HomeView.build(FakePage(), LibraryService(LibraryStore(d)), lambda _: None, lambda: None, lambda *args, **kwargs: None)
        self.assertEqual(view.content.controls[0].__class__.__name__, 'Row')

    def test_home_does_not_label_missing_episode_as_fully_completed(self):
        class FakePage(AsyncRunTaskMixin):
            def update(self): pass
            def run_thread(self, work): work()
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('partial-complete', {'title': 'Partial Complete', 'genres': '[]'})
            available = '/partial-01.mkv'
            missing = '/partial-02.mkv'
            store.upsert_episode(anime, available, 'Partial - 01.mkv', 1, 1, source_folder='/partial')
            store.upsert_episode(anime, missing, 'Partial - 02.mkv', 1, 2, source_folder='/partial')
            store.save_progress(available, 95, 100)
            store.mark_missing('/partial', [])
            view = HomeView.build(FakePage(), LibraryService(store), lambda _: None, lambda: None,
                                  lambda *args, **kwargs: None)
            texts = []
            def walk(control):
                if control.__class__.__name__ == 'Text' and getattr(control, 'value', None):
                    texts.append(control.value)
                for child in getattr(control, 'controls', []) or []:
                    walk(child)
                if getattr(control, 'content', None) is not None:
                    walk(control.content)
            walk(view)
            self.assertNotIn('Concluído', texts)

    def test_home_continuation_uses_anime_and_episode_title_for_player(self):
        class FakePage(AsyncRunTaskMixin):
            def update(self): pass
            def run_thread(self, work): work()
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('attack', {'title': 'Attack on Titan', 'genres': '[]'})
            path = '/attack-01.mkv'
            store.upsert_episode(anime, path, 'Attack - 01.mkv', 1, 1)
            store.save_progress(path, 25, 100)
            played = []
            view = HomeView.build(
                FakePage(), LibraryService(store), lambda _: None, lambda: None,
                lambda path, title, **kwargs: played.append((path, title, kwargs)),
            )
            def walk(control):
                yield control
                for child in getattr(control, 'controls', []) or []:
                    yield from walk(child)
                if getattr(control, 'content', None) is not None:
                    yield from walk(control.content)
            card = next(item for item in walk(view) if item.__class__.__name__ == 'Container' and
                        item.on_click and getattr(item, 'width', None) == 258)
            card.on_click(None)
            self.assertEqual(
                played[0],
                (path, 'Attack on Titan • T1 E1', {
                    'progress_seconds': 25.0,
                    'episode_id': 1,
                    'anime_id': 1,
                }),
            )

    def test_home_reuses_query_filter_and_sort_state_after_a_round_trip(self):
        class FakePage(AsyncRunTaskMixin):
            def update(self): pass
            def run_thread(self, work): work()
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('attack', {'title': 'Attack', 'genres': '["Ação"]'})
            store.upsert_episode(anime, '/attack.mkv', 'Attack - 01.mkv', 1, 1)
            store.toggle_favorite(anime)
            state = {'state': 'Favoritos', 'genre': 'Ação', 'sort': 'Nome Z-A',
                     'search_visible': True, 'query': 'attack'}
            view = HomeView.build(FakePage(), LibraryService(store), lambda _: None, lambda: None,
                                  lambda *args, **kwargs: None, view_state=state)
            def walk(control):
                yield control
                for child in getattr(control, 'controls', []) or []:
                    yield from walk(child)
                if getattr(control, 'content', None) is not None:
                    yield from walk(control.content)
            search = next(control for control in walk(view) if control.__class__.__name__ == 'TextField')
            sort = next(control for control in walk(view) if control.__class__.__name__ == 'Dropdown')
            self.assertEqual((search.value, search.visible, sort.value), ('attack', True, 'Nome Z-A'))


class DetailsDomainTests(unittest.TestCase):
    class FakePage:
        def __init__(self):
            self.controls = []
            self.snack_bar = None
            self.updates = 0

        def update(self):
            self.updates += 1

    def _store_with_episodes(self, directory):
        store = LibraryStore(directory)
        anime = store.upsert_anime('details', {'title': 'Details', 'genres': '[]'})
        paths = []
        for season, number in ((1, 1), (1, 2), (2, 1)):
            path = f'/library/details-{season}-{number}.mkv'
            store.upsert_episode(anime, path, Path(path).name, season, number, source_folder='/library')
            paths.append(path)
        return store, anime, paths

    def test_playback_target_starts_first_available_and_returns_none_without_episodes(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            empty = store.upsert_anime('empty', {'title': 'Empty', 'genres': '[]'})
            self.assertIsNone(store.playback_target(empty))
            store, anime, paths = self._store_with_episodes(d)
            self.assertEqual(store.playback_target(anime)['path'], paths[0])

    def test_adjacent_episode_carries_anime_title_for_native_player(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('player-title', {'title': 'Player Title', 'genres': '[]'})
            first = '/library/player-01.mkv'
            second = '/library/player-02.mkv'
            store.upsert_episode(anime, first, 'Player - 01.mkv', 1, 1)
            store.upsert_episode(anime, second, 'Player - 02.mkv', 1, 2)
            target = store.next_episode(first)
            self.assertEqual(target['path'], second)
            self.assertEqual(target['anime_title'], 'Player Title')

    def test_details_movie_uses_local_media_presentation_without_fake_episode_structure(self):
        class FakePage:
            def __init__(self):
                self.overlay = []
                self.snack_bar = None
            def update(self):
                pass

        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime_id = store.upsert_anime(
                "movie-details",
                {"title": "Movie Details", "genres": "[]", "media_kind": "movie"},
            )
            store.upsert_episode(
                anime_id, "content://movie/details", "Movie Details.mkv", 0, None,
                episode_type="movie",
            )
            store.save_progress("content://movie/details", 3600, 7200)
            anime = store.catalog()[0]
            view = DetailView.build(
                FakePage(), anime, lambda *args, **kwargs: None,
                lambda: None, lambda _: True, store.playback_target,
            )
            texts = []
            def walk(control):
                value = getattr(control, "value", None)
                button_text = getattr(control, "text", None)
                if value:
                    texts.append(value)
                if button_text:
                    texts.append(button_text)
                for child in getattr(control, "controls", []) or []:
                    walk(child)
                if getattr(control, "content", None) is not None:
                    walk(control.content)
            walk(view)
            self.assertIn("ARQUIVO LOCAL", texts)
            self.assertNotIn("S01E01", " ".join(map(str, texts)))
            self.assertIn("Duração • 2h 00min", texts)

    def test_details_special_only_uses_special_playback_action(self):
        class FakePage:
            def __init__(self):
                self.overlay = []
                self.snack_bar = None
            def update(self):
                pass

        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime_id = store.upsert_anime(
                "special-only-details",
                {"title": "Special Only", "genres": "[]", "media_kind": "series"},
            )
            store.upsert_episode(
                anime_id, "content://special/only-ova", "OVA01.mkv", 1, 1,
                episode_type="ova",
            )
            anime = store.catalog()[0]
            view = DetailView.build(
                FakePage(), anime, lambda *args, **kwargs: None,
                lambda: None, lambda _: True, store.playback_target,
            )
            texts = []
            def walk(control):
                value = getattr(control, "value", None)
                button_text = getattr(control, "text", None)
                if value:
                    texts.append(value)
                if button_text:
                    texts.append(button_text)
                for child in getattr(control, "controls", []) or []:
                    walk(child)
                if getattr(control, "content", None) is not None:
                    walk(control.content)
            walk(view)
            target = store.playback_target(anime_id)
            self.assertEqual("ova", target["episode_type"])
            self.assertIn("ESPECIAIS", texts)

    def test_details_specials_are_kept_separate_from_regular_episode_section(self):
        class FakePage:
            def __init__(self):
                self.overlay = []
                self.snack_bar = None
            def update(self):
                pass

        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime_id = store.upsert_anime(
                "special-details",
                {"title": "Special Details", "genres": "[]", "media_kind": "series"},
            )
            store.upsert_episode(anime_id, "content://special/e1", "E01.mkv", 1, 1)
            store.upsert_episode(
                anime_id, "content://special/ova", "OVA01.mkv", 1, 1,
                episode_type="ova",
            )
            anime = store.catalog()[0]
            view = DetailView.build(
                FakePage(), anime, lambda *args, **kwargs: None,
                lambda: None, lambda _: True, store.playback_target,
            )
            texts = []
            def walk(control):
                value = getattr(control, "value", None)
                button_text = getattr(control, "text", None)
                if value:
                    texts.append(value)
                if button_text:
                    texts.append(button_text)
                for child in getattr(control, "controls", []) or []:
                    walk(child)
                if getattr(control, "content", None) is not None:
                    walk(control.content)
            walk(view)
            self.assertIn("ESPECIAIS", texts)
            self.assertIn("OVA", texts)

    def test_catalog_orders_seasons_and_episodes_deterministically(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('catalog-order', {'title': 'Catalog Order', 'genres': '[]'})
            store.upsert_episode(anime, '/library/s2-02.mkv', 'Episode 02.mkv', 2, 2)
            store.upsert_episode(anime, '/library/s1-02.mkv', 'Episode 02.mkv', 1, 2)
            store.upsert_episode(anime, '/library/s1-01.mkv', 'Episode 01.mkv', 1, 1)
            store.upsert_episode(anime, '/library/s2-01.mkv', 'Episode 01.mkv', 2, 1)
            catalog = store.catalog()[0]
            self.assertEqual([season['season'] for season in catalog['seasons']], [1, 2])
            self.assertEqual([episode['number'] for episode in catalog['seasons'][0]['episodes']], [1, 2])
            self.assertEqual([episode['number'] for episode in catalog['seasons'][1]['episodes']], [1, 2])

    def test_playback_target_respects_season_order_when_numbers_repeat(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('seasons', {'title': 'Seasons', 'genres': '[]'})
            s1 = '/library/season1-01.mkv'
            s2 = '/library/season2-01.mkv'
            store.upsert_episode(anime, s1, 'Season 1 - 01.mkv', 1, 1)
            store.upsert_episode(anime, s2, 'Season 2 - 01.mkv', 2, 1)
            store.save_progress(s1, 95, 100)
            target = store.playback_target(anime)
            self.assertEqual(target['path'], s2)
            self.assertEqual(store.previous_episode(s2)['path'], s1)

    def test_adjacent_episode_orders_unparsed_numbers_by_filename(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('unparsed-order', {'title': 'Unparsed', 'genres': '[]'})
            first = '/library/episode-alpha.mkv'
            second = '/library/episode-beta.mkv'
            store.upsert_episode(anime, first, 'Episode Alpha', 1, None)
            store.upsert_episode(anime, second, 'Episode Beta', 1, None)
            self.assertEqual(store.next_episode(first)['path'], second)
            self.assertEqual(store.previous_episode(second)['path'], first)
    def test_browse_recently_played_ignores_missing_episode_history(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            first = store.upsert_anime('recent-a', {'title': 'Recent A', 'genres': '[]'})
            second = store.upsert_anime('recent-b', {'title': 'Recent B', 'genres': '[]'})
            a_available = '/library/recent-a-available.mkv'
            a_missing = '/library/recent-a-missing.mkv'
            b_available = '/library/recent-b-available.mkv'
            store.upsert_episode(first, a_available, 'A Available', 1, 1)
            store.upsert_episode(first, a_missing, 'A Missing', 1, 2)
            store.upsert_episode(second, b_available, 'B Available', 1, 1)
            store.save_progress(a_available, 20, 100)
            store.save_progress(a_missing, 20, 100)
            store.save_progress(b_available, 20, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (a_missing,))
                con.execute('UPDATE episodes SET last_played_at=100 WHERE path=?', (a_available,))
                con.execute('UPDATE episodes SET last_played_at=300 WHERE path=?', (a_missing,))
                con.execute('UPDATE episodes SET last_played_at=200 WHERE path=?', (b_available,))
            catalog = store.catalog()
            ordered = LibraryService.browse_catalog(catalog, sort='Assistidos recentemente')
            self.assertEqual([item['main_title'] for item in ordered], ['Recent B', 'Recent A'])

    def test_continue_watching_ignores_missing_history_for_recency(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            first = store.upsert_anime('continue-a', {'title': 'Continue A', 'genres': '[]'})
            second = store.upsert_anime('continue-b', {'title': 'Continue B', 'genres': '[]'})
            a_available = '/library/continue-a-01.mkv'
            a_missing = '/library/continue-a-02.mkv'
            b_available = '/library/continue-b-01.mkv'
            store.upsert_episode(first, a_available, 'A 01', 1, 1)
            store.upsert_episode(first, a_missing, 'A 02', 1, 2)
            store.upsert_episode(second, b_available, 'B 01', 1, 1)
            store.save_progress(a_available, 20, 100)
            store.save_progress(a_missing, 20, 100)
            store.save_progress(b_available, 20, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (a_missing,))
                con.execute('UPDATE episodes SET last_played_at=100 WHERE path=?', (a_available,))
                con.execute('UPDATE episodes SET last_played_at=300 WHERE path=?', (a_missing,))
                con.execute('UPDATE episodes SET last_played_at=200 WHERE path=?', (b_available,))
            items = store.continue_watching(limit=8)
            self.assertEqual([item['anime_title'] for item in items], ['Continue B', 'Continue A'])

    def test_details_marks_missing_episode_unplayable(self):
        anime = {'id': 'details-missing', 'main_title': 'Details Missing', 'meta': {'title': 'Details Missing'},
                 'favorite': False, 'genres': [], 'seasons': [{'season_name': 'Temporada 1', 'season': 1,
                 'episodes': [{'title': 'Episódio 1', 'path': 'content://episode/1', 'season': 1, 'number': 1,
                               'progress': 0, 'watched': False, 'missing': True}]}]}
        page = self.FakePage()
        played = []
        view = DetailView.build(page, anime, lambda *args, **kwargs: played.append(args), lambda: None, lambda _: True, lambda _: None)
        missing_cards = []
        def walk(control):
            if getattr(control, 'opacity', None) == .58 and hasattr(control, 'content'):
                missing_cards.append(control)
            for child in getattr(control, 'controls', []) or []:
                walk(child)
            child = getattr(control, 'content', None)
            if child is not None:
                walk(child)
        walk(view)
        self.assertEqual(len(missing_cards), 1)
        self.assertIsNone(missing_cards[0].on_click)
        self.assertEqual(played, [])

    def test_favorite_persists_after_store_reopen_and_catalog_filter(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('favorite', {'title': 'Favorite', 'genres': '[]'})
            store.upsert_episode(anime, '/library/favorite-01.mkv', 'Favorite - 01.mkv', 1, 1)
            self.assertTrue(store.toggle_favorite(anime))
            reopened = LibraryStore(d)
            catalog = reopened.catalog(favorites_only=True)
            self.assertEqual(len(catalog), 1)
            self.assertEqual(catalog[0]['id'], anime)
            self.assertTrue(catalog[0]['favorite'])
            self.assertTrue(reopened.is_favorite(anime))

    def test_catalog_persists_resume_and_next_episode_after_reopen(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('resume', {'title': 'Resume', 'genres': '[]'})
            first = '/library/resume-01.mkv'
            second = '/library/resume-02.mkv'
            store.upsert_episode(anime, first, 'Resume - 01.mkv', 1, 1)
            store.upsert_episode(anime, second, 'Resume - 02.mkv', 1, 2)
            store.save_progress(first, 40, 100)

            reopened = LibraryStore(d)
            catalog = reopened.catalog()[0]
            self.assertEqual(catalog['current_episode']['path'], first)
            self.assertEqual(catalog['current_episode']['progress'], 40)
            self.assertEqual(reopened.playback_target(anime)['path'], first)

            reopened.save_progress(first, 95, 100)
            self.assertEqual(reopened.playback_target(anime)['path'], second)

    def test_playback_target_prefers_partial_then_next_available_after_completion(self):
        with tempfile.TemporaryDirectory() as d:
            store, anime, paths = self._store_with_episodes(d)
            store.save_progress(paths[1], 20, 100)
            self.assertEqual(store.playback_target(anime)['path'], paths[1])
            store.save_progress(paths[1], 95, 100)
            self.assertEqual(store.playback_target(anime)['path'], paths[2])
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (paths[2],))
            self.assertEqual(store.playback_target(anime)['path'], paths[0])

    def test_catalog_details_fields_keep_local_and_anilist_counts_distinct(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('metadata', {
                'title': 'Metadata', 'genres': '["Drama"]', 'episodes_count': 12,
                'year': 2024, 'score': 84, 'status': 'RELEASING',
            })
            store.upsert_episode(anime, '/library/metadata-1.mkv', 'Metadata - 01.mkv', 1, 1)
            catalog = store.catalog()[0]
            self.assertEqual(catalog['meta']['episodes_count'], 12)
            self.assertEqual(len(catalog['seasons'][0]['episodes']), 1)
            self.assertEqual(catalog['seasons'][0]['episodes'][0]['number'], 1)
            self.assertEqual(catalog['seasons'][0]['season'], 1)


    def test_library_sync_keeps_multiple_saf_sources_independent_and_preserves_progress(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            service = LibraryService(store)
            first_tree = 'content://tree/library-a'
            second_tree = 'content://tree/library-b'
            first = {'uri': 'content://document/a-1', 'name': 'Naruto - 001.mkv'}
            second = {'uri': 'content://document/b-1', 'name': 'Naruto - 002.mkv'}
            with patch.object(service.anilist, 'search', return_value=[]):
                service.ingest_documents(first_tree, [first], folder_name='Biblioteca A')
                service.ingest_documents(second_tree, [second], folder_name='Biblioteca B')
            store.save_progress(first['uri'], 35, 100)

            with patch.object(service.anilist, 'search', return_value=[]):
                service.ingest_documents(first_tree, [], folder_name='Biblioteca A')

            episodes = {
                episode['path']: episode
                for anime in store.catalog()
                for season in anime['seasons']
                for episode in season['episodes']
            }
            self.assertTrue(episodes[first['uri']]['missing'])
            self.assertFalse(episodes[second['uri']]['missing'])
            self.assertEqual(episodes[first['uri']]['progress'], 35)
            self.assertEqual(len(store.folders()), 2)

            store.remove_folder(first_tree)
            self.assertEqual({folder['path'] for folder in store.folders()}, {second_tree})
            reopened = LibraryStore(d)
            episodes = {
                episode['path']: episode
                for anime in reopened.catalog()
                for season in anime['seasons']
                for episode in season['episodes']
            }
            self.assertTrue(episodes[first['uri']]['missing'])
            self.assertFalse(episodes[second['uri']]['missing'])
            self.assertEqual(episodes[first['uri']]['progress'], 35)

    def test_library_sync_retains_folder_account_ownership_across_account_changes(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            first_tree = 'content://tree/account-a'
            second_tree = 'content://tree/account-b'
            store.save_account({'id': 'google-a', 'email': 'a@example.com'})
            store.add_folder(first_tree, name='A', kind='saf', account_id=store.account()['id'])
            store.save_account({'id': 'google-b', 'email': 'b@example.com'})
            store.add_folder(second_tree, name='B', kind='saf', account_id=store.account()['id'])

            folders = {folder['path']: folder for folder in store.folders()}
            self.assertEqual(folders[first_tree]['account_id'], 'google-a')
            self.assertEqual(folders[second_tree]['account_id'], 'google-b')
            self.assertEqual(store.account()['id'], 'google-b')



class DetailsViewTests(unittest.TestCase):
    class FakePage:
        def __init__(self):
            self.updates = 0
            self.snack_bar = None
        def update(self):
            self.updates += 1

    def _build(self, anime, target=None):
        from views.details_view import DetailView
        played = []
        page = self.FakePage()
        view = DetailView.build(
            page, anime,
            lambda path, title, **kwargs: played.append((path, title, kwargs)),
            lambda: None, lambda _: True, lambda _: target,
        )
        return page, view, played

    def test_builds_for_anime_without_metadata_or_episodes(self):
        _, view, _ = self._build({'id': 1, 'main_title': 'Arquivo local', 'meta': {}, 'seasons': []})
        self.assertEqual(view.content.controls[0].__class__.__name__, 'Row')
        self.assertIn('Nenhum episódio', view.content.controls[-1].controls[0].content.value)

    def test_builds_complete_metadata_and_uses_local_episode_uri(self):
        episode = {'path': 'content://document/episode-1', 'title': 'Anime - 01.mkv', 'season': 1,
                   'number': 1, 'progress': 30, 'duration': 100, 'watched': False, 'missing': False}
        anime = {
            'id': 2, 'main_title': 'Anime', 'favorite': False, 'genres': ['Ação'],
            'meta': {'title': 'Anime', 'english': 'Anime English', 'year': 2024, 'status': 'RELEASING',
                     'episodes_count': 12, 'score': 84, 'description': 'Descrição local em cache.'},
            'current_episode': episode, 'seasons': [{'season_name': 'Temporada 1', 'season': 1, 'episodes': [episode]}],
        }
        _, view, played = self._build(anime, episode)
        def walk(control):
            yield control
            for child in getattr(control, 'controls', []) or []:
                yield from walk(child)
            content = getattr(control, 'content', None)
            if content is not None:
                yield from walk(content)

        primary = next(item for item in walk(view) if item.__class__.__name__ == 'FilledButton')
        primary.on_click(None)
        self.assertEqual(played[0][0], 'content://document/episode-1')
        self.assertEqual(played[0][2]['progress_seconds'], 30)
        self.assertEqual(anime['meta']['episodes_count'], 12)
        self.assertEqual(len(anime['seasons'][0]['episodes']), 1)

    def test_details_player_context_uses_anime_and_episode_label(self):
        episode = {'path': 'content://document/episode-2', 'title': 'Anime - 02.mkv', 'season': 1,
                   'number': 2, 'progress': 30, 'duration': 100, 'watched': False, 'missing': False}
        anime = {'id': 6, 'main_title': 'Anime', 'meta': {}, 'seasons': [
            {'season_name': 'Temporada 1', 'season': 1, 'episodes': [episode]}
        ]}
        _, view, played = self._build(anime, episode)
        def walk(control):
            yield control
            for child in getattr(control, 'controls', []) or []:
                yield from walk(child)
            content = getattr(control, 'content', None)
            if content is not None:
                yield from walk(content)
        primary = next(item for item in walk(view) if item.__class__.__name__ == 'FilledButton')
        primary.on_click(None)
        self.assertEqual(
            played[0],
            ('content://document/episode-2', 'Anime • T1 E2', {
                'progress_seconds': 30,
                'episode_id': None,
                'anime_id': 6,
            }),
        )

    def test_details_marks_next_unwatched_episode_after_completion(self):
        completed = {'path': 'content://document/episode-1', 'title': 'Anime - 01.mkv', 'season': 1,
                     'number': 1, 'progress': 100, 'duration': 100, 'watched': True, 'missing': False,
                     'last_played_at': 10}
        next_episode = {'path': 'content://document/episode-2', 'title': 'Anime - 02.mkv', 'season': 1,
                        'number': 2, 'progress': 0, 'duration': 100, 'watched': False, 'missing': False}
        anime = {'id': 4, 'main_title': 'Anime', 'meta': {}, 'seasons': [
            {'season_name': 'Temporada 1', 'season': 1, 'episodes': [completed, next_episode]}
        ]}
        _, view, _ = self._build(anime, next_episode)
        def walk(control):
            yield control
            for child in getattr(control, 'controls', []) or []:
                yield from walk(child)
            content = getattr(control, 'content', None)
            if content is not None:
                yield from walk(content)
        primary = next(item for item in walk(view) if item.__class__.__name__ == 'FilledButton')
        self.assertEqual(getattr(primary.content, 'value', primary.content), 'Próximo episódio')

    def test_details_season_picker_shows_local_availability(self):
        local = {'path': 'content://document/episode-1', 'title': 'Anime - 01.mkv', 'season': 1,
                 'number': 1, 'progress': 0, 'duration': 0, 'watched': False, 'missing': False}
        missing = {'path': 'content://document/episode-2', 'title': 'Anime - 02.mkv', 'season': 1,
                   'number': 2, 'progress': 0, 'duration': 0, 'watched': False, 'missing': True}
        anime = {'id': 5, 'main_title': 'Anime', 'meta': {}, 'seasons': [
            {'season_name': 'Temporada 1', 'season': 1, 'episodes': [local, missing]}
        ]}
        _, view, _ = self._build(anime, local)
        def walk(control):
            yield control
            for child in getattr(control, 'controls', []) or []:
                yield from walk(child)
            content = getattr(control, 'content', None)
            if content is not None:
                yield from walk(content)
        picker = next(item for item in walk(view) if item.__class__.__name__ == 'Dropdown')
        self.assertEqual(picker.options[0].text, 'Temporada 1 • 1/2 locais')

    def test_missing_episode_is_not_clickable(self):
        missing = {'path': '/library/missing.mkv', 'title': 'Missing', 'season': 1, 'number': 1,
                   'progress': 20, 'duration': 100, 'watched': False, 'missing': True}
        anime = {'id': 3, 'main_title': 'Missing', 'meta': {}, 'seasons': [{'season_name': 'Temporada 1', 'episodes': [missing]}]}
        _, view, _ = self._build(anime)
        self.assertIsNone(view.content.controls[-1].controls[0].on_click)


    def test_details_season_picker_switches_to_selected_season(self):
        first = {'path': 'content://document/s1-01', 'title': 'Anime - S1E01', 'season': 1,
                 'number': 1, 'progress': 0, 'duration': 100, 'watched': False, 'missing': False}
        second = {'path': 'content://document/s2-01', 'title': 'Anime - S2E01', 'season': 2,
                  'number': 1, 'progress': 0, 'duration': 100, 'watched': False, 'missing': False}
        anime = {'id': 7, 'main_title': 'Anime', 'meta': {}, 'seasons': [
            {'season_name': 'Temporada 1', 'season': 1, 'episodes': [first]},
            {'season_name': 'Temporada 2', 'season': 2, 'episodes': [second]},
        ]}
        _, view, _ = self._build(anime, first)

        def walk(control):
            yield control
            for child in getattr(control, 'controls', []) or []:
                yield from walk(child)
            content = getattr(control, 'content', None)
            if content is not None:
                yield from walk(content)

        picker = next(item for item in walk(view) if item.__class__.__name__ == 'Dropdown')
        episode_controls = [item for item in view.content.controls[-1].controls
                            if item.__class__.__name__ in {'Container', 'OutlinedButton'}]
        self.assertEqual(
            [item.content.controls[1].value for item in episode_controls],
            ['Anime - S1E01'],
        )
        picker.value = '1'
        picker.on_select(type('Event', (), {'control': picker})())
        episode_column = view.content.controls[-1]
        self.assertEqual(episode_column.controls[0].content.controls[1].value, 'Anime - S2E01')

    def test_details_episode_cards_expose_watched_progress_and_available_states(self):
        watched = {'path': 'content://document/watched', 'title': 'Watched', 'season': 1,
                   'number': 1, 'progress': 100, 'duration': 100, 'watched': True, 'missing': False}
        active = {'path': 'content://document/active', 'title': 'Active', 'season': 1,
                  'number': 2, 'progress': 25, 'duration': 100, 'watched': False, 'missing': False}
        available = {'path': 'content://document/available', 'title': 'Available', 'season': 1,
                     'number': 3, 'progress': 0, 'duration': 0, 'watched': False, 'missing': False}
        anime = {'id': 8, 'main_title': 'Anime', 'meta': {}, 'seasons': [
            {'season_name': 'Temporada 1', 'season': 1, 'episodes': [watched, active, available]}
        ]}
        _, view, _ = self._build(anime, active)
        cards = view.content.controls[-1].controls
        self.assertEqual(len(cards), 3)
        self.assertIn('Assistido', cards[0].content.controls[2].value)
        self.assertIn('Em andamento', cards[1].content.controls[2].value)
        self.assertIn('Disponível localmente', cards[2].content.controls[2].value)
        self.assertEqual(cards[1].content.controls[-1].value, 0.25)
        self.assertIsNotNone(cards[0].on_click)
        self.assertIsNotNone(cards[1].on_click)
        self.assertIsNotNone(cards[2].on_click)

    def test_current_episode_and_navigation_cross_seasons_after_completion(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('cross-season', {'title': 'Cross Season', 'genres': '[]'})
            first = '/library/s1-01.mkv'
            second = '/library/s2-01.mkv'
            store.upsert_episode(anime, first, 'S1 01', 1, 1)
            store.upsert_episode(anime, second, 'S2 01', 2, 1)
            store.save_progress(first, 95, 100)
            current = store.current_episode(anime)
            self.assertEqual(current['path'], second)
            self.assertEqual(store.next_episode(first)['path'], second)
            self.assertEqual(store.previous_episode(second)['path'], first)



class OrganizeTests(unittest.TestCase):
    class FakePage(AsyncRunTaskMixin):
        def update(self): pass
        def run_thread(self, work): work()

    def _catalog(self, directory):
        store = LibraryStore(directory)
        action = store.upsert_anime('action', {'title': 'Action', 'genres': '["Ação", "Fantasia"]'})
        comedy = store.upsert_anime('comedy', {'title': 'Comedy', 'genres': '["Comédia", "Fantasia"]'})
        plain = store.upsert_anime('plain', {'title': 'Plain', 'genres': '[]'})
        paths = []
        for anime, name in ((action, 'action'), (comedy, 'comedy'), (plain, 'plain')):
            path = f'/library/{name}.mkv'
            store.upsert_episode(anime, path, f'{name} - 01.mkv', 1, 1)
            paths.append(path)
        return store, action, comedy, plain, paths

    def test_organize_summary_empty_and_uses_only_real_genres(self):
        empty = LibraryService.organize_summary([])
        self.assertEqual(empty['genres'], [])
        self.assertEqual(
            [(item['name'], item['count']) for item in empty['states']],
            [('Todos', 0), ('Favoritos', 0), ('Em andamento', 0), ('Concluídos', 0)],
        )
        self.assertTrue(empty['collections'])
        self.assertTrue(all(int(item['count']) == 0 for item in empty['collections']))
        with tempfile.TemporaryDirectory() as d:
            store, *_ = self._catalog(d)
            genres = LibraryService.organize_summary(store.catalog())['genres']
            self.assertEqual([(item['name'], item['count']) for item in genres],
                             [('Ação', 1), ('Comédia', 1), ('Fantasia', 2)])
            self.assertNotIn('Drama', [item['name'] for item in genres])

    def test_browse_completed_ignores_missing_local_files(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('completed-missing', {'title': 'Completed Missing', 'genres': '[]'})
            available = '/library/available.mkv'
            missing = '/library/missing.mkv'
            store.upsert_episode(anime, available, 'Available', 1, 1)
            store.upsert_episode(anime, missing, 'Missing', 1, 2)
            store.save_progress(available, 95, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (missing,))
            catalog = store.catalog()
            self.assertEqual([item['id'] for item in LibraryService.browse_catalog(catalog, state='Concluídos')], [anime])

    def test_catalog_current_episode_ignores_missing_completed_history(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('catalog-missing', {'title': 'Catalog Missing', 'genres': '[]'})
            first = '/library/catalog-01.mkv'
            second = '/library/catalog-02.mkv'
            store.upsert_episode(anime, first, 'Catalog 01', 1, 1)
            store.upsert_episode(anime, second, 'Catalog 02', 1, 2)
            store.save_progress(first, 95, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (first,))
            catalog = store.catalog()
            entry = next(item for item in catalog if item['id'] == anime)
            self.assertEqual(entry['current_episode']['path'], second)
            self.assertEqual(entry['current_episode']['progress'], 0)

    def test_resume_ignores_completed_episode_that_is_now_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('resume-missing', {'title': 'Resume Missing', 'genres': '[]'})
            first = '/library/resume-01.mkv'
            second = '/library/resume-02.mkv'
            store.upsert_episode(anime, first, 'Resume 01', 1, 1)
            store.upsert_episode(anime, second, 'Resume 02', 1, 2)
            store.save_progress(first, 95, 100)
            store.save_progress(second, 10, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (first,))
            target = store.playback_target(anime)
            self.assertEqual(target['path'], second)
            self.assertEqual(target['progress'], 10)

    def test_continue_watching_ignores_missing_completed_history(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('history-missing', {'title': 'History Missing', 'genres': '[]'})
            first = '/library/history-01.mkv'
            second = '/library/history-02.mkv'
            store.upsert_episode(anime, first, 'History 01', 1, 1)
            store.upsert_episode(anime, second, 'History 02', 1, 2)
            store.save_progress(first, 95, 100)
            store.save_progress(second, 0, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (first,))
            items = store.continue_watching()
            self.assertEqual(items, [])

    def test_continue_watching_moves_to_next_local_episode_after_completion(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('continue', {'title': 'Continue', 'genres': '[]'})
            first = '/library/continue-01.mkv'
            second = '/library/continue-02.mkv'
            missing = '/library/continue-03.mkv'
            store.upsert_episode(anime, first, 'Continue 01', 1, 1)
            store.upsert_episode(anime, second, 'Continue 02', 1, 2)
            store.upsert_episode(anime, missing, 'Continue 03', 1, 3)
            store.save_progress(first, 95, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (missing,))
            items = store.continue_watching()
            self.assertEqual(items, [])
            self.assertEqual(store.next_episode(first)['path'], second)
            self.assertFalse(store.physical_row(second)['watched'])

    def test_final_completed_episode_does_not_wrap_to_first_episode(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('final', {'title': 'Final', 'genres': '[]'})
            first = '/library/final-01.mkv'
            last = '/library/final-02.mkv'
            store.upsert_episode(anime, first, 'Final 01', 1, 1)
            store.upsert_episode(anime, last, 'Final 02', 1, 2)
            store.save_progress(first, 95, 100)
            store.save_progress(last, 95, 100)
            self.assertIsNone(store.next_episode(last))
            self.assertEqual(store.playback_target(anime)['path'], first)

    def test_browse_search_uses_local_anime_aliases_without_network(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime('alias', {
                'title': 'Shingeki no Kyojin',
                'english': 'Attack on Titan',
                'romaji': 'Shingeki no Kyojin',
                'aliases': '["AOT", "進撃の巨人"]',
                'genres': '[]',
            })
            store.upsert_episode(anime, '/library/aot-01.mkv', 'AOT 01', 1, 1)
            catalog = store.catalog()
            self.assertEqual([item['id'] for item in LibraryService.browse_catalog(catalog, query='attack')], [anime])
            self.assertEqual([item['id'] for item in LibraryService.browse_catalog(catalog, query='進撃')], [anime])
            self.assertEqual(LibraryService.browse_catalog(catalog, query='one piece'), [])

    def test_organize_filters_reuse_favorites_progress_and_missing_rules(self):
        with tempfile.TemporaryDirectory() as d:
            store, action, comedy, plain, paths = self._catalog(d)
            store.toggle_favorite(action)
            store.save_progress(paths[0], 20, 100)
            store.save_progress(paths[1], 95, 100)
            with store._conn() as con:
                con.execute('UPDATE episodes SET missing=1 WHERE path=?', (paths[2],))
            catalog = store.catalog()
            service = LibraryService(store)
            self.assertEqual({a['id'] for a in service.browse_catalog(catalog, genre='Fantasia')}, {action, comedy})
            self.assertEqual([a['id'] for a in service.browse_catalog(catalog, state='Favoritos', genre='Ação')], [action])
            self.assertEqual([a['id'] for a in service.browse_catalog(catalog, state='Em andamento', genre='Ação')], [action])
            self.assertEqual([a['id'] for a in service.browse_catalog(catalog, state='Concluídos', genre='Comédia')], [comedy])
            self.assertEqual(service.browse_catalog(catalog, genre='Drama'), [])
            self.assertEqual(service.continue_watching()[0]['anime_id'], action)

    def test_organize_summary_persists_and_matches_home_catalog_rules(self):
        with tempfile.TemporaryDirectory() as d:
            store, action, *_ = self._catalog(d)
            store.toggle_favorite(action)
            reopened = LibraryStore(d)
            catalog = reopened.catalog()
            summary = LibraryService.organize_summary(catalog)
            self.assertEqual(summary['states'][1]['count'], len(LibraryService.browse_catalog(catalog, state='Favoritos')))
            self.assertEqual(summary['states'][0]['count'], len(catalog))
            self.assertEqual([item['name'] for item in summary['genres']], ['Ação', 'Comédia', 'Fantasia'])

    def test_organize_view_builds_empty_catalog_and_navigates_selected_anime(self):
        with tempfile.TemporaryDirectory() as d:
            empty = OrganizeView.build(self.FakePage(), LibraryService(LibraryStore(d)), lambda _: None, lambda: None, lambda: None)
            self.assertEqual(empty.content.controls[0].__class__.__name__, 'Row')
        with tempfile.TemporaryDirectory() as d:
            store, action, *_ = self._catalog(d)
            selected = []
            view = OrganizeView.build(self.FakePage(), LibraryService(store), selected.append, lambda: None, lambda: None)

            def walk(control):
                yield control
                for child in getattr(control, 'controls', []) or []:
                    yield from walk(child)
                content = getattr(control, 'content', None)
                if content is not None:
                    yield from walk(content)

            genre = next(item for item in walk(view) if item.__class__.__name__ == 'Container' and
                         item.on_click and item.content is not None and item.content.__class__.__name__ == 'Stack')
            result = genre.on_click(None)
            if hasattr(result, '__await__'):
                asyncio.run(result)

            def contains_value(control, expected):
                if getattr(control, 'value', None) == expected:
                    return True
                if any(contains_value(child, expected) for child in getattr(control, 'controls', []) or []):
                    return True
                child = getattr(control, 'content', None)
                return child is not None and contains_value(child, expected)

            card = next(item for item in walk(view) if item.__class__.__name__ == 'Container' and
                         item.on_click and item.content is not None and contains_value(item.content, 'Action'))
            card.on_click(None)
            self.assertEqual(selected[0]['id'], action)

    def test_organize_reopens_the_same_collection_state_after_details(self):
        with tempfile.TemporaryDirectory() as d:
            store, action, *_ = self._catalog(d)
            state = {'genre': 'Ação', 'state': 'Todos', 'sort': 'Nome A-Z', 'mode': 'collection'}
            view = OrganizeView.build(self.FakePage(), LibraryService(store), lambda _: None,
                                     lambda: None, lambda: None, view_state=state)
            def walk(control):
                yield control
                for child in getattr(control, 'controls', []) or []:
                    yield from walk(child)
                if getattr(control, 'content', None) is not None:
                    yield from walk(control.content)
            def contains_value(control, expected):
                if getattr(control, 'value', None) == expected:
                    return True
                if any(contains_value(child, expected) for child in getattr(control, 'controls', []) or []):
                    return True
                child = getattr(control, 'content', None)
                return child is not None and contains_value(child, expected)
            card = next(item for item in walk(view) if item.__class__.__name__ == 'Container' and
                         item.on_click and item.content is not None and contains_value(item.content, 'Action'))
            sort = next(item for item in walk(view) if item.__class__.__name__ == 'Dropdown')
            self.assertTrue(card.on_click)
            self.assertEqual(sort.value, 'Nome A-Z')

    def test_organize_view_action_triggers(self):
        page = self.FakePage()
        requested = [False]
        scanned = [False]
        def req():
            requested[0] = True
        def scn():
            scanned[0] = True

        with tempfile.TemporaryDirectory() as d:
            view = OrganizeView.build(
                page, LibraryService(LibraryStore(d)), lambda _: None, lambda: None, lambda: None,
                on_request_storage_access=req, on_scan_storage=scn
            )
            def walk(control):
                yield control
                for child in getattr(control, 'controls', []) or []:
                    yield from walk(child)
                if getattr(control, 'content', None) is not None:
                    yield from walk(control.content)

            header_row = view.content.controls[0]
            # Verify header action buttons exist
            icon_btns = [c for c in walk(header_row) if c.__class__.__name__ == 'IconButton']
            req_btn = next((b for b in icon_btns if getattr(b, 'tooltip', '') == 'Solicitar acesso ao armazenamento'), None)
            scn_btn = next((btn for btn in icon_btns if getattr(btn, "tooltip", "") == "Atualizar biblioteca"), None)
            self.assertIsNotNone(req_btn)
            self.assertIsNotNone(scn_btn)

            req_result = req_btn.on_click(None)
            self.assertTrue(asyncio.iscoroutine(req_result))
            asyncio.run(req_result)
            self.assertTrue(requested[0])

            scan_result = scn_btn.on_click(None)
            self.assertTrue(asyncio.iscoroutine(scan_result))
            asyncio.run(scan_result)
            self.assertTrue(scanned[0])

    def test_android_bridge_drains_independent_native_event_files_without_shared_lock(self):
        with tempfile.TemporaryDirectory() as d:
            bridge = AndroidBridge(d)
            queue = Path(d) / "reiflix-native-events"
            queue.mkdir()
            (queue / "event-2.json").write_text(
                json.dumps({"type": "second", "createdAt": 2}),
                encoding="utf-8",
            )
            (queue / "event-1.json").write_text(
                json.dumps({"type": "first", "createdAt": 1}),
                encoding="utf-8",
            )
            events = bridge.drain()
            self.assertEqual([event["type"] for event in events], ["first", "second"])
            self.assertEqual(list(queue.glob("event-*.json")), [])
            self.assertEqual(len(list(queue.glob("event-*.consumed"))), 2)
            bridge.acknowledge()
            self.assertEqual(list(queue.glob("*.consumed")), [])

    def test_android_bridge_can_claim_new_events_after_acknowledging_previous_batch(self):
        with tempfile.TemporaryDirectory() as d:
            bridge = AndroidBridge(d)
            queue = Path(d) / "reiflix-native-events"
            queue.mkdir()
            (queue / "event-one.json").write_text(json.dumps({"type": "one"}), encoding="utf-8")
            self.assertEqual([e["type"] for e in bridge.drain()], ["one"])
            (queue / "event-two.json").write_text(json.dumps({"type": "two"}), encoding="utf-8")
            self.assertEqual(bridge.drain(), [])
            bridge.acknowledge()
            self.assertEqual([e["type"] for e in bridge.drain()], ["two"])
            bridge.acknowledge()

class SafLibraryHardeningTests(unittest.TestCase):
    def test_saf_ingest_rejects_non_content_media_reference(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            service = LibraryService(store)
            catalog = service.ingest_documents(
                "content://tree/anime",
                [{"uri": "/storage/emulated/0/Anime/Naruto-001.mkv", "name": "Naruto-001.mkv"}],
            )
            self.assertEqual(catalog, [])
            self.assertEqual(store.library_summary()["episodes"], 0)
            self.assertIn("Referência local inválida", store.folders()[0]["last_error"])

    def test_catalog_exposes_episode_source_folder_for_multi_source_libraries(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            anime = store.upsert_anime("naruto", {"title": "Naruto", "genres": "[]"})
            uri = "content://document/naruto-001"
            tree = "content://tree/anime"
            store.upsert_episode(anime, uri, "Naruto - 001.mkv", 1, 1, source_folder=tree)
            episode = store.catalog()[0]["seasons"][0]["episodes"][0]
            self.assertEqual(episode["source_folder"], tree)

    def test_two_saf_sources_do_not_mark_each_other_missing(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            service = LibraryService(store)
            first_tree = "content://tree/one"
            second_tree = "content://tree/two"
            first = {"uri": "content://document/one", "name": "Naruto - 001.mkv"}
            second = {"uri": "content://document/two", "name": "Naruto - 002.mkv"}
            with patch.object(service.anilist, "search", return_value=[]):
                service.ingest_documents(first_tree, [first])
                service.ingest_documents(second_tree, [second])
                service.ingest_documents(first_tree, [first])
            rows = {
                ep["path"]: ep
                for anime in store.catalog()
                for season in anime["seasons"]
                for ep in season["episodes"]
            }
            self.assertFalse(rows[first["uri"]]["missing"])
            self.assertFalse(rows[second["uri"]]["missing"])
            self.assertEqual(rows[first["uri"]]["source_folder"], first_tree)
            self.assertEqual(rows[second["uri"]]["source_folder"], second_tree)

    
class PersistenceRecoveryTests(unittest.TestCase):
    def test_scan_runs_recover_as_interrupted_after_store_reopens(self):
        with tempfile.TemporaryDirectory() as d:
            first = LibraryStore(d)
            run_id = first.begin_scan()
            self.assertEqual(first.last_scan()["status"], "running")
            reopened = LibraryStore(d)
            scan = reopened.last_scan()
            self.assertEqual(scan["id"], run_id)
            self.assertEqual(scan["status"], "interrupted")
            self.assertIsNotNone(scan["finished_at"])
            self.assertEqual(len(reopened.interrupted_scans()), 1)

    def test_completed_scan_is_never_reclassified_as_interrupted(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            run_id = store.begin_scan()
            store.finish_scan(run_id, {
                "folders": 1, "files": 2, "videos": 1, "animes": 1,
                "episodes": 1, "errors": [],
            })
            reopened = LibraryStore(d)
            scan = reopened.last_scan()
            self.assertEqual(scan["status"], "completed")
            self.assertEqual(reopened.interrupted_scans(), [])

    def test_recover_interrupted_scans_finalizes_a_running_row(self):
        with tempfile.TemporaryDirectory() as d:
            store = LibraryStore(d)
            run_id = store.begin_scan()
            with store._conn() as con:
                con.execute("UPDATE scan_runs SET status='running',finished_at=NULL WHERE id=?", (run_id,))
            recovered = store.recover_interrupted_scans()
            self.assertEqual(recovered, 1)
            row = store.last_scan()
            self.assertEqual(row["status"], "interrupted")
            self.assertIsNotNone(row["finished_at"])

    def test_schema_migration_adds_scan_status_to_legacy_database(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "library.sqlite3"
            con = sqlite3.connect(path)
            con.execute("CREATE TABLE scan_runs (id INTEGER PRIMARY KEY, started_at REAL NOT NULL, finished_at REAL, folders INTEGER DEFAULT 0, files INTEGER DEFAULT 0, videos INTEGER DEFAULT 0, animes INTEGER DEFAULT 0, episodes INTEGER DEFAULT 0, errors TEXT NOT NULL DEFAULT '[]')")
            con.execute("INSERT INTO scan_runs(started_at) VALUES (123)")
            con.commit()
            con.close()
            store = LibraryStore(d)
            columns = {row[1] for row in store._conn().execute("PRAGMA table_info(scan_runs)")}
            self.assertIn("status", columns)
            self.assertEqual(store.last_scan()["status"], "interrupted")

if __name__ == '__main__':
    unittest.main()
