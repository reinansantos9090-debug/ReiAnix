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
    # earlier validation stage 21 says to integrate an existing synchronization layer, not invent one.
    # This test documents the audit result; local SQLite progress remains authoritative.
    main = read(MAIN).lower()
    assert "firestore" not in main
    assert "supabase" not in main
    assert "progress_sync_service" not in main


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
    main = read(MAIN)

    action_start = repo.index("fun requestAccountAction(action: String)")
    action_end = repo.index("private fun launchNativeAccountAction", action_start)
    action_method = repo[action_start:action_end]
    assert "sharedAccountRepository == null" in action_method  # compatibility only
    assert 'put("type", "compose_account_action")' in action_method
    assert "launchNativeAccountAction(normalizedAction)" in action_method

    native_action_start = repo.index("private fun launchNativeAccountAction")
    native_action_end = repo.index("private fun isNativeGoogleConfigured", native_action_start)
    native_action = repo[native_action_start:native_action_end]
    assert "Intent(appContext, MainActivity::class.java)" in native_action
    assert 'appendQueryParameter("native_account_owner", "true")' in native_action
    assert 'appendQueryParameter("settings_action", action)' in native_action
    assert '"google_account_switch"' in native_action

    assert 'data.getQueryParameter("native_account_owner") == "true"' in activity
    assert 'runNativeComposeAccountAction("login", requestId)' in activity
    assert 'runNativeComposeAccountAction("logout", requestId)' in activity
    assert 'runNativeComposeAccountAction("switch", requestId)' in activity
    assert "publishLegacyMailboxEvents = false" in activity
    assert "ReiAnixAppDependencies.from(applicationContext).sqliteAccountRepository" in activity
    assert 'put("type", "compose_settings_native_account_changed")' in activity

    assert "class ReiAnixSqliteAccountRepository" in account_repo
    assert 'db.rawQuery("SELECT key,value FROM account"' in account_repo
    assert 'db.update("account", values, "key=?"' in account_repo
    assert 'db.delete("account", null, null)' in account_repo
    assert "ReiAnixSqliteAccountRepository(sqliteDatabase)" in dependencies
    assert '"google_account_switch"' in request_state

    # The result-only mailbox event refreshes Python projections from SQLite;
    # no account profile or token is written a second time by this callback.
    helper_start = main.index("async def _run_compose_settings_native_account_changed")
    helper_end = main.index("async def _run_compose_settings_native_backup_restored", helper_start)
    helper = main[helper_start:helper_end]
    assert "store.account" in helper
    assert "store.save_account" not in helper
    assert "store.clear_account" not in helper
    assert "python_account_write_repeated" in helper


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
