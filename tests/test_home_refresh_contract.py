import ast
import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source


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

    def test_native_main_wires_home_refresh_to_existing_scan_pipeline(self):
        source = native_main_source()
        self.assertIn("object NativeScanController", source)
        self.assertIn("object NativeScanRunner", source)
        self.assertIn("NativeScanController.progress", source)
        self.assertIn("NativeIndex", source)
        self.assertIn("ReiAnixLibraryViewModel", source)
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
        source = native_main_source()
        self.assertIn("nativeCatalogReloadMutex", source)
        self.assertIn("refreshNativeCatalog()", source)
        self.assertIn("ReiAnixHomeLibraryUiState", source)
        self.assertIn("ReiAnixComposeLibraryHost", source)
    def test_refresh_instrumentation_contract_is_present(self):
        source = native_main_source()
        for event_name in (
            "NATIVE_SCAN_STARTED",
            "NATIVE_SCAN_CANCELLATION_REQUESTED",
            "NATIVE_SCAN_FINISHED",
            "NATIVE_SCAN_BATCH_PUBLICATION_FAILED",
        ):
            self.assertIn(event_name, source)
        self.assertIn("ReiAnixDiagnosticTimeline.record(", source)
    def test_home_refresh_tracks_the_exact_scan_request(self):
        source = native_main_source()
        for token in ("scanId", "requestId", "sourceKey", "generation", "activeScanId"):
            self.assertIn(token, source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanController.snapshot(", source)
    def test_home_refresh_survives_leaving_home_and_refreshes_on_return(self):
        source = native_main_source()
        self.assertIn("rememberNavController()", source)
        self.assertIn("ReiAnixRoutes.HOME", source)
        self.assertIn("ReiAnixComposeLibraryHost", source)
        self.assertIn("resetBackStack", source)
    def test_home_refresh_state_returns_to_idle_after_terminal_feedback(self):
        source = self.read("views/home_view.py")
        self.assertIn("view_state['_reset_refresh_state'] = _schedule_refresh_reset", source)
        self.assertIn('reset_state', source)
        self.assertIn('set_refresh_state("IDLE")', source)

    def test_retained_home_source_is_valid_python(self):
        ast.parse(self.read("views/home_view.py"), filename="views/home_view.py")

if __name__ == "__main__":
    unittest.main()
