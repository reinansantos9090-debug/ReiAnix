import unittest
from tests.native_main_contract import native_main_source


class NativeComposeEntrypointTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = native_main_source()

    def test_native_home_library_and_organize_routes_are_registered(self):
        for token in (
            "ReiAnixComposeLibraryHost",
            "ReiAnixRoutes.HOME",
            "ReiAnixRoutes.LIBRARY",
            "ReiAnixRoutes.ORGANIZE",
            "ReiAnixNavigationHost",
        ):
            self.assertIn(token, self.source)

    def test_root_settings_uses_native_compose_route(self):
        for token in (
            "ReiAnixSettingsRoute",
            "ReiAnixSettingsViewModel",
            "ReiAnixSettingsRepository",
            "ReiAnixRoutes.SETTINGS",
        ):
            self.assertIn(token, self.source)

    def test_nested_details_and_collector_screens_have_native_consumers(self):
        for token in (
            "ReiAnixDetailsRoute",
            "ReiAnixCollectorJourneyRoute",
            "ReiAnixRoutes.DETAILS",
            "ReiAnixRoutes.COLLECTOR",
        ):
            self.assertIn(token, self.source)

    def test_native_navigation_owns_the_application_shell(self):
        self.assertIn("ReiAnixNavigationHost", self.source)
        self.assertIn("NavHostController", self.source)
        self.assertIn("rememberNavController()", self.source)
        self.assertIn("ReiAnixComposeRoot", self.source)

    def test_main_entrypoint_delegates_to_the_native_application_graph(self):
        self.assertIn("internal object MainEntryPoint", self.source)
        self.assertIn("dependencies.bootstrap.start()", self.source)
        self.assertIn("ReiAnixAppDependencies", self.source)
        self.assertIn("ReiAnixApplication", self.source)
