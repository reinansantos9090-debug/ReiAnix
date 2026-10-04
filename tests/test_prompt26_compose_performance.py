import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
DETAILS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"
HOME = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"
SEARCH = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/search/ReiAnixSearch.kt"
REPOSITORY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
ARTWORK = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt"


class Prompt26ComposePerformanceTests(unittest.TestCase):
    def read(self, path):
        return path.read_text(encoding="utf-8")

    def test_expensive_library_projections_are_off_main_and_have_lightweight_initial_values(self):
        source = self.read(VM)

        home = source[source.index("val homeState"):source.index("private val _libraryFilters")]
        genres = source[source.index("val libraryGenres"):source.index("val filteredLibraryAnimes")]
        filtered = source[source.index("val filteredLibraryAnimes"):source.index("/**\n     * Canonical Details projection")]
        self.assertIn(".flowOn(Dispatchers.Default)", home)
        self.assertIn("ReiAnixHomeLibraryUiState()", home)
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
        self.assertIn("initialValue = initialState", details)
        self.assertIn("initialValue = ReiAnixDetailsUiState()", details)
        self.assertNotIn("initialValue = initialState", details)

    def test_home_lazy_rows_use_stable_identity_and_content_types(self):
        home = self.read(HOME)
        for marker in ("Home Continuar Assistindo", "Home Minha Lista"):
            start = home.index(marker)
            block = home[start:start + 1400]
            self.assertIn("key = { it.stableKey }", block)
            self.assertIn("contentType =", block)

    def test_existing_io_boundaries_remain_intact(self):
        repository = self.read(REPOSITORY)
        artwork = self.read(ARTWORK)
        self.assertIn("SupervisorJob() + Dispatchers.IO", repository)
        self.assertIn("withContext(Dispatchers.IO)", artwork)
        self.assertIn("collectAsStateWithLifecycle()", self.read(SEARCH))

    def test_prompt26_does_not_reintroduce_database_or_filesystem_access_to_compose_ui(self):
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
