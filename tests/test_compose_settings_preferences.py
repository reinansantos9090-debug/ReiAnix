from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_settings_controls_use_existing_preference_contract():
    settings = read("core/settings.py")
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    viewmodel = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt"
    )
    main = read("main.py")

    for key in (
        "app.confirm_destructive",
        "appearance.theme",
        "appearance.card_size",
        "appearance.show_thumbnails",
    ):
        assert f'"{key}"' in settings

    for key in (
        "app.confirm_destructive",
        "appearance.theme",
        "appearance.card_size",
        "appearance.show_thumbnails",
    ):
        assert key in compose

    assert "compose_settings_set" in repository
    assert "NativeMailbox.write(" in repository
    assert "setPreference(normalizedKey, normalizedValue)" in repository
    assert "setPreferencesAtomically(defaults)" in repository
    assert "resetSettingsNatively" in repository
    assert "compose_settings_bulk_applied" in repository
    assert "nativePersisted" in repository
    assert "SETTINGS_PERSISTED" in repository
    assert "effective[key] = pending.value" not in repository
    assert "settings.set" not in repository
    assert "fun setSetting(key: String, value: String)" in viewmodel
    assert "compose_settings_set" in main
    assert "async def _run_compose_settings_set" in main
    assert "await asyncio.to_thread(" in main
    assert "settings.set" in main  # compatibility for older hosts only
    assert "native_persisted=native_persisted" in main
    assert "if native_persisted:" in main
    assert "settings.invalidate_cache" in main
    assert "SETTINGS_RUNTIME_APPLY_FAILED" in main
    assert "compose_settings_bulk_applied" in main
    assert "_run_compose_settings_bulk_applied" in main
    assert "SETTINGS_RUNTIME_BULK_APPLIED" in main
    assert "if not native_persisted:" in main
    assert "setting_key" in main
    assert "apply_settings_runtime(setting_key, normalized)" in main

    # Notifications have no persisted/source-of-truth preference in this project,
    # so earlier validation stage 19 must not invent a non-functional Compose toggle for them.
    assert "notifications" not in compose.lower()


def test_theme_follows_flow_without_activity_restart():
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    root = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComposeRoot.kt"
    )
    library_host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    theme = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt"
    )
    tokens = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt"
    )

    assert "collectAsStateWithLifecycle" in library_host
    assert "settingsViewModel.themeMode.collectAsStateWithLifecycle()" in library_host
    assert "themeMode = themeMode" in library_host
    assert "ReiAnixSettingsRoute" in library_host
    assert "ReiAnixStorageRoute" in library_host
    assert "ReiAnixComposeTheme(themeMode = themeMode)" in root
    assert "ReiAnixComposeTheme(" not in compose
    assert "lightColorScheme" in theme
    assert "isSystemInDarkTheme" in theme
    assert "lightBackground" in tokens
    assert "appearance.card_size" in compose
    assert "appearance.show_thumbnails" in compose


def test_scope_is_whitelisted_in_python_before_persistence():
    main = read("main.py")
    start = main.index("if event_type == 'compose_settings_set':")
    end = main.index("if event_type == 'compose_settings_navigation':", start)
    block = main[start:end]

    for key in (
        "'app.confirm_destructive'",
        "'appearance.theme'",
        "'appearance.card_size'",
        "'appearance.show_thumbnails'",
    ):
        assert key in block

    assert "supported_compose_settings" in block
    worker_start = main.index("async def _run_compose_settings_set")
    worker_end = main.index("async def _run_compose_settings_action", worker_start)
    worker = main[worker_start:worker_end]
    assert "if native_persisted:" in worker
    assert "settings.invalidate_cache" in worker
    assert "settings.get, setting_key" in worker
    assert "settings.set" in worker  # legacy-host compatibility branch only
    assert "nativePersisted" in block
    assert "logger.warning" in block


