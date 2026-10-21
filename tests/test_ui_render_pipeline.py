import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source

ROOT = Path(__file__).resolve().parents[1]
VIEW_MODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
HOST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
NAVIGATION = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"


class UiRenderPipelineTests(unittest.TestCase):
    def test_native_compose_is_the_single_android_screen_shell(self):
        source = native_main_source()
        self.assertIn("ReiAnixComposeLibraryHost", source)
        self.assertIn("ReiAnixNavigationHost", source)
        self.assertIn("rememberNavController()", source)
        self.assertIn("NavHost(", source)
        self.assertNotIn("HomeView.build(", source)
        self.assertNotIn("DetailView.build(", source)
        self.assertNotIn("SettingsView.build(", source)

    def test_native_routes_cover_main_screens(self):
        source = NAVIGATION.read_text(encoding="utf-8") + HOST.read_text(encoding="utf-8")
        for route in (
            "ReiAnixRoutes.HOME", "ReiAnixRoutes.LIBRARY", "ReiAnixRoutes.ORGANIZE",
            "ReiAnixRoutes.SEARCH", "ReiAnixRoutes.DETAILS", "ReiAnixRoutes.COLLECTOR",
            "ReiAnixRoutes.SETTINGS",
        ):
            self.assertIn(route, source)

    def test_library_rendering_uses_bounded_native_pages_and_generation_guards(self):
        source = VIEW_MODEL.read_text(encoding="utf-8")
        self.assertIn("nativePageGeneration", source)
        self.assertIn("nativeCatalogReloadMutex", source)
        self.assertIn("loadNativeLibraryPage(", source)
        self.assertIn("SEARCH_DEBOUNCE_MS = 180L", source)
        self.assertIn("nativePagedLibraryState", source)

    def test_scan_refresh_updates_native_catalog_state(self):
        source = VIEW_MODEL.read_text(encoding="utf-8")
        self.assertIn("NativeScanController.progress", source)
        self.assertIn("collectLatest { refreshNativeCatalog() }", source)
        self.assertIn("refreshCanonicalCatalogAfterBootstrapValidation()", source)
        self.assertIn("refreshNativeCatalog()", source)

    def test_player_and_thumbnail_updates_are_owned_by_native_services(self):
        source = native_main_source()
        self.assertIn("NativePlayerActivity", source)
        self.assertIn("VideoThumbnailExtractor", source)
        self.assertIn("ReiAnixArtworkService", source)
        self.assertIn("ReiAnixDiagnosticTimeline.record(", source)


if __name__ == "__main__":
    unittest.main()
