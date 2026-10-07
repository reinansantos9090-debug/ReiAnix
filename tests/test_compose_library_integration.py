import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
BRIDGE = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")


class ComposeLibraryIntegrationTests(unittest.TestCase):
    def test_main_uses_real_library_projection_and_compose_command_boundary(self):
        self.assertIn("ComposeLibraryBridge(", MAIN)
        self.assertIn('compose_library_bridge.request_publish("startup")', MAIN)
        self.assertIn("if event_type == 'compose_library_command':", MAIN)
        self.assertIn("_track_compose_library_task", MAIN)
        self.assertIn('namespace="compose_library"', MAIN)
        for action in ("toggle_favorite", "set_watched", "refresh", "open_media"):
            self.assertIn(f"action == '{action}'", MAIN)

    def test_compose_library_commands_ack_before_slow_work(self):
        start = MAIN.index("if event_type == 'compose_library_command':")
        end = MAIN.index("player_event_types = {", start)
        block = MAIN[start:end]
        self.assertIn('"QUEUED"', block)
        self.assertIn("_run_compose_library_command", block)
        self.assertNotIn("await asyncio.to_thread(", block)
        self.assertNotIn("await add_folder()", block)
        self.assertNotIn("await remove_folder(", block)

    def test_compose_library_request_ids_are_idempotent(self):
        start = MAIN.index("if event_type == 'compose_library_command':")
        end = MAIN.index("player_event_types = {", start)
        block = MAIN[start:end]
        self.assertIn("claim_native_request(", block)
        self.assertIn('namespace="compose_library"', block)

    def test_favorite_command_does_not_treat_false_new_state_as_failure(self):
        start = MAIN.index("if action == 'toggle_favorite':")
        end = MAIN.index("elif action == 'set_watched':", start)
        block = MAIN[start:end]
        self.assertIn("existing = await asyncio.to_thread(store.catalog, anime_ids=[anime_id])", block)
        self.assertIn("await asyncio.to_thread(store.toggle_favorite, anime_id)", block)
        self.assertNotIn("if not updated:", block)

    def test_mutations_run_outside_python_ui_event_thread(self):
        for token in (
            "await asyncio.to_thread(store.toggle_favorite",
            "await asyncio.to_thread(",
            "store.set_watched",
            "store.episode_by_id",
        ):
            self.assertIn(token, MAIN)

    def test_existing_scanner_coordinator_remains_refresh_owner(self):
        start = MAIN.index("if action == 'refresh':")
        end = MAIN.index("elif action == 'open_media':", start)
        block = MAIN[start:end]
        self.assertIn("ScanOrigin.USER_REFRESH", block)
        self.assertIn("scan_coordinator.request(", block)
        self.assertNotIn("bridge.scan_media_store", block)
        self.assertNotIn("bridge.scan_all_storage", block)

    def test_existing_player_bridge_owns_compose_media_open(self):
        start = MAIN.index("elif action == 'open_media':")
        end = MAIN.index("                            compose_library_bridge.write_command_result", start)
        block = MAIN[start:end]
        self.assertIn("play_episode(", block)
        self.assertIn("store.episode_by_id", block)
        self.assertNotIn("NativePlayerActivity", block)

    def test_compose_projection_is_derived_and_does_not_define_sqlite_schema(self):
        self.assertIn("self.library.catalog()", BRIDGE)
        self.assertIn("self.store.folders()", BRIDGE)
        self.assertIn("SCHEMA_VERSION = 1", BRIDGE)
        self.assertNotIn("CREATE TABLE", BRIDGE)
        self.assertNotIn("sqlite3", BRIDGE)
