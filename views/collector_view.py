from __future__ import annotations

import asyncio
import logging

import flet as ft

from core.library_discovery import format_duration
from core.performance import get_performance_monitor
from core.ui import (
    ACCENT,
    BACKGROUND,
    PAGE_PADDING,
    RADIUS,
    SURFACE,
    TEXT,
    TEXT_MUTED,
    activate_theme_for_page,
    empty_state,
    focus_button_style,
)


logger = logging.getLogger(__name__)


class CollectorView:
    """Local Collector Journey; all derived values come from LibraryService."""

    @staticmethod
    def build(page: ft.Page, library, on_back, is_active=None):
        performance = get_performance_monitor()
        build_started = performance.now()
        performance.counter("ui.builds_requested.collector")
        theme = activate_theme_for_page(page)
        background = theme.background
        surface = theme.surface
        text = theme.text
        muted = theme.text_muted
        accent = theme.primary
        is_active = is_active or (lambda: True)

        status = ft.Row(
            [ft.ProgressRing(width=16, height=16, stroke_width=2, color=accent),
             ft.Text("Calculando sua jornada local…", color=muted, size=12)],
            spacing=8,
        )
        content = ft.Column(
            [
                ft.Row(
                    [
                        ft.IconButton(
                            icon=ft.Icons.ARROW_BACK,
                            icon_color=text,
                            tooltip="Voltar",
                            on_click=lambda _: on_back(),
                        ),
                        ft.Text(
                            "Collector Journey",
                            size=20,
                            weight=ft.FontWeight.BOLD,
                            color=text,
                            expand=True,
                        ),
                    ],
                    alignment=ft.MainAxisAlignment.START,
                ),
                status,
            ],
            scroll=ft.ScrollMode.AUTO,
            expand=True,
            spacing=12,
        )

        def badge_icon(name):
            return getattr(ft.Icons, name, ft.Icons.STAR)

        def metric_card(label, value, icon_name):
            return ft.Container(
                width=170,
                padding=14,
                bgcolor=surface,
                border_radius=RADIUS,
                content=ft.Row(
                    [
                        ft.Icon(badge_icon(icon_name), color=accent, size=22),
                        ft.Column(
                            [
                                ft.Text(label, size=10, color=muted),
                                ft.Text(str(value), size=18, weight=ft.FontWeight.BOLD, color=text),
                            ],
                            spacing=2,
                            tight=True,
                        ),
                    ],
                    spacing=10,
                ),
            )

        def achievement_card(item):
            unlocked = bool(item.get("unlocked"))
            color = theme.success if unlocked else muted
            icon = badge_icon(item.get("icon") or "STAR")
            progress = item.get("progress", 0)
            target = item.get("target", 1)
            percentage = min(1.0, float(progress) / max(1, target))
            return ft.Container(
                width=300,
                padding=12,
                bgcolor=surface,
                border_radius=RADIUS,
                opacity=1.0 if unlocked else 0.62,
                content=ft.Row(
                    [
                        ft.Container(
                            width=42,
                            height=42,
                            alignment=ft.Alignment(0, 0),
                            border_radius=21,
                            bgcolor=theme.surface_raised,
                            content=ft.Icon(icon, color=color, size=21),
                        ),
                        ft.Column(
                            [
                                ft.Row(
                                    [
                                        ft.Text(item["title"], size=13, weight=ft.FontWeight.BOLD, color=text, expand=True),
                                        ft.Text("DESBLOQUEADA" if unlocked else f"{progress}/{target}", size=9, color=color),
                                    ],
                                    spacing=6,
                                ),
                                ft.Text(item["description"], size=10, color=muted, max_lines=3, overflow=ft.TextOverflow.ELLIPSIS),
                                ft.ProgressBar(value=percentage, height=3, bgcolor=theme.surface_variant, color=color),
                            ],
                            spacing=5,
                            expand=True,
                            tight=True,
                        ),
                    ],
                    spacing=10,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
            )

        async def reload():
            if not is_active():
                return
            status.visible = True
            page.update()
            try:
                journey = await asyncio.to_thread(library.collector_journey)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Collector Journey load failed")
                if not is_active():
                    return
                content.controls[:] = [
                    ft.Row(
                        [
                            ft.IconButton(
                                icon=ft.Icons.ARROW_BACK,
                                icon_color=text,
                                tooltip="Voltar",
                                on_click=lambda _: on_back(),
                            ),
                            ft.Text("Collector Journey", size=20, weight=ft.FontWeight.BOLD, color=text, expand=True),
                        ]
                    ),
                    empty_state(
                        ft.Icons.STAR,
                        "Jornada indisponível",
                        "Não foi possível reconstruir os dados da sua biblioteca local.",
                        theme=theme,
                    ),
                ]
                page.update()
                return

            if not is_active():
                return

            progress = float(journey.get("level_progress") or 0)
            level = int(journey.get("level") or 1)
            xp = int(journey.get("xp") or 0)
            xp_into = int(journey.get("xp_into_level") or 0)
            unlocked = int(journey.get("achievements_unlocked") or 0)
            total = int(journey.get("achievements_total") or 0)
            active_title = journey.get("active_title_label") or "Nenhum título selecionado"

            level_card = ft.Container(
                padding=16,
                bgcolor=surface,
                border_radius=RADIUS,
                content=ft.Column(
                    [
                        ft.Row(
                            [
                                ft.Column(
                                    [
                                        ft.Text("NÍVEL", size=10, color=muted),
                                        ft.Text(str(level), size=28, weight=ft.FontWeight.BOLD, color=text),
                                    ],
                                    tight=True,
                                    spacing=1,
                                ),
                                ft.Column(
                                    [
                                        ft.Text(f"{xp} XP de jornada", size=13, color=text),
                                        ft.Text(
                                            f"{xp_into}/10 concluídos para o próximo nível",
                                            size=10,
                                            color=muted,
                                        ),
                                    ],
                                    spacing=2,
                                    expand=True,
                                ),
                            ],
                            spacing=14,
                        ),
                        ft.ProgressBar(value=progress, height=6, bgcolor=theme.surface_variant, color=accent),
                        ft.Text(
                            "1 XP = 1 episódio local concluído. O nível é reconstruído do consumo real; nada é persistido como pontuação.",
                            size=10,
                            color=muted,
                        ),
                    ],
                    spacing=9,
                ),
            )

            metrics = ft.Row(
                [
                    metric_card("Animes concluídos", journey.get("animes_completed", 0), "MOVIE_OUTLINED"),
                    metric_card("Episódios concluídos", journey.get("episodes_completed", 0), "FORMAT_LIST_NUMBERED"),
                    metric_card("Dias ativos", journey.get("active_days", 0), "CALENDAR_TODAY"),
                    metric_card("Gêneros explorados", journey.get("genres_explored", 0), "LOCAL_OFFER_OUTLINED"),
                    metric_card("Décadas exploradas", journey.get("decades_explored", 0), "HISTORY"),
                    metric_card("Conquistas", f"{unlocked}/{total}", "STAR"),
                ],
                wrap=True,
                spacing=8,
                run_spacing=8,
            )

            title_options = []
            for item in journey.get("titles", []):
                selected = item.get("id") == journey.get("active_title")
                if item.get("unlocked"):
                    title_options.append(
                        ft.Container(
                            content=ft.OutlinedButton(
                                f"{'✓ ' if selected else ''}{item['title']}",
                                tooltip=item["condition"],
                                on_click=lambda _, title_id=item["id"]: page.run_task(select_title, title_id),
                                style=focus_button_style(theme=theme, background=surface),
                            ),
                            padding=2,
                        )
                    )
                else:
                    title_options.append(
                        ft.Container(
                            padding=12,
                            bgcolor=surface,
                            border_radius=RADIUS,
                            opacity=0.55,
                            content=ft.Column(
                                [
                                    ft.Text(item["title"], size=11, color=muted),
                                    ft.Text(item["condition"], size=10, color=muted),
                                ],
                                spacing=2,
                                tight=True,
                            ),
                        )
                    )

            achievements = journey.get("achievements") or []
            unlocked_achievements = [item for item in achievements if item.get("unlocked")]
            locked_achievements = [item for item in achievements if not item.get("unlocked")]

            content.controls[:] = [
                ft.Row(
                    [
                        ft.IconButton(
                            icon=ft.Icons.ARROW_BACK,
                            icon_color=text,
                            tooltip="Voltar",
                            on_click=lambda _: on_back(),
                        ),
                        ft.Text("Collector Journey", size=20, weight=ft.FontWeight.BOLD, color=text, expand=True),
                        ft.TextButton("Recalcular", icon=ft.Icons.REFRESH, on_click=lambda _: page.run_task(reload)),
                    ],
                    alignment=ft.MainAxisAlignment.START,
                ),
                ft.Text(
                    "Sua biblioteca, seu progresso e suas conquistas — tudo reconstruído localmente.",
                    color=muted,
                    size=12,
                ),
                level_card,
                metrics,
                ft.Container(
                    padding=14,
                    bgcolor=surface,
                    border_radius=RADIUS,
                    content=ft.Column(
                        [
                            ft.Text("Título atual", size=11, color=muted),
                            ft.Text(active_title, size=16, weight=ft.FontWeight.BOLD, color=text),
                            ft.Text(
                                "Títulos desbloqueados podem ser selecionados sem alterar sua biblioteca ou consumo.",
                                size=10,
                                color=muted,
                            ),
                            ft.Row(title_options, wrap=True, spacing=6, run_spacing=6),
                        ],
                        spacing=7,
                        tight=True,
                    ),
                ),
                ft.Text(f"Conquistas desbloqueadas • {len(unlocked_achievements)}/{len(achievements)}", size=14, weight=ft.FontWeight.BOLD, color=text),
                ft.Row([achievement_card(item) for item in unlocked_achievements], wrap=True, spacing=8, run_spacing=8),
                ft.Text("Próximas conquistas", size=13, weight=ft.FontWeight.BOLD, color=text, visible=bool(locked_achievements)),
                ft.Row([achievement_card(item) for item in locked_achievements], wrap=True, spacing=8, run_spacing=8, visible=bool(locked_achievements)),
                ft.Container(
                    padding=12,
                    bgcolor=surface,
                    border_radius=RADIUS,
                    content=ft.Text(
                        f"Conteúdo concluído: {format_duration(journey.get('completed_duration_seconds', 0))}. "
                        "Essa métrica usa a duração dos episódios concluídos; não é apresentada como tempo de reprodução exato.",
                        size=10,
                        color=muted,
                    ),
                ),
            ]
            status.visible = False
            page.update()

        async def select_title(title_id):
            if not is_active():
                return
            try:
                journey = await asyncio.to_thread(library.set_collector_active_title, title_id)
            except Exception:
                logger.exception("Collector title selection failed", extra={"title_id": title_id})
                if not is_active():
                    return
                page.snack_bar = ft.SnackBar(ft.Text("Título indisponível. A jornada continua intacta."))
                page.snack_bar.open = True
                page.update()
                return
            if not is_active():
                return
            # Reuse the freshly returned derived state; rebuilding still remains
            # available through the explicit refresh action.
            _ = journey
            await reload()

        page.run_task(reload)
        result = ft.Container(
            content=content,
            padding=ft.Padding(left=PAGE_PADDING, right=PAGE_PADDING, top=14, bottom=18),
            bgcolor=background,
            expand=True,
        )
        performance.record_ui_build("collector", (performance.now()-build_started)*1000.0,
                                    controls=performance.control_count(result), cached=False)
        return result
