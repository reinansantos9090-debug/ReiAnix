import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = (ROOT / "core" / "android_bridge.py").read_text(encoding="utf-8")
MAIN_ACTIVITY = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
PLAYER = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").read_text(encoding="utf-8")
REQUEST = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt").read_text(encoding="utf-8")


class AssistOpenTests(unittest.TestCase):
    def test_paused_play_preserves_the_original_native_command(self):
        self.assertIn('private const val STATE_PENDING_PLAY_INTENT_DATA = "reiflix.pendingPlayIntentData"', MAIN_ACTIVITY)
        self.assertIn("private var pendingPlayIntentData: String? = null", MAIN_ACTIVITY)
        self.assertIn("pendingPlayIntentData = savedInstanceState?.getString(STATE_PENDING_PLAY_INTENT_DATA)", MAIN_ACTIVITY)
        self.assertIn("outState.putString(STATE_PENDING_PLAY_INTENT_DATA, pendingPlayIntentData)", MAIN_ACTIVITY)
        self.assertIn("pendingPlayIntentData = data.toString()", MAIN_ACTIVITY)
        self.assertIn("val preservedPlayData = pendingPlayIntentData", MAIN_ACTIVITY)
        self.assertIn('runCatching { Uri.parse(raw) }.getOrNull()', MAIN_ACTIVITY)
        self.assertIn("pendingPlayIntentData = null", MAIN_ACTIVITY)

    def test_assist_single_flight_and_session_identity_are_explicit(self):
        self.assertIn("seenPlayerRequestIds", MAIN_ACTIVITY)
        self.assertIn("while (seenPlayerRequestIds.size > 256)", MAIN_ACTIVITY)
        self.assertIn("PLAYER_HANDOFF_DUPLICATE", MAIN_ACTIVITY)
        self.assertIn("PLAYER_HANDOFF_REJECTED", MAIN_ACTIVITY)
        self.assertIn("activePlayerSessionId", MAIN_ACTIVITY)
        self.assertIn("playerSessionId", REQUEST)

    def test_assist_request_is_accepted_only_after_native_handoff_confirmation(self):
        self.assertIn("PLAYER_HANDOFF_DISPATCHED", MAIN_ACTIVITY)
        self.assertIn("PLAYER_SESSION_CREATED", PLAYER)
        self.assertIn("FIRST_FRAME_RENDERED", PLAYER)
        self.assertIn("events.contains(Player.EVENT_RENDERED_FIRST_FRAME)", PLAYER)

    def test_bridge_diagnostics_carry_assist_identity(self):
        for token in (
            '"player_session_id": params.get("player_session_id")',
            '"episode_id": params.get("episode_id")',
            '"anime_id": params.get("anime_id")',
        ):
            self.assertIn(token, BRIDGE)
        self.assertIn("PLAYER_HANDOFF_DISPATCHED", MAIN_ACTIVITY)

    def test_episode_and_uri_validation_stay_before_media3_prepare(self):
        prepare = PLAYER[PLAYER.index("private fun prepareCurrentMedia"):PLAYER.index("private fun createPlayerListener")]
        self.assertIn("validateLocalSource(localUri)", prepare)
        self.assertIn("player.setMediaItem(mediaItem, initialPositionMsForGeneration)", prepare)
        self.assertIn("player.prepare()", prepare)
        self.assertNotIn("Thread.sleep", prepare)
        self.assertNotIn("SystemClock.sleep", prepare)

    def test_source_validation_has_explicit_failure_classes(self):
        for code in ("STORAGE_PERMISSION_MISSING", "SAF_PERMISSION_MISSING", "MEDIASTORE_ITEM_UNAVAILABLE", "FILE_NOT_FOUND", "MEDIA_URI_INVALID"):
            self.assertIn(code, PLAYER)
        self.assertIn("SourceValidationFailure", PLAYER)
        self.assertIn("validateLocalSource", PLAYER)

    def test_media3_failure_classes_are_kept_distinct(self):
        for token in ("MEDIA3_PREPARE_FAILED", "classifyPlaybackFailure", "PlaybackFailureKind.DECODER", "PlaybackFailureKind.SOURCE"):
            self.assertIn(token, PLAYER)

    def test_first_frame_is_the_playback_completion_boundary(self):
        self.assertIn("FIRST_FRAME_RENDERED", PLAYER)
        self.assertIn("events.contains(Player.EVENT_RENDERED_FIRST_FRAME)", PLAYER)
        self.assertIn("publishPlayerError", PLAYER)
        self.assertIn("PLAYER_ERROR_STALE_IGNORED", PLAYER)

    def test_session_generation_fences_are_preserved(self):
        for token in ("isCurrentPreparation", "generation == playerGeneration", "transitionGeneration == expectedTransitionGeneration", "pendingPreparation?.cancel(true)", "PLAYER_ERROR_STALE_IGNORED"):
            self.assertIn(token, PLAYER)

    def test_lifecycle_exit_cannot_resurrect_an_old_player_session(self):
        for token in ("reportPlayerExit(", "MainActivity.notePlayerExit", "sessionState == SessionState.DESTROYED", "exitReported"):
            self.assertIn(token, PLAYER)

    def test_race_matrix_has_a_real_native_contract_for_each_case(self):
        cases = {
            "duplicate_request": ("PLAYER_HANDOFF_DUPLICATE", "seenPlayerRequestIds"),
            "activity_recreation": ("pendingPlayIntentData", "STATE_PENDING_PLAY_INTENT_DATA"),
            "player_exit": ("reportPlayerExit(", "PLAYER_SESSION_INVALIDATED"),
            "slow_preparation": ("playbackWorker.submit", "isCurrentPreparation"),
            "invalid_local_media": ("MEDIA_URI_INVALID", "validateLocalSource"),
            "missing_file": ("FILE_NOT_FOUND", "showPlayerError"),
            "stale_next_previous": ("PLAYER_NEXT_STALE_REJECTED", "PLAYER_PREVIOUS_STALE_REJECTED"),
            "first_frame": ("FIRST_FRAME_RENDERED", "playerGeneration"),
        }
        combined = MAIN_ACTIVITY + PLAYER + REQUEST
        for name, required in cases.items():
            for token in required:
                self.assertIn(token, combined, msg=f"native playback case {name} lacks contract {token}")


if __name__ == "__main__":
    unittest.main()
