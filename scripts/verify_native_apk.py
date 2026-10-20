#!/usr/bin/env python3
"""Reject APKs that still bundle the Python/Flet/Flutter runtime.

This dependency-free checker inspects ZIP members and compiled DEX content
after Gradle has produced the release artifact.
"""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path

REQUIRED_ENTRIES = ("AndroidManifest.xml", "assets/native-sqlite-contract-v29.json")
REQUIRED_DEX_MARKERS = (
    b"Lcom/reiflix/reiflix_local/ReiAnixApplication;",
    b"Lcom/reiflix/reiflix_local/MainActivity;",
    b"Lcom/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost;",
    b"Lcom/reiflix/reiflix_local/NativePlayerActivity;",
)
FORBIDDEN_DEX_MARKERS = (
    b"Lio/flutter/",
    b"Lcom/flet/",
    b"serious_python",
    b"seriousPython",
)
FORBIDDEN_EXACT_MEMBERS = {
    "assets/app.zip",
    "lib/arm64-v8a/libflutter.so",
    "lib/arm64-v8a/libapp.so",
}


def is_python_payload(name: str) -> bool:
    lowered = name.lower()
    base = lowered.rsplit("/", 1)[-1]
    return base in {"main.py", "main.pyc", "main.pyo"} or base.endswith((".py", ".pyc", ".pyo"))


def is_development_payload(name: str) -> bool:
    parts = {part.lower() for part in name.split("/")}
    return any(part in {".git", "tests", "androidtest", "test-fixtures", "docs"} for part in parts)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_native_apk.py <apk>", file=sys.stderr)
        return 2
    apk = Path(sys.argv[1])
    if not apk.is_file() or apk.stat().st_size == 0:
        print(f"APK not found or empty: {apk}", file=sys.stderr)
        return 2

    try:
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
            missing = [name for name in REQUIRED_ENTRIES if name not in names]
            if missing:
                raise ValueError("missing required APK entries: " + ", ".join(missing))

            dex_names = sorted(name for name in names if name.startswith("classes") and name.endswith(".dex"))
            if not dex_names:
                raise ValueError("APK contains no compiled DEX payload")
            dex = b"".join(archive.read(name) for name in dex_names)

            missing_markers = [marker.decode("ascii") for marker in REQUIRED_DEX_MARKERS if marker not in dex]
            if missing_markers:
                raise ValueError("native Kotlin entrypoint classes missing from DEX: " + ", ".join(missing_markers))

            forbidden_dex = [marker.decode("ascii") for marker in FORBIDDEN_DEX_MARKERS if marker.lower() in dex.lower()]
            if forbidden_dex:
                raise ValueError("Python/Flet/Flutter runtime markers found in DEX: " + ", ".join(forbidden_dex))

            forbidden_members = []
            for name in sorted(names):
                lowered = name.lower()
                if (
                    name in FORBIDDEN_EXACT_MEMBERS
                    or "flutter_assets/" in lowered
                    or "serious_python" in lowered
                    or "site-packages/" in lowered
                    or "libpython" in lowered
                    or is_python_payload(name)
                    or is_development_payload(name)
                ):
                    forbidden_members.append(name)
            if forbidden_members:
                raise ValueError("forbidden runtime/development payload members: " + ", ".join(forbidden_members[:30]))

    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"NATIVE_APK_VERIFICATION=FAIL: {exc}", file=sys.stderr)
        return 1

    print(f"NATIVE_RUNTIME=PASS apk={apk} bytes={apk.stat().st_size}")
    print("PYTHON_FLET_FLUTTER_ABSENT=PASS")
    print("ANDROID_ENTRYPOINT=PASS com.reiflix.reiflix_local.MainActivity")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
