#!/usr/bin/env python3
"""Evidence-oriented source and CI integrity audit for ReiAnix Prompt 3.

This audit is intentionally static. It never upgrades static evidence to a
runtime PASS; runtime device/emulator evidence is produced by the instrumented
workflow and its artifacts.
"""
from __future__ import annotations

import argparse
import ast
import subprocess
import sys
from pathlib import Path

REQUIRED_CLASSES = (
    "MainActivity",
    "ReiAnixComposeLibraryHost",
    "NativeMailbox",
    "NativeRequestState",
    "SafScanner",
    "MediaStoreScanner",
    "BroadStorageScanner",
    "NativeIndex",
    "NativeScanController",
    "NativePlayerActivity",
    "GoogleIdentity",
)

REQUIRED_STORAGE_STATES = (
    "UNKNOWN",
    "MEDIA_DENIED",
    "MEDIA_PARTIAL",
    "MEDIA_FULL",
    "SAF_AVAILABLE",
    "SAF_REVOKED",
    "BROAD_STORAGE_AVAILABLE",
    "BROAD_STORAGE_UNAVAILABLE",
    "READY",
)

TRACKED_RUNTIME_ROOTS = (
    "main.py",
    "core",
    "views",
    "scripts",
    "android",
    ".github/workflows",
)

KNOWN_GENERATED_PREFIXES = (
    "build/",
    ".pytest_cache/",
    ".mypy_cache/",
)


def read(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8")


def tracked_files(root: Path) -> set[str]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files"],
        capture_output=True,
        text=True,
        check=True,
    )
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def tracked_missing_from_worktree(root: Path) -> list[str]:
    missing = []
    for path in sorted(tracked_files(root)):
        if any(path.startswith(prefix) for prefix in KNOWN_GENERATED_PREFIXES):
            continue
        if not (root / path).exists():
            missing.append(path)
    return missing


def shell_sources(root: Path) -> list[Path]:
    paths = []
    for pattern in (".github/workflows/*.yml", ".github/workflows/*.yaml", "scripts/*.sh"):
        paths.extend(root.glob(pattern))
    return sorted(set(paths))


def python_sources(root: Path) -> list[Path]:
    paths = [root / "main.py"]
    paths.extend(sorted((root / "core").glob("*.py")))
    paths.extend(sorted((root / "views").glob("*.py")))
    paths.extend(sorted((root / "scripts").glob("*.py")))
    paths.extend(sorted((root / "tests").glob("*.py")))
    return [path for path in paths if path.is_file()]


def audit_python_exception_handlers(root: Path, failures: list[str]) -> None:
    for path in python_sources(root):
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except SyntaxError as exc:
            failures.append(f"Python syntax error in {path.relative_to(root)}: {exc}")
            continue
        for node in ast.walk(tree):
            if not isinstance(node, ast.ExceptHandler):
                continue
            catches_exception = node.type is not None and (
                isinstance(node.type, ast.Name) and node.type.id == "Exception"
                or isinstance(node.type, ast.Tuple)
                and any(isinstance(item, ast.Name) and item.id == "Exception" for item in node.type.elts)
            )
            if catches_exception and len(node.body) == 1 and isinstance(node.body[0], ast.Pass):
                failures.append(
                    f"silent except Exception/pass in {path.relative_to(root)}:{node.lineno}"
                )


def audit_shell_suppression(root: Path, failures: list[str]) -> None:
    for path in shell_sources(root):
        content = path.read_text(encoding="utf-8")
        if "|| true" in content:
            failures.append(f"failure-suppression token '|| true' remains in {path.relative_to(root)}")
        for lineno, line in enumerate(content.splitlines(), start=1):
            if "exit 0" in line:
                # exit 0 is not inherently a failure suppression. Record its
                # location for human review; callers must still inspect the
                # surrounding branch and command result.
                print(
                    f"RELEASE_AUDIT_NOTE: exit 0 requires branch review "
                    f"{path.relative_to(root)}:{lineno}: {line.strip()}"
                )


