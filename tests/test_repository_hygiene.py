import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class TestRepositoryHygiene(unittest.TestCase):
    def test_no_generated_or_debug_artifacts_are_checked_in(self):
        forbidden_suffixes = {".pyc", ".log", ".tmp", ".partial"}
        forbidden_names = {"reiflix-debug.apk", "debug-output.json"}
        offenders = []
        for path in ROOT.rglob("*"):
            if ".git" in path.parts or path.is_dir():
                continue
            # Ignore CI/runtime-generated directories. This test is about
            # repository contents, not artifacts produced during the suite itself.
            if "build" in path.parts or "__pycache__" in path.parts:
                continue
            if path.suffix.casefold() in forbidden_suffixes or path.name in forbidden_names:
                offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual([], offenders)

    def test_renamed_validation_files_exist(self):
        expected = [
            ROOT / "tests/test_discovery_engine.py",
            ROOT / "tests/test_ui_states.py",
            ROOT / "tests/test_e2e_diagnostics.py",
            ROOT / "tests/test_scalability.py",
            ROOT / "android/app/src/androidTest/kotlin/com/reiflix/reiflix_local/DeviceFlowInstrumentedTest.kt",
        ]
        self.assertTrue(all(path.is_file() for path in expected))


if __name__ == "__main__":
    unittest.main()
