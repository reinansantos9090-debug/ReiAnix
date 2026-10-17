from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"
NAVIGATION = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
COMPOSE_HOST = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
SETTINGS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
COVERAGE = ROOT / "docs/PROMPT_23_COMPOSE_INTERFACE_COVERAGE.md"


class Prompt23ComposeInterfaceMigrationContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.navigation = NAVIGATION.read_text(encoding="utf-8")
        cls.compose_host = COMPOSE_HOST.read_text(encoding="utf-8")
        cls.settings = SETTINGS.read_text(encoding="utf-8")
        cls.build_screen = cls.main[
            cls.main.index("    def _build_screen("):
            cls.main.index("    def _settings_view_paths()", cls.main.index("    def _build_screen("))
        ]

    def test_legacy_flet_screen_modules_are_not_imported_at_module_startup(self):
        module_prefix = self.main[:self.main.index("async def main(page: ft.Page):")]
        self.assertNotRegex(
            module_prefix,
            r"from views\.(home_view|details_view|organize_view|settings_view|collector_view|recovery_view) import",
        )
        self.assertEqual(
            self.main.count("from views.home_view import HomeView"), 1
        )
        self.assertEqual(
            self.main.count("from views.details_view import DetailView"), 1
        )
        self.assertEqual(
            self.main.count("from views.organize_view import OrganizeView"), 1
        )
        self.assertEqual(
            self.main.count("from views.settings_view import SettingsView"), 1
        )
        self.assertEqual(
            self.main.count("from views.collector_view import CollectorView"), 1
        )
        self.assertEqual(
            self.main.count("from views.recovery_view import RecoveryView"), 1
        )

    def test_migrated_screens_keep_real_non_compose_fallbacks_only(self):
        cases = (
            ('if route == "home":', 'elif route == "library":',
             "if compose_primary_ui:", "from views.home_view import HomeView", "HomeView.build("),
            ('elif route == "organize":', 'elif route == "details":',
             "if bridge.available:", "from views.organize_view import OrganizeView", "OrganizeView.build("),
            ('elif route == "details":', 'elif route == "collector":',
             "if compose_primary_ui:", "from views.details_view import DetailView", "DetailView.build("),
            ('elif route == "collector":', 'elif route == "settings":',
             "if compose_primary_ui:", "from views.collector_view import CollectorView", "CollectorView.build("),
        )
        for start, end, native_guard, legacy_import, legacy_builder in cases:
            with self.subTest(route=start):
                block = self.build_screen[
                    self.build_screen.index(start):
                    self.build_screen.index(end, self.build_screen.index(start))
                ]
                self.assertIn(native_guard, block)
                self.assertIn("control = ft.Container(expand=True)", block)
                self.assertIn(legacy_import, block)
                self.assertIn(legacy_builder, block)
                native_branch = block[
                    block.index(native_guard):
                    block.index("            else:", block.index(native_guard))
                ]
                self.assertNotIn(legacy_import, native_branch)
                self.assertNotIn(legacy_builder, native_branch)

    def test_android_compose_owns_root_and_nested_settings_without_hidden_flet_views(self):
        start = self.build_screen.index('elif route == "settings":')
        block = self.build_screen[start:]
        self.assertIn("if bridge.available:", block)
        native_branch = block[:block.index("            else:", block.index("if bridge.available:"))]
        self.assertIn("control = ft.Container(expand=True)", native_branch)
        self.assertNotIn("SettingsView.build(", native_branch)
        self.assertIn("from views.settings_view import SettingsView", block)
        self.assertIn("control = SettingsView.build(", block)
        self.assertIn("var selectedCategory by", self.settings)
        self.assertIn("BackHandler(enabled = selectedCategory != null)", self.settings)
        self.assertIn("onBack = { selectedCategory = null }", self.settings)

    def test_every_declared_compose_settings_category_is_managed_natively(self):
        model_start = self.settings.index("fun defaultCategories():")
        managed_start = self.settings.index("private val NativeManagedSettingsCategories")
        model_block = self.settings[model_start:managed_start]
        declared = set(re.findall(
            r'ReiAnixSettingsCategoryUiModel\("([^"]+)"',
            model_block,
        ))
        managed_end = self.settings.index("\n)", managed_start)
        managed_block = self.settings[managed_start:managed_end]
        managed = set(re.findall(r'^\s*"([^"]+)"\s*,?, managed_block, re.MULTILINE))
        self.assertTrue(declared, "No native settings categories were found")
        self.assertEqual(declared, managed)
        self.assertIn('onOpenCategory = { label ->', self.settings)
        self.assertIn("selectedCategory = label", self.settings)

    def test_compose_destinations_are_wired_to_the_existing_native_host(self):
        for route in (
            "HOME", "LIBRARY", "MY_LIST", "ORGANIZE", "SEARCH", "SETTINGS",
            "STORAGE", "RECOVERY", "COLLECTOR", "DETAILS", "PLAYER",
        ):
            self.assertIn("ReiAnixRoutes." + route, self.navigation)
        for native_screen in (
            "ReiAnixOrganizeRoute(",
            "ReiAnixSettingsRoute(",
            "ReiAnixRecoveryRoute(",
            "ReiAnixStorageRoute(",
            "navigateToCollector()",
        ):
            self.assertIn(native_screen, self.compose_host)
        self.assertIn("ReiAnixHomeRoute(", self.navigation)
        self.assertIn("ReiAnixLibraryRoute(", self.navigation)
        self.assertIn("ReiAnixSearchRoute(", self.navigation)
        self.assertIn("ReiAnixDetailsRoute(", self.navigation)

    def test_coverage_matrix_records_the_actual_cutover_and_remaining_fallback(self):
        report = COVERAGE.read_text(encoding="utf-8")
        for token in (
            "Prompt 23/29",
            "views/home_view.py",
            "views/details_view.py",
            "views/organize_view.py",
            "views/settings_view.py",
            "views/collector_view.py",
            "views/recovery_view.py",
            "ReiAnixSettingsRoute",
            "fallback não Android",
            "não declara o projeto 100% Kotlin",
            "matriz",
        ):
            self.assertIn(token, report)


if __name__ == "__main__":
    unittest.main()
, managed_block, re.MULTILINE))
        self.assertTrue(declared, "No native settings categories were found")
        self.assertEqual(declared, managed)
        self.assertIn('onOpenCategory = { label ->', self.settings)
        self.assertIn("selectedCategory = label", self.settings)

    def test_compose_destinations_are_wired_to_the_existing_native_host(self):
        for route in (
            "HOME", "LIBRARY", "MY_LIST", "ORGANIZE", "SEARCH", "SETTINGS",
            "STORAGE", "RECOVERY", "COLLECTOR", "DETAILS", "PLAYER",
        ):
            self.assertIn("ReiAnixRoutes." + route, self.navigation)
        for native_screen in (
            "ReiAnixOrganizeRoute(",
            "ReiAnixSettingsRoute(",
            "ReiAnixRecoveryRoute(",
            "ReiAnixStorageRoute(",
            "navigateToCollector()",
        ):
            self.assertIn(native_screen, self.compose_host)
        self.assertIn("ReiAnixHomeRoute(", self.navigation)
        self.assertIn("ReiAnixLibraryRoute(", self.navigation)
        self.assertIn("ReiAnixSearchRoute(", self.navigation)
        self.assertIn("ReiAnixDetailsRoute(", self.navigation)

    def test_coverage_matrix_records_the_actual_cutover_and_remaining_fallback(self):
        report = COVERAGE.read_text(encoding="utf-8")
        for token in (
            "Prompt 23/29",
            "views/home_view.py",
            "views/details_view.py",
            "views/organize_view.py",
            "views/settings_view.py",
            "views/collector_view.py",
            "views/recovery_view.py",
            "ReiAnixSettingsRoute",
            "fallback não Android",
            "não declara o projeto 100% Kotlin",
            "matriz",
        ):
            self.assertIn(token, report)


if __name__ == "__main__":
    unittest.main()