def test_all_settings_categories_are_compose_owned_and_actionable():
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    main = read("main.py")

    categories = (
        "Conta",
        "Geral",
        "Aparência",
        "Biblioteca",
        "Player",
        "Gestos",
        "Áudio e Legendas",
        "Metadata",
        "Artwork",
        "Armazenamento",
        "Dados e Cache",
        "Backup e Restauração",
        "Privacidade",
        "Varredura",
        "Diagnóstico",
        "Sobre",
    )
    managed_start = compose.index("private val NativeManagedSettingsCategories")
    managed_end = compose.index(")\n", managed_start) + 2
    managed_block = compose[managed_start:managed_end]
    for category in categories:
        assert f'"{category}"' in managed_block

    for category in (
        "Artwork",
        "Armazenamento",
        "Dados e Cache",
        "Backup e Restauração",
        "Diagnóstico",
    ):
        assert f'"{category}" -> {{' in compose

    for action in (
        "clear_anilist_cache",
        "settings_export",
        "settings_import",
        "select_saf",
        "request_media_access",
        "check_storage_access",
        "open_broad_storage_settings",
        "backup_create",
        "backup_restore",
        "backup_integrity",
        "backup_reconcile",
        "diagnostic_export",
    ):
        assert action in repository
        assert action in main

    settings_callback_start = host.index("settings = {")
    settings_callback_end = host.index("storage = {", settings_callback_start)
    settings_callback = host[settings_callback_start:settings_callback_end]
    assert "publishSettingsNavigation(" not in settings_callback
    assert "onOpenStorage = {" in settings_callback
    assert "ReiAnixRoutes.STORAGE" in settings_callback


def test_shell_observes_only_narrow_settings_projections():
    viewmodel = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt"
    )
    host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )

    for declaration in (
        'val themeMode: StateFlow<String>',
        'val appearanceCardSize: StateFlow<String>',
        'val appearanceShowThumbnails: StateFlow<Boolean>',
        'val libraryGridDensity: StateFlow<String>',
    ):
        assert declaration in viewmodel

    assert "settingsViewModel.themeMode.collectAsStateWithLifecycle()" in host
    assert "settingsViewModel.appearanceCardSize.collectAsStateWithLifecycle()" in host
    assert "settingsViewModel.appearanceShowThumbnails.collectAsStateWithLifecycle()" in host
    assert "settingsViewModel.libraryGridDensity.collectAsStateWithLifecycle()" in host
    assert 'settingsState.settings["appearance.theme"]' not in host
    assert 'settingsState.settings["appearance.card_size"]' not in host

def test_settings_storage_and_scanner_reuse_the_canonical_storage_route():
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    storage = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt"
    )

    assert "onOpenStorage: () -> Unit" in compose
    assert 'text = "Gerenciar fontes e atualização"' in compose
    assert 'text = "Ver armazenamento e fontes"' in compose
    assert "onClick = onOpenStorage" in compose
    assert "onOpenStorage = {" in host
    assert "ReiAnixRoutes.STORAGE" in host
    assert 'text = "Última varredura: $lastScanLabel"' in compose
    assert "scanInProgress = libraryState.scanInProgress" in host
    assert "scanState = libraryState.scanState" in host
    assert "lastScanStatus = libraryState.lastScanStatus" in host
    bridge = read("core/compose_library_bridge.py")
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
    )
    assert '"lastScanStatus": last_scan_status' in bridge
    assert "lastScanStatus = decoded.lastScanStatus ?: previous.lastScanStatus" in repository
    assert "onRemoveSaf = onRemoveSaf" in storage
    assert "pendingRemoval = source" in storage
    assert "onRefreshLibrary = {" in storage
    assert "viewModel.refresh()" in storage
    assert "viewModel.refreshNativeFolders()" in storage

def test_persisted_library_preferences_drive_native_compose_consumers():
    settings_vm = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt"
    )
    host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    navigation = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/navigation/ReiAnixNavigation.kt"
    )
    home = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/home/ReiAnixHome.kt"
    )
    library = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/library/ReiAnixLibrary.kt"
    )
    library_vm = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
    )
    library_repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt"
    )

    # All values remain projections from the single persisted SettingsStore snapshot.
    assert "val libraryPageSize: StateFlow<Int>" in settings_vm
    assert "val librarySortDefault: StateFlow<String>" in settings_vm
    assert "val continueWatchingEnabled: StateFlow<Boolean>" in settings_vm
    assert "val continueWatchingLimit: StateFlow<Int>" in settings_vm
    for flow in (
        "libraryPageSize",
        "librarySortDefault",
        "continueWatchingEnabled",
        "continueWatchingLimit",
    ):
        assert f"settingsViewModel.{flow}.collectAsStateWithLifecycle()" in host
        assert f"{flow} = {flow}" in host

    assert "libraryPageSize: Int = 36" in navigation
    assert 'librarySortDefault: String = "added_desc"' in navigation
    assert "continueWatchingEnabled: Boolean = true" in navigation
    assert "continueWatchingLimit: Int = 10" in navigation
    assert "continueWatching = visibleContinueWatching" in home
    assert "continueWatching.take(continueWatchingLimit.coerceAtLeast(0))" in home
    assert "viewModel.applyLibraryPageSize(pageSize)" in library
    assert "viewModel.applyLibrarySortDefault(defaultSortKey)" in library
    assert "pageSize = configuredLibraryPageSize" in library_vm
    assert "sort = librarySortLabelForSetting(appliedLibrarySortDefault)" in library_vm
    assert "pageSize.coerceIn(12, 72)" in library_repository
    assert "fun setSetting(key: String, value: String)" in read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt"
    )

