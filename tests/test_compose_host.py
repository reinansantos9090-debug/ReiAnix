import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
)
HOST = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
)
NAVIGATION = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
HOME = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"
HOST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"


class ComposeHostTests(unittest.TestCase):
    def test_main_activity_attaches_reversible_compose_library_host(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        host = HOST.read_text(encoding="utf-8")
        self.assertIn("ReiAnixComposeLibraryHost", source)
        self.assertIn("composeLibraryHost.show(", source)
        self.assertIn("ReiAnixRoutes.LIBRARY", source)
        self.assertIn("composeLibraryHost.hide()", source)
        self.assertIn("ComposeView", host)
        self.assertIn("ReiAnixComposeRoot", host)
        self.assertIn("ReiAnixNavigationHost", host)
        self.assertIn("startDestination: String = ReiAnixRoutes.LIBRARY", host)
        self.assertIn("startDestination = startDestination", host)
        self.assertIn("showBottomNavigation = true", host)


    def test_library_is_a_single_existing_native_navigation_route(self):
        navigation = NAVIGATION.read_text(encoding="utf-8")
        host = HOST.read_text(encoding="utf-8")
        self.assertIn('const val LIBRARY = "library"', navigation)
        self.assertIn("composable(ReiAnixRoutes.LIBRARY)", navigation)
        self.assertIn("ReiAnixLibraryRoute(", navigation)
        self.assertIn("ReiAnixNavigationHost", host)
        self.assertIn("rememberNavController()", host)
        self.assertNotIn("class ComposeNavigationController", navigation)
        self.assertNotIn("class LibraryNavigation", navigation)

    def test_compose_home_exposes_the_real_library_entry_point(self):
        home = HOME.read_text(encoding="utf-8")
        navigation = NAVIGATION.read_text(encoding="utf-8")
        self.assertIn("onOpenLibrary", home)
        self.assertIn("onOpenLibrary = { navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY) }", home)
        self.assertIn("fun NavHostController.navigateToTopLevel(", navigation)
        self.assertIn("ReiAnixRoutes.LIBRARY", navigation)

    def test_compose_details_and_back_use_native_navigation_authority(self):
        navigation = NAVIGATION.read_text(encoding="utf-8")
        host = HOST.read_text(encoding="utf-8")
        details = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt").read_text(encoding="utf-8")
        self.assertIn("ReiAnixRoutes.DETAILS", navigation)
        self.assertIn("fun NavHostController.navigateToDetails(", navigation)
        self.assertIn("fun NavHostController.navigateToPlayer(", navigation)
        self.assertIn("navigateToPlayer(", details)
        self.assertIn("controller.popBackStack()", host)
        self.assertIn("fun handleBack(): Boolean", host)
        self.assertNotIn("class ComposeNavigationController", navigation)

    def test_library_route_uses_canonical_compose_path_without_list_positions(self):
        host = HOST.read_text(encoding="utf-8")
        navigation = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
        ).read_text(encoding="utf-8")
        library = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"
        ).read_text(encoding="utf-8")
        models = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/LibraryUiModels.kt"
        ).read_text(encoding="utf-8")

        # The host delegates to Navigation Compose; it does not own the Library
        # route directly.
        self.assertIn("ReiAnixNavigationHost", host)
        self.assertIn("startDestination: String = ReiAnixRoutes.LIBRARY", host)
        self.assertIn("startDestination = startDestination", host)

        # Navigation Compose owns the LIBRARY route and mounts the real Library.
        self.assertIn("composable(ReiAnixRoutes.LIBRARY)", navigation)
        self.assertIn("ReiAnixLibraryRoute(", navigation)
        self.assertIn("composable(", navigation)
        self.assertIn("ReiAnixRoutes.DETAILS", navigation)

        # The Library owns card identity and sends the canonical anime ID into
        # the real Details navigation route.
        self.assertIn("anime.stableKey", library)
        self.assertIn("navController.navigateToDetails", library)
        self.assertIn('origin = ReiAnixRoutes.LIBRARY', library)

        # The stable key is derived only from the canonical database ID.
        self.assertIn("val stableKey: String", models)
        self.assertIn('get() = "anime:" + id', models)

    def test_details_route_is_the_real_compose_destination_for_library_host(self):
        navigation_host = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
        ).read_text(encoding="utf-8")
        details = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
        ).read_text(encoding="utf-8")
        self.assertIn('const val DETAILS = "details/{animeId}?origin={origin}"', navigation_host)
        self.assertIn("ReiAnixDetailsRoute(", navigation_host)
        self.assertIn("animeId = args.animeId", navigation_host)
        # Details keeps the current implementation's remembered state flow;
        # assert the semantic contract without depending on one-line formatting.
        self.assertIn("val detailsStateFlow = remember(viewModel, canonicalId)", details)
        self.assertIn("viewModel.detailsState(canonicalId)", details)
        self.assertIn("detailsState(", details)
        self.assertIn("navController.navigateToPlayer(", details)
        self.assertIn("viewModel::toggleFavorite", details)

    def test_main_activity_back_is_fully_handled_by_visible_compose_shell(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        host = HOST.read_text(encoding="utf-8")
        self.assertIn("composeLibraryHost.handleBack()", source)
        self.assertIn("BACK_COMPOSE_SHELL_UNHANDLED", source)
        self.assertIn("fun handleBack(): Boolean", host)
        self.assertIn("controller.previousBackStackEntry != null", host)
        self.assertIn("navigateToRequestedDestination", host)
        self.assertIn("handleRootBack()", host)
        self.assertIn("HOME_DOUBLE_BACK_WINDOW_MS", host)
        self.assertIn("lastHomeBackAtElapsedRealtimeMs", host)
        self.assertIn("Toast.makeText", host)
        self.assertIn("activity.finish()", host)
        self.assertNotIn("private fun handleHomeBack()", host)
        self.assertNotIn("activity.finishAndRemoveTask()", host)
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)", source)
        self.assertIn("BACK_COMPOSE_SHELL_RESTORED", source)
        self.assertNotIn("engine.navigationChannel.popRoute()", source)
        self.assertNotIn("flutterEngine", source)
        self.assertIn("setId(R.id.reianix_compose_app_shell)", host)

    def test_home_back_contract_uses_native_double_back_policy(self):
        host = HOST.read_text(encoding="utf-8")
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        handler_start = host.index("fun handleBack(): Boolean")
        handler_end = host.index("fun dispose()", handler_start)
        handler = host[handler_start:handler_end]
        dispatch_start = activity.index("private fun installSystemBackHandler")
        dispatch_end = activity.index("private fun persistedSafTreeUris", dispatch_start)
        dispatch = activity[dispatch_start:dispatch_end]
        self.assertIn("ReiAnixRoutes.HOME,", handler)
        self.assertIn("handleRootBack()", handler)
        self.assertIn("HOME_DOUBLE_BACK_WINDOW_MS", handler)
        self.assertIn("lastHomeBackAtElapsedRealtimeMs", handler)
        self.assertIn("activity.finish()", handler)
        self.assertIn("Toast.makeText", handler)
        self.assertNotIn("Python NavigationController", handler)
        self.assertEqual(dispatch.count("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)"), 1)
        self.assertIn("BACK_COMPOSE_SHELL_UNHANDLED", dispatch)
        self.assertIn("BACK_COMPOSE_SHELL_RESTORED", dispatch)
        self.assertIn("if (systemBackDispatchPosted)", dispatch)
        self.assertIn("finally {", dispatch)
        self.assertIn("window.decorView.post { systemBackDispatchPosted = false }", dispatch)
        self.assertNotIn("engine.navigationChannel.popRoute()", dispatch)

    def test_compose_internal_back_pops_only_one_native_stack_entry(self):
        host = HOST.read_text(encoding="utf-8")
        handler_start = host.index("fun handleBack(): Boolean")
        handler_end = host.index("fun dispose()", handler_start)
        handler = host[handler_start:handler_end]

        self.assertIn("if (controller.previousBackStackEntry != null)", handler)
        self.assertEqual(handler.count("controller.popBackStack()"), 1)
        self.assertIn("controller.navigateToTopLevel(ReiAnixRoutes.HOME)", handler)
        self.assertIn("controller.navigateToTopLevel(ReiAnixRoutes.SETTINGS)", handler)

    def test_compose_navigation_uses_stable_saveable_scoping(self):
        host = HOST.read_text(encoding="utf-8")
        search = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("rememberNavController()", host)
        self.assertIn("rememberSaveable", search)
        self.assertIn("LaunchedEffect(focusRequester)", search)
        self.assertNotIn("LaunchedEffect(Unit)", search)

    def test_main_activity_is_the_native_android_launcher(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        application = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixApplication.kt").read_text(encoding="utf-8")
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn("class MainActivity : ComponentActivity()", source)
        self.assertIn("class ReiAnixApplication : Application()", application)
        self.assertIn('android:name=".MainActivity"', manifest)
        self.assertIn('android:name=".ReiAnixApplication"', manifest)
        self.assertIn("android.intent.action.MAIN", manifest)
        self.assertIn("android.intent.category.LAUNCHER", manifest)
        self.assertNotIn("FlutterFragmentActivity", source)
        self.assertNotIn("FlutterApplication", application)
        self.assertNotIn("flutterEngine", source)
        self.assertNotIn("engine.navigationChannel.popRoute()", source)

    def test_compose_root_back_uses_native_exit_policy_and_restores_hidden_host(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        host = HOST.read_text(encoding="utf-8")
        root = host[host.index("fun handleBack(): Boolean"):host.index("fun dispose()", host.index("fun handleBack(): Boolean"))]
        self.assertIn("handleRootBack()", root)
        self.assertIn("HOME_DOUBLE_BACK_WINDOW_MS", root)
        self.assertIn("activity.finish()", root)
        self.assertIn("Toast.makeText", root)
        self.assertNotIn("Python NavigationController", root)
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)", source)
        self.assertIn("BACK_COMPOSE_SHELL_RESTORED", source)
        self.assertNotIn("engine.navigationChannel.popRoute()", source)
        self.assertNotIn("flutterEngine", source)

    def test_compose_details_back_is_a_single_navcontroller_pop(self):
        details = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("onBack = { navController.popBackStack() }", details)
        self.assertNotIn("onBack = { navController.popBackStack(); navController.popBackStack() }", details)

    def test_player_return_pops_handoff_route_once_after_native_activity_resume(self):
        player = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/player/ReiAnixPlayer.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("hostStoppedAfterLaunch", player)
        self.assertIn("Lifecycle.Event.ON_STOP", player)
        self.assertIn("Lifecycle.Event.ON_RESUME", player)
        self.assertIn("hostStoppedAfterLaunch = false", player)
        self.assertIn("navController.popBackStack()", player)
        self.assertIn("BackHandler", player)
        self.assertIn("rememberSaveable(args.episodeId, args.animeId)", player)

    def test_search_details_preserves_search_as_navigation_origin(self):
        search = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("origin = ReiAnixRoutes.SEARCH", search)
        self.assertIn("onBack = { navController.popBackStack() }", search)


if __name__ == "__main__":
    unittest.main()
