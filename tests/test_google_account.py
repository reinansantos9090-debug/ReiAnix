from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GOOGLE_IDENTITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/GoogleIdentity.kt"
MAIN_ACTIVITY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
MAILBOX = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt"
REQUEST_STATE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt"
BRIDGE = ROOT / "core/android_bridge.py"
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


def test_native_logout_clears_local_account_only_after_credential_sign_out():
    activity = read(MAIN_ACTIVITY)
    start = activity.index("private fun runNativeComposeAccountAction")
    end = activity.index("private fun signOutWithGoogle", start)
    block = activity[start:end]
    assert block.index("GoogleIdentity.signOut(") < block.index("accountRepository.clearProfile()")
    assert "if (!cleared)" in block
    assert "accountStateChanged = true" in block
    assert "GOOGLE_COMPOSE_ACCOUNT_FAILED" in block
def test_compose_account_actions_are_lifecycle_scoped_native_operations():
    activity = read(MAIN_ACTIVITY)
    settings = read(SETTINGS)
    assert "lifecycleScope.launch" in activity
    assert "googleSignInJob?.cancel()" in activity
    assert "googleSignOutJob?.cancel()" in activity
    assert "onAction(\"logout\")" in settings
    assert "runNativeComposeAccountAction(\"logout\", requestId)" in activity
def test_existing_project_has_no_real_progress_sync_backend_to_integrate():
    # Local SQLite progress remains authoritative; a remote sync client is not invented.
    kotlin = read(MAIN_ACTIVITY).lower() + read(GOOGLE_IDENTITY).lower()
    assert "firestore" not in kotlin
    assert "supabase" not in kotlin
    assert "progress_sync_service" not in kotlin
def test_compose_account_actions_use_native_credential_manager_and_canonical_sqlite():
    repo = read(SETTINGS_REPO)
    activity = read(MAIN_ACTIVITY)
    identity = read(GOOGLE_IDENTITY)
    request_state = read(REQUEST_STATE)
    account_repo = read(
        ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteAccountRepository.kt"
    )
    dependencies = read(
        ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppDependencies.kt"
    )

    assert "fun requestAccountAction(action: String)" in repo
    assert "launchNativeAccountAction(normalizedAction)" in repo
    assert "runNativeComposeAccountAction(\"login\", requestId)" in activity
    assert "runNativeComposeAccountAction(\"logout\", requestId)" in activity
    assert "runNativeComposeAccountAction(\"switch\", requestId)" in activity
    assert "publishLegacyMailboxEvents = false" in activity
    assert "accountRepository.clearProfile()" in activity
    assert "accountRepository.saveProfile(" in activity
    assert "class ReiAnixSqliteAccountRepository" in account_repo
    assert "ReiAnixSqliteAccountRepository(sqliteDatabase)" in dependencies
    assert "ClearCredentialStateRequest" in identity
    assert "google_account_switch" in request_state
    assert "GOOGLE_COMPOSE_ACCOUNT_COMPLETED" in activity
    assert "GOOGLE_COMPOSE_ACCOUNT_FAILED" in activity
def test_native_google_web_client_id_is_baked_into_rendered_android_resources():
    template = read(ROOT / "scripts/prepare_flet_template.py")
    config = read(ROOT / "app_config.py")

    assert "GOOGLE_WEB_CLIENT_ID" in config
    assert "__REIFLIX_GOOGLE_WEB_CLIENT_ID__" in template
    assert "ast.literal_eval(node.value)" in template
    assert 'ET.SubElement(native_config, "string", {"name": "reiflix_google_web_client_id"})' in template
    assert 'content.replace("__REIFLIX_GOOGLE_WEB_CLIENT_ID__", repr(google_web_client_id))' in template
    assert "R.string.reiflix_google_web_client_id" in read(MAIN_ACTIVITY)
    assert "google_account_switch" in read(REQUEST_STATE)
