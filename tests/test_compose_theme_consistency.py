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

        self.assertIn("val primary = Color(0xFF3D8BFF)", tokens)
        self.assertIn("val lightPrimary = Color(0xFF2563C7)", tokens)
        self.assertIn("primary = ReiAnixTokens.Colors.primary", theme)
        self.assertIn("primary = ReiAnixTokens.Colors.lightPrimary", theme)


if __name__ == "__main__":
    unittest.main()
