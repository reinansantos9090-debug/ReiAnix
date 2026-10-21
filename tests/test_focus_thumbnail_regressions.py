"""Regression contracts for focus stability and incremental thumbnail delivery."""
from pathlib import Path
from tests.native_main_contract import native_main_source
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOME = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
SETTINGS = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
UI = (ROOT / "core" / "ui.py").read_text(encoding="utf-8")
MAIN = native_main_source()
DETAILS = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")
MAIN_ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
EXTRACTOR = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt").read_text(encoding="utf-8")
ARTWORK = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt").read_text(encoding="utf-8")


class FocusAndThumbnailRegressionTests(unittest.TestCase):
    def test_home_has_no_automatic_autofocus(self):
        self.assertNotIn("autofocus", HOME)

    def test_settings_categories_have_no_automatic_autofocus(self):
        category_start = SETTINGS.index("def build_category_tile")
        category_end = SETTINGS.index("def render_settings", category_start)
        self.assertNotIn("autofocus", SETTINGS[category_start:category_end])

    def test_shared_focus_border_is_neutral_and_geometry_stable(self):
        style_start = UI.index("def focus_button_style")
        style_end = UI.index("def empty_state", style_start)
        style = UI[style_start:style_end]
        self.assertIn("ft.ControlState.DEFAULT: ft.BorderSide(1, palette.border)", style)
        self.assertIn("ft.ControlState.FOCUSED: ft.BorderSide(1, palette.border)", style)
        self.assertNotIn("FOCUSED: ft.BorderSide(2, palette.primary)", style)
        self.assertNotIn("FOCUSED: ft.BorderSide(2,", DETAILS)

    def test_thumbnail_ready_dispatches_incremental_home_update_not_catalog_refresh(self):
        view_model = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        self.assertIn('put("type", "thumbnail_ready")', MAIN_ACTIVITY)
        self.assertIn("latestKeyByUri[uri.toString()]", EXTRACTOR)
        self.assertIn("registerGeneratedThumbnail", ARTWORK)
        self.assertIn("refreshNativeCatalog()", view_model)

    def test_incremental_thumbnail_update_preserves_grid_and_uses_existing_bindings(self):
        start = HOME.index("def update_thumbnail_in_place")
        end = HOME.index("async def refresh_from_catalog", start)
        update = HOME[start:end]
        self.assertIn("artwork_bindings.get", update)
        self.assertIn('("episode", episode_id, "episode_thumbnail")', update)
        self.assertIn('node["episode_thumbnail"] = thumbnail_path', update)
        self.assertIn("holder.content = ft.Image", update)
        self.assertIn("schedule_artwork_ui_update()", update)
        self.assertNotIn('meta["cover_cache"] = thumbnail_path', update)
        self.assertNotIn('artwork_bindings.get((entity, anime_id, "poster")', update)
        self.assertNotIn("load_library_page", update)
        self.assertNotIn("grid.controls.clear", update)
        self.assertNotIn("browse_catalog_page", update)

    def test_thumbnail_callback_is_exposed_without_rebuild_path(self):
        self.assertIn("view_state['_update_thumbnail'] = update_thumbnail_in_place", HOME)
        self.assertIn("thumbnail", HOME)
        self.assertIn("view_state[\'_update_thumbnail\'] = update_thumbnail_in_place", HOME)

    def test_existing_thumbnail_generation_guard_remains(self):
        self.assertIn("latestKeyByUri[uri.toString()] != cacheKey(identity, size, modifiedAt)", EXTRACTOR)
        self.assertIn("return result.copy(canonicalArtworkRegistered = false)", EXTRACTOR)
        self.assertIn("THUMBNAIL_REQUESTED", EXTRACTOR)
        self.assertIn("THUMBNAIL_GENERATION_STARTED", EXTRACTOR)

    def test_multiple_thumbnail_updates_are_idempotent(self):
        start = HOME.index("def update_thumbnail_in_place")
        end = HOME.index("async def refresh_from_catalog", start)
        update = HOME[start:end]
        self.assertIn("if not affected_episode_ids:", update)
        self.assertIn("return bool(updated)", update)
        self.assertNotIn("_refresh_from_catalog", update)


    def test_thumbnail_rejection_releases_request_bookkeeping(self):
        self.assertIn("private val extractionPermits = Semaphore(2)", EXTRACTOR)
        self.assertIn("val lock = inFlight.computeIfAbsent(key)", EXTRACTOR)
        self.assertIn("if (permitAcquired) extractionPermits.release()", EXTRACTOR)
        self.assertIn("inFlight.remove(key, lock)", EXTRACTOR)

    def test_thumbnail_cache_hit_reuses_only_a_validated_cache_entry(self):
        self.assertIn("isValidCachedThumbnail(target)", EXTRACTOR)
        self.assertIn("readCachedResult(context, uri, target, key, size, modifiedAt)", EXTRACTOR)
        self.assertIn("THUMBNAIL_CACHE_HIT", EXTRACTOR)
        self.assertIn("registerGeneratedArtwork(context, uri, identity, size, modifiedAt, cached.result)", EXTRACTOR)

    def test_thumbnail_generation_reports_duration_and_request_identity(self):
        self.assertIn("THUMBNAIL_GENERATION_STARTED", EXTRACTOR)
        self.assertIn("THUMBNAIL_GENERATION_FINISHED", EXTRACTOR)
        self.assertIn("elapsedRealtimeNanos()", EXTRACTOR)
        self.assertIn('"requestId", requestId', EXTRACTOR)
        self.assertIn('"mediaIdentity", identity', EXTRACTOR)

    def test_thumbnail_generation_state_remains_bounded_while_latest_version_wins(self):
        self.assertIn("if (latestKeyByUri.size > 2048)", EXTRACTOR)
        self.assertIn("MAX_CACHE_BYTES = 128L * 1024L * 1024L", EXTRACTOR)
        self.assertIn("latestKeyByUri[uri.toString()] != cacheKey(identity, size, modifiedAt)", EXTRACTOR)
        self.assertIn("private val extractionPermits = Semaphore(2)", EXTRACTOR)

if __name__ == "__main__":
    unittest.main()
