from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
VIEWMODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
STORAGE = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt"
WORKFLOW = ROOT / ".github/workflows/kotlin_jvm_tests.yml"


class NativeLibraryConsumerIntegrationTest(unittest.TestCase):
    def test_storage_route_collects_and_refreshes_native_sqlite_folder_state(self):
        vm = VIEWMODEL.read_text(encoding="utf-8")
        storage = STORAGE.read_text(encoding="utf-8")

        self.assertIn("val nativeFoldersState: StateFlow<ReiAnixNativeFoldersUiState>", vm)
        self.assertIn("ReiAnixNativeFolderUiModel(name = folder.name, path = folder.path)", vm)
        self.assertIn("fun refreshNativeFolders()", vm)
        self.assertIn("queryNativeFolders()", vm)
        self.assertIn("viewModel.nativeFoldersState.collectAsStateWithLifecycle()", storage)
        self.assertIn("LaunchedEffect(viewModel)", storage)
        self.assertIn("viewModel.refreshNativeFolders()", storage)
        self.assertIn("NativeSqliteFoldersSection(", storage)

    def test_native_folder_consumer_keeps_empty_and_failure_distinct(self):
        storage = STORAGE.read_text(encoding="utf-8")

        for status in ("READY", "EMPTY", "NOT_FOUND", "ERROR"):
            self.assertIn(f"ReiAnixNativeFoldersStatus.{status}", storage)
        self.assertIn("ReiAnixNativeFoldersStatus.LOADING", storage)
        self.assertIn("Os registros configurados continuam preservados", storage)

    def test_kotlin_jvm_workflow_remains_manual_and_pull_request_only(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        trigger_section = workflow.split("concurrency:", 1)[0]
        self.assertIn("workflow_dispatch:", trigger_section)
        self.assertIn("pull_request:", trigger_section)
        self.assertNotIn("push:", trigger_section)
        self.assertNotIn("workflow_run:", trigger_section)


if __name__ == "__main__":
    unittest.main()
