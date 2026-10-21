from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def test_compose_player_uses_the_native_sqlite_to_activity_handoff():
    view_model = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt")
    route = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/player/ReiAnixPlayer.kt")
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryRepository.kt")
    codec = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/library/ReiAnixLibraryCommandCodec.kt")
    launch_start = view_model.index("fun openEpisode(episodeId: Long, expectedAnimeId?")
    launch_end = view_model.index("override fun onCleared()", launch_start)
    launch = view_model[launch_start:launch_end]
    assert "viewModel.openEpisode(" in route
    assert "nativeLibraryRepository.getEpisode(episodeId)" in launch
    assert "dependencies.sqliteSettingsRepository.allPreferences()" in launch
    assert "dependencies.sqliteLibraryRepository.playerNavigation(episodeId)" in launch
    assert "Intent.ACTION_VIEW" in launch
    assert '.appendQueryParameter("action", "play")' in launch
    assert '.appendQueryParameter("native_compose", "true")' in launch
    assert "NativeMailbox.write" not in launch
    assert "NativePlayerRequest.fromBridgeUri(source)" in activity
    assert "fun reportNativePlayerHandoff(" in repository
    assert "fun reportNativePlayerHandoff(" in view_model
    assert 'OPEN_MEDIA("open_media")' not in codec
    assert "open_media" not in codec
    assert "private fun performOpenPlayer(" in activity
    assert "reportNativePlayerHandoff(" in activity

def test_compose_player_handoff_preserves_local_identity_and_player_contract():
    view_model = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixLibraryViewModel.kt")
    activity = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/MainActivity.kt")
    repository = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/data/sqlite/ReiAnixSqliteLibraryRepository.kt")
    player_request = read("android/app/src/main/kotlin/com/reiflix/reiflix_local/player/NativePlayerRequest.kt")
    launch_start = view_model.index("fun openEpisode(episodeId: Long, expectedAnimeId?")
    launch_end = view_model.index("override fun onCleared()", launch_start)
    launch = view_model[launch_start:launch_end]
    for token in (
        "expectedAnimeId == null || episode.animeId == expectedAnimeId",
        "episode.missing != true && episode.path.isNotBlank()",
        '"content", "file"',
        "Uri.fromFile(File(rawReference))",
        '.appendQueryParameter("episode_id", episode.id.toString())',
        '.appendQueryParameter("anime_id", episode.animeId.toString())',
        '.appendQueryParameter("position_ms", positionMs.toString())',
        '.appendQueryParameter("can_next", canNext.toString())',
        '.appendQueryParameter("can_previous", canPrevious.toString())',
        "setting_audio_preferred_subtitle_language",
    ):
        assert token in launch
    assert "dependencies.sqliteLibraryRepository.playerNavigation(episodeId)" in launch
    assert "ReiAnixPlayerEpisodeNavigator.resolve(current, availableEpisodes)" in repository
    assert "WHERE anime_id = ?" in repository
    assert "missing = 0" in repository
    assert "COALESCE(availability_state, 'available') = 'available'" in repository
    assert "NativePlayerRequest.fromBridgeUri(source)" in activity
    assert 'putExtra("episodeId", episodeId)' in player_request
    assert 'putExtra("positionMs", positionMs)' in player_request

def test_prompt25_traceability_documents_keep_remaining_runtime_explicit():
    bridge_doc = read("docs/KOTLIN_BRIDGE_MIGRATION.md")
    audit_doc = read("docs/KOTLIN_MIGRATION_AUDIT.md")
    for token in (
        "Prompt 25/29",
        "load_library_page",
        "NativeMailbox",
        "ComposeLibraryBridge",
        "ComposeSettingsBridge",
        "não conclusiva",
    ):
        assert token.lower() in (bridge_doc + audit_doc).lower()



def test_prompt25_dependency_matrix_classifies_runtime_build_and_tooling_separately():
    audit_doc = read("docs/KOTLIN_MIGRATION_AUDIT.md")
    for token in (
        "RT-01",
        "RT-18",
        "18 famílias de fluxo",
        "Dependência de build e, para o APK atual, também de runtime",
        "Não conclusiva",
        "Nenhuma Activity base",
        "notificação mantida",
    ):
        assert token.lower() in audit_doc.lower()
