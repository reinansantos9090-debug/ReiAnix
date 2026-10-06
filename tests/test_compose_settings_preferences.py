from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_settings_controls_use_existing_preference_contract():
    settings = read("core/settings.py")
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    viewmodel = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/viewmodel/ReiAnixSettingsViewModel.kt"
    )
    main = read("main.py")

    for key in (
        "app.confirm_destructive",
        "appearance.theme",
        "appearance.card_size",
        "appearance.show_thumbnails",
    ):
        assert f'"{key}"' in settings

    for key in (
        "app.confirm_destructive",
        "appearance.theme",
        "appearance.card_size",
        "appearance.show_thumbnails",
    ):
        assert key in compose

    assert "compose_settings_set" in repository
    assert "NativeMailbox.write(" in repository
    assert "fun setSetting(key: String, value: String)" in viewmodel
    assert "compose_settings_set" in main
    assert "await asyncio.to_thread(settings.set, setting_key, setting_value)" in main
    assert "apply_settings_runtime(setting_key, normalized)" in main

    # Notifications have no persisted/source-of-truth preference in this project,
    # so earlier validation stage 19 must not invent a non-functional Compose toggle for them.
    assert "notifications" not in compose.lower()


def test_theme_follows_flow_without_activity_restart():
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    root = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/ReiAnixComposeRoot.kt"
    )
    library_host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    theme = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixComposeTheme.kt"
    )
    tokens = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt"
    )

    assert "collectAsStateWithLifecycle" in library_host
    assert 'settingsState.settings["appearance.theme"]' in library_host
    assert 'themeMode = settingsState.settings["appearance.theme"]' in library_host
    assert "ReiAnixSettingsRoute" in library_host
    assert "ReiAnixStorageRoute" in library_host
    assert "ReiAnixComposeTheme(themeMode = themeMode)" in root
    assert "ReiAnixComposeTheme(" not in compose
    assert "lightColorScheme" in theme
    assert "isSystemInDarkTheme" in theme
    assert "lightBackground" in tokens
    assert "appearance.card_size" in compose
    assert "appearance.show_thumbnails" in compose


def test_scope_is_whitelisted_in_python_before_persistence():
    main = read("main.py")
    start = main.index("if event_type == 'compose_settings_set':")
    end = main.index("if event_type == 'compose_settings_navigation':", start)
    block = main[start:end]

    for key in (
        "'app.confirm_destructive'",
        "'appearance.theme'",
        "'appearance.card_size'",
        "'appearance.show_thumbnails'",
    ):
        assert key in block

    assert "supported_compose_settings" in block
    assert "settings.set" in block
    assert "logger.warning" in block

def test_prompt47_settings_surface_is_single_compact_compose_language():
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    categories = (
        "Conta",
        "Geral",
        "Aparência",
        "Biblioteca",
        "Player",
        "Gestos",
        "Áudio e Legendas",
        "Metadata",
        "Artwork",
        "Armazenamento",
        "Dados e Cache",
        "Backup e Restauração",
        "Privacidade",
        "Varredura",
        "Diagnóstico",
        "Sobre",
    )

    assert "fun ReiAnixSettingsRow(" in compose
    assert "fun ReiAnixSettingsSurface(" in compose
    assert "items = state.categories" in compose
    assert 'key = { category -> "settings:' in compose
    assert "ReiAnixSettingCard(" not in compose
    assert "SettingsSpacing" not in compose
    assert "SettingsDimensions" not in compose
    assert "SettingsTokens" not in compose
    assert "Color(0x" not in compose

    for category in categories:
        assert f'ReiAnixSettingsCategoryUiModel("{category}"' in compose

    assert '"Artwork" -> {' in compose
    assert '"Dados e Cache" -> {' in compose
    assert '"Backup e Restauração" -> {' in compose
    assert '"Diagnóstico" -> {' in compose
    assert "NativeManagedSettingsCategories" in compose
    assert '.filterNot { it == "Armazenamento" }' in compose


def test_prompt47_settings_uses_centralized_visual_tokens_and_no_remote_account_image():
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    tokens = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/theme/ReiAnixTokens.kt"
    )
    storage = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/storage/ReiAnixStorageScreen.kt"
    )

    assert "ReiAnixTokens.Dimensions.settingsRowMinHeight" in compose
    assert "ReiAnixTokens.Dimensions.settingsIconContainerSize" in compose
    assert "ReiAnixTokens.Dimensions.settingsTrailingSize" in compose
    assert "ReiAnixTokens.Shapes.card" in compose
    assert "ReiAnixAccountAvatar" not in compose
    assert "androidx.compose.foundation.layout.safeDrawing" not in compose
    assert "val settingsRowMinHeight = 72.dp" in tokens
    assert "ReiAnixSettingsSurface(" in storage
    assert "settingsMaxWidth" in storage
