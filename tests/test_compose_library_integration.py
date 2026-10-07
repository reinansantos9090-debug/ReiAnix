import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
BRIDGE = (ROOT / "core/compose_library_bridge.py").read_text(encoding="utf-8")


class ComposeLibraryIntegrationTests(unittest.TestCase):
    @staticmethod
    def _command_dispatch_block():
        start = MAIN.index("if event_type == 'compose_library_command':")
        end = MAIN.index("player_event_types = {", start)
        return MAIN[start:end]

    @staticmethod
    def _command_worker_block():
        start = MAIN.index("async def _run_compose_library_command")
        end = MAIN.index("async def poll_native_bridge", start)
        return MAIN[start:end]

    def test_main_uses_real_library_projection_and_compose_command_boundary(self):
        dispatch = self._command_dispatch_block()
        worker = self._command_worker_block()
        self.assertIn("ComposeLibraryBridge(", MAIN)
        self.assertIn('compose_library_bridge.request_publish("startup")', MAIN)
        self.assertIn("if event_type == 'compose_library_command':", MAIN)
        self.assertIn("_track_compose_library_task", dispatch)
        self.assertIn('namespace="compose_library"', dispatch)
        for action in ("toggle_favorite", "set_watched", "refresh", "open_media"):
            self.assertIn(f"action == '{action}'", worker)

    def test_compose_library_commands_ack_before_slow_work(self):
        block = self._command_dispatch_block()
        self.assertIn('"QUEUED"', block)
        self.assertIn("_run_compose_library_command", block)
        self.assertNotIn("await add_folder()", block)
        self.assertNotIn("await remove_folder(", block)
        self.assertNotIn("store.toggle_favorite", block)
        self.assertNotIn("store.set_watched", block)
        self.assertNotIn("store.episode_by_id", block)

    def test_compose_library_request_ids_are_idempotent(self):
        block = self._command_dispatch_block()
        self.assertIn("claim_native_request(", block)
        self.assertIn('namespace="compose_library"', block)
        self.assertIn("duplicate request ignored", block)

    def test_favorite_command_does_not_treat_false_new_state_as_failure(self):
        block = self._command_worker_block()
        start = block.index("if action == 'toggle_favorite':")
        end = block.index("elif action == 'set_watched':", start)
        favorite = block[start:end]
        self.assertIn("existing = await asyncio.to_thread(store.catalog, anime_ids=[anime_id])", favorite)
        self.assertIn("await asyncio.to_thread(store.toggle_favorite, anime_id)", favorite)
        self.assertNotIn("if not updated:", favorite)

    def test_mutations_run_outside_python_ui_event_thread(self):
        worker = self._command_worker_block()
        for token in (
            "await asyncio.to_thread(store.toggle_favorite",
            "await asyncio.to_thread(",
            "store.set_watched",
            "store.episode_by_id",
        ):
            self.assertIn(token, worker)

    def test_existing_scanner_coordinator_remains_refresh_owner(self):
        block = self._command_worker_block()
        start = block.index("elif action == 'refresh':")
        end = block.index("elif action == 'open_media':", start)
        refresh = block[start:end]
        self.assertIn("ScanOrigin.USER_REFRESH", refresh)
        self.assertIn("scan_coordinator.request(", refresh)
        self.assertNotIn("bridge.scan_media_store", refresh)
        self.assertNotIn("bridge.scan_all_storage", refresh)

    def test_existing_player_bridge_owns_compose_media_open(self):
        block = self._command_worker_block()
        start = block.index("elif action == 'open_media':")
        end = block.index("else:", start)
        open_media = block[start:end]
        self.assertIn("play_episode(", open_media)
        self.assertIn("store.episode_by_id", open_media)
        self.assertNotIn("NativePlayerActivity", open_media)

    def test_compose_projection_is_derived_and_does_not_define_sqlite_schema(self):
        self.assertIn("self.library.catalog()", BRIDGE)
        self.assertIn("self.store.folders()", BRIDGE)
        self.assertIn("SCHEMA_VERSION = 1", BRIDGE)
        self.assertNotIn("CREATE TABLE", BRIDGE)
        self.assertNotIn("sqlite3", BRIDGE)
