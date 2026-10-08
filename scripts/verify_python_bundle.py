#!/usr/bin/env python3
"""Forensically verify Python identity and Back symbols inside an APK.

Flet 0.86 compiles app Python to .pyc by default and Android ships the app
payload as assets/app.zip. This verifier therefore inspects the actual stored
payload rather than relying on a successful Gradle/Flet exit code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from io import BytesIO
from pathlib import Path
from zipfile import BadZipFile, ZipFile


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--identity", type=Path, required=True)
    args = parser.parse_args()

    if not args.apk.is_file() or args.apk.stat().st_size == 0:
        print("APK missing or empty", file=sys.stderr)
        return 1
    expected = json.loads(args.identity.read_text(encoding="utf-8"))

    try:
        with ZipFile(args.apk) as apk:
            if "assets/app.zip" not in apk.namelist():
                print("APK has no assets/app.zip", file=sys.stderr)
                return 1
            app_payload = apk.read("assets/app.zip")
        with ZipFile(BytesIO(app_payload)) as app:
            names = app.namelist()
            payload = b"".join(
                app.read(name)
                for name in names
                if name.endswith(".py") or name.endswith(".pyc")
            )
            identity_candidates = [
                name for name in names
                if name.endswith("build_identity.pyc") or name.endswith("build_identity.py")
            ]
            main_candidates = [
                name for name in names
                if name == "main.pyc" or name.endswith("/main.pyc") or name == "main.py"
            ]
            if not identity_candidates:
                print("Packaged build_identity module was not found", file=sys.stderr)
                return 1
            if not main_candidates:
                print("Packaged main Python module was not found", file=sys.stderr)
                return 1
            if b"back_started" in payload:
                print("FORBIDDEN: back_started exists in packaged Python payload", file=sys.stderr)
                return 1
            if b"back_state" not in payload:
                print("REQUIRED: back_state was not found in packaged Python payload", file=sys.stderr)
                return 1
            identity_bytes = b"".join(app.read(name) for name in identity_candidates)

    except (OSError, BadZipFile, KeyError) as exc:
        print(f"Unable to inspect APK Python payload: {exc}", file=sys.stderr)
        return 1

    checks = {
        "commit": expected["commit"].encode("ascii"),
        "python_bundle_fingerprint": expected["python_bundle_fingerprint"].encode("ascii"),
        "version": expected["version"].encode("utf-8"),
        "python_runtime": expected["python_runtime"].encode("utf-8"),
        "flet_version": expected["flet_version"].encode("utf-8"),
    }
    missing = [name for name, value in checks.items() if value not in identity_bytes]
    if missing:
        print("Build identity values missing from packaged build_identity bytecode: " + ", ".join(missing), file=sys.stderr)
        return 1

    apk_sha = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    print("PYTHON_BUNDLE_VERIFICATION=PASS")
    print(f"APK_SHA256={apk_sha}")
    print(f"GIT_COMMIT={expected['commit']}")
    print(f"PYTHON_BUNDLE_FINGERPRINT={expected['python_bundle_fingerprint']}")
    print(f"PYTHON_RUNTIME={expected['python_runtime']}")
    print(f"FLET_VERSION={expected['flet_version']}")
    print("BACK_STARTED=ABSENT")
    print("BACK_STATE=PRESENT")
    print("IDENTITY_MODULE=PACKAGED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
