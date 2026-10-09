import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "android/app/src/main/kotlin/com/reiflix/reiflix_local/ui/settings/ReiAnixSettings.kt"


def read():
    return SETTINGS.read_text(encoding="utf-8")


def block(source, start, end):
    first = source.index(start)
    last = source.index(end, first + len(start))
    return source[first:last]


def test_settings_root_is_compact_and_keeps_all_categories():
    source = read()
    expected = (
        "Conta", "Geral", "Aparência", "Biblioteca", "Player", "Gestos",
        "Áudio e Legendas", "Metadata", "Artwork", "Armazenamento",
        "Dados e Cache", "Backup e Restauração", "Privacidade",
        "Varredura", "Diagnóstico", "Sobre",
    )
    for label in expected:
        assert 'ReiAnixSettingsCategoryUiModel("' + label + '"' in source

    root = block(
        source,
        "@Composable\nfun ReiAnixSettingsScreen(",
        "@Composable\nprivate fun SettingsProfileRow(",
    )
    row = block(
        source,
        "@Composable\nprivate fun SettingsFlatCategoryRow(",
        "@Composable\nprivate fun ReiAnixComposeSettingsCategoryScreen(",
    )
    assert "SettingsFlatCategoryRow(" in root
    assert "category = category" in root
    assert "SettingsProfileRow(" in root
    assert "category.description" in row
    assert "category.icon" in row
    assert "Icons.Filled.ChevronRight" in row
    assert "ReiAnixSettingsRow(" in row
    assert "showDivider = false" in row
    assert "Arrangement.spacedBy(ReiAnixTokens.Spacing.none)" in root
    assert "ReiAnixTokens.Spacing.huge" not in root


def test_settings_rows_are_flat_and_choices_are_on_demand():
    source = read()
    boolean_row = block(
        source,
        "@Composable\nprivate fun BooleanSettingCard(",
        "@Composable\nprivate fun LanguageSettingCard(",
    )
    choice_row = block(
        source,
        "@Composable\nprivate fun ChoiceSettingCard(",
        "@Composable\nprivate fun ReiAnixSettingsAccountContent(",
    )
    language_row = block(
        source,
        "@Composable\nprivate fun LanguageSettingCard(",
        "@Composable\nprivate fun ChoiceSettingCard(",
    )

    assert "ReiAnixSettingsSurface(" not in boolean_row
    assert "HorizontalDivider(" not in boolean_row
    assert "SwitchDefaults.colors(" in boolean_row
    assert "MaterialTheme.colorScheme.primary" in boolean_row
    assert "ReiAnixSettingsSurface(" not in choice_row
    assert "HorizontalDivider(" not in choice_row
    assert "dialogOpen" in choice_row
    assert "heightIn(max = 420.dp)" in choice_row
    assert "RadioButton(" in choice_row
    assert "Icons.Filled.ChevronRight" in choice_row
    assert "ReiAnixSettingsSurface(" not in language_row
    assert "HorizontalDivider(" not in language_row
    assert "ReiAnixTextField(" in language_row


def test_settings_internal_sections_and_back_contract_are_present():
    source = read()
    for section in (
        "Comportamento", "Visual", "Organização", "Reprodução",
        "Vídeo", "Controles", "Tela", "Gestos", "Legendas",
        "Idiomas", "AniList", "Acesso", "Dados locais", "Backup",
        "Técnico", "ReiAnix", "Scanner", "Privacidade",
    ):
        assert 'SettingsSectionLabel("' + section + '")' in source
    assert "BackHandler(enabled = selectedCategory != null)" in source
    assert 'selectedCategory = null' in source
    assert "onOpenCategory = { label ->" in source


def test_settings_preserves_existing_setting_keys_and_actions():
    source = read()
    for token in (
        "player.autoplay_next", "player.resume", "player.default_speed",
        "player.aspect_ratio", "player.zoom_enabled",
        "gestures.volume", "gestures.brightness", "gestures.double_tap",
        "gestures.long_press", "audio.subtitle_scale",
        "audio.preferred_language", "audio.preferred_subtitle_language",
        "audio.subtitles", "metadata.anilist_enabled", "metadata.auto_match",
        "artwork.enabled", "artwork.cache_limit_mb",
        "select_saf", "check_storage_access", "request_media_access",
        "open_broad_storage_settings", "settings_export", "settings_import",
        "reset_all_settings", "backup_create", "backup_restore",
        "backup_integrity", "backup_reconcile", "diagnostic_export",
    ):
        assert token in source


