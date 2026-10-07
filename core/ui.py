"""Shared visual language and theme engine for ReiAnix Flet UI."""
from __future__ import annotations

from dataclasses import dataclass
import os
import flet as ft


@dataclass(frozen=True)
class ThemeTokens:
    mode: str
    background: str
    surface: str
    surface_variant: str
    surface_raised: str
    text: str
    text_muted: str
    text_on_accent: str
    text_on_overlay: str
    primary: str
    secondary: str
    border: str
    divider: str
    error: str
    success: str
    warning: str
    overlay: str
    favorite: str


DARK_THEME = ThemeTokens(
    mode="dark",
    background="#000000",
    surface="#0A0A0A",
    surface_variant="#151515",
    surface_raised="#111111",
    text="#FFFFFF",
    text_muted="#B3B3B3",
    text_on_accent="#FFFFFF",
    text_on_overlay="#FFFFFF",
    primary="#2579FF",
    secondary="#B3B3B3",
    border="#202020",
    divider="#202020",
    error="#FF5B61",
    success="#4ADE80",
    warning="#F0C85A",
    overlay="#000000D9",
    favorite="#2579FF",
)

LIGHT_THEME = ThemeTokens(
    mode="light",
    background="#F7F7F7",
    surface="#FFFFFF",
    surface_variant="#EFEFEF",
    surface_raised="#E7E7EA",
    text="#141414",
    text_muted="#5F5F5F",
    text_on_accent="#FFFFFF",
    text_on_overlay="#FFFFFF",
    primary="#2563C7",
    secondary="#5F6670",
    border="#D0D0D0",
    divider="#DEDEDE",
    error="#B3261E",
    success="#1B6B42",
    warning="#8A6200",
    overlay="#181720CC",
    favorite="#2563C7",
)

THEMES = {"dark": DARK_THEME, "light": LIGHT_THEME}

# Backward-compatible names for older helpers/tests. Theme-aware Views shadow
# these values with the active ThemeTokens before constructing controls.
BACKGROUND = DARK_THEME.background
SURFACE = DARK_THEME.surface
SURFACE_RAISED = DARK_THEME.surface_raised
TEXT = DARK_THEME.text
TEXT_MUTED = DARK_THEME.text_muted
ACCENT = DARK_THEME.primary
SUCCESS = DARK_THEME.success
WARNING = DARK_THEME.warning
RADIUS = 14
PAGE_PADDING = 16

_ACTIVE_THEME = DARK_THEME


def normalize_theme_mode(value) -> str:
    normalized = str(value or "").strip().casefold()
    return normalized if normalized in {"system", "light", "dark"} else "system"


def _brightness_name(brightness) -> str:
    value = getattr(brightness, "name", brightness)
    return str(value or "").strip().casefold()


def effective_theme_mode(mode, platform_brightness=None) -> str:
    selected = normalize_theme_mode(mode)
    if selected != "system":
        return selected
    return "dark" if _brightness_name(platform_brightness) == "dark" else "light"


def theme_tokens(mode="dark", platform_brightness=None) -> ThemeTokens:
    return THEMES[effective_theme_mode(mode, platform_brightness)]


def theme_for_page(page, selected_mode=None) -> ThemeTokens:
    if selected_mode is None:
        theme_mode = getattr(page, "theme_mode", ft.ThemeMode.DARK)
        selected_mode = {
            ft.ThemeMode.SYSTEM: "system",
            ft.ThemeMode.LIGHT: "light",
            ft.ThemeMode.DARK: "dark",
        }.get(theme_mode, "dark")
    return theme_tokens(selected_mode, getattr(page, "platform_brightness", None))


def _system_overlay_style(icon_brightness):
    return ft.SystemOverlayStyle(
        status_bar_color=ft.Colors.TRANSPARENT,
        system_navigation_bar_color=ft.Colors.TRANSPARENT,
        status_bar_icon_brightness=icon_brightness,
        system_navigation_bar_icon_brightness=icon_brightness,
        enforce_system_status_bar_contrast=False,
        enforce_system_navigation_bar_contrast=False,
    )


def apply_page_theme(page, mode) -> ThemeTokens:
    selected = normalize_theme_mode(mode)
    page.theme = ft.Theme(
        color_scheme_seed=LIGHT_THEME.primary,
        font_family="Roboto",
        system_overlay_style=_system_overlay_style(ft.Brightness.DARK),
    )
    page.dark_theme = ft.Theme(
        color_scheme_seed=DARK_THEME.primary,
        font_family="Roboto",
        system_overlay_style=_system_overlay_style(ft.Brightness.LIGHT),
    )
    page.theme_animation_style = ft.AnimationStyle.no_animation()
    page.theme_mode = {
        "system": ft.ThemeMode.SYSTEM,
        "light": ft.ThemeMode.LIGHT,
        "dark": ft.ThemeMode.DARK,
    }[selected]
    tokens = theme_tokens(selected, getattr(page, "platform_brightness", None))
    page.bgcolor = tokens.background
    # The View/background and system-overlay surfaces must switch atomically
    # with the same ThemeTokens so transparent system bars never reveal a
    # different native/material background.
    return tokens


def activate_theme_for_page(page, selected_mode=None) -> ThemeTokens:
    global _ACTIVE_THEME
    _ACTIVE_THEME = theme_for_page(page, selected_mode)
    return _ACTIVE_THEME


def current_theme() -> ThemeTokens:
    return _ACTIVE_THEME


