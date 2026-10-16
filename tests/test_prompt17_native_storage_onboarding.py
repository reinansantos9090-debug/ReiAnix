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
