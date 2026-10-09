import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LIBRARY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"
TOKENS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt"
MODELS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/LibraryUiModels.kt"
COMPONENTS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComponents.kt"
ARTWORK = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt"
REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
VIEWMODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"


class LibraryVisualConsolidationTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_library_remains_lazy_adaptive_and_uses_anime_identity(self):
        library = self.read(LIBRARY)
        models = self.read(MODELS)

        self.assertIn("LazyVerticalGrid(", library)
        self.assertIn("GridCells.Adaptive(", library)
        self.assertIn("libraryGridMinWidth(cardSize)", library)
        self.assertIn("key = { anime -> anime.stableKey }", library)
        self.assertIn('get() = "anime:" + id', models)
        self.assertIn("rememberSaveable(", library)
        self.assertIn("LazyGridState.Saver", library)
        self.assertNotIn("GridCells.Fixed(2)", library)

    def test_genre_picker_is_lazy_bounded_and_selected_state_is_visible(self):
        library = self.read(LIBRARY)
        tokens = self.read(TOKENS)

        self.assertIn("LazyRow(", library)
        self.assertIn("androidx.compose.foundation.lazy.LazyColumn(", library)
        self.assertIn("items = genres", library)
        self.assertIn("key = { genre -> genre.stableKey }", library)
        self.assertIn("filters.selectedGenreKey == genre.stableKey", library)
        self.assertIn("val libraryGenrePickerMaxHeight = 360.dp", tokens)
        self.assertIn("max = ReiAnixTokens.Dimensions.libraryGenrePickerMaxHeight", library)

    def test_filtering_remains_bound_to_the_existing_viewmodel_and_projection(self):
        library = self.read(LIBRARY)
        viewmodel = self.read(VIEWMODEL)

        for callback in (
            "setLibrarySearchQuery",
            "setLibraryGenreFilter",
            "toggleLibraryFavoritesFilter",
            "toggleLibraryWatchingFilter",
            "toggleLibraryCompletedFilter",
            "setLibrarySort",
            "clearLibraryFilters",
        ):
            self.assertIn(callback, library + viewmodel)
        self.assertIn("ReiAnixLibraryFilterEngine.filter", viewmodel)
        self.assertIn("val libraryGenres", viewmodel)
        self.assertIn("canonicalCatalog", viewmodel)

    def test_card_reuses_canonical_artwork_progress_favorite_and_accessibility(self):
        library = self.read(LIBRARY)
        components = self.read(COMPONENTS)
        artwork = self.read(ARTWORK)

        self.assertIn("ReiAnixAnimeCard(", library)
        self.assertIn("artworkPath = anime.artwork?.localPath", library)
        self.assertIn("artworkExternalUrl = anime.artwork?.externalUrl", library)
        self.assertIn("progress = renderData.progress", library)
        self.assertIn("favorite = renderData.favorite", library)
        self.assertIn("ReiAnixPoster(", components)
        self.assertIn("ReiAnixProgressIndicator(", components)
        self.assertIn("animeAccessibilityLabel", components)
        self.assertIn("AsyncImage(", artwork)
        self.assertNotIn("CachePolicy.DISABLED", artwork)
        self.assertNotIn("downloadTo(", library)

    def test_refresh_actions_are_compact_and_empty_state_opens_existing_settings_flow(self):
        library = self.read(LIBRARY)

        self.assertIn("Icons.Filled.MoreVert", library)
        self.assertIn("Atualizar biblioteca", library)
        self.assertIn("Atualizando biblioteca…", library)
        self.assertIn("enabled = !isRefreshing", library)
        self.assertIn("onOpenStorage = {", library)
        self.assertIn("navigateToTopLevel(ReiAnixRoutes.SETTINGS)", library)
        self.assertIn('title = "Biblioteca vazia"', library)
        self.assertIn('"Adicione uma pasta de mídia para começar."', library)
        self.assertIn('state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)', library)
        self.assertIn('actionLabel = if (onOpenStorage != null) "Adicionar pasta"', library)

    def test_initial_scan_and_transient_snapshot_failures_do_not_show_false_empty_library(self):
        library = self.read(LIBRARY)
        repository = self.read(REPOSITORY)

        self.assertLess(
            library.index("if (state.animeCount > 0)"),
            library.index("else if (state.scanInProgress)"),
        )
        self.assertIn("ReiAnixScannerInProgressState(", library)
        self.assertIn("preserveCatalogOnError", repository)
        self.assertIn("preserveCatalogDuringScan", repository)
        self.assertIn("animes = if (preserveCatalog) previous.animes else decoded.animes", repository)

    def test_filtered_empty_state_is_distinct_from_the_real_empty_library(self):
        library = self.read(LIBRARY)

        self.assertIn('"Nenhum resultado"', library)
        self.assertIn('"Nenhum título corresponde aos filtros atuais."', library)
        self.assertIn('"Biblioteca vazia"', library)
        self.assertIn('"Adicione uma pasta de mídia para começar."', library)


if __name__ == "__main__":
    unittest.main()
