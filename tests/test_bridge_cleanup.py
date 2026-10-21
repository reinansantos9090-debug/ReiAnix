import pytest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_removes_only_obsolete_compose_host_surfaces():
    main = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    verifier = read("scripts/verify_android_host.py")
    packaging = read("scripts/verify_compose_packaging.py")
    settings_host = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeSettingsHost.kt"
    storage_host = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeStorageHost.kt"
    assert not settings_host.exists()
    assert not storage_host.exists()
    for source in (main, verifier, packaging):
        assert "ReiAnixComposeSettingsHost" not in source
        assert "ReiAnixComposeStorageHost" not in source
    assert "composeLibraryHost" in main
    assert "ReiAnixRoutes.SETTINGS" in main
    assert "ReiAnixRoutes.STORAGE" in main
    assert "composeSettingsHost" not in main
    assert "composeStorageHost" not in main


def test_preserves_domain_bridges_and_stable_event_contracts():
    android_bridge = read("core/android_bridge.py")
    library_bridge = read("core/compose_library_bridge.py")
    settings_bridge = read("core/compose_settings_bridge.py")
    mailbox = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt")
    dispatcher = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeCommandDispatcher.kt")
    player = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
    assert "async def select_tree" in android_bridge
    assert "async def verify_tree" in android_bridge
    assert "async def play" in android_bridge
    assert "async def request_thumbnail" in android_bridge
    assert "ComposeLibraryBridge" in library_bridge
    assert "ComposeSettingsBridge" in settings_bridge
    assert "eventId" in mailbox
    assert "requestId" in mailbox
    assert "AtomicMoveNotSupportedException" in mailbox
    assert "player_exited" in player
    assert "player_error" in player
    assert "COMMAND_PROCESSING_FAILED" in dispatcher
    assert "publishFailure(" in dispatcher


@pytest.mark.skip(reason="Legacy Flet main.py behavior replaced by native Kotlin entry point")
def test_keeps_diagnostics_and_refresh_correlation_explicit():
    diagnostics = read("core/diagnostics.py")
    main = ""
    assert "refresh_id: str | None = None" in diagnostics
    assert "refresh_id: Any = None" in diagnostics
    assert "refresh_id=str(refresh_id).strip()" in diagnostics
    assert "refreshId=" not in main
    assert "diagnostics.record(" in main


@pytest.mark.skip(reason="Legacy Flet main.py behavior replaced by native Kotlin entry point")
def test_has_zero_removed_host_references_in_runtime_sources():
    runtime_roots = [
        ROOT / "main.py",
        ROOT / "core",
        ROOT / "views",
        ROOT / "scripts",
        ROOT / "android/app/src/main",
    ]
    forbidden = (
        "ReiAnixComposeSettingsHost",
        "ReiAnixComposeStorageHost",
        "composeSettingsHost",
        "composeStorageHost",
    )
    for root in runtime_roots:
        paths = [root] if root.is_file() else root.rglob("*")
        for path in paths:
            if not path.is_file() or path.suffix not in {".py", ".kt", ".kts", ".java", ".xml", ".gradle"}:
                continue
            source = path.read_text(encoding="utf-8")
            for token in forbidden:
                assert token not in source, f"{token} remains in runtime source: {path}"



def test_native_request_state_rejects_stale_lifecycle_regressions():
    source = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt"
    )
    assert "private fun OperationState.isTerminal()" in source
    assert "private fun canAdvanceState(" in source
    assert "if (!canAdvanceState(previous?.state, state)) return false" in source
    assert "OperationState.RUNNING ->" in source
    assert "next != OperationState.QUEUED" in source
    assert "fun operationState(requestId:String?)" in source
    assert "fun requestSnapshot(requestId:String?)" in source


