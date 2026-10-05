from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DETAILS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"


def read_details() -> str:
    return DETAILS.read_text(encoding="utf-8")


def test_player_launch_guard_is_released_by_details_resume_lifecycle():
    source = read_details()
    assert "DisposableEffect(lifecycleOwner)" in source
    assert "LifecycleEventObserver" in source
    assert "event == Lifecycle.Event.ON_RESUME" in source
    assert "playerLaunchInFlight = false" in source
    assert "delay(" not in source
    assert "LaunchedEffect(Unit)" not in source


def test_player_launch_guard_preserves_single_existing_player_navigation_contract():
    source = read_details()
    assert source.count("navController.navigateToPlayer(") == 1
    assert "episodeId = episodeId.toString()" in source
    assert "animeId = canonicalId.toString()" in source
    assert "origin = origin" in source
    assert "if (!playerLaunchInFlight)" in source
    assert "playerLaunchInFlight = true" in source


def test_player_launch_guard_is_not_persisted_as_saved_domain_state():
    source = read_details()
    lock_block_start = source.index("var playerLaunchInFlight")
    lock_block_end = source.index("ReiAnixDetailsScreen(", lock_block_start)
    block = source[lock_block_start:lock_block_end]
    assert "rememberSaveable" not in block
    assert "viewModel" not in block
    assert "Repository" not in block
