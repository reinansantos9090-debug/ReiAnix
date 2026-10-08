import tempfile
import unittest
from pathlib import Path

from scripts.reianix_auto_repair import FIXES, apply_fix, matching_fixes


class AutoRepairEngineTests(unittest.TestCase):
    def test_only_allowlisted_fixes_exist(self):
        self.assertEqual(
            [fix.fix_id for fix in FIXES],
            ["FIX-001", "FIX-003", "FIX-002"],
        )

    def test_fixture_path_fix_requires_both_log_markers(self):
        log = (
            "python: can't open file "
            "scripts/create_library_fixture_fixture.py: [Errno 2] "
            "No such file or directory"
        )
        self.assertEqual([f.fix_id for f in matching_fixes(log)], ["FIX-001"])
        self.assertEqual(matching_fixes("create_library_fixture_fixture.py"), [])

    def test_diagnostic_suppression_fix_requires_certification_failure(self):
        log = (
            "tests/test_certification.py::CertificationTests::"
            "test_shell_diagnostics_do_not_use_true_success_suppression"
            " - failure-suppression token '|| true'"
        )
        self.assertEqual([f.fix_id for f in matching_fixes(log)], ["FIX-002"])

    def test_unrelated_failure_does_not_match(self):
        log = "AssertionError: synopsis mismatch in tests/test_metadata_engine.py"
        self.assertEqual(matching_fixes(log), [])

    def test_successful_flet_build_watcher_failure_matches_build_fix(self):
        log = (
            "Caught exception: Already watching path: "
            "/home/runner/work/ReiAnix/ReiAnix/build/flutter/android"
            "\nBuilt .apk for Android"
        )
        self.assertEqual([f.fix_id for f in matching_fixes(log)], ["FIX-003"])

    def test_fixture_fix_changes_exactly_one_occurrence(self):
        fix = FIXES[0]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / fix.path
            target.parent.mkdir(parents=True)
            target.write_text(fix.old + "\n", encoding="utf-8")
            self.assertTrue(apply_fix(root, fix))
            self.assertEqual(target.read_text(encoding="utf-8"), fix.new + "\n")

    def test_fixture_fix_refuses_ambiguous_duplicate_occurrences(self):
        fix = FIXES[0]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / fix.path
            target.parent.mkdir(parents=True)
            target.write_text(fix.old + "\n" + fix.old + "\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                apply_fix(root, fix)

    def test_diagnostic_fix_is_exact_and_scoped(self):
        fix = FIXES[1]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / fix.path
            target.parent.mkdir(parents=True)
            target.write_text(
                'echo "before"\n'
                + fix.old
                + '\necho "after"\n',
                encoding="utf-8",
            )
            self.assertTrue(apply_fix(root, fix))
            result = target.read_text(encoding="utf-8")
            self.assertIn(fix.new, result)
            self.assertNotIn("|| true", result)
            self.assertIn('echo "before"', result)
            self.assertIn('echo "after"', result)

    def test_workflow_is_privileged_only_for_trusted_main_failures(self):
        workflow = (
            Path(__file__).resolve().parents[1]
            / ".github"
            / "workflows"
            / "reianix_auto_repair.yml"
        ).read_text(encoding="utf-8")
        self.assertNotIn("workflow_run:", workflow)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("Resolve latest failed run", workflow)
        self.assertIn('"Build ReiAnix Android APK"', workflow)
        self.assertIn('"ReiAnix Android Instrumented Runtime Matrix"', workflow)
        self.assertNotIn("inputs:", workflow)
        self.assertIn("Build ReiAnix Android APK", workflow)
        self.assertIn("ReiAnix Android Instrumented Runtime Matrix", workflow)
        self.assertIn("contents: write", workflow)
        self.assertIn("actions: read", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertNotIn("pull_request_target:", workflow)

    def test_workflow_commits_only_allowlisted_android_workflow_file(self):
        workflow = (
            Path(__file__).resolve().parents[1]
            / ".github"
            / "workflows"
            / "reianix_auto_repair.yml"
        ).read_text(encoding="utf-8")
        self.assertIn('git config user.name "github-actions[bot]"', workflow)
        self.assertIn('git config user.email "41898282+github-actions[bot]@users.noreply.github.com"', workflow)
        self.assertIn("git add .github/workflows/android_instrumented.yml .github/workflows/build_apk.yml", workflow)
        self.assertIn("git diff --cached --check", workflow)
        self.assertIn("git push origin HEAD:main", workflow)



if __name__ == "__main__":
    unittest.main()
