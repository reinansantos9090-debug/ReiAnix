from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_prompt34_removes_only_obsolete_compose_host_surfaces():
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


def test_prompt34_preserves_domain_bridges_and_stable_event_contracts():
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
    assert '"player_exited"' in player
    assert '"player_error"' in player
    assert "COMMAND_PROCESSING_FAILED" in dispatcher
    assert "publishFailure(" in dispatcher


def test_prompt34_keeps_diagnostics_and_refresh_correlation_explicit():
    diagnostics = read("core/diagnostics.py")
    main = read("main.py")
    assert "refresh_id: str | None = None" in diagnostics
    assert "refresh_id: Any = None" in diagnostics
    assert "refresh_id=str(refresh_id).strip()" in diagnostics
    assert "refreshId=" not in main
    assert "diagnostics.record(" in main
