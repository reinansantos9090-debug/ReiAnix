import ast
import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source

from core.storage_access import StorageCapabilities
from tests.test_flet_page_harness import AsyncRunTaskMixin
from views.settings_view import SettingsView

ROOT = Path(__file__).resolve().parents[1]


class UiStateTests(unittest.TestCase):
    def test_scan_states_are_explicit(self):
        source = (ROOT / "core" / "storage_access.py").read_text(encoding="utf-8")
        for state in ("IDLE", "CHECKING", "SCANNING", "COMPLETED", "PARTIAL", "CANCELLED", "FAILED", "WAITING_FOR_MEDIASTORE", "VOLUME_UNAVAILABLE"):
            self.assertIn(f'{state} = "{state}"', source)

    def test_settings_normalizes_real_storage_capabilities_without_attribute_errors(self):
        class Store:
            def folders(self): return []
            def library_summary(self): return {"animes": 0, "episodes": 0, "folders": 0, "history": 0}
            def pending_matches(self): return []
            def last_scan(self): return None
            def latest_backup(self): return None
            def get_preference(self, key, default=None): return default
            def set_preference(self, key, value): return None

        class Library:
            def library_statistics(self):
                return {
                    "animes": 0, "episodes_available": 0, "episodes": 0,
                    "animes_completed": 0, "animes_in_progress": 0,
                    "animes_not_started": 0, "episodes_watched": 0,
                    "favorites": 0, "pinned": 0, "tags": 0, "notes": 0,
                    "without_metadata": 0, "without_cover": 0,
                }
            def clear_anilist_cache(self): return 0

        class Page(AsyncRunTaskMixin):
            def update(self): return None
            def show_dialog(self, dialog): return None
            def pop_dialog(self): return None

        cases = (
            ("denied", "unavailable", (), ()),
            ("denied", "unavailable", ("content://tree/denied",), ()),
            ("denied", "available", (), ()),
            ("partial", "unavailable", (), ()),
            ("partial", "unavailable", ("content://tree/partial",), ()),
            ("partial", "available", (), ("USB",)),
            ("full", "unavailable", (), ()),
            ("full", "unavailable", ("content://tree/full",), ()),
            ("full", "available", (), ("USB",)),
            ("full", "available", ("content://tree/full",), ("USB",)),
        )

        for media, broad, saf_roots, volumes in cases:
            with self.subTest(media=media, broad=broad, saf_roots=saf_roots, volumes=volumes):
                snapshot = StorageCapabilities(
                    media_read_state=media,
                    broad_storage_state=broad,
                    saf_roots=saf_roots,
                    removable_volumes=volumes,
                    lifecycle_state="revalidated",
                    api=36,
                )
                result = SettingsView.build(
                    Page(), Store(), Library(),
                    lambda: None, lambda: None,
                    lambda: None, lambda _ref: None,
                    lambda: None, lambda: None,
                    lambda: None, lambda: None,
                    lambda: None,
                    account={},
                    storage_snapshot=snapshot,
                    scan_snapshot={"state": "IDLE"},
                )
                self.assertIsNotNone(result)


    def test_settings_has_scan_diagnostics(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        for token in ("Varredura em andamento:", "Status:", "Última varredura:", "Volumes removíveis:"):
            self.assertIn(token, source)

    def test_native_entrypoint_passes_authoritative_state_to_compose(self):
        source = native_main_source()
        self.assertIn("ReiAnixSettingsViewModel", source)
        self.assertIn("ReiAnixLibraryViewModel", source)
        self.assertIn("NativeScanController.progress", source)
        self.assertIn("StateFlow", source)
    def test_native_entrypoint_binds_work_to_android_lifecycle(self):
        source = native_main_source()
        self.assertIn("ComponentActivity", source)
        self.assertIn("lifecycleScope", source)
        self.assertIn("onDestroy()", source)
        self.assertIn("ReiAnixAppDependencies", source)
    def test_storage_authorization_is_independent_of_library_rows(self):
        source = native_main_source()
        self.assertIn("object StorageAuthorization", source)
        self.assertIn("canScanMediaStore", source)
        self.assertIn("canReconcileMediaStore", source)
        self.assertIn("SafScanner", source)
        self.assertIn("MediaStoreScanner", source)
    def test_home_and_organize_do_not_mutate_flet_from_background_threads(self):
        home = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
        organize = (ROOT / "views" / "organize_view.py").read_text(encoding="utf-8")
        self.assertIn("await asyncio.to_thread(", home)
        self.assertIn("_start_view_task(load_catalog)", home)
        self.assertNotIn("page.run_thread(work)", home)
        self.assertIn("await asyncio.to_thread(", organize)
        self.assertIn("_start_view_task(load_catalog)", organize)
        self.assertNotIn("page.run_thread(load_catalog)", organize)

    def test_home_filters_are_secondary_and_card_dimensions_are_compact(self):
        source = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
        self.assertIn('filter_button = ft.OutlinedButton("Filtros"', source)
        self.assertIn('page.show_dialog(dialog)', source)
        self.assertIn('card_size = {"small": 120, "medium": 146, "large": 172}', source)
        self.assertIn("width=card_width", source)
        self.assertIn("artwork_holder(item, card_width, card_height", source)

    def test_source_is_valid_python(self):
        for path in (ROOT / "views" / "settings_view.py", ROOT / "core" / "storage_access.py"):
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))



    def test_settings_primary_sections_are_content_first(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        positions = [
            source.index('section("Conta"'),
            source.index('section("Geral"'),
            source.index('section("Aparência"'),
            source.index('section("Biblioteca"'),
            source.index('section("Player"'),
            source.index('section("Áudio e Legendas"'),
            source.index('section("Metadata"'),
            source.index('section("Armazenamento"'),
            source.index('section("Backup e Restauração"'),
            source.index('section("Diagnóstico"'),
        ]
        self.assertEqual(positions, sorted(positions))


if __name__ == "__main__":
    unittest.main()