def test_settings_respects_theme_surfaces_without_gray_literals():
    source = read()
    root = block(
        source,
        "@Composable\nfun ReiAnixSettingsScreen(",
        "@Composable\nprivate fun SettingsProfileRow(",
    )
    compact_rows = block(
        source,
        "@Composable\nprivate fun SettingsFlatCategoryRow(",
        "@Composable\nprivate fun ReiAnixComposeSettingsCategoryScreen(",
    )
    assert "MaterialTheme.colorScheme.background" in root
    assert "Color.Gray" not in root
    assert "Color.Gray" not in compact_rows
    assert "Surface(" in root
    assert "tonalElevation = ReiAnixTokens.Elevation.none" in source

def test_settings_profile_row_reuses_compact_design_tokens():
    source = read()
    profile = block(
        source,
        "@Composable\nprivate fun SettingsProfileRow(",
        "@Composable\nprivate fun SettingsFlatCategoryRow(",
    )

    assert "heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)" in profile
    assert "ReiAnixTokens.Dimensions.settingsIconContainerSize" in profile
    assert "ReiAnixTokens.Dimensions.iconMedium" in profile
    assert "heightIn(min = 64.dp)" not in profile
    assert "size(44.dp)" not in profile

def test_category_dispatch_has_one_branch_for_every_visible_category():
    source = read()
    branches = re.findall(r'^\s{16}"([^"]+)" -> \{', source, re.MULTILINE)
    expected = {
        "Conta", "Geral", "Aparência", "Biblioteca", "Player", "Gestos",
        "Áudio e Legendas", "Metadata", "Artwork", "Armazenamento",
        "Dados e Cache", "Backup e Restauração", "Privacidade", "Varredura",
        "Diagnóstico", "Sobre",
    }
    assert len(branches) == len(set(branches))
    assert set(branches) == expected

def test_settings_rows_share_compact_spacing_and_group_dividers():
    source = read()
    reusable_row = block(
        source,
        "@Composable\nfun ReiAnixSettingsRow(",
        "@Composable\nfun SettingsHeader(",
    )
    category_row = block(
        source,
        "@Composable\nprivate fun SettingsFlatCategoryRow(",
        "@Composable\nprivate fun ReiAnixComposeSettingsCategoryScreen(",
    )
    boolean_row = block(
        source,
        "@Composable\nprivate fun BooleanSettingCard(",
        "@Composable\nprivate fun LanguageSettingCard(",
    )
    choice_row = block(
        source,
        "@Composable\nprivate fun ChoiceSettingCard(",
        "@Composable\nprivate fun ReiAnixSettingsAccountContent(",
    )
    language_row = block(
        source,
        "@Composable\nprivate fun LanguageSettingCard(",
        "@Composable\nprivate fun ChoiceSettingCard(",
    )
    section = block(
        source,
        "@Composable\nprivate fun SettingsSectionLabel(",
        "@Composable\nfun ReiAnixSettingsSurface(",
    )
    header = block(
        source,
        "@Composable\nfun SettingsHeader(",
        "private fun settingsCategoryDescription(",
    )

    assert "showDivider: Boolean = true" in reusable_row
    assert "if (showDivider)" in reusable_row
    assert "ReiAnixTokens.TypographyTokens.settingsCategory" in reusable_row
    assert "ReiAnixTokens.TypographyTokens.settingsDescription" in reusable_row
    assert "showDivider = false" in category_row
    assert "HorizontalDivider(" not in boolean_row
    assert "HorizontalDivider(" not in choice_row
    assert "HorizontalDivider(" not in language_row
    assert "heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)" in boolean_row
    assert "heightIn(min = ReiAnixTokens.Dimensions.settingsRowMinHeight)" in choice_row
    assert "text = title," in section
    assert "title.uppercase()" not in section
    assert "MaterialTheme.typography.titleMedium" in header
