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
NAVIGATION = ROOT / "core/navigation.py"
HOME = ROOT / "views/home_view.py"
MAIN = ROOT / "main.py"


class Prompt91ComposeHostTests(unittest.TestCase):
    def test_main_activity_attaches_reversible_compose_library_host(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        host = HOST.read_text(encoding="utf-8")
        self.assertIn("ReiAnixComposeLibraryHost", source)
        self.assertIn("composeLibraryHost.show()", source)
        self.assertIn("composeLibraryHost.hide()", source)
        self.assertIn("ComposeView", host)
        self.assertIn("ReiAnixComposeRoot", host)
        self.assertIn("ReiAnixNavigationHost", host)
        self.assertIn("startDestination = ReiAnixRoutes.LIBRARY", host)
        self.assertIn("showBottomNavigation = false", host)

    def test_library_is_a_single_existing_navigation_route(self):
        navigation = NAVIGATION.read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn('"library"', navigation)
        self.assertIn('navigation.push("library")', main)
        self.assertIn('route == "library"', main)
        self.assertNotIn("class ComposeNavigationController", main)
        self.assertNotIn("class LibraryNavigation", main)

    def test_home_exposes_the_real_library_entry_point(self):
        home = HOME.read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn("on_open_library", home)
        self.assertIn('tooltip="Biblioteca"', home)
        self.assertIn("on_open_library=navigate_library", main)

    def test_python_keeps_details_and_back_in_the_existing_navigation_authority(self):
        navigation = NAVIGATION.read_text(encoding="utf-8")
        main = MAIN.read_text(encoding="utf-8")
        self.assertIn("library", navigation)
        self.assertIn('if navigation.current == "library":', main)
        self.assertIn('navigation.back()', main)
        self.assertIn('navigate_details(anime', main)

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
        self.assertIn("startDestination = ReiAnixRoutes.LIBRARY", host)

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

    def test_main_activity_back_can_pop_nested_compose_destination(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        host = HOST.read_text(encoding="utf-8")
        self.assertIn("composeLibraryHost.handleBack()", source)
        self.assertIn("fun handleBack(): Boolean", host)
        self.assertIn("controller.previousBackStackEntry != null", host)
        self.assertIn("controller.popBackStack(ReiAnixRoutes.LIBRARY, false)", host)

    def test_main_activity_does_not_replace_flutter_host(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("class MainActivity : FlutterFragmentActivity()", source)
        self.assertNotIn("class MainActivity : ComponentActivity()", source)


if __name__ == "__main__":
    unittest.main()
