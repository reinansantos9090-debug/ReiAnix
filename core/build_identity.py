"""Build identity embedded into every packaged ReiAnix Python runtime.

This file is overwritten by scripts/generate_build_identity.py in release builds.
The checked-in values deliberately identify an unbuilt development checkout.
"""
from __future__ import annotations

BUILD_COMMIT = "UNBUILT"
BUILD_VERSION = "0.2.1"
BUILD_NUMBER = "UNBUILT"
BUILD_TIMESTAMP_UTC = "UNBUILT"
BUILD_BRANCH = "UNBUILT"
PYTHON_BUNDLE_FINGERPRINT = "UNBUILT"
PYTHON_RUNTIME = "3.12"
FLET_VERSION = "0.86.5"


def as_dict() -> dict[str, str]:
    return {
        "commit": BUILD_COMMIT,
        "version": BUILD_VERSION,
        "build_number": BUILD_NUMBER,
        "timestamp_utc": BUILD_TIMESTAMP_UTC,
        "branch": BUILD_BRANCH,
        "python_bundle_fingerprint": PYTHON_BUNDLE_FINGERPRINT,
        "python_runtime": PYTHON_RUNTIME,
        "flet_version": FLET_VERSION,
    }
