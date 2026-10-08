"""Regression contracts for focus stability and incremental thumbnail delivery."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOME = (ROOT / "views" / "home_view.py").read_text(encoding="utf-8")
SETTINGS = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
UI = (ROOT / "core" / "ui.py").read_text(encoding="utf-8")
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
DETAILS = (ROOT / "views" / "details_view.py").read_text(encoding="utf-8")


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
        start = MAIN.index("elif event_type == 'thumbnail_ready':")
        end = MAIN.index("elif event_type == 'thumbnail_error':", start)
        handler = MAIN[start:end]
        self.assertIn("home_state.get('_update_thumbnail')", handler)
        self.assertIn("home_update(uri, thumbnail_path, media_identity)", handler)
        self.assertNotIn("on_catalog_changed(", handler)

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
        start = MAIN.index("elif event_type == 'thumbnail_ready':")
        end = MAIN.index("elif event_type == 'thumbnail_error':", start)
        handler = MAIN[start:end]
        self.assertIn("thumbnail_key = (uri, size, modified_at, media_identity)", handler)
        self.assertIn("thumbnail_key != latest_key", handler)
        self.assertIn("THUMBNAIL_STALE", handler)

    def test_multiple_thumbnail_updates_are_idempotent(self):
        start = HOME.index("def update_thumbnail_in_place")
        end = HOME.index("async def refresh_from_catalog", start)
        update = HOME[start:end]
        self.assertIn("if not affected_episode_ids:", update)
        self.assertIn("return bool(updated)", update)
        self.assertNotIn("_refresh_from_catalog", update)


    def test_thumbnail_rejection_releases_request_bookkeeping(self):
        request_start = MAIN.index("def request_missing_thumbnail")
        request_end = MAIN.index("def storage_state", request_start)
        request = MAIN[request_start:request_end]
        self.assertIn("asyncio.PriorityQueue(maxsize=128)", MAIN)
        self.assertNotIn("len(thumbnail_requests) >= 32", request)
        self.assertIn("queue_deferred", request)

    def test_thumbnail_cache_hit_releases_request_bookkeeping(self):
        request_start = MAIN.index("def request_missing_thumbnail")
        request_end = MAIN.index("def storage_state", request_start)
        request = MAIN[request_start:request_end]
        cache_hit = request.index('performance.counter("artwork.thumbnail.cache_hit")')
        prefix = request[:cache_hit]
        self.assertIn("thumbnail_requests.discard(key)", prefix)
        self.assertIn("thumbnail_request_started_at.pop(key, None)", prefix)
        self.assertIn("thumbnail_latest_key_by_uri.pop(path_ref, None)", prefix)

    def test_thumbnail_ready_captures_native_start_before_cleanup(self):
        start = MAIN.index("elif event_type == 'thumbnail_ready':")
        end = MAIN.index("elif event_type == 'thumbnail_error':", start)
        block = MAIN[start:end]
        self.assertIn("started_native = thumbnail_request_started_at.pop(thumbnail_key, None)", block)
        self.assertNotIn("started_native = thumbnail_request_started_at.get(thumbnail_key)", block)


    def test_thumbnail_generation_maps_remain_bounded_while_latest_version_wins(self):
        request_start = MAIN.index("def request_missing_thumbnail")
        request_end = MAIN.index("def storage_state", request_start)
        request = MAIN[request_start:request_end]
        self.assertIn("previous != key", request)
        self.assertIn("thumbnail_pending.pop(previous, None)", request)
        self.assertIn("thumbnail_latest_key_by_uri[path_ref] = key", request)
        self.assertIn("if len(thumbnail_latest_at) > 2048:", MAIN)
        self.assertIn("if len(thumbnail_completed_request_by_key) > 2048:", MAIN)


if __name__ == "__main__":
    unittest.main()
