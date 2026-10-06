from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"

MOVED = [
    "player/NativePlayerRequest.kt", "player/DeviceInteractionProfile.kt",
    "player/LocalSubtitleResolver.kt", "player/PlayerLocalMetadataStore.kt",
    "player/PlayerMediaPolicy.kt", "player/SystemUiController.kt",
    "storage/NativeBatch.kt", "storage/NativeIndex.kt",
    "storage/StorageAuthorization.kt", "storage/VideoThumbnailExtractor.kt",
    "bridge/NativeMailbox.kt", "bridge/NativeCommandDispatcher.kt",
    "bridge/NativeRequestState.kt", "bridge/GoogleIdentity.kt",
    "scanner/BroadStorageScanner.kt", "scanner/MediaStoreScanner.kt",
    "scanner/SafScanner.kt", "scanner/NativeScanController.kt",
    "scanner/NativeScanPublisher.kt", "scanner/MediaStoreRetryScheduler.kt",
]

ROOT_ENTRYPOINTS = [KOTLIN / "MainActivity.kt", KOTLIN / "NativePlayerActivity.kt"]

def test_layered_files_have_matching_packages():
    for relative in MOVED:
        path = KOTLIN / relative
        assert path.is_file(), path
        expected = "com.reiflix.reiflix_local." + relative.rsplit("/", 1)[0].replace("/", ".")
        first = path.read_text(encoding="utf-8").splitlines()[0]
        assert first == f"package {expected}", (path, first, expected)

def test_root_entrypoints_remain_root():
    assert all(path.is_file() for path in ROOT_ENTRYPOINTS)
    assert "package com.reiflix.reiflix_local" in ROOT_ENTRYPOINTS[0].read_text(encoding="utf-8")
    assert "package com.reiflix.reiflix_local" in ROOT_ENTRYPOINTS[1].read_text(encoding="utf-8")

def test_old_native_paths_are_removed():
    for name in [Path(p).name for p in MOVED]:
        assert not (KOTLIN / name).exists(), name

def test_no_compose_or_android_ui_leaks_into_storage():
    for relative in [p for p in MOVED if p.startswith("storage/")]:
        source = (KOTLIN / relative).read_text(encoding="utf-8")
        assert "androidx.compose" not in source
        assert "android.widget" not in source

def test_no_fictitious_domain_layer_or_second_database():
    assert not (KOTLIN / "domain").exists()
    assert not any("RoomDatabase" in p.read_text(encoding="utf-8", errors="ignore") for p in KOTLIN.rglob("*.kt"))

def test_critical_media_stack_files_remain_present():
    for relative in [
        "scanner/SafScanner.kt", "scanner/MediaStoreScanner.kt",
        "scanner/BroadStorageScanner.kt", "storage/NativeIndex.kt",
        "bridge/NativeMailbox.kt", "player/NativePlayerRequest.kt",
        "ui/navigation/ReiAnixNavigation.kt",
    ]:
        assert (KOTLIN / relative).is_file(), relative

def test_gradle_stack_is_reused_without_new_layer_dependencies():
    gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    assert "androidx.navigation:navigation-compose:2.9.8" in gradle
    assert "androidx.media3:media3-exoplayer:1.11.1" in gradle
    assert "implementation(\"androidx.room:" not in gradle