def test_compose_settings_reads_and_writes_existing_sqlite_preferences():
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    sqlite_repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSettingsRepository.kt"
    )

    assert "ReiAnixSqliteSettingsRepository(appContext)" in repository
    assert "sqliteSettingsRepository.allPreferences()" in repository
    assert "snapshotState.settings + persistedPreferences" in repository
    assert "setPreference(normalizedKey, normalizedValue)" in repository
    assert "nativePersisted" in repository
    assert "compose_settings_set" in repository
    assert "NativeMailbox.write(" in repository
    assert "SELECT key, value FROM preferences ORDER BY key COLLATE NOCASE ASC" in sqlite_repository
    assert "database.transaction" in sqlite_repository

def test_compose_artwork_cache_clear_is_native_and_does_not_repeat_python_mutation():
    settings_repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    artwork_service = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/artwork/ReiAnixArtworkService.kt"
    )
    main = read("main.py")

    assert 'if (normalizedAction == "clear_anilist_cache")' in settings_repository
    assert "nativeArtworkService.clearManagedCache()" in settings_repository
    assert '.put("nativeHandled", true)' in settings_repository
    assert "suspend fun clearManagedCache(): Int" in artwork_service
    assert 'source IN (\'cache\',\'anilist\') AND manual=0' in artwork_service
    assert 'UPDATE anime SET metadata_updated_at=NULL, cover_cache=\'\'' in artwork_service
    assert "isArtworkPathReferenced" in artwork_service
    assert "native_handled=native_handled" in main
    worker_start = main.index("async def _run_compose_settings_action")
    worker_end = main.index("async def _hydrate_compose_details_context", worker_start)
    worker = main[worker_start:worker_end]
    assert "if native_handled:" in worker
    assert "library.clear_anilist_cache" in worker  # legacy Flet path only

def test_compose_settings_import_export_use_native_android_pickers_and_canonical_sqlite():
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    file_service = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsFileService.kt"
    )
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    main = read("main.py")

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
    assert "if action == 'settings_export':" in main
    assert "if action == 'settings_import':" in main  # legacy Flet Settings consumer remains supported

def test_compose_settings_storage_actions_dispatch_to_native_android_host():
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    main = read("main.py")

    request_action_start = repository.index("fun requestAction(action: String)")
    native_method_start = repository.index("private fun launchNativeStorageSettingsAction")
    request_action = repository[request_action_start:native_method_start]
    native_method_end = repository.index("private fun launchNativeSettingsFileAction", native_method_start)
    native_method = repository[native_method_start:native_method_end]

    assert "NATIVE_STORAGE_SETTINGS_ACTIONS" in request_action
    dispatch_start = request_action.index("dispatchCommand(")
    native_dispatch_contract = request_action[:dispatch_start]
    legacy_dispatch_contract = request_action[dispatch_start:]
    assert '"backup_create", "backup_restore", "backup_integrity", "backup_reconcile"' in request_action
    assert '"select_saf"' not in legacy_dispatch_contract
    assert '"request_media_access"' not in legacy_dispatch_contract
    assert '"compose_settings_action"' in legacy_dispatch_contract  # backup/diagnostic actions still use the Python contract
    assert "MainActivity.createNativeStorageActionIntent(" in native_method
    assert 'appendQueryParameter("settings_action", action)' in native_method
    assert '"select_saf"' in native_method and '"select_tree"' in native_method
    assert 'data.getQueryParameter("settings_action")' in activity
    assert "beginComposeSettingsStorageAction(requestId, settingsAction)" in activity
    assert "completeComposeSettingsStorageAction(" in activity
    assert 'message = "Pasta adicionada à biblioteca."' in activity
    assert 'message = "Seleção de pasta cancelada."' in activity
    assert "if action == 'select_saf':" in main  # legacy Flet Settings keeps its own consumer
    assert "if action == 'request_media_access':" in main
