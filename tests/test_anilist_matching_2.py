import json
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError

from core.anilist import AniListClient
from core.library_parser import parse_video_path
from core.library_service import LibraryService
from core.library_store import LibraryStore
from core.organizer_ai import AnimeOrganizer, MatchContext, normalize


def media(anilist_id, title=None, *, english=None, romaji=None, native=None, synonyms=None,
          fmt="TV", year=None, genres=None):
    title = title or english or romaji or native or "Title"
    return {
        "id": anilist_id,
        "title": {
            "english": english or title,
            "romaji": romaji or title,
            "native": native,
        },
        "synonyms": list(synonyms or []),
        "format": fmt,
        "seasonYear": year,
        "genres": list(genres or []),
    }


class AniListMatching20Tests(unittest.TestCase):
    def test_unicode_normalization_preserves_native_script(self):
        self.assertEqual(normalize("進撃の巨人"), "進撃の巨人")

    def test_filename_parser_and_matcher_are_separate_but_composable(self):
        parsed = parse_video_path("[Group] One.Piece.S02E03.1080p.WEB-DL.x265.mkv")
        self.assertEqual(parsed.anime_title, "One Piece")
        best, confident, _ = AnimeOrganizer.choose(
            parsed.anime_title,
            [media(100, "One Piece", english="One Piece")],
            context=MatchContext(season_number=parsed.season, episode_type=parsed.episode_type),
        )
        self.assertEqual(best["id"], 100)
        self.assertTrue(confident)

    def test_synonym_match_is_strong(self):
        best, confident, ranked = AnimeOrganizer.choose(
            "AOT",
            [media(1, "Attack on Titan", synonyms=["AOT"])],
        )
        self.assertEqual(best["id"], 1)
        self.assertTrue(confident)
        self.assertIn("synonym_match", ranked[0]["match_reasons"])

    def test_japanese_native_title_matches_same_entity(self):
        best, confident, _ = AnimeOrganizer.choose(
            "進撃の巨人",
            [media(1, english="Attack on Titan", romaji="Shingeki no Kyojin", native="進撃の巨人")],
        )
        self.assertEqual(best["id"], 1)
        self.assertTrue(confident)

    def test_english_and_romaji_are_interchangeable(self):
        best, confident, _ = AnimeOrganizer.choose(
            "Boku no Hero Academia",
            [media(2, english="My Hero Academia", romaji="Boku no Hero Academia")],
        )
        self.assertEqual(best["id"], 2)
        self.assertTrue(confident)

    def test_release_noise_does_not_change_exact_title(self):
        best, confident, _ = AnimeOrganizer.choose(
            "[SubsPlease] My Hero Academia S07E01 1080p x265",
            [media(2, english="My Hero Academia", romaji="Boku no Hero Academia")],
        )
        self.assertEqual(best["id"], 2)
        self.assertTrue(confident)

    def test_season_context_prefers_matching_explicit_remote_season(self):
        candidates = [
            media(1, title="Show Season 1", fmt="TV"),
            media(2, title="Show Season 2", fmt="TV"),
        ]
        best, confident, ranked = AnimeOrganizer.choose(
            "Show S02",
            candidates,
            context=MatchContext(season_number=2),
        )
        self.assertEqual(best["id"], 2)
        self.assertTrue(confident)
        self.assertIn("season_match", ranked[0]["match_reasons"])

    def test_movie_format_beats_same_title_tv_candidate(self):
        candidates = [
            media(1, "Example", fmt="TV"),
            media(2, "Example Movie", fmt="MOVIE"),
        ]
        best, confident, ranked = AnimeOrganizer.choose(
            "Example Movie",
            candidates,
            context=MatchContext(media_kind="movie", episode_type="movie"),
        )
        self.assertEqual(best["id"], 2)
        self.assertTrue(confident)
        self.assertGreater(ranked[0]["match_score"], ranked[1]["match_score"])

    def test_movie_does_not_auto_merge_with_tv_when_scores_are_close(self):
        candidates = [
            media(1, "Example", fmt="TV"),
            media(2, "Example OVA", fmt="OVA"),
        ]
        best, confident, _ = AnimeOrganizer.choose(
            "Example Movie",
            candidates,
            context=MatchContext(media_kind="movie", episode_type="movie"),
        )
        self.assertFalse(confident)
        self.assertIsNotNone(best)

    def test_near_tie_is_ambiguous(self):
        candidates = [
            media(1, "Kanon"),
            media(2, "Kanon"),
        ]
        best, confident, ranked = AnimeOrganizer.choose("Kanon", candidates)
        self.assertIsNotNone(best)
        self.assertFalse(confident)
        self.assertEqual(ranked[0]["match_score"], ranked[1]["match_score"])

    def test_stable_secondary_sort_makes_ties_deterministic(self):
        candidates = [
            media(20, "Same"),
            media(10, "Same"),
        ]
        _, _, ranked = AnimeOrganizer.choose("Same", candidates)
        self.assertEqual([item["id"] for item in ranked], [10, 20])

    def test_local_anime_entity_persists_match_state_and_id(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime("local", {"title": "Local", "genres": "[]"})
            store.set_anilist_match("local", 42, status="matched", score=0.96, margin=0.21)
            match = store.anilist_match("local")
            self.assertEqual(match["id"], anime_id)
            self.assertEqual(match["anilist_id"], 42)
            self.assertEqual(match["anilist_match_status"], "matched")
            self.assertAlmostEqual(match["anilist_match_score"], 0.96)

    def test_not_found_is_distinct_from_network_error(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            store.upsert_anime("missing", {"title": "No Such Anime", "genres": "[]"})
            service = LibraryService(store)
            with patch.object(service.anilist, "search_detailed", return_value={"status": "ok", "results": []}):
                service.refresh_metadata("missing", "No Such Anime", force=True)
            self.assertEqual(store.anilist_match("missing")["anilist_match_status"], "not_found")

            store.upsert_anime("offline", {"title": "Offline", "genres": "[]"})
            with patch.object(service.anilist, "search_detailed", return_value={"status": "network_error", "results": []}):
                service.refresh_metadata("offline", "Offline", force=True)
            self.assertEqual(store.anilist_match("offline")["anilist_match_status"], "network_error")

    def test_rate_limit_is_distinct_and_does_not_delete_existing_match(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            store.upsert_anime("matched", {"title": "Matched", "genres": "[]", "anilist_id": 9}, source="anilist")
            store.set_anilist_match("matched", 9, status="matched", score=0.99, margin=0.4)
            service = LibraryService(store)
            with patch.object(service.anilist, "by_id", return_value=None):
                service.refresh_metadata("matched", "Matched", force=True)
            # Existing ID is kept when a refresh by ID cannot reach AniList.
            self.assertEqual(store.anilist_match("matched")["anilist_id"], 9)

    def test_manual_match_cannot_be_replaced_by_automatic_search(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            store.upsert_anime("manual", {"title": "Manual", "genres": "[]"})
            store.set_pending_match("manual", "Manual", [{"id": 10, "title": {"romaji": "Manual"}}])
            service = LibraryService(store)
            chosen = media(10, "Manual", genres=["Action"])
            with patch.object(service.anilist, "by_id", return_value=chosen):
                service.resolve_match("manual", 10)
            with patch.object(service.anilist, "search_detailed", side_effect=AssertionError("automatic search should not run")):
                with patch.object(service.anilist, "by_id", return_value=chosen):
                    result = service.refresh_metadata("manual", "Manual", force=True)
            self.assertEqual(result["anilist_id"], 10)
            self.assertTrue(store.anilist_match("manual")["anilist_match_manual"])
            self.assertEqual(store.anilist_match("manual")["anilist_match_status"], "manual")

    def test_unlink_returns_to_unmatched_without_deleting_local_entity(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime("show", {"title": "Show", "genres": "[]"})
            store.set_anilist_match("show", 77, status="manual", manual=True)
            service = LibraryService(store)
            service.unlink_match("show")
            self.assertEqual(store.anilist_match("show")["anilist_match_status"], "unmatched")
            self.assertIsNone(store.anilist_match("show")["anilist_id"])
            self.assertEqual(store.library_summary()["animes"], 1)
            self.assertEqual(anime_id, store.anime_metadata("show")["id"])

    def test_match_metadata_updates_genre_registry(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            store.upsert_anime("genre-show", {"title": "Genre Show", "genres": "[]"})
            service = LibraryService(store)
            candidate = media(88, "Genre Show", genres=["Action", "Fantasy"])
            with patch.object(service.anilist, "search_detailed", return_value={"status": "ok", "results": [candidate]}):
                service.refresh_metadata("genre-show", "Genre Show", force=True)
            names = {item["name"] for item in service.genre_options(include_unused=False)}
            self.assertTrue({"Action", "Fantasy"} <= names)

    def test_offline_persisted_metadata_is_reused_without_request(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            store.upsert_anime(
                "offline",
                {
                    "title": "Offline",
                    "romaji": "Offline",
                    "genres": json.dumps(["Action"]),
                    "anilist_id": 12,
                },
                source="anilist",
                status="available",
                fetched_at=1.0,
            )
            service = LibraryService(store)
            cached = store.anime_metadata("offline")
            with patch.object(service.anilist, "by_id", side_effect=AssertionError("no offline request")):
                result = service.refresh_metadata("offline", "Offline", force=False)
            self.assertEqual(result["anilist_id"], 12)
            self.assertEqual(result["title"], cached["title"])

    def test_parser_context_marks_movie_without_touching_storage_or_network(self):
        parsed = parse_video_path("My Film Movie.mkv")
        self.assertEqual(parsed.episode_type, "movie")
        self.assertIsNone(parsed.episode)


class AniListClientStatusTests(unittest.TestCase):
    def test_search_detailed_reports_network_error(self):
        client = AniListClient("/tmp/cache")
        with patch("core.anilist.urllib.request.urlopen", side_effect=URLError("offline")):
            result = client.search_detailed("Naruto")
        self.assertEqual(result["status"], "network_error")
        self.assertEqual(result["results"], [])

    def test_search_detailed_reports_ok_and_candidate_list(self):
        client = AniListClient("/tmp/cache")
        payload = {"data": {"Page": {"media": [media(1, "Naruto")]}}}
        fake = type("Response", (), {
            "__enter__": lambda self: self,
            "__exit__": lambda self, *args: None,
            "read": lambda self: json.dumps(payload).encode(),
            "headers": {},
        })()
        with patch("core.anilist.urllib.request.urlopen", return_value=fake):
            result = client.search_detailed("Naruto")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["results"][0]["id"], 1)


if __name__ == "__main__":
    unittest.main()
