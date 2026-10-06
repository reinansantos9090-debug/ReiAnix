from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from core.library_service import LibraryService
from core.library_store import LibraryStore

import io
from PIL import Image


def _valid_jpeg_bytes():
    output = io.BytesIO()
    Image.new("RGB", (8, 8), (24, 48, 72)).save(output, format="JPEG", quality=85)
    return output.getvalue()


VALID_JPEG = _valid_jpeg_bytes()


class ProfessionalMetadataTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    def _anime(self, title="Attack on Titan", lookup="attack on titan"):
        return self.store.upsert_anime(lookup, {"title": title, "genres": "[]"}, source="local")

    def test_schema_and_metadata_model_are_persistent(self):
        self._anime()
        row = self.store.anime_metadata("attack on titan")
        self.assertEqual(self.store.SCHEMA_VERSION, 29)
        self.assertEqual(row["metadata_source"], "local")
        self.assertEqual(row["metadata_status"], "unresolved")
        self.assertEqual(row["metadata_confidence"], "low")
        self.assertIsNone(row["format"])
        reopened = LibraryStore(self.tmp.name).anime_metadata("attack on titan")
        self.assertEqual(reopened["metadata_status"], "unresolved")

    def test_offline_library_ingest_never_calls_anilist(self):
        with patch.object(self.service.anilist, "search", side_effect=AssertionError("network used")),              patch.object(self.service.anilist, "by_id", side_effect=AssertionError("network used")):
            catalog = self.service.ingest_documents(
                "content://offline",
                [{"uri": "content://offline/1", "name": "Show S01E01.mkv", "size": 10, "modifiedAt": 1}],
                source_kind="saf",
            )
        self.assertEqual(len(catalog), 1)
        self.assertEqual(catalog[0]["seasons"][0]["episodes"][0]["number"], 1)
        self.assertEqual(catalog[0]["meta"]["metadata_status"], "unresolved")

    def test_anilist_refresh_normalizes_and_caches(self):
        self._anime()
        media = {
            "id": 16498,
            "title": {"english": "Attack on Titan", "romaji": "Shingeki no Kyojin", "native": "進撃の巨人"},
            "synonyms": ["AOT"],
            "description": "A safe synopsis.",
            "genres": ["Action", "Drama"],
            "seasonYear": 2013,
            "episodes": 25,
            "duration": 24,
            "averageScore": 86,
            "studios": {"nodes": [{"name": "WIT Studio"}]},
        }
        with patch.object(self.service.anilist, "search", return_value=[media]):
            refreshed = self.service.refresh_metadata("attack on titan", "Attack on Titan", force=True)
        self.assertEqual(refreshed["anilist_id"], 16498)
        self.assertEqual(refreshed["metadata_source"], "anilist")
        self.assertEqual(refreshed["metadata_status"], "available")
        self.assertEqual(refreshed["metadata_confidence"], "high")
        self.assertEqual(self.store.association("attack on titan"), 16498)
        self.assertEqual(self.store.anime_metadata("attack on titan")["english"], "Attack on Titan")

    def test_multiple_candidates_are_not_auto_associated(self):
        self._anime("Kanon", "kanon")
        candidates = [
            {"id": 1, "title": {"romaji": "Kanon"}, "synonyms": []},
            {"id": 2, "title": {"romaji": "Kanon"}, "synonyms": []},
        ]
        with patch.object(self.service.anilist, "search", return_value=candidates):
            result = self.service.refresh_metadata("kanon", "Kanon", force=True)
        self.assertIsNone(result.get("anilist_id"))
        self.assertEqual(self.store.association("kanon"), None)
        self.assertEqual(self.store.anime_metadata("kanon")["metadata_status"], "ambiguous")
        self.assertTrue(self.store.pending_matches())

    def test_low_confidence_candidate_stays_unresolved(self):
        self._anime("My Local Show", "my local show")
        candidates = [{"id": 77, "title": {"romaji": "Completely Different"}, "synonyms": []}]
        with patch.object(self.service.anilist, "search", return_value=candidates):
            result = self.service.refresh_metadata("my local show", "My Local Show", force=True)
        self.assertIsNone(result.get("anilist_id"))
        self.assertEqual(self.store.association("my local show"), None)
        self.assertEqual(result["metadata_status"], "unresolved")

    def test_offline_refresh_preserves_cached_metadata(self):
        self._anime()
        with self.store._conn() as con:
            con.execute(
                "UPDATE anime SET anilist_id=?,metadata_source='anilist',metadata_status='available',metadata_confidence='high',metadata_updated_at=? WHERE lookup_title=?",
                (16498, time.time() - 40 * 24 * 60 * 60, "attack on titan"),
            )
        with patch.object(self.service.anilist, "by_id", return_value=None):
            cached = self.service.refresh_metadata("attack on titan", "Attack on Titan", force=True)
        self.assertEqual(cached["anilist_id"], 16498)
        self.assertEqual(cached["metadata_status"], "stale")

    def test_partial_anilist_refresh_preserves_existing_editorial_fields(self):
        self._anime()
        with self.store._conn() as con:
            con.execute(
                "UPDATE anime SET anilist_id=?,description=?,year=?,format=?,genres=? WHERE lookup_title=?",
                (16498, "Cached description", 2013, "TV", "[\"Action\", \"Drama\"]", "attack on titan"),
            )
        media = {"id": 16498, "title": {"english": "Attack on Titan"}, "genres": ["Action"]}
        with patch.object(self.service.anilist, "by_id", return_value=media):
            refreshed = self.service.refresh_metadata("attack on titan", "Attack on Titan", force=True)
        self.assertEqual(refreshed["description"], "Cached description")
        self.assertEqual(refreshed["year"], 2013)
        self.assertEqual(refreshed["format"], "TV")
        self.assertEqual(refreshed["genres"], "[\"Action\"]")

    def test_manual_metadata_wins_over_anilist_refresh(self):
        self._anime()
        self.store.set_manual_metadata("attack on titan", {"title": "Meu Título", "description": "Minha descrição", "year": 9999})
        media = {
            "id": 16498,
            "title": {"english": "Attack on Titan", "romaji": "Shingeki no Kyojin", "native": "進撃の巨人"},
            "description": "Remote description",
            "genres": ["Action"],
            "seasonYear": 2013,
        }
        with patch.object(self.service.anilist, "search", return_value=[media]):
            refreshed = self.service.refresh_metadata("attack on titan", "Attack on Titan", force=True)
        self.assertEqual(refreshed["title"], "Meu Título")
        self.assertEqual(refreshed["description"], "Minha descrição")
        self.assertEqual(refreshed["year"], 9999)
        self.assertEqual(refreshed["genres"], "[\"Action\"]")
        self.assertEqual(refreshed["metadata_source"], "manual")

    def test_user_state_survives_metadata_merge(self):
        anime = self._anime()
        self.store.toggle_favorite(anime)
        self.store.toggle_pinned(anime)
        self.store.set_user_tags(anime, ["Favorito", "Rever"])
        self.store.set_personal_note(anime, "Minha nota")
        self.store.upsert_episode(anime, "content://x/1", "Show S01E01.mkv", 1, 1)
        self.store.save_progress("content://x/1", 45, 100)
        self.store.set_watched("content://x/1", True)
        media = {"id": 1, "title": {"english": "Attack on Titan", "romaji": "AOT"}, "genres": ["Action"]}
        with patch.object(self.service.anilist, "by_id", return_value=media):
            self.service.refresh_metadata("attack on titan", "Attack on Titan", force=True)
        row = self.store.catalog()[0]
        episode = row["seasons"][0]["episodes"][0]
        self.assertTrue(row["favorite"])
        self.assertTrue(row["is_pinned"])
        self.assertEqual(row["user_tags"], ["Favorito", "Rever"])
        self.assertEqual(row["personal_note"], "Minha nota")
        self.assertTrue(episode["watched"])
        self.assertEqual(episode["progress"], 100)

    def test_manual_identification_survives_metadata_refresh(self):
        anime = self._anime("Show", "show")
        self.store.upsert_episode(anime, "content://x/1", "Show S01E01.mkv", 1, 1)
        self.store.set_episode_identification("content://x/1", season=2, number=3, episode_type="special", title="Manual")
        media = {"id": 10, "title": {"english": "Show", "romaji": "Show"}, "genres": ["Action"]}
        with patch.object(self.service.anilist, "by_id", return_value=media):
            self.service.refresh_metadata("show", "Show", force=True)
        episode = self.store.physical_row("content://x/1")
        self.assertEqual((episode["season"], episode["number"], episode["episode_type"]), (2, 3, "special"))
        self.assertTrue(episode["manual_override"])

    def test_refresh_is_idempotent(self):
        self._anime()
        media = {"id": 16498, "title": {"english": "Attack on Titan", "romaji": "AOT"}, "genres": ["Action"]}
        with patch.object(self.service.anilist, "search", return_value=[media]) as search:
            first = self.service.refresh_metadata("attack on titan", "Attack on Titan", force=True)
            second = self.service.refresh_metadata("attack on titan", "Attack on Titan", force=True)
        self.assertEqual(first["anilist_id"], second["anilist_id"])
        self.assertEqual(self.store.association("attack on titan"), 16498)
        self.assertEqual(search.call_count, 1)

    def test_stale_state_is_read_only_and_does_not_fake_refresh(self):
        self._anime()
        with self.store._conn() as con:
            con.execute("UPDATE anime SET anilist_id=?,metadata_source='anilist',metadata_status='available',metadata_updated_at=? WHERE lookup_title=?",
                        (1, time.time() - 40 * 24 * 60 * 60, "attack on titan"))
        metadata = self.service._identify("attack on titan", "Attack on Titan", lambda _: None, allow_network=False)
        self.assertEqual(self.service.metadata_state(metadata), "stale")
        self.assertEqual(metadata["metadata_status"], "available")

    def test_movie_metadata_does_not_create_a_season(self):
        anime = self.store.upsert_anime("film", {"title": "Film", "media_kind": "movie", "genres": "[]"}, source="local")
        self.store.upsert_episode(anime, "content://film", "Film Movie.mkv", 0, None, episode_type="movie")
        catalog = self.store.catalog()[0]
        self.assertEqual(catalog["media_kind"], "movie")
        self.assertEqual(catalog["seasons"], [])
        self.assertEqual(len(catalog["media_files"]), 1)

    def test_unicode_metadata_round_trip(self):
        self._anime("進撃の巨人", "進撃の巨人")
        self.store.set_manual_metadata("進撃の巨人", {"title": "進撃の巨人", "description": "Descrição — 漢字"})
        reopened = LibraryStore(self.tmp.name).anime_metadata("進撃の巨人")
        self.assertEqual(reopened["title"], "進撃の巨人")
        self.assertIn("漢字", reopened["description"])

    def test_multiple_physical_files_share_one_logical_work(self):
        first = self.store.upsert_anime("one piece", {"title": "One Piece", "genres": "[]"}, source="local")
        second = self.store.upsert_anime("one piece", {"title": "One Piece", "genres": "[]"}, source="local")
        self.assertEqual(first, second)
        self.store.upsert_episode(first, "content://one/720", "One Piece S01E01 720p.mkv", 1, 1)
        self.store.upsert_episode(first, "content://one/1080", "One Piece S01E01 1080p.mkv", 1, 1)
        self.assertEqual(len(self.store.catalog()[0]["seasons"][0]["episodes"]), 2)

    def test_partial_scan_does_not_destroy_cached_metadata(self):
        self._anime()
        self.store.set_manual_metadata("attack on titan", {"title": "Título local"})
        catalog = self.service.ingest_documents(
            "content://partial",
            [{"uri": "content://partial/1", "name": "Attack on Titan S01E01.mkv", "size": 10, "modifiedAt": 1}],
            source_kind="saf",
            scan_errors=["provider timeout"],
        )
        self.assertEqual(catalog[0]["meta"]["title"], "Título local")
        self.assertFalse(self.store.physical_row("content://partial/1")["missing"])

    def test_rescan_and_restart_keep_metadata_and_playback_state(self):
        anime = self._anime()
        self.store.upsert_episode(anime, "content://restart/1", "Show S01E01.mkv", 1, 1)
        self.store.save_progress("content://restart/1", 25, 100)
        self.store.toggle_pinned(anime)
        self.store.set_manual_metadata("attack on titan", {"title": "Título persistente"})
        reopened = LibraryStore(self.tmp.name)
        catalog = reopened.catalog()[0]
        self.assertEqual(catalog["meta"]["title"], "Título persistente")
        self.assertTrue(catalog["is_pinned"])
        self.assertEqual(catalog["seasons"][0]["episodes"][0]["progress"], 25)

    def test_metadata_cache_hit_does_not_call_network(self):
        self._anime()
        with self.store._conn() as con:
            con.execute("UPDATE anime SET anilist_id=?,metadata_source='anilist',metadata_status='available',metadata_confidence='high',metadata_updated_at=? WHERE lookup_title=?",
                        (16498, time.time(), "attack on titan"))
        with patch.object(self.service.anilist, "by_id") as by_id:
            result = self.service.refresh_metadata("attack on titan", "Attack on Titan", force=False)
        by_id.assert_not_called()
        self.assertEqual(result["metadata_status"], "available")

    def test_first_hydration_resolves_local_title_and_caches_cover(self):
        anime = self._anime('Attack on Titan', 'attack on titan')
        self.store.upsert_episode(anime, 'content://hydrate/1', 'Attack on Titan S01E01.mkv', 1, 1)
        cover = __import__('pathlib').Path(self.tmp.name) / 'cover.jpg'
        cover.write_bytes(VALID_JPEG)
        media = {'id': 16498, 'title': {'english': 'Attack on Titan', 'romaji': 'Shingeki no Kyojin'},
                 'coverImage': {'extraLarge': 'https://img.example/a.jpg'}, 'genres': ['Action']}
        downloader = lambda url: (VALID_JPEG, "image/jpeg", 200)
        with patch.object(self.service.anilist, 'search', return_value=[media]), patch.object(self.service.artwork, '_downloader', side_effect=downloader) as artwork_downloader:
            result = self.service.hydrate_catalog_metadata(self.service.catalog())
        self.assertEqual(len(result), 1)
        row = self.store.anime_metadata('attack on titan')
        self.assertEqual(row['anilist_id'], 16498)
        self.assertNotEqual(row['cover_cache'], str(cover))
        self.assertTrue(Path(row['cover_cache']).is_file())
        self.assertEqual(self.store.association('attack on titan'), 16498)
        artwork_downloader.assert_called_once_with('https://img.example/a.jpg')

    def test_hydration_uses_existing_cover_without_http_download(self):
        anime = self._anime('Attack on Titan', 'attack on titan')
        cover = __import__('pathlib').Path(self.tmp.name) / 'cached.jpg'
        cover.write_bytes(VALID_JPEG)
        now = time.time()
        with self.store._conn() as con:
            con.execute(
                'UPDATE anime SET anilist_id=?,cover_url=?,cover_cache=?,metadata_status=?,metadata_source=?,metadata_updated_at=?,metadata_fetched_at=? WHERE id=?',
                (16498, 'https://img.example/a.jpg', str(cover), 'available', 'anilist', now, now, anime),
            )
        with patch.object(self.service.anilist, 'search') as search, patch.object(self.service.anilist, 'by_id') as by_id, patch.object(self.service.anilist, 'cache_cover') as downloader:
            self.service.hydrate_catalog_metadata(self.service.catalog())
        search.assert_not_called()
        by_id.assert_not_called()
        downloader.assert_not_called()

    def test_first_anilist_network_failure_is_persisted_and_not_retried_automatically(self):
        self._anime("First failure", "first failure")
        def offline_search(_title):
            self.service.anilist._last_request_status = "network_error"
            return []

        with patch.object(self.service.anilist, "search", side_effect=offline_search) as search:
            first = self.service.refresh_metadata("first failure", "First failure", force=True)
            self.assertEqual("unresolved", first["metadata_status"])
            second = self.service.hydrate_catalog_metadata(self.service.catalog())
        search.assert_called_once()
        self.assertEqual(len(second), 1)
        self.assertEqual(
            "network_error",
            self.store.anilist_match("first failure")["anilist_match_status"],
        )

    def test_materialized_metadata_never_auto_refreshes_when_stale(self):
        anime = self._anime("Attack on Titan", "attack on titan")
        now = time.time() - 45 * 24 * 60 * 60
        with self.store._conn() as con:
            con.execute(
                "UPDATE anime SET anilist_id=?,metadata_source='anilist',metadata_status='available',metadata_confidence='high',metadata_updated_at=?,metadata_fetched_at=?,description='Sinopse',description_original='Original' WHERE id=?",
                (16498, now, now, anime),
            )
        with patch.object(self.service.anilist, "search", side_effect=AssertionError("unexpected AniList search")),              patch.object(self.service.anilist, "by_id", side_effect=AssertionError("unexpected AniList by_id")):
            hydrated = self.service.hydrate_catalog_metadata(self.service.catalog())
        self.assertEqual(len(hydrated), 1)
        self.assertEqual(hydrated[0]["metadata"]["anilist_id"], 16498)

    def test_hydration_materializes_backdrop_once_and_reuses_it_offline(self):
        anime = self._anime("Backdrop Show", "backdrop show")
        self.store.upsert_episode(anime, "content://backdrop/1", "Backdrop Show S01E01.mkv", 1, 1)
        media = {
            "id": 200,
            "title": {"english": "Backdrop Show", "romaji": "Backdrop Show"},
            "coverImage": {"extraLarge": "https://img.example/poster.jpg"},
            "bannerImage": "https://img.example/backdrop.jpg",
            "description": "Original synopsis.",
        }

        def downloader(url):
            return VALID_JPEG, "image/jpeg", 200

        with patch.object(self.service.anilist, "search", return_value=[media]),              patch.object(self.service.anilist, "localize_description_to_pt_br", return_value="Original synopsis."),              patch.object(self.service.artwork, "_downloader", side_effect=downloader) as download:
            self.service.hydrate_catalog_metadata(self.service.catalog())
        self.assertEqual(download.call_count, 2)

        reopened = LibraryStore(self.tmp.name)
        reopened_service = LibraryService(reopened)
        try:
            with patch.object(reopened_service.anilist, "search", side_effect=AssertionError("offline search")),                  patch.object(reopened_service.anilist, "by_id", side_effect=AssertionError("offline by_id")):
                hydrated = reopened_service.hydrate_catalog_metadata(reopened_service.catalog())
            self.assertEqual(len(hydrated), 1)
            poster = reopened_service.artwork.resolve("anime", anime, "poster", allow_network=False)
            backdrop = reopened_service.artwork.resolve("anime", anime, "backdrop", allow_network=False)
            self.assertTrue(poster and Path(poster["local_path"]).is_file())
            self.assertTrue(backdrop and Path(backdrop["local_path"]).is_file())
        finally:
            reopened_service.artwork.shutdown()

    def test_description_original_and_localized_description_persist_separately(self):
        anime = self._anime("Localized Show", "localized show")
        with self.store._conn() as con:
            con.execute(
                "UPDATE anime SET anilist_id=?,metadata_source='anilist',metadata_status='available',metadata_fetched_at=?,description=?,description_original=? WHERE id=?",
                (321, time.time(), "The original description.", "The original description.", anime),
            )
        changed = self.service._persist_localized_description(
            "localized show",
            "The original description.",
            "A descrição em português.",
            local_anime_id=anime,
            source_language="en",
            from_cache=False,
        )
        self.assertTrue(changed)
        row = self.store.anime_metadata_by_id(anime)
        self.assertEqual(row["description_original"], "The original description.")
        self.assertEqual(row["description"], "A descrição em português.")

    def test_missing_cover_is_retried_only_after_existing_artwork_backoff(self):
        anime = self._anime('Attack on Titan', 'attack on titan')
        self.store.upsert_episode(anime, 'content://hydrate/2', 'Attack on Titan S01E02.mkv', 1, 2)
        now = time.time()
        with self.store._conn() as con:
            con.execute(
                'UPDATE anime SET anilist_id=?,cover_url=?,cover_cache=?,metadata_status=?,metadata_source=?,metadata_updated_at=?,metadata_fetched_at=? WHERE id=?',
                (16498, 'https://img.example/a.jpg', '', 'available', 'anilist', now, now, anime),
            )
        with patch.object(self.service.artwork, '_downloader', side_effect=TimeoutError("timeout")) as downloader:
            self.service.hydrate_catalog_metadata(self.service.catalog())
            self.service.hydrate_catalog_metadata(self.service.catalog())
        self.assertEqual(downloader.call_count, 1)

if __name__ == "__main__":
    unittest.main()
