import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from io import BytesIO
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GENERATE = ROOT / "scripts" / "generate_build_identity.py"
VERIFY = ROOT / "scripts" / "verify_python_bundle.py"


class BuildIdentityTests(unittest.TestCase):
    def test_build_identity_is_unbuilt_or_a_valid_generated_identity(self):
        source = (ROOT / "core" / "build_identity.py").read_text(encoding="utf-8")
        if 'BUILD_COMMIT = "UNBUILT"' not in source:
            import re
            self.assertRegex(source, r"BUILD_COMMIT = ['\"][0-9a-f]{40}['\"]")
            self.assertRegex(source, r"PYTHON_BUNDLE_FINGERPRINT = ['\"][0-9a-f]{64}['\"]")
        self.assertRegex(source, r"FLET_VERSION = ['\"]0\.86\.5['\"]")

    def test_generator_is_clean_tree_guarded_and_deterministic_for_source_set(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / "core").mkdir()
            (root / "views").mkdir()
            (root / "main.py").write_text("print('x')\n", encoding="utf-8")
            (root / "core" / "sample.py").write_text("VALUE = 1\n", encoding="utf-8")
            (root / "views" / "sample.py").write_text("VALUE = 2\n", encoding="utf-8")
            (root / "app_config.py").write_text("GOOGLE = ''\n", encoding="utf-8")
            (root / "core" / "build_identity.py").write_text("BUILD_COMMIT = 'UNBUILT'\n", encoding="utf-8")
            (root / "pyproject.toml").write_text('[project]\nversion = "0.2.1"\n', encoding="utf-8")
            subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "test"], cwd=root, check=True)
            temp_head = subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=root, text=True
            ).strip()
            env = os.environ.copy()
            env["GITHUB_SHA"] = temp_head
            subprocess.run(
                [sys.executable, str(GENERATE), "--root", str(root),
                 "--python-output", str(root / "core" / "build_identity.py"),
                 "--json-output", str(root / "build-identity.json")],
                cwd=ROOT, env=env, check=True,
            )
            manifest = json.loads((root / "build-identity.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["version"], "0.2.1")
            self.assertEqual(len(manifest["source_files"]), 3)
            self.assertNotIn("app_config.py", manifest["source_files"])
            self.assertNotIn("core/build_identity.py", manifest["source_files"])

    def test_verifier_rejects_legacy_back_symbol_and_accepts_current_symbol(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            apk = root / "test.apk"
            identity = root / "identity.json"
            identity.write_text(json.dumps({
                "commit": "a" * 40,
                "python_bundle_fingerprint": "b" * 64,
                "version": "0.2.1",
                "python_runtime": "3.12",
                "flet_version": "0.86.5",
            }), encoding="utf-8")
            build_identity = (
                b"BUILD_COMMIT='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'"
                b" PYTHON_BUNDLE_FINGERPRINT='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'"
                b" BUILD_VERSION='0.2.1' PYTHON_RUNTIME='3.12' FLET_VERSION='0.86.5'"
            )
            app_zip = BytesIO()
            with zipfile.ZipFile(app_zip, "w") as app:
                app.writestr("main.pyc", b"back_state")
                app.writestr("core/build_identity.pyc", build_identity)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/app.zip", app_zip.getvalue())
            result = subprocess.run(
                [sys.executable, str(VERIFY), str(apk), "--identity", str(identity)],
                cwd=ROOT, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("BACK_STARTED=ABSENT", result.stdout)

            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/app.zip", BytesIO(b"").getvalue())
            bad_payload = BytesIO()
            with zipfile.ZipFile(bad_payload, "w") as app:
                app.writestr("main.pyc", b"back_state back_started")
                app.writestr("core/build_identity.pyc", build_identity)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/app.zip", bad_payload.getvalue())
            result = subprocess.run(
                [sys.executable, str(VERIFY), str(apk), "--identity", str(identity)],
                cwd=ROOT, capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("back_started", result.stderr)
