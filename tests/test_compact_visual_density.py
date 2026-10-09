import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_UI = Path("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class CompactVisualDensityTests(unittest.TestCase):
    def test_shared_spacing_and_icon_scales_are_compact_and_semantic(self):
        tokens = read(ANDROID_UI / "theme/ReiAnixTokens.kt")
        for token in (
            "val xs = 4.dp",
            "val sm = 8.dp",
            "val md = 12.dp",
            "val lg = 16.dp",
            "val xl = 20.dp",
            "val section = 16.dp",
            "val sectionGap = 16.dp",
            "val iconSmall = 18.dp",
            "val iconSecondary = 20.dp",
            "val iconMedium = 22.dp",
            "val iconLarge = 26.dp",
            "val iconViewportSize = 24.dp",
            "val settingsChoiceDialogMaxHeight = 420.dp",
            "val settingsChoiceValueMinWidth = 64.dp",
            "val settingsChoiceValueMaxWidth = 120.dp",
            "val detailsSeasonCardMinWidth = 240.dp",
            "val organizeFilterMaxHeight = 260.dp",
            "val onboardingCardMaxWidth = 560.dp",
            "val onboardingIconSize = 48.dp",
            "val dividerHeight = 1.dp",
        ):
            self.assertIn(token, tokens)

        self.assertRegex(tokens, r"val settingsRowMinHeight = 56\.dp")
        self.assertRegex(tokens, r"val settingsIconContainerSize = 32\.dp")
        self.assertRegex(tokens, r"val chipMinHeight = 32\.dp")
        self.assertRegex(tokens, r"val touchTarget = 48\.dp")
        self.assertRegex(tokens, r"val topBarMinHeight = 56\.dp")
        self.assertRegex(tokens, r"val bottomNavigationMinHeight = 64\.dp")
        self.assertRegex(tokens, r"val emptyStateMinHeight = 240\.dp")

    def test_settings_rows_reuse_dimensions_and_keep_touch_sized_trailing_controls(self):
        settings = read(ANDROID_UI / "settings/ReiAnixSettings.kt")
        self.assertGreaterEqual(
            settings.count("heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)"),
            3,
        )
        self.assertGreaterEqual(
            settings.count("Modifier.size(ReiAnixTokens.Dimensions.settingsIconContainerSize)"),
            3,
        )
        self.assertIn("Modifier.size(ReiAnixTokens.Dimensions.settingsTrailingSize)", settings)
        self.assertIn("val settingsTrailingSize = 48.dp", read(ANDROID_UI / "theme/ReiAnixTokens.kt"))
        self.assertIn("ReiAnixTokens.Colors.dividerAlpha", settings)
        self.assertNotIn("alpha = 0.7f", settings)

    def test_divider_component_uses_shared_subtle_contrast(self):
        components = read(ANDROID_UI / "ReiAnixComponents.kt")
        settings = read(ANDROID_UI / "settings/ReiAnixSettings.kt")
        tokens = read(ANDROID_UI / "theme/ReiAnixTokens.kt")
        self.assertIn("val dividerHeight = 1.dp", tokens)
        self.assertIn("val subtleBorderAlpha = 0.55f", tokens)
        self.assertIn("val dividerAlpha = 0.8f", tokens)
        divider = components[components.index("fun ReiAnixDivider("):components.index("@Composable\nfun ReiAnixBadge(")]
        self.assertIn("ReiAnixTokens.Dimensions.dividerHeight", divider)
        self.assertIn("ReiAnixTokens.Colors.dividerAlpha", divider)
        self.assertIn("ReiAnixTokens.Dimensions.dividerHeight", settings)

    def test_library_grid_uses_density_tokens_and_compact_loading_gaps(self):
        library = read(ANDROID_UI / "library/ReiAnixLibrary.kt")
        tokens = read(ANDROID_UI / "theme/ReiAnixTokens.kt")
        for token in (
            "libraryGridSpacingDense",
            "libraryGridSpacingBalanced",
            "libraryGridSpacingRelaxed",
        ):
            self.assertIn(token, library)
            self.assertIn(token, tokens)
        self.assertNotRegex(library, r'"small"\s*->\s*14\.dp')
        self.assertNotRegex(library, r'"large"\s*->\s*6\.dp')
        loading = library[library.index("private fun LibraryLoadingGrid("):library.index("@Composable\nprivate fun LibraryFilterChip(")]
        self.assertIn("horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)", loading)
        self.assertIn("verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)", loading)
        self.assertNotIn("bottom = ReiAnixTokens.Spacing.huge", library)
        self.assertIn("key = { anime -> anime.stableKey }", library)

    def test_home_search_and_details_keep_stable_keys_and_use_shared_gaps(self):
        home = read(ANDROID_UI / "home/ReiAnixHome.kt")
        search = read(ANDROID_UI / "search/ReiAnixSearch.kt")
        details = read(ANDROID_UI / "details/ReiAnixDetails.kt")
        self.assertIn("Arrangement.spacedBy(ReiAnixTokens.Spacing.section)", home)
        self.assertIn("contentPadding = PaddingValues(bottom = ReiAnixTokens.Spacing.xxl)", home)
        self.assertIn("key = { anime -> anime.stableKey }", search)
        self.assertIn("horizontalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)", search)
        self.assertIn("verticalArrangement = Arrangement.spacedBy(ReiAnixTokens.Spacing.sm)", search)
        self.assertNotIn("bottom = ReiAnixTokens.Spacing.huge", search)
        self.assertIn("private fun DetailsEpisodeItem(", details)
        self.assertIn("ReiAnixEpisodeCard(", details)
        self.assertIn("modifier = Modifier.padding(horizontal = LocalReiAnixResponsiveMetrics.current.horizontalPadding, vertical = ReiAnixTokens.Spacing.xs)", details)

    def test_navigation_insets_and_player_contract_are_not_replaced_by_density_workarounds(self):
        shell = read(ANDROID_UI / "shell/ReiAnixAppShell.kt")
        navigation = read(ANDROID_UI / "navigation/ReiAnixNavigation.kt")
        player_ui = read(ANDROID_UI / "player/ReiAnixPlayer.kt")
        player_activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
        tokens = read(ANDROID_UI / "theme/ReiAnixTokens.kt")
        self.assertIn("WindowInsets.safeDrawing.only(", shell)
        self.assertIn("NavigationBarItem(", shell)
        self.assertIn("ReiAnixTokens.Dimensions.bottomNavigationMinHeight", shell)
        self.assertIn('const val BOTTOM_NAV_CONTENT_DESCRIPTION = "ReiAnixBottomNavigation"', navigation)
        self.assertIn("val touchTarget = 48.dp", tokens)
        self.assertIn("PlayerDimensions", tokens)
        self.assertIn("BackHandler", player_ui)
        self.assertIn("launchRequestId", player_ui)
        self.assertIn("popBackStack()", player_ui)
        self.assertIn("PictureInPicture", player_activity)
        self.assertIn("enterImmersiveMode()", player_activity)


if __name__ == "__main__":
    unittest.main()
