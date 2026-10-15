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
        self.assertIn("scanMutationMutex.withLock", service)
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

    def test_migration_document_records_safe_reconciliation_and_remaining_hybrid_edges(self):
        migration = (ROOT / "docs/KOTLIN_LIBRARY_INDEXING_MIGRATION.md").read_text(encoding="utf-8")
        roadmap = (ROOT / "docs/KOTLIN_MIGRATION_ROADMAP.md").read_text(encoding="utf-8")

        self.assertIn("nativeLibraryIndexOwner", migration)
        self.assertIn("mayReconcile", migration)
        self.assertIn("core/scan_coordinator.py", migration)
        self.assertIn("ComposeLibraryBridge", migration)
        self.assertIn("Prompt 05/29", roadmap)
        self.assertIn("ReiAnixLibraryService.kt", roadmap)


if __name__ == "__main__":
    unittest.main()
