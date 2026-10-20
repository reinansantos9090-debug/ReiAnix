import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source


ROOT = Path(__file__).resolve().parents[1]
MAIN = native_main_source()
NAVIGATION = ROOT / "core/navigation.py"
MAIN_ACTIVITY = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
)
NAV_HOST = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
)
COMPOSE_HOST = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
)


class ComposeCutoverTests(unittest.TestCase):
    def test_compose_is_the_single_android_visual_host(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn(
            "composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)",
            source,
        )
        self.assertNotIn("composeSettingsHost.show(", source)
        self.assertNotIn("composeStorageHost.show(", source)
        self.assertIn(
            "val requestedDestination = data.getQueryParameter(\"start_destination\")?.trim()",
            source,
        )
        self.assertIn(
            "composeLibraryHost.show(destination, resetBackStack = true)",
            source,
        )
        for destination in (
            "ReiAnixRoutes.LIBRARY",
            "ReiAnixRoutes.ORGANIZE",
            "ReiAnixRoutes.SETTINGS",
            "ReiAnixRoutes.STORAGE",
        ):
            self.assertIn(destination, source)

    def test_compose_navigation_is_native_without_python_route_mirroring(self):
        main = MAIN
        navigation = NAVIGATION.read_text(encoding="utf-8")
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        nav_host = NAV_HOST.read_text(encoding="utf-8")

        self.assertIn("ReiAnixComposeLibraryHost", main)
        self.assertIn("MainActivity", main)
        self.assertNotIn("compose_navigation_changed", main)
        self.assertNotIn("compose_library_navigation", main)
        self.assertNotIn("compose_settings_navigation", main)
        self.assertNotIn("sync_top_level", navigation)
        self.assertNotIn("compose_navigation_changed", host)
        self.assertNotIn("compose_library_navigation", host)
        self.assertNotIn("compose_settings_navigation", host)
        self.assertNotIn("onRouteChanged", nav_host)
        self.assertIn("NavHost(", nav_host)
        self.assertIn("LaunchedEffect(startDestination, effectiveStartDestination)", nav_host)
    def test_migrated_screens_use_only_native_compose_routes(self):
        source = MAIN
        nav_host = NAV_HOST.read_text(encoding="utf-8")
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        for route in (
            "ReiAnixRoutes.HOME", "ReiAnixRoutes.LIBRARY",
            "ReiAnixRoutes.ORGANIZE", "ReiAnixRoutes.DETAILS",
            "ReiAnixRoutes.COLLECTOR", "ReiAnixRoutes.SETTINGS",
        ):
            self.assertIn(route, source)
        self.assertIn("NavHost(", nav_host)
        self.assertIn("ReiAnixComposeLibraryHost", source)
        self.assertIn("ReiAnixRoutes.COLLECTOR", host)
        self.assertNotIn("HomeView.build(", source)
        self.assertNotIn("DetailView.build(", source)
        self.assertNotIn("SettingsView.build(", source)
    def test_recovery_and_collector_are_native_compose_destinations(self):
        source = MAIN
        compose_host = COMPOSE_HOST.read_text(encoding="utf-8")
        self.assertIn("ReiAnixRoutes.RECOVERY", source)
        self.assertIn("ReiAnixRoutes.COLLECTOR", source)
        self.assertIn("navigateToCollector()", source)
        self.assertIn("ReiAnixRoutes.RECOVERY", compose_host)
        self.assertNotIn("recovery_bridge = AndroidBridge", source)
        self.assertNotIn("CollectorView.build(", source)
    def test_canonical_domain_boundaries_remain_intact(self):
        source = MAIN
        self.assertIn("ReiAnixAppDependencies", source)
        self.assertIn("ReiAnixLibraryService", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("NativeMailbox", source)
        self.assertIn("ReiAnixSqliteLibraryRepository", source)
        self.assertIn("ReiAnixConsumptionService", source)

if __name__ == "__main__":
    unittest.main()
