from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"


class NativeLibraryIndexingMigrationContractTests(unittest.TestCase):
    def test_scan_mutations_share_the_canonical_native_library_service(self):
        dependencies = (KOTLIN / "ReiAnixAppDependencies.kt").read_text(encoding="utf-8")
        service = (KOTLIN / "data/sqlite/ReiAnixLibraryService.kt").read_text(encoding="utf-8")
        runner = (KOTLIN / "scanner/NativeScanRunner.kt").read_text(encoding="utf-8")

        self.assertIn("ReiAnixLibraryService(sqliteLibraryRepository)", dependencies)
        self.assertIn("repository.indexDiscoveredMedia(snapshot, onTransactionOutcome)", service)
        self.assertIn("repository.finishNativeScanRun(", service)
        self.assertIn("scanMutationMutex.lock()", service)
        self.assertIn("scanMutationMutex.unlock()", service)
        self.assertIn("serializeMutation {", service)
        self.assertIn(".libraryService.indexDiscoveredMedia(snapshot)", runner)
        self.assertIn(".libraryService.finishNativeScanRun(", runner)
        self.assertNotIn("sqliteLibraryRepository.indexDiscoveredMedia(snapshot)", runner)
        self.assertNotIn("sqliteLibraryRepository.finishNativeScanRun(", runner)

    def test_native_identity_and_volume_writes_use_the_same_service_boundary(self):
        activity = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        view_model = (KOTLIN / "viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        service = (KOTLIN / "data/sqlite/ReiAnixLibraryService.kt").read_text(encoding="utf-8")

        self.assertIn("appDependencies.libraryService.recordNativeVolumeChange(payload)", activity)
        self.assertIn("nativeLibraryService.reconcileSafDocumentIdentityChange(", view_model)
        self.assertIn("repository.recordNativeVolumeChange(payload)", service)
        self.assertIn("repository.reconcileSafDocumentIdentityChange(", service)

    def test_compose_catalog_and_paging_read_canonical_sqlite(self):
        view_model = (KOTLIN / "viewmodel/ReiAnixLibraryViewModel.kt").read_text(encoding="utf-8")
        repository = (KOTLIN / "data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
        query_service = (KOTLIN / "data/sqlite/ReiAnixLibraryQueryService.kt").read_text(encoding="utf-8")
        projector = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixNativeLibraryCatalogProjector.kt").read_text(encoding="utf-8")

        self.assertIn("nativeLibraryRepository.loadLibraryCatalogRows()", view_model)
        self.assertIn("NativeScanController.progress", view_model)
        self.assertIn("val nativeScans = scans.values", view_model)
        self.assertIn("val activeNativeScan = nativeScans", view_model)
        self.assertIn("val latestNativeScan = nativeScans", view_model)
        self.assertNotIn("val safScans = scans.values.filter", view_model)
        self.assertIn("scanState = nativeScanState", view_model)
        self.assertIn("lastScanStatus = nativeLastScanStatus", view_model)
        self.assertIn("loadNativeLibraryPage(", view_model)
        self.assertIn("nativePagedLibraryState.asStateFlow()", view_model)
        self.assertIn("libraryOnly = true", view_model)
        self.assertIn("nativeLibraryQueries.searchAnime(", view_model)
        self.assertNotIn("repository.loadLibraryPage(", view_model)
        self.assertNotIn("repository.pagedLibraryState", view_model)
        self.assertIn("SELECT * FROM anime ORDER BY added_at DESC", repository)
        self.assertIn("database.readConsistentSnapshot { connection ->", repository)
        self.assertIn("episode_observations", repository)
        self.assertIn("episodeArtworkByAnime", repository)
        self.assertIn("totalCount = totalCount", repository)
        database = (KOTLIN / "data/sqlite/ReiAnixSqliteDatabase.kt").read_text(encoding="utf-8")
        self.assertIn("readConsistentSnapshot", database)
        self.assertIn("beginTransactionReadOnly()", database)
        self.assertIn("val totalCount: Int = 0", repository)
        self.assertIn("libraryOnly: Boolean = false", repository)
        self.assertIn("libraryOnly = libraryOnly", query_service)
        self.assertIn("LibraryUiMappers.anime(projection)", projector)
        self.assertIn("playback_target_episode_id", projector)

    def test_mediastore_indexes_only_authorized_saf_scopes_and_preserves_existing_saf_locations(self):
        runner = (KOTLIN / "scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        media_store = (KOTLIN / "scanner/MediaStoreScanner.kt").read_text(encoding="utf-8")
        native_index = (KOTLIN / "storage/NativeIndex.kt").read_text(encoding="utf-8")
        repository = (KOTLIN / "data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")

        # The selected roots come from the persisted canonical folders table and must
        # still be authorized/readable SAF trees before they can bound MediaStore results.
        self.assertIn("sqliteLibraryRepository.listFolders()", runner)
        self.assertIn('folder.kind.equals("saf", ignoreCase = true)', runner)
        self.assertIn("SafScanner.inspectTree(context, treeUri, requirePersisted = true)", runner)
        self.assertIn("libraryScopes = libraryScopes", runner)
        self.assertIn("matchingLibraryScope(authorizedScopes", media_store)
        self.assertIn("maxByOrNull { it.second }", media_store)
        self.assertIn('put("scopeVerified", true)', media_store)

        # Native staging, reuse, and transaction receipts use exactly one source key.
        self.assertIn("NativeIndex.SOURCE_MEDIASTORE", media_store)
        self.assertIn("NativeIndex.SOURCE_MEDIASTORE", runner)
        self.assertIn("libraryScopeSignature", media_store)
        self.assertIn('scope.optString("nativeCatalogWriteOutcome") != "COMMITTED"', native_index)
        self.assertIn('meta.optString("libraryScopeSignature") == libraryScopeSignature', native_index)
        self.assertIn("indexMediaStoreSnapshots(", runner)
        self.assertIn(".libraryService.indexDiscoveredMedia(snapshot)", runner)

        # A complete catalog reconciliation is only possible for a complete authorized scope;
        # the source's global/partial result cannot mark SAF-owned rows missing or rewrite their
        # durable tree URI and tree-relative path as a volume-relative MediaStore path.
        self.assertIn("complete = complete", runner)
        self.assertIn("authorizationValid = canReadMediaStore && libraryScopes.isNotEmpty()", runner)
        self.assertIn("val preserveSafLocation = snapshot.source == \"mediastore\"", repository)
        self.assertIn("if (!preserveSafLocation && current.path != item.location)", repository)
        self.assertIn("if (!preserveSafLocation)", repository)
        self.assertIn("SAFE_LIBRARY_SCOPE_REQUIRES_API_29", media_store)

    def test_migration_document_records_safe_reconciliation_and_remaining_hybrid_edges(self):
        migration = (ROOT / "docs/KOTLIN_LIBRARY_INDEXING_MIGRATION.md").read_text(encoding="utf-8")
        roadmap = (ROOT / "docs/KOTLIN_MIGRATION_ROADMAP.md").read_text(encoding="utf-8")

        self.assertIn("nativeLibraryIndexOwner", migration)
        self.assertIn("mayReconcile", migration)
        runner = (KOTLIN / "scanner/NativeScanRunner.kt").read_text(encoding="utf-8")
        self.assertIn("discoveredMediaFromSnapshot(documents, scopeRef)", runner)
        self.assertIn("preserving the receipt without transferring writes to Python", runner)
        self.assertIn('.put("documents", JSONArray())', runner)
        self.assertNotIn('.put("documents", documents)', runner)
        self.assertIn('== "NATIVE_POSITIVE_RECOVERY_PENDING"', runner)
        self.assertIn("if (!nativePositiveRecoveryPending)", runner)
        self.assertNotIn("LEGACY_INGESTION_REQUIRED", runner)
        self.assertIn("core/scan_coordinator.py", migration)
        self.assertIn("ComposeLibraryBridge", migration)
        self.assertIn("Prompt 05/29", roadmap)
        self.assertIn("ReiAnixLibraryService.kt", roadmap)


if __name__ == "__main__":
    unittest.main()
