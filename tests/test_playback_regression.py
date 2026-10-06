import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "main.py").read_text(encoding="utf-8")
DETAILS = (ROOT / "views/details_view.py").read_text(encoding="utf-8")
HOME = (ROOT / "views/home_view.py").read_text(encoding="utf-8")
BRIDGE = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
MAIN_ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
REQUEST = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")


class PlaybackRegressionTests(unittest.TestCase):
    def test_same_episode_new_request_id_is_not_rejected_by_uri_window(self):
        self.assertIn("seenPlayerRequestIds", MAIN_ACTIVITY)
        self.assertIn("reason=same_request", MAIN_ACTIVITY)
        self.assertNotIn("PLAYER_HANDOFF_DEDUPE_WINDOW_MS", MAIN_ACTIVITY)
        self.assertNotIn("lastPlayerHandoffUri", MAIN_ACTIVITY)
        self.assertNotIn("ignored_same_uri", MAIN_ACTIVITY)

    def test_details_keeps_progressible_episode_clickable(self):
        self.assertIn("clickable = None if is_missing else lambda _, item=episode: play(item)", DETAILS)
        self.assertIn('progress_seconds=episode.get("progress") or 0', DETAILS)
        self.assertIn('episode_id=episode.get("id")', DETAILS)
        self.assertIn('anime_id=anime_group.get("id")', DETAILS)

    def test_home_passes_canonical_episode_and_anime_ids_to_player_trace(self):
        self.assertIn('episode_id=episode.get("id")', HOME)
        self.assertIn('anime_id=item.get("anime_id")', HOME)
        self.assertNotIn('anime_id=item.get("id")', HOME)

    def test_python_player_launch_preserves_nonzero_resume_position(self):
        block = MAIN[MAIN.index("def play_episode"):MAIN.index("def open_marathon")]
        self.assertIn("progress_seconds", block)
        self.assertIn("max(0, int(progress_seconds * 1000))", block)
        self.assertIn('settings.get("player.resume")', block)
        self.assertIn("episode_id=episode_id", MAIN)
        self.assertIn("anime_id=anime_id", MAIN)

    def test_bridge_carries_resume_and_identity_to_native_request(self):
        self.assertIn("position_ms=max(0, int(position_ms))", BRIDGE)
        self.assertIn("episode_id=str(episode_id)", BRIDGE)
        self.assertIn("anime_id=str(anime_id)", BRIDGE)
        self.assertIn('putExtra("episodeId", episodeId)', REQUEST)
        self.assertIn('putExtra("animeId", animeId)', REQUEST)

    def test_native_player_trace_contains_resume_and_identity_stages(self):
        for token in (
            "PLAYER_ACTIVITY_ON_CREATE",
            "PLAYER_REUSE_INTENT",
            "PREFLIGHT_ASYNC_START",
            "MEDIA3_PREPARE_DISPATCHED",
            "RESUME_APPLIED",
            "RESUME_CLAMPED",
            "FIRST_FRAME_RENDERED",
            '"animeId"',
            '"episodeId"',
        ):
            self.assertIn(token, PLAYER)

    def test_progress_does_not_gate_the_episode_click_path(self):
        start = DETAILS.index("def episode_item")
        end = DETAILS.index("def load_more_episodes", start)
        block = DETAILS[start:end]
        self.assertIn('is_missing = bool(episode.get("missing"))', block)
        self.assertIn("clickable = None if is_missing else", block)
        self.assertNotIn("if episode.get('progress')", block)

    def test_exact_request_id_dedupe_remains_bounded(self):
        self.assertIn("while (seenPlayerRequestIds.size > 256)", MAIN_ACTIVITY)
        self.assertIn("seenPlayerRequestIds.remove(oldest)", MAIN_ACTIVITY)


if __name__ == "__main__":
    unittest.main()
