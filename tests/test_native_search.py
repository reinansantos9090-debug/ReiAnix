import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEARCH = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
ENGINE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearchEngine.kt"
VM = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
NAV = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
QUERY_SERVICE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixLibraryQueryService.kt"
SQLITE_REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"


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

    def test_compose_search_uses_canonical_sqlite_query_service(self):
        view_model = VM.read_text(encoding="utf-8")
        service = QUERY_SERVICE.read_text(encoding="utf-8")
        repository = SQLITE_REPOSITORY.read_text(encoding="utf-8")

        self.assertIn("nativeLibraryQueries.searchAnime(", view_model)
        self.assertIn("searchText = request.settledQuery", view_model)
        self.assertIn("ReiAnixLibraryQueryResult.Failure", view_model)
        self.assertIn("repository.searchAnimeIds(", service)
        self.assertIn("database.read { connection ->", repository)
        self.assertIn("connection.rawQuery(sql, queryArgs.toTypedArray())", repository)
        self.assertIn("ORDER BY $sort", repository)
        self.assertIn("LIMIT ? OFFSET ?", repository)
        self.assertIn("requestedQuery != request.settledQuery", view_model)
        self.assertIn("isLoading = true", view_model)
        self.assertIn("searchState.isLoading", SEARCH.read_text(encoding="utf-8"))
        self.assertIn("results = orderedIds.mapNotNull(byId::get)", view_model)

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

    def test_remote_search_reuses_native_anilist_services(self):
        search = SEARCH.read_text(encoding="utf-8")
        engine = ENGINE.read_text(encoding="utf-8")
        ani_view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixAniListViewModel.kt").read_text(encoding="utf-8")
        integration = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListIntegrationService.kt").read_text(encoding="utf-8")
        self.assertIn("Descobrir no AniList", search)
        self.assertIn("aniListViewModel.setDiscoveryQuery", search)
        self.assertIn("var aniListQuery by rememberSaveable", search)
        self.assertIn("val displayedQuery = if (source == \"anilist\") aniListQuery else searchQuery", search)
        self.assertIn("aniListQuery = value", search)
        self.assertIn("AniListSearchResults", search)
        self.assertIn(".debounce(ANI_LIST_SEARCH_DEBOUNCE_MS)", ani_view_model)
        self.assertIn("integration.search(query)", ani_view_model)
        self.assertIn("client.searchAnime(term, limit)", integration)
        self.assertIn("normalizedAlternateTitles", engine)
        self.assertNotIn("anilist", engine.lower())
        self.assertNotIn("https://", search.lower())
        self.assertNotIn("recent searches", search.lower())

    def test_remote_details_keep_anilist_identity_separate_from_sqlite_ids(self):
        navigation = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt").read_text(encoding="utf-8")
        details = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixAniListDetails.kt").read_text(encoding="utf-8")
        integration = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/anilist/ReiAnixAniListIntegrationService.kt").read_text(encoding="utf-8")
        self.assertIn("ANILIST_DETAILS", navigation)
        self.assertIn("aniListDetails(anilistId", navigation)
        self.assertIn("loadAniListDetails(anilistId)", details)
        self.assertIn("client.getAnime(anilistId)", integration)
        self.assertIn("nenhuma entrada local foi criada", details.lower())

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
        ani_view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixAniListViewModel.kt").read_text(encoding="utf-8")
        self.assertIn(".debounce(", source)
        self.assertIn(".mapLatest", source)
        self.assertIn(".transformLatest", source)
        self.assertIn("flowOn(Dispatchers.Default)", source)
        self.assertNotIn("Thread.sleep(", source)
        self.assertNotIn("GlobalScope", source)
        self.assertIn(".debounce(ANI_LIST_SEARCH_DEBOUNCE_MS)", ani_view_model)
        self.assertIn("searchJob?.cancel()", ani_view_model)

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
