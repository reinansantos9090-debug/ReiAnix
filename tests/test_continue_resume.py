import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
STORE_SOURCE = (ROOT / "core/library_store.py").read_text(encoding="utf-8")
PLAYER = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
).read_text(encoding="utf-8")
POLICY = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/PlayerMediaPolicy.kt"
).read_text(encoding="utf-8")
HOME = (ROOT / "views/home_view.py").read_text(encoding="utf-8")


class ContinueResumeTests(unittest.TestCase):
    def test_continue_resolves_episode_by_canonical_episode_id(self):
        self.assertIn("def episode_by_id(self, episode_id):", STORE_SOURCE)
        self.assertIn("fresh_episode = await asyncio.to_thread(store.episode_by_id, episode_id)", MAIN)
        self.assertIn('launch_anime_id = fresh_episode.get("anime_id")', MAIN)
        self.assertIn('launch_path = str(fresh_episode.get("path") or "").strip()', MAIN)

    def test_continue_does_not_trust_stale_home_anime_identity(self):
        play = MAIN[MAIN.index("def play_episode("):MAIN.index("def open_marathon(", MAIN.index("def play_episode("))]
        self.assertIn('player_active_anime_id["value"] = launch_anime_id', play)
        self.assertIn('anime_id=launch_anime_id', play)
        self.assertIn('episode_id=fresh_episode.get("id")', play)
        self.assertIn('uri_source": "canonical_sqlite_row"', play)
        # The Home control tree is intentionally untouched in this stage.
        self.assertIn('on_play_episode(', HOME)

    def test_resume_uses_seconds_in_sqlite_and_ms_on_native_boundary(self):
        self.assertIn("position_ms / 1000.0", MAIN)
        self.assertIn("duration_ms / 1000.0", MAIN)
        self.assertIn("int(max(0.0, launch_progress_seconds) * 1000)", MAIN)
        self.assertIn('"positionMs", position', PLAYER)
        self.assertIn('"durationMs", duration', PLAYER)

    def test_continue_reloads_the_latest_persisted_progress(self):
        play = MAIN[MAIN.index("async def launch_native_player():"):MAIN.index("def open_marathon(", MAIN.index("async def launch_native_player():"))]
        self.assertIn('raw_progress = fresh_episode.get("progress")', play)
        self.assertIn('raw_progress_seconds = float(raw_progress or 0.0)', play)
        self.assertIn('float(raw_progress or 0.0)', play)
        self.assertIn('duration_seconds = max(', play)
        self.assertIn('launch_progress_seconds = raw_progress_seconds', play)
        self.assertIn('"PROGRESS_VALIDATED"', play)

    def test_ready_gates_resume_seek_before_playback(self):
        prepare = PLAYER[PLAYER.index("private fun prepareCurrentMedia"):PLAYER.index("private fun createPlayerListener")]
        self.assertIn("player.playWhenReady = false", prepare)
        self.assertIn("RESUME_SEEK_REQUESTED", PLAYER)
        self.assertIn("RESUME_SEEK_APPLIED", PLAYER)
        self.assertIn("val appliedResumePositionMs = seekToSavedPosition(restoredPositionMs ?: savedPosition)", PLAYER)
        self.assertIn("player.playWhenReady = requestedPlayWhenReadyForGeneration", PLAYER)

    def test_resume_position_is_clamped_by_existing_policy(self):
        self.assertIn("fun safeResumePosition", POLICY)
        self.assertIn("requestedMs.coerceIn(0L, lastPlayable)", POLICY)

    def test_invalid_continue_source_is_rejected_before_native_handoff(self):
        play = MAIN[MAIN.index("async def launch_native_player():"):MAIN.index("def open_marathon(", MAIN.index("async def launch_native_player():"))]
        self.assertIn('"EPISODE_NOT_FOUND"', play)
        self.assertIn('"MEDIA_URI_MISSING"', play)
        self.assertIn('"FILE_NOT_FOUND"', play)
        self.assertIn("STALE_SESSION_AFTER_PROGRESS_LOOKUP", play)

    def test_progress_writes_are_session_bound(self):
        self.assertIn("activate_playback_session", MAIN)
        self.assertIn("invalidate_playback_session", MAIN)
        self.assertIn('session_id=payload.get("playerSessionId")', MAIN)
        self.assertIn("self._playback_session_lock", STORE_SOURCE)
        self.assertIn('status="stale_session"', STORE_SOURCE)

    def test_stale_session_cannot_overwrite_new_session_progress(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("stage27", {"title": "earlier validation stage 27", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage27/e01",
                "E01.mkv",
                1,
                1,
                media_identity="stage27:e01",
            )
            self.assertTrue(store.activate_playback_session("session-a"))
            self.assertTrue(
                store.save_progress(
                    "content://stage27/e01",
                    300,
                    1000,
                    episode_id=1,
                    event_created_at=1_000,
                    session_id="session-a",
                )
            )
            self.assertTrue(store.activate_playback_session("session-b"))
            self.assertFalse(
                store.save_progress(
                    "content://stage27/e01",
                    300,
                    1000,
                    episode_id=1,
                    event_created_at=2_000,
                    session_id="session-a",
                )
            )
            self.assertTrue(
                store.save_progress(
                    "content://stage27/e01",
                    500,
                    1000,
                    episode_id=1,
                    event_created_at=3_000,
                    session_id="session-b",
                )
            )
            self.assertEqual(500, store.physical_row("content://stage27/e01")["progress"])

    def test_same_session_can_update_progress_without_creating_parallel_state(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("fixture_27-b", {"title": "earlier validation stage 27 B", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage27/e02",
                "E02.mkv",
                1,
                2,
                media_identity="stage27:e02",
            )
            episode = store.physical_row("content://stage27/e02")
            self.assertTrue(store.activate_playback_session("same"))
            self.assertTrue(
                store.save_progress(
                    episode["path"],
                    180,
                    900,
                    episode_id=episode["id"],
                    event_created_at=1_000,
                    session_id="same",
                )
            )
            self.assertTrue(
                store.save_progress(
                    episode["path"],
                    240,
                    900,
                    episode_id=episode["id"],
                    event_created_at=2_000,
                    session_id="same",
                )
            )
            row = store.physical_row(episode["path"])
            self.assertEqual(240, row["progress"])
            self.assertEqual(900, row["duration"])

    def test_continue_projection_excludes_completed_episode(self):
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as directory:
            store = LibraryStore(directory)
            anime = store.upsert_anime("fixture_27-c", {"title": "earlier validation stage 27 C", "genres": "[]"})
            store.upsert_episode(
                anime,
                "content://stage27/e03",
                "E03.mkv",
                1,
                3,
                media_identity="stage27:e03",
            )
            episode = store.physical_row("content://stage27/e03")
            self.assertTrue(store.save_progress(episode["path"], 899, 1000, episode_id=episode["id"], event_created_at=1_000))
            self.assertEqual(1, len(store.continue_watching()))
            self.assertTrue(store.save_progress(episode["path"], 900, 1000, episode_id=episode["id"], event_created_at=2_000))
            self.assertEqual([], store.continue_watching())

    def test_no_home_file_change_is_required_for_continue_identity_fix(self):
        self.assertNotIn('episode_id=episode.get("anime_id")', HOME)
        self.assertIn('episode_id=episode.get("id")', HOME)


if __name__ == "__main__":
    unittest.main()
