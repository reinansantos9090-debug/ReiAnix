from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_settings_controls_use_existing_preference_contract():
    settings = read("core/settings.py")
    compose = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
    viewmodel = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt")
    contract = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsContract.kt")
    sqlite = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSettingsRepository.kt")
    for key in ("app.confirm_destructive", "appearance.theme", "appearance.card_size", "appearance.show_thumbnails"):
        assert f'"{key}"' in settings + contract
        assert key in compose
    assert "setPreference(normalizedKey, normalizedValue)" in repository
    assert "setPreferencesAtomically(defaults)" in repository
    assert "nativePersisted" in repository
    assert "compose_settings_set" in repository
    assert "fun setSetting(key: String, value: String)" in viewmodel
    assert "definitions: Map<String, ReiAnixSettingDefinition>" in contract
    assert "SELECT key, value FROM preferences ORDER BY key COLLATE NOCASE ASC" in sqlite
    assert "notifications" not in compose.lower()


def test_theme_follows_flow_without_activity_restart():
    compose = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
    root = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComposeRoot.kt")
    library_host = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
    theme = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt")
    tokens = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt")
    assert "collectAsStateWithLifecycle" in library_host
    assert "settingsViewModel.themeMode.collectAsStateWithLifecycle()" in library_host
    assert "ReiAnixComposeTheme(themeMode = themeMode)" in root
    assert "ReiAnixComposeTheme(" not in compose
    assert "lightColorScheme" in theme
    assert "isSystemInDarkTheme" in theme
    assert "lightBackground" in tokens


def test_all_settings_categories_are_compose_owned_and_actionable():
    compose = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
    host = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    categories = (
        "Conta", "Geral", "Aparência", "Biblioteca", "Player", "Gestos", "Áudio e Legendas",
        "Metadata", "Artwork", "Armazenamento", "Dados e Cache", "Backup e Restauração",
        "Privacidade", "Varredura", "Diagnóstico", "Sobre",
    )
    managed_start = compose.index("private val NativeManagedSettingsCategories")
    managed_end = compose.index(")\n", managed_start) + 2
    managed_block = compose[managed_start:managed_end]
    for category in categories:
        assert f'"{category}"' in managed_block
    for category in ("Artwork", "Armazenamento", "Dados e Cache", "Backup e Restauração", "Diagnóstico"):
        assert f'"{category}" -> {{' in compose
    for action in (
        "clear_anilist_cache", "settings_export", "settings_import", "select_saf", "request_media_access",
        "check_storage_access", "open_broad_storage_settings", "backup_create", "backup_restore",
        "backup_integrity", "backup_reconcile", "diagnostic_export",
    ):
        assert action in repository + activity
    settings_callback_start = host.index("settings = {")
    settings_callback_end = host.index("storage = {", settings_callback_start)
    settings_callback = host[settings_callback_start:settings_callback_end]
    assert "publishSettingsNavigation(" not in settings_callback
    assert "onOpenStorage = {" in settings_callback
    assert "ReiAnixRoutes.STORAGE" in settings_callback


def test_shell_observes_only_narrow_settings_projections():
    viewmodel = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt")
    host = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
    for declaration in (
        'val themeMode: StateFlow<String>', 'val appearanceCardSize: StateFlow<String>',
        'val appearanceShowThumbnails: StateFlow<Boolean>', 'val libraryGridDensity: StateFlow<String>',
    ):
        assert declaration in viewmodel
    for flow in ("themeMode", "appearanceCardSize", "appearanceShowThumbnails", "libraryGridDensity"):
        assert f"settingsViewModel.{flow}.collectAsStateWithLifecycle()" in host
    assert 'settingsState.settings["appearance.theme"]' not in host
    assert 'settingsState.settings["appearance.card_size"]' not in host


