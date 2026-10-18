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
    assert "library.playerNavigationRows(episodeId)" in service
    assert "rows.availableCandidates" in service
    assert "isAvailableRegularCandidate" in service
    repository = (ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt").read_text(encoding="utf-8")
    rows = repository[repository.index("internal suspend fun playerNavigationRows"):repository.index("suspend fun collectorSnapshot")]
    assert "database.readConsistentSnapshot" in rows
    assert "JOIN anime a ON a.id = e.anime_id" in rows
    assert "COALESCE(e.availability_state, 'available') = 'available'" in rows
    assert "e.episode_type NOT IN" in rows
    assert "CREATE TABLE" not in rows.upper()
    assert "nextCanNext" in service and "previousCanPrevious" in service
    assert "nativePlayerNavigationService.resolve(sourceEpisodeId)" in request
    assert "navigation.current.id != sourceEpisodeId" in request
    assert "navigation.current.animeId != expectedAnimeId" in request
    assert "resolvedCurrentUri != expectedCurrentUri" in request
    assert "active player identity does not match its canonical SQLite episode" in request
    assert 'putExtra("episodeTitle", targetTitle)' in request
    assert "startActivity(successorIntent)" in request
    assert 'putExtra("positionMsSpecified", positionMs != null)' in request
    assert 'putExtra("positionMs", positionMs ?: 0L)' in request
    assert "disabled resume / watched" in request
    assert "currentPlayerSettingsSnapshot()" in request
    for extra in (
        "setting_player_default_speed",
        "setting_player_aspect_ratio",
        "setting_player_zoom_enabled",
        "setting_player_immersive",
        "setting_player_lock_mode",
        "setting_player_auto_hide_seconds",
        "setting_player_double_tap_seek_seconds",
        "setting_player_long_press_speed",
        "setting_player_max_video_resolution",
        "setting_player_max_video_frame_rate",
        "setting_player_max_audio_channels",
        "setting_player_pip",
        "setting_gestures_volume",
        "setting_gestures_brightness",
        "setting_gestures_double_tap",
        "setting_gestures_long_press",
        "setting_audio_preferred_language",
        "setting_audio_preferred_subtitle_language",
        "setting_audio_subtitles",
        "setting_audio_subtitle_scale",
        "setting_audio_subtitle_bottom_padding",
        "setting_audio_subtitle_embedded_style",
    ):
        assert extra in request
    for origin in (
        "originRequestId",
        "originTransitionGeneration",
        "originMonotonicNs",
        "originPlayerSessionId",
        "playerSessionId",
    ):
        assert origin in request
    assert "NativeMailbox.write(this@NativePlayerActivity, transitionEvent)" not in request


def test_compose_launch_and_player_transitions_share_episode_ordering():
    service = NAVIGATION.read_text(encoding="utf-8")
    view_model = VIEW_MODEL.read_text(encoding="utf-8")

    assert "ReiAnixNativePlayerEpisodeOrder::key" in view_model
    assert "ReiAnixNativePlayerEpisodeOrder::isAvailableRegularCandidate" in view_model
    assert "LibraryStore._episode_order_key" in service
    assert "ORDER BY season ASC, number ASC, id ASC" not in service
    assert "schemaVersion" not in service
