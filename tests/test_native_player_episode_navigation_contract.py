from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def block(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    end_index = source.index(end, start_index)
    return source[start_index:end_index]


def test_native_compose_handoff_is_identified_without_changing_legacy_launches():
    main_activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")

    assert '.putExtra("nativeCompose", source.getQueryParameter("native_compose") == "true")' in main_activity
    assert 'appendQueryParameter("native_compose", "true")' in read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
    )


def test_native_player_next_previous_dispatches_to_kotlin_for_compose_sessions_only():
    player = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
    request_episode = block(player, "private fun requestEpisode(", "private fun dispatchNativeEpisodeTransition(")
    dispatch = block(player, "private fun dispatchNativeEpisodeTransition(", "private fun seekToSavedPosition(")

    assert 'if (intent.getBooleanExtra("nativeCompose", false))' in request_episode
    assert "dispatchNativeEpisodeTransition(" in request_episode
    assert "NativeMailbox.write(this@NativePlayerActivity, transitionEvent)" in request_episode
    assert "dependencies.sqliteLibraryRepository.playerNavigation(episodeId)" in dispatch
    assert "withContext(Dispatchers.IO)" in dispatch
    assert "NativePlayerRequest.fromBridgeUri(successorUri.build())" in dispatch
    assert '.putExtra("nativeCompose", true)' in dispatch
    assert 'appendQueryParameter("origin_request_id", sourceRequestId)' in dispatch
    assert 'appendQueryParameter("origin_created_at", startedAtMs.toString())' in dispatch
    assert 'appendQueryParameter("origin_transition_generation", generation.toString())' in dispatch
    assert 'appendQueryParameter("origin_player_session_id", sourcePlayerSessionId)' in dispatch
    assert 'appendQueryParameter("origin_monotonic_ns", monotonicNs.toString())' in dispatch
    assert 'appendQueryParameter("transition_direction", direction)' in dispatch
    assert "startActivity(successorIntent)" in dispatch
    assert "NativeMailbox.write(" not in dispatch


def test_native_player_navigation_uses_canonical_sqlite_and_legacy_availability_predicate():
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
    )
    navigation_query = block(repository, "internal suspend fun playerNavigation(", "suspend fun collectorSnapshot(")

    assert "queryEpisode(connection, episodeId)" in navigation_query
    assert "WHERE anime_id = ?" in navigation_query
    assert "missing = 0" in navigation_query
    assert "COALESCE(availability_state, 'available') = 'available'" in navigation_query
    assert "episode_type NOT IN ('movie', 'special', 'ova', 'oad', 'ona', 'extra')" in navigation_query
    assert "ReiAnixPlayerEpisodeNavigator.resolve(current, availableEpisodes)" in navigation_query


def test_native_player_uses_the_canonical_kotlin_episode_navigation():
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
    host = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt")
    navigator = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixPlayerEpisodeNavigation.kt")

    assert "dispatchNativeEpisodeTransition(" in activity + host
    assert "playerNavigation(" in repository
    assert "ReiAnixPlayerEpisodeNavigator.resolve(" in repository
    assert "ReiAnixPlayerEpisodeNavigationSnapshot" in navigator
    assert "NativePlayerRequest.fromBridgeUri(successorUri.build())" in host
    assert "startActivity(successorIntent)" in host

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def block(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    end_index = source.index(end, start_index)
    return source[start_index:end_index]


def test_native_compose_handoff_is_identified_without_changing_legacy_launches():
    main_activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")

    assert '.putExtra("nativeCompose", source.getQueryParameter("native_compose") == "true")' in main_activity
    assert 'appendQueryParameter("native_compose", "true")' in read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt"
    )


def test_native_player_next_previous_dispatches_to_kotlin_for_compose_sessions_only():
    player = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")
    request_episode = block(player, "private fun requestEpisode(", "private fun dispatchNativeEpisodeTransition(")
    dispatch = block(player, "private fun dispatchNativeEpisodeTransition(", "private fun seekToSavedPosition(")

    assert 'if (intent.getBooleanExtra("nativeCompose", false))' in request_episode
    assert "dispatchNativeEpisodeTransition(" in request_episode
    assert "NativeMailbox.write(this@NativePlayerActivity, transitionEvent)" in request_episode
    assert "dependencies.sqliteLibraryRepository.playerNavigation(episodeId)" in dispatch
    assert "withContext(Dispatchers.IO)" in dispatch
    assert "NativePlayerRequest.fromBridgeUri(successorUri.build())" in dispatch
    assert '.putExtra("nativeCompose", true)' in dispatch
    assert 'appendQueryParameter("origin_request_id", sourceRequestId)' in dispatch
    assert 'appendQueryParameter("origin_created_at", startedAtMs.toString())' in dispatch
    assert 'appendQueryParameter("origin_transition_generation", generation.toString())' in dispatch
    assert 'appendQueryParameter("origin_player_session_id", sourcePlayerSessionId)' in dispatch
    assert 'appendQueryParameter("origin_monotonic_ns", monotonicNs.toString())' in dispatch
    assert 'appendQueryParameter("transition_direction", direction)' in dispatch
    assert "startActivity(successorIntent)" in dispatch
    assert "NativeMailbox.write(" not in dispatch


def test_native_player_navigation_uses_canonical_sqlite_and_legacy_availability_predicate():
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt"
    )
    navigation_query = block(repository, "internal suspend fun playerNavigation(", "suspend fun collectorSnapshot(")

    assert "queryEpisode(connection, episodeId)" in navigation_query
    assert "WHERE anime_id = ?" in navigation_query
    assert "missing = 0" in navigation_query
    assert "COALESCE(availability_state, 'available') = 'available'" in navigation_query
    assert "episode_type NOT IN ('movie', 'special', 'ova', 'oad', 'ona', 'extra')" in navigation_query
    assert "ReiAnixPlayerEpisodeNavigator.resolve(current, availableEpisodes)" in navigation_query


def test_flet_python_player_transition_consumer_is_retained_for_desktop_compatibility():
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/NativePlayerActivity.kt")

    assert "dispatchNativeEpisodeTransition(" in activity
    assert "ReiAnixPlayerEpisodeNavigator" in activity
    assert "NativeMailbox.write(this@NativePlayerActivity, transitionEvent)" in activity
    assert "NativePlayerRequest.fromBridgeUri(successorUri.build())" in activity
    assert "startActivity(successorIntent)" in activity
