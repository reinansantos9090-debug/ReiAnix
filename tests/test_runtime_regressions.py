from pathlib import Path
import unittest

from core.navigation import NavigationController


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
HOME = ROOT / "views/home_view.py"
UI = ROOT / "core/ui.py"
STYLES = ROOT / "android/app/src/main/res/values/styles.xml"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"


class RuntimeRegressionTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_startup_persists_only_durable_ui_state(self):
        source = self.read(MAIN)
        payload = source[source.index("def _navigation_state_payload"):source.index("def _write_navigation_state")]
        self.assertIn('"version": 3', payload)
        self.assertIn('"home_state"', payload)
        self.assertIn('"organize_state"', payload)
        self.assertIn('"settings_state"', payload)
        self.assertNotIn('"navigation":', payload)
        self.assertNotIn('"details_media_id"', payload)
        self.assertIn("navigation = NavigationController()", source)
        self.assertIn("navigation.reset_to_root()", source)

    def test_settings_view_stack_is_derived_from_existing_navigation_controller(self):
        source = self.read(MAIN)
        self.assertIn("def _settings_view_paths()", source)
        self.assertIn("current_path = tuple(navigation.settings_path)", source)
        self.assertIn("settings_path_override=path", source)
        self.assertNotIn("class SettingsNavigation", source)
        self.assertNotIn("settings_history =", source)

    def test_nested_settings_back_is_one_level_per_navigation_operation(self):
        nav = NavigationController(clock=lambda: 100.0)
        nav.push("settings")
        nav.push_settings("Aparência")
        nav.push_settings("Player")

        self.assertEqual(nav.back(), "settings_inner")
        self.assertEqual(nav.settings_path, ("Aparência",))
        self.assertEqual(nav.back(), "settings_inner")
        self.assertEqual(nav.settings_path, ())
        self.assertEqual(nav.back(), "previous")
        self.assertEqual(nav.current, "home")

    def test_back_diagnostics_cover_physical_flutter_and_logical_events(self):
        main = self.read(MAIN)
        activity = self.read(MAIN_ACTIVITY)
        self.assertIn("BACK_PHYSICAL_RECEIVED", activity)
        self.assertIn("BACK_FLUTTER_POP_SENT", activity)
        self.assertIn("BACK_FLET_VIEW_POP_RECEIVED", main)
        self.assertIn("BACK_NAVIGATION_EXECUTED", main)
        self.assertEqual(main.count("page.on_view_pop = handle_flet_view_pop"), 1)

    def test_home_click_has_deterministic_callback_and_no_key_reconciliation_dependency(self):
        home = self.read(HOME)
        self.assertIn("def on_card_tap(item=anime):", home)
        self.assertIn("HOME_CARD_TAP animeId=%s", home)
        self.assertIn("on_select_anime(item)", home)
        self.assertNotIn('key=f"anime:{anime.get(\'id\', \'-\')}"', home)

    def test_home_artwork_work_is_concurrency_limited(self):
        home = self.read(HOME)
        self.assertIn("artwork_concurrency = asyncio.Semaphore(1)", home)
        self.assertIn("async with artwork_concurrency:", home)
        self.assertIn("artwork_ui_update_scheduled", home)
        self.assertIn("schedule_artwork_ui_update()", home)
        hydration_start = home.index("async def hydrate_metadata_and_artwork")
        hydration_end = home.index("async def refresh_home_sections", hydration_start)
        self.assertNotIn("holder.update()", home[hydration_start:hydration_end])

    def test_home_initial_page_is_bounded(self):
        home = self.read(HOME)
        self.assertIn("home_page_size = min(page_size, 48)", home)
        self.assertIn("page_size=home_page_size", home)
        self.assertIn("logger.info(\"HOME_RENDER_LIMIT", home)

    def test_dark_theme_has_explicit_transparent_system_overlay(self):
        ui = self.read(UI)
        self.assertIn("system_overlay_style", ui)
        self.assertIn("status_bar_color=ft.Colors.TRANSPARENT", ui)
        self.assertIn("system_navigation_bar_color=ft.Colors.TRANSPARENT", ui)
        self.assertIn("enforce_system_status_bar_contrast=False", ui)
        self.assertIn("enforce_system_navigation_bar_contrast=False", ui)
        self.assertIn("page.bgcolor = tokens.background", ui)

    def test_native_host_keeps_edge_to_edge_without_forcing_mainactivity_bar_colors(self):
        activity = self.read(MAIN_ACTIVITY)
        styles = self.read(STYLES)
        self.assertIn("systemUiController.applyApplicationImmersivePolicy(useContextAppearance = false)", activity)
        self.assertIn("WindowCompat.setDecorFitsSystemWindows(window, false)", self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/SystemUiController.kt"))
        self.assertIn('<item name="android:windowBackground">#000000</item>', styles)


if __name__ == "__main__":
    unittest.main()
