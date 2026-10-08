import ast
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class DiagnosticsTests(unittest.TestCase):
    def test_diagnostic_timeline_is_bounded_and_structured(self):
        source = (ROOT / "core" / "diagnostics.py").read_text(encoding="utf-8")
        self.assertIn("deque(maxlen=max_events)", source)
        self.assertIn("[E2E]", source)
        ast.parse(source)

    def test_main_has_required_end_to_end_checkpoints(self):
        source = (ROOT / "main.py").read_text(encoding="utf-8")
        for token in (
            "APP_START", "ACTUAL_PERMISSION_STATE", "PERMISSION_RESULT",
            "SCAN_PROGRESS", "SCAN_COMPLETED", "SCAN_FAILED",
            "PYTHON_INGEST", "CATALOG_UPDATED", "UI_REFRESHED",
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
        paths = [ROOT / "main.py"]
        paths.extend(sorted((ROOT / "core").glob("*.py")))
        paths.extend(sorted((ROOT / "views").glob("*.py")))
        pattern = re.compile(r"except\s+Exception\s*:\s*\n\s*pass")
        for path in paths:
            source = path.read_text(encoding="utf-8")
            self.assertNotRegex(source, pattern, str(path))

    def test_python_sources_parse(self):
        for path in (ROOT / "main.py", ROOT / "core/diagnostics.py", ROOT / "views/settings_view.py"):
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))


if __name__ == "__main__":
    unittest.main()