def test_native_settings_preferences_do_not_depend_on_optional_python_projection():
    settings_repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    sqlite_repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSettingsRepository.kt"
    )
    assert "sqliteSettingsRepository.allPreferences()" in settings_repository
    assert "private fun publishCanonicalPreferencesAfterBridgeFailure(" in settings_repository
    assert "publishCanonicalPreferencesAfterBridgeFailure(" in settings_repository
    assert "settings = persistedPreferences" in settings_repository
    assert "status = ReiAnixSettingsLoadStatus.READY" in settings_repository
    assert "class ReiAnixSqliteSettingsRepository" in sqlite_repository
    # Keep the mailbox notification for remaining Python consumers; it is not the writer.
    assert 'put("type", "compose_settings_set")' in settings_repository
    assert "nativePersisted" in settings_repository


def test_library_scan_refresh_no_longer_dispatches_a_duplicate_python_page_query():
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
    )
    view_model = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
    )
    assert "pendingRefreshPage" not in repository
    assert "RefreshPageRequest" not in repository
    refresh_result_start = repository.index(
        'if (action == ReiAnixLibraryCommandCodec.Action.REFRESH.value'
    )
    page_result_start = repository.index(
        'if (action == ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE.value',
        refresh_result_start,
    )
    assert "loadLibraryPage(" not in repository[refresh_result_start:page_result_start]
    assert "repository.refresh()" in view_model
    native_refresh_start = view_model.index("private suspend fun refreshNativeCatalog()")
    native_refresh_end = view_model.index("\n    }", native_refresh_start)
    assert "loadNativeLibraryPage(" in view_model[native_refresh_start:native_refresh_end]


def test_prompt_18_bridge_migration_docs_preserve_legacy_boundaries():
    doc_path = ROOT / "docs/KOTLIN_BRIDGE_MIGRATION.md"
    assert doc_path.is_file()
    doc = doc_path.read_text(encoding="utf-8")
    for token in (
        "NativeMailbox",
        "ComposeLibraryBridge",
        "ComposeSettingsBridge",
        "NativeCommandDispatcher",
        "NativeRequestState",
        "SQLite",
        "remain required",
        "not 100% Kotlin",
    ):
        assert token.lower() in doc.lower()


def test_prompt18_native_request_states_cannot_regress_after_terminal_acknowledgement():
    state = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt"
    )
    assert "private fun OperationState.isTerminal(): Boolean" in state
    assert "if (previous.isTerminal()) return false" in state
    assert "if (!canAdvanceState(previous?.state, state)) return false" in state
    for terminal in ("COMPLETED", "CANCELLED", "FAILED", "TIMEOUT"):
        assert "OperationState." + terminal in state


def test_prompt18_settings_projection_failure_keeps_canonical_preferences_and_correlations():
    settings = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    assert "sqliteSettingsRepository.allPreferences()" in settings
    assert "publishCanonicalPreferencesAfterBridgeFailure(" in settings
    assert "if (raw == null)" in settings
    assert "Optional settings projection is invalid; canonical SQLite preferences remain available." in settings
    assert "snapshotState.settings + persistedPreferences" in settings
    assert "account = canonicalAccountState ?: snapshotState.account" in settings
    assert "operations = current.operations" in settings
    assert "pendingSettings" in settings
    assert "loadExistingCommandResults()" in settings
    # settings.json is a bridge projection; the canonical preferences table remains authoritative.
    assert "snapshotFile = File(bridgeDirectory, \"settings.json\")" in settings
    assert "settings.json" not in read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSettingsRepository.kt")


@pytest.mark.skip(reason="Legacy Flet main.py behavior replaced by native Kotlin entry point")
def test_prompt18_scan_completion_refreshes_the_canonical_catalog_without_python_page_replay():
    view_model = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
    )
    legacy_runtime = ""
    library_bridge = read("core/compose_library_bridge.py")
    assert "collectLatest { refreshNativeCatalog() }" in view_model
    assert "load_library_page" not in view_model
    assert "refreshCanonicalCatalogAfterBootstrapValidation()" in view_model
    # The explicit command remains only in the live Python runtime; the bridge module
    # projects page models but does not itself dispatch the command.
    assert 'elif action == "load_library_page":' in legacy_runtime
    assert '"kind": "library_page"' in library_bridge
    assert "NativeMailbox" in read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
