import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMPOSE_HOST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
NAVIGATION_NATIVE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
DETAILS_NATIVE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
SETTINGS_NATIVE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
SETTINGS = ROOT / "views" / "settings_view.py"
COLLECTOR = ROOT / "views" / "collector_view.py"
DETAILS = ROOT / "views" / "details_view.py"
NAVIGATION = ROOT / "core" / "navigation.py"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"


class NavigationLifecycleTests(unittest.TestCase):

    def test_compose_route_changes_invalidate_home_only_state(self):
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        self.assertIn("val currentRoute by navController.currentBackStackEntryAsState()", host)
        self.assertIn("LaunchedEffect(currentRoute?.destination?.route)", host)
        self.assertIn("lastHomeBackAtElapsedRealtimeMs = 0L", host)
        self.assertIn("if (route != ReiAnixRoutes.HOME && route != ReiAnixRoutes.RECOVERY)", host)

    def test_duplicate_details_navigation_uses_single_top_policy(self):
        source = NAVIGATION_NATIVE.read_text(encoding="utf-8")
        start = source.index("fun NavHostController.navigateToDetails(")
        end = source.index("fun NavHostController.navigateToPlayer(", start)
        handler = source[start:end]
        self.assertIn("launchSingleTop = true", handler)
        self.assertIn("ReiAnixRoutes.details(animeId, origin)", handler)
        self.assertNotIn('navigate("details")', handler)
        self.assertNotIn("composeLibraryHost.show", handler)
    def test_duplicate_settings_category_does_not_grow_nested_path(self):
        source = NAVIGATION.read_text(encoding="utf-8")
        start = source.index("def push_settings")
        end = source.index("def back", start)
        block = source[start:end]
        self.assertIn("if self._settings_path and self._settings_path[-1] == normalized:", block)
        self.assertIn("return", block)


    def test_duplicate_settings_root_navigation_uses_single_top_policy(self):
        source = NAVIGATION_NATIVE.read_text(encoding="utf-8")
        start = source.index("fun NavHostController.navigateToTopLevel(")
        end = source.index("fun NavHostController.navigateToCollector(", start)
        handler = source[start:end]
        self.assertIn("topLevelDestinations.none", handler)
        self.assertIn("launchSingleTop = true", handler)
        self.assertIn("restoreState = true", handler)
        self.assertIn("ReiAnixRoutes.SETTINGS", source)

    def test_native_back_dismisses_nested_settings_before_popping_route(self):
        settings = SETTINGS_NATIVE.read_text(encoding="utf-8")
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("BackHandler(enabled = selectedCategory != null)", settings)
        self.assertIn("selectedCategory = null", settings)
        self.assertIn("navController.popBackStack()", host)
        self.assertIn("onBackPressedDispatcher.addCallback(", activity)
        self.assertEqual(1, activity.count("onBackPressedDispatcher.addCallback("))

    def test_settings_screen_reacts_to_lifecycle_aware_native_state(self):
        settings = SETTINGS_NATIVE.read_text(encoding="utf-8")
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        self.assertIn("collectAsStateWithLifecycle()", settings)
        self.assertIn("rememberSaveable", settings)
        self.assertIn("BackHandler(enabled = selectedCategory != null)", settings)
        self.assertIn("ReiAnixSettingsRoute(", host)
        self.assertIn("settingsViewModel", host)
        self.assertNotIn("page.views", settings + host)
    def test_settings_focus_task_has_single_registration_path(self):
        source = SETTINGS.read_text(encoding="utf-8")
        start = source.index("def handle_category_focus")
        end = source.index("def build_category_tile", start)
        handler = source[start:end]
        self.assertEqual(handler.count("register_settings_task("), 0)
        self.assertIn("focus_task = start_task(", handler)
        start_task = source.index("def start_task")
        end_task = source.index("async def execute_action", start_task)
        helper = source[start_task:end_task]
        self.assertEqual(helper.count("register_settings_task(task)"), 1)

    def test_settings_background_tasks_are_registered(self):
        source = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("def start_task(handler, *args):", source)
        self.assertIn("register_settings_task(task)", source)
        direct_run_task_lines = [
            line for line in source.splitlines()
            if "page.run_task(" in line
        ]
        self.assertEqual(len(direct_run_task_lines), 1)
        self.assertIn("task = page.run_task(handler, *args)", direct_run_task_lines[0])

    def test_settings_inactive_instances_cannot_update_page(self):
        source = SETTINGS.read_text(encoding="utf-8")
        start = source.index("def safe_update")
        end = source.index("def notice", start)
        safe_update = source[start:end]
        self.assertIn("if not settings_is_active():", safe_update)
        self.assertIn("return", safe_update)


    def test_collector_route_uses_native_compose_lifecycle(self):
        navigation = NAVIGATION_NATIVE.read_text(encoding="utf-8")
        host = COMPOSE_HOST.read_text(encoding="utf-8")
        self.assertIn("ReiAnixRoutes.COLLECTOR", navigation)
        self.assertIn("ReiAnixCollectorJourneyRoute(", navigation)
        self.assertIn("rememberNavController()", host)
        self.assertIn("ReiAnixNavigationHost(", host)
        self.assertNotIn("collector_instance_generation", host)
        self.assertNotIn("CollectorActivity", navigation)

    def test_details_instance_guard_is_scoped_to_composable_lifecycle(self):
        details = DETAILS_NATIVE.read_text(encoding="utf-8")
        self.assertIn("var playerLaunchInFlight by remember(canonicalId)", details)
        self.assertIn("var detailsWasPaused by remember(canonicalId)", details)
        self.assertIn("DisposableEffect(lifecycleOwner)", details)
        self.assertIn("Lifecycle.Event.ON_PAUSE", details)
        self.assertIn("Lifecycle.Event.ON_RESUME", details)
        self.assertIn("lifecycleOwner.lifecycle.removeObserver(observer)", details)
        self.assertIn("if (!playerLaunchInFlight)", details)
        self.assertIn("navController.navigateToPlayer(", details)
    def test_android_back_still_has_single_native_dispatch(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertEqual(source.count("onBackPressedDispatcher.addCallback"), 1)
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)", source)
        self.assertNotIn('put("type", "android_back")', source)


if __name__ == "__main__":
    unittest.main()
