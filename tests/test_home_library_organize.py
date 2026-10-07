"""earlier validation stage 7 regression contracts for Home, Library and Organize performance boundaries."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
HOME = ROOT / "views" / "home_view.py"
ORGANIZE = ROOT / "views" / "organize_view.py"
COMPOSE_ORGANIZE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt"
COMPOSE_HOST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
STORE = ROOT / "core" / "library_store.py"


class HomeLibraryOrganizeTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_main_binds_home_and_organize_to_current_route_lifecycle(self):
        main = self.read(MAIN)
        self.assertIn('is_active=lambda: ui_alive[0] and navigation.current == "home"', main)
        self.assertIn('is_active=lambda: ui_alive[0] and navigation.current == "organize"', main)

    def test_home_uses_lifecycle_guard_without_replacing_paging(self):
        home = self.read(HOME)
        self.assertIn("is_active=None", home)
        self.assertIn("is_active = is_active or (lambda: True)", home)
        self.assertIn("await asyncio.to_thread(", home)
        self.assertIn("library.browse_catalog_page", home)
        self.assertIn("if changed and is_active():", home)
        self.assertIn("page.update()", home)

    def test_home_load_catalog_no_longer_double_updates_after_paged_load(self):
        home = self.read(HOME)
        start = home.index("async def load_catalog")
        end = home.index("search.on_change = on_search", start)
        block = home[start:end]
        self.assertNotIn("page.update()\n            await restore_scroll_position()\n            page.run_task(refresh_home_sections", block)
        self.assertIn("await load_library_page(reset=True)", block)
        self.assertNotIn("page.update()", block.split("await load_library_page(reset=True)", 1)[1].split("page.run_task(refresh_home_sections", 1)[0])

    def test_home_sections_skip_rebuild_when_visible_projection_is_unchanged(self):
        home = self.read(HOME)
        self.assertIn("section_signatures: dict[str, tuple]", home)
        self.assertIn("if card is not None and section_signatures.get(key) == signature:", home)
        self.assertIn("return False", home)
        self.assertIn("changed = render_continue()", home)
        self.assertIn("if changed and is_active():", home)

    def test_home_continue_row_also_avoids_recreating_equal_items(self):
        home = self.read(HOME)
        self.assertIn("continue_signature = [None]", home)
        self.assertIn("if continue_signature[0] == signature:", home)
        self.assertIn("continue_row.controls.clear()", home)

    def test_home_inactive_callbacks_do_not_trigger_global_updates(self):
        home = self.read(HOME)
        self.assertIn("if not is_active():\n                return", home)
        self.assertIn("if not is_active():\n                        return", home)
        self.assertIn("async def refresh_home_sections(token):", home)
        self.assertIn("if changed and is_active():", home)

    def test_organize_overview_work_is_backgrounded_and_generation_checked(self):
        organize = self.read(ORGANIZE)
        self.assertIn("is_active=None", organize)
        self.assertIn("is_active = is_active or (lambda: True)", organize)
        self.assertIn("async def load_overview(*, token=None):", organize)
        self.assertIn("asyncio.to_thread(library.organize_summary_bounded)", organize)
        self.assertNotIn("asyncio.to_thread(library.genre_options, include_unused=False)", organize)
        self.assertIn("library.organize_summary_bounded", organize)
        self.assertIn("if token != render_generation[0]:", organize)
        self.assertIn("if is_active():\n                page.update()", organize)

    def test_organize_initial_build_does_not_query_summary_synchronously(self):
        organize = self.read(ORGANIZE)
        start = organize.rindex("        save_view_state()\n")
        end = organize.index("        _start_view_task(load_catalog)", start)
        initial = organize[start:end]
        self.assertNotIn("organize_summary_bounded", initial)
        self.assertNotIn("genre_options(", initial)
        self.assertIn('content.controls.extend([header("Organizar"), status])', initial)

    def test_organize_collection_summary_is_not_applied_after_generation_changes(self):
        organize = self.read(ORGANIZE)
        start = organize.index("async def render_collection")
        end = organize.index("async def render_collection_only", start)
        block = organize[start:end]
        self.assertIn("collection_token = render_generation[0]", block)
        self.assertIn("if collection_token != render_generation[0]:", block)

    def test_organize_refresh_reuses_async_overview_loader(self):
        organize = self.read(ORGANIZE)
        self.assertIn("await load_overview()", organize)
        self.assertNotIn("render_overview()\n                page.update()", organize)

    def test_compose_organize_renders_all_configured_sources_from_canonical_storage_state(self):
        organize = self.read(COMPOSE_ORGANIZE)
        host = self.read(COMPOSE_HOST)
        self.assertIn("state.storage.configuredSources", organize)
        self.assertIn("sources.forEach { source ->", organize)
        self.assertIn("item(\n                            key = source.stableKey", organize)
        self.assertIn("state.storage.sourceState(source)", organize)
        self.assertIn("onRemoveFolder: (String) -> Unit", organize)
        self.assertIn("Remover fonte da biblioteca?", organize)
        self.assertIn("Os arquivos e vídeos físicos não serão apagados.", organize)
        self.assertIn("onRemoveFolder = libraryViewModel::removeSafTree", host)
        self.assertNotIn("remember { mutableStateOf(listOf<", organize)

    def test_compose_organize_exposes_real_scan_states_without_fake_percentages(self):
        organize = self.read(COMPOSE_ORGANIZE)
        for label in ("Permissão necessária", "Processando", "Concluído", "Concluído parcialmente", "Erro", "Aguardando"):
            self.assertIn(label, organize)
        self.assertNotIn("%", organize)
        self.assertIn("availableContentCount", organize)

    def test_store_instruments_bounded_home_sections_and_organize_summary(self):
        store = self.read(STORE)
        home_start = store.index("def home_sections(")
        home_end = store.index("def organize_summary(", home_start)
        home = store[home_start:home_end]
        self.assertIn('record_sqlite(\n            "home_sections"', home)
        org_start = store.index("def organize_summary(")
        org_end = store.index("def set_episode_identification", org_start)
        org = store[org_start:org_end]
        self.assertIn('record_sqlite(\n            "organize_summary"', org)

    def test_home_bounded_projection_remains_in_place(self):
        home = self.read(HOME)
        self.assertIn("home_page_size = min(page_size, 48)", home)
        self.assertIn("if remaining < 800", home)

    def test_organize_bounded_pagination_remains_in_place(self):
        organize = self.read(ORGANIZE)
        self.assertIn("page_size=36", organize)
        self.assertIn("if remaining < 800", organize)
        self.assertIn("library.browse_catalog_page", organize)


if __name__ == "__main__":
    unittest.main()
