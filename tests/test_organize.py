"""earlier validation stage 37 regression tests for dynamic Organize categories."""
import tempfile
import unittest
from pathlib import Path

from core.genre_registry import GenreRegistry
from core.library_service import LibraryService
from core.library_store import LibraryStore

ROOT = Path(__file__).resolve().parents[1]
ORGANIZE = ROOT / "views" / "organize_view.py"
STORE = ROOT / "core" / "library_store.py"
MAIN = ROOT / "main.py"


class SQLiteTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = LibraryStore(self.tmp.name)
        self.service = LibraryService(self.store)
        self.registry = GenreRegistry(self.store)

    def tearDown(self):
        self.tmp.cleanup()

    def add_anime(self, key, title, genres=()):
        anime_id = self.store.upsert_anime(
            key,
            {"title": title, "genres": list(genres), "media_kind": "series"},
        )
        self.registry.sync_anime(anime_id, genres, source="local")
        self.store.upsert_episode(
            anime_id,
            f"content://stage37/{key}/01",
            f"{title} S01E01.mkv",
            1,
            1,
        )
        return anime_id

    def genre_counts(self):
        return {
            item["name"]: item["count"]
            for item in self.store.organize_summary()["genres"]
        }

    def test_a_empty_library_has_no_genres_or_visible_categories(self):
        summary = self.store.organize_summary()
        self.assertEqual([], summary["genres"])
        self.assertTrue(all(item["count"] == 0 for item in summary["collections"]))

    def test_b_single_genre_has_one_real_category(self):
        self.add_anime("action", "Action One", ["Action"])
        self.assertEqual({"Action": 1}, self.genre_counts())

    def test_c_multiple_genres_have_real_counts(self):
        self.add_anime("a", "A", ["Action"])
        self.add_anime("b", "B", ["Comedy"])
        self.add_anime("c", "C", ["Action", "Comedy"])
        self.assertEqual({"Action": 2, "Comedy": 2}, self.genre_counts())

    def test_d_unused_registry_genres_do_not_enter_organize_summary(self):
        self.add_anime("a", "A", ["Action"])
        self.registry.register("Drama", source="local", is_custom=False)
        self.registry.register("Horror", source="local", is_custom=False)
        self.assertNotIn("Drama", self.genre_counts())
        self.assertNotIn("Horror", self.genre_counts())
        self.assertTrue(
            any(item["name"] == "Drama" and item["count"] == 0 for item in self.registry.list_all())
        )
        self.assertTrue(
            any(item["name"] == "Horror" and item["count"] == 0 for item in self.registry.list_all())
        )

    def test_e_duplicate_sources_count_an_anime_once(self):
        anime_id = self.store.upsert_anime(
            "dup",
            {"title": "Duplicate Genre", "genres": ["Action"]},
        )
        self.registry.sync_anime(anime_id, ["Action"], source="local")
        self.registry.sync_anime(anime_id, ["Action"], source="anilist")
        self.store.upsert_episode(
            anime_id,
            "content://stage37/dup/01",
            "Duplicate S01E01.mkv",
            1,
            1,
        )
        self.assertEqual({"Action": 1}, self.genre_counts())

    def test_f_anime_without_genre_does_not_create_placeholder(self):
        self.add_anime("plain", "Plain", [])
        self.assertEqual([], self.store.organize_summary()["genres"])

    def test_g_removed_episode_removes_genre_from_projection(self):
        anime_id = self.add_anime("removed", "Removed", ["Horror"])
        self.assertEqual({"Horror": 1}, self.genre_counts())
        with self.store._conn() as con:
            con.execute("DELETE FROM episodes WHERE anime_id=?", (anime_id,))
        self.assertNotIn("Horror", self.genre_counts())

    def test_h_stage35_out_of_scope_reconciliation_removes_genre_population(self):
        root = "content://com.android.externalstorage.documents/tree/primary%3AAnime"
        self.store.add_folder(
            root,
            name="Anime",
            kind="saf",
            authorization="granted",
            saf_authority="com.android.externalstorage.documents",
            saf_document_id="primary:Anime",
            saf_volume_id="primary",
            saf_identity="saf:com.android.externalstorage.documents:primary:Anime",
        )
        anime_id = self.store.upsert_anime(
            "outside",
            {"title": "Outside", "genres": ["Drama"], "media_kind": "series"},
        )
        self.registry.sync_anime(anime_id, ["Drama"], source="local")
        path = "/storage/emulated/0/WhatsApp/outside.mp4"
        self.store.upsert_episode(
            anime_id,
            path,
            "outside.mp4",
            1,
            1,
            source_folder="broad-storage",
            media_identity="shared:primary:WhatsApp/outside.mp4",
        )
        self.assertEqual({"Drama": 1}, self.genre_counts())
        report = self.service.reconcile_existing_library()
        self.assertEqual(1, report["removed"])
        self.assertNotIn("Drama", self.genre_counts())

    def test_i_normalization_keeps_case_variants_under_one_identity(self):
        anime_id = self.store.upsert_anime(
            "normalized",
            {"title": "Normalized", "genres": ["Action"]},
        )
        self.registry.sync_anime(
            anime_id,
            ["Action", " action ", "ACTION"],
            source="local",
        )
        self.store.upsert_episode(
            anime_id,
            "content://stage37/normalized/01",
            "Normalized S01E01.mkv",
            1,
            1,
        )
        counts = self.genre_counts()
        self.assertEqual(1, counts["Action"])
        self.assertNotIn("action", counts)

    def test_j_refresh_projection_tracks_current_database(self):
        anime_id = self.add_anime("refresh", "Refresh", ["Romance"])
        first = self.store.organize_summary()
        self.assertEqual({"Romance": 1}, self.genre_counts())
        with self.store._conn() as con:
            con.execute("DELETE FROM episodes WHERE anime_id=?", (anime_id,))
        second = self.store.organize_summary()
        self.assertEqual([], second["genres"])
        self.assertEqual([], second["genres"])
        self.assertNotEqual(first["genres"], second["genres"])

    def test_k_unavailable_episode_does_not_count_as_active_genre_content(self):
        anime_id = self.add_anime("missing", "Missing", ["Fantasy"])
        with self.store._conn() as con:
            con.execute(
                "UPDATE episodes SET missing=1,availability_state='scope_unavailable' WHERE anime_id=?",
                (anime_id,),
            )
        self.assertNotIn("Fantasy", self.genre_counts())

    def test_l_refresh_uses_same_bounded_projection(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        self.assertIn("async def refresh_from_catalog()", source)
        self.assertIn("await load_overview()", source)
        self.assertIn("_start_view_task(run_catalog_refreshes)", source)

    def test_m_stale_overview_results_are_generation_guarded(self):
        source = ORGANIZE.read_text(encoding="utf-8")
        block = source[
            source.index("async def load_overview"):
            source.index("async def render()", source.index("async def load_overview"))
        ]
        self.assertIn("token = render_generation[0]", block)
        self.assertIn("if token != render_generation[0]:", block)
        self.assertIn("asyncio.to_thread(library.organize_summary_bounded)", block)

    def test_n_organize_uses_aggregate_sql_not_per_genre_queries(self):
        source = STORE.read_text(encoding="utf-8")
        block = source[
            source.index("def organize_summary(self):"):
            source.index("def set_episode_identification", source.index("def organize_summary(self):"))
        ]
        self.assertEqual(2, block.count("c.execute("))
        self.assertIn("COUNT(DISTINCT ag.anime_id)", block)
        self.assertIn("GROUP BY g.id, g.canonical_name, g.normalized_name", block)

    def test_o_large_genre_population_contains_only_real_rows(self):
        for index in range(96):
            self.add_anime(
                f"genre-{index}",
                f"Genre {index}",
                [f"Genre {index}"],
            )
        genres = self.store.organize_summary()["genres"]
        self.assertEqual(96, len(genres))
        self.assertTrue(all(int(item["count"]) == 1 for item in genres))


class SourceContractTests(unittest.TestCase):
    @staticmethod
    def read(path):
        return path.read_text(encoding="utf-8")

    def test_ui_filters_empty_items_before_building_controls(self):
        source = self.read(ORGANIZE)
        self.assertIn('if int(item.get("count") or 0) > 0', source)
        self.assertIn("genres = [", source)
        self.assertIn("for item in collections", source)
        self.assertIn("if genres:", source)
        self.assertNotIn("visible=False", source)
        self.assertNotIn("registry_genres", source)

    def test_registry_remains_identity_store_but_not_render_population(self):
        source = self.read(ORGANIZE)
        self.assertIn("render_overview(summary)", source)
        self.assertNotIn("render_overview(summary, registry_genres)", source)
        self.assertNotIn("library.genre_options, include_unused=False", source)

    def test_sql_genre_projection_requires_available_library_content(self):
        source = self.read(STORE)
        block = source[
            source.index("def organize_summary(self):"):
            source.index("def set_episode_identification", source.index("def organize_summary(self):"))
        ]
        self.assertIn("e.missing=0", block)
        self.assertIn("COALESCE(e.availability_state,'available')='available'", block)

    def test_no_parallel_organize_database_or_visual_placeholder(self):
        source = self.read(ORGANIZE)
        self.assertNotIn("organize.db", source)
        self.assertNotIn("Nenhum gênero está disponível", source)

    def test_navigation_invalidates_stale_organize_callbacks(self):
        main = self.read(MAIN)
        self.assertIn('if navigation.current == "organize":', main)
        self.assertIn('_drop_screen_cache("organize")', main)

    def test_expected_small_objective_logs_exist(self):
        source = self.read(ORGANIZE)
        store = self.read(STORE)
        for label in (
            "ORGANIZE_QUERY_START",
            "ORGANIZE_CATEGORY_COUNT",
            "ORGANIZE_EMPTY_CATEGORY_SKIPPED",
            "ORGANIZE_UI_BUILT",
        ):
            self.assertIn(label, source)
        self.assertIn("ORGANIZE_QUERY_START", store)
        self.assertIn("ORGANIZE_QUERY_DONE", store)


if __name__ == "__main__":
    unittest.main()
