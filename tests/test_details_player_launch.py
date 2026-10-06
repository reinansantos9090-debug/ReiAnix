from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DETAILS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/details/ReiAnixDetails.kt"


def read_details() -> str:
    return DETAILS.read_text(encoding="utf-8")


def lifecycle_block(source: str) -> str:
    start = source.index("DisposableEffect(lifecycleOwner)")
    end = source.index("ReiAnixDetailsScreen(", start)
    return source[start:end]


def test_player_launch_guard_requires_a_real_lifecycle_exit_before_release():
    source = read_details()
    block = lifecycle_block(source)

    assert "DisposableEffect(lifecycleOwner)" in block
    assert "LifecycleEventObserver" in block
    assert "detailsWasPaused" in block

    pause_transition = re.search(
        r"Lifecycle\.Event\.ON_PAUSE,\s*"
        r"Lifecycle\.Event\.ON_STOP\s*->\s*\{\s*"
        r"detailsWasPaused\s*=\s*true\s*\}",
        block,
        re.DOTALL,
    )
    assert pause_transition is not None

    resume_transition = re.search(
        r"Lifecycle\.Event\.ON_RESUME\s*->\s*\{\s*"
        r"if\s*\(detailsWasPaused\)\s*\{\s*"
        r"playerLaunchInFlight\s*=\s*false\s*"
        r"detailsWasPaused\s*=\s*false\s*\}",
        block,
        re.DOTALL,
    )
    assert resume_transition is not None
    assert block.index("detailsWasPaused = true") < block.index("playerLaunchInFlight = false")

    # Regression guard: ON_RESUME alone must never release the lock.
    assert re.search(
        r"if\s*\(event\s*==\s*Lifecycle\.Event\.ON_RESUME\)\s*\{\s*"
        r"playerLaunchInFlight\s*=\s*false",
        block,
        re.DOTALL,
    ) is None

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
