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

    def test_existing_io_boundaries_remain_intact(self):
        repository = self.read(REPOSITORY)
        artwork = self.read(ARTWORK)
        self.assertIn("SupervisorJob() + Dispatchers.IO", repository)
        self.assertIn("withContext(Dispatchers.IO)", artwork)
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
