import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
SERVICE = (ANDROID / "data/sqlite/ReiAnixPersonalListService.kt").read_text(encoding="utf-8")
VIEW_MODEL = (ANDROID / "viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
MY_LIST = (ANDROID / "ui/mylist/ReiAnixMyList.kt").read_text(encoding="utf-8")
REPOSITORY = (ANDROID / "data/library/ReiAnixLibraryRepository.kt").read_text(encoding="utf-8")
CODEC = (ANDROID / "data/library/ReiAnixLibraryCommandCodec.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
DETAILS_FLET = (ROOT / "views/details_view.py").read_text(encoding="utf-8")
STORE = (ROOT / "core/library_store.py").read_text(encoding="utf-8")


class KotlinPersonalListMigrationTests(unittest.TestCase):
    def test_personal_list_uses_existing_favorite_column_and_transaction(self):
        self.assertIn("WHERE COALESCE(favorite, 0) = 1", SERVICE)
        self.assertIn("ORDER BY title COLLATE NOCASE ASC, id ASC", SERVICE)
        self.assertIn("private suspend fun setMembership(", SERVICE)
        self.assertIn("database.transaction { db ->", SERVICE)
        self.assertIn("if (currentlyIncluded == included) return@transaction included", SERVICE)
        self.assertIn("UPDATE anime SET favorite = ? WHERE id = ?", SERVICE)
        self.assertNotIn("CREATE TABLE", SERVICE)
        self.assertNotIn("Room.databaseBuilder", SERVICE)
        self.assertIn("SCHEMA_VERSION = 29", STORE)

    def test_compose_my_list_is_derived_from_the_native_catalog_state(self):
        self.assertIn("nativeLibraryRepository.loadLibraryCatalogRows()", VIEW_MODEL)
        self.assertIn("val myListPresentationState", VIEW_MODEL)
        self.assertIn("val myListAnimes", VIEW_MODEL)
        self.assertIn("projectMyListAnimes(animes, selectedFilter)", VIEW_MODEL)
        self.assertIn("val state by viewModel.myListPresentationState.collectAsStateWithLifecycle()", MY_LIST)
        self.assertIn("val visibleAnimes by viewModel.myListAnimes.collectAsStateWithLifecycle()", MY_LIST)
        self.assertIn("key = { anime -> anime.stableKey }", MY_LIST)
        self.assertIn("onToggleFavorite = viewModel::toggleFavorite", MY_LIST)

    def test_compose_favorite_write_does_not_cross_the_python_mailbox(self):
        worker_start = MAIN.index("async def _run_compose_library_command")
        worker_end = MAIN.index("async def poll_native_bridge", worker_start)
        worker = MAIN[worker_start:worker_end]
        dispatch_start = MAIN.index("if event_type == 'compose_library_command':")
        dispatch_end = MAIN.index("player_event_types = {", dispatch_start)
        dispatch = MAIN[dispatch_start:dispatch_end]

        self.assertNotIn('action == "toggle_favorite":', worker)
        self.assertNotIn("store.toggle_favorite", worker)
        self.assertNotIn("'toggle_favorite',", dispatch)
        self.assertNotIn('TOGGLE_FAVORITE("toggle_favorite")', CODEC)
        self.assertNotIn("fun toggleFavorite(animeId: Long)", REPOSITORY)
        self.assertIn("personalListService.toggle(animeId)", VIEW_MODEL)
        self.assertIn("refreshNativeCatalog()", VIEW_MODEL)

    def test_legacy_flet_writer_is_identified_not_misrepresented_as_migrated(self):
        self.assertIn("def _toggle_favorite_from_details(anime_id):", MAIN)
        self.assertIn("store.toggle_favorite(anime_id)", MAIN)
        self.assertIn("on_toggle_favorite", DETAILS_FLET)
        self.assertIn("toggle_favorite", STORE)

    def test_add_remove_only_change_membership_not_consumption_or_associations(self):
        update_start = SERVICE.index("private suspend fun setMembership(")
        update_end = SERVICE.index("\n    }\n}", update_start)
        membership = SERVICE[update_start:update_end]
        self.assertIn("UPDATE anime SET favorite = ? WHERE id = ?", membership)
        self.assertNotIn("DELETE FROM episodes", membership)
        self.assertNotIn("DELETE FROM associations", membership)
        self.assertNotIn("progress =", membership)
        self.assertNotIn("watched =", membership)


if __name__ == "__main__":
    unittest.main()
