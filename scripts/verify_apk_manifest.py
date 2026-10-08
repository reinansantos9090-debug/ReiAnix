#!/usr/bin/env python3
"""Validate the effective AndroidManifest.xml packaged in a ReiAnix APK.

AAPT2's xmltree output is diagnostic text whose indentation can vary between
build-tools releases. Keep parsing semantic instead of depending on one exact
indentation level.
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

MAIN_ACTIVITY = "com.reiflix.reiflix_local.MainActivity"
PLAYER_ACTIVITY = "com.reiflix.reiflix_local.NativePlayerActivity"
PIP_FEATURE = "android.software.picture_in_picture"
MAIN_LAUNCH_MODE_ATTRIBUTE = "android:launchMode"
MAIN_DOCUMENT_LAUNCH_MODE_ATTRIBUTE = "android:documentLaunchMode"
REQUIRED_PERMISSIONS = (
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
)

def _dump(aapt2: Path, apk: Path, command: str) -> str:
    args = [str(aapt2), "dump", command, str(apk)]
    if command == "xmltree":
        args.extend(["--file", "AndroidManifest.xml"])
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr or result.stdout or f"aapt2 dump {command} failed")
    return result.stdout

def extract_activity_block(manifest: str, activity_name: str) -> str | None:
    blocks = re.split(r"(?m)^\s*E:\s*activity\b[^\n]*\n?", manifest)
    for tail in blocks[1:]:
        block = "E: activity\n" + tail
        if activity_name in block:
            return block
    return None

def has_attribute(block: str | None, attribute: str, *accepted_values: str) -> bool:
    if not block:
        return False
    match = re.search(
        rf"android:{re.escape(attribute)}\b.*?(?=\n(?:\s*E:|\s*A:)|\Z)",
        block,
        re.S,
    )
    if not match:
        return False
    line = match.group(0)
    return any(value in line for value in accepted_values)

def has_deep_link(activity_block: str | None) -> bool:
    if not activity_block:
        return False
    return bool(
        re.search(r'android:scheme\b.*?(?:["\']reiflix["\']|reiflix)', activity_block, re.S)
        and re.search(r'android:host\b.*?(?:["\']native["\']|native)', activity_block, re.S)
    )

def has_permission(manifest: str, permission: str) -> bool:
    return permission in manifest


def has_optional_feature(manifest: str, feature_name: str) -> bool:
    blocks = re.split(r"(?m)^\s*E:\s*uses-feature\b[^\n]*\n?", manifest)
    for tail in blocks[1:]:
        block = "E: uses-feature\n" + tail
        if feature_name in block and not re.search(
            r"android:required\b.*?(?:true|0xffffffff|0x1)(?:\n|$)", block, re.S
        ):
            return True
    return False


def has_max_sdk_32_for_legacy_permission(manifest: str) -> bool:
    blocks = re.split(r"(?m)^\s*E:\s*uses-permission\b[^\n]*\n?", manifest)
    for tail in blocks[1:]:
        block = "E: uses-permission\n" + tail
        if "android.permission.READ_EXTERNAL_STORAGE" not in block:
            continue
        return bool(re.search(r"android:maxSdkVersion\b.*?(?:32|0x20|0x00000020)", block, re.S))
    return False

def has_launchable_activity(badging: str, activity_name: str) -> bool:
    return bool(re.search(
        rf"""launchable-activity:\s*name=['"]{re.escape(activity_name)}['"]""",
        badging,
    ))

def has_package_contract(
    badging: str,
    expected_version_code: int | None = None,
    expected_version_name: str = "0.2.1",
) -> bool:
    version_code_pattern = (
        rf'''versionCode=['"]{expected_version_code}['"]'''
        if expected_version_code is not None
        else r'''versionCode=['"]\d+['"]'''
    )
    return bool(re.search(
        rf"""package:\s+name=['"]com\.reiflix\.reiflix_local['"]\s+
            {version_code_pattern}\s+
            versionName=['"]{re.escape(expected_version_name)}['"]""",
        badging,
        re.X,
    ))


