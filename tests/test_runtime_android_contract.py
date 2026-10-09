import ast
import unittest
from pathlib import Path

from core.android_bridge import AndroidBridge


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
PLAYER_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
SYSTEM_UI = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/SystemUiController.kt"
PLAYER_LAYOUT = ROOT / "android/app/src/main/res/layout/native_player_view.xml"


class RuntimeAndroidContractTests(unittest.TestCase):
    def test_python_normalizer_preserves_content_uri_identity(self):
        source = "content://media/external/video/media/42"
        self.assertEqual(AndroidBridge.normalize_local_media_reference(source), source)

    def test_python_normalizer_accepts_case_insensitive_local_uri_scheme(self):
        self.assertEqual(
            AndroidBridge.normalize_local_media_reference("CONTENT://media/external/video/media/42"),
            "content://media/external/video/media/42",
        )
        self.assertEqual(
            AndroidBridge.normalize_local_media_reference("FILE:///storage/emulated/0/Anime/Ep 01.mp4"),
            "file:///storage/emulated/0/Anime/Ep 01.mp4",
        )

    def test_python_normalizer_rejects_remote_sources(self):
        for value in ("https://example.invalid/video.mp4", "rtsp://example.invalid/video", "reiflix://native?action=play"):
            self.assertIsNone(AndroidBridge.normalize_local_media_reference(value))

    def test_python_normalizer_accepts_absolute_local_path(self):
        value = AndroidBridge.normalize_local_media_reference("/tmp/reiflix-episode.mp4")
        self.assertTrue(value.startswith("file:///"))
        self.assertTrue(value.endswith("/tmp/reiflix-episode.mp4"))

    def test_main_player_handoff_is_request_id_idempotent(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("activePlayerRequestId", source)
        self.assertIn("PLAY_HANDOFF_ACCEPTED", source)
        self.assertIn("PLAY_HANDOFF_DUPLICATE", source)
        self.assertNotIn("playerLaunchActive", source)
        self.assertIn("previousActiveRequestId", source)
        self.assertIn("reusingPlayerActivity", source)
        self.assertIn("FLAG_ACTIVITY_REORDER_TO_FRONT", source)
        self.assertIn("registerForActivityResult(ActivityResultContracts.StartActivityForResult())", source)
        self.assertIn("playerActivityLauncher.launch(intent)", source)
        self.assertIn("PLAY_HANDOFF_DISPATCHED", source)

    def test_native_command_receipt_diagnostics_are_distinct_from_launcher_acceptance(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        bridge = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        for token in (
            "COMMAND_RECEIVED",
            "parameterNames",
            "publishNativeDiagnostic(\"COMMAND_RECEIVED\"",
            "COMMAND_DISPATCHED",
        ):
            self.assertIn(token, source)
        for token in (
            "COMMAND_CREATED",
            "COMMAND_LAUNCH_REQUESTED",
            "COMMAND_LAUNCH_ACCEPTED",
            "COMMAND_DELIVERY_TIMEOUT",
            "observe_native_event",
            "EXTERNAL_NON_BROWSER_APPLICATION",
        ):
            self.assertIn(token, bridge)
        self.assertNotIn("await self.page.launch_url(url)", bridge)

    def test_native_player_has_structured_lifecycle_and_playback_diagnostics(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        required = (
            "URI_RECEIVED",
            "URI_NORMALIZED",
            "PREFLIGHT_ASYNC_START",
            "PREFLIGHT_ASYNC_OK",
            "PREFLIGHT_ASYNC_FAILED",
            "PREFLIGHT_DEFERRED",
            "EXOPLAYER_CREATE",
            "MEDIA_ITEM",
            "PREPARE",
            "PLAYBACK_STATE=",
            "PlaybackException",
            "player_error",
            "player_exit_queued",
            "player_exited",
            "onCreate",
            "onStart",
            "onResume",
            "onPause",
            "onStop",
            "onWindowFocusChanged",
            "onConfigurationChanged",
        )
        for token in required:
            self.assertIn(token, source)
        self.assertIn("WindowInsetsCompat.Type.systemBars()", source)
        self.assertIn("WindowInsetsCompat.Type.displayCutout()", source)
        self.assertIn("systemUiController.applyImmersive()", source)
        self.assertIn("playerView.player = player", source)
        self.assertIn("playerView.player === player", source)
        self.assertIn("PLAYER_VIEW_ATTACHED", source)
        self.assertIn("FIRST_FRAME_RENDERED", source)
        self.assertIn("ViewCompat.getRootWindowInsets(window.decorView)", source)
        self.assertNotIn("Gravity.CENTER + fixed", source)
        self.assertNotIn("sleep(", source)

    def test_runtime_dependency_versions_are_currently_supported_baselines(self):
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("androidx.activity:activity-ktx:1.13.0", gradle)
        self.assertIn("androidx.media3:media3-exoplayer:1.11.1", gradle)
        self.assertIn("androidx.media3:media3-ui:1.11.1", gradle)

    def test_native_player_uses_transformable_media3_texture_surface(self):
        layout = PLAYER_LAYOUT.read_text(encoding="utf-8")
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn('app:surface_type="texture_view"', layout)
        self.assertIn('R.layout.native_player_view', source)
        self.assertIn('video.setTransform(zoomMatrix)', source)

    def test_native_player_supports_all_local_source_families(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn('sourceFor(uri)', source)
        self.assertIn('MediaStore.AUTHORITY -> "mediastore"', source)
        self.assertIn('-> "broad_storage"', source)
        self.assertIn("SafScanner.isAuthorizedDocument", source)
        self.assertIn("MediaStoreScanner.isAuthorizedDocument", source)
        self.assertIn("BroadStorageScanner.isAuthorizedFile", source)
        self.assertIn("contentResolver.openFileDescriptor", source)

    def test_native_player_gesture_contract_is_touch_arbitrated_and_non_stretching(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        for token in (
            "ScaleGestureDetector",
            "GestureDetector",
            "PlayerGesturePolicy",
            "RESIZE_MODE_ZOOM",
            "RESIZE_MODE_FIT",
            "zoomEnabled",
            "MIN_SCALE_FACTOR",
            "MAX_SCALE_FACTOR",
            "showFeedback",
            "setControlsVisible",
            "CONTROL_TIMEOUT_MS",
            "BACK_BUTTON_TOUCH",
            "ANDROID_BACK",
            "PLAYER_SINGLE_TAP",
            "PLAYER_DOUBLE_TAP",
            "PLAYER_LONG_PRESS",
            "GESTURE_START",
            "GESTURE_END",
            "GESTURE_HORIZONTAL_IGNORED",
            "VERTICAL",
            "adjustBrightness",
            "adjustVolumeByFraction",
            "AudioManager.STREAM_MUSIC",
            "controls.bringToFront()",
            'tag = "reiflix_back_button"',
            'tag = "reiflix_lock_button"',
            'tag = "reiflix_seekbar"',
            'tag = "reiflix_gesture_volume"',
            'tag = "reiflix_gesture_brightness"',
            'tag = "reiflix_gesture_double_tap"',
            'tag = "reiflix_gesture_long_press"',
            'arrayOf("Ajustar", "Preencher")',
        ):
            self.assertIn(token, source)
        self.assertNotIn("calculateCloudStreamSeekTarget", source)
        self.assertNotIn("horizontalSeekDelta", source)
        self.assertNotIn("GestureMode.HORIZONTAL_SEEK", source)
        self.assertIn("GESTURE_HORIZONTAL_IGNORED", source)
        self.assertIn("reiflix_skip_opening", source)
        self.assertIn("reiflix_skip_ending", source)
        self.assertIn("showLocalMetadataEditor", source)
        self.assertIn("PlayerLocalMetadataStore", source)

    def test_generation_back_immersive_and_error_contracts(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        required = (
            "playerGeneration",
            "beginPlayerGeneration",
            "createPlayerListener(generation",
            "generation == playerGeneration",
            "SessionState",
            "ACTIVE",
            "EXITING",
            "DESTROYED",
            "errorPublishedForGeneration",
            "player_exited",
            "restoreSystemUiBeforeExit",
            "systemUiController",
            "systemUiController.applyImmersive()",
            "getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())",
            "mandatorySystemGestures()",
            "setAudioAttributes",
            "FEATURE_PICTURE_IN_PICTURE",
            "setAutoEnterEnabled(",
            "player.playWhenReady && player.isPlaying",
        )
        for token in required:
            self.assertIn(token, source)
        self.assertIn('android:enableOnBackInvokedCallback="true"', (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"))
        self.assertIn('android:launchMode="singleTop"', (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"))

    def test_native_player_reuses_one_activity_for_episode_changes(self):
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        main = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("override fun onNewIntent(newIntent: Intent)", player)
        self.assertIn("keepActivity=true", player)
        self.assertIn("episodeChangePending", player)
        self.assertIn('android:launchMode="singleTop"', manifest)
        self.assertIn("FLAG_ACTIVITY_REORDER_TO_FRONT", main)
        handoff_start = main.index("    private fun openPlayer(data: Uri?, commandReceivedAtMs: Long = 0L)")
        handoff_end = main.index("    private fun clearPendingPlay()", handoff_start)
        handoff = main[handoff_start:handoff_end]
        self.assertNotIn("validatePlayerSource", handoff)
        self.assertNotIn("openFileDescriptor(uri, \"r\")", handoff)

    def test_native_player_exit_contract_contains_playback_context(self):
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        for token in ("mediaId", "episodeId", "positionMs", "durationMs", "completion", "timestamp"):
            self.assertIn(token, player)

    def test_native_player_error_recovery_is_bounded_and_structured(self):
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        for token in (
            'tag = "reiflix_error_retry"',
            "retryCurrentMedia",
            "MAX_RETRY_ATTEMPTS = 2",
            "PLAYER_RETRY",
            "PLAYER_PLAY",
            "PLAYER_PAUSE",
            "PLAYER_SEEK",
            "PLAYER_TRACK_CHANGE",
            "PLAYER_PIP",
        ):
            self.assertIn(token, player)

    def test_main_and_player_reapply_application_system_ui_after_lifecycle_boundaries(self):
        main = MAIN_ACTIVITY.read_text(encoding="utf-8")
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        for token in (
            "override fun onCreate(savedInstanceState: Bundle?)",
            "override fun onResume()",
            "override fun onWindowFocusChanged(hasFocus: Boolean)",
            "override fun onConfigurationChanged(newConfig:",
        ):
            self.assertIn(token, main)
        self.assertIn("applyApplicationSystemUi()", main)
        self.assertIn("systemUiController.applyApplicationImmersivePolicy(useContextAppearance = false)", main)
        exit_start = player.index("private fun restoreSystemUiBeforeExit")
        exit_end = player.index("private fun applyImmersiveAfterLayout", exit_start)
        self.assertIn("systemUiController.applyNormal(useContextAppearance = false)", player[exit_start:exit_end])
        self.assertIn("applyImmersiveAfterLayout()", player)
        self.assertIn("ViewCompat.requestApplyInsets", player)
    def test_native_player_primary_surface_does_not_expose_secondary_controls(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        controls = source[source.index("private fun installControls()"):source.index("private fun installBackHandler()")]
        top = controls[:controls.index("centerControls =")]
        bottom = controls[controls.index("bottomBar ="):]
        self.assertIn('actionButton("Voltar", 44)', top)
        self.assertIn('actionButton("Opções", 48)', top)
        self.assertNotIn('actionButton("‹", 44)', source)
        self.assertNotIn('actionButton("⋮", 48)', source)
        compose_controls = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/player/ReiAnixNativePlayerControls.kt"
        ).read_text(encoding="utf-8")
        for token in (
            "Icons.Filled.ArrowBack",
            "Icons.Filled.AspectRatio",
            "Icons.Filled.MoreVert",
            "Icons.Filled.Replay",
            'onBack = { finishPlayer("back_button") }',
        ):
            self.assertIn(token, compose_controls + source)
        for label in ('"Áudio"', '"Legenda"', '"Velocidade"', '"Ajuste"', '"PIP"'):
            self.assertNotIn("actionButton("+label, top)
        self.assertNotIn('"Visto"', bottom)
        self.assertNotIn('"Timer 15m"', bottom)
        self.assertNotIn('"audio_bottom"', bottom)

    def test_scroll_architecture_has_one_vertical_owner_per_main_screen(self):
        views = {
            "home_view.py": "views/home_view.py",
            "details_view.py": "views/details_view.py",
            "organize_view.py": "views/organize_view.py",
            "settings_view.py": "views/settings_view.py",
        }
        for label, relative in views.items():
            source = (ROOT / relative).read_text(encoding="utf-8")
            self.assertIn("scroll=ft.ScrollMode.AUTO", source, label)
            tree = ast.parse(source)
            self.assertTrue(tree.body, label)
            vertical_columns = [
                node for node in ast.walk(tree)
                if isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "Column"
                and any(
                    kw.arg == "scroll"
                    and isinstance(kw.value, ast.Attribute)
                    and kw.value.attr == "AUTO"
                    for kw in node.keywords
                )
            ]
            self.assertEqual(
                1,
                len(vertical_columns),
                f"{label} must have exactly one primary vertical scroll owner",
            )
        home = (ROOT / "views/home_view.py").read_text(encoding="utf-8")
        organize = (ROOT / "views/organize_view.py").read_text(encoding="utf-8")
        self.assertIn('grid = ft.Row(', home)
        self.assertIn('content = ft.Column(expand=True, scroll=ft.ScrollMode.AUTO', organize)
        self.assertIn("content=layout,", home)
        self.assertIn("content=content,", organize)
        self.assertIn("content=layout,", (ROOT / "views/details_view.py").read_text(encoding="utf-8"))
        self.assertIn("content=content,", (ROOT / "views/settings_view.py").read_text(encoding="utf-8"))
        for source in (home, organize, (ROOT / "views/details_view.py").read_text(encoding="utf-8"), (ROOT / "views/settings_view.py").read_text(encoding="utf-8")):
            self.assertIn("expand=True", source)

    def test_python_handles_native_player_state_and_navigation_events(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        for token in (
            "event_type in {'player_progress', 'player_paused', 'player_completed'}",
            "store.save_progress",
            "event_type == 'player_mark_watched'",
            "store.set_watched",
            "event_type == 'player_autoplay_changed'",
            'settings.set("player.autoplay_next", enabled)',
            "event_type in {'player_next_request', 'player_previous_request'}",
            "asyncio.to_thread(\n                                        library.player_navigation,",
            "await start_native_player(",
            'origin_request_id=event_request_id',
            'player_transition_generation["value"]',

            "event_type == 'player_exited'",
            "event_created_at=event.get('createdAt') or event.get('timestamp')",
            "exit_updated = await asyncio.to_thread(",
            "store.save_progress",
            "on_catalog_changed()",
        ):
            self.assertIn(token, source)

    def test_scan_ui_only_calls_running_a_snapshot_not_final(self):
        settings = (ROOT / "views/settings_view.py").read_text(encoding="utf-8")
        self.assertIn("Varredura em andamento:", settings)
        self.assertIn('runtime_status in {"CHECKING", "SCANNING", "WAITING_FOR_MEDIASTORE"}', settings)
        self.assertIn("Última varredura:", settings)
        self.assertIn("Status:", settings)

    def test_system_ui_has_one_application_immersive_policy_and_an_explicit_external_normal_policy(self):
        main = MAIN_ACTIVITY.read_text(encoding="utf-8")
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        system_ui = SYSTEM_UI.read_text(encoding="utf-8")
        self.assertIn("applyApplicationSystemUi()", main)
        self.assertIn("applyApplicationImmersivePolicy(useContextAppearance = false)", main)
        self.assertIn("fun applyApplicationImmersivePolicy(useContextAppearance: Boolean = true)", system_ui)
        self.assertIn("applyImmersive()", system_ui)
        self.assertIn("fun applyApplicationPolicy(useContextAppearance: Boolean = true)", system_ui)
        self.assertIn("fun applyNormal(useContextAppearance: Boolean = true)", system_ui)
        self.assertIn("WindowCompat.setDecorFitsSystemWindows(window, false)", system_ui)
        immersive = system_ui[system_ui.index("fun applyApplicationImmersivePolicy("):system_ui.index("    /** Backward-compatible normal policy")]
        normal = system_ui[system_ui.index("fun applyNormal("):system_ui.index("private fun applyEdgeToEdgeWindow")]
        self.assertIn("applyImmersive(useContextAppearance)", immersive)
        self.assertIn("hide(WindowInsetsCompat.Type.systemBars())", system_ui)
        self.assertIn("show(WindowInsetsCompat.Type.systemBars())", normal)
        self.assertIn("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE", system_ui)
        self.assertNotIn("systemUiController.applyNormal(useContextAppearance = false)", main)
        self.assertNotIn("systemUiController.applyApplicationPolicy()", main)

    def test_player_immersive_policy_is_global_and_first_frame_timeout_is_diagnostic_only(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("private fun shouldUseImmersive(): Boolean =", source)
        self.assertIn("resolveImmersivePolicy(", source)
        self.assertIn("immersiveSetting", source)
        self.assertIn("setKeepContentOnPlayerReset(true)", source)
        self.assertIn("firstFrameDiagnosticTimeoutMs", source)
        self.assertIn("FIRST_FRAME_WATCH_ARMED", source)
        self.assertIn("FIRST_FRAME_TIMEOUT", source)
        self.assertIn('"type", "player_diagnostic"', source)
        self.assertIn("cancelFirstFrameDiagnostics", source)
        self.assertNotIn("Thread.sleep(", source)
        self.assertNotIn("SystemClock.sleep(", source)
    def test_no_silent_exception_suppression_in_runtime_android_sources(self):
        for path in (MAIN_ACTIVITY, PLAYER_ACTIVITY, SYSTEM_UI):
            source = path.read_text(encoding="utf-8")
            self.assertNotIn("except Exception: pass", source)
            self.assertNotIn("catch (Exception) { pass }", source)

if __name__ == "__main__":
    unittest.main()
