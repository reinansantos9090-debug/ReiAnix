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


def test_all_settings_categories_are_compose_owned_and_actionable():
    compose = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"
    )
    host = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/host/ReiAnixComposeLibraryHost.kt"
    )
    repository = read(
        "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsRepository.kt"
    )
    main = read("main.py")

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
    for category in categories:
        assert f'"{category}"' in compose
        assert f'"{category}"' in compose[compose.index("NativeManagedSettingsCategories"):]

    for category in (
        "Artwork",
        "Armazenamento",
        "Dados e Cache",
        "Backup e Restauração",
        "Diagnóstico",
    ):
        assert f'"{category}" -> {{' in compose

    for action in (
        "clear_anilist_cache",
        "settings_export",
        "settings_import",
        "select_saf",
        "request_media_access",
        "check_storage_access",
        "open_broad_storage_settings",
        "backup_create",
        "backup_restore",
        "backup_integrity",
        "backup_reconcile",
        "diagnostic_export",
    ):
        assert action in repository
        assert action in main

    settings_callback_start = host.index("settings = {")
    settings_callback_end = host.index("storage = {", settings_callback_start)
    settings_callback = host[settings_callback_start:settings_callback_end]
    assert "publishSettingsNavigation(" not in settings_callback
    assert "ReiAnixRoutes.STORAGE" not in settings_callback