def _resolve_theme(theme=None) -> ThemeTokens:
    return theme if isinstance(theme, ThemeTokens) else current_theme()


def count_label(count: int, singular: str, plural: str | None = None) -> str:
    """Return a localized count label with correct Brazilian Portuguese plurality."""
    try:
        value = int(count)
    except (TypeError, ValueError):
        value = 0
    word = singular if value == 1 else (plural or f"{singular}s")
    return f"{value} {word}"


def section_title(text: str, icon=None, *, theme: ThemeTokens | None = None):
    palette = _resolve_theme(theme)
    controls = []
    if icon:
        controls.append(ft.Icon(icon, size=16, color=palette.primary))
    controls.append(ft.Text(text.upper(), size=12, weight=ft.FontWeight.BOLD, color=palette.text_muted))
    return ft.Row(controls, spacing=6)


def media_artwork(
    source,
    height,
    *,
    width=None,
    icon_size=32,
    label="Sem capa",
    theme: ThemeTokens | None = None,
):
    palette = _resolve_theme(theme)
    fallback = ft.Container(
        width=width,
        height=height,
        border_radius=RADIUS,
        bgcolor=palette.surface_raised,
        alignment=ft.Alignment(0, 0),
        content=ft.Column(
            [
                ft.Icon(ft.Icons.MOVIE_OUTLINED, color=palette.text_muted, size=icon_size),
                ft.Text(label, color=palette.text_muted, size=10, visible=height >= 120),
            ],
            horizontal_alignment=ft.CrossAxisAlignment.CENTER,
            tight=True,
            spacing=3,
        ),
    )
    if not source:
        return fallback
    source = str(source)
    if source.startswith(("content://", "file://", "http://", "https://")):
        valid = True
    else:
        try:
            valid = os.path.isfile(source) and os.path.getsize(source) > 0
        except OSError:
            valid = False
    if not valid:
        return fallback
    return ft.Image(
        src=source,
        width=width,
        height=height,
        fit=ft.BoxFit.COVER,
        border_radius=RADIUS,
        error_content=fallback,
    )


def spoiler_artwork(
    page,
    source,
    height,
    *,
    width=None,
    icon_size=32,
    label="Imagem protegida",
    theme: ThemeTokens | None = None,
):
    """Contextual anti-spoiler layer for artwork without mutating cached files.

    The source image is rendered normally underneath a transient visual shield.
    A long press reveals it only for the current press; releasing restores the
    protection. No new image file is generated.
    """
    palette = _resolve_theme(theme)
    artwork = media_artwork(
        source,
        height,
        width=width,
        icon_size=icon_size,
        label=label,
        theme=palette,
    )
    hint = ft.Text(
        "Pressione e segure para revelar",
        color=palette.text_on_overlay,
        size=10,
        text_align=ft.TextAlign.CENTER,
    )
    shield = ft.Container(
        width=width,
        height=height,
        border_radius=RADIUS,
        bgcolor=palette.overlay,
        blur=14,
        alignment=ft.Alignment(0, 0),
        content=hint,
    )

    def restore(_event=None):
        shield.visible = True
        shield.update()

    def reveal(_event=None):
        shield.visible = False
        shield.update()

    gesture = ft.GestureDetector(
        content=ft.Stack(
            controls=[artwork, shield],
            width=width,
            height=height,
        ),
        on_long_press_start=reveal,
        on_long_press_end=restore,
        on_long_press_cancel=restore,
        on_long_press_up=restore,
    )
    return gesture


def focus_button_style(*, theme: ThemeTokens | None = None, background: str | None = None, radius: float = RADIUS):
    """Shared focus treatment for remote/keyboard activation without a second focus system."""
    palette = _resolve_theme(theme)
    return ft.ButtonStyle(
        bgcolor={
            ft.ControlState.DEFAULT: background or palette.surface,
            ft.ControlState.FOCUSED: palette.surface_raised,
            ft.ControlState.PRESSED: palette.surface_variant,
        },
        side={
            ft.ControlState.DEFAULT: ft.BorderSide(1, palette.border),
            ft.ControlState.FOCUSED: ft.BorderSide(1, palette.border),
        },
        shape=ft.RoundedRectangleBorder(radius=radius),
        padding=0,
    )


def empty_state(icon, title: str, body: str, action=None, *, theme: ThemeTokens | None = None):
    palette = _resolve_theme(theme)
    controls = [
        ft.Icon(icon, color=palette.text_muted, size=42),
        ft.Text(
            title,
            color=palette.text,
            size=16,
            weight=ft.FontWeight.BOLD,
            text_align=ft.TextAlign.CENTER,
        ),
        ft.Text(body, color=palette.text_muted, size=12, text_align=ft.TextAlign.CENTER),
    ]
    if action:
        controls.append(action)
    return ft.Container(
        content=ft.Column(controls, horizontal_alignment=ft.CrossAxisAlignment.CENTER, spacing=8),
        alignment=ft.Alignment(0, 0),
        padding=20,
        bgcolor=palette.surface,
        border_radius=RADIUS,
    )


def chip_style(active=False, *, theme: ThemeTokens | None = None):
    palette = _resolve_theme(theme)
    return ft.ButtonStyle(
        color=palette.text_on_accent if active else palette.text,
        bgcolor=palette.primary if active else palette.surface,
        side=ft.BorderSide(0, "#00000000"),
        shape=ft.RoundedRectangleBorder(radius=18),
        padding=ft.Padding(left=14, right=14, top=4, bottom=4),
    )
