"""Source aggregate for contracts after the root Flet entrypoint moved to Kotlin.

The original root main.py bundled orchestration that now lives in the native
Activity, process bootstrap, Compose host, ViewModels, repositories, scanners,
player and command dispatcher. Tests use this aggregate to inspect those real
production components instead of retaining an executable/copy of the deleted
Python entrypoint as a fixture.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_ROOT = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"


def native_main_source() -> str:
    """Return the complete native Kotlin production source for integration contracts."""
    paths = sorted(KOTLIN_ROOT.rglob("*.kt"))
    if not paths:
        raise AssertionError("Native Kotlin source tree is missing")
    return "\n\n".join(path.read_text(encoding="utf-8") for path in paths)
