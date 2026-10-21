import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source


ROOT = Path(__file__).resolve().parents[1]
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
PLAYER_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
BRIDGE = ROOT / "core/android_bridge.py"
SCAN = ROOT / "core/scan_coordinator.py"
STORE = ROOT / "core/library_store.py"
SERVICE = ROOT / "core/library_service.py"
HOME = ROOT / "views/home_view.py"
ORGANIZE = ROOT / "views/organize_view.py"


class LifecycleContractTests(unittest.TestCase):
    def test_main_activity_restores_recreation_state_and_limits_scan_cancel_to_true_finish(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        for token in (
            "override fun onSaveInstanceState(outState: Bundle)",
            "STATE_PENDING_PLAY_URI",
            "STATE_PENDING_PLAY_POSITION_MS",
            "STATE_ACTIVE_PLAYER_REQUEST_ID",
            "STATE_SEEN_NATIVE_REQUEST_IDS",
            "override fun onNewIntent(intent: Intent)",
            "override fun onConfigurationChanged",
            "if (isFinishing) NativeScanController.cancelAll()",
        ):
            self.assertIn(token, source)
        self.assertNotIn("if (isFinishing || isChangingConfigurations) NativeScanController.cancelAll()", source)

    def test_long_running_native_scan_batch_helper_is_not_bound_to_activity_instance(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        publisher = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanPublisher.kt").read_text(encoding="utf-8")
        self.assertNotIn("private fun publishNativeScanBatch", source)
        self.assertIn("NativeScanPublisher.publish(", source)
        self.assertIn("appContext: Context", publisher)
        self.assertNotIn("this@", publisher)
        self.assertIn("TAG", publisher)
        self.assertIn("NativeMailbox.writeOrThrow", publisher)

    def test_saf_inventory_is_process_guarded_and_does_not_retain_activity(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        start = source.index("private fun publishSafInventory()")
        end = source.index("private fun handleBroadSettingsReturn", start)
        block = source[start:end]
        self.assertIn("safInventoryInFlight.compareAndSet(false, true)", block)
        self.assertIn("val appContext = applicationContext", block)
        self.assertIn("val lifecycleSnapshot = if (activityResumed)", block)
        self.assertNotIn("this@MainActivity", block)
        self.assertIn("safInventoryInFlight.set(false)", block)
        self.assertIn("private val safInventoryInFlight = AtomicBoolean(false)", source)

    def test_main_activity_unregisters_lifecycle_listeners_and_debounced_callbacks(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("unregisterStorageReceiver()", source)
        self.assertIn("MediaStoreScanner.stopChangeObserver(this)", source)
        self.assertIn("storageReceiverRegistered = false", source)
        self.assertNotIn("private val mediaStoreRetryHandler", source)
        self.assertNotIn("private val mediaStoreRetryScheduled = AtomicBoolean(false)", source)
        scheduler = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreRetryScheduler.kt").read_text(encoding="utf-8")
        self.assertNotIn("private fun scheduleMediaStoreScanRequest", source)
        self.assertIn("MediaStoreRetryScheduler.schedule(", source)
        self.assertNotIn("this@MainActivity", scheduler)
        self.assertNotIn("activityResumed", scheduler)

    def test_native_lifecycle_unregisters_observers_and_stops_activity_work(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        player = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("unregisterStorageReceiver()", source)
        self.assertIn("MediaStoreScanner.stopChangeObserver(this)", source)
        self.assertIn("pendingPreparation?.cancel(true)", player)
        self.assertIn("playbackWorker.shutdown()", player)
        self.assertIn("progressWorker.shutdown()", player)
    def test_native_activity_revalidates_authorized_storage_on_resume(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("override fun onResume()", source)
        self.assertIn("publishSafInventory()", source)
        self.assertIn("startupDiscoveryTriggered", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("first_activity_resume", source)
    def test_native_lifecycle_state_is_owned_by_component_activity(self):
        source = native_main_source()
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("ComponentActivity", source)
        self.assertIn("lifecycleScope", source)
        self.assertIn("override fun onResume()", activity)
        self.assertIn("override fun onPause()", activity)
        self.assertIn("override fun onDestroy()", activity)
    def test_native_scan_controller_rejects_duplicate_active_requests(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanController.kt").read_text(encoding="utf-8")
        self.assertIn("object NativeScanController", source)
        self.assertIn("activeScanId", source)
        self.assertIn("fun begin(", source)
        self.assertIn("fun snapshot(scanId: String)", source)
        self.assertIn("NativeScanController.begin(", native_main_source())
    def test_native_player_cancels_generation_bound_work_on_destroy(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("pendingPreparation?.cancel(true)", source)
        self.assertIn("handler.removeCallbacks(progressReporter)", source)
        self.assertIn("progressWorker.shutdown()", source)
        self.assertIn("playbackWorker.shutdown()", source)
        self.assertIn("cancelFirstFrameDiagnostics", source)

    def test_native_navigation_host_keeps_route_state_inside_compose(self):
        host = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt").read_text(encoding="utf-8")
        nav = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt").read_text(encoding="utf-8")
        self.assertIn("NavHostController", host)
        self.assertIn("rememberNavController()", nav)
        self.assertIn("ReiAnixRoutes.HOME", nav)
        self.assertIn("controller.navigateToTopLevel(", host)
    def test_native_startup_returns_to_home_instead_of_restoring_transient_route(self):
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        host = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt").read_text(encoding="utf-8")
        self.assertIn("composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)", activity)
        self.assertIn("navigateToTopLevel(ReiAnixRoutes.HOME)", host)
        self.assertNotIn("restore_details_context", native_main_source())
    def test_player_recreation_releases_resources_and_preserves_restorable_state(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        for token in (
            "override fun onSaveInstanceState(outState: Bundle)",
            'outState.putLong("position_ms"',
            'outState.putFloat("playback_speed"',
            'outState.putBoolean("play_when_ready"',
            'outState.putBundle("track_selection_parameters"',
            'outState.putInt("resize_mode"',
            "handler.removeCallbacks(progressReporter)",
            "handler.removeCallbacks(controlsHider)",
            "handler.removeCallbacks(feedbackHider)",
            "pendingPreparation?.cancel(true)",
            "playbackWorker.shutdown()",
            "player.release()",
        ):
            self.assertIn(token, source)
        self.assertIn("&& !isChangingConfigurations", source)

    def test_player_async_and_media_callbacks_are_generation_guarded(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        for token in (
            "playerGeneration",
            "beginPlayerGeneration",
            "generation == playerGeneration && sessionState == SessionState.ACTIVE",
            "isCurrentPreparation(generation, localUri, preparationTransitionGeneration)",
            "pendingPreparation?.cancel(true)",
            "activePlayerListener?.let { player.removeListener(it) }",
            "activeAnalyticsListener?.let { player.removeAnalyticsListener(it) }",
        ):
            self.assertIn(token, source)

    def test_on_create_and_on_new_intent_share_the_same_native_dispatcher_after_bootstrap(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        on_create = source[source.index("override fun onCreate(savedInstanceState: Bundle?)"):
                           source.index("public override fun onNewIntent(intent: Intent)")]
        self.assertIn("handleNativeIntent(launchIntent)", on_create)
        self.assertIn("appDependencies.bootstrap.validateCanonicalDatabase()", on_create)
        self.assertLess(
            on_create.index("appDependencies.bootstrap.validateCanonicalDatabase()"),
            on_create.index("handleNativeIntent(launchIntent)"),
        )
        start = source.index("public override fun onNewIntent(intent: Intent)")
        self.assertIn("handleNativeIntent(intent)", source[start:])
        self.assertIn("pendingNativeIntentsBeforeBootstrap", source[start:])

    def test_native_command_dispatch_has_validation_and_explicit_lifecycle_diagnostics(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        for token in (
            "BRIDGE_PROTOCOL_VERSION = 2",
            "MISSING_OPERATION",
            "UNSUPPORTED_OPERATION",
            "MISSING_REQUEST_ID",
            "UNSUPPORTED_PROTOCOL_VERSION",
            "COMMAND_RECEIVED",
            "COMMAND_DISPATCHED",
            "COMMAND_DUPLICATE",
            "OPERATION_STARTED",
            "native_error",
        ):
            self.assertIn(token, source)
        self.assertNotIn("data=${intent.dataString", source)

    def test_native_mailbox_requires_event_identity_and_tracks_operation_state(self):
        mailbox = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt").read_text(encoding="utf-8")
        request_state = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt").read_text(encoding="utf-8")
        for token in ("eventId", "requestId", "createdAt", "AtomicMoveNotSupportedException"):
            self.assertIn(token, mailbox)
        for token in ("if(id.isEmpty())return false", "OperationState", "isTerminal()", "canAdvanceState("):
            self.assertIn(token, request_state)
    def test_native_mailbox_uses_atomic_delivery_and_correlated_acknowledgement(self):
        bridge = BRIDGE.read_text(encoding="utf-8")
        mailbox = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt").read_text(encoding="utf-8")
        self.assertIn("def requeue_event_ids", bridge)
        self.assertIn("def acknowledge", bridge)
        self.assertIn("AtomicMoveNotSupportedException", mailbox)
        self.assertIn("eventId", mailbox)
        self.assertIn("requestId", mailbox)
    def test_scan_coordinator_serializes_mutations_and_matches_cancelled_operations(self):
        source = SCAN.read_text(encoding="utf-8")
        for token in (
            "self._lock = asyncio.Lock()",
            "self._active_request: ScanRequest | None = None",
            "self._pending",
            "self._cancel_requested",
            "async def cancel(self)",
            "await self.bridge.cancel_scans()",
            "async def handle_native_event",
        ):
            self.assertIn(token, source)

    def test_catalog_and_progress_recovery_do_not_convert_partial_failures_into_deletion(self):
        store = STORE.read_text(encoding="utf-8")
        service = SERVICE.read_text(encoding="utf-8")
        for token in (
            "def mark_source_unavailable",
            "state='scope_unavailable'",
            "def restore_source",
            "def interrupted_scans",
            "def recover_interrupted_scans",
            "episode_observations",
            "scan_runs",
        ):
            self.assertIn(token, store)
        self.assertIn("only a trusted COMPLETE generation reconciles", service)
        self.assertIn("final_status", service)
        progress_start = store.index("def save_progress")
        progress_block = store[progress_start:progress_start + 4200]
        self.assertIn("event_created_at", progress_block)
        self.assertIn("if durable_time <= last_seen", progress_block)
        self.assertIn("durable_time", progress_block)

    def test_native_thumbnail_callbacks_are_media_identity_guarded(self):
        activity = MAIN_ACTIVITY.read_text(encoding="utf-8")
        extractor = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/VideoThumbnailExtractor.kt").read_text(encoding="utf-8")
        self.assertIn("mediaIdentity", activity + extractor)
        self.assertIn("latestKeyByUri[uri.toString()] = key", extractor)
        self.assertIn("latestKeyByUri[uri.toString()] != cacheKey(identity, size, modifiedAt)", extractor)
        self.assertIn("registerGeneratedThumbnail", extractor)
        self.assertIn("cacheKey", extractor)

    def test_python_stale_screen_work_is_generation_guarded(self):
        home = HOME.read_text(encoding="utf-8")
        organize = ORGANIZE.read_text(encoding="utf-8")
        for token in (
            "render_generation = [0]",
            "search_generation = [0]",
            "if token != render_generation[0]:",
        ):
            self.assertIn(token, home)
        for token in (
            "render_generation = [0]",
            "search_generation = [0]",
            "if token != search_generation[0]:",
        ):
            self.assertIn(token, organize)


    def test_main_activity_background_scan_jobs_do_not_use_activity_bound_job_registry(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        self.assertNotIn("activeNativeScanJobs", source)
        self.assertNotIn("private val activeNativeScanJobs", source)
        self.assertNotIn("mutableMapOf<String, Job>()", source)

    def test_media_store_delayed_retry_does_not_capture_activity_instance(self):
        source = MAIN_ACTIVITY.read_text(encoding="utf-8")
        scheduler = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreRetryScheduler.kt").read_text(encoding="utf-8")
        self.assertIn("MediaStoreRetryScheduler.schedule(", source)
        self.assertNotIn("private fun scheduleMediaStoreScanRequest", source)
        self.assertNotIn("this@MainActivity", scheduler)
        self.assertNotIn("activityResumed", scheduler)
        self.assertIn("MainActivity.createNativeStorageActionIntent(", scheduler)
        self.assertIn('action = "scan_media_store"', scheduler)
        self.assertNotIn('put("type", "scan_request")', scheduler)
        self.assertIn("appContext", scheduler)

    def test_player_pip_exit_respects_immersive_policy(self):
        source = PLAYER_ACTIVITY.read_text(encoding="utf-8")
        pip_start = source.index("override fun onPictureInPictureModeChanged")
        pip_end = source.index("override fun onConfigurationChanged", pip_start)
        pip_block = source[pip_start:pip_end]
        self.assertIn("applyImmersiveAfterLayout()", pip_block)
        self.assertNotIn("} else {\n            enterImmersiveMode()", pip_block)

if __name__ == "__main__":
    unittest.main()
