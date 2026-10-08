import json
import tempfile
import unittest
from core.genre_classifier import GenreClassifier
from core.genre_registry import GenreRegistry, normalize_genre
from core.library_service import LibraryService
from core.library_store import LibraryStore


class GenreRegistryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)
        self.registry = self.service.genre_registry

    def tearDown(self):
        self.tmp.cleanup()

    def anime(self, lookup, title, genres=None):
        anime_id = self.store.upsert_anime(
            lookup, {"title": title, "genres": json.dumps(genres or [], ensure_ascii=False)}, source="local"
        )
        self.registry.sync_anime(anime_id, genres or [], source="local")
        self.store.upsert_episode(anime_id, f"content://{lookup}/1", f"{title} S01E01.mkv", 1, 1)
        return anime_id

    def test_normalization_and_explicit_alias_share_identity(self):
        action = self.registry.register("Action", source="system", is_system=True)
        self.registry.add_alias(action.id, "Ação")
        self.assertEqual(normalize_genre(" action  "), "action")
        self.assertEqual(self.registry.resolve("ACTION").id, action.id)
        self.assertEqual(self.registry.resolve("Ação").id, action.id)

    def test_no_fuzzy_merge_for_similar_genres(self):
        action = self.registry.register("Action")
        live = self.registry.register("Live Action")
        self.assertNotEqual(action.id, live.id)

    def test_unknown_genre_is_persisted_and_reopened(self):
        genre = self.registry.register("New Genre", source="anilist")
        reopened = GenreRegistry(LibraryStore(self.tmp.name))
        self.assertEqual(reopened.resolve("new genre").id, genre.id)

    def test_classifier_returns_only_real_inferences(self):
        self.assertEqual(GenreClassifier.classify("Completely Unknown Local Title"), [])
        self.assertIn("Ação", GenreClassifier.classify("Naruto"))

    def test_classifier_genre_resolves_to_registry_system_identity(self):
        anime_id = self.anime("naruto", "Naruto", GenreClassifier.classify("Naruto"))
        genres = self.registry.get_for_anime(anime_id)
        self.assertEqual([g.canonical_name for g in genres], ["Action"])
        self.assertEqual([g.id for g in genres], [self.registry.resolve("Ação").id])

    def test_multiple_genres_counts_distinct_anime(self):
        a = self.anime("a", "A", ["Action", "Fantasy"])
        b = self.anime("b", "B", ["Action", "Comedy"])
        c = self.anime("c", "C", ["Fantasy"])
        options = {x["name"]: x["count"] for x in self.registry.list_all(include_unused=False)}
        self.assertEqual(options["Action"], 2)
        self.assertEqual(options["Fantasy"], 2)
        self.assertEqual(options["Comedy"], 1)
        self.assertNotEqual(a, b)
        self.assertNotEqual(b, c)

    def test_filter_by_registry_id_and_alias(self):
        self.anime("a", "A", ["Action", "Fantasy"])
        self.anime("b", "B", ["Action", "Comedy"])
        self.anime("c", "C", ["Fantasy"])
        action = self.registry.resolve("Action")
        result = self.service.browse_catalog(self.service.catalog(), genre=action.id)
        self.assertEqual({x["main_title"] for x in result}, {"A", "B"})
        alias_result = self.service.browse_catalog(self.service.catalog(), genre="Ação")
        self.assertEqual({x["main_title"] for x in alias_result}, {"A", "B"})

    def test_intersection_is_supported_by_query_layer(self):
        self.anime("a", "A", ["Action", "Fantasy"])
        self.anime("b", "B", ["Action", "Comedy"])
        self.anime("c", "C", ["Fantasy"])
        catalog = self.service.catalog()
        action_id = self.registry.resolve("Action").id
        fantasy_id = self.registry.resolve("Fantasy").id
        both = [x for x in catalog if action_id in x["genre_ids"] and fantasy_id in x["genre_ids"]]
        self.assertEqual([x["main_title"] for x in both], ["A"])

    def test_empty_genre_does_not_create_unknown(self):
        anime_id = self.anime("empty", "No Genre", [])
        self.assertEqual(self.registry.get_for_anime(anime_id), [])
        self.assertIsNone(self.registry.resolve("Unknown"))

    def test_anilist_update_adds_and_removes_only_its_source(self):
        anime_id = self.anime("show", "Show", ["Action"])
        self.registry.sync_anime(anime_id, ["Action", "Fantasy"], source="anilist")
        self.registry.sync_anime(anime_id, ["Action"], source="anilist")
        names = {g.canonical_name for g in self.registry.get_for_anime(anime_id)}
        self.assertEqual(names, {"Action"})

    def test_custom_genre_is_separate_from_external_metadata(self):
        anime_id = self.anime("show", "Show", ["Action"])
        custom = self.registry.register("Minha coleção pessoal", source="user", is_custom=True)
        self.registry.attach(anime_id, custom.id)
        self.registry.sync_anime(anime_id, ["Action", "Fantasy"], source="anilist")
        names = {g.canonical_name for g in self.registry.get_for_anime(anime_id)}
        self.assertEqual(names, {"Action", "Fantasy", "Minha coleção pessoal"})

    def test_legacy_json_migrates_without_losing_library_state(self):
        with self.store._conn() as con:
            con.execute("UPDATE anime SET genres=? WHERE id=?", ('["Action","Adventure"]', self.anime("legacy", "Legacy", [])))
        reopened_store = LibraryStore(self.tmp.name)
        reopened_registry = GenreRegistry(reopened_store)
        names = {g.canonical_name for g in reopened_registry.get_for_anime(1)}
        self.assertEqual(names, {"Action", "Adventure"})
        self.assertEqual(reopened_store.library_summary()["animes"], 1)

    def test_offline_registry_does_not_call_anilist(self):
        self.anime("offline", "Offline", ["Action"])
        service = LibraryService(self.store)
        self.assertEqual({g.canonical_name for g in service.genre_registry.get_for_anime(1)}, {"Action"})

    def test_repeated_registration_is_idempotent(self):
        first = self.registry.register("Action")
        second = self.registry.register(" action ")
        third = self.registry.register("Ação")
        self.assertEqual(first.id, second.id)
        self.assertEqual(first.id, third.id)

    def test_details_and_organize_receive_same_registry_ids(self):
        self.anime("same", "Same", ["Action", "Fantasy"])
        item = self.service.catalog()[0]
        self.assertEqual(set(item["genre_ids"]), {self.registry.resolve("Action").id, self.registry.resolve("Fantasy").id})
        self.assertEqual(set(item["genres"]), {"Action", "Fantasy"})


if __name__ == "__main__":
    unittest.main()
