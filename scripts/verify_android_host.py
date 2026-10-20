#!/usr/bin/env python3
"""Fail a release build unless the real Kotlin ReiAnix Android host is in its APK.

The gate checks the native Activity, Application, Compose, scanner, mailbox,
Media3 player classes and canonical SQLite contract asset.
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

CONTRACT_ASSET_PATH = "assets/native-sqlite-contract-v29.json"
CONTRACT_SCHEMA_VERSION = 29
CONTRACT_SQLITE_USER_VERSION = 0

REQUIRED_CLASSES = (
    b"Lcom/reiflix/reiflix_local/MainActivity;",
    b"Lcom/reiflix/reiflix_local/ReiAnixApplication;",
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
            if CONTRACT_ASSET_PATH not in names:
                raise ValueError(f"APK does not contain the generated SQLite contract asset: {CONTRACT_ASSET_PATH}")
            try:
                contract = json.loads(archive.read(CONTRACT_ASSET_PATH))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise ValueError(f"APK SQLite contract asset is not valid JSON: {exc}") from exc
            if contract.get("schema_version") != CONTRACT_SCHEMA_VERSION:
                raise ValueError(
                    f"APK SQLite contract schema_version must be {CONTRACT_SCHEMA_VERSION}; "
                    f"found {contract.get('schema_version')!r}"
                )
            if contract.get("sqlite_user_version") != CONTRACT_SQLITE_USER_VERSION:
                raise ValueError(
                    f"APK SQLite contract user_version must be {CONTRACT_SQLITE_USER_VERSION}; "
                    f"found {contract.get('sqlite_user_version')!r}"
                )
            required_contract_keys = {
                "tables", "columns", "table_contract", "indexes", "foreign_keys", "triggers",
            }
            missing_contract_keys = required_contract_keys - contract.keys()
            if missing_contract_keys:
                raise ValueError(
                    "APK SQLite contract is missing metadata: " + ", ".join(sorted(missing_contract_keys))
                )
            dex = b"".join(archive.read(name) for name in dex_files)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"Invalid APK: {exc}", file=sys.stderr)
        return 1

    missing = [descriptor.decode() for descriptor in REQUIRED_CLASSES if descriptor not in dex]
    if missing:
        print("Native ReiAnix host was not packaged: native application classes are missing; refusing to publish this APK.", file=sys.stderr)
        print("Missing DEX descriptors: " + ", ".join(missing), file=sys.stderr)
        return 1
    print(
        f"Verified native ReiAnix host and SQLite contract asset (schema v{CONTRACT_SCHEMA_VERSION}) "
        f"in {args.apk} ({args.apk.stat().st_size} bytes)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
