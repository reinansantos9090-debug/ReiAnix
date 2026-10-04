import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "main.py"


class Prompt28FletUiRemovalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = MAIN.read_text(encoding="utf-8")
        start = cls.source.index("    def _build_screen(")
        end = cls.source.index("    def _settings_view_paths()", start)
        cls.build_screen = cls.source[start:end]

    def test_library_route_has_no_flet_domain_ui(self):
        block_start = self.build_screen.index('        elif route == "library":')
        block_end = self.build_screen.index('        elif route == "organize":', block_start)
        block = self.build_screen[block_start:block_end]
        self.assertIn("control = ft.Container(expand=True)", block)
        self.assertNotIn("LibraryView.build(", block)

    def test_root_settings_uses_compose_without_building_flet_ui(self):
        block_start = self.build_screen.index('        elif route == "settings":')
        block = self.build_screen[block_start:]
        self.assertIn("if fixed_settings_path == () and bridge.available:", block)
        native_branch = block[:block.index("            else:", block.index("if fixed_settings_path == ()"))]
        self.assertIn("control = ft.Container(expand=True)", native_branch)
        self.assertNotIn("SettingsView.build(", native_branch)

    def test_settings_flet_view_is_retained_for_nested_and_unavailable_fallbacks(self):
        block_start = self.build_screen.index('        elif route == "settings":')
        block = self.build_screen[block_start:]
        self.assertIn("else:", block)
        fallback_block = block[block.index("            else:"):]
        self.assertIn("control = SettingsView.build(", fallback_block)

    def test_unmigrated_flet_screens_still_have_real_consumers(self):
        for call in (
            "HomeView.build(",
            "OrganizeView.build(",
            "DetailView.build(",
            "CollectorView.build(",
        ):
            self.assertIn(call, self.build_screen)

    def test_flet_navigation_projection_and_compose_hosts_are_preserved(self):
        self.assertIn("page.views.extend(views)", self.source)
        self.assertIn("page.on_view_pop = handle_flet_view_pop", self.source)
        self.assertIn("async def _show_compose_library():", self.source)
        self.assertIn("async def _show_compose_settings():", self.source)
        self.assertIn("ComposeLibraryBridge(", self.source)
        self.assertIn("ComposeSettingsBridge(", self.source)


if __name__ == "__main__":
    unittest.main()