def test_settings_storage_and_scanner_reuse_the_canonical_storage_route():
    compose = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt")
    host = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
    storage = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt")
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt")
    bridge = read("core/compose_library_bridge.py")
    assert "onOpenStorage: () -> Unit" in compose
    assert 'text = "Gerenciar fontes e atualização"' in compose
    assert 'text = "Ver armazenamento e fontes"' in compose
    assert "onOpenStorage = {" in host
    assert "ReiAnixRoutes.STORAGE" in host
    assert "scanInProgress = libraryState.scanInProgress" in host
    assert "scanState = libraryState.scanState" in host
    assert "lastScanStatus = libraryState.lastScanStatus" in host
    assert '"lastScanStatus": last_scan_status' in bridge
    assert "lastScanStatus = decoded.lastScanStatus ?: previous.lastScanStatus" in repository
    assert "onRemoveSaf = onRemoveSaf" in storage
    assert "pendingRemoval = source" in storage
    assert "viewModel.refreshNativeFolders()" in storage


def test_persisted_library_preferences_drive_native_compose_consumers():
    settings_vm = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt")
    host = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt")
    navigation = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt")
    home = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt")
    library = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt")
    library_vm = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt")
    library_repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt")
    assert "val libraryPageSize: StateFlow<Int>" in settings_vm
    assert "val librarySortDefault: StateFlow<String>" in settings_vm
    assert "val continueWatchingEnabled: StateFlow<Boolean>" in settings_vm
    assert "val continueWatchingLimit: StateFlow<Int>" in settings_vm
    for flow in ("libraryPageSize", "librarySortDefault", "continueWatchingEnabled", "continueWatchingLimit"):
        assert f"settingsViewModel.{flow}.collectAsStateWithLifecycle()" in host
    assert "libraryPageSize: Int = 36" in navigation
    assert 'librarySortDefault: String = "added_desc"' in navigation
    assert "continueWatchingEnabled: Boolean = true" in navigation
    assert "continueWatchingLimit: Int = 10" in navigation
    assert "continueWatching = visibleContinueWatching" in home
    assert "continueWatching.take(continueWatchingLimit.coerceAtLeast(0))" in home
    assert "viewModel.applyLibraryPageSize(pageSize)" in library
    assert "viewModel.applyLibrarySortDefault(defaultSortKey)" in library
    assert "pageSize.coerceIn(12, 72)" in library_repository
    assert "nativePageGeneration" in library_vm


def test_compose_settings_reads_and_writes_existing_sqlite_preferences():
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
    sqlite_repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSettingsRepository.kt")
    assert "ReiAnixSqliteSettingsRepository(appContext)" in repository
    assert "sqliteSettingsRepository.allPreferences()" in repository
    assert "snapshotState.settings + persistedPreferences" in repository
    assert "setPreference(normalizedKey, normalizedValue)" in repository
    assert "nativePersisted" in repository
    assert "compose_settings_set" in repository
    assert "NativeMailbox.write(" in repository
    assert "SELECT key, value FROM preferences ORDER BY key COLLATE NOCASE ASC" in sqlite_repository
    assert "database.transaction" in sqlite_repository


def test_compose_artwork_cache_clear_is_native_and_does_not_repeat_legacy_mutation():
    settings_repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
    artwork_service = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt")
    assert 'if (normalizedAction == "clear_anilist_cache")' in settings_repository
    assert "nativeArtworkService.clearManagedCache()" in settings_repository
    assert '.put("nativeHandled", true)' in settings_repository
    assert "suspend fun clearManagedCache(): Int" in artwork_service
    assert "source IN ('cache','anilist') AND manual=0" in artwork_service
    assert "UPDATE anime SET metadata_updated_at=NULL, cover_cache=''" in artwork_service
    assert "isArtworkPathReferenced" in artwork_service


