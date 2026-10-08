#!/usr/bin/env python3
"""Analyze Android runtime Logcat for ReiAnix-specific fatal failures.

This is intentionally conservative: the CI should fail only for high-confidence
runtime failures (ANR, fatal AndroidRuntime exception, fatal signal, OOM) that
are associated with the ReiAnix package/process. Other emulator/system errors
are retained as context but do not fail the job.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


PACKAGE_DEFAULT = "com.reiflix.reiflix_local"

HIGH_CONFIDENCE_PATTERNS = (
    ("ANR", re.compile(r"ANR in\s+(?:" + re.escape(PACKAGE_DEFAULT) + r"|[^\s]+)")),
    ("FATAL_EXCEPTION", re.compile(r"FATAL EXCEPTION")),
    ("FATAL_SIGNAL", re.compile(r"Fatal signal\s+\d+")),
    ("OUT_OF_MEMORY", re.compile(r"OutOfMemoryError")),
)

WARNING_PATTERNS = (
    re.compile(r"SecurityException"),
    re.compile(r"FileNotFoundException"),
    re.compile(r"NullPointerException"),
    re.compile(r"IllegalStateException"),
    re.compile(r"IOException"),
    re.compile(r"Traceback \(most recent call last\)"),
)


def package_near(lines: list[str], index: int, package: str, radius: int = 35) -> bool:
    start = max(0, index - radius)
    end = min(len(lines), index + radius + 1)
    window = "\n".join(lines[start:end])
    return package in window or f"Process: {package}" in window


def classify(lines: list[str], package: str) -> tuple[list[dict], list[dict]]:
    failures: list[dict] = []
    warnings: list[dict] = []

    patterns = list(HIGH_CONFIDENCE_PATTERNS)
    for index, line in enumerate(lines):
        for kind, pattern in patterns:
            if not pattern.search(line):
                continue
            if not package_near(lines, index, package):
                continue
            failures.append(
                {
                    "type": kind,
                    "line": index + 1,
                    "message": line.strip(),
                }
            )
            break

    for index, line in enumerate(lines):
        if not any(pattern.search(line) for pattern in WARNING_PATTERNS):
            continue
        if package_near(lines, index, package):
            warnings.append(
                {
                    "line": index + 1,
                    "message": line.strip(),
                }
            )

    # Deduplicate repeated stacktrace markers while preserving evidence.
    def dedupe(items: list[dict]) -> list[dict]:
        seen: set[tuple] = set()
        result = []
        for item in items:
            key = (item.get("type"), item["message"])
            if key in seen:
                continue
            seen.add(key)
            result.append(item)
        return result

    return dedupe(failures), dedupe(warnings)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logcat", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--package", default=PACKAGE_DEFAULT)
    args = parser.parse_args()

    logcat_path = Path(args.logcat)
    output_path = Path(args.output)

    if not logcat_path.is_file():
        print(f"RUNTIME_LOGCAT_ANALYSIS=UNAVAILABLE file={logcat_path}")
        return 2

    text = logcat_path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    failures, warnings = classify(lines, args.package)

    result = {
        "package": args.package,
        "logcat": str(logcat_path),
        "line_count": len(lines),
        "status": "FAIL" if failures else "PASS",
        "failures": failures,
        "warnings": warnings[:50],
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"RUNTIME_LOGCAT_LINES={len(lines)}")
    print(f"RUNTIME_FATAL_FINDINGS={len(failures)}")
    print(f"RUNTIME_WARNING_FINDINGS={len(warnings)}")

    for item in failures[:20]:
        print(
            f"RUNTIME_FAILURE type={item['type']} "
            f"line={item['line']} message={item['message'][:500]}"
        )

    if failures:
        print(f"RUNTIME_LOGCAT_ANALYSIS=FAIL report={output_path}")
        return 1

    print(f"RUNTIME_LOGCAT_ANALYSIS=PASS report={output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
