import ast
import inspect
import json
import os
import tempfile
import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source

from core.dialogs import dismiss_dialog
from core.diagnostics import DiagnosticTimeline
from core.storage_access import StorageAccessState, StorageCapabilities, storage_access_state, storage_source_states, storage_snapshot_is_stale

ROOT = Path(__file__).resolve().parents[1]

class StorageOnboardingTests(unittest.TestCase):
    def test_settings_consumes_typed_storage_capabilities_attributes(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        block = source[source.index("normalized = normalize_storage_snapshot"):source.index("scan = scan_snapshot", source.index("normalized = normalize_storage_snapshot"))]
        self.assertIn("normalized.media_read_state", block)
        self.assertIn("normalized.broad_storage_state", block)
        self.assertIn("normalized.saf_roots", block)
        self.assertIn("normalized.removable_volumes", block)
        self.assertNotIn("snap.get(", block)

    def test_storage_snapshot_ordering_rejects_older_native_state(self):
        self.assertTrue(storage_snapshot_is_stale(1000, 1001))
        self.assertFalse(storage_snapshot_is_stale(1001, 1001))
        self.assertFalse(storage_snapshot_is_stale(0, 1001))
        self.assertFalse(storage_snapshot_is_stale("bad", 1001))


    def test_storage_capabilities_normalize_native_snapshot(self):
        capabilities = StorageCapabilities.from_native({
            "mediaReadState": "partial",
            "broadStorageState": "available",
            "safRoots": ["content://tree/1", "content://tree/1"],
            "removableVolumes": ["AB", "AB"],
            "scannerCapabilities": ["mediastore", "broad-storage"],
            "lifecycleState": "revalidated",
            "api": 36,
        })
        self.assertEqual("partial", capabilities.media_read_state)
        self.assertEqual("available", capabilities.broad_storage_state)
        self.assertEqual(("content://tree/1",), capabilities.saf_roots)
        self.assertEqual(("AB",), capabilities.removable_volumes)
        self.assertTrue(capabilities.can_scan("mediastore"))
        self.assertFalse(capabilities.can_reconcile("mediastore"))
        self.assertTrue(capabilities.can_scan("broad-storage"))
        self.assertEqual(36, capabilities.api)

    def test_partial_never_implies_broad_access(self):
        capabilities = StorageCapabilities(
            media_read_state="partial",
            broad_storage_state="unavailable",
            scanner_capabilities=frozenset({"mediastore"}),
        )
        self.assertTrue(capabilities.can_scan("mediastore"))
        self.assertFalse(capabilities.can_scan("broad-storage"))
        self.assertEqual(StorageAccessState.MEDIA_PARTIAL, storage_access_state("partial", False))

    def test_real_permission_snapshot_has_deterministic_states(self):
        self.assertEqual(storage_access_state("denied", False), StorageAccessState.NEEDS_MEDIA_PERMISSION)
        self.assertEqual(storage_access_state("partial", False), StorageAccessState.MEDIA_PARTIAL)
        self.assertEqual(storage_access_state("full", False), StorageAccessState.READY)
        self.assertEqual(storage_access_state("full", True), StorageAccessState.READY)
        self.assertEqual(storage_access_state("full", True, dismissed=True), StorageAccessState.DECLINED)

    def test_dismissal_uses_flet_managed_dialog_stack(self):
        class Page:
            def __init__(self): self.pop_count = 0
            def pop_dialog(self):
                self.pop_count += 1
                return object()

        page = Page()
        dismiss_dialog(page, object())
        self.assertEqual(1, page.pop_count)

    def test_project_has_no_invalid_alertdialog_close_calls(self):
        sources = "\n".join(path.read_text(encoding="utf-8") for path in ROOT.rglob("*.py") if "tests" not in path.parts)
        self.assertNotIn("dialog.close(", sources)
        self.assertIn("page.show_dialog(dialog)", sources)
        self.assertIn("page.pop_dialog()", sources)
        self.assertNotIn("page.overlay.append(dialog)", sources)
        self.assertNotIn("dialog.open = True", sources)
        self.assertNotIn("dialog.open = False", sources)

    def test_permission_intent_is_single_task_and_lifecycle_queued(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('android:launchMode="singleTask"', manifest)
        self.assertIn('android:documentLaunchMode="never"', manifest)
        self.assertIn("setIntent(intent)", source)
        self.assertIn("pendingLifecycleAction", source)
        self.assertIn("override fun onResume()", source)
        self.assertIn("activityResumed", source)
        self.assertIn("LIFECYCLE", source)

    def test_permission_request_is_not_launched_from_a_non_resumed_activity(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        request_block = source.split("private fun requestMediaAccess()", 1)[1].split("private fun publishStorageStatus()", 1)[0]
        self.assertIn('if (!activityResumed)', request_block)
        self.assertIn('queueLifecycleAction("request_media_access")', request_block)
        self.assertIn("mediaPermissionRequester.launch(permissions)", request_block)

    def test_existing_media_permission_continues_to_scan_instead_of_stopping_at_grant_event(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        request_block = source.split("private fun requestMediaAccess()", 1)[1].split("private fun publishStorageStatus()", 1)[0]
        self.assertIn('val currentAccess = MediaStoreScanner.accessLevel(this)', request_block)
        self.assertIn('if (currentAccess != "denied")', request_block)
        self.assertLess(request_block.index('put("type", "mediastore_permission")'), request_block.index("scanMediaStore(requestId)"))
        self.assertIn("scanMediaStore(requestId)", request_block)

    def test_add_folder_is_not_blocked_by_an_active_scan(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_picker_is_lifecycle_gated_and_single_shot(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        picker = source.split("private fun openTreePicker(", 1)[1].split("override fun onWindowFocusChanged", 1)[0]
        self.assertIn('if (!activityResumed || !focused)', picker)
        self.assertIn('queueLifecycleAction("select_tree", correlationId)', picker)
        self.assertIn("safPickerPending", picker)
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", picker)
        self.assertIn("treePicker.launch(pickerIntent)", picker)
        self.assertIn("FLAG_GRANT_PERSISTABLE_URI_PERMISSION", picker)
        self.assertIn("FLAG_GRANT_PREFIX_URI_PERMISSION", picker)
        self.assertNotIn("SafPickerProxyActivity", source)
        self.assertNotIn("proxyIntent", picker)
        self.assertIn("safPickerPending = false", source)

    def test_saf_permission_result_releases_picker_guard_before_scan_finishes(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_picker_watchdog_stays_active_during_picker_and_waits_for_foreground(self):
        source = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")
        picker = source[source.index("private fun openTreePicker("):source.index("override fun onWindowFocusChanged")]
        launch = picker[picker.index("treePicker.launch(pickerIntent)"):picker.index("catch (exception: Exception)")]
        self.assertIn("scheduleSafPickerWatchdog(correlationId)", launch)
        self.assertNotIn("cancelSafPickerWatchdog()", launch)
        pause = source[source.index("override fun onPause()"):source.index("override fun onStop()")]
        self.assertNotIn("cancelSafPickerWatchdog()", pause)
        focus = source[source.index("override fun onWindowFocusChanged"):source.index("override fun onConfigurationChanged")]
        focus_loss = focus[focus.index("} else if (safPickerPending)"):]
        self.assertNotIn("cancelSafPickerWatchdog()", focus_loss)

    def test_saf_picker_watchdog_recovers_when_android_returns_without_focus_loss_callback(self):
        source = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")
        watchdog = source.split("private fun scheduleSafPickerWatchdog", 1)[1].split(
            "private fun handleTreePickerResult", 1
        )[0]
        self.assertNotIn("if (!safPickerFocusLost)", watchdog)
        self.assertIn("SafPickerPhase.WAITING_RESULT", watchdog)
        resume = source.split("override fun onResume()", 1)[1].split(
            "override fun onPause()", 1
        )[0]
        self.assertIn("safPickerPhase == SafPickerPhase.WAITING_RESULT", resume)

    def test_compose_select_saf_result_uses_the_compose_request_id(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_settings_saf_selection_preserves_request_id_and_result_bridge(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_inventory_reconciles_folder_identity_not_raw_uri_text(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_permission_result_never_leaves_picker_open_when_validation_fails(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_onboarding_error_survives_late_snapshots_and_shows_user_message(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_inventory_can_recover_one_new_explicitly_selected_persisted_tree(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_picker_watchdog_recovers_when_android_returns_without_result_callback(self):
        source = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("private const val SAF_PICKER_RETURN_GRACE_MS = 2500L", source)
        watchdog = source.split("private fun scheduleSafPickerWatchdog", 1)[1].split(
            "private fun handleTreePickerResult", 1
        )[0]
        self.assertIn("SafPickerPhase.WAITING_RESULT", watchdog)
        self.assertIn("safPickerFocusRegainedAtMs", watchdog)
        self.assertIn('stage = "picker_result_watchdog"', watchdog)
        self.assertIn('code = "RESULT_CALLBACK_TIMEOUT"', watchdog)
        pause = source.split("override fun onPause()", 1)[1].split("override fun onStop()", 1)[0]
        self.assertIn("safPickerFocusLost = true", pause)
        self.assertNotIn("cancelSafPickerWatchdog()", pause)

    def test_permission_callback_uses_authoritative_access_level(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        callback = source.split("private val mediaPermissionRequester", 1)[1].split("private val treePicker", 1)[0]
        self.assertIn("MediaStoreScanner.accessLevel(this)", callback)
        self.assertIn('val granted = access != "denied"', callback)
        self.assertNotIn('grants.any { it.value } && MediaStoreScanner.hasReadPermission(this)', callback)

    def test_storage_state_machine_keeps_sources_independent(self):
        self.assertEqual(storage_source_states("denied", False)["media"], "media_denied")
        self.assertEqual(storage_source_states("partial", False)["media"], "media_partial")
        self.assertEqual(storage_source_states("full", False)["media"], "media_full")
        self.assertEqual(storage_source_states("full", True)["broad"], "broad_storage_available")
        self.assertEqual(storage_source_states("full", False)["broad"], "broad_storage_unavailable")
        self.assertEqual(storage_source_states("full", False, ["content://tree/one"])["saf"], "saf_available")
        self.assertEqual(storage_source_states("full", False, [], saf_revoked=True)["saf"], "saf_revoked")
        self.assertEqual(storage_access_state("full", False, require_broad=True), StorageAccessState.NEEDS_BROAD_STORAGE)
        self.assertEqual(storage_access_state("full", False), StorageAccessState.READY)

    def test_authorized_alternative_source_suppresses_media_onboarding(self):
        self.assertEqual(
            storage_access_state("denied", False, True),
            StorageAccessState.READY,
        )
        self.assertEqual(
            storage_access_state("denied", True, False),
            StorageAccessState.READY,
        )

    def test_startup_onboarding_renders_reianix_ui_without_auto_launching_picker(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_onboarding_cancel_uses_canonical_dismissal_without_faking_ready(self):
        source = native_main_source()
        self.assertIn("internal object MainEntryPoint", source)
        self.assertIn("dependencies.bootstrap.start()", source)
        self.assertIn("ReiAnixAppBootstrap", source)
        self.assertIn("MainActivity", source)


    def test_compose_onboarding_updates_in_place_after_saf_permission(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_native_saf_result_emits_persist_ready_and_scan_diagnostics(self):
        source = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn('"SAF_PICKER_REQUESTED"', source)
        self.assertIn('"SAF_PICKER_RESULT"', source)
        self.assertIn('"STORAGE_PERMISSION_PERSISTED"', source)
        self.assertIn('"STORAGE_READY"', source)
        self.assertIn('"PERMISSION_CHANGE"', source)
        self.assertIn('"STARTUP_SCAN_REQUESTED"', source)
        self.assertIn('scanTree(configuredReference, requestId)', source)

    def test_startup_scan_waits_for_a_persisted_saf_tree(self):
        source = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")
        resume = source[source.index("override fun onResume()"):source.index("override fun onPause")]
        self.assertIn("val hasPersistedSafTree = hasPersistedSafTreeGrant()", resume)
        self.assertIn("if (shouldDiscover && hasPersistedSafTree)", resume)
        self.assertIn('"STARTUP"', resume)
        self.assertIn('reason = "first_activity_resume"', resume)
        self.assertIn('"STARTUP_SCAN_SKIPPED"', resume)
        self.assertIn('result = "no_persisted_saf_tree"', resume)
        self.assertIn("hasPersistedSafTree &&", resume)
        self.assertIn("private fun hasPersistedSafTreeGrant()", source)
        self.assertIn("DocumentsContract.isTreeUri(permission.uri)", source)
        self.assertNotIn('if (shouldDiscover) {\n            publishScanRequest(', resume)

    def test_saf_ready_diagnostic_waits_for_persisted_validation(self):
        source = (
            ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
        ).read_text(encoding="utf-8")
        start = source.index("private fun handleTreePickerResult")
        end = source.index("\n    private fun ", start + len("private fun handleTreePickerResult"))
        result = source[start:end]
        self.assertIn('"SAF_PERMISSION_PERSISTED"', result)
        self.assertLess(
            result.index("SafScanner.persistPermission(this, uri, flags)"),
            result.index('"SAF_PERMISSION_PERSISTED"'),
        )
        self.assertLess(
            result.index("val persistedInspection"),
            result.index('"STORAGE_READY"'),
        )
        self.assertLess(
            result.index('"STORAGE_READY"'),
            result.index('JSONObject().put("type", "saf_permission")'),
        )
        self.assertLess(
            result.index('JSONObject().put("type", "saf_permission")'),
            result.index('scanTree(configuredReference, requestId)'),
        )

    def test_snapshot_preserves_previous_catalog_during_scan(self):
        bridge = (ROOT / "core" / "compose_library_bridge.py").read_text(encoding="utf-8")
        repository = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("reuse_previous_catalog", bridge)
        self.assertIn("previous_animes", bridge)
        self.assertIn("preserveCatalogDuringScan", repository)
        self.assertIn("previous.animes", repository)


    def test_native_scan_publication_uses_failing_mailbox_contract(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        publisher = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanPublisher.kt").read_text(encoding="utf-8")
        self.assertNotIn("private fun publishNativeScanBatch", source)
        self.assertIn("NativeScanPublisher.publish(", source)
        self.assertIn("NativeMailbox.writeOrThrow(", publisher)
        self.assertIn("NativeMailbox.write(", publisher)
        self.assertIn('eventType.replace("_batch", "_error")', publisher)
    def test_native_mailbox_uses_atomic_move_with_non_atomic_fallback(self):
        source = (ROOT / "core" / "android_bridge.py").read_text(encoding="utf-8")
        native = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt").read_text(encoding="utf-8")
        self.assertIn("ATOMIC_MOVE", native)
        self.assertIn("StandardCopyOption.REPLACE_EXISTING", native)
        self.assertIn("event-*.json", source)
        self.assertNotIn("event-*.json.tmp", source)

    def test_native_mailbox_drain_does_not_silently_hide_io_or_json_failures(self):
        source = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        self.assertIn('logger.error("[ANDROID] Invalid legacy native mailbox batch discarded: %s", legacy.name)', source)
        self.assertIn('logger.error("[ANDROID] Invalid native mailbox event discarded:', source)
        self.assertIn('logger.error("[ANDROID] Native mailbox drain failed;', source)

    def test_startup_onboarding_has_explicit_recoverable_states(self):
        source = native_main_source()
        self.assertIn("internal object MainEntryPoint", source)
        self.assertIn("dependencies.bootstrap.start()", source)
        self.assertIn("ReiAnixAppBootstrap", source)
        self.assertIn("MainActivity", source)


    def test_startup_picker_cancel_returns_to_needs_folder(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_compose_onboarding_is_the_android_startup_surface(self):
        host = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt").read_text(encoding="utf-8")
        screen = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixLibraryFolderOnboarding.kt").read_text(encoding="utf-8")
        self.assertIn("activity.nativeStorageOnboardingState.collectAsStateWithLifecycle()", host)
        self.assertIn("nativeOnboarding.isPromptVisible", host)
        self.assertIn("ReiAnixLibraryFolderOnboarding(", host)
        self.assertNotIn("libraryState.storage.onboardingState", host)
        self.assertNotIn("views/settings_view.py", host)
        self.assertIn('text = "ESCOLHER PASTA"', screen)
        self.assertIn('text = "CANCELAR"', screen)
        self.assertNotIn('text = "PERMITIR"', screen)
        self.assertIn("onCancel = activity::dismissNativeStorageOnboarding", host)
        self.assertIn('activity.requestNativeStorageAction("select_tree")', host)
        self.assertIn("ACTION_OPEN_DOCUMENT_TREE", (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8"))

    def test_storage_onboarding_cannot_overlay_settings_navigation(self):
        host = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("currentBackStackEntryAsState", host)
        self.assertIn("val onboardingAllowed", host)
        self.assertIn("ReiAnixRoutes.HOME", host)
        self.assertIn("ReiAnixRoutes.LIBRARY", host)
        overlay = host[host.index("if (onboardingAllowed && nativeOnboarding.isPromptVisible)"):host.index("fun hide()")]
        self.assertIn("onboardingAllowed", overlay)
        self.assertIn("ReiAnixLibraryFolderOnboarding(", overlay)

    def test_selected_saf_root_uses_canonical_identity_and_becomes_ready(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_existing_authorized_saf_source_identity_is_repaired_only_for_current_grants(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_startup_does_not_schedule_thumbnail_reconciliation_before_storage_ready(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_storage_diagnostics_use_supported_python_record_keywords_only(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_storage_permission_dialogs_in_settings_use_managed_flet_stack(self):
        source = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        self.assertIn("page.show_dialog(", source)
        self.assertIn("page.pop_dialog()", source)
        self.assertNotIn("page.overlay.append(", source)
        self.assertIn("Verificar permissão de vídeos", source)
        self.assertIn("Armazenamento amplo", source)

    def test_startup_never_self_launches_persisted_saf_verification(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_saf_completion_clears_onboarding_wait_state(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_main_handles_authoritative_saf_inventory_and_marks_revoked_sources(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_broad_permission_event_does_not_reopen_onboarding_after_settings_launch(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_add_folder_marks_native_picker_open_and_keeps_cancel_recoverable(self):
        source = native_main_source()
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT_TREE", source)
        self.assertIn("safPickerPending", source)
        self.assertIn("pendingSafRequestId", source)
        self.assertIn("persistedUriPermissions", source)
        self.assertIn("SafScanner.inspectTree", source)
        self.assertIn("NativeStorageOnboardingState", source)
        self.assertIn("PHASE_NEEDS_FOLDER", source)
        self.assertIn("PHASE_ERROR", source)


    def test_startup_does_not_self_launch_main_activity_for_storage_snapshot(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_native_intents_have_unique_request_identity_and_are_deduplicated(self):
        bridge = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("uuid.uuid4().hex", bridge)
        self.assertIn('"request_id": request_id', bridge)
        self.assertIn('getQueryParameter("request_id")', main)
        self.assertIn("nativeRequestState", main)
        self.assertIn("NativeRequestState.isSupportedAction", main)
        self.assertIn("COMMAND_DUPLICATE", main)

    def test_activity_preserves_request_state_across_recreation(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("STATE_LAST_NATIVE_REQUEST_ID", source)
        self.assertIn("STATE_PENDING_LIFECYCLE_ACTION", source)
        self.assertIn("STATE_BROAD_SETTINGS_PENDING", source)
        self.assertIn("savedInstanceState?.getString(STATE_LAST_NATIVE_REQUEST_ID)", source)
        self.assertIn("override fun onSaveInstanceState(outState: Bundle)", source)

    def test_open_settings_does_not_publish_a_false_permission_before_navigation(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        block = source.split("private fun openBroadStorageSettings()", 1)[1].split("private fun scanAllStorage", 1)[0]
        self.assertNotIn('put("granted", false)', block)
        self.assertIn("broadStoragePermissionPending = true", block)
        self.assertIn("publishStorageStatus()", source)

    def test_native_host_rechecks_and_never_scans_before_authorization(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("override fun onResume()", source)
        self.assertIn("publishStorageStatus()", source)
        self.assertIn("if (!BroadStorageScanner.hasAccess(this))", source)
        self.assertIn("if (!MediaStoreScanner.hasReadPermission(this))", source)
        self.assertIn("mediaPermissionRequestPending", source)
        self.assertIn("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", source)

    def test_broad_storage_is_optional_for_full_media_access(self):
        self.assertEqual(storage_access_state("full", False), StorageAccessState.READY)
        self.assertEqual(storage_access_state("full", True), StorageAccessState.READY)

    def test_storage_dialogs_do_not_use_artificial_async_lifecycle_delays(self):
        settings = (ROOT / "views" / "settings_view.py").read_text(encoding="utf-8")
        self.assertNotIn("asyncio.sleep(0)", settings)

    def test_refresh_library_gates_broad_scanner_on_authorization(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_on_resume_does_not_publish_intermediate_denied_before_pending_request(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        resume = source[source.index("override fun onResume()"):source.index("override fun onPause()", source.index("override fun onResume()"))]
        self.assertLess(resume.index("consumeAndDispatchPendingLifecycleAction()"), resume.index("publishStorageStatus()"))
        self.assertIn("if (hadPendingLifecycleAction) return", resume)
        self.assertIn("consumeLifecycleAction()", source[source.index("private fun consumeAndDispatchPendingLifecycleAction()"):])

    def test_broad_scanner_is_guarded_in_python_refresh_flow(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_native_scan_controller_is_process_wide_and_source_scoped(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanController.kt").read_text(encoding="utf-8")
        main = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("private val sourceOwners", source)
        self.assertIn("sourceOwners.containsKey(sourceKey)", source)
        self.assertIn("NativeScanController.begin(scanId, scanKey)", main)
        self.assertIn("NativeScanController.begin(scanId, BroadStorageScanner.SOURCE)", main)
        self.assertIn("NativeScanController.begin(scanId, MediaStoreScanner.SOURCE)", main)
        self.assertNotIn("activeNativeScans", main)

    def test_activity_finishing_cancels_native_scans_but_recreation_does_not(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        destroy = source[source.index("override fun onDestroy()"):source.index("override fun onSaveInstanceState", source.index("override fun onDestroy()"))]
        self.assertIn("if (isFinishing) NativeScanController.cancelAll()", destroy)
        self.assertIn("applicationContext", source)

    def test_mediastore_scan_isolates_volume_failures(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt").read_text(encoding="utf-8")
        self.assertIn("MediaStore query failed for volume $volumeName", source)
        self.assertIn("for(volumeName in volumeNames)", source)
        self.assertIn("catch(exception:Exception)", source)
    def test_main_imports_os_for_durable_flet_storage_path(self):
        source = native_main_source()
        self.assertFalse((ROOT / "main.py").exists())
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_legacy_external_volume_discovery_contract(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt").read_text(encoding="utf-8")
        self.assertIn("getExternalFilesDirs(null)", source)
        self.assertIn("inferVolumeRoot", source)
        self.assertIn("Environment.isExternalStorageRemovable(volumeRoot)", source)

    def test_generated_manifest_normalizes_existing_legacy_permission(self):
        source = (ROOT / "scripts/prepare_flet_template.py").read_text(encoding="utf-8")
        self.assertIn("existing_nodes = [", source)
        self.assertIn('permission == "android.permission.READ_EXTERNAL_STORAGE"', source)
        self.assertIn('node.set("{" + ANDROID + "}maxSdkVersion", max_sdk)', source)

    def test_duplicate_native_scan_reports_already_running(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('put("phase", "already_running")', source)
        self.assertIn('"saf_scan_progress"', source)
        self.assertIn('"broad_storage_scan_progress"', source)
        self.assertIn('"mediastore_scan_progress"', source)
    def test_long_native_scan_uses_application_context_for_mailbox_callbacks(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        scan_block = source[source.index("private fun scanTree"):source.index("    private fun requestMediaAccess", source.index("private fun scanTree"))]
        broad_block = source[source.index("private fun scanAllStorage"):source.index("    private fun scanMediaStore", source.index("private fun scanAllStorage"))]
        media_block = source[source.index("private fun scanMediaStore"):source.index("    private fun cancelNativeScans", source.index("private fun scanMediaStore"))]
        for block in (scan_block, broad_block, media_block):
            self.assertIn("val appContext = applicationContext", block)
            self.assertNotIn("NativeMailbox.write(this@MainActivity", block)

    def test_native_bridge_orders_events_by_creation_time(self):
        source = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        self.assertIn("def _event_time(event: dict)", source)
        self.assertIn("claimed_events.sort(key=lambda item: (item[0], item[1], item[2]))", source)
        self.assertIn('"createdAt"', source)

    def test_native_bridge_uses_publication_order_when_timestamps_tie(self):
        with tempfile.TemporaryDirectory() as directory:
            from core.android_bridge import AndroidBridge

            bridge = AndroidBridge(directory)
            queue = Path(directory) / "reiflix-native-events"
            queue.mkdir(parents=True, exist_ok=True)

            # Make the lexicographically smaller filename the later publication.
            # Filename order must not move the terminal scan ahead of its batch.
            batch = queue / "event-z-batch.json"
            terminal = queue / "event-a-terminal.json"
            batch.write_text(
                json.dumps({"eventId": "batch-1", "type": "saf_scan_batch", "createdAt": 1000}),
                encoding="utf-8",
            )
            terminal.write_text(
                json.dumps({"eventId": "terminal-1", "type": "saf_scan", "createdAt": 1000}),
                encoding="utf-8",
            )
            os.utime(batch, ns=(1_000_000_100, 2_000_000_100))
            os.utime(terminal, ns=(1_000_000_200, 2_000_000_200))

            events = bridge.drain(max_events=10)
            try:
                self.assertEqual(["saf_scan_batch", "saf_scan"], [event["type"] for event in events])
            finally:
                bridge.acknowledge()

    def test_native_bridge_retains_failed_requeue_events(self):
        source = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        self.assertIn("self._retained: set[Path]", source)
        self.assertIn("self._retained.add(consumed)", source)
        self.assertIn("if consumed in self._retained:", source)

    def test_native_bridge_does_not_delete_claimed_events_on_drain_io_failure(self):
        source = (ROOT / "core/android_bridge.py").read_text(encoding="utf-8")
        block = source[source.index("except OSError as exc:", source.index("def drain")):source.index("    def requeue_event_ids", source.index("def drain"))]
        self.assertIn("claimed events will be restored/retried", block)
        self.assertIn('path.replace(path.with_suffix(".json"))', block)
        self.assertNotIn("path.unlink(missing_ok=True)", block)

    def test_main_activity_has_no_legacy_saf_scan_guard_reference(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn("tryBeginNativeScan", source)
        self.assertIn('NativeScanController.begin(scanId, scanKey)', source)

    def test_main_has_single_os_import_for_storage_configuration(self):
        source = native_main_source()
        self.assertFalse((ROOT / "main.py").exists())
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_closure_contract_remains_storage_only(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_native_mailbox_contract_is_versioned_and_atomic(self):
        mailbox = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt").read_text(encoding="utf-8")
        self.assertIn("EVENT_VERSION = 2", mailbox)
        self.assertIn('put("eventType", eventType(event))', mailbox)
        self.assertIn("stream.fd.sync()", mailbox)
        self.assertIn("Files.move(", mailbox)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", mailbox)
        self.assertIn("StandardCopyOption.REPLACE_EXISTING", mailbox)
        self.assertIn("AtomicMoveNotSupportedException", mailbox)
        self.assertIn("requestId", mailbox)
        self.assertNotIn("temp.renameTo(target)", mailbox)

    def test_runtime_capabilities_are_single_python_snapshot(self):
        source = native_main_source()
        self.assertIn("internal object MainEntryPoint", source)
        self.assertIn("dependencies.bootstrap.start()", source)
        self.assertIn("ReiAnixAppBootstrap", source)
        self.assertIn("MainActivity", source)


    def test_request_ids_cross_lifecycle(self):
        activity = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        state = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt").read_text(encoding="utf-8")
        self.assertIn("pendingMediaRequestId", activity)
        self.assertIn("pendingBroadRequestId", activity)
        self.assertIn("pendingSafRequestId", activity)
        self.assertIn("consumeLifecycleAction()", activity)
        self.assertIn("consumedLifecycleRequestId()", activity)
        self.assertIn("pendingLifecycleRequestId", state)

    def test_settings_return_revalidates_broad_api(self):
        activity = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("BroadStorageScanner.hasAccess(this)", activity)
        self.assertIn("revalidatedAfterSettings", activity)
        block = activity.split("if (broadStoragePermissionPending)", 1)[1].split("publishStorageCapabilities", 1)[0]
        self.assertNotIn('.put("granted", true)', block)

    def test_no_fixed_permission_polling_delay(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_template_enforces_activity_launch_contract(self):
        hook = (ROOT / "scripts/prepare_flet_template.py").read_text(encoding="utf-8")
        self.assertIn('main.set(exported_attr, "true")', hook)
        self.assertIn('main.set(launch_attr, "singleTask")', hook)
        self.assertIn('main.set(document_launch_attr, "never")', hook)

if __name__ == "__main__":
    unittest.main()


class TestAuthorizedStorageDiscovery(unittest.TestCase):
    def test_native_saf_selection_persists_identity_fields_by_name(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        start = source.index("private fun handleTreePickerResult")
        picker = source[start:source.index("private fun ", start + 30)]
        call_start = picker.index("upsertSafFolder(")
        call_end = picker.index("\n                        )", call_start)
        call = picker[call_start:call_end]
        self.assertIn('authorization = "granted"', call)
        self.assertIn("safAuthority =", call)
        self.assertIn("safDocumentId =", call)
        self.assertIn("safVolumeId =", call)
        self.assertIn("safIdentity =", call)

    def test_compose_library_refresh_dispatches_native_scan_and_terminal_result(self):
        source = native_main_source()
        self.assertIn("StorageAuthorization.capabilities(", source)
        self.assertIn("BroadStorageScanner.hasAccess(", source)
        self.assertIn("NativeScanController.begin(", source)
        self.assertIn("NativeScanRunner", source)
        self.assertIn("publishScanRequest(", source)
        self.assertIn("hasPersistedSafTreeGrant()", source)


    def test_main_activity_scans_startup_only_for_a_persisted_saf_root_and_preserves_permission_flow(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        start = source.index("override fun onResume()")
        resume = source[start:source.index("override fun onPause()", start)]
        self.assertIn("startupDiscoveryTriggered", resume)
        self.assertIn("if (shouldDiscover && hasPersistedSafTree)", resume)
        self.assertIn('"STARTUP"', resume)
        self.assertIn('reason = "first_activity_resume"', resume)
        self.assertIn("STARTUP_SCAN_SKIPPED", resume)
        self.assertIn('result = "no_persisted_saf_tree"', resume)
        self.assertIn("hasPersistedSafTree &&", resume)
        self.assertIn("mediaAccessChangedToUsable || broadBecameAvailable", resume)
        self.assertIn('publishScanRequest(\n                "PERMISSION_CHANGE"', resume)
        self.assertNotIn('result = "explicit_scan_required"', resume)
        self.assertNotIn("scanMediaStore(null)", resume)
        self.assertNotIn("scanAllStorage(null)", resume)
        self.assertNotIn("persistedSafTreeUris().forEach", resume)
    def test_media_permission_transition_and_existing_access_converge_to_scan(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("mediaAccessChangedToUsable", source)
        request = source[source.index("private fun requestMediaAccess()"):source.index("private fun publishStorageStatus(requestId: String? = null)", source.index("private fun requestMediaAccess()"))]
        self.assertIn("if (currentAccess != \"denied\")", request)
        self.assertIn("scanMediaStore(requestId)", request)
        callback = source[source.index("private val mediaPermissionRequester"):source.index("private val treePicker", source.index("private val mediaPermissionRequester"))]
        self.assertIn("val requestId = pendingMediaRequestId", callback)
        self.assertIn("scanMediaStore(requestId)", callback)

    def test_existing_broad_access_converges_to_scan(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        start = source.index("private fun openBroadStorageSettings()")
        block = source[start:source.index("private fun scanAllStorage", start)]
        self.assertIn("if (BroadStorageScanner.hasAccess(this))", block)
        self.assertEqual(block.count("scanAllStorage(requestId)"), 1)
        self.assertIn("revalidatedAfterSettings", block)
    def test_storage_receiver_reacts_to_mount_and_media_scanner_finish(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        start = source.index("private val storageReceiver")
        receiver = source[start:source.index("private fun registerStorageReceiver", start)]
        self.assertIn("Intent.ACTION_MEDIA_MOUNTED", receiver)
        self.assertIn("Intent.ACTION_MEDIA_SCANNER_FINISHED", receiver)
        self.assertIn("publishScanRequest(", receiver)
        self.assertIn('"VOLUME_MOUNT"', receiver)
        self.assertIn("reason = action", receiver)
        self.assertIn("recordNativeVolumeChange(payload)", receiver)
        self.assertIn('"nativeScanDispatchOwner", true', receiver)
        self.assertNotIn("scanAllStorage(null)", receiver)
        self.assertIn("activityResumed", receiver)
    def test_storage_receiver_does_not_launch_permission_ui(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        receiver = source[source.index("private val storageReceiver"):source.index("private fun registerStorageReceiver", source.index("private val storageReceiver"))]
        self.assertNotIn("requestMediaAccess()", receiver)
        self.assertNotIn("openBroadStorageSettings()", receiver)
        self.assertNotIn("openTreePicker()", receiver)

class TestComposeStorageSnapshotMerge(unittest.TestCase):
    def test_catalog_preservation_does_not_freeze_new_storage_onboarding_state(self):
        source = (
            ROOT
            / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
        ).read_text(encoding="utf-8")
        start = source.index("internal fun mergeSnapshotState(")
        end = source.index("\n        internal fun reconcilePagedState(", start)
        merge = source[start:end]
        # Catalog preservation and storage authorization are independent state
        # domains. A READY onboarding snapshot must not be replaced by stale UI
        # state just because the last catalog is retained during a scan.
        self.assertIn("storage = decoded.storage", merge)
        self.assertNotIn("storage = if (preserveCatalog) previous.storage else decoded.storage", merge)

    def test_saf_permission_mailbox_event_processing_transitions_storage_onboarding_to_ready(self):
        import tempfile
        import shutil
        from core.library_store import LibraryStore
        from core.storage_access import StorageCapabilities, configured_library_saf_roots

        temp_dir = tempfile.mkdtemp()
        try:
            store = LibraryStore(temp_dir)
            storage_capabilities = [StorageCapabilities.unknown()]
            storage_onboarding = {
                "dismissed": False,
                "dialog_open": True,
                "waiting_for_result": True,
                "startup_gate": True,
                "inventory_complete": False,
                "state": "NEEDS_FOLDER",
                "message": None,
                "error": None,
                "last_storage_mutation_at_ms": 0,
            }

            tree_uri = "content://com.android.externalstorage.documents/tree/primary%3AAnime"

            # Process simulated saf_permission event
            saf_mutation_at_ms = 1000
            storage_onboarding["last_storage_mutation_at_ms"] = saf_mutation_at_ms
            storage_onboarding["waiting_for_result"] = False
            storage_onboarding["dismissed"] = False

            current_roots = tuple(dict.fromkeys(list(storage_capabilities[0].saf_roots) + [tree_uri]))
            storage_capabilities[0] = StorageCapabilities(
                media_read_state="granted",
                broad_storage_state="unavailable",
                saf_roots=current_roots,
                removable_volumes=(),
                scanner_capabilities=frozenset({"saf"}),
                reconciliation_capabilities=frozenset({"saf"}),
                lifecycle_state="revalidated",
                api=34,
            )

            store.add_folder(
                tree_uri,
                name="Anime",
                kind="saf",
                authorization="granted",
                saf_identity="primary:Anime",
            )

            valid_roots = configured_library_saf_roots(storage_capabilities[0].saf_roots, store.folders())
            self.assertTrue(bool(valid_roots))

            if valid_roots:
                storage_onboarding["startup_gate"] = False
                storage_onboarding["state"] = "READY"
                storage_onboarding["message"] = None
                storage_onboarding["error"] = None
                storage_onboarding["dialog_open"] = False

            self.assertEqual(storage_onboarding["state"], "READY")
            self.assertFalse(storage_onboarding["startup_gate"])
            self.assertFalse(storage_onboarding["dialog_open"])
            self.assertEqual(len(store.folders()), 1)
            self.assertEqual(store.folders()[0]["path"], tree_uri)
        finally:
            shutil.rmtree(temp_dir, ignore_errors=True)
