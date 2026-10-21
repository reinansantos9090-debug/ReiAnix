from pathlib import Path
import unittest

from core.navigation import NavigationController
from tests.native_main_contract import native_main_source

ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
COMPOSE_HOST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
UI = ROOT / "core/ui.py"
STYLES = ROOT / "android/app/src/main/res/values/styles.xml"


class RuntimeRegressionTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_startup_uses_native_bootstrap_and_routes_home(self):
        activity = self.read(MAIN_ACTIVITY)
        source = native_main_source()
        self.assertIn("MainEntryPoint.onActivityCreated(this)", activity)
        self.assertIn("dependencies.bootstrap.start()", source)
        self.assertIn("ReiAnixRoutes.HOME", source)
        self.assertIn("resetBackStack = true", activity)
        self.assertIn("ReiAnixAppBootstrap", source)

    def test_settings_navigation_is_native_and_has_no_parallel_stack(self):
        source = native_main_source()
        self.assertIn("NavHostController", source)
        self.assertIn("composeLibraryHost.handleBack()", source)
        self.assertIn("controller.popBackStack()", source)
        self.assertIn("ReiAnixRoutes.SETTINGS", source)

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

    def test_back_diagnostics_cover_native_compose_and_activity_events(self):
        activity = self.read(MAIN_ACTIVITY)
        host = self.read(COMPOSE_HOST)
        self.assertIn("BACK_PHYSICAL_RECEIVED", activity)
        self.assertIn("BACK_COMPOSE_SHELL_RESTORED", activity)
        self.assertIn("fun handleBack(): Boolean", host)
        self.assertNotIn("flutterEngine", activity)
        self.assertNotIn("engine.navigationChannel.popRoute()", activity)

    def test_native_home_artwork_work_is_owned_by_single_service(self):
        source = native_main_source()
        self.assertIn("ReiAnixArtworkService", source)
        self.assertIn("nativeCatalogReloadMutex", source)
        self.assertIn("refreshNativeCatalog()", source)

    def test_native_home_initial_page_is_bounded(self):
        view_model = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt")
        self.assertIn("loadNativeLibraryPage(", view_model)
        self.assertIn("nativePageGeneration", view_model)
        self.assertIn("nativePagedLibraryState", view_model)

    def test_dark_theme_has_explicit_transparent_system_overlay(self):
        activity = self.read(MAIN_ACTIVITY)
        styles = self.read(STYLES)
        system_ui = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/SystemUiController.kt")
        self.assertIn("systemUiController.applyApplicationImmersivePolicy", activity)
        self.assertIn("WindowCompat.setDecorFitsSystemWindows(window, false)", system_ui)
        self.assertIn('<item name="android:windowBackground">#000000</item>', styles)


if __name__ == "__main__":
    unittest.main()
