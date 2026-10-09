import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

COMPOSE_UI_FILES = (
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixStateComponents.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/player/ReiAnixPlayer.kt",
    "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt",
)

GENERIC_THEME_TOKENS = (
    "background",
    "surface",
    "surfaceVariant",
    "text",
    "textMuted",
    "primary",
    "primaryContainer",
    "border",
    "divider",
    "onPrimary",
)


class ComposeThemeConsistencyTests(unittest.TestCase):
    def test_screen_ui_uses_material_theme_for_generic_colors(self):
        for relative in COMPOSE_UI_FILES:
            source = (ROOT / relative).read_text(encoding="utf-8")
            for token in GENERIC_THEME_TOKENS:
                pattern = re.compile(
                    rf"ReiAnixTokens\.Colors\.{re.escape(token)}\b"
                )
                self.assertIsNone(
                    pattern.search(source),
                    msg=f"{relative} bypasses MaterialTheme with generic color token {token}",
                )

    def test_design_tokens_remain_the_source_for_theme_palettes(self):
        tokens = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt"
        ).read_text(encoding="utf-8")
        theme = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt"
        ).read_text(encoding="utf-8")

        expected_tokens = {
            "val background = Color(0xFF000000)",
            "val surface = Color(0xFF050505)",
            "val surfaceVariant = Color(0xFF080808)",
            "val surfaceRaised = Color(0xFF0A0A0A)",
            "val surfaceDialog = Color(0xFF0A0A0A)",
            "val surfaceNavigation = Color(0xFF000000)",
            "val divider = Color(0xFF202020)",
            "val border = Color(0xFF202020)",
            "val text = Color(0xFFFFFFFF)",
            "val textMuted = Color(0xFFB3B3B3)",
            "val textTertiary = Color(0xFF777777)",
            "val textDisabled = Color(0xFF666666)",
            "val primary = Color(0xFF2579FF)",
        }
        for token in expected_tokens:
            self.assertIn(token, tokens)
        self.assertIn("val lightPrimary = Color(0xFF2563C7)", tokens)
        self.assertIn("background = ReiAnixTokens.Colors.background", theme)
        self.assertIn("surface = ReiAnixTokens.Colors.surface", theme)
        self.assertIn("surfaceContainerLow = ReiAnixTokens.Colors.backgroundSecondary", theme)
        self.assertIn("surfaceContainer = ReiAnixTokens.Colors.surface", theme)
        self.assertIn("surfaceContainerHigh = ReiAnixTokens.Colors.surfaceRaised", theme)
        self.assertIn("surfaceContainerHighest = ReiAnixTokens.Colors.surfaceDialog", theme)
        self.assertIn("primary = ReiAnixTokens.Colors.primary", theme)
        self.assertIn("primary = ReiAnixTokens.Colors.lightPrimary", theme)
        forbidden_blue_surfaces = (
            "02070D",
            "07111A",
            "0A1B2B",
            "102537",
            "0D2134",
            "050C15",
            "0D3B73",
            "1B456E",
            "163149",
        )
        for color in forbidden_blue_surfaces:
            self.assertNotIn(color, tokens)
            self.assertNotIn(color, theme)

    def test_legacy_gray_surface_palette_is_absent_from_compose_ui_sources(self):
        legacy = ("0xFF151515", "0xFF111111", "#151515", "#111111")
        for relative in COMPOSE_UI_FILES:
            source = (ROOT / relative).read_text(encoding="utf-8")
            for color in legacy:
                self.assertNotIn(
                    color,
                    source,
                    msg=f"{relative} still contains legacy gray surface {color}",
                )

    def test_legacy_gray_surface_palette_is_absent_from_compose_design_tokens(self):
        tokens = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt").read_text(encoding="utf-8")
        theme = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt").read_text(encoding="utf-8")
        for legacy in ("0xFF151515", "0xFF111111"):
            self.assertNotIn(legacy, tokens)
            self.assertNotIn(legacy, theme)
        self.assertIn("surfaceContainerLowest = ReiAnixTokens.Colors.background", theme)
        self.assertIn("surfaceContainerLow = ReiAnixTokens.Colors.backgroundSecondary", theme)
        self.assertIn("surfaceContainer = ReiAnixTokens.Colors.surface", theme)
        self.assertIn("surfaceContainerHigh = ReiAnixTokens.Colors.surfaceRaised", theme)