def audit_architecture(root: Path, failures: list[str]) -> None:
    player_view = root / "views" / "player_view.py"
    if player_view.exists():
        failures.append("forbidden competing Flet player exists: views/player_view.py")

    critical_singletons = {
        "NativePlayerActivity.kt": list((root / "android").rglob("NativePlayerActivity.kt")),
        "NativeMailbox.kt": list((root / "android").rglob("NativeMailbox.kt")),
        "NativeIndex.kt": list((root / "android").rglob("NativeIndex.kt")),
    }
    for name, matches in critical_singletons.items():
        if len(matches) != 1:
            failures.append(f"{name} singleton count is {len(matches)}: " + ", ".join(
                str(p.relative_to(root)) for p in matches
            ))

    duplicate_db = [
        path for path in tracked_files(root)
        if path.lower().endswith((".db", ".sqlite", ".sqlite3"))
    ]
    if duplicate_db:
        failures.append("tracked database file(s) unexpectedly versioned: " + ", ".join(sorted(duplicate_db)))

    storage = read(root, "core/storage_access.py")
    for state in REQUIRED_STORAGE_STATES:
        if f"{state} =" not in storage:
            failures.append(f"missing canonical storage state: {state}")

    required_files = (
        "core/storage_access.py",
        "core/android_bridge.py",
        "core/library_store.py",
        "core/library_service.py",
        "core/consumption.py",
        "core/search_engine.py",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeRequestState.kt",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/storage/NativeIndex.kt",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/SafScanner.kt",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/MediaStoreScanner.kt",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/scanner/BroadStorageScanner.kt",
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt",
    )
    for relative in required_files:
        if not (root / relative).is_file():
            failures.append(f"critical file missing: {relative}")

    manifest = read(root, "android/app/src/main/AndroidManifest.xml")
    for token in (
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        'android:name=".NativePlayerActivity"',
        'android:launchMode="singleTask"',
        'android:documentLaunchMode="never"',
    ):
        if token not in manifest:
            failures.append(f"manifest/storage/player contract missing: {token}")

    bridge = read(root, "core/android_bridge.py")
    for token in (
        "BRIDGE_PROTOCOL_VERSION",
        "request_id",
        "requeue_event_ids",
        "acknowledge",
        "content",
    ):
        if token not in bridge:
            failures.append(f"AndroidBridge contract missing: {token}")

    mailbox = read(root, "android/app/src/main/kotlin/com/reiflix/reiflix_local/bridge/NativeMailbox.kt")
    for token in ("eventId", "requestId", "AtomicMoveNotSupportedException"):
        if token not in mailbox:
            failures.append(f"NativeMailbox durability contract missing: {token}")

    player = read(root, "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
    for token in ("player_error", "player_exited", "SystemUiController", "Media3", "onResume"):
        if token not in player:
            failures.append(f"NativePlayerActivity contract missing: {token}")

    consumption = read(root, "core/consumption.py")
    if "COMPLETION_RATIO = 0.90" not in consumption:
        failures.append("central consumption completion ratio is not 0.90")

    for field in (
        "user_tags",
        "episode_type",
        "episode_title",
        "identification_source",
        "identification_confidence",
        "manual_override",
    ):
        if field not in read(root, "core/library_store.py"):
            failures.append(f"catalog field missing: {field}")

    instrumented_workflow = read(root, ".github/workflows/android_instrumented.yml")
    instrumented_script = read(root, "scripts/run_android_instrumented.sh")
    for token in (
        "workflow_dispatch:",
        "name: Android API 36",
        "api-level: 36",
        "reactivecircus/android-emulator-runner@v2",
    ):
        if token not in instrumented_workflow:
            failures.append(f"instrumented runtime workflow missing: {token}")
    for forbidden in ("push:", "pull_request:", "matrix:", "api: [30, 36]", "Android TV API 36"):
        if forbidden in instrumented_workflow:
            failures.append(f"instrumented runtime workflow still contains legacy contract: {forbidden}")
    if ":app:connectedDebugAndroidTest" not in instrumented_workflow and ":app:connectedDebugAndroidTest" not in instrumented_script:
        failures.append("instrumented runtime command missing from workflow/script")
    if "name: ReiAnix Android No-Emulator Contract Checks" in instrumented_workflow:
        failures.append("instrumented workflow still advertises no-emulator-only certification")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    root = args.root.resolve()
    failures: list[str] = []

    try:
        missing = tracked_missing_from_worktree(root)
        if missing:
            failures.extend("tracked file missing from worktree: " + path for path in missing)
    except (OSError, subprocess.CalledProcessError) as exc:
        failures.append(f"could not verify git tracked-file integrity: {exc}")

    audit_shell_suppression(root, failures)
    audit_python_exception_handlers(root, failures)
    audit_architecture(root, failures)

    build_gradle = read(root, "android/app/build.gradle.kts")
    version_code = None
    version_name = None
    import re
    m = re.search(r"versionCode\s*=\s*(\d+)", build_gradle)
    n = re.search(r'versionName\s*=\s*"([^"]+)"', build_gradle)
    if not m or not n:
        failures.append("version contract is incomplete")
    else:
        version_code = int(m.group(1))
        version_name = n.group(1)
        if version_code < 2:
            failures.append("versionCode must be >= 2 for installable updates")
        if version_name != "0.2.1":
            failures.append("versionName must be 0.2.1")

    host = read(root, "scripts/verify_android_host.py")
    for class_name in REQUIRED_CLASSES:
        if class_name not in host:
            failures.append(f"APK host gate missing {class_name}")

    if failures:
        for failure in failures:
            print("RELEASE_AUDIT_FAIL:", failure, file=sys.stderr)
        return 1

    print("RELEASE_AUDIT_VALIDATED")
    print(
        f"applicationId=com.reiflix.reiflix_local "
        f"versionCode={version_code} versionName={version_name} targetSdk=36"
    )
    print("player_view.py=ABSENT")
    print("instrumented_runtime=MANUAL_API36")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
