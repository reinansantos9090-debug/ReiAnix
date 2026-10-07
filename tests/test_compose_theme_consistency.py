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
            "val surface = Color(0xFF0A0A0A)",
            "val surfaceVariant = Color(0xFF151515)",
            "val surfaceRaised = Color(0xFF111111)",
            "val surfaceDialog = Color(0xFF151515)",
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
        self.assertIn("surfaceContainer = ReiAnixTokens.Colors.surfaceVariant", theme)
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


if __name__ == "__main__":
    unittest.main()
