import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class Prompt50ArtworkPipelineTests(unittest.TestCase):
    def read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_artwork_engine_is_single_canonical_download_owner(self):
        source = self.read("core/artwork.py")
        self.assertIn("class ArtworkEngine:", source)
        self.assertNotIn("class ArtworkEngineV2", source)
        self.assertNotIn("class ArtworkDownloader", source)
        self.assertIn("MAX_WORKERS = 2", source)
        self.assertIn("MAX_PENDING_TASKS = 128", source)
        self.assertIn("def set_change_listener", source)
        self.assertIn("ARTWORK_PUBLISHED", source)

    def test_download_validation_is_not_http_200_only(self):
        source = self.read("core/artwork.py")
        self.assertIn("self._is_valid_image_payload(payload)", source)
        self.assertIn("_detect_image_extension(payload)", source)
        self.assertIn("self._is_valid_image_file(existing[\"local_path\"])", source)

    def test_invalid_persisted_cache_is_rejected_and_cleared(self):
        source = self.read("core/artwork.py")
        sync_start = source.index("def sync_anime_metadata")
        sync_end = source.index("def reindex_entity", sync_start)
        block = source[sync_start:sync_end]
        self.assertIn("self._is_valid_image_file(cover_cache)", block)
        self.assertIn("def _mark_inconsistent", source)
        self.assertIn("UPDATE anime SET cover_cache='' WHERE id=?", source)

    def test_main_publishes_artwork_without_rebuilding_catalog(self):
        source = self.read("main.py")
        self.assertIn("library.artwork.set_change_listener", source)
        self.assertIn("library.artwork.set_diagnostic_recorder(diagnostics.record)", source)
        self.assertIn("artwork_event_loop.call_soon_threadsafe", source)
        self.assertIn("compose_library_bridge.request_publish", source)
        self.assertNotIn("refresh_everything()", source)

    def test_flet_views_have_incremental_artwork_callbacks(self):
        home = self.read("views/home_view.py")
        details = self.read("views/details_view.py")
        self.assertIn("def update_artwork_in_place", home)
        self.assertIn('view_state["_update_artwork"] = update_artwork_in_place', home)
        self.assertIn("def update_artwork_in_place", details)
        self.assertIn('view_state["_update_artwork"] = update_artwork_in_place', details)

    def test_compose_local_artwork_mapping_remains_local_first(self):
        mapper = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/mapper/LibraryUiMappers.kt")
        artwork = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/artwork/ReiAnixLocalArtwork.kt")
        self.assertIn('"artwork_local_path", "cover_cache", "cover", "poster_path", "local_path"', mapper)
        self.assertIn("External URLs are deliberately not fetched", artwork)
        self.assertNotIn("Coil", artwork)
        self.assertNotIn("Glide", artwork)
        self.assertNotIn("Picasso", artwork)

    def test_python_files_remain_syntactically_valid(self):
        for relative in ("main.py", "core/artwork.py", "core/library_service.py", "views/home_view.py", "views/details_view.py"):
            ast.parse(self.read(relative), filename=relative)


if __name__ == "__main__":
    unittest.main()
