from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_native_storage_onboarding_state_has_explicit_ui_phases():
    state = load(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/NativeStorageOnboardingState.kt"
    )
    for phase in (
        "PHASE_CHECKING",
        "PHASE_READY",
        "PHASE_NEEDS_FOLDER",
        "PHASE_FOLDER_PICKER_OPEN",
        "PHASE_ERROR",
    ):
        assert phase in state
    assert "val isPromptVisible: Boolean" in state
    assert "!dismissed" in state


def test_compose_onboarding_uses_native_state_not_python_projection():
    host = load(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    assert "activity.nativeStorageOnboardingState.collectAsStateWithLifecycle()" in host
    assert "nativeOnboarding.isPromptVisible" in host
    assert "onCancel = activity::dismissNativeStorageOnboarding" in host
    assert 'activity.requestNativeStorageAction("select_tree")' in host
    assert "libraryState.storage.onboardingState" not in host
    assert "libraryViewModel::dismissStorageOnboarding" not in host


def test_native_onboarding_revalidates_canonical_roots_and_saf_grants():
    main = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    assert "fun dismissNativeStorageOnboarding()" in main
    assert "private fun refreshNativeStorageOnboardingState(" in main
    assert "appDependencies.sqliteLibraryRepository.listFolders()" in main
    assert 'it.kind.equals("saf", ignoreCase = true)' in main
    assert "SafScanner.hasPersistedReadPermission" in main
    assert "SafScanner.inspectTree" in main
    assert "DocumentsContract.isTreeUri" in main
    assert "withContext(Dispatchers.IO)" in main
    assert "storageOnboardingRefreshJob?.cancel()" in main
    assert 'refreshNativeStorageOnboardingState("activity_resume")' in main
    assert 'refreshNativeStorageOnboardingState("saf_release_terminal")' in main
    assert 'refreshNativeStorageOnboardingState("saf_release_failed")' in main
    assert 'refreshNativeStorageOnboardingState("saf_release_invalid_uri")' in main


def test_picker_outcomes_update_native_onboarding_without_faking_grants():
    main = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    assert 'NativeStorageOnboardingState.PHASE_FOLDER_PICKER_OPEN' in main
    assert 'NativeStorageOnboardingState.PHASE_NEEDS_FOLDER' in main
    assert 'refreshNativeStorageOnboardingState("saf_picker_cancelled")' in main
    assert 'NativeStorageOnboardingState.PHASE_ERROR' in main
    assert 'NativeStorageOnboardingState.PHASE_READY' in main
    assert "SafScanner.persistPermission(this, uri, flags)" in main
    assert "upsertSafFolder(" in main
    assert "handleBroadSettingsReturn" in main
    assert "val granted = BroadStorageScanner.hasAccess(this)" in main
    assert "val access = MediaStoreScanner.accessLevel(this)" in main


def test_official_flet_overlay_copies_native_onboarding_model():
    script = load("scripts/prepare_flet_template.py")
    assert 'source / "src/main/kotlin/com/reiflix/reiflix_local/storage/NativeStorageOnboardingState.kt"' in script


def test_permissions_are_separated_by_storage_mechanism():
    manifest = load("android/app/src/main/AndroidManifest.xml")
    media = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt")
    broad = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt")
    saf = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt")
    assert "READ_MEDIA_VIDEO" in manifest
    assert "READ_MEDIA_VISUAL_USER_SELECTED" in manifest
    assert "MANAGE_EXTERNAL_STORAGE" in manifest
    assert "Environment.isExternalStorageManager()" in broad
    assert "requiredPermissions()" in media
    assert "takePersistableUriPermission" in saf
    assert "optional" in manifest.lower()


def test_first_run_opens_the_existing_saf_picker_once_after_bootstrap_ready():
    main = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    bootstrap = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt")
    assert "private var automaticSafPickerAttempted = false" in main
    assert "STATE_AUTO_SAF_PICKER_ATTEMPTED" in main
    assert "private fun maybeLaunchAutomaticSafPicker()" in main
    auto = main[main.index("private fun maybeLaunchAutomaticSafPicker()"):
                main.index("private fun refreshNativeStorageOnboardingState")]
    assert "automaticSafPickerAttempted" in auto
    assert "safPickerPending" in auto
    assert "pendingMediaRequestId != null" in auto
    assert "bootstrap.state.value.recoveryRequired" in auto
    assert "bootstrap.state.value.stage != ReiAnixBootstrapStage.READY" in auto
    assert auto.index("automaticSafPickerAttempted = true") < auto.index('requestNativeStorageAction("select_tree")')
    picker = main[main.index("private fun openTreePicker"):main.index("override fun onWindowFocusChanged")]
    assert "Intent.ACTION_OPEN_DOCUMENT_TREE" in picker
    assert picker.index("PHASE_FOLDER_PICKER_OPEN") < picker.index("treePicker.launch(pickerIntent)")
    assert "validateCanonicalDatabase()" in main
    assert "NativeIndex.pendingSafRecovery(appContext).length() > 0" in bootstrap


def test_missing_or_dismissed_root_remains_actionable_and_cancel_does_not_loop():
    main = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    state = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/NativeStorageOnboardingState.kt")
    dismiss = main[main.index("fun dismissNativeStorageOnboarding()"):
                   main.index("/** Launches the existing SAF picker once")]
    refresh = main[main.index("private fun refreshNativeStorageOnboardingState"):
                   main.index("private fun", main.index("private fun refreshNativeStorageOnboardingState") + 20)]
    cancel = main[main.index("private fun handleTreePickerResult"):
                  main.index("private fun", main.index("private fun handleTreePickerResult") + 20)]
    assert "current.phase != NativeStorageOnboardingState.PHASE_READY" in dismiss
    assert "putBoolean(NATIVE_STORAGE_ONBOARDING_DISMISSED, false)" in dismiss
    assert "dismissed = false" in refresh
    assert "automaticSafPickerAttempted" in cancel or "maybeLaunchAutomaticSafPicker()" in refresh
    assert 'refreshNativeStorageOnboardingState("saf_picker_cancelled")' in cancel
    assert "PHASE_NEEDS_FOLDER" in state
    assert 'result = "no_persisted_saf_tree"' in main
    assert 'reason = "first_activity_resume"' in main
    assert 'result = "explicit_scan_required"' not in main


def test_saf_source_is_registered_before_scan_and_media_permissions_do_not_replace_tree():
    main = load("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    handler = main[main.index("private fun handleTreePickerResult"):main.index("private fun", main.index("private fun handleTreePickerResult") + 20)]
    assert "SafScanner.persistPermission(this, uri, flags)" in handler
    assert "upsertSafFolder(" in handler
    assert handler.index("SafScanner.persistPermission(this, uri, flags)") < handler.index("upsertSafFolder(")
    assert handler.index("upsertSafFolder(") < handler.index("scanTree(configuredReference, requestId)")
    assert "if (shouldDiscover && hasPersistedSafTree)" in main
    assert "no_persisted_saf_tree" in main