def has_target_sdk_36(badging: str) -> bool:
    return bool(re.search(r"""targetSdkVersion\s*[:=]\s*['"]?36['"]?""", badging))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", type=Path, default=Path(shutil.which("aapt2") or ""))
    parser.add_argument("--expected-version-code", type=int, default=None)
    parser.add_argument("--expected-version-name", default="0.2.1")
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    if not args.aapt2.is_file():
        parser.error(f"aapt2 not found: {args.aapt2}")

    try:
        manifest = _dump(args.aapt2, args.apk, "xmltree")
        badging = _dump(args.aapt2, args.apk, "badging")
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    main_block = extract_activity_block(manifest, MAIN_ACTIVITY)
    player_block = extract_activity_block(manifest, PLAYER_ACTIVITY)
    failed: list[str] = []
    if not has_package_contract(
        badging,
        expected_version_code=args.expected_version_code,
        expected_version_name=args.expected_version_name,
    ):
        failed.append("packaged APK package/versionCode/versionName contract is incorrect")
    if not has_target_sdk_36(badging):
        failed.append("packaged APK targetSdkVersion is not 36")
    if main_block is None:
        failed.append(f"MainActivity not found in packaged manifest: {MAIN_ACTIVITY}")
    else:
        checks = (
            ("launchMode=singleTask", has_attribute(main_block, "launchMode", "0x00000002", "0x2", "=2", "singleTask")),
            ("documentLaunchMode=never", has_attribute(main_block, "documentLaunchMode", "0x00000003", "0x3", "=3", "never")),
            ("exported=true", has_attribute(main_block, "exported", "0xffffffff", "true")),
            ("reiflix://native", has_deep_link(main_block)),
        )
        failed.extend(name for name, ok in checks if not ok)

    if player_block is None:
        failed.append(f"NativePlayerActivity not found in packaged manifest: {PLAYER_ACTIVITY}")
    else:
        player_checks = (
            ("NativePlayerActivity launchMode=singleTop", has_attribute(player_block, "launchMode", "0x00000001", "0x1", "=1", "singleTop")),
            ("NativePlayerActivity supportsPictureInPicture=true", has_attribute(player_block, "supportsPictureInPicture", "0xffffffff", "true")),
            ("NativePlayerActivity exported=false", has_attribute(player_block, "exported", "0x0", "false")),
        )
        failed.extend(name for name, ok in player_checks if not ok)

    if not has_launchable_activity(badging, MAIN_ACTIVITY):
        failed.append("launchable MainActivity is missing from AAPT2 badging output")
    failed.extend(permission for permission in REQUIRED_PERMISSIONS if not has_permission(manifest, permission))
    if not has_optional_feature(manifest, PIP_FEATURE):
        failed.append("optional Picture-in-Picture feature declaration is missing or required=true")
    if not has_max_sdk_32_for_legacy_permission(manifest):
        failed.append("READ_EXTERNAL_STORAGE is not visibly constrained to maxSdkVersion=32 in packaged manifest")

    if failed:
        print("Packaged manifest validation failed:", file=sys.stderr)
        for item in failed:
            print(f"  - {item}", file=sys.stderr)
        if main_block:
            print(main_block, file=sys.stderr)
        print("--- AAPT2 badging ---", file=sys.stderr)
        print(badging, file=sys.stderr)
        return 1

    print("Verified packaged AndroidManifest.xml:")
    print("  package: com.reiflix.reiflix_local")
    version_match = re.search(r'''versionCode=['"](\d+)['"]''', badging)
    actual_version_code = version_match.group(1) if version_match else "unknown"
    version_name_match = re.search(r'''versionName=['"]([^'"]+)['"]''', badging)
    actual_version_name = version_name_match.group(1) if version_name_match else "unknown"
    print(f"  versionCode: {actual_version_code}")
    print(f"  versionName: {actual_version_name}")
    print("  targetSdkVersion: 36")
    print(f"  MainActivity: {MAIN_ACTIVITY}")
    print("  launchMode: singleTask (AAPT2 ActivityInfo constant 2)")
    print("  documentLaunchMode: never (AAPT2 ActivityInfo constant 3)")
    print("  exported: true")
    print("  launchable activity: yes")
    print("  deep-link: reiflix://native")
    print("  NativePlayerActivity: launchMode=singleTop, supportsPictureInPicture=true, exported=false")
    print("  feature: android.software.picture_in_picture (required=false)")
    for permission in REQUIRED_PERMISSIONS:
        print(f"  permission: {permission}")
    print("  legacy media permission: READ_EXTERNAL_STORAGE (maxSdkVersion=32)")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
