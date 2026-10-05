import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
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


class Prompt33ComposeCutoverTests(unittest.TestCase):
    def test_compose_is_the_single_android_visual_host(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn(
            "composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)",
            source,
        )
        self.assertNotIn("composeSettingsHost.show(", source)
        self.assertNotIn("composeStorageHost.show(", source)
        self.assertIn(
            "composeLibraryHost.show(ReiAnixRoutes.LIBRARY, resetBackStack = true)",
            source,
        )
        self.assertIn(
            "composeLibraryHost.show(ReiAnixRoutes.ORGANIZE, resetBackStack = true)",
            source,
        )
        self.assertIn(
            "composeLibraryHost.show(ReiAnixRoutes.SETTINGS, resetBackStack = true)",
            source,
        )
        self.assertIn(
            "composeLibraryHost.show(ReiAnixRoutes.STORAGE, resetBackStack = true)",
            source,
        )

    def test_compose_routes_project_back_to_the_existing_python_navigation(self):
        main = MAIN.read_text(encoding="utf-8")
        navigation = NAVIGATION.read_text(encoding="utf-8")
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        nav_host = NAV_HOST.read_text(encoding="utf-8")

        self.assertIn("compose_primary_ui = bool(bridge.available)", main)
        self.assertIn("event_type == 'compose_navigation_changed'", main)
        self.assertIn("navigation.sync_top_level(destination)", main)
        self.assertIn("navigation.sync_top_level("settings")", main)
        self.assertIn("navigation.push("details")", main)

        self.assertIn('"my_list"', navigation)
        self.assertIn('"search"', navigation)
        self.assertIn("def sync_top_level", navigation)

        self.assertIn("onRouteChanged = ::publishComposeRouteChanged", host)
        self.assertIn('"compose_navigation_changed"', host)
        self.assertIn("LaunchedEffect(composeRoute, animeId, episodeId, routeOrigin)", nav_host)

    def test_migrated_screens_do_not_build_parallel_flet_ui_on_android(self):
        source = MAIN.read_text(encoding="utf-8")
        start = source.index("    def _build_screen(")
        end = source.index("    def _settings_view_paths()", start)
        block = source[start:end]

        home = block[block.index('        if route == "home":'):block.index('        elif route == "library":')]
        details = block[block.index('        elif route == "details":'):block.index('        elif route == "collector":')]

        self.assertIn("if compose_primary_ui:", home)
        self.assertIn("control = ft.Container(expand=True)", home)
        self.assertNotIn("HomeView.build(", home)

        self.assertIn("if compose_primary_ui:", details)
        self.assertIn("control = ft.Container(expand=True)", details)
        self.assertNotIn("DetailView.build(", details)

        # Collector remains a real Flet surface because no Compose equivalent
        # exists yet.
        self.assertIn("CollectorView.build(", block)

        # Nested Settings remains the guarded legacy fallback.
        self.assertIn("SettingsView.build(", block)

    def test_recovery_and_collector_keep_real_legacy_surfaces(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn("recovery_bridge = AndroidBridge(data_dir, page)", source)
        self.assertIn("await recovery_bridge.hide_library()", source)
        self.assertIn("elif destination == 'collector':", source)
        self.assertIn("await bridge.hide_library()", source)
        self.assertIn("def navigate_collector():", source)
        self.assertIn("CollectorView.build(", source)

    def test_canonical_domain_boundaries_remain_intact(self):
        source = MAIN.read_text(encoding="utf-8")
        self.assertIn("LibraryStore", source)
        self.assertIn("ScanCoordinator", source)
        self.assertIn("AndroidBridge(", source)
        self.assertIn("ComposeLibraryBridge(", source)
        self.assertIn("ComposeSettingsBridge(", source)
        self.assertIn("compose_library_bridge.request_publish", source)
        self.assertIn("player_progress", source)
        self.assertIn("player_exited", source)
        self.assertIn("saf_permission", source)


if __name__ == "__main__":
    unittest.main()