if __name__ == "__main__":
    unittest.main()


TYPOGRAPHY_SCREEN_FILES = {
    "Home": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt",
    "Library": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt",
    "Details": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt",
    "Search": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt",
    "Settings": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt",
}


class ReiAnixTypographyConsistencyTests(unittest.TestCase):
    def test_canonical_typography_is_compact_and_keeps_a_clear_hierarchy(self):
        token_source = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt"
        ).read_text(encoding="utf-8")

        def font_size(role):
            match = re.search(
                rf"val {re.escape(role)} = TextStyle\(\s*fontSize = ([0-9]+)\.sp",
                token_source,
            )
            self.assertIsNotNone(match, msg=f"Missing typography role: {role}")
            return int(match.group(1))

        self.assertEqual(font_size("display"), 26)
        self.assertEqual(font_size("screenTitle"), 22)
        self.assertEqual(font_size("sectionTitle"), 17)
        self.assertEqual(font_size("itemTitle"), 14)
        self.assertEqual(font_size("body"), 14)
        self.assertEqual(font_size("bodySecondary"), 13)
        self.assertEqual(font_size("metadata"), 12)
        self.assertEqual(font_size("caption"), 11)
        self.assertEqual(font_size("settingsCategory"), 15)
        self.assertEqual(font_size("settingsDescription"), 13)
        self.assertGreater(font_size("screenTitle"), font_size("sectionTitle"))
        self.assertGreater(font_size("sectionTitle"), font_size("itemTitle"))
        self.assertIn("val cardTitle = itemTitle", token_source)
        self.assertIn("displayLarge = TypographyTokens.display", token_source)
        self.assertIn("headlineLarge = TypographyTokens.screenTitle", token_source)
        self.assertIn("titleLarge = TypographyTokens.sectionTitle", token_source)
        self.assertIn("titleMedium = TypographyTokens.itemTitle", token_source)
        self.assertIn("bodyLarge = TypographyTokens.body", token_source)
        self.assertIn("bodyMedium = TypographyTokens.bodySecondary", token_source)
        self.assertIn("bodySmall = TypographyTokens.metadata", token_source)
        self.assertIn("labelSmall = TypographyTokens.caption", token_source)
        # The token styles use sp; do not override the user's system font scale.
        self.assertNotRegex(token_source, r"fontScale\s*=|\.copy\(\s*fontSize\s*=")

    def test_primary_compose_screens_share_semantic_theme_roles(self):
        for screen, relative in TYPOGRAPHY_SCREEN_FILES.items():
            source = (ROOT / relative).read_text(encoding="utf-8")
            self.assertTrue(
                "MaterialTheme.typography" in source
                or "ReiAnixTokens.TypographyTokens" in source,
                msg=f"{screen} must use the shared typography hierarchy",
            )
            self.assertNotRegex(
                source,
                r"fontSize\s*=",
                msg=f"{screen} must not introduce unreviewed hardcoded font sizes",
            )

        settings = (
            ROOT / TYPOGRAPHY_SCREEN_FILES["Settings"]
        ).read_text(encoding="utf-8")
        self.assertIn("ReiAnixTokens.TypographyTokens.settingsCategory", settings)
        self.assertIn("ReiAnixTokens.TypographyTokens.settingsDescription", settings)

    def test_navigation_labels_keep_the_canonical_compact_role(self):
        shell = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/shell/ReiAnixAppShell.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("ReiAnixTokens.TypographyTokens.navigationLabel", shell)
        self.assertIn("ReiAnixTokens.Dimensions.bottomNavigationMinHeight", shell)
        self.assertIn("NavigationBarItem(", shell)
        self.assertIn("onDestinationClick(destination.route)", shell)

    def test_coexisting_flet_titles_stay_close_to_compose_scale(self):
        home = (ROOT / "views/home_view.py").read_text(encoding="utf-8")
        settings = (ROOT / "views/settings_view.py").read_text(encoding="utf-8")
        self.assertIn('ft.Text(title, size=15, weight=ft.FontWeight.BOLD', home)
        self.assertIn('ft.Text(label, color=TEXT, size=15, weight=ft.FontWeight.BOLD)', settings)
