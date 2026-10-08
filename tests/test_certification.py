import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class CertificationTests(unittest.TestCase):
    def test_player_view_remains_absent(self):
        self.assertFalse((ROOT / "views" / "player_view.py").exists())

    def test_canonical_storage_states_are_present_once(self):
        source = (ROOT / "core" / "storage_access.py").read_text(encoding="utf-8")
        for state in (
            "UNKNOWN", "MEDIA_DENIED", "MEDIA_PARTIAL", "MEDIA_FULL",
            "SAF_AVAILABLE", "SAF_REVOKED",
            "BROAD_STORAGE_AVAILABLE", "BROAD_STORAGE_UNAVAILABLE", "READY",
        ):
            self.assertEqual(1, len(re.findall(rf"^\s+{state}\s*=", source, re.M)))

    def test_runtime_matrix_executes_real_connected_instrumentation(self):
        workflow = (ROOT / ".github/workflows/android_instrumented.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("name: Android API 36", workflow)
        self.assertIn("api-level: 36", workflow)
        self.assertIn("reactivecircus/android-emulator-runner@v2", workflow)
        script = (ROOT / "scripts" / "run_android_instrumented.sh").read_text(encoding="utf-8")
        self.assertIn(":app:connectedDebugAndroidTest", script)
        self.assertNotIn("push:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("matrix:", workflow)
        self.assertNotIn("api: [30, 36]", workflow)
        self.assertNotIn("Android TV API 36", workflow)
        self.assertNotIn("No-Emulator Contract Checks", workflow)

    def test_shell_diagnostics_do_not_use_true_success_suppression(self):
        paths = [ROOT / ".github/workflows/android_instrumented.yml", ROOT / ".github/workflows/build_apk.yml"]
        paths.extend(sorted((ROOT / "scripts").glob("*.sh")))
        for path in paths:
            self.assertNotIn("|| true", path.read_text(encoding="utf-8"), str(path))

    def test_release_audit_accepts_runtime_command_from_executable_script(self):
        audit = (ROOT / "scripts/audit_release.py").read_text(encoding="utf-8")
        self.assertIn("scripts/run_android_instrumented.sh", audit)
        self.assertIn("instrumented runtime command missing from workflow/script", audit)
        self.assertIn("instrumented_script", audit)

    def test_audit_script_checks_runtime_matrix_and_exception_suppression(self):
        source = (ROOT / "scripts/audit_release.py").read_text(encoding="utf-8")
        self.assertIn("reactivecircus/android-emulator-runner@v2", source)
        self.assertIn("silent except Exception/pass", source)
        self.assertIn("failure-suppression token '|| true'", source)
        self.assertIn("tracked_missing_from_worktree", source)


if __name__ == "__main__":
    unittest.main()
