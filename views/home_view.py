from __future__ import annotations

import asyncio
import inspect
import logging
import os
import time
import flet as ft

from core.consumption import consumption_state, progress_ratio
from core.settings import SettingsStore
from core.performance import get_performance_monitor
from core.ui import ACCENT, BACKGROUND, PAGE_PADDING, RADIUS, SURFACE, TEXT, TEXT_MUTED, activate_theme_for_page, empty_state, media_artwork, count_label, focus_button_style
from core.library_discovery import format_duration

logger = logging.getLogger(__name__)

HOME_PULL_REFRESH_THRESHOLD = 72.0


def _pull_refresh_should_trigger(
    *,
    gesture_active: bool,
    at_top: bool,
    overscroll: float,
    threshold: float = HOME_PULL_REFRESH_THRESHOLD,
    page_loading: bool = False,
    refresh_state: str = "IDLE",
) -> bool:
    """Return whether the current pull gesture is eligible to trigger refresh."""
    try:
        distance = float(overscroll)
        limit = float(threshold)
    except (TypeError, ValueError):
        return False
    return bool(
        gesture_active
        and at_top
        and not page_loading
        and str(refresh_state or "IDLE").upper() != "REFRESHING"
        and distance >= limit
    )


class HomeView:
    """CloudStream-inspired local library home with async data access and compact cards."""

    @staticmethod
    def build(page: ft.Page, library, on_select_anime, on_open_settings, on_play_episode, on_open_organize=None,
              view_state=None, on_request_thumbnail=None, on_open_collector=None, on_refresh_library=None,
              on_refresh_ui_updated=None, on_refresh_ui_failed=None, on_open_library=None, is_active=None):
        performance = get_performance_monitor()
        build_started = performance.now()
        performance.counter("ui.builds_requested.home")
        theme = activate_theme_for_page(page)
        BACKGROUND = theme.background
        SURFACE = theme.surface
        TEXT = theme.text
        TEXT_MUTED = theme.text_muted
        ACCENT = theme.primary
        catalog: list[dict] = []
        continuing: list[dict] = []
        home_data: dict = {}
        view_state = view_state if view_state is not None else {}
        is_active = is_active or (lambda: True)
        settings = SettingsStore(library.store)
        sort_labels = {
            "added_desc": "Mais recentes",
            "title_asc": "Nome A-Z",
            "title_desc": "Nome Z-A",
            "recently_watched": "Assistidos recentemente",
        }
        selected_state = [view_state.get("state", "Todos")]
        selected_genre = [view_state.get("genre", "Todos")]
        selected_sort = [view_state.get("sort", sort_labels[settings.get("library.sort_default")])]
        selected_media_type = [view_state.get("media_type", "Todos")]
        card_size = {"small": 120, "medium": 146, "large": 172}[settings.get("appearance.card_size")]
        density_gap = {"small": 14, "medium": 10, "large": 6}[settings.get("library.grid_density")]
        card_width = card_size
        card_height = round(card_size * 176 / 146)
        show_thumbnails = settings.get("appearance.show_thumbnails")
        configured_page_size = settings.get("library.page_size")
        try:
            page_size = max(1, int(configured_page_size))
        except (TypeError, ValueError):
            page_size = 24
        # Keep the eager Flet Row bounded even when a large user preference is
        # selected. Pagination still loads the complete library incrementally.
        home_page_size = min(page_size, 48)
        selected_tag = [view_state.get("tag", "Todos")]
        selected_season = [view_state.get("season", "Todos")]
        selected_episode_type = [view_state.get("episode_type", "Todos")]
        selected_availability = [view_state.get("availability", "Todos")]
        selected_metadata = [view_state.get("metadata", "Todos")]
        selected_artwork = [view_state.get("artwork", "Todos")]
        logger.info("HOME_RENDER_LIMIT configured_page_size=%s effective_page_size=%s", configured_page_size, home_page_size)
        search_visible = [bool(view_state.get("search_visible", False))]
        render_generation = [0]
        search_generation = [0]
        current_page = [0]
        has_more = [True]
        total_matches = [0]
        page_loading = [False]
        page_load_scheduled = [False]
        scan_active = [False]
        home_sections_generation = [0]
        filter_options_loaded = [False]
        artwork_tasks: set[tuple] = set()
        artwork_pending_items: dict[tuple, dict] = {}
        artwork_request_tokens: dict[tuple, tuple] = {}
        artwork_token_counter = [0]
        artwork_bindings: dict[tuple, list] = {}
        catalog_focus_targets: dict[str, object] = {}
        artwork_concurrency = asyncio.Semaphore(1)
        artwork_batch_scheduled = [False]
        artwork_ui_update_scheduled = [False]
        catalog_refresh_scheduled = [False]
        catalog_refresh_dirty = [False]
        refresh_after_load_pending = [bool(view_state.get("_manual_refresh_pending"))]
        refresh_state = [str(view_state.get("_refresh_state") or "IDLE").upper()]
        refresh_button = [None]
        pull_refresh_indicator = [None]
        pull_refresh_label = [None]
        pull_refresh_active = [False]
        pull_gesture_active = [False]
        pull_gesture_at_top = [False]
        pull_overscroll = [0.0]
        pull_threshold = HOME_PULL_REFRESH_THRESHOLD
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
                        logger.debug("Home task completion hook unavailable", exc_info=True)
            return task

        def cancel_view_tasks():
            tasks = tuple(view_tasks)
            view_tasks.clear()
            page_load_scheduled[0] = False
            for task in tasks:
                cancel = getattr(task, "cancel", None)
                if callable(cancel):
                    try:
                        cancel()
                    except Exception:
                        logger.debug("Home task cancellation failed", exc_info=True)

        def _set_pull_indicator(visible, message=None):
            indicator = pull_refresh_indicator[0]
            label = pull_refresh_label[0]
            changed = False
            if label is not None and message is not None and label.value != message:
                label.value = message
                changed = True
            if indicator is not None and indicator.visible != bool(visible):
                indicator.visible = bool(visible)
                changed = True
            if changed and is_active():
                performance.counter("home.pull_refresh.indicator_updates")
                page.update()

        def _schedule_refresh_reset(expected_state, delay=0.8):
            async def reset_state():
                await asyncio.sleep(delay)
                if refresh_state[0] == expected_state:
                    if is_active():
                        set_refresh_state("IDLE")
                    else:
                        view_state["_refresh_state"] = "IDLE"
                        refresh_state[0] = "IDLE"
            _start_view_task(reset_state)

        def set_refresh_state(state, *, update=True):
            normalized = str(state or "IDLE").upper()
            if normalized not in {"IDLE", "REFRESHING", "SUCCESS", "ERROR"}:
                normalized = "IDLE"
            was_pull_refresh = pull_refresh_active[0]
            if normalized in {"IDLE", "SUCCESS", "ERROR"}:
                pull_refresh_active[0] = False
            if normalized == "SUCCESS" and was_pull_refresh:
                logger.info("PULL_REFRESH_COMPLETED")
            elif normalized == "ERROR" and was_pull_refresh:
                logger.info("PULL_REFRESH_CANCELLED reason=refresh_error")
            refresh_state[0] = normalized
            view_state["_refresh_state"] = normalized
            indicator = pull_refresh_indicator[0]
            if indicator is not None:
                indicator.visible = bool(
                    is_active()
                    and (
                        (
                            normalized == "REFRESHING"
                            and pull_refresh_active[0]
                        )
                        or (
                            normalized == "IDLE"
                            and pull_gesture_active[0]
                            and pull_gesture_at_top[0]
                        )
                    )
                )
            if pull_refresh_label[0] is not None:
                if normalized == "REFRESHING":
                    pull_refresh_label[0].value = "Atualizando biblioteca…"
                elif normalized in {"SUCCESS", "ERROR"}:
                    pull_refresh_label[0].value = "Atualizando biblioteca…"
                elif pull_gesture_active[0] and pull_gesture_at_top[0]:
                    pull_refresh_label[0].value = (
                        "Solte para atualizar"
                        if pull_overscroll[0] >= pull_threshold
                        else "Puxe para atualizar"
                    )
            button = refresh_button[0]
            if button is not None:
                if normalized == "REFRESHING":
                    button.icon = ft.Icons.SYNC
                    button.tooltip = "Atualizando biblioteca…"
                    button.disabled = True
                elif normalized == "SUCCESS":
                    button.icon = ft.Icons.CHECK_CIRCLE_OUTLINE
                    button.tooltip = "Biblioteca atualizada"
                    button.disabled = False
                elif normalized == "ERROR":
                    button.icon = ft.Icons.ERROR_OUTLINE
                    button.tooltip = "Falha ao atualizar • tocar para tentar novamente"
                    button.disabled = False
                else:
                    button.icon = ft.Icons.REFRESH
                    button.tooltip = "Atualizar biblioteca"
                    button.disabled = False
            if update and is_active():
                performance.counter("home.refresh.page_updates")
                page.update()

        async def handle_manual_refresh(_event=None, *, source="button"):
            if refresh_state[0] == "REFRESHING":
                performance.counter("home.refresh.rejected")
                logger.info(
                    "HOME_REFRESH_REJECTED source=%s reason=refresh_in_progress",
                    source,
                )
                if source == "pull":
                    pull_refresh_active[0] = False
                    _set_pull_indicator(False)
                    logger.info("PULL_REFRESH_REJECTED reason=refresh_in_progress")
                return
            if not callable(on_refresh_library):
                set_refresh_state("ERROR")
                logger.warning("Home refresh requested without a refresh callback")
                return
            if source == "pull":
                pull_refresh_active[0] = True
                performance.counter("home.pull_refresh.triggered")
                logger.info("PULL_REFRESH_TRIGGERED")
            else:
                performance.counter("home.refresh.button_tapped")
            set_refresh_state("REFRESHING", update=False)
            try:
                result = await on_refresh_library(source=source)
                if isinstance(result, tuple):
                    message, waiting = result
                else:
                    message, waiting = str(result or ""), True
                if message:
                    page.snack_bar = ft.SnackBar(ft.Text(str(message)))
                    page.snack_bar.open = True
                if not waiting:
                    set_refresh_state("ERROR", update=False)
                    if source == "pull":
                        pull_refresh_active[0] = False
                    _schedule_refresh_reset("ERROR", delay=1.6)
                if is_active():
                    page.update()
            except Exception:
                logger.exception("Home manual refresh failed")
                performance.counter("home.refresh.failed")
                if source == "pull":
                    pull_refresh_active[0] = False
                set_refresh_state("ERROR", update=False)
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível atualizar a biblioteca agora."))
                page.snack_bar.open = True
                _schedule_refresh_reset("ERROR", delay=1.6)
                if is_active():
                    page.update()


        def save_view_state():
            view_state.update(
                state=selected_state[0], genre=selected_genre[0], sort=selected_sort[0],
                media_type=selected_media_type[0], tag=selected_tag[0], season=selected_season[0],
                episode_type=selected_episode_type[0], availability=selected_availability[0],
                metadata=selected_metadata[0], artwork=selected_artwork[0],
                search_visible=search_visible[0], query=search.value or "",
            )

        def ratio(item):
            return progress_ratio(item)

        grid = ft.Row(wrap=True, spacing=density_gap, run_spacing=max(8, density_gap + 2))
        status = ft.Row(
            [ft.ProgressRing(width=16, height=16, stroke_width=2, color=ACCENT),
             ft.Text("Carregando biblioteca local…", color=TEXT_MUTED, size=12)],
            spacing=8,
        )
        feedback = ft.Container(visible=False)
        hydration_status = ft.Text("", color=TEXT_MUTED, size=11, visible=False)
        library_label = ft.Text("MINHA BIBLIOTECA", size=13, weight=ft.FontWeight.BOLD, color=TEXT_MUTED)
        search = ft.TextField(
            value=view_state.get("query", ""), visible=search_visible[0],
            hint_text="Buscar na sua biblioteca", prefix_icon=ft.Icons.SEARCH,
            border_radius=RADIUS, border_width=0, bgcolor=SURFACE, color=TEXT,
            content_padding=12, text_size=14,
        )
        sort = ft.Dropdown(
            value=selected_sort[0], width=175, dense=True, text_size=12, color=TEXT,
            bgcolor=SURFACE, border_color=theme.border, border_radius=RADIUS,
            options=[ft.dropdown.Option(key=value, text=value) for value in (
                "Mais recentes", "Assistidos recentemente", "Progresso", "Episódio",
                "Temporada + episódio", "Modificação", "Duração", "Tamanho",
                "Favoritos primeiro", "Fixados primeiro", "Nome A-Z", "Nome Z-A",
            )],
        )
        state_filter = ft.Dropdown(value=selected_state[0], label="Estado", width=220, options=[
            ft.dropdown.Option(v) for v in (
                "Todos", "Favoritos", "Fixados", "Não assistidos",
                "Em andamento", "Concluídos", "Assistidos",
            )
        ])
        genre_filter = ft.Dropdown(value=selected_genre[0], label="Gênero", width=220, options=[ft.dropdown.Option("Todos")])
        media_type = ft.Dropdown(value=selected_media_type[0], label="Tipo", width=220, options=[
            ft.dropdown.Option("Todos", "Todos"), *[ft.dropdown.Option(v, v) for v in ("Série/Anime", "Filme", "Especial", "Episódio")]
        ])
        tag = ft.Dropdown(value=selected_tag[0], label="Etiqueta", width=220, options=[ft.dropdown.Option("Todos", "Todos")])
        season = ft.Dropdown(value=selected_season[0], label="Temporada", width=220, options=[ft.dropdown.Option("Todos", "Todos")])
        episode_type = ft.Dropdown(value=selected_episode_type[0], label="Tipo de episódio", width=220, options=[ft.dropdown.Option("Todos", "Todos")])
        availability = ft.Dropdown(value=selected_availability[0], label="Disponibilidade", width=220, options=[
            ft.dropdown.Option(v) for v in ("Todos", "Disponível", "Com missing", "Sem missing")
        ])
        metadata_filter = ft.Dropdown(value=selected_metadata[0], label="Metadata", width=220, options=[
            ft.dropdown.Option(v) for v in ("Todos", "Disponível", "Ausente")
        ])
        artwork_filter = ft.Dropdown(value=selected_artwork[0], label="Capa", width=220, options=[
            ft.dropdown.Option(v) for v in ("Todos", "Disponível", "Ausente")
        ])
        filter_summary = ft.Text(size=11, color=TEXT_MUTED)

        continue_row = ft.Row(scroll=ft.ScrollMode.AUTO, spacing=10)
        continuation_section = ft.Container(
            content=ft.Column([ft.Text("CONTINUAR ASSISTINDO", size=13, weight=ft.FontWeight.BOLD, color=TEXT_MUTED), continue_row], spacing=9),
            visible=False,
        )
        section_rows: dict[str, ft.Row] = {}
        section_cards: dict[str, ft.Container] = {}
        section_signatures: dict[str, tuple] = {}
        continue_signature = [None]

        def schedule_artwork_ui_update():
            if artwork_ui_update_scheduled[0]:
                return
            if not is_active():
                return
            artwork_ui_update_scheduled[0] = True

            async def flush():
                try:
                    # page.run_task schedules this flush after the current callback
                    # returns, so same-turn completions can share one UI update
                    # without an artificial delay.
                    if not is_active():
                        return
                    update_started = time.perf_counter()
                    performance.counter("home.page_updates.artwork")
                    page.update()
                    logger.info(
                        "HOME_ARTWORK_UI_UPDATE duration_ms=%s",
                        int((time.perf_counter() - update_started) * 1000),
                    )
                except Exception:
                    logger.debug("Home artwork batch UI update skipped", exc_info=True)
                finally:
                    artwork_ui_update_scheduled[0] = False

            _start_view_task(flush)

        def _valid_artwork_source(path):
            if not isinstance(path, str):
                return False
            if path.startswith(("content://", "file://")):
                return True
            try:
                return os.path.isfile(path) and os.path.getsize(path) > 0
            except OSError:
                return False

        def _apply_artwork(holder, width, height, path):
            if not _valid_artwork_source(path):
                return False
            holder.content = ft.Image(
                src=path,
                width=width,
                height=height,
                fit=ft.BoxFit.COVER,
                border_radius=RADIUS,
            )
            return True

        def update_artwork_in_place(entity, item_id, artwork_type, local_path):
            """Apply a newly materialized canonical artwork path without rebuilding Home."""
            if str(artwork_type or "").strip().lower() != "poster":
                return False
            if not _valid_artwork_source(local_path):
                return False
            try:
                normalized_id = int(item_id)
            except (TypeError, ValueError):
                return False
            binding_key = (
                "movie" if str(entity or "").strip().lower() == "movie" else "anime",
                normalized_id,
                "poster",
            )
            changed = False
            for holder, width, height in artwork_bindings.get(binding_key, ()):
                changed = _apply_artwork(holder, width, height, local_path) or changed

            # Keep the in-memory view projection aligned with the persisted cache
            # so future incremental operations do not reintroduce the placeholder.
            for item in (*catalog, *continuing):
                if not isinstance(item, dict):
                    continue
                item_id_value = item.get("id")
                if item_id_value is None:
                    item_id_value = item.get("anime_id")
                try:
                    same_id = int(item_id_value) == normalized_id
                except (TypeError, ValueError):
                    same_id = False
                item_entity = (
                    "movie"
                    if str(item.get("media_kind") or (item.get("meta") or {}).get("media_kind") or "").casefold() == "movie"
                    else "anime"
                )
                if same_id and item_entity == binding_key[0]:
                    item["cover"] = local_path
                    item.setdefault("meta", {})["cover_cache"] = local_path
                    changed = True

            return changed

        if isinstance(view_state, dict):
            view_state["_update_artwork"] = update_artwork_in_place

        def _queue_artwork_resolution(entity, item_id, kind, item):
            try:
                normalized_id = int(item_id)
            except (TypeError, ValueError):
                return None
            entity = str(entity or "").strip()
            kind = str(kind or "").strip()
            if not entity or not kind or item is None:
                return None
            key = (entity, normalized_id, kind)
            artwork_token_counter[0] += 1
            artwork_resolution_token = (
                entity,
                normalized_id,
                kind,
                artwork_token_counter[0],
            )
            artwork_tasks.add(key)
            artwork_request_tokens[key] = artwork_resolution_token
            artwork_pending_items[key] = item
            performance.gauge("home.artwork.pending", len(artwork_tasks))
            return artwork_resolution_token

        async def _flush_artwork_batch():
            if not is_active() or not artwork_tasks:
                return
            generation = render_generation[0]
            snapshot = tuple(artwork_tasks)
            snapshot_tokens = {
                key: artwork_request_tokens.get(key)
                for key in snapshot
            }
            grouped: dict[tuple[str, str], list[int]] = {}
            for entity, item_id, kind in snapshot:
                grouped.setdefault((entity, kind), []).append(int(item_id))

            try:
                for (entity, kind), ids in grouped.items():
                    if generation != render_generation[0] or not is_active():
                        return
                    async with artwork_concurrency:
                        resolved = await asyncio.to_thread(
                            library.resolve_artwork_batch,
                            entity,
                            list(dict.fromkeys(ids)),
                            (kind,),
                        )
                    if generation != render_generation[0] or not is_active():
                        return
                    for item_id_raw, row in (resolved or {}).items():
                        item_id = int(item_id_raw)
                        key = (entity, item_id, kind)
                        if snapshot_tokens.get(key) != artwork_request_tokens.get(key):
                            performance.counter("home.artwork.stale_ignored")
                            logger.info(
                                "STALE_ARTWORK_IGNORED entity=%s entity_id=%s type=%s generation=%s request_generation=%s",
                                entity,
                                item_id,
                                kind,
                                generation,
                                (snapshot_tokens.get(key) or ("", 0, "", 0))[-1],
                            )
                            continue
                        path = (row or {}).get("local_path")
                        item = artwork_pending_items.get(key)
                        if item is not None and _valid_artwork_source(path):
                            if kind == "poster":
                                meta = item.setdefault("meta", {})
                                meta["cover_cache"] = path
                                item["cover"] = path
                            elif kind == "episode_thumbnail":
                                item["episode_thumbnail"] = path
                        for holder, width, height in artwork_bindings.get(key, ()):
                            if _apply_artwork(holder, width, height, path):
                                performance.counter("home.artwork.batch_updated")
                if generation == render_generation[0] and is_active():
                    schedule_artwork_ui_update()
            except Exception:
                logger.exception("Home batched artwork lookup failed", extra={"screen": "home"})
            finally:
                for key in snapshot:
                    if artwork_request_tokens.get(key) == snapshot_tokens.get(key):
                        artwork_tasks.discard(key)
                        artwork_pending_items.pop(key, None)
                        artwork_request_tokens.pop(key, None)
                performance.gauge("home.artwork.pending", len(artwork_tasks))
                artwork_batch_scheduled[0] = False
                if artwork_tasks and generation == render_generation[0] and is_active():
                    schedule_artwork_batch_prefetch()

        def schedule_artwork_batch_prefetch():
            if artwork_batch_scheduled[0] or not artwork_tasks or not is_active():
                return
            artwork_batch_scheduled[0] = True
            _start_view_task(_flush_artwork_batch)

        def artwork_holder(item, width, height, *, entity="anime", kind="poster", source=None):
            holder = ft.Container(
                width=width, height=height, border_radius=RADIUS, bgcolor=theme.surface_raised,
                alignment=ft.Alignment(0, 0),
            )
            binding_entity = "movie" if item.get("media_kind") == "movie" and entity == "anime" else entity
            binding_id = item.get("anime_id") if item.get("anime_id") is not None and binding_entity in {"anime", "movie"} else item.get("id")
            if binding_id is not None:
                artwork_bindings.setdefault((binding_entity, int(binding_id), kind), []).append((holder, width, height))

            if not show_thumbnails:
                holder.content = ft.Icon(ft.Icons.MOVIE_OUTLINED, color=TEXT_MUTED, size=28)
                return holder

            # Poster sources are resolved only through the ArtworkEngine. This
            # prevents a stale anime.cover_cache from bypassing poster type
            # isolation after an older thumbnail contamination.
            direct_source = source if kind == "episode_thumbnail" else None
            if _apply_artwork(holder, width, height, direct_source):
                return holder

            candidate_source = item.get("episode_thumbnail") if kind == "episode_thumbnail" else None
            if _apply_artwork(holder, width, height, candidate_source):
                return holder

            item_id = item.get("anime_id") if item.get("anime_id") is not None and entity in {"anime", "movie"} else item.get("id")
            if item_id is not None and library is not None:
                _queue_artwork_resolution(binding_entity, item_id, kind, item)
            holder.content = ft.Icon(ft.Icons.MOVIE_OUTLINED, color=TEXT_MUTED, size=28)
            return holder

        def player_episode_title(anime_title, episode):
            if episode.get("episode_type") == "movie":
                return anime_title
            season_value = episode.get("season")
            number_value = episode.get("number")
            if season_value is not None and number_value is not None:
                return f"{anime_title} • T{int(season_value)} E{int(number_value)}"
            if season_value is not None:
                return f"{anime_title} • T{int(season_value)}"
            return anime_title

        def play_continuation(item):
            episode = item.get("episode", item)
            if not episode.get("path") or episode.get("missing"):
                return
            anime_title = item.get("anime_title") or item.get("main_title") or item.get("title") or "Reproduzir"
            on_play_episode(
                episode["path"],
                player_episode_title(anime_title, episode),
                progress_seconds=episode.get("progress", 0) or 0,
                episode_id=episode.get("id"),
                anime_id=item.get("anime_id"),
            )

        async def reveal_focus(scrollable, scroll_key):
            try:
                await scrollable.scroll_to(scroll_key=scroll_key, duration=120)
            except Exception:
                logger.debug("Home focus scroll skipped key=%s", scroll_key, exc_info=True)

        async def reveal_catalog_focus(index: int, key: str):
            await reveal_focus(layout, key)
            if index != len(catalog) - 1 or not has_more[0] or page_loading[0]:
                return
            previous_count = len(catalog)
            await load_library_page(reset=False)
            if len(catalog) <= previous_count:
                return
            next_item = catalog[previous_count]
            next_id = next_item.get("id")
            next_key = f"home-catalog-{next_id if next_id is not None else previous_count}"
            target = catalog_focus_targets.get(next_key)
            if target is not None:
                try:
                    await target.focus()
                except Exception:
                    logger.debug("Home pagination focus restore skipped key=%s", next_key, exc_info=True)

        def home_card(item, action=None, episode=False):
            meta = item.get("meta") or {}
            cover = item.get("cover") or meta.get("cover_cache")
            if episode:
                title = item.get("anime_title") or item.get("title") or "Mídia local"
                subtitle = item.get("episode_title") or (f"T{item.get('season')} E{item.get('number')}" if item.get("season") is not None else "Episódio")
                holder = artwork_holder(
                    item,
                    card_width,
                    card_height,
                    entity="episode",
                    kind="episode_thumbnail",
                    source=item.get("episode_thumbnail"),
                )
            else:
                title = item.get("main_title") or item.get("anime_title") or meta.get("title") or "Mídia local"
                subtitle = "Filme" if item.get("media_kind") == "movie" else (count_label(item.get("available_count", 0), "episódio") if item.get("available_count") else "")
                holder = artwork_holder(item, card_width, card_height, source=cover)
            if episode and not item.get("episode_thumbnail") and on_request_thumbnail:
                candidate = item.get("episode") or item.get("current_episode")
                if not candidate and item.get("seasons"):
                    candidate = next((ep for season_data in item.get("seasons", []) for ep in season_data.get("episodes", []) if ep.get("path") and not ep.get("missing")), None)
                if candidate:
                    on_request_thumbnail(candidate, priority=300)
            performance.counter("home.cards_created")
            return ft.OutlinedButton(
                width=card_width,
                height=card_height + 48,
                on_click=(lambda _, value=item: action(value)) if action else None,
                style=focus_button_style(theme=theme, background=SURFACE),
                content=ft.Column([holder,
                    ft.Text(title, size=12, weight=ft.FontWeight.BOLD, color=TEXT, max_lines=2, overflow=ft.TextOverflow.ELLIPSIS),
                    ft.Text(subtitle, size=10, color=TEXT_MUTED, max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                    ft.ProgressBar(value=ratio(item), color=ACCENT, bgcolor=theme.surface_variant, height=3, visible=consumption_state(item).value == "in_progress"),
                ], spacing=4),
            )

        async def reveal_section_focus(row, key):
            await reveal_focus(row, key)
            try:
                await layout.scroll_to(scroll_key=key, duration=120)
            except Exception:
                logger.debug("Home vertical focus scroll skipped key=%s", key, exc_info=True)

        def _visible_item_signature(item, *, episode=False):
            if not isinstance(item, dict):
                return (repr(item),)
            meta = item.get("meta") or {}
            return (
                item.get("id"),
                item.get("anime_id"),
                item.get("path"),
                item.get("main_title") or item.get("anime_title") or item.get("title"),
                item.get("episode_title"),
                item.get("season"),
                item.get("number"),
                item.get("favorite"),
                item.get("is_pinned"),
                item.get("progress"),
                item.get("duration"),
                item.get("watched"),
                item.get("last_played_at"),
                item.get("missing"),
                item.get("media_kind"),
                item.get("available_count"),
                item.get("watched_count"),
                item.get("missing_count"),
                item.get("cover"),
                meta.get("cover_cache"),
                meta.get("cover_url"),
                "episode" if episode else "anime",
            )

        def render_section(title, key, items, action=None, episode=False):
            section_started = time.perf_counter()
            performance.event("HOME_SECTION_RENDER_START", screen="home",
                              metadata={"section": key, "generation": render_generation[0]})
            visible_items = list((items or [])[:8])
            signature = tuple(_visible_item_signature(item, episode=episode) for item in visible_items)
            row = section_rows.setdefault(key, ft.Row(scroll=ft.ScrollMode.AUTO, spacing=10))
            card = section_cards.get(key)
            if card is not None and section_signatures.get(key) == signature:
                card.visible = bool(visible_items)
                return False

            row.controls.clear()
            for index, item in enumerate(visible_items):
                control = home_card(item, action=action, episode=episode)
                focus_key = f"home-section-{key}-{index}"
                control.key = focus_key
                control.on_focus = lambda _event, container=row, k=focus_key: _start_view_task(
                    reveal_section_focus, container, k
                )
                row.controls.append(control)
            if card is None:
                card = ft.Container(
                    content=ft.Column(
                        [ft.Text(title, size=15, weight=ft.FontWeight.BOLD, color=TEXT), row],
                        spacing=9,
                    )
                )
                section_cards[key] = card
            card.visible = bool(visible_items)
            card.content.controls[1] = row
            section_signatures[key] = signature
            performance.event("HOME_SECTION_RENDER_END",
                              duration_ms=(time.perf_counter() - section_started) * 1000.0,
                              screen="home",
                              metadata={"section": key, "items": len(visible_items),
                                        "generation": render_generation[0]})
            return True

        def _library_filters():
            return dict(
                query=search.value or "", state=selected_state[0], genre=selected_genre[0], sort=selected_sort[0],
                tag=selected_tag[0], media_type=selected_media_type[0], season=selected_season[0],
                episode_type=selected_episode_type[0], availability=selected_availability[0],
                metadata=selected_metadata[0], artwork=selected_artwork[0],
            )

        async def load_library_page(*, reset=False):
            if page_loading[0]:
                if reset:
                    refresh_after_load_pending[0] = True
                return True
            if not reset and not has_more[0]:
                return True
            performance.event("HOME_BROWSE_START", screen="home",
                              metadata={"reset": reset, "generation": render_generation[0]})
            if reset:
                render_generation[0] += 1
                current_page[0] = 0
                has_more[0] = True
                total_matches[0] = 0
            page_loading[0] = True
            token = render_generation[0]
            target_page = 0 if reset else current_page[0] + 1
            browse_started = time.perf_counter()
            try:
                result = await asyncio.to_thread(
                    library.browse_catalog_page,
                    page=target_page, page_size=home_page_size, **_library_filters(),
                )
            except Exception:
                logger.exception("Home paged query failed", extra={"screen":"home","page":target_page})
                if reset:
                    feedback.content = empty_state(ft.Icons.ERROR_OUTLINE, "Não foi possível ler a biblioteca local agora.", "Tente novamente.")
                    feedback.visible = True
                    if is_active():
                        page.update()
                page_loading[0] = False
                return False
            finally:
                performance.event(
                    "HOME_BROWSE_END",
                    duration_ms=(time.perf_counter() - browse_started) * 1000.0,
                    screen="home",
                    metadata={"page": target_page, "reset": reset, "generation": token},
                )
                logger.info(
                    "HOME_BROWSE_CATALOG_PAGE duration_ms=%s page=%s reset=%s",
                    int((time.perf_counter() - browse_started) * 1000),
                    target_page,
                    reset,
                )
            if token != render_generation[0]:
                page_loading[0] = False
                return
            page_items = result.get("items") or []
            if reset:
                artwork_bindings.clear()
                artwork_tasks.clear()
                artwork_pending_items.clear()
                artwork_request_tokens.clear()
                catalog_focus_targets.clear()
                catalog.clear()
                grid.controls.clear()
                feedback.visible = False
            existing_ids = {int(item.get("id")) for item in catalog if item.get("id") is not None}
            fresh_items = [item for item in page_items if item.get("id") is None or int(item.get("id")) not in existing_ids]
            catalog.extend(fresh_items)
            current_page[0] = int(result.get("page") or target_page)
            total_matches[0] = int(result.get("total") or 0)
            has_more[0] = bool(result.get("has_more"))
            if catalog:
                library_label.value = f"MINHA BIBLIOTECA • {total_matches[0]}"
                feedback.visible = False
                start_index = len(catalog) - len(fresh_items)
                for offset, item in enumerate(fresh_items):
                    index = start_index + offset
                    key = f"home-catalog-{item.get('id') if item.get('id') is not None else index}"
                    control = card(item)
                    control.key = key
                    control.on_focus = lambda _event, i=index, k=key: _start_view_task(reveal_catalog_focus, i, k)
                    catalog_focus_targets[key] = control
                    grid.controls.append(control)
            elif scan_active[0]:
                library_label.value = "DESCOBRINDO BIBLIOTECA LOCAL…"
                feedback.visible = False
            else:
                library_label.value = "MINHA BIBLIOTECA"
                feedback.content = empty_state(ft.Icons.SEARCH_OFF if (search.value or "").strip() else ft.Icons.VIDEO_LIBRARY_OUTLINED, "Nenhum resultado" if (search.value or "").strip() else "Sua biblioteca local está vazia", "Tente alterar a busca ou os filtros." if (search.value or "").strip() else "Adicione uma pasta com animes nas configurações para começar.", ft.FilledButton("Abrir configurações", icon=ft.Icons.SETTINGS, on_click=lambda _: on_open_settings()) if not (search.value or "").strip() else None)
                feedback.visible = True
            active_filters = sum(v not in {None, "", "Todos", "Mais recentes"} for v in (selected_state[0], selected_genre[0], selected_media_type[0], selected_tag[0], selected_season[0], selected_episode_type[0], selected_availability[0], selected_metadata[0], selected_artwork[0]))
            filter_summary.value = f"{active_filters} filtro(s) ativo(s)" if active_filters else "Filtros"
            status.visible = scan_active[0]
            page_loading[0] = False
            if is_active():
                performance.counter("home.page_updates.catalog")
                page.update()
                await restore_scroll_position()
                schedule_artwork_batch_prefetch()
            pending_refresh = refresh_after_load_pending[0]
            refresh_after_load_pending[0] = False
            if pending_refresh and is_active():
                schedule_refresh_from_catalog()
            if fresh_items:
                async def run_hydration_batch():
                    started = time.perf_counter()
                    performance.event("HOME_HYDRATION_START", screen="home",
                                      metadata={"count": len(fresh_items), "generation": token})
                    await hydrate_metadata_and_artwork(list(fresh_items), token)
                    performance.event("HOME_HYDRATION_END",
                                      duration_ms=(time.perf_counter() - started) * 1000.0,
                                      screen="home",
                                      metadata={"count": len(fresh_items), "generation": token})
                schedule_background(run_hydration_batch)
            return True

        def schedule_background(coro_factory):
            try:
                loop = asyncio.get_running_loop()
            except RuntimeError:
                _start_view_task(coro_factory)
                return
            _start_view_task(coro_factory)

        async def load_next_page():
            try:
                await load_library_page(reset=False)
            finally:
                page_load_scheduled[0] = False

        def _scroll_event_name(event):
            value = getattr(event, "event_type", None)
            name = getattr(value, "name", None)
            if name:
                return str(name).upper()
            raw = str(value or "")
            return raw.rsplit(".", 1)[-1].upper()

        async def _trigger_pull_refresh():
            if not is_active():
                pull_refresh_active[0] = False
                _set_pull_indicator(False)
                return
            if refresh_state[0] == "REFRESHING":
                performance.counter("home.pull_refresh.rejected")
                pull_refresh_active[0] = False
                _set_pull_indicator(False)
                return
            await handle_manual_refresh(None, source="pull")

        def _scroll_direction_name(event):
            value = getattr(event, "direction", None)
            name = getattr(value, "name", None)
            if name:
                return str(name).upper()
            raw = str(value or "")
            return raw.rsplit(".", 1)[-1].upper()

        def on_home_scroll(event):
            try:
                view_state["scroll_position"] = float(event.pixels)
                remaining = float(event.max_scroll_extent - event.pixels)
                pixels = float(event.pixels)
                min_extent = float(event.min_scroll_extent)
                extent_before = float(event.extent_before)
                event_type = _scroll_event_name(event)
                direction = _scroll_direction_name(event)
            except (TypeError, ValueError, AttributeError):
                return
            if not is_active():
                return

            at_top = extent_before <= 1.0 and pixels <= min_extent + 1.0

            if event_type == "USER":
                if direction == "IDLE":
                    was_pull_gesture = pull_gesture_active[0]
                    should_refresh = _pull_refresh_should_trigger(
                        gesture_active=pull_gesture_active[0],
                        at_top=pull_gesture_at_top[0],
                        overscroll=pull_overscroll[0],
                        threshold=pull_threshold,
                        page_loading=page_loading[0],
                        refresh_state=refresh_state[0],
                    )
                    pull_gesture_active[0] = False
                    pull_gesture_at_top[0] = False
                    overscroll_ready = pull_overscroll[0] >= pull_threshold
                    pull_overscroll[0] = 0.0
                    _set_pull_indicator(False)
                    if should_refresh:
                        performance.counter("home.pull_refresh.armed")
                        _start_view_task(_trigger_pull_refresh)
                    elif overscroll_ready:
                        performance.counter("home.pull_refresh.cancelled_below_threshold")
                    elif was_pull_gesture:
                        performance.counter("home.pull_refresh.cancelled")
                        logger.info(
                            "PULL_REFRESH_CANCELLED reason=below_threshold_or_invalid_state",
                        )
                else:
                    was_gesture_active = pull_gesture_active[0]
                    pull_gesture_active[0] = True
                    pull_gesture_at_top[0] = (
                        at_top
                        and not page_loading[0]
                        and refresh_state[0] != "REFRESHING"
                    )
                    if not was_gesture_active:
                        logger.info(
                            "PULL_GESTURE_START at_top=%s blocked=%s",
                            pull_gesture_at_top[0],
                            not pull_gesture_at_top[0],
                        )
                    if pull_gesture_at_top[0]:
                        pull_overscroll[0] = 0.0
                        _set_pull_indicator(True, "Puxe para atualizar")
                    else:
                        pull_overscroll[0] = 0.0
                        _set_pull_indicator(False)
            elif event_type == "OVERSCROLL":
                overscroll_raw = getattr(event, "overscroll", None)
                try:
                    overscroll = float(overscroll_raw) if overscroll_raw is not None else 0.0
                except (TypeError, ValueError):
                    overscroll = 0.0
                if not at_top:
                    pull_gesture_at_top[0] = False
                    pull_overscroll[0] = 0.0
                    _set_pull_indicator(False)
                elif pull_gesture_active[0] and pull_gesture_at_top[0] and overscroll < 0.0:
                    was_ready = pull_overscroll[0] >= pull_threshold
                    pull_overscroll[0] += -overscroll
                    performance.gauge("home.pull_refresh.overscroll", pull_overscroll[0])
                    logger.info(
                        "PULL_OVERSCROLL amount=%.1f accumulated=%.1f",
                        -overscroll,
                        pull_overscroll[0],
                    )
                    is_ready = pull_overscroll[0] >= pull_threshold
                    if was_ready != is_ready:
                        if is_ready:
                            logger.info(
                                "PULL_THRESHOLD_REACHED threshold=%.1f accumulated=%.1f",
                                pull_threshold,
                                pull_overscroll[0],
                            )
                        _set_pull_indicator(
                            True,
                            "Solte para atualizar" if is_ready else "Puxe para atualizar",
                        )
            elif event_type == "UPDATE":
                if not at_top:
                    pull_gesture_at_top[0] = False
                    pull_overscroll[0] = 0.0
                    _set_pull_indicator(False)
            else:
                # Unknown event names are diagnostic-only and must not start
                # another refresh path.
                pull_gesture_at_top[0] = pull_gesture_at_top[0] and at_top

            if remaining < 800 and has_more[0] and not page_loading[0] and not page_load_scheduled[0]:
                page_load_scheduled[0] = True
                schedule_background(load_next_page)
        def card(anime):
            available_count = int(anime.get("available_count") or 0)
            completed = int(anime.get("watched_count") or 0)
            current = anime.get("current_episode") or {}
            current_state = consumption_state(current) if current else None
            progress = ratio(current)
            cover = (anime.get("meta") or {}).get("cover_cache")
            content_count = int(anime.get("content_count") or 0)
            missing_count = int(anime.get("missing_count") or 0)
            subtitle = "Filme" if anime.get("media_kind") == "movie" else (
                count_label(available_count, "episódio") if missing_count == 0 else f"{available_count}/{content_count} disponíveis"
            )
            status_value = "Concluído" if available_count > 0 and missing_count == 0 and completed == available_count else (f"{completed} concluídos" if completed else subtitle)
            indicators = []
            if anime.get("favorite"):
                indicators.append(ft.Container(ft.Icon(ft.Icons.STAR, color=theme.favorite, size=15), top=5, right=5, bgcolor=theme.overlay, border_radius=12, padding=3))
            if current_state and current_state.value in {"completed", "watched"}:
                indicators.append(ft.Container(ft.Icon(ft.Icons.CHECK, color=theme.text_on_overlay, size=14), bottom=5, right=5, bgcolor=theme.success + "CC", border_radius=12, padding=3))
            if anime.get("is_pinned"):
                indicators.append(ft.Container(
                    ft.Icon(ft.Icons.PUSH_PIN, color=ACCENT, size=14),
                    top=5, left=5, bgcolor=theme.overlay, border_radius=12, padding=3,
                ))

            def on_card_tap(item=anime):
                try:
                    current_screen = page.views[-1].route if page.views else "/"
                except Exception:
                    current_screen = "/"
                logger.info(
                    "HOME_CARD_TAP animeId=%s renderGeneration=%s cardGeneration=%s "
                    "pageLoading=%s hydrationActive=%s currentScreen=%s",
                    item.get("id"),
                    render_generation[0],
                    render_generation[0],
                    page_loading[0],
                    bool(artwork_tasks),
                    current_screen,
                )
                tap_started = time.perf_counter()
                try:
                    on_select_anime(item)
                except Exception:
                    logger.exception(
                        "HOME_CARD_TAP failed animeId=%s renderGeneration=%s",
                        item.get("id"),
                        render_generation[0],
                    )
                finally:
                    logger.info(
                        "HOME_CARD_TAP_END animeId=%s duration_ms=%s",
                        item.get("id"),
                        int((time.perf_counter() - tap_started) * 1000),
                    )

            return ft.OutlinedButton(
                width=card_width,
                height=card_height + 48,
                on_click=lambda _: on_card_tap(),
                style=focus_button_style(theme=theme, background=SURFACE),
                content=ft.Column([
                    ft.Stack([artwork_holder(anime, card_width, card_height, source=cover), *indicators]),
                    ft.Text(anime.get("main_title", "Anime local"), size=12, weight=ft.FontWeight.BOLD, color=TEXT, max_lines=2, overflow=ft.TextOverflow.ELLIPSIS),
                    ft.Text(status_value, size=10, color=TEXT_MUTED, max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                    ft.ProgressBar(value=progress, color=ACCENT, bgcolor=theme.surface_variant, height=3, visible=current_state is not None and current_state.value == "in_progress"),
                ], spacing=4),
            )

        async def open_continuation_details(item):
            anime = next(
                (candidate for candidate in catalog
                 if candidate.get("id") is not None and candidate.get("id") == item.get("anime_id")),
                None,
            )
            if anime is None and item.get("anime_id") is not None:
                try:
                    projected = await asyncio.to_thread(
                        library.catalog_by_ids, [int(item["anime_id"])]
                    )
                except Exception:
                    logger.exception(
                        "Home continuation details lookup failed",
                        extra={"screen": "home", "anime_id": item.get("anime_id")},
                    )
                    return
                anime = next(iter(projected or []), None)
            if anime is not None:
                on_select_anime(anime)
            else:
                logger.warning(
                    "Continuation details target unavailable",
                    extra={"screen": "home", "anime_id": item.get("anime_id")},
                )

        def request_continuation_details(item):
            async def task():
                await open_continuation_details(item)
            _start_view_task(task)

        def render_continue():
            nonlocal continue_signature
            visible_items = list(continuing)
            signature = tuple(_visible_item_signature(item, episode=True) for item in visible_items)
            if continue_signature[0] == signature:
                continuation_section.visible = bool(visible_items)
                return False
            continue_signature[0] = signature
            continue_row.controls.clear()
            continuation_section.visible = bool(visible_items)
            for item in visible_items:
                progress = ratio(item)
                label = "FILME" if item.get("episode_type") == "movie" else f"T{item.get('season', 1)} • E{item.get('number', '—')}"
                card_control = ft.Container(
                    width=258, bgcolor=SURFACE, border_radius=RADIUS, padding=9, ink=True,
                    on_click=lambda _, entry=item: play_continuation(entry),
                    content=ft.Row([
                        artwork_holder(
                            item,
                            56,
                            82,
                            entity="episode",
                            kind="episode_thumbnail",
                            source=item.get("episode_thumbnail"),
                        ),
                        ft.Column([
                            ft.Text(item.get("anime_title", "Anime local"), color=TEXT, size=12, weight=ft.FontWeight.BOLD, max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                            ft.Text(label, color=TEXT_MUTED, size=10),
                            ft.ProgressBar(value=progress, color=ACCENT, bgcolor=theme.surface_variant, height=3, visible=bool(item.get("duration"))),
                            ft.Row([
                                ft.TextButton("Continuar", icon=ft.Icons.PLAY_ARROW, on_click=lambda _, entry=item: play_continuation(entry)),
                                ft.TextButton(
                                    "Detalhes",
                                    icon=ft.Icons.INFO_OUTLINE,
                                    on_click=lambda _, entry=item: request_continuation_details(entry),
                                ),
                            ], spacing=0),
                        ], spacing=4, expand=True),
                    ], spacing=8),
                )
                continue_row.controls.append(card_control)

        def refresh_filter_options(options):
            tags = options.get("tags") or []
            seasons = options.get("seasons") or []
            episode_types = options.get("episode_types") or []
            genres = [str(value) for value in (options.get("genres") or []) if value]
            states = [str(value) for value in (options.get("states") or []) if value]
            if "Todos" not in states:
                states.insert(0, "Todos")
            state_filter.options = [ft.dropdown.Option(value, value) for value in states]
            if selected_state[0] not in states:
                selected_state[0] = "Todos"
            state_filter.value = selected_state[0]
            tag.options = [ft.dropdown.Option("Todos", "Todos"), ft.dropdown.Option("Sem etiqueta", "Sem etiqueta")] + [ft.dropdown.Option(v, v) for v in tags]
            season.options = [ft.dropdown.Option("Todos", "Todos")] + [ft.dropdown.Option(str(v), f"Temporada {v}") for v in seasons]
            episode_type.options = [ft.dropdown.Option("Todos", "Todos")] + [ft.dropdown.Option(v, v) for v in episode_types]
            genre_filter.options = [ft.dropdown.Option("Todos", "Todos")] + [ft.dropdown.Option(v, v) for v in genres]
            for control, value_box, fallback, values in (
                (tag, selected_tag, "Todos", {"Todos", "Sem etiqueta", *tags}),
                (season, selected_season, "Todos", {"Todos", *[str(v) for v in seasons]}),
                (episode_type, selected_episode_type, "Todos", {"Todos", *episode_types}),
                (genre_filter, selected_genre, "Todos", {"Todos", *genres}),
            ):
                if value_box[0] not in values:
                    value_box[0] = fallback
                control.value = value_box[0]
            state_filter.value = selected_state[0]
            media_type.value = selected_media_type[0]
            availability.value = selected_availability[0]
            metadata_filter.value = selected_metadata[0]
            artwork_filter.value = selected_artwork[0]

        async def apply_filters(_=None):
            selected_state[0] = state_filter.value or "Todos"
            selected_genre[0] = genre_filter.value or "Todos"
            selected_media_type[0] = media_type.value or "Todos"
            selected_tag[0] = tag.value or "Todos"
            selected_season[0] = season.value or "Todos"
            selected_episode_type[0] = episode_type.value or "Todos"
            selected_availability[0] = availability.value or "Todos"
            selected_metadata[0] = metadata_filter.value or "Todos"
            selected_artwork[0] = artwork_filter.value or "Todos"
            save_view_state()
            page.pop_dialog()
            await load_library_page(reset=True)

        async def clear_filters(_=None):
            selected_state[0] = "Todos"
            selected_genre[0] = "Todos"
            selected_sort[0] = "Mais recentes"
            selected_media_type[0] = "Todos"
            selected_tag[0] = "Todos"
            selected_season[0] = "Todos"
            selected_episode_type[0] = "Todos"
            selected_availability[0] = "Todos"
            selected_metadata[0] = "Todos"
            selected_artwork[0] = "Todos"
            state_filter.value = "Todos"
            genre_filter.value = "Todos"
            sort.value = "Mais recentes"
            media_type.value = "Todos"
            tag.value = "Todos"
            season.value = "Todos"
            episode_type.value = "Todos"
            availability.value = "Todos"
            metadata_filter.value = "Todos"
            artwork_filter.value = "Todos"
            search.value = ""
            search_generation[0] += 1
            save_view_state()
            page.pop_dialog()
            await load_library_page(reset=True)

        def current_gacha_filters():
            return {
                "query": search.value or "",
                "state": selected_state[0],
                "genre": selected_genre[0],
                "sort": selected_sort[0],
                "tag": selected_tag[0],
                "media_type": selected_media_type[0],
                "season": selected_season[0],
                "episode_type": selected_episode_type[0],
                "availability": selected_availability[0],
                "metadata": selected_metadata[0],
                "artwork": selected_artwork[0],
            }

        def open_gacha(_event=None):
            state = {"last_id": None, "episode": False, "result": None}
            dialog_width = min(520, max(280, float(page.width or 480) - 32))

            async def draw():
                try:
                    result = await asyncio.to_thread(
                        library.gacha_pick,
                        filters=current_gacha_filters(),
                        exclude_id=state["last_id"],
                        episode=state["episode"],
                    )
                except Exception:
                    logger.exception("Gacha calculation failed", extra={"screen": "home"})
                    result = None
                state["result"] = result
                if not result:
                    dialog.content = empty_state(ft.Icons.MOVIE_OUTLINED, "Acervo vazio", "Não há itens que correspondam aos filtros atuais.", theme=theme)
                    dialog.actions = [ft.TextButton("Fechar", on_click=lambda _: page.pop_dialog())]
                    page.update()
                    return
                anime = result["anime"]
                state["last_id"] = anime.get("id")
                cover = (anime.get("meta") or {}).get("cover_cache") or (anime.get("meta") or {}).get("cover_url")
                title = anime.get("main_title") or "Anime local"
                episode = result.get("episode")
                content = [
                    ft.Text("Resultado local", size=11, color=TEXT_MUTED),
                    media_artwork(cover, 210, width=140, icon_size=28, label="Sem capa", theme=theme),
                    ft.Text(title, size=15, weight=ft.FontWeight.BOLD, color=TEXT, text_align=ft.TextAlign.CENTER),
                ]
                if episode:
                    number = episode.get("number")
                    label = f"T{episode.get('season', '—')} E{number if number is not None else '—'}"
                    content.extend([
                        ft.Text(label, color=ACCENT, weight=ft.FontWeight.BOLD),
                        ft.Text(episode.get("episode_title") or episode.get("file_name") or "Episódio local", size=12, text_align=ft.TextAlign.CENTER),
                        ft.Text(f"{format_duration(episode.get('duration'))}", size=11, color=TEXT_MUTED),
                    ])
                elif state["episode"]:
                    content.append(ft.Text("Este item não possui episódios regulares disponíveis para sorteio.", size=11, color=TEXT_MUTED, text_align=ft.TextAlign.CENTER))
                else:
                    content.append(ft.Text("Sorteio não altera consumo, progresso ou histórico.", size=11, color=TEXT_MUTED, text_align=ft.TextAlign.CENTER))
                dialog.content = ft.Column(content, tight=True, horizontal_alignment=ft.CrossAxisAlignment.CENTER, spacing=8, width=dialog_width)
                dialog.actions = [
                    ft.TextButton("Sortear episódio", on_click=lambda _: (state.__setitem__("episode", True), _start_view_task(draw))),
                    ft.TextButton("Sortear novamente", on_click=lambda _: _start_view_task(draw)),
                    ft.FilledButton("Abrir", on_click=lambda _: open_gacha_result()),
                ]
                page.update()

            def open_gacha_result():
                result = state.get("result") or {}
                anime = result.get("anime")
                if not anime:
                    return
                page.pop_dialog()
                on_select_anime(anime)

            dialog = ft.AlertDialog(
                modal=True,
                title=ft.Text("🎲 Gacha do Acervo"),
                content=ft.Row([ft.ProgressRing(), ft.Text("Sorteando do acervo local…")], tight=True),
                actions=[ft.TextButton("Cancelar", on_click=lambda _: page.pop_dialog())],
            )
            page.show_dialog(dialog)
            _start_view_task(draw)

        def open_timeline(_event=None):
            dialog = ft.AlertDialog(
                modal=True,
                title=ft.Text("📅 Linha do Tempo"),
                content=ft.Row([ft.ProgressRing(), ft.Text("Lendo o metadata local…")], tight=True),
                actions=[ft.TextButton("Fechar", on_click=lambda _: page.pop_dialog())],
            )
            page.show_dialog(dialog)

            async def load_timeline():
                try:
                    groups = await asyncio.to_thread(library.library_timeline)
                except Exception:
                    logger.exception("Timeline calculation failed", extra={"screen": "home"})
                    groups = []
                controls = []
                if not groups:
                    controls.append(ft.Text("A biblioteca ainda não possui itens para a timeline.", color=TEXT_MUTED))
                for group in groups:
                    controls.append(ft.Text(str(group["label"]), size=15, weight=ft.FontWeight.BOLD, color=ACCENT))
                    for item in group["items"]:
                        item_id = item.get("id")
                        def make_open(anime_id):
                            async def open_item():
                                try:
                                    projected = await asyncio.to_thread(library.catalog_by_ids, [int(anime_id)])
                                except Exception:
                                    logger.exception("Timeline details lookup failed", extra={"anime_id": anime_id})
                                    return
                                anime = next(iter(projected or []), None)
                                if anime:
                                    page.pop_dialog()
                                    on_select_anime(anime)
                            return open_item
                        controls.append(ft.ListTile(
                            leading=ft.Icon(ft.Icons.EVENT_OUTLINED, color=TEXT_MUTED),
                            title=ft.Text(item.get("title") or "Anime local", color=TEXT),
                            subtitle=ft.Text(str(item.get("season") or ""), color=TEXT_MUTED) if item.get("season") else None,
                            trailing=ft.Icon(ft.Icons.CHEVRON_RIGHT, color=TEXT_MUTED),
                            on_click=lambda _, callback=make_open(item_id): _start_view_task(callback),
                        ))
                dialog.content = ft.ListView(controls=controls, spacing=4, width=min(560, max(280, float(page.width or 480) - 32)), height=min(520, max(180, len(controls) * 52)))
                page.update()
            _start_view_task(load_timeline)

        def open_duration_anomalies(_event=None):
            dialog = ft.AlertDialog(
                modal=True,
                title=ft.Text("⚠ Durações incomuns"),
                content=ft.Row([ft.ProgressRing(), ft.Text("Comparando durações locais…")], tight=True),
                actions=[ft.TextButton("Fechar", on_click=lambda _: page.pop_dialog())],
            )
            page.show_dialog(dialog)

            async def load_anomalies():
                try:
                    report = await asyncio.to_thread(library.duration_anomaly_report)
                except Exception:
                    logger.exception("Duration anomaly calculation failed", extra={"screen": "home"})
                    report = {"items": [], "anomaly_count": 0, "series_with_anomalies": 0, "series_with_insufficient_data": 0}
                controls = [ft.Text(
                    f"{report['series_with_anomalies']} série(s) com duração incomum • {report['series_with_insufficient_data']} com dados insuficientes",
                    size=11, color=TEXT_MUTED,
                )]
                unusual = [item for item in report.get("items", []) if item.get("status") == "unusual"]
                if not unusual:
                    controls.append(ft.Text("Nenhuma duração incomum foi identificada com os dados locais atuais.", color=TEXT_MUTED))
                for series in unusual:
                    controls.append(ft.Text(series["anime_title"], size=14, weight=ft.FontWeight.BOLD, color=TEXT))
                    for anomaly in series["anomalies"]:
                        label = anomaly.get("episode_title") or anomaly.get("file_name") or "Episódio local"
                        controls.append(ft.Text(
                            f"• {label} — {format_duration(anomaly['duration_seconds'])} • {anomaly['label']}",
                            size=11, color=ACCENT,
                        ))
                dialog.content = ft.ListView(controls=controls, spacing=4, width=min(560, max(280, float(page.width or 480) - 32)), height=min(520, max(180, len(controls) * 34)))
                page.update()
            _start_view_task(load_anomalies)
        def open_filters(_=None):
            _start_view_task(load_filter_options)
            page_width = float(page.width or 470)
            dialog_width = min(470.0, max(280.0, page_width - 32.0))
            field_width = min(220.0, max(128.0, (dialog_width - 20.0) / 2.0))
            for field in (
                state_filter,
                genre_filter,
                media_type,
                tag,
                season,
                episode_type,
                availability,
                metadata_filter,
                artwork_filter,
            ):
                field.width = field_width
            dialog = ft.AlertDialog(
                modal=True, title=ft.Text("Filtros da biblioteca"),
                content=ft.Column([
                    ft.Row([state_filter, genre_filter], wrap=True),
                    ft.Row([media_type, tag], wrap=True),
                    ft.Row([season, episode_type], wrap=True),
                    ft.Row([availability, metadata_filter], wrap=True),
                    artwork_filter,
                ], tight=True, width=dialog_width),
                actions=[
                    ft.TextButton("Limpar", icon=ft.Icons.CLEAR_ALL, on_click=lambda _: _start_view_task(clear_filters)),
                    ft.TextButton("Cancelar", on_click=lambda _: page.pop_dialog()),
                    ft.FilledButton("Aplicar", on_click=apply_filters),
                ],
                actions_alignment=ft.MainAxisAlignment.END,
            )
            page.show_dialog(dialog)
            page.update()

        async def toggle_search(_):
            search_visible[0] = not search_visible[0]
            search.visible = search_visible[0]
            if not search_visible[0]:
                search.value = ""
            save_view_state()
            await load_library_page(reset=True)

        async def on_search(event):
            save_view_state()
            search_generation[0] += 1
            token = search_generation[0]
            await asyncio.sleep(0.18)
            if token != search_generation[0]:
                return
            await load_library_page(reset=True)

        async def on_sort(event):
            selected_sort[0] = event.control.value or "Mais recentes"
            save_view_state()
            await load_library_page(reset=True)

        async def hydrate_metadata_and_artwork(items, token):
            if not items or token != render_generation[0]:
                return
            hydration_started = time.perf_counter()
            logger.info(
                "HOME_HYDRATION_START count=%s generation=%s active_artwork_tasks=%s",
                len(items), token, len(artwork_tasks),
            )
            try:
                results = await asyncio.to_thread(library.hydrate_catalog_metadata, items)
                if token != render_generation[0]:
                    return
                by_id = {int(item.get('id')): item for item in items if item.get('id') is not None}
                updated = 0
                for result in results or []:
                    item_id = result.get('id')
                    target = by_id.get(int(item_id)) if item_id is not None else None
                    metadata = result.get('metadata') or {}
                    if target is None or not metadata:
                        continue
                    target['meta'] = dict(metadata)
                    if metadata.get('cover_cache'):
                        entity = 'movie' if target.get('media_kind') == 'movie' else 'anime'
                        _queue_artwork_resolution(entity, item_id, 'poster', target)
                        updated += 1
                if updated:
                    logger.info('HOME_ARTWORK_BATCH_QUEUED count=%s', updated)
                    schedule_artwork_batch_prefetch()
            except Exception:
                logger.exception('Home metadata/artwork hydration failed', extra={'screen':'home','requestId':'-','library_items':len(items)})
            finally:
                logger.info(
                    "HOME_HYDRATION_END duration_ms=%s generation=%s active_artwork_tasks=%s",
                    int((time.perf_counter() - hydration_started) * 1000),
                    token,
                    len(artwork_tasks),
                )

        async def refresh_home_sections(token):
            sections_started = time.perf_counter()
            performance.event("HOME_INITIAL_LOAD_START", screen="home",
                              metadata={"generation": token})
            try:
                try:
                    continue_limit = int(settings.get("library.continue_watching_limit"))
                except (TypeError, ValueError):
                    continue_limit = 10
                loaded = await asyncio.to_thread(
                    library.media_center_home,
                    limit=12,
                    continue_limit=continue_limit,
                )
            except Exception:
                logger.exception("Home secondary sections load failed", extra={"screen":"home"})
                return
            finally:
                performance.event("HOME_INITIAL_LOAD_END",
                                  duration_ms=(time.perf_counter() - sections_started) * 1000.0,
                                  screen="home",
                                  metadata={"generation": token})
                logger.info(
                    "HOME_MEDIA_CENTER_HOME duration_ms=%s",
                    int((time.perf_counter() - sections_started) * 1000),
                )
            if token != render_generation[0] or token != home_sections_generation[0]:
                return
            home_data.clear()
            home_data.update(loaded or {})
            continuing.clear()
            if settings.get("library.continue_watching"):
                limit = settings.get("library.continue_watching_limit")
                continuing.extend(home_data.get("continue_watching", [])[:limit])
            changed = render_continue()
            for title, key in (
                ("FAVORITOS", "favorites"),
                ("PINADOS", "pinned"),
                ("FILMES", "movies"),
            ):
                try:
                    changed = render_section(
                        title,
                        key,
                        home_data.get(key),
                        action=on_select_anime,
                        episode=False,
                    ) or changed
                except Exception:
                    logger.exception("Home section render failed", extra={"screen":"home","section":key})
            if changed and is_active():
                performance.counter("home.page_updates.sections")
                page.update()
                schedule_artwork_batch_prefetch()

        async def load_filter_options():
            if filter_options_loaded[0]:
                return
            try:
                loaded_options = await asyncio.to_thread(library.search_options)
            except Exception:
                logger.exception("Home filter options load failed", extra={"screen":"home"})
                return
            if filter_options_loaded[0] or not is_active():
                return
            refresh_filter_options(loaded_options or {})
            filter_options_loaded[0] = True
            page.update()

        def update_thumbnail_in_place(uri, thumbnail_path, media_identity=None):
            """Apply a completed episode thumbnail to mounted episode artwork only."""
            uri = str(uri or "").strip()
            thumbnail_path = str(thumbnail_path or "").strip()
            media_identity = str(media_identity or "").strip()
            if not show_thumbnails or not uri or not thumbnail_path:
                return False

            affected_episode_ids: set[int] = set()

            def collect_episode_ids(value):
                if isinstance(value, dict):
                    item_uri = str(value.get("path") or "").strip()
                    item_identity = str(value.get("media_identity") or "").strip()
                    episode_id = value.get("episode_id")
                    if episode_id is None and value.get("anime_id") is not None and value.get("id") is not None:
                        episode_id = value.get("id")
                    if (
                        item_uri == uri
                        or (media_identity and item_identity == media_identity)
                    ) and episode_id is not None:
                        try:
                            affected_episode_ids.add(int(episode_id))
                        except (TypeError, ValueError):
                            pass
                    for child in value.values():
                        collect_episode_ids(child)
                elif isinstance(value, (list, tuple)):
                    for child in value:
                        collect_episode_ids(child)

            collect_episode_ids(catalog)
            collect_episode_ids(home_data)
            if not affected_episode_ids:
                return False

            updated = 0
            for episode_id in affected_episode_ids:
                key = ("episode", episode_id, "episode_thumbnail")
                for holder, width, height in artwork_bindings.get(key, ()):
                    holder.content = ft.Image(
                        src=thumbnail_path,
                        width=width,
                        height=height,
                        fit=ft.BoxFit.COVER,
                        border_radius=RADIUS,
                    )
                    updated += 1
                def update_item(node):
                    if isinstance(node, dict):
                        node_uri = str(node.get("path") or "").strip()
                        node_id = node.get("id")
                        node_episode_id = node.get("episode_id")
                        if node_episode_id is None and node.get("anime_id") is not None:
                            node_episode_id = node_id
                        try:
                            matches_id = int(node_episode_id) == episode_id
                        except (TypeError, ValueError):
                            matches_id = False
                        if matches_id and (node_uri == uri or not node_uri):
                            node["episode_thumbnail"] = thumbnail_path
                        for child in node.values():
                            update_item(child)
                    elif isinstance(node, (list, tuple)):
                        for child in node:
                            update_item(child)
                update_item(catalog)
                update_item(home_data)
            if updated:
                logger.info(
                    "THUMBNAIL_UI_UPDATE affected_episodes=%s controls=%s",
                    len(affected_episode_ids),
                    updated,
                )
                schedule_artwork_ui_update()
            return bool(updated)

        async def refresh_from_catalog():
            if not is_active():
                return
            if page_loading[0]:
                # The active catalog load owns the refresh of this control tree.
                # Mark it pending and let load_library_page() schedule a single
                # follow-up through the existing coalescing hook.
                refresh_after_load_pending[0] = True
                return
            save_view_state()
            filter_options_loaded[0] = False
            loaded = await load_library_page(reset=True)
            if not loaded:
                if view_state.get("_manual_refresh_pending"):
                    view_state["_manual_refresh_pending"] = False
                    if callable(on_refresh_ui_failed):
                        on_refresh_ui_failed()
                return
            home_sections_generation[0] = render_generation[0]
            _start_view_task(refresh_home_sections, render_generation[0])
            if view_state.get("_manual_refresh_pending"):
                view_state["_manual_refresh_pending"] = False
                if callable(on_refresh_ui_updated):
                    on_refresh_ui_updated()

        async def retry_load_catalog(_event=None):
            await load_catalog()

        async def load_catalog():
            status.visible = True
            status.controls = [
                ft.ProgressRing(width=16, height=16, stroke_width=2, color=ACCENT),
                ft.Text("Carregando biblioteca local…", color=TEXT_MUTED, size=12),
            ]
            try:
                last_scan = await asyncio.to_thread(library.last_scan)
            except Exception:
                logger.exception("Home local projections load failed", extra={"screen":"home","requestId":"-"})
                status.controls = [
                    ft.Icon(ft.Icons.ERROR_OUTLINE, color=theme.error, size=18),
                    ft.Text("Não foi possível ler a biblioteca local agora.", color=theme.error, size=12),
                    ft.TextButton("Tentar novamente", on_click=retry_load_catalog),
                ]
                status.visible = True
                if is_active():
                    page.update()
                return
            scan_active[0] = bool(
                last_scan and str(last_scan.get("status") or "").casefold() in {"running", "started"}
            )
            filter_options_loaded[0] = False
            await load_library_page(reset=True)
            home_sections_generation[0] = render_generation[0]
            status.visible = scan_active[0]
            _start_view_task(refresh_home_sections, render_generation[0])
            if view_state.get("_manual_refresh_pending") and is_active():
                # Returning to Home after a scan completed elsewhere already
                # loaded the durable catalog above. Consume that pending refresh
                # without launching a second catalog query or scan.
                view_state["_manual_refresh_pending"] = False
                if callable(on_refresh_ui_updated):
                    on_refresh_ui_updated()

        search.on_change = on_search
        search.on_submit = on_search
        sort.on_select = on_sort
        refresh_button[0] = ft.IconButton(
            icon=ft.Icons.REFRESH,
            icon_color=TEXT,
            tooltip="Atualizar biblioteca",
            on_click=handle_manual_refresh,
        )
        pull_refresh_indicator[0] = ft.Row(
            [
                ft.ProgressRing(width=16, height=16, stroke_width=2, color=ACCENT),
                ft.Text("Puxe para atualizar", size=11, color=TEXT_MUTED),
            ],
            spacing=8,
            alignment=ft.MainAxisAlignment.CENTER,
            visible=False,
        )
        pull_refresh_label[0] = pull_refresh_indicator[0].controls[1]
        set_refresh_state(refresh_state[0], update=False)
        header = ft.Row([
            ft.Row([
                ft.Container(content=ft.Icon(ft.Icons.PLAY_CIRCLE_FILLED, color=ACCENT, size=29), bgcolor=SURFACE, border_radius=12, padding=5),
                ft.Text("ReiAnix", size=22, weight=ft.FontWeight.BOLD, color=TEXT),
            ], spacing=8),
            ft.Row([
                refresh_button[0],
                ft.IconButton(icon=ft.Icons.SEARCH, icon_color=TEXT, tooltip="Pesquisar", on_click=toggle_search),
                ft.IconButton(icon=ft.Icons.GRID_VIEW, icon_color=TEXT, tooltip="Biblioteca", visible=on_open_library is not None, on_click=lambda _: on_open_library() if on_open_library else None),
                ft.IconButton(icon=ft.Icons.DASHBOARD_OUTLINED, icon_color=TEXT, tooltip="Organizar", visible=on_open_organize is not None, on_click=lambda _: on_open_organize() if on_open_organize else None),
                ft.IconButton(icon=ft.Icons.SETTINGS_OUTLINED, icon_color=TEXT, tooltip="Configurações", on_click=lambda _: on_open_settings()),
            ], spacing=0),
        ], alignment=ft.MainAxisAlignment.SPACE_BETWEEN, wrap=True, run_spacing=8)
        filter_button = ft.OutlinedButton("Filtros", icon=ft.Icons.TUNE, on_click=open_filters)
        smart_tool_controls = [
            ft.OutlinedButton("🎲 Gacha", on_click=open_gacha),
            ft.OutlinedButton("📅 Timeline", on_click=open_timeline),
            ft.OutlinedButton("⚠ Durações", on_click=open_duration_anomalies),
        ]
        if on_open_collector is not None:
            smart_tool_controls.append(ft.OutlinedButton("🏆 Collector", on_click=lambda _: on_open_collector()))
        smart_tools_row = ft.Row(smart_tool_controls, wrap=True, spacing=8, run_spacing=8)
        main_library_bar = ft.Row(
            [ft.Row([library_label, filter_summary], spacing=10, wrap=True), sort, filter_button],
            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
            wrap=True,
            spacing=8,
            run_spacing=8,
        )
        sections_column = ft.Column(
            [section_cards.setdefault(key, ft.Container(content=ft.Column([
                ft.Text(title, size=15, weight=ft.FontWeight.BOLD, color=TEXT),
                section_rows.setdefault(key, ft.Row(scroll=ft.ScrollMode.AUTO, spacing=10)),
            ], spacing=9), visible=False))
             for title, key in (
                ("FAVORITOS", "favorites"),
                ("PINADOS", "pinned"),
                ("FILMES", "movies"),
             )],
            spacing=14,
        )
        # Deliberately keep the existing single outer scroll container: filters,
        # pagination, saved scroll offset and touch navigation all depend on it.
        # A nested GridView would introduce a second viewport here. We therefore
        # use the stable Row+pagination architecture, with a bounded page size,
        # instead of a blind GridView migration.
        layout = ft.Column([
            header, pull_refresh_indicator[0], search, ft.Text("Sua biblioteca local, conteúdo primeiro.", size=12, color=TEXT_MUTED),
            status, hydration_status, continuation_section, main_library_bar, feedback, grid, sections_column, ft.Container(height=24),
        ], scroll=ft.ScrollMode.AUTO, expand=True, spacing=12, scroll_interval=60, on_scroll=on_home_scroll)
        async def restore_scroll_position():
            stored = view_state.get("scroll_position")
            if stored is None:
                return
            try:
                result = layout.scroll_to(offset=float(stored), duration=0)
                if inspect.isawaitable(result):
                    await result
            except Exception:
                logger.debug("Home scroll restoration unavailable", exc_info=True)

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
            # Cached Home controls can be discarded by settings/details changes.
            # Existing async work cannot safely mutate that retired control tree.
            render_generation[0] += 1
            home_sections_generation[0] = render_generation[0]
            catalog_refresh_dirty[0] = False
            artwork_request_tokens.clear()
            cancel_view_tasks()

        view_state['_refresh_from_catalog'] = schedule_refresh_from_catalog
        view_state['_update_thumbnail'] = update_thumbnail_in_place
        view_state['_invalidate_view_tasks'] = invalidate_view_tasks
        view_state['_set_refresh_state'] = set_refresh_state
        view_state['_reset_refresh_state'] = _schedule_refresh_reset
        view_state['_manual_refresh_pending'] = bool(view_state.get('_manual_refresh_pending', False))
        status.visible = True
        _start_view_task(load_catalog)
        result = ft.Container(
            content=layout, padding=ft.Padding(left=PAGE_PADDING, right=PAGE_PADDING, top=16, bottom=8),
            bgcolor=BACKGROUND, expand=True,
        )
        performance.record_ui_build("home", (performance.now()-build_started)*1000.0,
                                    controls=performance.control_count(result), cached=False)
        return result