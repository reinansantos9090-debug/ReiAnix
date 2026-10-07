import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
DETAILS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
HOME = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"
SEARCH = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
ARTWORK = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt"


class ComposePerformanceTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_expensive_library_projections_are_off_main_and_have_lightweight_initial_values(self):
        source = self.read(VM)

        home = source[source.index("val homeState"):source.index("private val _libraryFilters")]
        genres = source[source.index("val libraryGenres"):source.index("val filteredLibraryAnimes")]
        filtered = source[source.index("val filteredLibraryAnimes"):source.index("/**\n     * Canonical Details projection")]
        self.assertIn(".flowOn(Dispatchers.Default)", home)
        self.assertIn("projectHomeState(uiState.value)", home)
        self.assertIn(".flowOn(Dispatchers.Default)", genres)
        self.assertIn("emptyList()", genres)
        self.assertIn(".flowOn(Dispatchers.Default)", filtered)
        self.assertIn("emptyList()", filtered)

    def test_details_projection_is_cold_and_lifecycle_collected(self):
        vm = self.read(VM)
        details = self.read(DETAILS)
        start = vm.index("fun detailsState(")
        end = vm.index("/** The canonical Continue Watching projection", start)
        block = vm[start:end]
        self.assertIn("Flow<ReiAnixDetailsUiState>", block)
        self.assertIn(".flowOn(Dispatchers.Default)", block)
        self.assertIn(".distinctUntilChanged()", block)
        self.assertNotIn(".stateIn(", block)
        self.assertIn("collectAsStateWithLifecycle(", details)
        self.assertIn("initialValue = ReiAnixDetailsUiState()", details)
        self.assertNotIn("initialValue = initialState", details)

    def test_home_lazy_rows_use_stable_identity_and_content_types(self):
        home = self.read(HOME)
        continue_start = home.index("private fun HomeContinueSection")
        media_start = home.index("private fun HomeMediaSection")
        continue_block = home[continue_start:continue_start + 2600]
        media_block = home[media_start:media_start + 2600]
        self.assertIn("key = { it.stableKey }", continue_block)
        self.assertIn('contentType = { "home-continue-episode" }', continue_block)
        self.assertIn("key = { it.stableKey }", media_block)
        self.assertIn('contentType = { "home-media-anime" }', media_block)

    def test_home_sections_use_persisted_recency_and_available_content(self):
        home = self.read(HOME)
        self.assertIn('title = "Adicionados recentemente"', home)
        self.assertIn("buildRecentlyAddedItems(animes)", home)
        self.assertIn("it.addedAt?.isFinite() == true", home)
        self.assertIn('title = "Conteúdo disponível"', home)
        self.assertIn("it.availableContentCount > 0", home)
        self.assertNotIn('title = "Em alta"', home)
        self.assertNotIn("buildTrendingItems(", home)

    def test_home_reuses_canonical_home_models_without_intermediate_render_projection(self):
        home = self.read(HOME)
        self.assertIn("animes = state.animes", home)
        self.assertNotIn("HomeAnimeRenderData", home)
        self.assertNotIn("toHomeRenderData", home)
        self.assertIn("List<ReiAnixHomeAnimeUiModel>", home)
        self.assertIn("ReiAnixHomeLibraryUiState.from(state)", home)
        self.assertNotIn("it.playbackEpisodeId", home)

    def test_home_keeps_usable_catalog_visible_during_incremental_loading(self):
        home = self.read(HOME)
        self.assertIn(
            "ReiAnixLibraryLoadStatus.LOADING -> {\n                if (state.animes.isNotEmpty())",
            home,
        )
        self.assertIn(
            "ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> {\n                if (state.animes.isNotEmpty())",
            home,
        )
        self.assertIn('state.sourceState.equals("NOT_CONFIGURED", ignoreCase = true)', home)
        self.assertIn('"Configure sua biblioteca"', home)
        self.assertIn('"Selecionar pasta"', home)

    def test_home_continue_watching_displays_persisted_duration_without_new_progress_rules(self):
        home = self.read(HOME)
        self.assertIn("val durationText = item.durationSeconds", home)
        self.assertIn("?.let(::formatDuration)", home)
        self.assertIn("durationText,", home)
        self.assertIn("item.episodeId, item.animeId", home)
        self.assertNotIn("COMPLETION_RATIO", home)

    def test_home_continuation_respects_thumbnail_toggle_and_scan_loading_state(self):
        home = self.read(HOME)
        state = self.read(ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/ReiAnixLibraryUiState.kt")

        self.assertIn(
            "showThumbnails = showThumbnails,\n                    onWatch = onWatch",
            home,
        )
        self.assertIn(
            "state.scanInProgress &&\n                    state.animes.isEmpty()",
            state,
        )
        self.assertIn(
            "state.status == ReiAnixLibraryLoadStatus.EMPTY",
            state,
        )
        self.assertIn(
            "ReiAnixLibraryLoadStatus.LOADING",
            state,
        )

    def test_existing_io_boundaries_remain_intact(self):
        repository = self.read(REPOSITORY)
        artwork = self.read(ARTWORK)
        self.assertIn("SupervisorJob() + Dispatchers.IO", repository)
        self.assertIn("AsyncImage(", artwork)
        self.assertIn("ImageLoader.Builder", artwork)
        self.assertIn("diskCachePolicy(CachePolicy.DISABLED)", artwork)
        self.assertNotIn("BitmapFactory.decodeFile(", artwork)
        self.assertIn("collectAsStateWithLifecycle()", self.read(SEARCH))

    def test_library_projection_does_not_eagerly_resolve_artwork(self):
        bridge = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")
        self.assertNotIn("resolve_artwork_batch(", bridge)
        self.assertNotIn("from PIL import Image", bridge)

    def test_native_scan_batches_publish_incrementally_to_compose(self):
        main = (ROOT / "main.py").read_text(encoding="utf-8")
        self.assertIn('compose_library_bridge.request_publish("library_batch_ingested")', main)
        self.assertIn('int(result.get("new") or 0) > 0', main)
        self.assertIn('int(result.get("updated") or 0) > 0', main)

    def test_does_not_reintroduce_database_or_filesystem_access_to_compose_ui(self):
        sources = []
        for path in (HOME, DETAILS, SEARCH):
            sources.append(self.read(path))
        combined = "\n".join(sources)
        for forbidden in (
            "SQLiteDatabase",
            "SQLiteOpenHelper",
            "FileOutputStream",
            "FileInputStream",
            "java.io.File(",
            "ContentResolver",
        ):
            self.assertNotIn(forbidden, combined)


if __name__ == "__main__":
    unittest.main()
