from pathlib import Path
import base64
import gzip
import json
import re
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / "core/library_store.py"
DATABASE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteDatabase.kt"
BOOTSTRAPPER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteBootstrapper.kt"
BOOTSTRAP_ARCHIVE = ROOT / "build-logic/native-sqlite-bootstrap-v29.json.gz.b64"
LIBRARY = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
SETTINGS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteSettingsRepository.kt"
MODELS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteModels.kt"
TEMPLATE = ROOT / "scripts/prepare_flet_template.py"
FIXTURE_GENERATOR = ROOT / "scripts/generate_native_sqlite_test_fixture.py"
KOTLIN_WORKFLOW = ROOT / ".github/workflows/kotlin_jvm_tests.yml"
APK_WORKFLOW = ROOT / ".github/workflows/build_apk.yml"
HOST_VERIFIER = ROOT / "scripts/verify_android_host.py"


class NativeSqlitePersistenceContractTests(unittest.TestCase):
    def test_native_layer_targets_the_existing_schema_and_path(self):
        python_store = STORE.read_text(encoding="utf-8")
        database = DATABASE.read_text(encoding="utf-8")

        self.assertIn("SCHEMA_VERSION = 29", python_store)
        self.assertIn('File(File(appContext.filesDir, "data"), DATABASE_FILE_NAME)', database)
        self.assertIn('DATABASE_FILE_NAME = "library.sqlite3"', database)
        self.assertIn("schema_migrations", database)
        self.assertIn("PRAGMA user_version", database)
        self.assertIn("PRAGMA quick_check", database)
        self.assertIn("PRAGMA foreign_key_check", database)
        self.assertIn("table_xinfo", database)
        self.assertIn('CONTRACT_ASSET_NAME = "native-sqlite-contract-v29.json"', database)
        self.assertIn("ReiAnixSqliteSchemaMismatchException", database)
        self.assertIn("validateContract && !contractValidated", database)
        self.assertNotIn("REQUIRED_COLUMN_CONTRACT", database)
        self.assertIn('contract.getJSONObject("columns")', database)
        self.assertIn('contract.getJSONObject("table_contract")', database)

    def test_opening_never_creates_or_rebuilds_the_canonical_database(self):
        database = DATABASE.read_text(encoding="utf-8")

        self.assertIn("SQLiteDatabase.OPEN_READONLY", database)
        self.assertIn("SQLiteDatabase.OPEN_READWRITE", database)
        self.assertNotIn("CREATE_IF_NECESSARY", database)
        self.assertNotIn("SQLiteOpenHelper", database)
        self.assertNotIn("CREATE TABLE", database.upper())
        self.assertNotIn("DROP TABLE", database.upper())
        self.assertNotIn("openOrCreateDatabase", database)
        self.assertNotIn("CREATE_IF_NECESSARY", database)
        self.assertNotIn("deleteDatabase(", database)
        self.assertIn("beginTransactionNonExclusive", database)
        self.assertIn("setTransactionSuccessful", database)
        self.assertIn("endTransaction", database)

    def test_fresh_install_bootstrap_is_atomic_and_keeps_database_opener_read_only(self):
        bootstrapper = BOOTSTRAPPER.read_text(encoding="utf-8")
        bootstrap = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt").read_text(encoding="utf-8")
        database = DATABASE.read_text(encoding="utf-8")
        archive = json.loads(gzip.decompress(base64.b64decode(
            BOOTSTRAP_ARCHIVE.read_text(encoding="ascii").strip()
        )))

        self.assertEqual(29, archive["schema_version"])
        self.assertEqual(29, archive["initial_migration_version"])
        self.assertEqual(0, archive["sqlite_user_version"])
        self.assertIn("schema_migrations", archive["tables"])
        self.assertGreater(len(archive["statements"]), 10)
        self.assertIn("ReiAnixSqliteBootstrapper(appContext, dependencies.sqliteDatabase).bootstrapIfMissing()", bootstrap)
        self.assertIn("interruptedPreviousDatabase || priorRecoverySnapshots || pendingSafRecovery", bootstrap)
        recovery_gate_start = bootstrap.index("val interruptedPreviousDatabase")
        recovery_gate_end = bootstrap.index("val priorRecoverySnapshots", recovery_gate_start)
        recovery_evidence = bootstrap[recovery_gate_start:recovery_gate_end]
        self.assertIn('file.name.startsWith(".library.sqlite3.previous-")', recovery_evidence)
        self.assertNotIn('file.name.startsWith(".library.sqlite3.candidate-")', recovery_evidence)
        self.assertIn("Os.link(candidateFile.absolutePath, canonicalFile.absolutePath)", bootstrapper)
        self.assertIn(".library.sqlite3.candidate-", bootstrapper)
        self.assertIn("inspectSchemaFile(candidateFile)", bootstrapper)
        self.assertIn("quickCheckFile(candidateFile)", bootstrapper)
        self.assertIn("schema_migrations(version, applied_at)", bootstrapper)
        self.assertNotIn("CREATE TABLE", database.upper())
        self.assertNotIn("DROP TABLE", database.upper())
        self.assertNotIn("openOrCreateDatabase", database)

    def test_native_library_repository_uses_canonical_tables_and_bound_values(self):
        library = LIBRARY.read_text(encoding="utf-8")

        for table in ("anime", "episodes", "folders", "artwork"):
            self.assertIn("FROM " + table, library)
        self.assertIn('"id = ?"', library)
        self.assertIn("arrayOf(id.toString())", library)
        self.assertIn('connection.update(', library)
        self.assertIn('"episodes"', library)
        self.assertIn("database.transaction", library)
        self.assertIn("withContext(Dispatchers.IO)", library)
        # Scanner scope identity may be an opaque SAF document ID; persisted source_folder
        # and missing-state reconciliation must use the configured tree URI instead.
        self.assertIn("snapshot.scopeReference", library)
        self.assertIn("val root = snapshot.scopeReference.trim().ifBlank", library)


    def test_saf_scan_has_one_persistence_writer_and_indexes_partial_positive_results(self):
        publisher = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanPublisher.kt").read_text(encoding="utf-8")
        runner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        repository = LIBRARY.read_text(encoding="utf-8")
        database_source = DATABASE.read_text(encoding="utf-8")
        native_index = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/NativeIndex.kt").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")

        self.assertIn("NATIVE_SCAN_BATCH_STAGED", publisher)
        self.assertNotIn('put("nativeLibraryIndexOwner", true)', publisher)
        self.assertIn('if payload.get("nativeLibraryIndexOwner") is True:', main)
        self.assertNotIn('if source_kind == "saf" and payload.get("nativeLibraryIndexOwner") is True:', main)
        broad_start = runner.index("suspend fun runBroadScan(")
        media_start = runner.index("suspend fun runMediaStoreScan(", broad_start)
        broad_scan = runner[broad_start:media_start]
        media_scan = runner[media_start:]
        # Existing domain contract treats these two scans as diagnostics, not catalog sources.
        self.assertNotIn("indexDiscoveredMedia", broad_scan)
        self.assertNotIn("indexDiscoveredMedia", media_scan)
        self.assertIn('result.put("nativeLibraryIndexOwner", true)', broad_scan)
        self.assertIn('result.put("nativeLibraryIndexOwner", true)', media_scan)
        self.assertIn("indexDiscoveredMedia", runner[:broad_start])
        self.assertIn("NATIVE_KOTLIN_INDEXER_OWNS_PERSISTENCE", main)
        self.assertIn("NativeIndex.committedDocuments", runner)
        self.assertIn("NativeIndex.stagedDocuments", runner)
        self.assertIn("indexDiscoveredMedia", runner)
        self.assertIn("private fun discoveredMediaFromSnapshot(", runner)
        self.assertIn("sourceFolder = sourceFolder", runner)
        self.assertIn("fun stagedDocuments(", native_index)
        self.assertIn("if (published) cleanupUnreferencedBatchFiles", native_index)
        # A failed native transaction must retain receipt-gated positive discoveries for Kotlin recovery.
        self.assertIn('put("nativeLibraryIndexOwner", true)', runner)
        self.assertIn('put("documents", JSONArray())', runner)
        self.assertNotIn('put("nativeLibraryIndexOwner", !ownershipTransferred)', runner)
        self.assertNotIn('put("nativeLibraryIndexOwner", !fallbackOwnershipAllowed)', runner)
        self.assertNotIn('put("documents", if (fallbackAvailable)', runner)
        self.assertNotIn("LEGACY_INGESTION_REQUIRED", runner)
        self.assertNotIn("serialized fallback ingestion required", runner)
        self.assertIn("NATIVE_POSITIVE_RECOVERY_PENDING", runner)
        self.assertIn("nativeIndexingCancelled = indexError is CancellationException", runner)
        self.assertIn("SAF native indexing cancellation was handled according to the confirmed transaction outcome.", runner)
        self.assertIn("onTransactionOutcome", repository)
        self.assertIn("ReiAnixSqliteTransactionOutcome.COMMITTED", database_source)
        self.assertIn("ReiAnixSqliteTransactionOutcome.ROLLED_BACK", database_source)
        self.assertIn("ReiAnixSqliteTransactionOutcome.UNKNOWN", database_source)
        self.assertIn("val rollbackConfirmed = transactionOutcome == ReiAnixSqliteTransactionOutcome.ROLLED_BACK", runner)
        self.assertIn("val rollbackConfirmed = transactionOutcome == ReiAnixSqliteTransactionOutcome.ROLLED_BACK", runner)
        self.assertIn("var recoverablePositiveSnapshot = rollbackConfirmed &&", runner)
        self.assertIn("fun canRecoverInKotlin(): Boolean", runner)
        self.assertIn("if (!canRecoverInKotlin()) return null", runner)
        self.assertIn("val canReadPositiveSnapshot = canRecoverInKotlin() && recoveryDocuments() != null", runner)
        self.assertIn('put("nativeLibraryIndexOwner", true)', runner)
        self.assertIn("nativeIndexingCheckpointAttempted.set(true)", runner)
        self.assertIn("ROLLBACK_RECEIPT_UNAVAILABLE_RESCAN_REQUIRED", runner)
        self.assertIn("if (!persisted)", runner)
        self.assertIn("markNativeCatalogOutcomeUnknown", runner)
        self.assertIn("markNativeCatalogCommittedWithoutReport", runner)
        self.assertIn("committedIndexReport.set(report)", runner)
        self.assertIn('put("commitOutcome", "COMMITTED")', runner)
        self.assertIn('put("nativeLibraryIndexOwner", true)', runner)
        self.assertIn("!scanGenerationFinished", runner)
        self.assertIn("published && committedIndexReport.get() == null && !scanGenerationFinished", runner)
        self.assertIn("fun recoveryDocuments(): JSONArray?", runner)
        self.assertIn("fun publishRecoveryTerminal(message: String, cancelled: Boolean)", runner)
        self.assertNotIn('priorRecovery == "LEGACY_INGESTION_REQUIRED"', runner)
        self.assertIn("terminalEventPublished = true", runner)
        self.assertIn("recoveryProtocolVersion", native_index)
        self.assertIn("markNativeCatalogIndexing", runner)
        self.assertIn("markNativeCatalogNotCommitted", runner)
        self.assertIn("markNativeCatalogCommitted", runner)
        self.assertIn("fun pendingSafRecovery(context: Context): JSONArray", native_index)
        self.assertIn("fun recoveryDocuments(", native_index)
        self.assertIn('target.name.endsWith(".ndjson")', native_index)
        self.assertIn('documents.length() == recordedProcessed', native_index)
        self.assertIn('documents.length() == 0', native_index)
        self.assertIn('scope.optString("nativeCatalogWriteOutcome") !in setOf("NOT_STARTED", "NOT_COMMITTED")', native_index)
        self.assertIn("NativeIndex.recoveryDocuments(", runner)
        self.assertIn('partialStats.put("status", SafScanner.STATUS_PARTIAL).put("partial", true)', runner)
        self.assertIn("full reconciliation; previous rows were preserved", runner)
        self.assertIn("suspend fun recoverInterruptedSafScans(context: Context)", runner)
        bootstrap = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ReiAnixAppBootstrap.kt").read_text(encoding="utf-8")
        self.assertIn("NativeScanRunner.recoverInterruptedSafScans(appContext)", bootstrap)
        self.assertLess(
            bootstrap.index("NativeScanRunner.recoverInterruptedSafScans(appContext)"),
            bootstrap.index("NativeCommandDispatcher.start(appContext)"),
        )
        self.assertIn("appDependencies.bootstrap.start()", activity)
        self.assertIn("appDependencies.bootstrap.validateCanonicalDatabase()", activity)
        self.assertNotIn("NativeScanRunner.recoverInterruptedSafScans(applicationContext)", activity)
        self.assertNotIn("NativeCommandDispatcher.start(this@MainActivity)", activity)
        self.assertIn("withContext(Dispatchers.IO)", activity)
        self.assertIn('put("nativeScanRunOwner", nativeScanRunOwner)', runner)
        self.assertIn("COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED", runner)
        self.assertIn("recoveryEventPublished", native_index)
        self.assertRegex(
            main,
            r"""if\s+payload\.get\((['"])documents\1\)\s+and\s+payload\.get\((['"])nativeLibraryIndexOwner\2\)\s+is\s+not\s+True\s*:""",
        )

    def test_native_saf_finalization_records_scan_without_second_catalog_reconciliation(self):
        from unittest.mock import patch
        from core.library_service import LibraryService
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            tree_uri = "content://com.android.externalstorage.documents/tree/primary%3AAnime"
            store.add_folder(tree_uri, name="Anime", kind="saf", authorization="granted")
            service = LibraryService(store)

            with patch.object(store, "reconcile_scope_generation", side_effect=AssertionError("duplicate reconciliation")) as reconcile:
                result = service.finish_ingest_documents(
                    tree_uri,
                    source_kind="saf",
                    scan_id="native-owned-scan",
                    scope_kind="root",
                    scope_ref=tree_uri,
                    scan_generation=1,
                    generation_id="native:1",
                    status="completed",
                    enforce_library_source=False,
                    native_persistence_owner=True,
                )

            reconcile.assert_not_called()
            self.assertIsInstance(result.catalog, list)
            folder = next(item for item in store.folders() if item["path"] == tree_uri)
            self.assertEqual(folder["authorization"], "granted")
            scan = store.scan_by_id("native-owned-scan")
            self.assertIsNotNone(scan)

    def test_native_index_rollback_snapshot_is_recoverable_once_without_destructive_reconciliation(self):
        from unittest.mock import patch
        from core.library_service import LibraryService
        from core.library_store import LibraryStore

        with tempfile.TemporaryDirectory() as data_dir:
            store = LibraryStore(data_dir)
            source = "content://com.android.externalstorage.documents/tree/primary%3AAnime"
            previous = {
                "uri": "content://media/previous",
                "name": "Show S01E01.mkv",
                "relativePath": "Show/Show S01E01.mkv",
                "mimeType": "video/x-matroska",
                "size": 1024,
                "modifiedAt": 1000,
                "volumeId": "primary",
                "volumeUuid": "primary",
            }
            recovered = {
                "uri": "content://media/recovered",
                "name": "Show S01E02.mkv",
                "relativePath": "Show/Show S01E02.mkv",
                "mimeType": "video/x-matroska",
                "size": 2048,
                "modifiedAt": 2000,
                "volumeId": "primary",
                "volumeUuid": "primary",
            }
            service = LibraryService(store)
            service.ingest_documents(source, [previous], scan_id="before-native-failure", scope_kind="root", scope_ref=source)
            # This models the terminal event emitted only after the native SQLite
            # transaction has rolled back. The partial receipt must not authorize
            # reconciliation, while the discovered item is still persisted.
            service.ingest_documents(
                source,
                [recovered],
                scan_id="native-index-failed",
                source_kind="saf",
                scope_kind="root",
                scope_ref=source,
                scan_generation=12,
                scan_stats={"status": "partial", "partial": True, "generationId": "native:12"},
                scan_errors=["native SQLite index failed; serialized fallback used"],
            )
            first = store.physical_row(recovered["uri"])
            self.assertIsNotNone(first)
            self.assertFalse(first["missing"])
            self.assertFalse(store.physical_row(previous["uri"])["missing"])

            # Replaying the same terminal scan is idempotent and cannot create a
            # second episode or observation for the recovered physical identity.
            service.ingest_documents(
                source,
                [recovered],
                scan_id="native-index-failed",
                source_kind="saf",
                scope_kind="root",
                scope_ref=source,
                scan_generation=12,
                scan_stats={"status": "partial", "partial": True, "generationId": "native:12"},
                scan_errors=["native SQLite index failed; serialized fallback used"],
            )
            rows = [episode for anime in store.catalog() for season in anime["seasons"] for episode in season["episodes"]]
            self.assertEqual(2, len(rows))
            self.assertEqual("partial", store.scan_by_id("native-index-failed")["status"])

            # If no verified snapshot can be recovered, finalization receives an empty
            # partial receipt. It must preserve previously known physical rows.
            service.finish_ingest_documents(
                source,
                source_kind="saf",
                scan_id="native-index-snapshot-unavailable",
                scope_kind="root",
                scope_ref=source,
                scan_generation=13,
                status="partial",
                scan_errors=["verified discovery snapshot unavailable; controlled rescan required"],
                scan_stats={"status": "partial", "partial": True, "generationId": "native:13"},
                enforce_library_source=True,
                native_persistence_owner=False,
            )
            self.assertFalse(store.physical_row(previous["uri"])["missing"])
            self.assertFalse(store.physical_row(recovered["uri"])["missing"])

            # An uncertain SQLite transaction cannot trigger a second writer or
            # destructive reconciliation; the native catalog remains authoritative.
            with patch.object(store, "reconcile_scope_generation", side_effect=AssertionError("unknown outcome must not reconcile")) as reconcile:
                service.finish_ingest_documents(
                    source,
                    source_kind="saf",
                    scan_id="native-index-outcome-unknown",
                    scope_kind="root",
                    scope_ref=source,
                    scan_generation=14,
                    generation_id="native:14",
                    status="partial",
                    scan_errors=["native SQLite transaction outcome unknown; rescan required"],
                    scan_stats={
                        "status": "partial",
                        "partial": True,
                        "generationId": "native:14",
                        "nativeLibraryIndex": {"commitOutcome": "UNKNOWN"},
                    },
                    enforce_library_source=True,
                    native_persistence_owner=True,
                )
            reconcile.assert_not_called()
            self.assertFalse(store.physical_row(previous["uri"])["missing"])
            self.assertFalse(store.physical_row(recovered["uri"])["missing"])

    def test_metadata_patch_updates_catalog_fields_without_overwriting_user_state(self):
        library = LIBRARY.read_text(encoding="utf-8")
        method_start = library.index("suspend fun updateAnimeMetadata(")
        method_end = library.index("suspend fun getEpisode(", method_start)
        method = library[method_start:method_end]

        self.assertIn('database.transaction', method)
        self.assertIn('"metadata_updated_at"', method)
        self.assertIn('SELECT * FROM anime WHERE id = ?', method)
        self.assertIn('optionalString("metadata_manual_fields")', method)
        self.assertIn('if ("title" !in manualFields)', method)
        self.assertNotIn('values.put("metadata_source"', method)
        self.assertNotIn('values.put("anilist_id"', method)
        self.assertIn('patch.coverCache?.takeIf { it.isNotBlank() }', method)
        self.assertIn('patch.genresJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }', method)
        for user_owned_column in ('"favorite"', '"is_pinned"', '"personal_note"', '"user_tags"'):
            self.assertNotIn(user_owned_column, method)

    def test_settings_are_parameterized_and_transactional(self):
        settings = SETTINGS.read_text(encoding="utf-8")

        self.assertIn("FROM preferences", settings)
        self.assertIn('"key = ?"', settings)
        self.assertIn("connection.update(", settings)
        self.assertIn("connection.insertOrThrow(", settings)
        self.assertIn("connection.delete(", settings)
        self.assertIn("database.transaction", settings)
        self.assertIn("withContext(Dispatchers.IO)", settings)

    def test_rendered_android_host_includes_robolectric_jvm_sqlite_runtime(self):
        template = TEMPLATE.read_text(encoding="utf-8")

        self.assertIn('testImplementation("org.robolectric:robolectric:4.14.1")', template)
        self.assertIn("unitTests.isIncludeAndroidResources = true", template)
        self.assertIn("unitTests.includeAndroidResources = true", template)
        self.assertNotIn(":app:testDebugUnitTest", template)

    def test_kotlin_jvm_workflow_runs_only_when_manually_dispatched(self):
        workflow = KOTLIN_WORKFLOW.read_text(encoding="utf-8")
        trigger_section = workflow.split("concurrency:", 1)[0]
        self.assertIn("workflow_dispatch:", trigger_section)
        self.assertNotIn("pull_request:", trigger_section)
        self.assertNotIn("pull_request_target:", trigger_section)
        self.assertNotIn("push:", trigger_section)
        self.assertNotIn("schedule:", trigger_section)
        self.assertNotIn("workflow_run:", trigger_section)
        lines = workflow.splitlines()
        on_index = lines.index("on:")
        event_lines = []
        for line in lines[on_index + 1:]:
            if line and not line[0].isspace() and not line.lstrip().startswith("#"):
                break
            event_lines.append(line)

        events = []
        for line in event_lines:
            match = re.fullmatch(r"  ([a-z_]+):", line)
            if match:
                events.append(match.group(1))
        self.assertEqual(["workflow_dispatch"], events)
        self.assertIn("Execute Kotlin JVM unit tests", workflow)
        self.assertIn(":app:testDebugUnitTest", workflow)

        template = TEMPLATE.read_text(encoding="utf-8")
        self.assertNotIn("kotlin_jvm_tests.yml", template)
        self.assertNotIn(".github/workflows/", template)
        self.assertIn("--contract-output", workflow)
        self.assertIn("native-sqlite-contract-v29.json", workflow)
        self.assertIn("rm -f android/app/src/main/assets/native-sqlite-contract-v29.json", workflow)

    def test_apk_workflow_packages_and_cleans_generated_runtime_contract(self):
        workflow = APK_WORKFLOW.read_text(encoding="utf-8")
        template = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("Generate canonical SQLite runtime contract and JVM fixture", workflow)
        self.assertIn("--contract-output android/app/src/main/assets/native-sqlite-contract-v29.json", workflow)
        self.assertIn("--bootstrap-output build/native-sqlite-bootstrap-v29.json", workflow)
        self.assertIn("ReiAnix-native-sqlite-bootstrap-source-${{ github.run_number }}", workflow)
        self.assertIn("rm -f android/app/src/main/assets/native-sqlite-contract-v29.json", workflow)
        self.assertIn("assets_source = overlay / \"src\" / \"main\" / \"assets\"", template)
        self.assertIn("shutil.copytree(assets_source, app / \"src\" / \"main\" / \"assets\"", template)

    def test_apk_verifier_requires_the_sqlite_contract_inside_the_real_apk(self):
        verifier = HOST_VERIFIER.read_text(encoding="utf-8")
        self.assertIn('CONTRACT_ASSET_PATH = "assets/native-sqlite-contract-v29.json"', verifier)
        self.assertIn("archive.read(CONTRACT_ASSET_PATH)", verifier)
        self.assertIn("CONTRACT_SCHEMA_VERSION = 29", verifier)
        self.assertIn("CONTRACT_SQLITE_USER_VERSION = 0", verifier)
        self.assertIn("missing_contract_keys", verifier)
        workflow = APK_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('python scripts/verify_android_host.py "$apk"', workflow)

    def test_runtime_fixture_is_generated_from_the_real_library_store_schema(self):
        with tempfile.TemporaryDirectory(prefix="reianix-contract-test-") as temporary:
            fixture_path = Path(temporary) / "canonical-schema.json"
            contract_path = Path(temporary) / "canonical-contract.json"
            bootstrap_path = Path(temporary) / "canonical-bootstrap.json"
            bootstrap_asset_path = Path(temporary) / "canonical-bootstrap-asset.json"
            result = subprocess.run(
                [
                    sys.executable, str(FIXTURE_GENERATOR),
                    "--output", str(fixture_path),
                    "--contract-output", str(contract_path),
                    "--bootstrap-output", str(bootstrap_path),
                    "--bootstrap-asset-output", str(bootstrap_asset_path),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
            contract = json.loads(contract_path.read_text(encoding="utf-8"))
            bootstrap = json.loads(bootstrap_path.read_text(encoding="utf-8"))
            bootstrap_asset = json.loads(bootstrap_asset_path.read_text(encoding="utf-8"))

        self.assertEqual(bootstrap, bootstrap_asset)
        self.assertEqual(29, bootstrap["schema_version"])
        self.assertEqual(29, bootstrap["initial_migration_version"])
        archive_content = BOOTSTRAP_ARCHIVE.read_text(encoding="ascii").strip()
        archive_bootstrap = json.loads(gzip.decompress(base64.b64decode(archive_content)))
        self.assertEqual(bootstrap, archive_bootstrap)
        self.assertEqual(0, bootstrap["sqlite_user_version"])
        self.assertEqual(fixture["tables"], bootstrap["tables"])
        self.assertEqual(fixture["statements"], bootstrap["statements"])
        self.assertEqual(
            [item["sql"] for item in fixture["triggers"] if item.get("sql")],
            bootstrap["triggers"],
        )
        self.assertGreater(len(bootstrap["statements"]), 10)
        self.assertEqual(29, fixture["schema_version"])
        self.assertEqual(0, fixture["sqlite_user_version"])
        self.assertEqual(
            {key: value for key, value in fixture.items() if key != "statements"},
            contract,
            "runtime contract and JVM fixture must be generated from the same canonical snapshot",
        )
        # LibraryStore records schema_migrations and intentionally leaves SQLite's
        # PRAGMA user_version at its default zero; these are separate contracts.
        self.assertEqual(0, fixture["sqlite_user_version"])
        expected_tables = {
            "folders", "anime", "episodes", "episode_observations", "artwork",
            "associations", "pending_matches", "account", "preferences", "genres",
            "genre_aliases", "anime_genres", "schema_migrations", "scan_runs",
        }
        self.assertEqual(expected_tables, set(fixture["tables"]))
        self.assertIn("discovered_at", {column["name"] for column in fixture["columns"]["artwork"]})
        self.assertIn("applied_at", {column["name"] for column in fixture["columns"]["schema_migrations"]})
        index_sql = {index["name"]: index["sql"] for index in fixture["indexes"]}
        self.assertIn("idx_episodes_anime_season_number_abs", index_sql)
        self.assertIn("idx_episodes_media_identity", index_sql)
        self.assertIn("WHERE media_identity IS NOT NULL", index_sql["idx_episodes_media_identity"])
        self.assertTrue(any("REFERENCES anime(id) ON DELETE CASCADE" in sql for sql in fixture["statements"]))

        structured_indexes = {index["name"]: index for index in fixture["indexes"]}
        unique_identity = structured_indexes["idx_episodes_media_identity"]
        self.assertEqual("episodes", unique_identity["table"])
        self.assertTrue(unique_identity["unique"])
        self.assertTrue(unique_identity["partial"])
        self.assertEqual(["media_identity"], [column["name"] for column in unique_identity["columns"]])
        self.assertEqual("MEDIA_IDENTITY IS NOT NULL", unique_identity["predicate"])
        compound_index = structured_indexes["idx_episodes_anime_season_number_abs"]
        self.assertEqual(
            ["anime_id", "season", "number", "absolute_number", "id"],
            [column["name"] for column in compound_index["columns"]],
        )
        self.assertEqual("anime", fixture["foreign_keys"]["episodes"][0]["table"])
        self.assertEqual("episodes", fixture["foreign_keys"]["episode_observations"][0]["table"])

        def column(table, name):
            return next(item for item in fixture["columns"][table] if item["name"] == name)

        self.assertEqual("REAL", column("episodes", "number")["type"])
        self.assertEqual("INTEGER", column("episodes", "file_size")["type"])
        self.assertEqual("REAL", column("preferences", "updated_at")["type"])
        self.assertTrue(column("artwork", "discovered_at")["not_null"])
        self.assertTrue(column("artwork", "updated_at")["not_null"])
        self.assertTrue(column("episodes", "anime_id")["not_null"])
        self.assertIn("hidden", column("episodes", "number"))
        self.assertEqual(0, column("episodes", "number")["hidden"])

    def test_models_keep_nullable_sqlite_values_and_existing_identity(self):
        models = MODELS.read_text(encoding="utf-8")
        library = LIBRARY.read_text(encoding="utf-8")

        self.assertIn("val anilistId: Long?", models)
        self.assertIn("val number: Double?", models)
        self.assertIn("val mediaIdentity: String?", models)
        self.assertIn("optionalString", library)
        self.assertIn("optionalDouble", library)
        self.assertIn("ORDER BY season ASC, number ASC, id ASC", library)


    def test_native_saf_scan_records_scan_run_in_kotlin_before_python_projection(self):
        repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
        runner = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")

        self.assertIn("suspend fun finishNativeScanRun(", repository)
        self.assertIn('"scan_runs"', repository)
        self.assertIn("finishNativeScanRun(", runner)
        self.assertIn('put("nativeScanRunOwner", persistNativeScanRun(payload))', runner)
        self.assertIn("payload.get('nativeScanRunOwner') is True", main)
        self.assertIn("catalog = await asyncio.to_thread(library.store.catalog)", main)
        self.assertIn("library.finish_ingest_documents", main)  # compatibility/recovery fallback only

    def test_native_volume_availability_is_persisted_without_duplicate_python_scan(self):
        repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        main = (ROOT / "main.py").read_text(encoding="utf-8")

        self.assertIn("suspend fun recordNativeVolumeChange(", repository)
        self.assertIn('"native_volume_states"', repository)
        self.assertIn("recordNativeVolumeChange(payload)", activity)
        self.assertIn('"nativeScanDispatchOwner", true', activity)
        self.assertIn("payload.get('nativeStorageOwner') is True", main)
        self.assertIn("payload.get('nativeScanDispatchOwner') is not True", main)

if __name__ == "__main__":
    unittest.main()
