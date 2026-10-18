from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt"
NAVIGATION = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/player/ReiAnixNativeEpisodeNavigation.kt"
VIEW_MODEL = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"


def test_native_player_resolves_next_previous_from_canonical_sqlite():
    service = NAVIGATION.read_text(encoding="utf-8")
    player = PLAYER.read_text(encoding="utf-8")
    request = player[player.index("private fun requestEpisode"):player.index("private fun seekToSavedPosition")]

    assert "ReiAnixNativePlayerNavigationService" in service
    assert "library.getEpisode(episodeId)" in service
    assert "library.listEpisodes(current.animeId)" in service
    assert "isAvailableRegularCandidate" in service
    assert "nextCanNext" in service and "previousCanPrevious" in service
    assert "nativePlayerNavigationService.resolve(sourceEpisodeId)" in request
    assert "startActivity(successorIntent)" in request
    assert "positionMsSpecified\", positionMs != null)" in request
    assert "positionMs ?: 0L" in request
    assert "disabled resume / watched" in request
    assert "originRequestId" in request
    assert "originTransitionGeneration" in request
    assert "originMonotonicNs" in request
    assert "playerSessionId" in request
    assert "NativeMailbox.write(this@NativePlayerActivity, transitionEvent)" not in request


def test_compose_launch_and_player_transitions_share_episode_ordering():
    service = NAVIGATION.read_text(encoding="utf-8")
    view_model = VIEW_MODEL.read_text(encoding="utf-8")

    assert "ReiAnixNativePlayerEpisodeOrder::key" in view_model
    assert "ReiAnixNativePlayerEpisodeOrder::isAvailableRegularCandidate" in view_model
    assert "LibraryStore._episode_order_key" in service
    assert "ORDER BY season ASC, number ASC, id ASC" not in service
    assert "schemaVersion" not in service
