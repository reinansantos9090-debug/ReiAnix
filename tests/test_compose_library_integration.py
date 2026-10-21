import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")
VIEW_MODEL = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
REPOSITORY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt").read_text(encoding="utf-8")
CODEC = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryCommandCodec.kt").read_text(encoding="utf-8")
ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
NAVIGATION = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt").read_text(encoding="utf-8")
DETAILS = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt").read_text(encoding="utf-8")


class ComposeLibraryIntegrationTests(unittest.TestCase):
    def test_main_uses_native_repository_and_compose_navigation_boundary(self):
        self.assertIn("ReiAnixLibraryViewModel", VIEW_MODEL)
        self.assertIn("loadLibraryCatalogRows()", VIEW_MODEL + REPOSITORY)
        self.assertIn("ReiAnixNativeLibraryCatalogProjector", VIEW_MODEL)
        self.assertIn("ReiAnixNavigationHost", NAVIGATION)
        self.assertIn("ReiAnixRoutes.LIBRARY", NAVIGATION)

    def test_native_catalog_publication_is_not_a_scan_request(self):
        start = VIEW_MODEL.index("fun refresh()")
        end = VIEW_MODEL.index("fun refreshDetails(", start)
        refresh = VIEW_MODEL[start:end]
        self.assertIn("repository.refresh()", refresh)
        self.assertIn("refreshNativeCatalog()", refresh)
        self.assertNotIn("NativeScanRunner.run", refresh)
        self.assertNotIn("MediaStoreScanner.scan", refresh)

    def test_library_viewmodel_refresh_uses_repository_and_canonical_projection(self):
        self.assertIn("nativeLibraryRepository.loadLibraryCatalogRows()", VIEW_MODEL)
        self.assertIn("ReiAnixNativeLibraryCatalogProjector", VIEW_MODEL)
        self.assertIn("nativeCatalogReloadMutex.withLock", VIEW_MODEL)
        self.assertIn("refreshCanonicalCatalogAfterBootstrapValidation()", VIEW_MODEL)

    def test_personal_list_mutations_are_native_and_refresh_state(self):
        for token in ("personalListService.toggle(animeId)", "personalListService.add(animeId)", "personalListService.remove(animeId)"):
            self.assertIn(token, VIEW_MODEL)
        self.assertIn("refreshNativeCatalog()", VIEW_MODEL)
        self.assertNotIn("TOGGLE_FAVORITE", CODEC)

    def test_repository_snapshot_reconciliation_does_not_reset_to_first_page(self):
        self.assertIn("reconcilePagedState", REPOSITORY)
        self.assertIn("loadedPage = current.loadedPage", REPOSITORY)
        self.assertIn("hasMore = current.hasMore ||", REPOSITORY)
        self.assertNotIn("animes = firstPage", REPOSITORY)
        self.assertNotIn("animes.take(36)", REPOSITORY)

    def test_library_page_loading_is_bounded_and_uses_page_state(self):
        self.assertIn("loadNativeLibraryPage(", VIEW_MODEL)
        self.assertIn("nativePageGeneration", VIEW_MODEL)
        self.assertIn("pageSize", VIEW_MODEL)
        self.assertIn("nativePagedLibraryState", VIEW_MODEL)

    def test_scanning_is_owned_by_the_native_scan_coordinator(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanController.kt").read_text(encoding="utf-8")
        runner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        self.assertIn("object NativeScanController", source)
        self.assertIn("fun begin(", source)
        self.assertIn("fun complete(", source)
        self.assertIn("NativeScanController.begin(", runner)
        self.assertIn("NativeScanController.complete(", runner)

    def test_compose_player_open_is_native_not_a_python_mailbox_command(self):
        start = VIEW_MODEL.index("fun openEpisode(episodeId: Long, expectedAnimeId: Long?")
        end = VIEW_MODEL.index("override fun onCleared()", start)
        launch = VIEW_MODEL[start:end]
        self.assertIn("nativeLibraryRepository.getEpisode(episodeId)", launch)
        self.assertIn("Intent.ACTION_VIEW", launch)
        self.assertIn('.appendQueryParameter("native_compose", "true")', launch)
        self.assertIn("private fun performOpenPlayer(", ACTIVITY)
        self.assertIn("reportNativePlayerHandoff(", ACTIVITY)
        self.assertNotIn("NativeMailbox.write", launch)

    def test_details_and_navigation_use_canonical_native_ids(self):
        self.assertIn("detailsState(canonicalId)", DETAILS)
        self.assertIn("viewModel.refreshDetails(canonicalId)", DETAILS)
        self.assertIn("navigateToDetails", NAVIGATION)
        self.assertIn("ReiAnixRoutes.DETAILS", NAVIGATION)

    def test_legacy_projection_bridge_does_not_create_a_second_database(self):
        self.assertIn("self.library.catalog()", BRIDGE)
        self.assertIn("self.store.folders()", BRIDGE)
        self.assertIn("SCHEMA_VERSION = 1", BRIDGE)
        self.assertNotIn("CREATE TABLE", BRIDGE)
        self.assertNotIn("sqlite3.connect", BRIDGE)


if __name__ == "__main__":
    unittest.main()
