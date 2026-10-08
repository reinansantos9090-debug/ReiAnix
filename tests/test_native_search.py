import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEARCH = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
ENGINE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearchEngine.kt"
VM = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
NAV = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"


class NativeSearchTests(unittest.TestCase):
    def test_search_route_is_wired_to_existing_navigation_surface(self):
        source = NAV.read_text(encoding="utf-8")
        self.assertIn("ReiAnixSearchRoute", source)
        self.assertIn("search = {", source)
        self.assertIn("viewModel = homeViewModel", source)

    def test_search_uses_canonical_viewmodel_projection(self):
        source = VM.read_text(encoding="utf-8")
        self.assertIn("canonicalCatalog", source)
        self.assertIn("val searchCatalog", source)
        self.assertIn("val searchGenres", source)
        self.assertIn("ReiAnixSearchEngine.buildIndex(animes)", source)
        self.assertIn("val searchCatalog", source)
        self.assertIn("combine(", source)
        self.assertIn("fun setSearchQuery", source)
        self.assertNotIn("LibraryStore(", source)
        self.assertNotIn("SQLiteDatabase", source)

    def test_search_ui_has_required_states_and_stable_details_identity(self):
        source = SEARCH.read_text(encoding="utf-8")
        for state in ("LOADING", "ERROR", "SOURCE_UNAVAILABLE", "EMPTY", "READY"):
            self.assertIn("ReiAnixLibraryLoadStatus." + state, source)
        self.assertIn("searchQuery.isBlank()", source)
        self.assertIn("results.isEmpty()", source)
        self.assertIn("key = { anime -> anime.stableKey }", source)
        self.assertIn("onOpenDetails(anime.id)", source)
        self.assertTrue(
            "ReiAnixLocalArtwork(" in source
            or "ReiAnixAnimeCard(" in source
            or "ReiAnixPoster(" in source
        )
        self.assertIn("anime.year", source)
        self.assertIn("anime.genres", source)

    def test_search_does_not_add_remote_catalog_or_synthetic_history(self):
        for path in (SEARCH, ENGINE):
            source = path.read_text(encoding="utf-8").lower()
            self.assertNotIn("http://", source)
            self.assertNotIn("https://", source)
            self.assertNotIn("most searched", source)
            self.assertNotIn("recent searches", source)
            self.assertNotIn("sqlite", source)

    def test_search_engine_indexes_only_projection_fields(self):
        source = ENGINE.read_text(encoding="utf-8")
        for token in (
            "anime.title",
            "anime.genres",
            "anime.year",
            "episode.title",
            "episode.fileName",
            "episode.seasonNumber",
            "episode.number",
        ):
            self.assertIn(token, source)
        self.assertNotIn("anilist", source.lower())

    def test_search_query_is_debounced_and_cancelable_off_main(self):
        source = SEARCH.read_text(encoding="utf-8") + VM.read_text(encoding="utf-8")
        self.assertIn(".debounce(", source)
        self.assertIn(".mapLatest", source)
        self.assertIn("flowOn(Dispatchers.Default)", source)
        self.assertNotIn("delay(", source)
        self.assertNotIn("http://", source.lower())
        self.assertNotIn("https://", source.lower())
        self.assertNotIn("anilist", source.lower())

    def test_search_opens_immediately_from_canonical_catalog(self):
        source = SEARCH.read_text(encoding="utf-8")
        self.assertIn("val hasLocalCatalog = browseAnimes.isNotEmpty()", source)
        self.assertIn("searchState.query != searchQuery", source)
        self.assertIn("SearchQueryLoadingState", source)
        self.assertIn("!searchState.filters.hasAnyFilter", source)
        self.assertIn("FocusRequester", source)
        self.assertIn("focusRequester.requestFocus()", source)

    def test_search_reuses_library_cards_and_stable_grid_keys(self):
        source = SEARCH.read_text(encoding="utf-8")
        self.assertIn("ReiAnixAnimeCard(", source)
        self.assertIn('libraryGridMinWidth("medium")', source)
        self.assertIn('key = { anime -> anime.stableKey }', source)
        self.assertIn('contentType = { "search-result-anime-card" }', source)
        self.assertNotIn("SearchResultRow(", source)
        self.assertNotIn("LazyColumn(", source)

    def test_search_index_deduplicates_canonical_anime_and_scores_alternate_titles(self):
        source = ENGINE.read_text(encoding="utf-8")
        self.assertIn("distinctBy(ReiAnixAnimeUiModel::id)", source)
        self.assertIn("normalizedAlternateTitles", source)
        self.assertIn("anime.romajiTitle", source)
        self.assertIn("anime.nativeTitle", source)
        self.assertIn("anime.englishTitle", source)
        self.assertIn("normalizedAlternateTitles.any { it == normalizedQuery }", source)
        self.assertIn("fun all(): List<ReiAnixAnimeUiModel>", source)

    def test_search_field_uses_shared_component(self):
        source = SEARCH.read_text(encoding="utf-8")
        self.assertIn("ReiAnixSearchField(", source)
        self.assertNotIn("OutlinedTextField(", source)


if __name__ == "__main__":
    unittest.main()
