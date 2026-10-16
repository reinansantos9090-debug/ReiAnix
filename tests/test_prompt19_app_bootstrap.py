from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_application_owns_and_starts_one_process_bootstrap():
    application = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixApplication.kt"
    )
    dependencies = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppDependencies.kt"
    )
    bootstrap = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt"
    )
    assert "dependencies = ReiAnixAppDependencies(this)" in application
    assert "dependencies.bootstrap.start()" in application
    assert "val bootstrap: ReiAnixAppBootstrap by lazy" in dependencies
    assert "CoroutineScope(SupervisorJob() + Dispatchers.IO)" in bootstrap
    assert "private var startupJob: Job? = null" in bootstrap
    assert "if (startupJob != null) return" in bootstrap
    initialize = bootstrap[
        bootstrap.index("private suspend fun initializeProcessServices()"):
        bootstrap.index("private fun publishDatabaseCheck")
    ]
    assert "inspectCanonicalDatabase()" in initialize
    assert initialize.index("inspectCanonicalDatabase()") < initialize.index("if (check.recoveryRequired)")
    assert initialize.index("if (check.recoveryRequired)") < initialize.index("ensureNativeServicesStarted()")
    services = bootstrap[bootstrap.index("private suspend fun ensureNativeServicesStarted()"):
                         bootstrap.index("private fun inspectCanonicalDatabase()")]
    assert services.index("validated.recoveryRequired") < services.index("NativeScanRunner.recoverInterruptedSafScans(appContext)")
    assert services.index("NativeScanRunner.recoverInterruptedSafScans(appContext)") < services.index("NativeCommandDispatcher.start(appContext)")
    assert "NativeIndex.pendingSafRecovery(appContext).length() > 0" in bootstrap
    assert "NativeScanRunner.recoverInterruptedSafScans(appContext)" in bootstrap
    assert "NativeCommandDispatcher.start(appContext)" in bootstrap
    assert bootstrap.index("NativeScanRunner.recoverInterruptedSafScans(appContext)") < bootstrap.index(
        "NativeCommandDispatcher.start(appContext)"
    )


def test_activity_delegates_process_startup_and_database_health_to_bootstrap():
    activity = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
    )
    assert "appDependencies.bootstrap.start()" in activity
    assert "appDependencies.bootstrap.validateCanonicalDatabase()" in activity
    assert "NativeScanRunner.recoverInterruptedSafScans(applicationContext)" not in activity
    assert "NativeCommandDispatcher.start(" not in activity
    # The Activity still calls the existing runner for explicitly authorized scans.
    assert "NativeScanRunner.runSafScan(" in activity
    assert "NativeScanRunner.runMediaStoreScan(" in activity


def test_bootstrap_preserves_canonical_sqlite_and_recovery_contract():
    bootstrap = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt"
    )
    activity = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
    )
    assert "ReiAnixBackupService.DATABASE_NAME" in bootstrap
    assert "dependencies.sqliteDatabase.inspectSchema().supportsCurrentContract" in bootstrap
    assert "ReiAnixBackupService(appContext).integrityReport().healthy" in bootstrap
    assert "ReiAnixBootstrapPersistenceStatus.NOT_CREATED" in bootstrap
    assert "interruptedPromotion || priorRecoverySnapshots || pendingSafRecovery" in bootstrap
    assert 'File(dataDirectory, "backups")' in bootstrap
    assert "ReiAnixRoutes.RECOVERY" in activity
    assert "Thread.sleep" not in bootstrap
    assert "GlobalScope" not in bootstrap


def test_compose_observes_process_bootstrap_state_without_owning_service_lifecycle():
    view_model = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
    )
    host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    bootstrap = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt"
    )
    assert "val bootstrapState: StateFlow<ReiAnixBootstrapState>" in view_model
    assert "appDependencies?.bootstrap?.state" in view_model
    assert "libraryViewModel.bootstrapState.collectAsStateWithLifecycle()" in host
    assert "ReiAnixBootstrapPhase.DEGRADED" in host
    assert "ReiAnixDiagnosticTimeline.record(" in bootstrap
    assert 'Log.w(TAG, "Could not record bootstrap diagnostic event=$name.", error)' in bootstrap


def test_legacy_runtime_is_preserved_and_documented_as_a_migration_boundary():
    main = read("main.py")
    doc = read("docs/KOTLIN_APP_BOOTSTRAP_MIGRATION.md")
    assert "async def main(page: ft.Page)" in main
    assert "LibraryStore(data_dir)" in main
    assert "SettingsStore(store)" in main
    assert "ComposeLibraryBridge(" in main
    assert "ComposeSettingsBridge(" in main
    assert "ScanCoordinator(" in main
    for token in ("Grupo A", "Grupo B", "Grupo C", "FlutterFragmentActivity", "NOT_CREATED", "não"):
        assert token.lower() in doc.lower()
    assert "Não foi removida delegação global Python" in doc


def test_compose_consumers_and_remote_side_effects_wait_for_a_validated_bootstrap():
    view_model = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
    )
    host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    activity = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt"
    )
    assert "dependencies.bootstrap.state.first" in view_model
    assert "startCanonicalConsumersAfterDatabaseValidation()" in view_model
    init = view_model[view_model.index("init {"):view_model.index("private suspend fun refreshNativeCatalog()")]
    assert "requestPendingArtwork()" not in init
    assert "requestPendingArtwork()" not in view_model
    assert "requestPendingArtwork()" in host
    assert "reconcileUnmatchedLibraryMetadata()" in host
    assert "LaunchedEffect(libraryViewModel)" in host
    assert ".drop(1)" in host
    assert '.filter { scanning -> !scanning }' in host
    assert "composeLibraryHost.show(ReiAnixRoutes.RECOVERY, resetBackStack = true)" in activity
    assert "composeLibraryHost.show(ReiAnixRoutes.HOME, resetBackStack = true)" in activity
    assert activity.index("appDependencies.bootstrap.validateCanonicalDatabase()") < activity.index(
        "composeLibraryHost = ReiAnixComposeLibraryHost("
    )
