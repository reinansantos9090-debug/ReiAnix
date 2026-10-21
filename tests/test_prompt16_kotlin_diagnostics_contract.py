from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_native_timeline_is_bounded_correlated_and_process_local():
    timeline = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/diagnostics/ReiAnixDiagnosticTimeline.kt"
    )
    assert "RETENTION_LIMIT = 300" in timeline
    assert "System.currentTimeMillis()" in timeline
    assert "SystemClock.elapsedRealtimeNanos()" in timeline
    assert "requestId" in timeline and "scanId" in timeline and "sessionId" in timeline
    assert "while (recent.size > RETENTION_LIMIT) recent.removeFirst()" in timeline
    assert "MutableStateFlow<List<ReiAnixDiagnosticEvent>>" in timeline
    assert "snapshotJson(limit: Int = 100)" in timeline
    assert "NativeMailbox" not in timeline
    assert "SQLiteDatabase" not in timeline


def test_native_performance_diagnostics_no_longer_delegates_event_recording_to_python():
    performance = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/PerformanceDiagnostics.kt"
    )
    assert "ReiAnixDiagnosticTimeline.record(" in performance
    assert "ANDROID_FRAME_WINDOW" in performance
    assert "ANDROID_MEMORY_SAMPLE" in performance
    assert "PLAYER_PERFORMANCE_MILESTONE" in performance
    assert "fun snapshotJson()" in performance
    assert "NativeMailbox" not in performance
    # Existing performance aggregates remain bounded and preserve their thresholds.
    for token in ("framesOver16Ms", "framesOver32Ms", "framesOver50Ms", "framesOver100Ms"):
        assert token in performance


def test_compose_diagnostic_export_contains_timeline_and_aggregated_performance():
    service = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/ReiAnixDiagnosticsService.kt"
    )
    main = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    assert '"timeline"' in service
    assert '"recent_events"' in service
    assert '"performance"' in service
    assert "ReiAnixDiagnosticTimeline.snapshotJson(100)" in service
    assert "PerformanceDiagnostics.snapshotJson()" in service
    assert '"includes_raw_file_paths_in_events", false' in service
    assert '"includes_remote_telemetry", false' in service
    assert 'action != "diagnostic_export"' in main
    assert "ReiAnixDiagnosticsService(applicationContext).exportJson()" in main


def test_compose_diagnostics_ui_collects_native_timeline_with_empty_and_bounded_states():
    settings = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    assert "ReiAnixDiagnosticTimeline.events.collectAsStateWithLifecycle()" in settings
    assert "Eventos nativos recentes" in settings
    assert "diagnostic:timeline:empty" in settings
    assert "diagnosticEvents.take(20)" in settings
    assert "event.timestampMs" in settings


def test_native_services_publish_diagnostics_without_replacing_domain_events():
    dependencies = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppDependencies.kt"
    )
    player = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
    )
    scan = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanController.kt"
    )
    artwork = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt"
    )
    backup = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/ReiAnixBackupService.kt"
    )
    assert "ANILIST_METADATA_COMMITTED" in dependencies
    assert "NATIVE_PLAYER_PLAYBACK_ERROR" in player
    assert "playerSessionId" in player and "transitionGeneration" in player
    assert "NATIVE_SCAN_STARTED" in scan
    assert "NATIVE_SCAN_FINISHED" in scan
    assert "NATIVE_ARTWORK_DOWNLOAD_FAILED" in artwork
    assert "NATIVE_ARTWORK_PUBLISHED" in artwork
    assert "NativeMailbox.write(appContext" in artwork  # existing consumer notification remains
    assert "NATIVE_BACKUP_CREATED" in backup
    assert "NATIVE_BACKUP_RESTORE_COMPLETED" in backup
    assert "NATIVE_RECOVERY_RESTORE_COMPLETED" in backup
    assert "throw error" in backup



def test_native_diagnostics_are_the_android_runtime_authority():
    assert (ROOT / "core/diagnostic_service.py").is_file()
    assert (ROOT / "core/diagnostics.py").is_file()
    assert (ROOT / "core/performance.py").is_file()
    entrypoint = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/main.kt")
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    diagnostics_service = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/ReiAnixDiagnosticsService.kt")
    assert "ReiAnixDiagnosticTimeline" in entrypoint
    assert "PerformanceDiagnostics" in entrypoint
    assert "ReiAnixDiagnosticTimeline.record(" in entrypoint
    assert "MainEntryPoint.onActivityCreated(" in activity
    assert "PerformanceDiagnostics.snapshotJson()" in diagnostics_service
    assert "DiagnosticTimeline" not in entrypoint
    assert "get_performance_monitor" not in entrypoint
    assert "DiagnosticsService" not in entrypoint
    assert not (ROOT / "main.py").exists()
