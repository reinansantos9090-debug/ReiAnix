import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAIN_PY = (ROOT / "main.py").read_text(encoding="utf-8")
BRIDGE = (ROOT / "core" / "android_bridge.py").read_text(encoding="utf-8")
MAIN_ACTIVITY = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
).read_text(encoding="utf-8")
PLAYER = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
).read_text(encoding="utf-8")


class AssistOpenTests(unittest.TestCase):
    def test_paused_play_preserves_the_original_native_command(self):
        self.assertIn(
            'private const val STATE_PENDING_PLAY_INTENT_DATA = "reiflix.pendingPlayIntentData"',
            MAIN_ACTIVITY,
        )
        self.assertIn("private var pendingPlayIntentData: String? = null", MAIN_ACTIVITY)
        self.assertIn("pendingPlayIntentData = savedInstanceState?.getString(STATE_PENDING_PLAY_INTENT_DATA)", MAIN_ACTIVITY)
        self.assertIn("outState.putString(STATE_PENDING_PLAY_INTENT_DATA, pendingPlayIntentData)", MAIN_ACTIVITY)
        self.assertIn("pendingPlayIntentData = data.toString()", MAIN_ACTIVITY)
        self.assertIn("val preservedPlayData = pendingPlayIntentData", MAIN_ACTIVITY)
        self.assertIn('runCatching { Uri.parse(raw) }.getOrNull()', MAIN_ACTIVITY)
        self.assertIn('result = if (preservedPlayData != null) "dispatched_preserved_request" else "dispatched_legacy_restore"', MAIN_ACTIVITY)
        self.assertIn("pendingPlayIntentData = null", MAIN_ACTIVITY)

    def test_assist_single_flight_and_session_identity_are_explicit(self):
        self.assertIn('if player_launch_inflight["value"]:', MAIN_PY)
        self.assertIn('"ASSIST_REQUEST_REJECTED"', MAIN_PY)
        self.assertIn('"OPEN_REQUEST_IN_FLIGHT"', MAIN_PY)
        self.assertIn('"ASSIST_REQUEST_CREATED"', MAIN_PY)
        self.assertIn('player_active_request_id["value"] = None', MAIN_PY)
        self.assertIn('player_active_episode_id["value"] = episode_id', MAIN_PY)
        self.assertIn('player_active_anime_id["value"] = anime_id', MAIN_PY)
        self.assertIn('player_active_uri["value"] = path', MAIN_PY)

    def test_assist_request_is_accepted_only_after_native_handoff_confirmation(self):
        self.assertIn('"ASSIST_REQUEST_ACCEPTED"', MAIN_PY)
        self.assertIn('"reason": "native_handoff_confirmed"', MAIN_PY)
        bridge_block = BRIDGE[BRIDGE.index("async def _launch"):BRIDGE.index("    async def ", BRIDGE.index("async def _launch") + 10)]
        self.assertIn('expected_event = "PLAYER_HANDOFF_DISPATCHED" if action == "play" else "COMMAND_RECEIVED"', bridge_block)
        self.assertIn("PLAYER_HANDOFF_DISPATCHED", bridge_block)
        self.assertIn("NATIVE_HANDOFF_ACCEPTED", bridge_block)

    def test_bridge_diagnostics_carry_assist_identity(self):
        for token in (
            '"player_session_id": params.get("player_session_id")',
            '"episode_id": params.get("episode_id")',
            '"anime_id": params.get("anime_id")',
        ):
            self.assertIn(token, BRIDGE)

    def test_episode_and_uri_validation_stay_before_media3_prepare(self):
        self.assertIn('"EPISODE_RESOLVED"', MAIN_PY)
        self.assertIn('"URI_VALIDATED"', MAIN_PY)
        prepare = PLAYER[PLAYER.index("private fun prepareCurrentMedia"):PLAYER.index("private fun createPlayerListener")]
        self.assertIn("validateLocalSource(localUri)", prepare)
        self.assertIn("player.setMediaItem(mediaItem)", prepare)
        self.assertIn("player.prepare()", prepare)
        self.assertNotIn("Thread.sleep", prepare)
        self.assertNotIn("SystemClock.sleep", prepare)

    def test_source_validation_has_explicit_failure_classes(self):
        for code in (
            "STORAGE_PERMISSION_MISSING",
            "SAF_PERMISSION_MISSING",
            "MEDIASTORE_ITEM_UNAVAILABLE",
            "FILE_NOT_FOUND",
            "MEDIA_URI_INVALID",
        ):
            self.assertIn(code, PLAYER)
        for code in ("EPISODE_NOT_FOUND", "MEDIA_URI_MISSING", "FILE_NOT_FOUND"):
            self.assertIn(code, MAIN_PY)
        self.assertIn("SourceValidationFailure", PLAYER)
        self.assertIn('validateLocalSource', PLAYER)

    def test_media3_failure_classes_are_kept_distinct(self):
        for token in (
            "MEDIA3_PREPARE_FAILED",
            "classifyPlaybackFailure",
            "PlaybackFailureKind.DECODER",
            "PlaybackFailureKind.SOURCE",
        ):
            self.assertIn(token, PLAYER)

    def test_first_frame_is_the_assist_completion_boundary(self):
        self.assertIn('diagnostic_event == "FIRST_FRAME_RENDERED"', MAIN_PY)
        self.assertIn('"ASSIST_COMPLETED"', MAIN_PY)
        self.assertIn('"completion": "FIRST_FRAME_RENDERED"', MAIN_PY)
        self.assertIn('events.contains(Player.EVENT_RENDERED_FIRST_FRAME)', PLAYER)
        self.assertIn('publishPlayerError', PLAYER)

    def test_session_generation_fences_are_preserved(self):
        for token in (
            "player_callback_is_current",
            "stale_session",
            "stale_request",
            "stale_player_generation",
            "stale_transition_generation",
            "PLAYER_CALLBACK_STALE",
        ):
            self.assertIn(token, MAIN_PY)
        for token in (
            "isCurrentPreparation",
            "generation == playerGeneration",
            "transitionGeneration == expectedTransitionGeneration",
            "pendingPreparation?.cancel(true)",
            "PLAYER_ERROR_STALE_IGNORED",
        ):
            self.assertIn(token, PLAYER)

    def test_lifecycle_exit_cannot_resurrect_an_old_assist(self):
        for token in (
            "def invalidate_player_session",
            'player_session_active["value"] = False',
            'player_active_session_id["value"] = None',
            'player_active_request_id["value"] = None',
            'player_active_episode_id["value"] = None',
            'player_active_anime_id["value"] = None',
            'player_active_uri["value"] = None',
        ):
            self.assertIn(token, MAIN_PY)
        for token in (
            "reportPlayerExit",
            "MainActivity.notePlayerExit",
            "sessionState == SessionState.DESTROYED",
            "exitReported",
        ):
            self.assertIn(token, PLAYER)

    def test_race_matrix_has_a_real_contract_for_each_case(self):
        cases = {
            "A_normal_assist": ("ASSIST_REQUEST_CREATED", "ASSIST_COMPLETED"),
            "B_double_tap": ("OPEN_REQUEST_IN_FLIGHT", "seenPlayerRequestIds"),
            "C_assist_back": ("invalidate_player_session", "reportPlayerExit"),
            "D_activity_recreation": ("pendingPlayIntentData", "player_session_id"),
            "E_previous_player_finishing": ("lastPlayerExitAtMs", "PLAYER_HANDOFF_REJECTED"),
            "sessionless_handoff": ("PLAYER_SESSION_INVALID", "playerSessionId"),
            "F_episode_a_then_b": ("player_active_session_id", "PLAYER_REQUEST_REPLACED"),
            "G_slow_sqlite": ("asyncio.to_thread(library.player_navigation", "transition_guard"),
            "H_slow_uri_resolution": ("playbackWorker.submit", "isCurrentPreparation"),
            "I_slow_media3_prepare": ("prepare()", "playerGeneration"),
            "J_missing_file": ("FILE_NOT_FOUND", "showPlayerError"),
            "episode_validation": ("EPISODE_NOT_FOUND", "MEDIA_URI_MISSING"),
            "K_invalid_uri": ("MEDIA_URI_INVALID", "normalizeLocalReference"),
            "L_missing_permission": ("STORAGE_PERMISSION_MISSING", "SafScanner.isAuthorizedDocument"),
            "M_valid_saf": ("SAF_PERMISSION_MISSING", "SafScanner.isAuthorizedDocument"),
            "N_valid_mediastore": ("MEDIASTORE_ITEM_UNAVAILABLE", "MediaStoreScanner.isAuthorizedDocument"),
            "O_media3_prepare_failure": ("MEDIA3_PREPARE_FAILED", "player.prepare()"),
            "P_unsupported_media": ("PlaybackFailureKind.DECODER", "showPlayerError"),
            "Q_old_request": ("stale_request", "commandCreatedAtMs"),
            "R_old_mailbox": ("MAILBOX_STALE_COMMAND_DISCARDED", "bridge.drain()"),
            "S_old_callback": ("PLAYER_CALLBACK_STALE", "player_callback_is_current"),
            "T_old_timeout": ("PLAYER_TIMEOUT_STALE", "episodeChangeTimeoutSessionId"),
            "U_old_generation": ("stale_player_generation", "playerGeneration"),
            "V_details_screen_exit": ("player_exited", "invalidate_player_session"),
            "W_retry_after_failure": ("ASSIST_FAILED", "player_launch_inflight[\"value\"] = False"),
        }
        combined = MAIN_PY + MAIN_ACTIVITY + PLAYER
        for name, required in cases.items():
            for token in required:
                self.assertIn(token, combined, msg=f"earlier validation stage 26 case {name} lacks contract {token}")


if __name__ == "__main__":
    unittest.main()
