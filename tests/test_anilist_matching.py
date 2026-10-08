import tempfile
import unittest
from unittest.mock import patch

from core.library_service import LibraryService
from core.library_store import LibraryStore
from core.organizer_ai import AnimeOrganizer, normalize


class AniListMatchingHardeningTests(unittest.TestCase):
    def test_normalize_removes_common_filename_tokens(self):
        self.assertEqual(normalize("Naruto Shippuden S01 E001 1080p WEB-DL x265"), "naruto shippuden")

    def test_exact_alias_beats_a_similar_title(self):
        candidates = [
            {"id": 1, "title": {"romaji": "Naruto"}, "synonyms": ["Naruto"]},
            {"id": 2, "title": {"romaji": "Boruto: Naruto Next Generations"}, "synonyms": []},
        ]
        best, confident, ranked = AnimeOrganizer.choose("Naruto", candidates)
        self.assertEqual(best["id"], 1)
        self.assertTrue(confident)
        self.assertEqual(ranked[0]["match_score"], 1.0)

    def test_near_tied_candidates_require_manual_confirmation(self):
        candidates = [
            {"id": 1, "title": {"romaji": "Kanon"}, "synonyms": []},
            {"id": 2, "title": {"romaji": "Kanon"}, "synonyms": []},
        ]
        best, confident, ranked = AnimeOrganizer.choose("Kanon", candidates)
        self.assertIsNotNone(best)
        self.assertFalse(confident)
        self.assertGreaterEqual(ranked[0]["match_score"], AnimeOrganizer.AUTO_CONFIRM_SCORE)


class PendingMatchResolutionTests(unittest.TestCase):
    def test_explicit_pending_match_fetches_and_persists_selected_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            store.set_pending_match("attack", "Attack", [
                {"id": 10, "title": {"romaji": "Attack"}},
            ])
            media = {
                "id": 10,
                "title": {"english": "Attack", "romaji": "Attack", "native": "Attack"},
                "genres": ["Action"],
            }
            with patch.object(service.anilist, "by_id", return_value=media):
                metadata = service.resolve_match("attack", 10)
            self.assertEqual(metadata["anilist_id"], 10)
            self.assertEqual(store.association("attack"), 10)
            self.assertEqual(store.pending_matches(), [])
            self.assertEqual(store.anime_metadata("attack")["anilist_id"], 10)

    def test_explicit_resolution_rejects_anilist_id_not_in_remote_response(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            service = LibraryService(store)
            store.set_pending_match("attack", "Attack", [{"id": 10, "title": {"romaji": "Attack"}}])
            with patch.object(service.anilist, "by_id", return_value=None):
                with self.assertRaises(ValueError):
                    service.resolve_match("attack", 10)


if __name__ == "__main__":
    unittest.main()
