import ast
import re
import unittest
from pathlib import Path
from tests.native_main_contract import native_main_source

ROOT = Path(__file__).resolve().parents[1]


class DiagnosticsTests(unittest.TestCase):
    def test_diagnostic_timeline_is_bounded_and_structured(self):
        source = (ROOT / "core" / "diagnostics.py").read_text(encoding="utf-8")
        self.assertIn("deque(maxlen=max_events)", source)
        self.assertIn("[E2E]", source)
        ast.parse(source)

    def test_main_has_required_end_to_end_checkpoints(self):
        source = native_main_source()
        for token in (
            "MainEntryPoint", "ReiAnixAppBootstrap", "MainActivity",
            "ReiAnixDiagnosticTimeline", "NativeScanController",
            "NativePlayerActivity", "ReiAnixComposeLibraryHost",
        ):
            self.assertIn(token, source)

    def test_native_lifecycle_publishes_checkpoints(self):
        source = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('"event", "APP_START"', source)
        self.assertIn('"event", "ON_RESUME"', source)
        self.assertIn('"event", "PERMISSION_RESULT"', source)

    def test_apk_host_requires_all_critical_classes(self):
        source = (ROOT / "scripts/verify_android_host.py").read_text(encoding="utf-8")
        for name in (
            "MainActivity", "NativeMailbox", "NativeRequestState", "SafScanner",
            "MediaStoreScanner", "BroadStorageScanner", "NativeIndex",
            "NativeScanController", "NativePlayerActivity", "GoogleIdentity",
        ):
            self.assertIn(name, source)

    def test_no_shell_success_suppression_in_workflow(self):
        source = (ROOT / ".github/workflows/build_apk.yml").read_text(encoding="utf-8")
        self.assertNotIn("|| true", source)

    def test_active_python_sources_do_not_silently_drop_exception_failures(self):
        paths = []
        paths.extend(sorted((ROOT / "core").glob("*.py")))
        paths.extend(sorted((ROOT / "views").glob("*.py")))
        pattern = re.compile(r"except\s+Exception\s*:\s*\n\s*pass")
        for path in paths:
            source = path.read_text(encoding="utf-8")
            self.assertNotRegex(source, pattern, str(path))

    def test_python_sources_parse(self):
        for path in (ROOT / "core/diagnostics.py", ROOT / "views/settings_view.py"):
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))


if __name__ == "__main__":
    unittest.main()
