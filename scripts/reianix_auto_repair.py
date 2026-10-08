#!/usr/bin/env python3
"""Conservative, allow-listed CI repair engine for ReiAnix.

The repair engine intentionally knows only deterministic CI mistakes. It never
uses the failure log as executable input and never edits files outside the
allow-listed target for a matched repair.
"""

from __future__ import annotations

import argparse
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class KnownFix:
    fix_id: str
    description: str
    log_markers: tuple[str, ...]
    path: str
    old: str
    new: str
    tests: tuple[tuple[str, ...], ...]
    commit_message: str


FIXES = (
    KnownFix(
        fix_id="FIX-001",
        description="Correct the deterministic Android library fixture generator path.",
        log_markers=(
            "create_library_fixture_fixture.py",
            "No such file or directory",
        ),
        path=".github/workflows/android_instrumented.yml",
        old="python scripts/create_library_fixture_fixture.py",
        new="python scripts/create_library_fixture.py",
        tests=(
            ("python", "-m", "pytest", "-q", "tests/test_build_tools.py"),
            ("python", "-m", "pytest", "-q", "tests/test_certification.py"),
        ),
        commit_message="fix(ci): correct library fixture generator path",
    ),
    KnownFix(
        fix_id="FIX-002",
        description="Remove forbidden success suppression from Android diagnostics.",
        log_markers=(
            "test_shell_diagnostics_do_not_use_true_success_suppression",
            "failure-suppression token '|| true'",
        ),
        path=".github/workflows/android_instrumented.yml",
        old='grep -h "COMMAND_EXIT=" "$out"/*.txt || true',
        new='if compgen -G "$out/*.txt" > /dev/null; then\n'
            '  grep -h "COMMAND_EXIT=" "$out"/*.txt\n'
            "fi",
        tests=(
            ("python", "-m", "pytest", "-q", "tests/test_certification.py"),
        ),
        commit_message="fix(ci): avoid silent diagnostic command suppression",
    ),
)


def matching_fixes(log_text: str) -> list[KnownFix]:
    return [fix for fix in FIXES if all(marker in log_text for marker in fix.log_markers)]


def apply_fix(root: Path, fix: KnownFix) -> bool:
    target = (root / fix.path).resolve()
    repo_root = root.resolve()
    if repo_root not in target.parents:
        raise RuntimeError(f"refusing path outside repository: {fix.path}")

    text = target.read_text(encoding="utf-8")
    occurrences = text.count(fix.old)
    if occurrences == 0:
        print(f"{fix.fix_id}: target pattern already absent; no change")
        return False
    if occurrences != 1:
        raise RuntimeError(
            f"{fix.fix_id}: expected exactly one target occurrence in {fix.path}, found {occurrences}"
        )

    target.write_text(text.replace(fix.old, fix.new), encoding="utf-8")
    print(f"{fix.fix_id}: applied to {fix.path}")
    return True


def run_test(command: tuple[str, ...], root: Path) -> None:
    print("TEST:", " ".join(command))
    subprocess.run(command, cwd=root, check=True)


def validate_fix(root: Path, fix: KnownFix) -> None:
    for command in fix.tests:
        run_test(command, root)

    subprocess.run(("git", "diff", "--check"), cwd=root, check=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True, help="Failed workflow log text file")
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument("--fix-id", help="Apply one explicit known fix instead of log matching")
    parser.add_argument("--list-fixes", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = Path(args.root).resolve()

    if args.list_fixes:
        for fix in FIXES:
            print(f"{fix.fix_id}: {fix.description}")
        return 0

    log_text = Path(args.log).read_text(encoding="utf-8", errors="replace")
    if args.fix_id:
        fixes = [fix for fix in FIXES if fix.fix_id == args.fix_id]
        if not fixes:
            raise SystemExit(f"unknown fix id: {args.fix_id}")
    else:
        fixes = matching_fixes(log_text)

    if not fixes:
        print("REIANIX_AUTO_REPAIR=NO_KNOWN_FIX")
        return 0

    if len(fixes) > 1:
        raise SystemExit(
            "multiple known fixes matched; refusing to combine repairs automatically: "
            + ", ".join(f.fix_id for f in fixes)
        )

    fix = fixes[0]
    changed = apply_fix(root, fix)
    if not changed:
        print(f"{fix.fix_id}: no repair needed")
        return 0

    try:
        validate_fix(root, fix)
    except Exception:
        subprocess.run(("git", "reset", "--hard", "HEAD"), cwd=root, check=False)
        print(f"{fix.fix_id}: validation failed; repository restored to HEAD")
        raise

    print(f"REIANIX_AUTO_REPAIR=PASS fix={fix.fix_id}")
    print(f"REIANIX_AUTO_REPAIR_COMMIT_MESSAGE={fix.commit_message}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
