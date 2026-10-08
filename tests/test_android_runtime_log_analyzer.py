import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "analyze_android_runtime_log.py"


class AndroidRuntimeLogAnalyzerTests(unittest.TestCase):
    def run_analyzer(self, logcat: str):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            log_path = tmp_path / "logcat.txt"
            report_path = tmp_path / "report.json"
            log_path.write_text(logcat, encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--logcat",
                    str(log_path),
                    "--output",
                    str(report_path),
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            report = json.loads(report_path.read_text(encoding="utf-8"))
            return result, report

    def test_fatal_reianix_exception_fails(self):
        result, report = self.run_analyzer(
            "10-08 16:00:00.000 E/AndroidRuntime: FATAL EXCEPTION: main\n"
            "10-08 16:00:00.001 E/AndroidRuntime: Process: com.reiflix.reiflix_local, PID: 1234\n"
            "10-08 16:00:00.002 E/AndroidRuntime: java.lang.NullPointerException\n"
        )
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["failures"][0]["type"], "FATAL_EXCEPTION")

    def test_reianix_anr_fails(self):
        result, report = self.run_analyzer(
            "10-08 16:00:00.000 E/ActivityManager: ANR in com.reiflix.reiflix_local\n"
        )
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["failures"][0]["type"], "ANR")

    def test_unrelated_system_fatal_does_not_fail(self):
        result, report = self.run_analyzer(
            "10-08 16:00:00.000 E/AndroidRuntime: FATAL EXCEPTION: main\n"
            "10-08 16:00:00.001 E/AndroidRuntime: Process: com.android.systemui, PID: 55\n"
            "10-08 16:00:00.002 E/AndroidRuntime: java.lang.IllegalStateException\n"
        )
        self.assertEqual(result.returncode, 0)
        self.assertEqual(report["status"], "PASS")

    def test_warning_is_recorded_without_failing(self):
        result, report = self.run_analyzer(
            "10-08 16:00:00.000 W/ReiAnix: SecurityException while probing optional provider\n"
            "10-08 16:00:00.001 I/ReiAnix: package=com.reiflix.reiflix_local\n"
        )
        self.assertEqual(result.returncode, 0)
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(len(report["warnings"]), 1)

    def test_missing_logcat_is_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--logcat",
                    str(Path(tmp) / "missing.txt"),
                    "--output",
                    str(Path(tmp) / "report.json"),
                ],
                text=True,
                capture_output=True,
                check=False,
            )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
