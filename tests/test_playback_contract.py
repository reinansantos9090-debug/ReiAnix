import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MAILBOX = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
MAIN = ROOT / "main.py"


class PlaybackContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.player = PLAYER.read_text(encoding="utf-8")
        cls.main_activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        cls.mailbox = MAILBOX.read_text(encoding="utf-8")
        cls.manifest = MANIFEST.read_text(encoding="utf-8")
        cls.main = MAIN.read_text(encoding="utf-8")

    def test_progress_events_use_existing_episode_identity_and_media3_state(self):
        for token in (
            '"mediaId", currentMediaId()',
            '"episodeId", currentEpisodeId()',
            '"positionMs", position',
            '"durationMs", duration',
            '"playerState", if (::player.isInitialized) player.playbackStateLabel() else "STATE_IDLE"',
            '"isPlaying", if (::player.isInitialized) player.isPlaying else false',
            '"playbackSpeed", if (::player.isInitialized) player.playbackParameters.speed else 1f',
            'saveProgress("player_progress")',
            'saveProgress("player_paused", force = true)',
            'saveProgress("player_completed", force = true)',
        ):
            self.assertIn(token, self.player)

    def test_playback_event_capture_time_survives_async_mailbox_delivery(self):
        for token in (
            "private fun nextPlayerEventCreatedAt()",
            "val wallClockMs = System.currentTimeMillis()",
            "max(wallClockMs, previous + 1L)",
            '.put("createdAt", nextPlayerEventCreatedAt())',
            'val capturedAt = event.optLong("createdAt", 0L).takeIf { it > 0L } ?: now',
            '.put("createdAt",capturedAt)',
        ):
            self.assertIn(token, self.player + self.mailbox)

    def test_error_diagnostics_contain_required_runtime_context(self):
        for token in (
            '"timestamp", System.currentTimeMillis()',
            '"mediaId", currentMediaId()',
            '"episodeId", currentEpisodeId()',
            '"playerState", if (::player.isInitialized) player.playbackStateLabel() else "STATE_IDLE"',
            '"isPlaying", if (::player.isInitialized) player.isPlaying else false',
            'private fun publishPlayerError',
            'player_error',
        ):
            self.assertIn(token, self.player)

    def test_player_mailbox_events_avoid_ui_thread_durable_fsync(self):
        for token in (
            'NativeMailbox.writeBestEffort(',
            'fun writeBestEffort(context: Context, event: JSONObject): Boolean',
            'if (durable) {',
            'stream.fd.sync()',
        ):
            self.assertIn(token, self.player + self.mailbox)
        self.assertGreaterEqual(self.player.count("NativeMailbox.writeBestEffort("), 3)
        self.assertIn("NativeMailbox.write(", self.player)

    def test_play_launch_neighbor_queries_leave_flet_event_loop(self):
        start = self.main.index("    async def start_native_player(")
        end = self.main.index("    def play_episode(", start)
        block = self.main[start:end]
        self.assertIn("await asyncio.to_thread(library.player_navigation, path)", block)
        self.assertIn('source": "player_navigation"', block)
        self.assertNotIn("can_next=library.next_episode(path)", block)
        self.assertNotIn("can_previous=library.previous_episode(path)", block)

    def test_native_player_handoff_dedupes_exact_request_id_without_uri_window(self):
        source = self.main_activity
        self.assertIn("seenPlayerRequestIds", source)
        self.assertIn("reason=same_request", source)
        self.assertNotIn("PLAYER_HANDOFF_DEDUPE_WINDOW_MS", source)
        self.assertNotIn("lastPlayerHandoffUri", source)
        self.assertNotIn("ignored_same_uri", source)
    def test_bridge_waits_for_real_play_handoff_confirmation(self):
        bridge = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        self.assertIn('expected_event = "PLAYER_HANDOFF_DISPATCHED" if action == "play" else "COMMAND_RECEIVED"', bridge)
        self.assertIn('"PLAYER_HANDOFF_FAILED"', bridge)
        self.assertIn('"PLAYER_HANDOFF_REJECTED"', bridge)
        self.assertIn('"PLAYER_HANDOFF_DISPATCHED"', bridge)

    def test_pending_play_preserves_canonical_identity_and_stale_ordering(self):
        source = self.main_activity
        for token in (
            "pendingPlayEpisodeId",
            "pendingPlayAnimeId",
            "STATE_PENDING_PLAY_EPISODE_ID",
            "STATE_PENDING_PLAY_ANIME_ID",
            "activePlayerCommandCreatedAtMs",
            "STATE_ACTIVE_PLAYER_COMMAND_CREATED_AT_MS",
            "reason=stale_created_at",
            "PLAYER_HANDOFF_REJECTED",
            "PLAYER_REQUEST_REPLACED",
        ):
            self.assertIn(token, source)

    def test_play_handoff_only_reports_dispatched_after_start_activity(self):
        source = self.main_activity
        handoff_start = source.index("private fun openPlayer(")
        handoff_end = source.index("private fun clearPendingPlay", handoff_start)
        block = source[handoff_start:handoff_end]
        self.assertIn('"PLAYER_HANDOFF_DISPATCHED"', block)
        self.assertIn("NativeRequestState.OperationState.COMPLETED", block)
        self.assertIn("return false", block)
        self.assertIn("PLAYER_HANDOFF_FAILED", block)

    def test_first_frame_timing_contract_is_correlated_and_non_destructive(self):
        for token in (
            "commandCreatedAtMs",
            "commandReceivedAtMs",
            "handoffDispatchedAtMs",
            "activityStartedAtMs",
            "episodeTapAtMs",
            "preflightStartedAtMs",
            "preflightCompletedAtMs",
            "prepareDispatchedAtMs",
            "playerReadyAtMs",
            "firstFrameRenderedAtMs",
            '"handoffLatencyMs"',
            '"activityStartupLatencyMs"',
            '"preflightLatencyMs"',
            '"prepareLatencyMs"',
            '"firstFrameLatencyMs"',
            '"preflightDurationMs"',
            '"prepareToReadyMs"',
            '"readyToFirstFrameMs"',
            '"tapToFirstFrameMs"',
            '"totalOpenToFirstFrameMs"',
            '"assist_to_activity_ms"',
            '"activity_to_player_ms"',
            '"player_prepare_ms"',
            '"first_frame_ms"',
            'FIRST_FRAME_RENDERED',
            'FIRST_FRAME_TIMEOUT',
            'player_diagnostic',
        ):
            self.assertIn(token, self.player)
        self.assertIn('firstFrameDiagnosticTimeoutMs = 8_000L', self.player)
        self.assertIn('putExtra("commandReceivedAtMs", commandReceivedAtMs)', self.main_activity)
        self.assertIn('putExtra("handoffDispatchedAtMs", handoffDispatchedAtMs)', self.main_activity)
        timeout_start = self.player.index('FIRST_FRAME_TIMEOUT')
        timeout_block = self.player[timeout_start:self.player.index('private fun armFirstFrameDiagnostics', timeout_start)]
        self.assertNotIn('prepare()', timeout_block)
        self.assertNotIn('startActivity(', timeout_block)

    def test_progress_persistence_has_its_own_io_executor(self):
        source = self.player
        save_start = source.index("    private fun saveProgress(")
        save_end = source.index("    override fun onStart()", save_start)
        save_block = source[save_start:save_end]
        self.assertIn("progressWorker.submit", save_block)
        self.assertNotIn("playbackWorker.submit", save_block)
        self.assertIn('Thread(runnable, "ReiAnix-ProgressIO")', source)
        self.assertIn("progressWorker.shutdown()", source)
        self.assertIn("lastProgressPersistAt = System.currentTimeMillis()", source)

    def test_player_event_timestamps_are_monotonic_within_activity(self):
        self.assertIn("AtomicLong", self.player)
        self.assertIn("PLAYER_EVENT_CLOCK_MS", self.player)
        self.assertIn("private fun nextPlayerEventCreatedAt()", self.player)
        self.assertIn("max(wallClockMs, previous + 1L)", self.player)
        self.assertIn('val transitionCreatedAtMs = nextPlayerEventCreatedAt()', self.player)
        self.assertIn('val exitCapturedAt = nextPlayerEventCreatedAt()', self.player)
        progress_start = self.player.index("private fun buildProgressEvent")
        progress_end = self.player.index("private fun saveProgress", progress_start)
        progress_block = self.player[progress_start:progress_end]
        self.assertIn('put("createdAt", nextPlayerEventCreatedAt())', progress_block)
        self.assertNotIn('put("createdAt", System.currentTimeMillis())', progress_block)
        self.assertIn("val exitCapturedAt = nextPlayerEventCreatedAt()", self.player)

    def test_mailbox_best_effort_queue_is_bounded_and_not_globally_synchronized(self):
        self.assertIn("BEST_EFFORT_QUEUE_CAPACITY = 128", self.mailbox)
        self.assertIn("ArrayBlockingQueue(BEST_EFFORT_QUEUE_CAPACITY)", self.mailbox)
        self.assertIn("ThreadPoolExecutor.AbortPolicy()", self.mailbox)
        self.assertNotIn("@Synchronized\n    fun write(", self.mailbox)
        self.assertNotIn("@Synchronized\n    fun writeOrThrow(", self.mailbox)

    def test_critical_player_state_events_stay_on_durable_mailbox_path(self):
        source = self.player
        save_start = source.index("    private fun saveProgress(")
        save_end = source.index("    override fun onStart()", save_start)
        save_block = source[save_start:save_end]
        self.assertIn("val durable = force || eventType in setOf", save_block)
        self.assertIn("NativeMailbox.write(this@NativePlayerActivity, event)", save_block)
        self.assertIn("NativeMailbox.writeBestEffort(this@NativePlayerActivity, event)", save_block)
        self.assertIn('"player_paused"', save_block)
        self.assertIn('"player_completed"', save_block)
        exit_idx = source.index('"type", "player_exited"')
        exit_end = source.index('"player_exit_queued reason="', exit_idx)
        exit_block = source[exit_idx:exit_end]
        self.assertIn("NativeMailbox.write(", exit_block)
        self.assertNotIn("NativeMailbox.writeBestEffort(", exit_block)
        request_start = source.index("private fun requestEpisode")
        request_end = source.index("private fun seekToSavedPosition", request_start)
        request_block = source[request_start:request_end]
        self.assertIn("playbackWorker.submit", request_block)
        self.assertIn("NativeMailbox.write(", request_block)
        self.assertIn("val published = NativeMailbox.write(", request_block)
        autoplay_idx = source.index('persistCanonicalPlayerSetting("player.autoplay_next"')
        autoplay_block = source[max(0, autoplay_idx - 240):autoplay_idx + 220]
        self.assertIn('"compose_settings_set"', autoplay_block)
        self.assertNotIn('"player_autoplay_changed"', save_block)
        self.assertNotIn("NativeMailbox.writeBestEffort(", autoplay_block)

    def test_mailbox_best_effort_is_background_only_and_command_diagnostics_remain_durable(self):
        mailbox = self.mailbox
        main_activity = self.main_activity
        self.assertIn("ReiFlix-MailboxTelemetry", mailbox)
        self.assertIn("bestEffortExecutor.execute", mailbox)
        diagnostic_start = main_activity.index("private fun publishNativeDiagnostic")
        diagnostic_end = main_activity.index("private fun publishNativeCommandError", diagnostic_start)
        diagnostic_block = main_activity[diagnostic_start:diagnostic_end]
        self.assertIn("NativeMailbox.write(", diagnostic_block)
    def test_bridge_coalesces_only_consecutive_progress_for_same_identity(self):
        from core.android_bridge import AndroidBridge

        base = {
            "eventType": "player_progress",
            "requestId": "req-1",
            "payload": {
                "playerSessionId": "session-a",
                "activityInstanceId": "activity-a",
                "episodeId": "101",
                "uri": "file:///a.mkv",
                "playerGeneration": 2,
                "transitionGeneration": 3,
            },
        }
        later = {**base, "createdAt": 20, "payload": {**base["payload"], "positionMs": 20}}
        newest = {**base, "createdAt": 30, "payload": {**base["payload"], "positionMs": 30}}
        pause = {"eventType": "player_paused", "requestId": "req-1", "payload": dict(base["payload"])}
        after_pause = {**base, "createdAt": 40, "payload": {**base["payload"], "positionMs": 40}}
        events = AndroidBridge._coalesce_progress_events([base, later, newest, pause, after_pause])
        self.assertEqual(3, len(events))
        self.assertIs(events[0], newest)
        self.assertIs(events[1], pause)
        self.assertIs(events[2], after_pause)

    def test_consumption_pipeline_has_no_parallel_player_state(self):
        for token in (
            'ConsumptionState.UNWATCHED',
            'ConsumptionState.IN_PROGRESS',
            'ConsumptionState.COMPLETED',
            'ConsumptionState.WATCHED',
            'COMPLETION_RATIO = 0.90',
        ):
            consumption = (ROOT / "core/consumption.py").read_text(encoding="utf-8")
            self.assertIn(token, consumption)
        self.assertNotIn("PLAYER_COMPLETED", (ROOT / "core/consumption.py").read_text(encoding="utf-8"))

    def test_existing_python_event_consumer_updates_single_store(self):
        for token in (
            "elif event_type in {'player_progress', 'player_paused', 'player_completed'}:",
            "store.save_progress",
            "elif event_type == 'player_exited':",
        ):
            self.assertIn(token, self.main)

    def test_audio_subtitle_speed_and_seek_stay_on_one_media3_player(self):
        for token in (
            'TrackSelectionOverride(',
            'clearOverridesOfType(trackType)',
            'player.setPlaybackSpeed',
            'player.playbackParameters.speed',
            'PlayerGesturePolicy.seekTarget',
            'player.trackSelectionParameters',
            'player.setAudioAttributes',
            'C.USAGE_MEDIA',
        ):
            self.assertIn(token, self.player)
        self.assertEqual(1, self.player.count("ExoPlayer.Builder(this).build()"))
        self.assertNotIn("ExoPlayer.Builder(this).build()", self.player[self.player.index("private fun showTrackSelection"):])

    def test_player_gestures_and_lock_affordance_are_explicit(self):
        for token in (
            'GESTURE_HORIZONTAL_IGNORED',
            'LOCK_AFFORDANCE_TIMEOUT_MS',
            'lockAffordanceHider',
            'if (locked) {',
        ):
            self.assertIn(token, self.player)
        self.assertNotIn("TrackSelectionDialogBuilder", self.player)
        self.assertNotIn("horizontalSeekDelta", self.player)
        self.assertNotIn("setting_gestures_horizontal_swipe_seek", self.player)

    def test_lifecycle_rotation_pip_and_exit_are_single_activity_contracts(self):
        for token in (
            'override fun onSaveInstanceState(outState: Bundle)',
            'override fun onConfigurationChanged',
            'override fun onPictureInPictureModeChanged',
            'saveProgress("player_progress", force = true)',
            'reportPlayerExit("activity_finish")',
            'if (sessionState == SessionState.DESTROYED || exitReported) return',
            'android:supportsPictureInPicture="true"',
        ):
            self.assertIn(token, self.player + self.manifest)

    def test_media3_state_machine_is_explicit(self):
        for token in (
            'Player.STATE_IDLE',
            'Player.STATE_BUFFERING',
            'Player.STATE_READY',
            'Player.STATE_ENDED',
            'onIsPlayingChanged(isPlaying: Boolean)',
            'onPlaybackStateChanged(state: Int)',
            'showPlayerError(',
        ):
            self.assertIn(token, self.player)


if __name__ == "__main__":
    unittest.main()
