import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ProgressContinuityTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_python_progress_and_exit_require_complete_playback_identity(self):
        source = self.read("main.py")
        self.assertIn("def player_callback_identity_is_complete(payload):", source)
        self.assertIn('"PLAYER_CALLBACK_REJECTED_INCOMPLETE_IDENTITY"', source)
        progress = source[
            source.index("elif event_type in {'player_progress', 'player_paused', 'player_completed'}:")
            : source.index("elif event_type == 'player_mark_watched':")
        ]
        self.assertIn("player_callback_identity_is_complete(payload)", progress)
        exited = source[
            source.index("elif event_type == 'player_exited':")
            : source.index("elif event_type == 'google_sign_in_started':")
        ]
        self.assertIn("player_callback_identity_is_complete(payload)", exited)

    def test_exit_persistence_carries_media_identity(self):
        source = self.read("main.py")
        exited = source[
            source.index("elif event_type == 'player_exited':")
            : source.index("elif event_type == 'google_sign_in_started':")
        ]
        self.assertIn('episode_id=payload.get("episodeId")', exited)
        self.assertIn('media_id=payload.get("mediaId")', exited)
        self.assertIn('session_id=payload.get("playerSessionId")', exited)

    def test_native_progress_event_is_self_identifying(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
        builder = source[
            source.index("private fun buildProgressEvent")
            : source.index("private fun saveProgress", source.index("private fun buildProgressEvent"))
        ]
        for token in (
            "episodeId = currentEpisodeId()",
            "mediaId = currentMediaId()",
            "sessionId = playerSessionId.trim()",
            "mediaUri = uri.toString().trim()",
            "PROGRESS_EVENT_REJECTED_INCOMPLETE_IDENTITY",
        ):
            self.assertIn(token, builder)
        self.assertIn('.put("uri", mediaUri)', builder)
        self.assertIn('.put("mediaId", mediaId)', builder)
        self.assertIn('.put("episodeId", episodeId)', builder)
        self.assertIn('.put("playerSessionId", playerSessionId)', builder)


if __name__ == "__main__":
    unittest.main()
