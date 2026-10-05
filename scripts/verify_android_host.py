#!/usr/bin/env python3
"""Fail a release build unless the ReiAnix native Android host is in its APK.

A successful Flet/Flutter build alone is insufficient: a stock Flet client does
not contain the SAF, mailbox, and Media3 classes maintained in this repository.
This check is deliberately dependency-free so it can run in GitHub Actions.
"""
from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

REQUIRED_CLASSES = (
    b"Lcom/reiflix/reiflix_local/MainActivity;",
    b"Lcom/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost;",
    b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel;",
    b"Lcom/reiflix/reiflix_local/bridge/NativeMailbox;",
    b"Lcom/reiflix/reiflix_local/bridge/NativeRequestState;",
    b"Lcom/reiflix/reiflix_local/scanner/SafScanner;",
    b"Lcom/reiflix/reiflix_local/scanner/MediaStoreScanner;",
    b"Lcom/reiflix/reiflix_local/scanner/BroadStorageScanner;",
    b"Lcom/reiflix/reiflix_local/storage/NativeIndex;",
    b"Lcom/reiflix/reiflix_local/scanner/NativeScanController;",
    b"Lcom/reiflix/reiflix_local/NativePlayerActivity;",
    b"Lcom/reiflix/reiflix_local/player/NativePlayerRequest;",
    b"Lcom/reiflix/reiflix_local/bridge/GoogleIdentity;",
    b"Lcom/reiflix/reiflix_local/ui/theme/ReiAnixComposeThemeKt;",
    b"Lcom/reiflix/reiflix_local/ui/ReiAnixComposeRootKt;",
    b"Lcom/reiflix/reiflix_local/viewmodel/ReiAnixViewModel;",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    if args.apk.stat().st_size == 0:
        parser.error("APK is empty")

    try:
        with zipfile.ZipFile(args.apk) as archive:
            names = set(archive.namelist())
            dex_files = sorted(name for name in names if name.startswith("classes") and name.endswith(".dex"))
            if "AndroidManifest.xml" not in names or not dex_files:
                raise ValueError("APK does not contain AndroidManifest.xml and DEX payload")
            dex = b"".join(archive.read(name) for name in dex_files)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"Invalid APK: {exc}", file=sys.stderr)
        return 1

    missing = [descriptor.decode() for descriptor in REQUIRED_CLASSES if descriptor not in dex]
    if missing:
        print("Native ReiAnix host was not packaged; refusing to publish a stock Flet APK.", file=sys.stderr)
        print("Missing DEX descriptors: " + ", ".join(missing), file=sys.stderr)
        return 1
    print(f"Verified native ReiAnix host in {args.apk} ({args.apk.stat().st_size} bytes).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
