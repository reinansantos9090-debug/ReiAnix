"""Helpers for contracts after the Flet root entrypoint moved to native Kotlin.

This module deliberately inspects production Kotlin source instead of preserving
a duplicate copy of the removed Python entrypoint as a test fixture.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_ROOT = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"


def native_main_source() -> str:
    """Return native application orchestration source for source-level contracts."""
    names = (
        KOTLIN_ROOT / "main.kt",
        KOTLIN_ROOT / "MainActivity.kt",
        KOTLIN_ROOT / "ReiAnixApplication.kt",
        KOTLIN_ROOT / "ReiAnixAppDependencies.kt",
        KOTLIN_ROOT / "ReiAnixAppBootstrap.kt",
        KOTLIN_ROOT / "NativePlayerActivity.kt",
        KOTLIN_ROOT / "bridge/NativeCommandDispatcher.kt",
        KOTLIN_ROOT / "viewmodel/ReiAnixLibraryViewModel.kt",
        KOTLIN_ROOT / "viewmodel/ReiAnixSettingsViewModel.kt",
        KOTLIN_ROOT / "data/sqlite/ReiAnixConsumptionService.kt",
        KOTLIN_ROOT / "data/sqlite/ReiAnixSqliteLibraryRepository.kt",
        KOTLIN_ROOT / "data/library/ReiAnixLibraryRepository.kt",
        KOTLIN_ROOT / "ui/host/ReiAnixComposeLibraryHost.kt",
    )
    return "\n\n".join(
        path.read_text(encoding="utf-8")
        for path in names
        if path.is_file()
    )
