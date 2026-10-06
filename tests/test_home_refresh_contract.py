import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HomeRefreshContractTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_home_exposes_manual_refresh_and_explicit_states(self):
        source = self.read("views/home_view.py")
        self.assertIn("on_refresh_library=None", source)
        for state in ("IDLE", "REFRESHING", "SUCCESS", "ERROR"):
            self.assertIn(f'"{state}"', source)
        self.assertIn('icon=ft.Icons.REFRESH', source)
        self.assertIn('tooltip="Atualizar biblioteca"', source)
        self.assertIn("async def handle_manual_refresh", source)
        self.assertIn('button.disabled = True', source)
        self.assertIn('button.icon = ft.Icons.SYNC', source)

    def test_main_wires_home_refresh_to_existing_scan_pipeline(self):
        source = self.read("main.py")
        self.assertIn("async def request_home_refresh", source)
        self.assertIn("async def refresh_home_library", source)
        start = source.index("async def request_home_refresh")
        end = source.index("async def login", start)
        block = source[start:end]
        self.assertIn("await refresh_library(_home_refresh_context=home_refresh_context)", block)
        self.assertIn("HOME_REFRESH_REQUESTED", block)
        self.assertIn("HOME_REFRESH_ACCEPTED", block)
        self.assertIn("HOME_REFRESH_REJECTED", block)
        self.assertIn("ScanOrigin.USER_REFRESH", source)
        self.assertNotIn("bridge.scan_all_storage()", block)
        self.assertNotIn("bridge.scan_media_store()", block)
        self.assertNotIn("bridge.rescan_tree(", block)

    def test_home_refresh_has_no_parallel_scanner(self):
        source = self.read("views/home_view.py")
        self.assertNotIn("scan_all_storage", source)
        self.assertNotIn("scan_media_store", source)
        self.assertNotIn("rescan_tree", source)

    def test_home_refresh_updates_cached_catalog_without_rebuilding_home(self):
        source = self.read("views/home_view.py")
        start = source.index("async def refresh_from_catalog")
        end = source.index("async def retry_load_catalog", start)
        block = source[start:end]
        self.assertIn("await load_library_page(reset=True)", block)
        self.assertIn("on_refresh_ui_updated", block)
        self.assertIn("on_refresh_ui_failed", block)
        self.assertNotIn("render_current(", block)

    def test_catalog_change_is_coalesced_through_existing_home_hook(self):
        main = self.read("main.py")
        self.assertIn("home_refresh_context[\"scan_terminal\"]", main)
        self.assertIn("home_state[\"_manual_refresh_pending\"] = True", main)
        self.assertIn("home_state.get(\"_refresh_from_catalog\")", main)
        self.assertIn("on_catalog_changed()", main)
        broad = main[main.index("elif event_type == 'broad_storage_scan':"):main.index("elif event_type == 'broad_storage_status':")]
        self.assertNotIn("finally:\n                                on_catalog_changed()", broad)

    def test_refresh_instrumentation_contract_is_present(self):
        source = self.read("main.py")
        for event_name in (
            "HOME_REFRESH_REQUESTED",
            "HOME_REFRESH_ACCEPTED",
            "HOME_REFRESH_REJECTED",
            "HOME_REFRESH_STARTED",
            "HOME_REFRESH_SCAN_STARTED",
            "HOME_REFRESH_SCAN_COMPLETED",
            "HOME_REFRESH_DB_UPDATED",
            "HOME_REFRESH_UI_UPDATED",
            "HOME_REFRESH_COMPLETED",
            "HOME_REFRESH_FAILED",
        ):
            self.assertIn(event_name, source)

    def test_home_refresh_tracks_the_exact_scan_request(self):
        source = self.read("main.py")
        start = source.index("async def request_home_refresh")
        end = source.index("async def refresh_home_library", start)
        block = source[start:end]
        self.assertIn('_home_refresh_context=home_refresh_context', block)
        self.assertIn('_home_refresh_context["request_id"]', source)
        self.assertIn('snapshot.request_id == home_refresh_context.get("request_id")', source)
        self.assertIn("ScanState.BLOCKED", source)
    def test_home_refresh_survives_leaving_home_and_refreshes_on_return(self):
        source = self.read("main.py")
        self.assertIn('home_state["_manual_refresh_pending"] = True', source)
        render_start = source.index("def render_current(")
        render_end = source.index("def handle_flet_view_pop", render_start)
        render = source[render_start:render_end]
        self.assertIn('navigation.current == "home"', render)
        self.assertIn('home_state.get("_manual_refresh_pending")', render)
        self.assertIn('home_state.get("_refresh_from_catalog")', render)

    def test_home_refresh_state_returns_to_idle_after_terminal_feedback(self):
        source = self.read("views/home_view.py")
        self.assertIn("view_state['_reset_refresh_state'] = _schedule_refresh_reset", source)
        self.assertIn('reset_state', source)
        self.assertIn('set_refresh_state("IDLE")', source)

    def test_source_is_valid_python(self):
        for path in ("main.py", "views/home_view.py"):
            ast.parse(self.read(path), filename=path)


if __name__ == "__main__":
    unittest.main()
