from __future__ import annotations
import ast
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class RegressionBaselineTests(unittest.TestCase):
    """Static regression baseline; it does not replace device/runtime validation."""

    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_required_architecture_components_are_present(self):
        for path in (
            "main.py","core/android_bridge.py","core/library_store.py",
            "core/library_service.py","core/artwork.py","core/genre_classifier.py",
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt",
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt",
            "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt",
            "views/home_view.py","views/organize_view.py","views/details_view.py","views/settings_view.py",
        ):
            self.assertTrue((ROOT / path).is_file(), path)

    def test_android_back_is_owned_by_flet_navigation_not_native_mailbox(self):
        main=self.read("main.py")
        activity=self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
        self.assertIn("page.on_view_pop = handle_flet_view_pop",main)
        self.assertIn("page.views.clear()",main)
        self.assertNotIn('put("type", "android_back")',activity)
        self.assertNotIn("event_type == 'android_back'",main)

    def test_on_resume_contains_authorized_discovery_scan_triggers(self):
        source=self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
        start=source.index("override fun onResume()")
        end=source.index("override fun onPause()",start)
        resume=source[start:end]
        self.assertIn("startupDiscoveryTriggered",resume)
        self.assertIn("publishScanRequest(",resume)
        self.assertIn('"STARTUP"',resume)
        self.assertNotIn("scanMediaStore(null)",resume)
        self.assertNotIn("scanAllStorage(null)",resume)

    def test_organize_has_async_collection_handler_bound_to_clicks(self):
        source=self.read("views/organize_view.py")
        tree=ast.parse(source)
        async_names={n.name for n in ast.walk(tree) if isinstance(n,ast.AsyncFunctionDef)}
        self.assertIn("state_button", source)
        self.assertIn("genre_card", source)
        self.assertIn("handle", async_names)
        self.assertIn("on_click=handle", source)
        self.assertIn("await render()", source)

    def test_genre_registry_is_local_and_not_artificially_limited(self):
        source=self.read("core/genre_registry.py")
        self.assertIn("class GenreRegistry", source)
        self.assertIn("sync_anime", source)
        self.assertNotIn("return genres or [\"Minha biblioteca\"]", source)


    def test_anilist_and_artwork_pipeline_already_exist(self):
        service=self.read("core/library_service.py")
        artwork=self.read("core/artwork.py")
        anilist=self.read("core/anilist.py")
        self.assertIn("AniListClient",service)
        self.assertIn("ArtworkEngine",service)
        self.assertIn("sync_anime_metadata",service)
        self.assertIn("cache_cover",anilist)
        self.assertIn("class ArtworkEngine",artwork)

    def test_player_contracts_and_horizontal_swipe_seek_is_forbidden(self):
        player=self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
        main=self.read("main.py")
        for token in ("player_error","player_exited"):
            self.assertIn(token,player)
            self.assertIn(token,main)
        for token in (
            "PlayerGesturePolicy",
            "PLAYER_DOUBLE_TAP",
            "PLAYER_LONG_PRESS",
            "VERTICAL",
            "GESTURE_HORIZONTAL_IGNORED",
            "seekTarget",
            "adjustBrightness",
            "adjustVolumeByFraction",
            "playerGeneration",
            "restoreSystemUiBeforeExit",
        ):
            self.assertIn(token,player)
        self.assertNotIn("HORIZONTAL_SEEK", player)
        self.assertNotIn("horizontalSeekDelta", player)
        self.assertNotIn("gestures.horizontal_swipe_seek", main)


    # Compatibility alias kept because the blocking contract runner invokes the
    # historical test name directly. The assertion body follows the current
    # Prompt 04 rule: generic horizontal swipe must not seek.
    def test_player_contracts_and_horizontal_seek_are_present(self):
        self.test_player_contracts_and_horizontal_swipe_seek_is_forbidden()

    def test_existing_ui_async_guardrails_are_present(self):
        home=self.read("views/home_view.py")
        organize=self.read("views/organize_view.py")
        self.assertIn("asyncio.to_thread(",home)
        self.assertIn("asyncio.to_thread(",organize)
        self.assertIn("page.run_task(",home)

if __name__ == "__main__":
    unittest.main()
