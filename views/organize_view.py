"""Local-library organization view built on the authoritative catalog and search engine."""
from __future__ import annotations

import asyncio
import inspect
import time
import logging
import math

import flet as ft

from core.consumption import consumption_state, progress_ratio
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
    chip_style,
    count_label,
    empty_state,
    media_artwork,
    section_title,
)

logger = logging.getLogger(__name__)


class OrganizeView:
    """Organize one loaded SQLite catalog without scanning or contacting AniList."""

    _STATE_ICONS = {
        "Todos": ft.Icons.GRID_VIEW,
        "Favoritos": ft.Icons.STAR,
        "Fixados": ft.Icons.PUSH_PIN_OUTLINED,
        "Assistidos": ft.Icons.HISTORY,
        "Não assistidos": ft.Icons.LOOKS_ONE_OUTLINED,
        "Em andamento": ft.Icons.PLAY_CIRCLE_OUTLINE,
        "Concluídos": ft.Icons.CHECK_CIRCLE_OUTLINE,
    }
    _STATE_ORDER = (
        "Todos",
        "Favoritos",
        "Fixados",
        "Não assistidos",
        "Em andamento",
        "Concluídos",
        "Assistidos",
    )
    _SORTS = (
        "Mais recentes",
        "Assistidos recentemente",
        "Progresso",
        "Episódio",
        "Temporada + episódio",
        "Modificação",
        "Duração",
        "Tamanho",
        "Favoritos primeiro",
        "Fixados primeiro",
        "Nome A-Z",
        "Nome Z-A",
    )

    @staticmethod
    def build(
        page: ft.Page,
        library,
        on_select_anime,
        on_back,
        on_open_settings,
        on_request_storage_access=None,
        on_scan_storage=None,
        on_request_video_access=None,
        on_add_folder=None,
        view_state=None,
        is_active=None,
    ):
        performance = get_performance_monitor()
        build_started = performance.now()
        performance.counter("ui.builds_requested.organize")
        theme = activate_theme_for_page(page)
        BACKGROUND = theme.background
        SURFACE = theme.surface
        TEXT = theme.text
        TEXT_MUTED = theme.text_muted
        ACCENT = theme.primary
        catalog: list[dict] = []
        view_state = view_state if view_state is not None else {}
        is_active = is_active or (lambda: True)
        selected_genre = [view_state.get("genre", "Todos")]
        selected_state = [view_state.get("state", "Todos")]
        selected_sort = [view_state.get("sort", "Mais recentes")]
        query = [view_state.get("query", "")]
        mode = [view_state.get("mode", "overview")]
        render_generation = [0]
        search_generation = [0]
        current_page = [0]
        has_more = [True]
        total_matches = [0]
        page_loading = [False]
        catalog_load_failed = [False]
        scan_active = [False]
        catalog_refresh_scheduled = [False]
        catalog_refresh_dirty = [False]
        view_tasks: set[object] = set()

        def _discard_view_task(task):
            view_tasks.discard(task)

        def _start_view_task(handler, *args):
            task = page.run_task(handler, *args)
            if task is not None:
                view_tasks.add(task)
                add_done_callback = getattr(task, "add_done_callback", None)
                if callable(add_done_callback):
                    try:
                        add_done_callback(_discard_view_task)
                    except Exception:
                        logger.debug("Organize task completion hook unavailable", exc_info=True)
            return task

        def cancel_view_tasks():
            tasks = tuple(view_tasks)
            view_tasks.clear()
            for task in tasks:
                cancel = getattr(task, "cancel", None)
                if callable(cancel):
                    try:
                        cancel()
                    except Exception:
                        logger.debug("Organize task cancellation failed", exc_info=True)


        def save_view_state():
            view_state.update(
                genre=selected_genre[0],
                state=selected_state[0],
                sort=selected_sort[0],
                query=query[0],
                mode=mode[0],
            )

        async def _invoke_callback(callback, *args):
            if not callback:
                return
            result = callback(*args)
            if inspect.isawaitable(result):
                await result

        async def handle_request_storage(_event=None):
            await _invoke_callback(on_request_storage_access)

        async def handle_scan_storage(_event=None):
            await _invoke_callback(on_scan_storage)

        async def handle_request_video_access(_event=None):
            await _invoke_callback(on_request_video_access)

        async def handle_add_folder(_event=None):
            await _invoke_callback(on_add_folder)

        content = ft.Column(expand=True, scroll=ft.ScrollMode.AUTO, spacing=14, scroll_interval=60)
        status = ft.Row(
            [
                ft.ProgressRing(width=16, height=16, stroke_width=2, color=ACCENT),
                ft.Text("Carregando sua biblioteca local…", color=TEXT_MUTED, size=12),
            ],
            spacing=8,
        )
        collection_grid = ft.Row(wrap=True, spacing=14, run_spacing=20)
        collection_summary = ft.Text("", color=TEXT_MUTED, size=12)
        collection_search = None
        collection_genre = None
        collection_sort = None

        def artwork(source, height, icon_size=28, width=None, label="Sem capa"):
            return media_artwork(source, height, width=width, icon_size=icon_size, label=label)

        def progress(anime):
            current = anime.get("current_episode") or {}
            if not current:
                return None
            try:
                duration = float(current.get("duration") or 0)
            except (TypeError, ValueError):
                return None
            if not math.isfinite(duration) or duration <= 0:
                return None
            return progress_ratio(current)

        async def select_anime(_event, anime):
            if not is_active():
                return
            try:
                result = on_select_anime(anime)
                if inspect.isawaitable(result):
                    await result
            except Exception:
                logger.exception("Organize anime navigation failed")
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível abrir esta obra."))
                page.snack_bar.open = True
                page.update()

        def make_anime_click_handler(anime):
            def handle(event):
                async def invoke():
                    await select_anime(event, anime)
                try:
                    loop = asyncio.get_running_loop()
                except RuntimeError:
                    asyncio.run(invoke())
                else:
                    _start_view_task(invoke)
                return None
            return handle

        def anime_card(anime):
            available_count = int(anime.get("available_count") or 0)
            watched = int(anime.get("watched_count") or 0)
            ratio = progress(anime)
            meta = anime.get("meta") or {}
            cover = meta.get("cover_cache") or meta.get("cover_url")
            subtitle = count_label(available_count, "episódio")
            metadata_status = str(meta.get("metadata_status") or "unresolved").casefold()
            identified = bool(meta.get("anilist_id")) or metadata_status in {"available", "manual", "stale"}
            artwork_state = "ready" if cover else (
                "pending"
                if metadata_status in {"refreshing", "available", "stale"} and meta.get("cover_url")
                else "unmatched"
            )
            state_prefix = "" if identified else "Não identificado • "
            artwork_label = "Capa" if cover else (
                "Capa pendente" if artwork_state == "pending" else "Sem capa"
            )
            indicators = []
            if anime.get("favorite"):
                indicators.append(
                    ft.Container(
                        ft.Icon(ft.Icons.STAR, color=theme.favorite, size=16),
                        top=7,
                        right=7,
                        bgcolor=theme.overlay,
                        border_radius=12,
                        padding=4,
                    )
                )
            if anime.get("is_pinned"):
                indicators.append(
                    ft.Container(
                        ft.Icon(ft.Icons.PUSH_PIN, color=theme.text_on_overlay, size=15),
                        top=7,
                        left=7,
                        bgcolor=theme.overlay,
                        border_radius=12,
                        padding=4,
                    )
                )

            return ft.Container(
                key=f"anime:{anime.get('id', '-')}",
                width=148,
                ink=True,
                border_radius=14,
                on_click=make_anime_click_handler(anime),
                content=ft.Column(
                    [
                        ft.Stack(
                            [
                                ft.Container(
                                    artwork(cover, 210, width=148, label=artwork_label),
                                    width=148,
                                    height=210,
                                ),
                                *indicators,
                            ]
                        ),
                        ft.Text(
                            anime.get("main_title") or "Anime local",
                            size=13,
                            weight=ft.FontWeight.BOLD,
                            color=theme.text,
                            max_lines=2,
                            overflow=ft.TextOverflow.ELLIPSIS,
                        ),
                        ft.Text(
                            "Não identificado",
                            size=10,
                            color=theme.warning,
                            visible=not identified,
                        ),
                        ft.Text(
                            f"{watched}/{available_count} assistidos" if available_count else subtitle,
                            size=10,
                            color=theme.text_muted,
                            max_lines=1,
                            overflow=ft.TextOverflow.ELLIPSIS,
                        ),
                        ft.ProgressBar(
                            value=ratio,
                            color=theme.primary,
                            bgcolor=theme.surface_variant,
                            bar_height=3,
                            visible=(
                                ratio is not None
                                and ratio > 0
                                and consumption_state(anime.get("current_episode") or {}).value
                                == "in_progress"
                            ),
                        ),
                    ],
                    spacing=5,
                ),
            )

        def header(title, back_handler=None):
            if back_handler:
                left = ft.IconButton(
                    icon=ft.Icons.ARROW_BACK,
                    icon_color=theme.text_on_overlay,
                    tooltip="Voltar",
                    on_click=back_handler,
                )
            else:
                left = ft.IconButton(
                    icon=ft.Icons.HOME_OUTLINED,
                    icon_color=theme.text_on_overlay,
                    tooltip="Início",
                    on_click=lambda _event: on_back(),
                )
            actions = []
            if on_request_storage_access:
                actions.append(
                    ft.IconButton(
                        icon=ft.Icons.FOLDER_OPEN_OUTLINED,
                        icon_color=theme.text_on_overlay,
                        tooltip="Solicitar acesso ao armazenamento",
                        on_click=handle_request_storage,
                    )
                )
            if on_scan_storage:
                actions.append(
                    ft.IconButton(
                        icon=ft.Icons.REFRESH,
                        icon_color=theme.text_on_overlay,
                        tooltip="Atualizar biblioteca",
                        on_click=handle_scan_storage,
                    )
                )
            actions.append(
                ft.IconButton(
                    icon=ft.Icons.SETTINGS_OUTLINED,
                    icon_color=theme.text_on_overlay,
                    tooltip="Configurações",
                    on_click=lambda _event: on_open_settings(),
                )
            )
            return ft.Row(
                [
                    left,
                    ft.Column(
                        [
                            ft.Text(
                                title,
                                size=21,
                                weight=ft.FontWeight.BOLD,
                                color=theme.text,
                            ),
                            ft.Text(
                                "Explore sua biblioteca local",
                                size=11,
                                color=theme.text_muted,
                            ),
                        ],
                        spacing=1,
                        expand=True,
                    ),
                    *actions,
                ]
            )

        def state_button(label, count):
            async def handle(_event):
                selected_state[0] = label
                selected_genre[0] = "Todos"
                mode[0] = "collection"
                save_view_state()
                await render()

            return ft.Container(
                width=155,
                padding=12,
                bgcolor=SURFACE,
                border_radius=RADIUS,
                ink=True,
                on_click=handle,
                content=ft.Column(
                    [
                        ft.Icon(
                            OrganizeView._STATE_ICONS.get(label, ft.Icons.DASHBOARD_OUTLINED),
                            color=ACCENT,
                            size=23,
                        ),
                        ft.Text(
                            label,
                            color=TEXT,
                            size=12,
                            weight=ft.FontWeight.BOLD,
                            max_lines=1,
                            overflow=ft.TextOverflow.ELLIPSIS,
                        ),
                        ft.Text(
                            count_label(count, "anime"),
                            color=TEXT_MUTED,
                            size=10,
                        ),
                    ],
                    spacing=5,
                ),
            )

        def genre_card(item):
            label = item["name"]
            count = item["count"]
            cover = item.get("cover")

            async def handle(_event):
                selected_genre[0] = item.get("id") or label
                selected_state[0] = "Todos"
                mode[0] = "collection"
                save_view_state()
                await render()

            return ft.Container(
                width=170,
                height=142,
                border_radius=RADIUS,
                clip_behavior=ft.ClipBehavior.HARD_EDGE,
                bgcolor=theme.surface_variant,
                ink=True,
                on_click=handle,
                content=ft.Stack(
                    [
                        ft.Container(artwork(cover, 142), height=142, opacity=0.55),
                        ft.Container(
                            content=ft.Column(
                                [
                                    ft.Text(
                                        label.upper(),
                                        color=theme.text_on_overlay,
                                        size=14,
                                        weight=ft.FontWeight.BOLD,
                                        max_lines=2,
                                        overflow=ft.TextOverflow.ELLIPSIS,
                                    ),
                                    ft.Text(
                                        count_label(count, "anime"),
                                        color=theme.secondary,
                                        size=11,
                                    ),
                                ],
                                spacing=3,
                            ),
                            left=12,
                            right=10,
                            bottom=10,
                        ),
                    ]
                ),
            )

        def empty_catalog():
            actions = []
            if on_request_video_access:
                actions.append(
                    ft.FilledButton(
                        "Permitir leitura de vídeos",
                        icon=ft.Icons.VIDEO_LIBRARY_OUTLINED,
                        on_click=handle_request_video_access,
                    )
                )
            if on_request_storage_access:
                actions.append(
                    ft.OutlinedButton(
                        "Acesso amplo",
                        icon=ft.Icons.FOLDER_OPEN_OUTLINED,
                        on_click=handle_request_storage,
                    )
                )
            if on_add_folder:
                actions.append(
                    ft.OutlinedButton(
                        "Adicionar pasta",
                        icon=ft.Icons.CREATE_NEW_FOLDER,
                        on_click=handle_add_folder,
                    )
                )
            if on_scan_storage:
                actions.append(
                    ft.OutlinedButton(
                        "Atualizar",
                        icon=ft.Icons.REFRESH,
                        on_click=handle_scan_storage,
                    )
                )
            actions.append(
                ft.TextButton(
                    "Abrir configurações",
                    icon=ft.Icons.SETTINGS,
                    on_click=lambda _event: on_open_settings(),
                )
            )
            return ft.Container(
                content=ft.Column(
                    [
                        ft.Icon(ft.Icons.VIDEO_LIBRARY_OUTLINED, size=48, color=ACCENT),
                        ft.Text(
                            "Seu catálogo está vazio",
                            size=18,
                            weight=ft.FontWeight.BOLD,
                            color=TEXT,
                        ),
                        ft.Text(
                            "Permita a leitura de vídeos, use acesso amplo quando disponível, ou escolha uma pasta específica para montar sua biblioteca local.",
                            size=12,
                            color=TEXT_MUTED,
                            text_align=ft.TextAlign.CENTER,
                        ),
                        ft.Column(
                            actions,
                            horizontal_alignment=ft.CrossAxisAlignment.CENTER,
                            spacing=8,
                        ),
                    ],
                    horizontal_alignment=ft.CrossAxisAlignment.CENTER,
                    spacing=12,
                ),
                alignment=ft.Alignment(0, 0),
                padding=24,
            )

        async def open_collection(genre=None, state=None):
            if genre is not None:
                selected_genre[0] = genre
            if state is not None:
                selected_state[0] = state
            mode[0] = 'collection'
            save_view_state()
            await render_collection(reset=True)

        async def back_to_overview(_event=None):
            mode[0] = 'overview'
            save_view_state()
            await render()

        async def clear_filters(_event=None):
            selected_genre[0] = 'Todos'
            selected_state[0] = 'Todos'
            selected_sort[0] = 'Mais recentes'
            query[0] = ''
            mode[0] = 'collection'
            save_view_state()
            await render_collection(reset=True)

        async def on_search(event):
            query[0] = event.control.value or ''
            save_view_state()
            search_generation[0] += 1
            token = search_generation[0]
            await asyncio.sleep(0.18)
            if token != search_generation[0]:
                return
            await render_collection(reset=True)

        async def on_sort(event):
            selected_sort[0] = event.control.value or 'Mais recentes'
            save_view_state()
            await render_collection(reset=True)

        async def on_genre_change(event):
            selected_genre[0] = event.control.value or 'Todos'
            selected_state[0] = 'Todos'
            save_view_state()
            await render_collection(reset=True)

        async def load_next_collection_page():
            await load_collection_page(reset=False)

        async def load_collection_page(*, reset=False):
            if page_loading[0] or (not reset and not has_more[0]):
                return
            if reset:
                render_generation[0] += 1
                current_page[0] = 0
                has_more[0] = True
                total_matches[0] = 0
                catalog.clear()
                collection_grid.controls.clear()
            page_loading[0] = True
            token = render_generation[0]
            target_page = 0 if reset else current_page[0] + 1
            try:
                result = await asyncio.to_thread(
                    library.browse_catalog_page,
                    page=target_page, page_size=36,
                    query=query[0], state=selected_state[0], genre=selected_genre[0],
                    sort=selected_sort[0],
                )
            except Exception:
                logger.exception('Organize paged query failed', extra={'screen':'organize','page':target_page})
                page_loading[0] = False
                if reset:
                    collection_grid.controls.clear()
                    collection_summary.value = 'Não foi possível aplicar os filtros.'
                if is_active():
                    page.update()
                return
            if token != render_generation[0]:
                page_loading[0] = False
                return
            items = result.get('items') or []
            catalog.clear()
            catalog.extend(items)
            fresh = list(items)
            current_page[0] = int(result.get('page') or target_page)
            total_matches[0] = int(result.get('total') or 0)
            has_more[0] = bool(result.get('has_more'))
            collection_grid.controls.extend(anime_card(item) for item in fresh)
            description = []
            if query[0].strip(): description.append(f'busca "{query[0].strip()}"')
            if selected_genre[0] != 'Todos': description.append(f'gênero {selected_genre[0]}')
            if selected_state[0] != 'Todos': description.append(selected_state[0].lower())
            if selected_sort[0] != 'Mais recentes': description.append(selected_sort[0].lower())
            suffix = ' • '.join(description) if description else 'Todos os itens da biblioteca'
            collection_summary.value = f'{count_label(total_matches[0], "anime")} • {suffix}'
            page_loading[0] = False
            if is_active():
                page.update()
                await restore_scroll_position()

        def on_collection_scroll(event):
            try:
                view_state['scroll_position'] = float(event.pixels)
                remaining = float(event.max_scroll_extent - event.pixels)
            except (TypeError, ValueError, AttributeError):
                return
            if not is_active():
                return
            if remaining < 800 and has_more[0] and not page_loading[0] and mode[0] == 'collection':
                _start_view_task(load_next_collection_page)
        def render_overview(summary):
            if summary is None:
                content.controls.extend(
                    [
                        header("Organizar"),
                        empty_state(
                            ft.Icons.ERROR_OUTLINE,
                            "Não foi possível organizar a biblioteca",
                            "A biblioteca local não pôde ser consultada agora.",
                        ),
                    ]
                )
                return

            content.controls.extend([header("Organizar"), status])
            if catalog_load_failed[0]:
                content.controls.append(
                    empty_state(
                        ft.Icons.ERROR_OUTLINE,
                        "Não foi possível carregar a biblioteca",
                        "Tente novamente para ler o catálogo local.",
                        ft.FilledButton(
                            "Tentar novamente",
                            on_click=load_catalog,
                        ),
                    )
                )
                return
            all_collection_items = summary.get("collections") or []
            collections = [
                item for item in all_collection_items
                if int(item.get("count") or 0) > 0
            ]
            genres = [
                item for item in (summary.get("genres") or [])
                if int(item.get("count") or 0) > 0
            ]

            skipped_collections = len(all_collection_items) - len(collections)
            skipped_genres = len(summary.get("genres") or []) - len(genres)
            logger.info(
                "ORGANIZE_CATEGORY_COUNT collections=%s genres=%s",
                len(collections), len(genres),
            )
            skipped = skipped_collections + skipped_genres
            if skipped:
                logger.info(
                    "ORGANIZE_EMPTY_CATEGORY_SKIPPED count=%s collections=%s genres=%s",
                    skipped, skipped_collections, skipped_genres,
                )

            total_overview = sum(int(item.get("count") or 0) for item in collections)
            if total_overview == 0:
                if status.visible:
                    return
                content.controls.append(empty_catalog())
                return

            content.controls.extend(
                [
                    section_title("Categorias", ft.Icons.DASHBOARD_OUTLINED),
                    ft.Row(
                        [
                            state_button(
                                item["name"],
                                item["count"],
                            )
                            for item in collections
                        ],
                        scroll=ft.ScrollMode.AUTO,
                        spacing=10,
                    ),
                ]
            )
            if genres:
                content.controls.extend(
                    [
                        section_title("Gêneros", ft.Icons.LOCAL_OFFER_OUTLINED),
                        ft.Row([genre_card(item) for item in genres],
                               wrap=True, spacing=12, run_spacing=12),
                    ]
                )
            logger.info(
                "ORGANIZE_UI_BUILT collections=%s genres=%s",
                len(collections), len(genres),
            )

        def state_chip(label):
            active = (
                label == selected_state[0]
                and selected_genre[0] == "Todos"
            )

            async def handle(_event):
                await open_collection("Todos", label)

            return ft.OutlinedButton(
                label,
                on_click=handle,
                style=chip_style(active),
            )

        async def render_collection(reset=True):
            nonlocal collection_search, collection_genre, collection_sort
            if mode[0] != 'collection':
                mode[0] = 'collection'
            content.controls.clear()
            content.controls.extend([header(selected_genre[0] if selected_genre[0] != 'Todos' else selected_state[0], back_to_overview)])
            if status.visible:
                content.controls.append(status)

            collection_search = ft.TextField(
                value=query[0],
                hint_text='Buscar título, gênero, alias ou episódio',
                prefix_icon=ft.Icons.SEARCH, border_radius=RADIUS, border_width=0,
                bgcolor=SURFACE, color=TEXT, content_padding=12, text_size=14, expand=True,
                on_change=on_search, on_submit=on_search,
            )
            clear_button = ft.TextButton(
                'Limpar', icon=ft.Icons.CLEAR_ALL, on_click=clear_filters,
                visible=bool(query[0].strip()) or selected_state[0] != 'Todos' or selected_genre[0] != 'Todos' or selected_sort[0] != 'Mais recentes'
            )
            content.controls.append(ft.Row([collection_search, clear_button], spacing=8))
            content.controls.append(ft.Row([state_chip(label) for label in OrganizeView._STATE_ORDER], scroll=ft.ScrollMode.AUTO, spacing=8))

            collection_token = render_generation[0]
            try:
                summary = await asyncio.to_thread(library.organize_summary_bounded)
                genres = ['Todos'] + [item['name'] for item in summary.get('genres', [])]
            except Exception:
                logger.exception('Organize bounded summary failed')
                genres = ['Todos']
            if collection_token != render_generation[0]:
                return
            if selected_genre[0] not in genres:
                selected_genre[0] = 'Todos'
                save_view_state()
            collection_genre = ft.Dropdown(value=selected_genre[0], label='Gênero', width=235, options=[ft.dropdown.Option(value, value) for value in genres])
            collection_genre.on_change = on_genre_change
            collection_sort = ft.Dropdown(value=selected_sort[0], label='Ordenar', width=235, options=[ft.dropdown.Option(value, value) for value in OrganizeView._SORTS])
            collection_sort.on_select = on_sort
            content.controls.append(ft.Row([collection_sort, collection_genre], wrap=True, spacing=8))
            content.controls.append(collection_summary)
            collection_grid.controls.clear()
            content.controls.append(collection_grid)
            content.on_scroll = on_collection_scroll
            await load_collection_page(reset=reset)

        async def render_collection_only():
            if mode[0] != 'collection':
                mode[0] = 'collection'
                save_view_state()
            # load_collection_page() performs the single required UI update after
            # the catalog controls are populated.
            await render_collection(reset=True)
        async def load_overview(*, token=None):
            if token is None:
                render_generation[0] += 1
                token = render_generation[0]
            overview_started = time.perf_counter()
            logger.info("ORGANIZE_QUERY_START token=%s", token)
            try:
                summary = await asyncio.to_thread(library.organize_summary_bounded)
            except Exception:
                logger.exception('Organize overview load failed')
                summary = None
            if token != render_generation[0]:
                return
            content.controls.clear()
            render_overview(summary)
            if is_active():
                page.update()
            performance.event(
                'organize.overview_load',
                duration_ms=(time.perf_counter() - overview_started) * 1000.0,
                screen='organize',
                metadata={
                    'collections': len((summary or {}).get('collections') or []),
                    'genres': len((summary or {}).get('genres') or []),
                },
            )

        async def render():
            if mode[0] == 'overview':
                await load_overview()
                return
            # load_collection_page() already commits the catalog UI update.
            await render_collection(reset=True)
        async def refresh_from_catalog():
            save_view_state()
            if mode[0] == 'collection':
                await render_collection(reset=True)
            else:
                await load_overview()

        async def load_catalog():
            token = render_generation[0]
            try:
                last_scan = await asyncio.to_thread(library.last_scan)
                catalog_load_failed[0] = False
            except Exception:
                logger.exception('Organize local state load failed', extra={'screen':'organize'})
                catalog_load_failed[0] = True
                scan_active[0] = False
            else:
                scan_active[0] = bool(last_scan and str(last_scan.get('status') or '').casefold() in {'running','started'})
            if token != render_generation[0]:
                return
            status.visible = scan_active[0]
            if scan_active[0]:
                status.controls = [
                    ft.ProgressRing(width=16, height=16, stroke_width=2, color=ACCENT),
                    ft.Text('Descobrindo vídeos locais…', color=TEXT_MUTED, size=12),
                ]
            await render()
            if is_active():
                await restore_scroll_position()
        content.on_scroll = on_collection_scroll
        async def restore_scroll_position():
            stored = view_state.get('scroll_position')
            if stored is None:
                return
            try:
                result = content.scroll_to(offset=float(stored), duration=0)
                if inspect.isawaitable(result):
                    await result
            except Exception:
                logger.debug('Organize scroll restoration unavailable', exc_info=True)

        def schedule_refresh_from_catalog():
            catalog_refresh_dirty[0] = True
            if catalog_refresh_scheduled[0]:
                return
            catalog_refresh_scheduled[0] = True

            async def run_catalog_refreshes():
                try:
                    while catalog_refresh_dirty[0]:
                        catalog_refresh_dirty[0] = False
                        await refresh_from_catalog()
                finally:
                    catalog_refresh_scheduled[0] = False
                    if catalog_refresh_dirty[0]:
                        schedule_refresh_from_catalog()

            _start_view_task(run_catalog_refreshes)

        def invalidate_view_tasks():
            # Generation guards already protect page queries; advance them when
            # the cached control tree is discarded so old tasks cannot render it.
            render_generation[0] += 1
            catalog_refresh_dirty[0] = False
            cancel_view_tasks()

        view_state['_refresh_from_catalog'] = schedule_refresh_from_catalog
        view_state['_invalidate_view_tasks'] = invalidate_view_tasks
        save_view_state()
        render_generation[0] += 1
        content.controls.extend([header("Organizar"), status])
        _start_view_task(load_catalog)
        result = ft.Container(
            content=content,
            padding=ft.Padding(
                left=PAGE_PADDING,
                right=PAGE_PADDING,
                top=18,
                bottom=8,
            ),
            bgcolor=BACKGROUND,
            expand=True,
        )
        performance.record_ui_build("organize", (performance.now()-build_started)*1000.0,
                                    controls=performance.control_count(result), cached=False)
        return result
