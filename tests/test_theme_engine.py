import ast
import tempfile
import unittest
from pathlib import Path

from core.library_store import LibraryStore
from core.settings import SettingsStore
from core.ui import (
    DARK_THEME,
    LIGHT_THEME,
    ThemeTokens,
    effective_theme_mode,
    normalize_theme_mode,
    theme_tokens,
)


ROOT = Path(__file__).resolve().parents[1]


class ThemeEngineTests(unittest.TestCase):
    def test_normalize_theme_mode_has_safe_fallback(self):
        self.assertEqual(normalize_theme_mode("dark"), "dark")
        self.assertEqual(normalize_theme_mode("light"), "light")
        self.assertEqual(normalize_theme_mode("system"), "system")
        self.assertEqual(normalize_theme_mode("invalid"), "system")
        self.assertEqual(normalize_theme_mode(None), "system")

    def test_system_resolves_from_platform_brightness(self):
        self.assertEqual(effective_theme_mode("system", "dark"), "dark")
        self.assertEqual(effective_theme_mode("system", "light"), "light")
        self.assertEqual(effective_theme_mode("system", None), "light")
        self.assertEqual(effective_theme_mode("dark", "light"), "dark")
        self.assertEqual(effective_theme_mode("light", "dark"), "light")

    def test_dark_and_light_have_complete_shared_tokens(self):
        required = {
            "background",
            "surface",
            "surface_variant",
            "surface_raised",
            "text",
            "text_muted",
            "text_on_accent",
            "text_on_overlay",
            "primary",
            "secondary",
            "border",
            "divider",
            "error",
            "success",
            "warning",
            "overlay",
            "favorite",
            "mode",
        }
        for theme in (DARK_THEME, LIGHT_THEME):
            self.assertIsInstance(theme, ThemeTokens)
            self.assertEqual(required, set(theme.__dataclass_fields__))
            for field in required:
                self.assertTrue(getattr(theme, field))

        self.assertNotEqual(DARK_THEME.background, LIGHT_THEME.background)
        self.assertNotEqual(DARK_THEME.surface, LIGHT_THEME.surface)
        self.assertNotEqual(DARK_THEME.text, LIGHT_THEME.text)
        self.assertNotEqual(DARK_THEME.text_muted, LIGHT_THEME.text_muted)

    def test_dark_palette_is_neutral_with_controlled_blue_accent(self):
        source = (ROOT / "core/ui.py").read_text(encoding="utf-8")
        expected = {
            'background="#050505"',
            'surface="#0B0B0B"',
            'surface_variant="#111111"',
            'surface_raised="#171717"',
            'text="#F5F5F5"',
            'text_muted="#B8B8B8"',
            'primary="#3D8BFF"',
            'border="#292929"',
            'divider="#242424"',
            'overlay="#000000D9"',
        }
        for value in expected:
            self.assertIn(value, source)
        for legacy in ("#16151F", "#252331", "#302D3E", "#2D2A3B", "#39364B", "#3C394C"):
            self.assertNotIn(legacy, source)

    def test_theme_tokens_resolve_without_parallel_theme_store(self):
        self.assertIs(theme_tokens("dark"), DARK_THEME)
        self.assertIs(theme_tokens("light"), LIGHT_THEME)
        self.assertIs(theme_tokens("system", "dark"), DARK_THEME)
        self.assertIs(theme_tokens("system", "light"), LIGHT_THEME)

    def test_theme_preference_persists_through_existing_settings_store(self):
        with tempfile.TemporaryDirectory() as root:
            store = LibraryStore(root)
            settings = SettingsStore(store)

            settings.set("appearance.theme", "light")
            self.assertEqual(SettingsStore(store).get("appearance.theme"), "light")

            settings.set("appearance.theme", "dark")
            self.assertEqual(SettingsStore(store).get("appearance.theme"), "dark")

            settings.set("appearance.theme", "system")
            self.assertEqual(SettingsStore(store).get("appearance.theme"), "system")

    def test_corrupt_theme_preference_falls_back_without_touching_other_settings(self):
        with tempfile.TemporaryDirectory() as root:
            store = LibraryStore(root)
            store.set_preference("appearance.theme", "definitely-invalid")
            store.set_preference("player.resume", "false")

            settings = SettingsStore(store)
            self.assertEqual(settings.get("appearance.theme"), "dark")
            self.assertFalse(settings.get("player.resume"))

    def test_views_do_not_keep_the_old_competing_dark_palette(self):
        view_paths = (
            ROOT / "views/home_view.py",
            ROOT / "views/details_view.py",
            ROOT / "views/organize_view.py",
            ROOT / "views/settings_view.py",
            ROOT / "views/recovery_view.py",
        )
        legacy_palette = (
            "#16151F",
            "#252331",
            "#2D2A3B",
            "#F7F5FA",
            "#AAA7B6",
            "#39364B",
        )
        for path in view_paths:
            source = path.read_text(encoding="utf-8")
            self.assertIn("activate_theme_for_page", source, path.name)
            for color in legacy_palette:
                self.assertNotIn(color, source, f"{path.name} retains {color}")

    def test_main_owns_runtime_theme_apply_and_system_brightness_hook(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn("apply_page_theme(page, settings.get(\"appearance.theme\"))", source)
        self.assertIn('if setting_key == "appearance.theme":', source)
        self.assertIn("page.on_platform_brightness_change", source)
        self.assertNotIn("page.theme_mode =", source)

    def test_settings_has_explicit_three_mode_selection_and_selection_indicator(self):
        source = (ROOT / "views/settings_view.py").read_text(encoding="utf-8")
        for label in ("Sistema", "Claro", "Escuro"):
            self.assertIn(label, source)
        self.assertIn("button_cls = ft.FilledButton if selected else ft.OutlinedButton", source)
        self.assertIn("return button_cls(button_label, on_click=handle)", source)
        self.assertIn('settings.get("appearance.theme") == mode', source)

    def test_theme_change_does_not_route_through_storage_or_native_player(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        tree = ast.parse(source, filename="main.py")
        runtime = next(
            node for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name == "apply_settings_runtime"
        )
        theme_if = next(
            node for node in runtime.body
            if isinstance(node, ast.If)
            and isinstance(node.test, ast.Compare)
            and isinstance(node.test.left, ast.Name)
            and node.test.left.id == "setting_key"
            and len(node.test.ops) == 1
            and isinstance(node.test.ops[0], ast.Eq)
            and len(node.test.comparators) == 1
            and isinstance(node.test.comparators[0], ast.Constant)
            and node.test.comparators[0].value == "appearance.theme"
        )
        theme_calls = [
            node for node in ast.walk(theme_if)
            if isinstance(node, ast.Call)
        ]
        call_names = {
            node.func.id
            for node in theme_calls
            if isinstance(node.func, ast.Name)
        }
        self.assertIn("apply_page_theme", call_names)
        self.assertIn("render_current", call_names)
        self.assertTrue(any(isinstance(node, ast.Return) for node in theme_if.body))
        self.assertFalse(
            any(
                isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id == "library"
                and node.func.attr == "configure_settings"
                for node in theme_calls
            )
        )

        theme_index = runtime.body.index(theme_if)
        following = runtime.body[theme_index + 1:]
        self.assertTrue(
            any(
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id == "library"
                and node.func.attr == "configure_settings"
                for statement in following
                for node in ast.walk(statement)
            )
        )
        self.assertTrue(
            any(
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id == "_clear_screen_cache"
                for node in theme_calls
            )
        )


if __name__ == "__main__":
    unittest.main()
