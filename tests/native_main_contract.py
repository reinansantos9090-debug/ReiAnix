"""Source aggregate for contracts after the root Flet entrypoint moved to Kotlin.

The original root main.py bundled orchestration that now lives in the native
Activity, process bootstrap, Compose host, ViewModels, repositories, scanners,
player and command dispatcher. Tests use this aggregate to inspect those real
production components instead of retaining an executable/copy of the deleted
Python entrypoint as a fixture.
"""
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_ROOT = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"


class NativeMainSource(str):
    """String-compatible native source with minimal former-Path read support."""

    def read_text(self, encoding: str = "utf-8") -> str:
        return str(self)

    def exists(self) -> bool:
        return False

    def is_file(self) -> bool:
        return False


@lru_cache(maxsize=1)
def native_main_source() -> NativeMainSource:
    """Return the complete native Kotlin production source for integration contracts."""
    paths = sorted(KOTLIN_ROOT.rglob("*.kt"))
    if not paths:
        raise AssertionError("Native Kotlin source tree is missing")
    source = "\n\n".join(path.read_text(encoding="utf-8") for path in paths)
    return NativeMainSource(source)