def test_compose_settings_import_export_use_native_android_pickers_and_canonical_sqlite():
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
    file_service = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsFileService.kt")
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    assert 'normalizedAction == "settings_export" || normalizedAction == "settings_import"' in repository
    assert "Intent(appContext, MainActivity::class.java)" in repository
    assert "EXTRA_ACTION" in repository and "EXTRA_REQUEST_ID" in repository
    assert 'CreateDocument("application/json")' in activity
    assert "ActivityResultContracts.OpenDocument()" in activity
    assert "ReiAnixSettingsFileService(appDependencies.sqliteSettingsRepository)" in activity
    assert 'const val EXPORT_FORMAT = "reiflix-settings"' in file_service
    assert "SUPPORTED_SCHEMA_VERSIONS = setOf(1, SCHEMA_VERSION)" in file_service
    assert "setPreferencesAtomically(valuesToCommit)" in file_service
    assert "compose_settings_bulk_applied" in activity
    assert "writeSettingsFileActionResult" in activity


def test_compose_settings_storage_actions_dispatch_to_native_android_host():
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    request_action_start = repository.index("fun requestAction(action: String)")
    native_method_start = repository.index("private fun launchNativeStorageSettingsAction")
    request_action = repository[request_action_start:native_method_start]
    native_method_end = repository.index("private fun launchNativeSettingsFileAction", native_method_start)
    native_method = repository[native_method_start:native_method_end]
    assert "NATIVE_STORAGE_SETTINGS_ACTIONS" in request_action
    assert "NATIVE_BACKUP_DIAGNOSTIC_ACTIONS" in request_action
    assert "NATIVE_BACKUP_DIAGNOSTIC_ACTIONS" in repository
    for action in ("backup_create", "backup_restore", "backup_integrity", "backup_reconcile", "diagnostic_export"):
        assert f'"{action}"' in repository
    native_backup_start = request_action.index("if (normalizedAction in NATIVE_BACKUP_DIAGNOSTIC_ACTIONS)")
    assert native_backup_start < request_action.index("launchNativeSettingsFileAction(normalizedAction)", native_backup_start)
    assert "dispatchCommand(" not in request_action
    assert '"compose_settings_action"' not in request_action
    assert "MainActivity.createNativeStorageActionIntent(" in native_method
    assert 'appendQueryParameter("settings_action", action)' in native_method
    assert '"select_saf"' in native_method and '"select_tree"' in native_method
    assert 'data.getQueryParameter("settings_action")' in activity
    assert "beginComposeSettingsStorageAction(requestId, settingsAction)" in activity
    assert "completeComposeSettingsStorageAction(" in activity


def test_compose_backup_restore_integrity_and_diagnostics_are_native():
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt")
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    backup = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/ReiAnixBackupService.kt")
    diagnostics = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/backup/ReiAnixDiagnosticsService.kt")
    assert "NATIVE_BACKUP_DIAGNOSTIC_ACTIONS" in repository
    assert "launchNativeSettingsFileAction(normalizedAction)" in repository
    assert 'CreateDocument("application/zip")' in activity
    assert "ActivityResultContracts.OpenDocument()" in activity
    assert "finishNativeBackupExport" in activity
    assert "finishNativeBackupImport" in activity
    assert "finishNativeDiagnosticExport" in activity
    assert "runNativeSettingsMaintenanceAction" in activity
    assert 'const val FORMAT = "rei-flix-backup-v1"' in backup
    assert "const val SCHEMA_VERSION = 29" in backup
    assert "VACUUM INTO" in backup
    assert "SHA-256" in backup
    assert "manifest_core_sha256" in backup
    assert "SETTINGS_SCHEMA_VERSION = 2" in backup
    assert 'key == "settings.schema_version"' in backup
    assert "MAX_ARCHIVE_BYTES" in backup
    assert "MANUAL_ARTWORK_PREFIX" in backup
    assert "ATTACH DATABASE" in backup
    assert "beginTransactionNonExclusive" in backup
    assert "createBackupFile" in backup
    assert "restoreBackup" in backup
    assert "integrityReport" in backup
    assert "reconcileRestoredFileAvailability" in backup
    assert "includes_document_uris" in diagnostics
    assert "includes_anime_titles" in diagnostics
    assert "compose_settings_native_backup_restored" in activity
    assert "NATIVE_BACKUP_RESTORE_PROJECTION_REFRESHED" not in activity


if __name__ == "__main__":
    pass
