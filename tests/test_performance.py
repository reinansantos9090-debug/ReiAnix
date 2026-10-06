import asyncio
import tempfile
import time
import unittest
from unittest.mock import patch
from pathlib import Path

from core.library_service import LibraryService
from core.library_store import LibraryStore


class StorePaginationTests(unittest.TestCase):
    def _seed(self, store, count=40):
        for index in range(count):
            anime_id = store.upsert_anime(
                f"title-{index:03d}",
                {
                    "title": f"Title {index:03d}",
                    "genres": "[]",
                    "media_kind": "series",
                },
            )
            store.upsert_episode(
                anime_id,
                f"/library/title-{index:03d}-01.mkv",
                f"Title {index:03d} - 01.mkv",
                1,
                1,
                file_size=100 + index,
                modified_at=1000 + index,
            )

    def test_catalog_page_only_materializes_requested_page(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            self._seed(store, 40)

            first = store.catalog_page(page=0, page_size=12)
            second = store.catalog_page(page=1, page_size=12)

            self.assertEqual(first["total"], 40)
            self.assertEqual(len(first["items"]), 12)
            self.assertEqual(len(second["items"]), 12)
            self.assertTrue(first["has_more"])
            self.assertTrue(second["has_more"])
            self.assertTrue(set(item["id"] for item in first["items"]).isdisjoint(
                item["id"] for item in second["items"]
            ))

    def test_catalog_page_query_and_sort_are_applied_without_full_catalog_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            self._seed(store, 30)

            result = store.catalog_page(
                page=0,
                page_size=12,
                query="Title 002",
                sort="Nome Z-A",
            )

            self.assertEqual(result["total"], 1)
            self.assertEqual(len(result["items"]), 1)
            self.assertEqual(result["items"][0]["main_title"], "Title 002")

    def test_paged_states_follow_consumption_completion_ratio(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            completed_path = '/library/completed.mkv'
            completed = store.upsert_anime('completed', {'title': 'Completed', 'genres': '[]'})
            store.upsert_episode(completed, completed_path, 'Completed', 1, 1)
            store.save_progress(completed_path, 95, 100)
            active_path = '/library/active.mkv'
            active = store.upsert_anime('active', {'title': 'Active', 'genres': '[]'})
            store.upsert_episode(active, active_path, 'Active', 1, 1)
            store.save_progress(active_path, 50, 100)
            result = store.catalog_page(page=0, page_size=12, state='Concluídos')
            active_result = store.catalog_page(page=0, page_size=12, state='Em andamento')
            self.assertEqual([item['main_title'] for item in result['items']], ['Completed'])
            self.assertEqual([item['main_title'] for item in active_result['items']], ['Active'])
    def test_catalog_anime_ids_is_a_bounded_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            self._seed(store, 10)
            page = store.catalog_page(page=0, page_size=3)
            selected_id = page["items"][0]["id"]

            projected = store.catalog(anime_ids=[selected_id])

            self.assertEqual(len(projected), 1)
            self.assertEqual(projected[0]["id"], selected_id)

    def test_home_sections_remain_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            self._seed(store, 40)

            sections = store.home_sections(limit=8)

            self.assertLessEqual(len(sections["continue_watching"]), 8)
            self.assertLessEqual(len(sections["favorites"]), 8)
            self.assertLessEqual(len(sections["pinned"]), 8)
            self.assertLessEqual(len(sections["movies"]), 8)

    def test_continue_watching_uses_sql_bounded_resumable_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            first = store.upsert_anime("first", {"title": "First", "genres": "[]"})
            second = store.upsert_anime("second", {"title": "Second", "genres": "[]"})
            for anime_id, prefix in ((first, "first"), (second, "second")):
                for number in range(1, 80):
                    path = f"/library/{prefix}-{number:03d}.mkv"
                    store.upsert_episode(
                        anime_id, path, path.rsplit("/", 1)[-1], 1, number,
                        file_size=1000 + number, modified_at=number,
                    )
            first_path = "/library/first-079.mkv"
            second_path = "/library/second-078.mkv"
            store.save_progress(first_path, 20, 100, event_created_at=1)
            store.save_progress(second_path, 40, 100, event_created_at=2)

            result = store.continue_watching(limit=1)

            self.assertEqual(1, len(result))
            self.assertEqual(second_path, result[0]["path"])
            self.assertLessEqual(len(store.continue_watching(limit=100)), 2)

    def test_home_sections_batch_catalog_hydration(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            self._seed(store, 40)
            store.toggle_favorite(1)
            with patch.object(store, "catalog", wraps=store.catalog) as catalog:
                sections = store.home_sections(limit=8)
            self.assertEqual(1, catalog.call_count)
            self.assertLessEqual(len(sections["favorites"]), 8)
            self.assertLessEqual(len(sections["pinned"]), 8)
            self.assertLessEqual(len(sections["movies"]), 8)

    def test_episode_filter_index_has_a_direct_query_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            with store._conn() as con:
                indexes = {row["name"] for row in con.execute("PRAGMA index_list(episodes)").fetchall()}
                self.assertIn("idx_episodes_anime_season_number_abs", indexes)
                plan = con.execute(
                    "EXPLAIN QUERY PLAN SELECT id FROM episodes "
                    "WHERE anime_id=? AND season=? AND number=? "
                    "ORDER BY absolute_number, id",
                    (1, 1, 1),
                ).fetchall()
                plan_text = " ".join(str(row["detail"]) for row in plan)
                self.assertIn("idx_episodes_anime_season_number_abs", plan_text)

    def test_organize_assistidos_preserves_missing_completed_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime("missing-completed", {"title": "Missing Completed", "genres": "[]"})
            path = "/library/missing-completed.mkv"
            store.upsert_episode(anime_id, path, "Missing Completed", 1, 1)
            store.save_progress(path, 100, 100)
            with store._conn() as con:
                con.execute("UPDATE episodes SET missing=1 WHERE path=?", (path,))
            summary = store.organize_summary()
            values = {item["name"]: item["count"] for item in summary["collections"]}
            self.assertEqual(1, values["Assistidos"])
            self.assertEqual(0, values["Concluídos"])

    def test_summary_projections_preserve_expected_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            completed = store.upsert_anime("complete", {"title": "Complete", "genres": "[]"})
            active = store.upsert_anime("active", {"title": "Active", "genres": "[]"})
            empty = store.upsert_anime("empty", {"title": "Empty", "genres": "[]"})
            store.upsert_episode(completed, "/complete.mkv", "Complete", 1, 1)
            store.save_progress("/complete.mkv", 100, 100)
            store.upsert_episode(active, "/active.mkv", "Active", 1, 1)
            store.save_progress("/active.mkv", 20, 100)
            store.upsert_episode(empty, "/empty.mkv", "Empty", 1, 1)
            summary = store.organize_summary()
            values = {item["name"]: item["count"] for item in summary["collections"]}
            self.assertEqual(3, values["Todos"])
            self.assertEqual(1, values["Assistidos"])
            self.assertEqual(1, values["Em andamento"])
            self.assertEqual(1, values["Concluídos"])
            self.assertEqual(1, values["Não iniciados"])

    def test_library_summary_returns_expected_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime_id = store.upsert_anime("summary", {"title": "Summary", "genres": "[]"})
            store.upsert_episode(anime_id, "/library/summary.mkv", "Summary", 1, 1)
            store.save_progress("/library/summary.mkv", 10, 100)
            store.add_folder("/library")
            self.assertEqual(
                {"folders": 1, "animes": 1, "episodes": 1, "history": 1},
                store.library_summary(),
            )

    def test_performance_indexes_cover_default_sort_resume_and_episode_query(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            with store._conn() as con:
                indexes = {row["name"] for row in con.execute("PRAGMA index_list(anime)").fetchall()}
                episode_indexes = {row["name"] for row in con.execute("PRAGMA index_list(episodes)").fetchall()}
                self.assertIn("idx_anime_added_title", indexes)
                self.assertIn("idx_anime_favorite_added", indexes)
                self.assertIn("idx_episodes_resume", episode_indexes)
                self.assertIn("idx_episodes_season_number", episode_indexes)

                plan = con.execute(
                    "EXPLAIN QUERY PLAN SELECT id FROM anime "
                    "ORDER BY added_at DESC, title COLLATE NOCASE ASC, id DESC LIMIT 36"
                ).fetchall()
                plan_text = " ".join(str(row["detail"]) for row in plan)
                self.assertIn("idx_anime_added_title", plan_text)

class ServiceAndSourceTests(unittest.TestCase):
    def test_service_exposes_paged_catalog_and_bounded_home_sections(self):
        with tempfile.TemporaryDirectory() as directory:
            service = LibraryService(LibraryStore(directory))
            store = service.store
            anime_id = store.upsert_anime("naruto", {"title": "Naruto", "genres": "[]"})
            store.upsert_episode(anime_id, "/library/naruto-01.mkv", "Naruto 01", 1, 1)

            result = service.browse_catalog_page(page=0, page_size=12)
            home = service.media_center_home(limit=4)

            self.assertEqual(result["total"], 1)
            self.assertEqual(len(result["items"]), 1)
            self.assertLessEqual(len(home["favorites"]), 4)

    def test_home_uses_page_api_and_does_not_load_full_catalog(self):
        source = Path("views/home_view.py").read_text(encoding="utf-8")

        self.assertIn("browse_catalog_page", source)
        self.assertIn('page_size = settings.get("library.page_size")', source)
        self.assertIn("home_page_size = min(page_size, 48)", source)
        self.assertIn("page_size=home_page_size", source)
        self.assertIn("on_scroll=on_home_scroll", source)
        self.assertIn("search_generation", source)
        self.assertIn("if token != search_generation[0]:", source)
        self.assertNotIn("library.catalog(", source)

    def test_organize_uses_page_api_and_does_not_load_full_catalog(self):
        source = Path("views/organize_view.py").read_text(encoding="utf-8")

        self.assertIn("browse_catalog_page", source)
        self.assertIn("page_size=36", source)
        self.assertIn("on_collection_scroll", source)
        self.assertIn("search_generation", source)
        self.assertIn("organize_summary_bounded", source)
        self.assertNotIn("library.catalog", source)
        self.assertNotIn("page.run_task(lambda:", source)

    def test_details_bounds_initial_episode_render_and_uses_batch_artwork(self):
        source = Path("views/details_view.py").read_text(encoding="utf-8")
        self.assertIn("visible_episode_count = [48]", source)
        self.assertIn("Carregar mais", source)
        self.assertIn("resolve_artwork_batch", source)
        self.assertIn("_prepare_episode_artwork", source)

    def test_home_defers_secondary_projections_and_filter_options(self):
        source = Path("views/home_view.py").read_text(encoding="utf-8")
        self.assertIn("await load_library_page(reset=True)", source)
        self.assertIn("_start_view_task(refresh_home_sections, render_generation[0])", source)
        self.assertIn("_start_view_task(load_filter_options)", source)
        startup = source[source.index("async def load_catalog():"):source.index("search.on_change = on_search")]
        self.assertNotIn("library.media_center_home", startup)
        self.assertNotIn("library.search_options", startup)

    def test_home_library_tree_is_page_bounded_and_mailbox_has_idle_backoff(self):
        home = Path("views/home_view.py").read_text(encoding="utf-8")
        main = Path("main.py").read_text(encoding="utf-8")
        self.assertIn("page_size = settings.get(\"library.page_size\")", home)
        self.assertIn("catalog.extend(fresh_items)", home)
        self.assertIn("if remaining < 800", home)
        self.assertIn('poll_interval = 0.08 if player_session_active["value"] else 0.2', main)
        self.assertIn('poll_interval = min(1.0, max(0.2, poll_interval * 1.5))', main)
        self.assertNotIn("while True:\n            bridge.drain()", main)

    def test_async_stale_generation_contracts_are_present(self):
        home = Path("views/home_view.py").read_text(encoding="utf-8")
        organize = Path("views/organize_view.py").read_text(encoding="utf-8")

        self.assertIn("render_generation", home)
        self.assertIn("if token != render_generation[0]:", home)
        self.assertIn("search_generation", organize)
        self.assertIn("if token != search_generation[0]:", organize)


class MetadataAndViewLifecycleCoalescingTests(unittest.TestCase):
    def test_hydration_respects_recent_metadata_request_dedupe(self):
        """A discarded/recreated Home must not immediately repeat AniList I/O."""
        with tempfile.TemporaryDirectory() as directory:
            service = LibraryService(LibraryStore(directory))
            anime_id = service.store.upsert_anime(
                "deduped-show",
                {"title": "Deduped Show", "genres": "[]", "metadata_status": "stale"},
            )
            service.store.upsert_episode(
                anime_id, "/library/deduped-show-01.mkv", "Deduped Show 01", 1, 1,
            )
            now = time.time()
            with service.store._conn() as con:
                con.execute(
                    "UPDATE anime SET anilist_id=?,metadata_status='stale',"
                    "metadata_fetched_at=?,metadata_updated_at=? WHERE id=?",
                    (16498, now, now, anime_id),
                )
            with patch.object(service.anilist, "by_id") as by_id:
                hydrated = service.hydrate_catalog_metadata(service.catalog())
            by_id.assert_not_called()
            self.assertEqual(1, len(hydrated))
            service.artwork.shutdown()

    def test_cached_view_invalidation_advances_existing_generation_guards(self):
        home = Path("views/home_view.py").read_text(encoding="utf-8")
        organize = Path("views/organize_view.py").read_text(encoding="utf-8")
        main = Path("main.py").read_text(encoding="utf-8")
        for source in (home, organize):
            self.assertIn("def invalidate_view_tasks():", source)
            self.assertIn("render_generation[0] += 1", source)
            self.assertIn("view_state['_invalidate_view_tasks'] = invalidate_view_tasks", source)
        self.assertIn("def _invalidate_cached_view(state, route):", main)
        self.assertIn("invalidate_tasks = state.pop('_invalidate_view_tasks', None)", main)


class SQLiteConnectionConcurrencyTests(unittest.TestCase):
    def test_store_connections_enable_foreign_keys_and_bounded_busy_wait(self):
        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            with store._conn() as connection:
                self.assertEqual(1, connection.execute("PRAGMA foreign_keys").fetchone()[0])
                self.assertEqual(store.SQLITE_BUSY_TIMEOUT_MS, connection.execute("PRAGMA busy_timeout").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
