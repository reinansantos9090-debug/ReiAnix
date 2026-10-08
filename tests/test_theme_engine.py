import ast
import tempfile
import unittest
from pathlib import Path

from core.library_store import LibraryStore
from core.settings import SettingsStore
from core.ui import (
    DARK_THEME,
    _flet_material_theme,
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
        self.assertEqual(DARK_THEME.background, "#000000")
        self.assertEqual(DARK_THEME.surface, "#050505")
        self.assertEqual(DARK_THEME.surface_variant, "#080808")
        self.assertEqual(DARK_THEME.surface_raised, "#0A0A0A")
        self.assertEqual(DARK_THEME.primary, "#2579FF")

    def test_dark_palette_is_neutral_with_controlled_blue_accent(self):
        source = (ROOT / "core/ui.py").read_text(encoding="utf-8")
        expected = {
            'background="#000000"',
            'surface="#050505"',
            'surface_variant="#080808"',
            'surface_raised="#0A0A0A"',
            'text="#FFFFFF"',
            'text_muted="#B3B3B3"',
            'primary="#2579FF"',
            'border="#202020"',
            'divider="#202020"',
            'overlay="#000000D9"',
        }
        for value in expected:
            self.assertIn(value, source)
        for legacy in ("#151515", "#111111", "#16151F", "#252331", "#302D3E", "#2D2A3B", "#39364B", "#3C394C"):
            self.assertNotIn(legacy, source)

    def test_flet_material_theme_constructs_with_active_surface_roles(self):
        dark = _flet_material_theme(DARK_THEME)
        light = _flet_material_theme(LIGHT_THEME)

        for theme, expected in ((dark, DARK_THEME), (light, LIGHT_THEME)):
            self.assertEqual(theme.color_scheme.surface, expected.surface)
            self.assertEqual(theme.color_scheme.secondary_container, expected.surface_variant)
            self.assertEqual(theme.color_scheme.tertiary_container, expected.surface_variant)
            self.assertEqual(theme.color_scheme.surface_container, expected.surface)
            self.assertEqual(theme.color_scheme.surface_container_high, expected.surface_raised)
            self.assertEqual(theme.canvas_color, expected.background)
            self.assertEqual(theme.scaffold_bgcolor, expected.background)
            self.assertEqual(theme.card_bgcolor, expected.surface)
            self.assertEqual(theme.dialog_theme.bgcolor, expected.surface_raised)
            self.assertEqual(theme.bottom_sheet_theme.bgcolor, expected.surface_raised)
            self.assertEqual(theme.navigation_bar_theme.bgcolor, expected.background)
            self.assertEqual(theme.appbar_theme.bgcolor, expected.background)

    def test_flet_material_fallbacks_are_bound_to_active_theme_tokens(self):
        source = (ROOT / "core/ui.py").read_text(encoding="utf-8")
        for marker in (
            "def _flet_material_theme(tokens: ThemeTokens)",
            "color_scheme=",
            "surface=tokens.surface",
            "secondary_container=tokens.surface_variant",
            "tertiary_container=tokens.surface_variant",
            "surface_container=tokens.surface",
            "surface_container_high=tokens.surface_raised",
            "canvas_color=tokens.background",
            "card_bgcolor=tokens.surface",
            "scaffold_bgcolor=tokens.background",
            "appbar_theme=ft.AppBarTheme",
            "navigation_bar_theme=ft.NavigationBarTheme",
            "dialog_theme=ft.DialogTheme",
            "bottom_sheet_theme=ft.BottomSheetTheme",
            "disabled_color=tokens.text_muted",
            "divider_color=tokens.divider",
        ):
            self.assertIn(marker, source)

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
