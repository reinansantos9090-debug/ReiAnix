from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class GlobalVisualConsolidationTests(unittest.TestCase):
    def test_existing_reianix_design_system_remains_the_only_theme(self):
        tokens = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt")
        theme = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt")
        self.assertIn("object ReiAnixTokens", tokens)
        self.assertIn("fun ReiAnixComposeTheme(", theme)
        all_ui_source = "\n".join(path.read_text(encoding="utf-8") for path in UI.rglob("*.kt"))
        for forbidden in ("NewTheme", "NewDesignSystem", "CloudStreamTheme", "LegacyTheme2"):
            self.assertNotIn(forbidden, all_ui_source)

    def test_theme_semantic_aliases_resolve_to_the_canonical_palette(self):
        tokens = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt")
        theme = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt")
        for token in (
            "val background = Color(0xFF000000)",
            "val primary = Color(0xFF2579FF)",
            "val onBackground = text",
            "val onSurface = text",
            "val muted = textMuted",
            "val lightOnBackground = lightText",
            "val lightOnSurface = lightText",
            "val lightMuted = lightTextMuted",
            "val divider = Color(0xFF202020)",
            "val error = Color(0xFFFF5B61)",
            "val success = Color(0xFF4ADE80)",
            "val secondary = bodySecondary",
            "val cornerRadius = 12.dp",
        ):
            self.assertIn(token, tokens)
        self.assertIn("background = ReiAnixTokens.Colors.background", theme)
        self.assertIn("onBackground = ReiAnixTokens.Colors.onBackground", theme)
        self.assertIn("onSurface = ReiAnixTokens.Colors.onSurface", theme)
        self.assertIn("onSurfaceVariant = ReiAnixTokens.Colors.muted", theme)
        self.assertIn("background = ReiAnixTokens.Colors.lightBackground", theme)
        self.assertIn("onBackground = ReiAnixTokens.Colors.lightOnBackground", theme)
        self.assertIn("onSurfaceVariant = ReiAnixTokens.Colors.lightMuted", theme)
        self.assertIn("primary = ReiAnixTokens.Colors.lightPrimary", theme)

    def test_primary_screens_share_the_canonical_typography_and_surface_scheme(self):
        screens = {
            "Home": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt",
            "Library": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt",
            "Search": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt",
            "Details": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt",
            "Settings": "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt",
        }
        for screen, relative in screens.items():
            source = read(relative)
            self.assertIn("MaterialTheme.colorScheme.background", source, msg=screen)
            self.assertTrue(
                "ReiAnixTokens.TypographyTokens" in source or "MaterialTheme.typography" in source,
                msg=f"{screen} must use the shared typography roles",
            )
            self.assertNotIn("fontSize =", source, msg=screen)

    def test_loading_empty_and_error_states_share_visual_tokens(self):
        states = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixStateComponents.kt")
        self.assertIn("fun ReiAnixLoadingIndicator(", states)
        self.assertEqual(states.count("CircularProgressIndicator("), 1)
        self.assertIn("ReiAnixTokens.Dimensions.loadingIndicatorSize", states)
        self.assertIn("ReiAnixTokens.Dimensions.loadingIndicatorStroke", states)
        self.assertIn("ReiAnixTokens.TypographyTokens.emptyStateTitle", states)
        self.assertIn("ReiAnixTokens.TypographyTokens.secondary", states)
        self.assertIn("ReiAnixTokens.TypographyTokens.body", states)
        library = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt")
        organize = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt")
        settings = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        self.assertIn("ReiAnixLoadingIndicator()", library)
        self.assertIn("ReiAnixLoadingIndicator()", organize)
        self.assertIn("ReiAnixLoadingIndicator(", settings)

    def test_shared_controls_artwork_lazy_lists_and_accessible_navigation_are_preserved(self):
        home = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt")
        library = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt")
        search = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt")
        details = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt")
        settings = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        components = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt")
        navigation = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
        player = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/player/ReiAnixPlayer.kt")
        self.assertIn("ReiAnixAnimeCard(", home)
        self.assertIn("ReiAnixAnimeCard(", library)
        self.assertIn("ReiAnixAnimeCard(", search)
        self.assertIn("key = { anime -> anime.stableKey }", library)
        self.assertIn("key = { anime -> anime.stableKey }", search)
        self.assertIn("key = { episode -> episode.stableKey }", details)
        self.assertIn("fun ReiAnixSearchField(", components)
        self.assertIn("fun ReiAnixChip(", components)
        self.assertIn("fun ReiAnixProgressIndicator(", components)
        self.assertIn("ReiAnixTokens.Dimensions.settingsRowMinHeight", settings)
        self.assertIn("BOTTOM_NAV_CONTENT_DESCRIPTION", navigation)
        self.assertIn("BackHandler", player)
        artwork = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt")
        self.assertIn("ReiAnixLocalArtwork", artwork)
        self.assertTrue("ReiAnixPoster" in components or "ReiAnixPoster" in home)


    def test_responsive_layout_metrics_are_owned_by_canonical_design_tokens(self):
        tokens = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt")
        responsive = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixResponsive.kt")
        self.assertIn("object Responsive", tokens)
        for token in (
            "mediumWidth", "expandedWidth", "mediumHeight", "expandedHeight",
            "mediumLibraryGridMinWidth", "expandedLibraryGridMinWidth",
            "mediumHomeCardWidth", "expandedHomeCardWidth",
            "compactContinueCardWidth", "mediumContinueCardWidth",
            "expandedContinueCardWidth", "compactSearchGridMinWidth",
            "mediumSearchGridMinWidth", "expandedSearchGridMinWidth",
            "compactWindowWidth", "compactWindowHeight",
            "mediumHorizontalPadding", "expandedHorizontalPadding",
            "mediumContentMaxWidth", "expandedContentMaxWidth",
            "mediumSettingsMaxWidth", "expandedSettingsMaxWidth",
            "mediumTextMaxWidth", "expandedTextMaxWidth",
            "homeHeroLandscapeMinHeight", "homeHeroLandscapeMaxHeight",
            "homeHeroPortraitMinHeight", "homeHeroPortraitMaxHeight",
            "homeHeroExpandedMaxHeight", "detailsHeroLandscapeMinHeight",
            "detailsHeroLandscapeMaxHeight",
        ):
            self.assertIn("val " + token + " =", tokens)
            self.assertIn("ReiAnixTokens.Responsive." + token, responsive)
        self.assertNotRegex(responsive, r"\b\d+(?:\.\d+)?\.dp\b")
        self.assertIn("val mediumWidth = Dimensions.detailsHeroWideBreakpoint", tokens)
        self.assertIn("val mediumWidth = ReiAnixTokens.Responsive.mediumWidth", responsive)

    def test_rectangular_surfaces_use_semantic_shape_and_organize_filter_is_tokenized(self):
        components = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt")
        organize = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt")
        settings = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        tokens = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt")
        self.assertNotIn("RoundedCornerShape(0.dp)", components + organize + settings)
        self.assertIn("shape = if (continuous) androidx.compose.ui.graphics.RectangleShape else ReiAnixTokens.Shapes.card", components)
        self.assertEqual(organize.count("shape = androidx.compose.ui.graphics.RectangleShape"), 2)
        flat_wrapper_start = settings.index("fun ReiAnixSettingsSurface(")
        flat_wrapper_end = settings.index("fun ReiAnixSettingsRow(", flat_wrapper_start)
        flat_wrapper = settings[flat_wrapper_start:flat_wrapper_end]
        self.assertIn("Box(modifier = modifier.fillMaxWidth())", flat_wrapper)
        self.assertNotIn("\n    Surface(", flat_wrapper)
        self.assertNotIn("tonalElevation", flat_wrapper)
        self.assertIn("val organizeFilterMaxHeight = 260.dp", tokens)
        self.assertIn("ReiAnixTokens.Dimensions.organizeFilterMaxHeight", organize)

    def test_content_headers_share_canonical_top_bar_component(self):
        home = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt")
        library = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt")
        search = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt")
        details = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt")
        organize = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt")
        settings = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        mylist = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/mylist/ReiAnixMyList.kt")
        storage = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt")
        components = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt")
        self.assertIn("fun ReiAnixTopBar(", components)
        self.assertIn("ReiAnixTokens.Dimensions.topBarMinHeight", components)
        self.assertIn("LocalReiAnixResponsiveMetrics.current.horizontalPadding", components)
        for screen in (home, library, search, details, organize, settings, mylist):
            self.assertIn("ReiAnixTopBar(", screen)
        self.assertIn("SettingsHeader(", storage)
        self.assertIn("actions: @Composable RowScope.() -> Unit", components)
        self.assertIn("navigationContentDescription", components)
        self.assertIn("ReiAnixTokens.TypographyTokens.screenTitle", settings)
        self.assertIn("applyResponsiveHorizontalPadding: Boolean = true", components)
        self.assertIn("applyResponsiveHorizontalPadding = false", settings)


    def test_shared_text_fields_use_one_canonical_color_policy(self):
        components = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt")
        self.assertIn("private fun reiAnixOutlinedTextFieldColors()", components)
        self.assertEqual(components.count("OutlinedTextFieldDefaults.colors("), 1)
        self.assertEqual(components.count("colors = reiAnixOutlinedTextFieldColors()"), 2)
        for token in (
            "focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant",
            "focusedBorderColor = MaterialTheme.colorScheme.primary",
            "unfocusedBorderColor = MaterialTheme.colorScheme.outline",
            "disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy",
            "disabledTextColor = MaterialTheme.colorScheme.onSurface.copy",
            "focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant",
        ):
            self.assertIn(token, components)

    def test_settings_preferences_share_compact_semantic_text_component(self):
        settings = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
        self.assertIn("private fun SettingsPreferenceText(", settings)
        self.assertGreaterEqual(settings.count("SettingsPreferenceText("), 4)
        self.assertIn("ReiAnixTokens.TypographyTokens.bodySecondary", settings)
        self.assertIn("ReiAnixTokens.TypographyTokens.metadata", settings)
        self.assertIn("onCheckedChange(!checked)", settings)
        self.assertIn("onSave(draftValue.trim())", settings)
        self.assertIn("onSelected(choice.value)", settings)


    def test_primary_inline_text_actions_reuse_the_shared_compact_button(self):
        components = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt")
        screens = {
            "Home": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"),
            "Search": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"),
            "Details": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"),
            "Settings": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"),
        }
        self.assertIn("contentPadding: androidx.compose.foundation.layout.PaddingValues", components)
        self.assertIn("style = ReiAnixTokens.TypographyTokens.button", components)
        for name, source in screens.items():
            self.assertIn("ReiAnixCompactButton(", source, msg=name)
        self.assertNotIn("TextButton(", screens["Home"])
        self.assertNotIn("TextButton(", screens["Search"])
        self.assertNotIn("TextButton(", screens["Details"])
        self.assertIn('text = "Salvar"', screens["Settings"])
        self.assertIn('text = "Ver todas  ›"', screens["Details"])
        self.assertGreaterEqual(screens["Details"].count("ReiAnixCompactButton("), 2)


    def test_library_search_details_organize_and_my_list_share_filter_chips(self):
        components = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt")
        screens = {
            "Library": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"),
            "Search": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"),
            "Details": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"),
            "Organize": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt"),
            "My List": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/mylist/ReiAnixMyList.kt"),
        }
        self.assertIn("fun ReiAnixChip(", components)
        self.assertIn("ReiAnixTokens.Dimensions.chipMinHeight", components)
        self.assertIn("ReiAnixTokens.TypographyTokens.chip", components)
        for name, source in screens.items():
            self.assertIn("ReiAnixChip(", source, msg=name)
            self.assertNotRegex(source, r"(?<![A-Za-z0-9_])FilterChip\s*\(", msg=name)
            self.assertNotRegex(source, r"(?<![A-Za-z0-9_])AssistChip\s*\(", msg=name)


    def test_alert_dialog_actions_reuse_the_shared_compact_button(self):
        screens = {
            "Library": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"),
            "Organize": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt"),
            "Storage": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt"),
            "Settings": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"),
        }
        for name, source in screens.items():
            self.assertIn("ReiAnixCompactButton(", source, msg=name)
            self.assertNotRegex(source, r"(?<![A-Za-z0-9_])TextButton\s*\(", msg=name)


    def test_content_screens_use_semantic_section_and_item_typography_roles(self):
        screens = {
            "Details": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"),
            "Search": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"),
            "Library": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"),
            "Organize": read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/organize/ReiAnixOrganize.kt"),
        }
        for name, source in screens.items():
            self.assertIn("ReiAnixTokens.TypographyTokens.sectionTitle", source, msg=name)
            self.assertNotIn("MaterialTheme.typography.titleLarge", source, msg=name)
            self.assertNotIn("MaterialTheme.typography.titleMedium", source, msg=name)
        tokens = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt")
        self.assertIn("val sectionTitle = TextStyle(", tokens)
        self.assertIn("val itemTitle = TextStyle(", tokens)


if __name__ == "__main__":
    unittest.main()
