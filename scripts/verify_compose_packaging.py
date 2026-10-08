#!/usr/bin/env python3
"""Verify the earlier validation stage 01 Compose foundation survives the official APK build."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

REQUIRED = {
    "ReiAnixComposeThemeKt.class":
        b"Lcom/reiflix/reiflix_local/ui/theme/ReiAnixComposeThemeKt;",
    "ReiAnixComposeRootKt.class":
        b"Lcom/reiflix/reiflix_local/ui/ReiAnixComposeRootKt;",
    "ReiAnixViewModel.class":
        b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixViewModel;",
    "ReiAnixSettingsViewModel.class":
        b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel;",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--classes-root", type=Path, required=True)
    args = parser.parse_args()

    if not args.apk.is_file() or args.apk.stat().st_size == 0:
        print(f"APK not found or empty: {args.apk}", file=sys.stderr)
        return 2
    if not args.classes_root.is_dir():
        print(f"Compiled classes root not found: {args.classes_root}", file=sys.stderr)
        return 2

    missing_classes = []
    for filename in REQUIRED:
        matches = list(args.classes_root.rglob(filename))
        if not matches:
            missing_classes.append(filename)
        else:
            print(f"COMPOSE_COMPILED_CLASS={matches[0]}")
    if missing_classes:
        print("Compose Kotlin classes were not produced by the generated Android module:", file=sys.stderr)
        print(", ".join(missing_classes), file=sys.stderr)
        return 1

    with zipfile.ZipFile(args.apk) as archive:
        dex_names = sorted(
            name for name in archive.namelist()
            if name.startswith("classes") and name.endswith(".dex")
        )
        dex = b"".join(archive.read(name) for name in dex_names)

    missing_dex = [
        descriptor.decode()
        for descriptor in REQUIRED.values()
        if descriptor not in dex
    ]
    if missing_dex:
        print("Compose Kotlin classes were compiled but did not survive into APK DEX:", file=sys.stderr)
        print(", ".join(missing_dex), file=sys.stderr)
        return 1

    print("COMPOSE_COMPILED_CLASSES=PASS")
    print("COMPOSE_APK_DEX=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
