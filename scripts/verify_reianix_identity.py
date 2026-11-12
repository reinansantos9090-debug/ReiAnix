#!/usr/bin/env python3
"""Ensure the repository uses the new brand except for its installed Android ID."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
gradle = (root / "android/app/build.gradle.kts").read_text(encoding="utf-8")
match = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)
if match is None:
    raise SystemExit("Android applicationId missing")

app_id = match.group(1)
expected_namespace = "com.reianix.reianix_local"
if ('namespace = "' + expected_namespace + '"') not in gradle:
    raise SystemExit("The Android namespace is not the expected new namespace")

parts = app_id.split(".")
if len(parts) < 3:
    raise SystemExit("Unexpected Android applicationId")
old = parts[1]
variants = [old, old[:3] + "-" + old[3:], old[:3] + "_" + old[3:]]
stale = re.compile("|".join(re.escape(value) for value in variants), re.IGNORECASE)
allowed_id = re.compile(re.escape(app_id), re.IGNORECASE)
tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).split(b"\0")
problems = []
for raw in tracked:
    if not raw:
        continue
    rel = raw.decode("utf-8")
    if stale.search(rel):
        problems.append(rel + ": old filename")
    path = root / rel
    try:
        content = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    # Escaped dots appear only in diagnostic regular expressions for the
    # preserved Android application ID.
    sanitized = allowed_id.sub("", content.replace("\\.", "."))
    for number, line in enumerate(sanitized.splitlines(), 1):
        if stale.search(line):
            problems.append(rel + ":" + str(number))
if problems:
    print("Unexpected previous-brand references outside the Android ID:")
    for problem in problems:
        print("  " + problem)
    sys.exit(1)
print("OK: no previous-brand paths or source references outside applicationId")
