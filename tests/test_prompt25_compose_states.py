from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local"
UI = KOTLIN / "ui"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_prompt25_has_reusable_state_components():
    content = read(UI / "ReiAnixStateComponents.kt")
    for symbol in (
        "ReiAnixLoadingState",
        "ReiAnixEmptyState",
        "ReiAnixEmptyLibraryState",
        "ReiAnixScannerInProgressState",
        "ReiAnixSourceUnavailableState",
        "ReiAnixFileUnavailableState",
        "ReiAnixArtworkMissingState",
        "ReiAnixRecoverableErrorState",
    ):
        assert f"fun {symbol}(" in content


def test_prompt25_screen_flows_use_shared_states_and_real_recovery():
    home = read(UI / "home/ReiAnixHome.kt")
    library = read(UI / "library/ReiAnixLibrary.kt")
    details = read(UI / "details/ReiAnixDetails.kt")
    search = read(UI / "search/ReiAnixSearch.kt")
    settings = read(UI / "settings/ReiAnixSettings.kt")
    storage = read(UI / "storage/ReiAnixStorageScreen.kt")
    player = read(UI / "player/ReiAnixPlayer.kt")

    assert "ReiAnixRecoverableErrorState(" in home
    assert "ReiAnixSourceUnavailableState(" in home
    assert "ReiAnixEmptyLibraryState(" in home
    assert "ReiAnixLoadingState(" in home

    assert "ReiAnixScannerInProgressState(" in library
    assert "ReiAnixRecoverableErrorState(" in library
    assert "ReiAnixSourceUnavailableState(" in library
    assert "ReiAnixEmptyLibraryState(" in library

    assert "ReiAnixFileUnavailableState(" in details
    assert "ReiAnixRecoverableErrorState(" in details
    assert "ReiAnixSourceUnavailableState(" in details
    assert "ReiAnixEmptyLibraryState(" in details

    assert "ReiAnixRecoverableErrorState(" in search
    assert "ReiAnixSourceUnavailableState(" in search
    assert "ReiAnixEmptyState(" in search

    assert "ReiAnixLoadingState(" in settings
    assert "ReiAnixRecoverableErrorState(" in settings
    assert "onRetry = viewModel::refresh" in settings

    assert "ReiAnixScannerInProgressState(" in storage
    assert "ReiAnixSourceUnavailableState(" in storage
    assert "onSelectSaf" in storage
    assert "onRequestMediaAccess" in storage
    assert "onOpenBroadSettings" in storage

    assert "ReiAnixLoadingState(" in player
    assert "ReiAnixRecoverableErrorState(" in player
    assert "viewModel.openEpisode" in player
    assert "onRetry = {" in player


def test_prompt25_artwork_does_not_silently_swallow_decode_errors():
    artwork = read(UI / "artwork/ReiAnixLocalArtwork.kt")

    assert "catch (_: Exception)" not in artwork
    assert "Log.w(TAG" in artwork
    assert "ReiAnixArtworkMissingState(" in artwork
    assert "LocalArtworkDecodeResult.Missing" in artwork
