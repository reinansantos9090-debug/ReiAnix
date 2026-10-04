from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GOOGLE_IDENTITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/GoogleIdentity.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MAILBOX = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt"
REQUEST_STATE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt"
BRIDGE = ROOT / "core/android_bridge.py"
MAIN = ROOT / "main.py"
SETTINGS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
SETTINGS_REPO = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
SETTINGS_VM = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt"
FLET_SETTINGS = ROOT / "views/settings_view.py"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_google_uses_real_credential_manager_sign_out_without_storing_tokens():
    source = read(GOOGLE_IDENTITY)
    assert "ClearCredentialStateRequest" in source
    assert "TYPE_CLEAR_CREDENTIAL_STATE" in source
    assert "clearCredentialState(clearRequest)" in source
    assert '"google_signed_out"' in source
    assert '"credential_state_clear_failed"' in source
    assert '.put("idToken"' not in source
    assert '.put("token"' not in source


def test_native_sign_out_is_a_real_operation_and_is_lifecycle_cancellable():
    source = read(MAIN_ACTIVITY)
    assert '"google_sign_out" -> {' in source
    assert "signOutWithGoogle(requestId)" in source
    assert "googleSignOutJob?.cancel()" in source
    assert "googleSignOutJob = null" in source
    assert '"GOOGLE_SIGN_OUT_COMPLETED"' in source
    assert '"GOOGLE_SIGN_OUT_FAILED"' in source


def test_bridge_waits_for_real_sign_out_completion():
    bridge = read(BRIDGE)
    assert "GOOGLE_SIGN_OUT_COMPLETED" in bridge
    assert "GOOGLE_SIGN_OUT_FAILED" in bridge
    assert 'async def sign_out(self): return await self._launch("google_sign_out")' in bridge


def test_native_mailbox_and_request_state_cover_sign_out_contract():
    mailbox = read(MAILBOX)
    request_state = read(REQUEST_STATE)
    assert '"google_signed_out" -> "COMPLETED"' in mailbox
    assert '"google_sign_out"' in request_state


def test_compose_account_ui_uses_existing_source_of_truth_and_real_actions():
    settings = read(SETTINGS)
    repo = read(SETTINGS_REPO)
    viewmodel = read(SETTINGS_VM)
    assert 'onAccountAction = viewModel::requestAccountAction' in settings
    assert 'onAction("login")' in settings
    assert 'onAction("switch")' in settings
    assert 'onAction("logout")' in settings
    assert "A biblioteca local, o scanner e o player continuam disponíveis sem login e sem conectividade." in settings
    assert '"compose_account_action"' in repo
    assert "requestAccountAction" in viewmodel


def test_python_logout_clears_local_account_only_after_native_sign_out():
    source = read(MAIN)
    start = source.index("    async def logout(_=None):")
    end = source.index("    async def switch_account", start)
    block = source[start:end]
    assert "await bridge.sign_out()" in block
    assert block.index("await bridge.sign_out()") < block.index("store.clear_account()")
    assert 'set_account_state("error", "logout_failed")' in block


def test_python_compose_account_actions_are_lifecycle_scoped_and_flet_logout_is_awaited():
    main = read(MAIN)
    flet = read(FLET_SETTINGS)
    assert "account_action_task = [None]" in main
    assert "account_task.cancel()" in main
    assert "page.run_task(execute_account_action, action)" in main
    assert 'ft.OutlinedButton("Sair", on_click=lambda _: start_task(on_logout))' in flet


def test_existing_project_has_no_real_progress_sync_backend_to_integrate():
    # Prompt 21 says to integrate an existing synchronization layer, not invent one.
    # This test documents the audit result; local SQLite progress remains authoritative.
    main = read(MAIN).lower()
    assert "firestore" not in main
    assert "supabase" not in main
    assert "progress_sync_service" not in main
