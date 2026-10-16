from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


BACKUP = "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/ReiAnixBackupService.kt"
DATABASE = "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt"
ACTIVITY = "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
SETTINGS_REPOSITORY = "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
NAVIGATION = "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
RECOVERY_ROUTE = "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/recovery/ReiAnixRecoveryRoute.kt"


def test_native_recovery_validates_candidate_before_promoting_canonical_database():
    service = read(BACKUP)
    start = service.index("private fun restoreRecoveryBackupLocked")
    end = service.index("fun integrityReport()", start)
    recovery = service[start:end]

    inspect = recovery.index("val preview = inspectBackup(file)")
    snapshot = recovery.index("val recoverySnapshot = createRecoverySafetySnapshot()")
    stage_validation = recovery.index("validateSnapshot(stagedDatabase, sanitizeUnknownPreferences = true)")
    candidate_validation = recovery.index("validateSnapshot(candidate, sanitizeUnknownPreferences = false)")
    promotion = recovery.index("candidate.renameTo(databaseFile)")
    post_validation = recovery.index("validateSnapshot(databaseFile, sanitizeUnknownPreferences = false)")
    schema_check = recovery.index("schemaSnapshot.supportsCurrentContract")
    integrity_check = recovery.index("postRestoreIntegrity.healthy")

    assert inspect < snapshot < stage_validation < candidate_validation < promotion
    assert promotion < post_validation < schema_check < integrity_check
    assert "PRAGMA foreign_key_check" in service
    assert "PRAGMA integrity_check" in service
    assert 'File(databaseFile.absolutePath + "-wal")' in service
    assert 'File(databaseFile.absolutePath + "-journal")' in service
    assert "sidecarPairs.asReversed()" in recovery
    assert "previousDatabaseMoved" in recovery
    assert "createRecoverySafetySnapshot" in recovery
    assert "readRecoverableAccountSnapshot()" in recovery
    assert "restoreRecoverableAccountSnapshot(stagedDatabase, accountSnapshot)" in recovery
    assert "accountStatePreserved = accountSnapshot.readable" in recovery
    assert "reauthenticationRequired = !accountSnapshot.readable" in recovery


def test_recovery_snapshot_preserves_source_bytes_and_describes_its_limits():
    service = read(BACKUP)
    snapshot_start = service.index("private fun createRecoverySafetySnapshot")
    snapshot_end = service.index("private fun reconnectArtworkReferences", snapshot_start)
    snapshot = service[snapshot_start:snapshot_end]

    assert 'format", "reianix-recovery-snapshot-v1' in snapshot
    assert 'put("sha256", sha256(destination))' in snapshot
    assert "source.length() == destination.length()" in snapshot
    assert "recovery-snapshot.json" in snapshot
    assert "not a backup" in snapshot or "não é um backup restaurável" in snapshot.lower()


def test_database_file_maintenance_shares_the_canonical_sqlite_lock():
    database = read(DATABASE)
    service = read(BACKUP)

    assert "withExclusiveFileMaintenance" in database
    assert "synchronized(connectionLock)" in database
    assert "sharedDatabase.withExclusiveFileMaintenance" in service
    assert "contractValidated = false" in database


def test_native_compose_recovery_route_is_connected_to_the_restore_action():
    activity = read(ACTIVITY)
    repository = read(SETTINGS_REPOSITORY)
    navigation = read(NAVIGATION)
    route = read(RECOVERY_ROUTE)
    host = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")

    assert "NATIVE_DATABASE_RECOVERY_REQUIRED" in activity
    assert "else if (!canonicalFile.isFile || canonicalFile.length() <= 0L)" in activity
    assert "integrityReport()" in activity
    assert "!schemaCompatible || !integrity.healthy" in activity
    assert "copyBackupToDocumentAndVerify" in activity
    assert "MessageDigest.isEqual" in activity
    assert "ReiAnixRoutes.RECOVERY" in activity
    assert "backupService.restoreRecoveryBackup(inputCopy)" in activity
    assert "result.reauthenticationRequired" in activity
    assert "result.accountStatePreserved" in activity
    assert "showNativeBackupRestoreConfirmation" in activity
    assert "setPositiveButton(" in activity
    assert "completeNativeBackupRestore(inputCopy, action, requestId)" in activity
    assert "Confirmar restauração do backup" in activity
    assert "preview.manifest" in activity
    assert "counts.optLong(\"anime\"" in activity
    assert '"backup_recovery_restore"' in activity
    assert '"backup_recovery_restore"' in repository
    assert "composable(ReiAnixRoutes.RECOVERY) { recovery() }" in navigation
    assert "recovery = { ReiAnixRecoveryRoute(viewModel = settingsViewModel) }" in host
    assert 'viewModel.requestAction("backup_recovery_restore")' in route
    assert "Restaurar a biblioteca?" in route or "Recuperar a biblioteca?" in route
    assert "diagnostic_export" in route


def test_android_startup_recovery_does_not_invoke_python_restore_writer():
    main = read("main.py")
    anchor = "if recovery_bridge.available:\n        # Android recovery is owned by the native Compose/SQLite services."
    start = main.index(anchor)
    end = main.index("        async def recovery_diagnostic()", start)
    android_recovery_branch = main[start:end]

    assert 'await recovery_bridge.open_library(start_destination="recovery")' in android_recovery_branch
    assert "recovery_service.restore_backup" not in android_recovery_branch
    assert "await recovery_bridge.hide_library()" not in main
    # The desktop-only fallback remains available; this prompt does not remove Flet globally.
    assert "recovery_service.restore_backup" in main[end:]
