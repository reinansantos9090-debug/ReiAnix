from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_prompt32_native_compose_surface_contract_is_present():
    navigation = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
    shell = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/shell/ReiAnixAppShell.kt")
    theme = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt")
    assert "NavHost(" in navigation
    assert "ReiAnixAppShell" in navigation
    assert "Scaffold(" in shell
    assert "NavigationBar(" in shell
    for route in ("HOME", "LIBRARY", "SEARCH", "SETTINGS", "DETAILS", "PLAYER"):
        assert f"ReiAnixRoutes.{route}" in navigation
    assert "ReiAnixComposeTheme" in theme


def test_prompt32_details_and_library_use_stable_episode_identity():
    models = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/model/LibraryUiModels.kt")
    details = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt")
    assert 'get() = "episode:" + id' in models
    assert "key = { episode -> episode.stableKey }" in details
    assert "details-episode-list" in details
    assert "collectAsStateWithLifecycle" in details


def test_prompt32_canonical_local_playback_contract_is_preserved():
    bridge = read("core/android_bridge.py")
    player = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
    main = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    assert "normalize_local_media_reference" in bridge
    assert "validateLocalSource(localUri)" in player
    assert "Media3" in player
    assert "player_exited" in player
    assert "player_error" in player
    assert "playerRequest.toIntent(this, localUri)" in main
    assert not (ROOT / "views/player_view.py").exists()


def test_prompt32_storage_and_lifecycle_contracts_are_preserved():
    manifest = read("android/app/src/main/AndroidManifest.xml")
    main = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    for permission in (
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
    ):
        assert permission in manifest
    assert "onBackPressedDispatcher.addCallback(" in main
    assert "engine.navigationChannel.popRoute()" in main
    assert 'put("type", "android_back")' not in main
    assert "override fun onSaveInstanceState(outState: Bundle)" in main
    assert "override fun onResume()" in main
    assert "override fun onPause()" in main


def test_prompt32_scanner_and_native_index_contracts_are_present():
    scanner = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt")
    media = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt")
    broad = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt")
    index = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/NativeIndex.kt")
    assert "persisted" in scanner.lower()
    assert "MediaStore" in media
    assert "hasAccess" in broad
    assert "generation" in index.lower()
    assert "stableIdentity(" in index


def test_prompt32_runtime_suite_exists_and_is_not_silently_disabled():
    workflow = read(".github/workflows/android_instrumented.yml")
    script = read("scripts/run_android_instrumented.sh")
    expected_tests = (
        "NativePlayerPlaybackInstrumentedTest.kt",
        "BackAndSettingsReturnInstrumentedTest.kt",
        "DeviceFlowInstrumentedTest.kt",
        "NativeIndexInstrumentedTest.kt",
        "NativeMailboxInstrumentedTest.kt",
    )
    for name in expected_tests:
        assert name in workflow
    assert "connectedDebugAndroidTest" in script
    assert "reactivecircus/android-emulator-runner@v2" in workflow
    assert "api-level: 36" in workflow
    assert "on:\n  workflow_dispatch:" in workflow


def test_prompt32_no_forbidden_remote_media_pipeline_was_added():
    player = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt").lower()
    bridge = read("core/android_bridge.py").lower()
    assert "okhttp" not in player
    assert "downloadmanager" not in player
    assert "http://" not in player
    assert "https://" not in player
    # The local bridge may contain remote URL examples in validation tests/docs,
    # but production playback must reject remote media references before launch.
    start = bridge.index("@staticmethod\n    def normalize_local_media_reference")
    normalized = bridge[start:]
    assert "return none" in normalized
    assert 'scheme not in {"content", "file"}' in normalized


def test_prompt32_episode_reconciliation_path_is_canonical_after_player_exit():
    main = read("main.py")
    start = main.index("elif event_type == 'player_exited':")
    end = main.index("elif event_type == 'google_sign_in_started':", start)
    block = main[start:end]
    for token in (
        "exit_is_current",
        "invalidate_player_session",
        "on_catalog_changed()",
        "exit_activity_instance_id",
    ):
        assert token in block
    assert "store.save_progress" in block


def test_prompt32_no_known_ui_tree_mutation_regression_was_reintroduced():
    details = read("views/details_view.py")
    compose_details = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt")
    assert "episode_column.controls.clear()" not in details
    assert "page.update()" not in details[details.index("def render_episodes"):details.index("episode_column = ft.Column")]
    assert ".controls.clear()" not in compose_details
    assert "items(\n                items = season.episodes" in compose_details


def test_prompt32_no_weakening_or_fake_runtime_pass_contracts():
    runner = read("scripts/release_certification.py")
    tests = read("tests/test_certification_runner.py")
    assert "ALLOWED" in runner
    assert "DEVICE_ONLY" in runner
    for classification in ("PASS", "PARTIAL", "FAIL", "NOT VALIDATED", "NOT APPLICABLE", "BLOCKED"):
        assert classification in runner
    assert "p.returncode==0 else FAIL" in runner
    assert "NOT VALIDATED" in runner
