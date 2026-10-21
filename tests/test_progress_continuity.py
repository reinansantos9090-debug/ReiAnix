import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source


ROOT = Path(__file__).resolve().parents[1]


class ProgressContinuityTests(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_native_progress_and_exit_require_complete_playback_identity(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
        progress = source[source.index("private fun buildProgressEvent"):source.index("private fun saveProgress")]
        self.assertIn("currentEpisodeId()", progress)
        self.assertIn("currentMediaId()", progress)
        self.assertIn("playerSessionId", progress)
        self.assertIn("uri.toString()", progress)
        self.assertIn("NATIVE_PROGRESS_PERSIST_FAILED", source)
    def test_exit_persistence_carries_media_identity(self):
        source = self.read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
        exited = source[source.index("private fun reportPlayerExit"):source.index("private fun finishPlayer", source.index("private fun reportPlayerExit"))]
        self.assertIn('put("episodeId", currentEpisodeId())', exited)
        self.assertIn('put("mediaId", currentMediaId())', exited)
        self.assertIn('put("playerSessionId", playerSessionId)', exited)
        self.assertIn("saveProgress(", exited)
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
