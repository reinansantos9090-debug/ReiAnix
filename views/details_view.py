"""Details presentation for one locally indexed anime.

This module deliberately receives an already loaded local catalog entry.  It
never contacts AniList and delegates playback selection to LibraryService.
"""
from __future__ import annotations

import asyncio
import html
import inspect
import logging
import math
import re

import flet as ft
from core.consumption import consumption_state, is_completed, is_in_progress, playback_action, progress_ratio
from core.library_discovery import duration_anomalies
from core.dialogs import dismiss_dialog
from core.performance import get_performance_monitor
from core.ui import ACCENT, BACKGROUND, PAGE_PADDING, RADIUS, SUCCESS, SURFACE, TEXT, TEXT_MUTED, WARNING, activate_theme_for_page, media_artwork, spoiler_artwork, section_title, focus_button_style


class DetailView:
    _logger = logging.getLogger("reiflix.details")

    @staticmethod
    def build(page: ft.Page, anime_group: dict, on_play_episode, on_back,
              on_toggle_favorite, get_playback_target=None, on_set_user_tags=None,
              on_toggle_pinned=None, on_set_personal_note=None, on_set_episode_identification=None,
               on_identification_saved=None, on_refresh_metadata=None, resolve_artwork=None, resolve_artwork_batch=None,
               on_open_marathon=None, resolve_artwork_palette=None, is_active=None, view_state=None,
               on_request_thumbnail=None):
        performance = get_performance_monitor()
        build_started = performance.now()
        performance.counter("ui.builds_requested.details")
        theme = activate_theme_for_page(page)
        BACKGROUND = theme.background
        SURFACE = theme.surface
        TEXT = theme.text
        TEXT_MUTED = theme.text_muted
        ACCENT = theme.primary
        SUCCESS = theme.success
        WARNING = theme.warning
        metadata = anime_group.get("meta") or {}
        title = metadata.get("title_official") or anime_group.get("main_title") or "Anime local"
        alternate_titles = [metadata.get(key) for key in ("english", "romaji", "native")]
        alternate_title = next((value for value in alternate_titles if value and value != title), None)
        title_variants = []
        for label, key in (("Inglês", "english"), ("Romaji", "romaji"), ("Nativo", "native")):
            value = str(metadata.get(key) or "").strip()
            if value and value != title and (label, value) not in title_variants:
                title_variants.append((label, value))
        seasons = anime_group.get("seasons") or []
        regular_episodes = [episode for season in seasons for episode in season.get("episodes", [])]
        special_episodes = [episode for group in (anime_group.get("specials") or []) for episode in group.get("episodes", [])]
        movie_episodes = list(anime_group.get("media_files") or [])
        episodes = [*regular_episodes, *special_episodes, *movie_episodes]
        available = [episode for episode in episodes if not episode.get("missing")]
        missing_count = len(episodes) - len(available)
        media_kind = str(anime_group.get("media_kind") or metadata.get("media_kind") or "series").casefold()
        is_movie = media_kind == "movie" or bool(movie_episodes and not regular_episodes and not special_episodes)
        favorite = [bool(anime_group.get("favorite"))]
        pinned = [bool(anime_group.get("is_pinned"))]
        selected_season = [0]
        visible_episode_count = [48]
        visible_special_count = [48]
        episode_artwork = {}
        episode_thumbnail_bindings = {}
        # Every episode card registers its stable progress bar here.
        progress_bars = []
        expanded_description = [False]
        current = anime_group.get("current_episode") or {}
        duration_warning = None
        try:
            duration_report = duration_anomalies([
                {
                    **episode,
                    "anime_id": anime_group.get("id"),
                    "anime_title": title,
                }
                for episode in regular_episodes
            ])
            if duration_report.get("anomaly_count"):
                duration_warning = f"{duration_report['anomaly_count']} episódio(s) com duração incomum"
        except Exception:
            DetailView._logger.exception("Duration anomaly projection failed", extra={"screen":"details"})
        primary_target = get_playback_target(anime_group["id"]) if get_playback_target else (current or next((item for item in available), None))
        last_completed = max(
            (item for item in regular_episodes if is_completed(item) and not item.get("missing")),
            key=lambda item: item.get("last_played_at") or 0,
            default=None,
        )
        is_next_after_completion = bool(
            primary_target and last_completed
            and primary_target.get("path") != last_completed.get("path")
            and not primary_target.get("watched")
        )

        def ratio(episode):
            if not episode:
                return None
            try:
                duration = float(episode.get("duration") or 0)
            except (TypeError, ValueError):
                return None
            return progress_ratio(episode) if math.isfinite(duration) and duration > 0 else None

        def duration_label(episode):
            try:
                seconds = float(episode.get("duration") or 0)
            except (TypeError, ValueError):
                return None
            if not math.isfinite(seconds) or seconds <= 0:
                return None
            total = int(seconds)
            if total >= 3600:
                return f"{total // 3600}h {(total % 3600) // 60:02d}min"
            return f"{total // 60}min"

        def placeholder(height=198):
            return media_artwork(None, height, width=132, icon_size=38)

        artwork_entity = "movie" if is_movie else "anime"
        cover = metadata.get("cover_cache") or None
        if resolve_artwork:
            resolved_poster = resolve_artwork(
                artwork_entity,
                anime_group["id"],
                "poster",
                allow_network=False,
            )
            if resolved_poster and resolved_poster.get("local_path"):
                cover = resolved_poster.get("local_path")

        poster = ft.Container(
            width=132,
            height=198,
            border_radius=RADIUS,
            content=media_artwork(cover, 198, width=132, icon_size=38),
        )

        backdrop_path = str(metadata.get("banner_url") or "").strip() or None
        if resolve_artwork:
            resolved_backdrop = resolve_artwork(
                artwork_entity,
                anime_group["id"],
                "backdrop",
                allow_network=False,
            )
            if resolved_backdrop and resolved_backdrop.get("local_path"):
                backdrop_path = resolved_backdrop.get("local_path")
        backdrop = (
            ft.Container(
                content=media_artwork(backdrop_path, 150, width=None, icon_size=30),
                height=150,
                border_radius=RADIUS,
            )
            if backdrop_path
            else None
        )

        def update_artwork_in_place(entity, entity_id, artwork_type, local_path):
            """Update only the active Details artwork slots; never rebuild episodes."""
            try:
                same_entity = str(entity or "").strip().lower() == artwork_entity
                same_id = int(entity_id) == int(anime_group.get("id") or 0)
            except (TypeError, ValueError):
                return False
            if not same_entity or not same_id or not isinstance(local_path, str) or not local_path.strip():
                return False
            local_path = local_path.strip()
            if artwork_type == "poster":
                poster.content = media_artwork(local_path, 198, width=132, icon_size=38)
                metadata["cover_cache"] = local_path
                anime_group.setdefault("meta", {})["cover_cache"] = local_path
                anime_group["cover"] = local_path
                return True
            if artwork_type == "backdrop" and backdrop is not None:
                backdrop.content = media_artwork(local_path, 150, width=None, icon_size=30)
                metadata["banner_url"] = metadata.get("banner_url") or local_path
                return True
            return False

        if isinstance(view_state, dict):
            view_state["_update_artwork"] = update_artwork_in_place

        contextual_accent = [theme.primary]
        contextual_on_accent = [theme.text_on_accent]
        contextual_soft = [theme.surface_variant]
        hero_accent_indicator = ft.Container(width=4, height=54, bgcolor=contextual_accent[0], border_radius=3)

        def meta_chip(label, icon=None):
            return ft.Container(
                content=ft.Row(([ft.Icon(icon, size=14, color=theme.secondary)] if icon else []) + [
                    ft.Text(str(label), size=11, color=theme.secondary)
                ], tight=True, spacing=4),
                padding=ft.Padding.symmetric(horizontal=9, vertical=5), bgcolor=theme.surface_raised, border_radius=RADIUS,
            )

        facts = []
        if metadata.get("year"):
            facts.append(meta_chip(metadata["year"], ft.Icons.CALENDAR_TODAY_OUTLINED))
        if metadata.get("status"):
            facts.append(meta_chip(metadata["status"], ft.Icons.INFO_OUTLINE))
        if available:
            local_label = "Biblioteca: 1 episódio local" if len(available) == 1 else f"Biblioteca: {len(available)} episódios locais"
            facts.append(meta_chip(local_label, ft.Icons.VIDEO_LIBRARY_OUTLINED))
        if metadata.get("episodes_count"):
            expected_label = f"AniList: {metadata['episodes_count']} episódios esperados"
            facts.append(meta_chip(expected_label, ft.Icons.FORMAT_LIST_NUMBERED))
        if metadata.get("format"):
            format_labels = {
                "TV": "TV", "TV_SHORT": "TV curta", "MOVIE": "Filme", "OVA": "OVA",
                "ONA": "ONA", "SPECIAL": "Special", "MUSIC": "Music",
            }
            facts.append(meta_chip(format_labels.get(str(metadata["format"]).upper(), str(metadata["format"])), ft.Icons.VIDEO_LIBRARY_OUTLINED))
        if metadata.get("duration"):
            duration_source = "AniList" if metadata.get("anilist_id") else "Metadata"
            facts.append(meta_chip(f"{duration_source}: {metadata['duration']} min/ep", ft.Icons.SCHEDULE_OUTLINED))
        if metadata.get("score") is not None:
            score_source = "Score AniList" if metadata.get("anilist_id") else "Score"
            facts.append(meta_chip(f"{score_source}: {float(metadata['score']) / 10:g}", ft.Icons.STAR_OUTLINED))
        if duration_warning:
            facts.append(meta_chip(duration_warning))

        genres = anime_group.get("genres") or []
        genre_controls = [
            ft.Container(ft.Text(genre, size=11, color=theme.text), bgcolor=theme.border, border_radius=14,
                         padding=ft.Padding.symmetric(horizontal=10, vertical=5))
            for genre in genres if genre
        ]

        description = html.unescape(re.sub(r"<[^>]+>", "", metadata.get("description") or "")).strip()
        description_text = ft.Text(description, size=13, color=theme.secondary, max_lines=5,
                                   overflow=ft.TextOverflow.ELLIPSIS, visible=bool(description))
        expand_button = ft.TextButton("Ler mais", visible=len(description) > 300)

        def toggle_description(_):
            expanded_description[0] = not expanded_description[0]
            description_text.max_lines = None if expanded_description[0] else 5
            description_text.overflow = None if expanded_description[0] else ft.TextOverflow.ELLIPSIS
            expand_button.content = "Mostrar menos" if expanded_description[0] else "Ler mais"
            page.update()

        expand_button.on_click = toggle_description
        favorite_button = ft.IconButton(
            icon=ft.Icons.STAR if favorite[0] else ft.Icons.STAR_BORDER,
            icon_color=theme.favorite if favorite[0] else theme.text,
            tooltip="Remover dos favoritos" if favorite[0] else "Adicionar aos favoritos",
        )

        def toggle_favorite(_):
            favorite[0] = bool(on_toggle_favorite(anime_group["id"]))
            anime_group["favorite"] = favorite[0]
            favorite_button.icon = ft.Icons.STAR if favorite[0] else ft.Icons.STAR_BORDER
            favorite_button.icon_color = theme.favorite if favorite[0] else theme.text
            favorite_button.tooltip = "Remover dos favoritos" if favorite[0] else "Adicionar aos favoritos"
            page.update()

        favorite_button.on_click = toggle_favorite
        pin_button = ft.IconButton(
            icon=ft.Icons.PUSH_PIN if pinned[0] else ft.Icons.PUSH_PIN_OUTLINED,
            icon_color=ACCENT if pinned[0] else theme.text,
            tooltip="Desafixar anime" if pinned[0] else "Fixar anime", visible=on_toggle_pinned is not None,
        )
        def toggle_pin(_):
            try:
                pinned[0] = bool(on_toggle_pinned(anime_group["id"]))
                anime_group["is_pinned"] = pinned[0]
                pin_button.icon = ft.Icons.PUSH_PIN if pinned[0] else ft.Icons.PUSH_PIN_OUTLINED
                pin_button.icon_color = ACCENT if pinned[0] else theme.text
                pin_button.tooltip = "Desafixar anime" if pinned[0] else "Fixar anime"
                page.update()
            except Exception:
                DetailView._logger.exception(
                    "Failed to toggle pin",
                    extra={"screen":"details","requestId":"-","library_items":1},
                )
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível alterar o pin.")); page.snack_bar.open = True; page.update()
        pin_button.on_click = toggle_pin

        note_text = [str(anime_group.get("personal_note") or "")]
        note_summary = ft.Text(size=12, color=theme.secondary, max_lines=3, overflow=ft.TextOverflow.ELLIPSIS)
        note_button = ft.OutlinedButton("Adicionar nota", icon=ft.Icons.NOTE_ADD_OUTLINED)
        def render_note():
            note_summary.value = note_text[0] or "Nenhuma nota pessoal."
            note_button.text = "Editar nota" if note_text[0] else "Adicionar nota"
            note_button.icon = ft.Icons.EDIT_NOTE if note_text[0] else ft.Icons.NOTE_ADD_OUTLINED
        def edit_note(_):
            dialog = None
            field = ft.TextField(
                label="Nota privada",
                value=note_text[0],
                multiline=True,
                min_lines=3,
                max_lines=8,
                max_length=2000,
                autofocus=True,
            )
            saving = [False]
            cancel_button = ft.TextButton("Cancelar", on_click=lambda _: dismiss_dialog(page, dialog))
            delete_button = ft.FilledButton("Apagar", visible=bool(note_text[0]))
            save_button = ft.FilledButton("Salvar")

            async def save(_event):
                if saving[0]:
                    return
                saving[0] = True
                save_button.disabled = True
                delete_button.disabled = True
                page.update()
                try:
                    value = (
                        await asyncio.to_thread(on_set_personal_note, anime_group["id"], field.value)
                        if on_set_personal_note
                        else field.value
                    )
                    if callable(is_active) and not is_active():
                        return
                    note_text[0] = value or ""
                    anime_group["personal_note"] = note_text[0]
                    render_note()
                    dismiss_dialog(page, dialog)
                    page.snack_bar = ft.SnackBar(ft.Text("Nota salva."))
                    page.snack_bar.open = True
                    safe_update()
                except ValueError as exc:
                    field.error_text = str(exc)
                    save_button.disabled = False
                    delete_button.disabled = False
                    saving[0] = False
                    page.update()
                except Exception:
                    DetailView._logger.exception("Failed to save personal note")
                    field.error_text = "Não foi possível salvar a nota."
                    save_button.disabled = False
                    delete_button.disabled = False
                    saving[0] = False
                    page.update()

            async def clear_and_save(_event):
                field.value = ""
                await save(_event)

            delete_button.on_click = clear_and_save
            save_button.on_click = save
            dialog = ft.AlertDialog(
                modal=True,
                title=ft.Text("Nota pessoal"),
                content=field,
                actions=[cancel_button, delete_button, save_button],
            )
            page.show_dialog(dialog)
            page.update()
        note_button.on_click = edit_note
        render_note()

        personal_tags = list(anime_group.get("user_tags") or [])
        tags_row = ft.Row(wrap=True, spacing=6, run_spacing=6)

        async def save_tags(tags):
            nonlocal personal_tags
            try:
                personal_tags = (
                    await asyncio.to_thread(on_set_user_tags, anime_group["id"], tags)
                    if on_set_user_tags
                    else tags
                )
                if callable(is_active) and not is_active():
                    return
                anime_group["user_tags"] = personal_tags
                render_tags()
                page.snack_bar = ft.SnackBar(ft.Text("Etiqueta salva."))
                page.snack_bar.open = True
                page.update()
            except ValueError as exc:
                page.snack_bar = ft.SnackBar(ft.Text(str(exc)))
                page.snack_bar.open = True
                page.update()
            except Exception:
                DetailView._logger.exception("Failed to save personal tags")
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível salvar suas etiquetas."))
                page.snack_bar.open = True
                page.update()

        def render_tags():
            tags_row.controls.clear()
            for tag in personal_tags:
                async def remove_tag(_, value=tag):
                    await save_tags([item for item in personal_tags if item != value])

                tags_row.controls.append(ft.OutlinedButton(
                    tag, icon=ft.Icons.CLOSE, tooltip=f"Remover etiqueta {tag}",
                    on_click=remove_tag,
                    style=ft.ButtonStyle(color=theme.secondary, side=ft.BorderSide(1, theme.border)),
                ))

        def add_tag(_):
            dialog = None
            field = ft.TextField(label="Etiqueta", hint_text="Ex.: Prioridade", autofocus=True, max_length=40)
            adding = [False]

            async def add_value(_event):
                if adding[0]:
                    return
                value = (field.value or "").strip()
                if not value:
                    field.error_text = "Digite uma etiqueta."
                    page.update()
                    return
                adding[0] = True
                try:
                    await save_tags([*personal_tags, value])
                    dismiss_dialog(page, dialog)
                finally:
                    adding[0] = False

            dialog = ft.AlertDialog(
                modal=True,
                title=ft.Text("Adicionar etiqueta pessoal"),
                content=field,
                actions=[
                    ft.TextButton("Cancelar", on_click=lambda _: dismiss_dialog(page, dialog)),
                    ft.FilledButton("Adicionar", on_click=add_value),
                ],
            )
            page.show_dialog(dialog)
            page.update()

        render_tags()

        def play(episode):
            if not episode or episode.get("missing") or not episode.get("path"):
                page.snack_bar = ft.SnackBar(ft.Text("Nenhum episódio local disponível para reprodução."))
                page.snack_bar.open = True
                page.update()
                return
            number = episode.get("number")
            episode_label = f"T{episode.get('season', '—')} E{number if number is not None else '—'}"
            player_title = f"{anime_group.get('main_title') or title} • {episode_label}"
            on_play_episode(
                episode["path"],
                player_title,
                progress_seconds=episode.get("progress") or 0,
                episode_id=episode.get("id"),
                anime_id=anime_group.get("id"),
            )

        primary_ratio = ratio(primary_target) if primary_target else None
        primary_type = str(primary_target.get("episode_type") or "regular").casefold() if primary_target else ""
        primary_action = playback_action(primary_target) if primary_target else "unavailable"
        if primary_action == "continue":
            primary_label = (
                "Continuar filme" if is_movie
                else "Continuar especial" if primary_type in {"special", "ova", "oad", "ona", "extra"}
                else "Continuar episódio"
            )
        elif is_next_after_completion:
            primary_label = "Próximo episódio"
        elif primary_action == "replay":
            primary_label = (
                "Reassistir filme" if is_movie
                else "Reassistir especial" if primary_type in {"special", "ova", "oad", "ona", "extra"}
                else "Reassistir episódio"
            )
        elif primary_action == "watch":
            primary_label = (
                "Assistir filme" if is_movie
                else "Assistir especial" if primary_type in {"special", "ova", "oad", "ona", "extra"}
                else "Assistir episódio"
            )
        else:
            primary_label = "Sem episódios disponíveis"
        metadata_status = str(metadata.get("metadata_status") or "unresolved").casefold()
        status_labels = {"available": "Metadata disponível", "manual": "Metadata manual", "stale": "Metadata desatualizada", "ambiguous": "Metadata ambígua", "unresolved": "Metadata não encontrada", "refreshing": "Atualizando metadata…"}
        metadata_state = status_labels.get(metadata_status, "Metadata parcial")
        refresh_button = ft.OutlinedButton("Atualizar metadata", icon=ft.Icons.REFRESH, on_click=on_refresh_metadata) if on_refresh_metadata else None

        marathon_button = (
            ft.OutlinedButton(
                "Maratona",
                icon=ft.Icons.TIMER_OUTLINED,
                disabled=not bool(regular_episodes or movie_episodes),
                on_click=lambda _: on_open_marathon(
                    anime_group["id"],
                    (primary_target or {}).get("path"),
                ),
            )
            if on_open_marathon is not None else None
        )

        primary_button = ft.FilledButton(
            primary_label, icon=ft.Icons.PLAY_ARROW, disabled=not bool(primary_target),
            on_click=lambda _: play(primary_target),
            style=ft.ButtonStyle(
                bgcolor=theme.primary,
                color=theme.text_on_overlay,
                side={
                    ft.ControlState.DEFAULT: ft.BorderSide(0, theme.primary),
                    ft.ControlState.FOCUSED: ft.BorderSide(0, theme.primary),
                },
                shape=ft.RoundedRectangleBorder(radius=12),
            ),
        )


        def edit_identification(episode):
            if not on_set_episode_identification:
                return
            page_width = float(page.width or 480)
            dialog_width = max(280.0, min(520.0, page_width - 32.0))
            title_width = max(180.0, min(330.0, dialog_width - 24.0))
            season = ft.TextField(label="Temporada", value="" if not episode.get("season") else str(episode["season"]), width=min(120.0, max(96.0, dialog_width / 3.2)))
            number = ft.TextField(label="Episódio", value="" if episode.get("number") is None else str(episode["number"]), width=min(120.0, max(96.0, dialog_width / 3.2)))
            kind = ft.Dropdown(label="Tipo", value=episode.get("episode_type") or "regular", width=min(160.0, max(120.0, dialog_width - 180.0)),
                               options=[ft.dropdown.Option(key=value, text=value) for value in ("regular", "special", "ova", "oad", "ona", "extra", "movie", "unknown")])
            title_field = ft.TextField(label="Título do episódio (opcional)", value=episode.get("episode_title") or "", width=title_width)
            dialog = None
            saving = [False]
            cancel_button = ft.TextButton("Cancelar", on_click=lambda _: dismiss_dialog(page, dialog))
            save_button = ft.FilledButton("Salvar")

            async def save(_event):
                if saving[0]:
                    return
                try:
                    parsed_season = int(season.value) if (season.value or "").strip() else None
                    parsed_number = float(number.value) if (number.value or "").strip() else None
                    if parsed_number is not None and parsed_number.is_integer():
                        parsed_number = int(parsed_number)
                except (TypeError, ValueError) as exc:
                    number.error_text = str(exc) or "Valores inválidos."
                    page.update()
                    return

                saving[0] = True
                save_button.disabled = True
                page.update()
                try:
                    await asyncio.to_thread(
                        on_set_episode_identification,
                        episode["path"],
                        season=parsed_season,
                        number=parsed_number,
                        episode_type=kind.value,
                        title=title_field.value,
                    )
                    if callable(is_active) and not is_active():
                        return
                    dismiss_dialog(page, dialog)
                    if on_identification_saved:
                        result = on_identification_saved()
                        if inspect.isawaitable(result):
                            await result
                    else:
                        episode.update(
                            season=parsed_season or 0,
                            number=parsed_number,
                            episode_type=kind.value,
                            episode_title=(title_field.value or "").strip() or None,
                            identification_source="manual",
                            identification_confidence="high",
                            manual_override=True,
                        )
                        render_episodes()
                    page.snack_bar = ft.SnackBar(ft.Text("Identificação manual salva."))
                    page.snack_bar.open = True
                    page.update()
                except (TypeError, ValueError) as exc:
                    number.error_text = str(exc) or "Valores inválidos."
                    save_button.disabled = False
                    saving[0] = False
                    page.update()
                except Exception:
                    DetailView._logger.exception("Failed to save episode identification")
                    number.error_text = "Não foi possível salvar a identificação."
                    save_button.disabled = False
                    saving[0] = False
                    page.update()

            save_button.on_click = save
            dialog = ft.AlertDialog(
                modal=True,
                title=ft.Text("Corrigir identificação"),
                content=ft.Column(
                    [season, number, kind, title_field],
                    tight=True,
                    width=dialog_width,
                ),
                actions=[cancel_button, save_button],
            )
            page.show_dialog(dialog)
            page.update()

        def _prepare_episode_artwork(items):
            ids = [item.get("id") for item in items if item.get("id") is not None and not item.get("missing")]
            if not ids:
                return
            if resolve_artwork_batch:
                try:
                    episode_artwork.update(
                        resolve_artwork_batch(
                            "episode",
                            ids,
                            ("episode_thumbnail", "poster"),
                        ) or {}
                    )
                except Exception:
                    DetailView._logger.exception("Batched episode artwork lookup failed")
                    if resolve_artwork:
                        for item_id in ids:
                            try:
                                resolved = resolve_artwork(
                                    "episode",
                                    item_id,
                                    "episode_thumbnail",
                                    allow_network=False,
                                )
                            except Exception:
                                continue
                            if resolved:
                                episode_artwork[str(item_id)] = resolved
            elif resolve_artwork:
                for item_id in ids:
                    try:
                        resolved = resolve_artwork(
                            "episode",
                            item_id,
                            "episode_thumbnail",
                            allow_network=False,
                        )
                    except Exception:
                        continue
                    if resolved:
                        episode_artwork[str(item_id)] = resolved
            if on_request_thumbnail:
                for item in items:
                    if item.get("id") is None or item.get("missing"):
                        continue
                    resolved = episode_artwork.get(str(item.get("id")))
                    if not resolved or resolved.get("artwork_type") != "episode_thumbnail" or resolved.get("fallback"):
                        try:
                            on_request_thumbnail(item, priority=400)
                        except Exception:
                            DetailView._logger.debug("Episode thumbnail request scheduling failed", exc_info=True)

        def _episode_control_key(episode, scope):
            episode_id = episode.get("id")
            if episode_id is not None:
                identity = str(episode_id)
            else:
                # Catalog rows normally have a real id. Keep a stable media
                # identity fallback for legacy rows without one; never use
                # position, title, progress, or ordering for control identity.
                identity = str(episode.get("media_identity") or episode.get("path") or "").strip()
            if not identity:
                return None
            return ft.ValueKey(f"{scope}:{identity}")

        def episode_item(episode, *, scope="episode"):
            episode_ratio = ratio(episode)
            number = episode.get("number")
            episode_type = str(episode.get("episode_type") or "regular").casefold()
            if is_movie:
                number_label = "ARQUIVO LOCAL"
            elif episode_type in {"special", "ova", "oad", "ona", "extra"}:
                number_label = episode_type.upper()
            elif isinstance(number, (int, float)) and math.isfinite(number):
                number_label = f"EP {int(number):02d}" if float(number).is_integer() else f"EP {number:g}"
            else:
                number_label = "EP —"
            state = consumption_state(episode)
            if episode.get("missing"):
                icon, status, color = ft.Icons.ERROR_OUTLINE, "Arquivo indisponível", WARNING
            elif state.value in {"completed", "watched"}:
                icon, status, color = ft.Icons.CHECK_CIRCLE, "Concluído" if state.value == "completed" else "Assistido", SUCCESS
            elif state.value == "in_progress":
                icon, status, color = ft.Icons.PLAY_CIRCLE_FILL, f"Em andamento • {int((episode_ratio or 0) * 100)}%", ACCENT
            else:
                icon, status, color = ft.Icons.PLAY_CIRCLE_OUTLINE, "Disponível localmente", theme.text_muted
            if episode.get("manual_override"):
                identification = "✎ Correção manual"
            elif episode.get("identification_confidence") == "low" or episode.get("episode_type") == "unknown":
                identification = "⚠ Precisa revisar"
            else:
                identification = "✓ Identificado"

            thumb = None
            episode_id = episode.get("id")
            if episode_id is not None and not episode.get("missing"):
                thumb_slot = ft.Container(
                    width=112,
                    height=72,
                    border_radius=RADIUS,
                    bgcolor=theme.surface_raised,
                    alignment=ft.Alignment(0, 0),
                )
                resolved_thumb = episode_artwork.get(str(episode_id))
                thumb_path = (
                    (resolved_thumb.get("local_path") or resolved_thumb.get("external_url"))
                    if resolved_thumb else None
                )
                if thumb_path:
                    thumb_slot.content = media_artwork(thumb_path, 72, width=112, icon_size=20)
                else:
                    thumb_slot.content = ft.Icon(ft.Icons.MOVIE_OUTLINED, color=theme.text_muted, size=20)
                try:
                    episode_thumbnail_bindings[int(episode_id)] = thumb_slot
                except (TypeError, ValueError):
                    pass
                thumb = thumb_slot

            duration = duration_label(episode)
            edit_button = (
                ft.IconButton(
                    icon=ft.Icons.EDIT_OUTLINED,
                    icon_size=16,
                    tooltip="Corrigir identificação",
                    visible=on_set_episode_identification is not None and not is_movie,
                    on_click=lambda _, item=episode: edit_identification(item),
                )
                if on_set_episode_identification is not None and not is_movie
                else None
            )
            details = ft.Column([
                ft.Row([
                    ft.Text(number_label, size=10, weight=ft.FontWeight.BOLD, color=theme.text_muted),
                    ft.Row([ft.Icon(icon, size=17, color=color)], tight=True),
                ], alignment=ft.MainAxisAlignment.SPACE_BETWEEN),
                ft.Text(episode.get("episode_title") or episode.get("title") or episode.get("file_name") or "Mídia local", size=13, color=theme.text, weight=ft.FontWeight.BOLD,
                        max_lines=2, overflow=ft.TextOverflow.ELLIPSIS),
                ft.Text(status if not is_movie else ("Concluído" if state.value in {"completed", "watched"} else "Filme local"), size=11, color=color),
                ft.Text(f"Duração • {duration}", size=10, color=theme.text_muted, visible=bool(duration)),
                ft.Text(f"Absoluto • {episode.get('absolute_number')}", size=10, color=theme.text_muted,
                        visible=episode.get("absolute_number") is not None and not is_movie),
                ft.Text(identification, size=10, color=theme.text_muted, visible=not is_movie),
            ], spacing=4, expand=True)
            # Keep the card tree structurally stable across 0% -> in-progress
            # transitions. The progress bar always exists at the same position;
            # only its value/visibility changes.
            progress_value = episode_ratio if episode_ratio is not None else 0
            show_progress = (
                episode_ratio is not None
                and episode_ratio > 0
                and not episode.get("missing")
                and state.value == "in_progress"
            )
            progress_bar = ft.ProgressBar(
                value=progress_value,
                color=contextual_accent[0],
                bgcolor=theme.surface_variant,
                bar_height=4,
                visible=show_progress,
            )
            progress_bars.append(progress_bar)
            details.controls.append(progress_bar)
            is_missing = bool(episode.get("missing"))
            clickable = None if is_missing else lambda _, item=episode: play(item)
            content = (ft.Row([thumb, details], spacing=10, vertical_alignment=ft.CrossAxisAlignment.CENTER)
                       if thumb else details)
            if is_missing:
                episode_button = ft.Container(
                    content=content, padding=12, border_radius=RADIUS, bgcolor=SURFACE,
                    opacity=.58,
                )
            else:
                episode_button = ft.OutlinedButton(
                    content=content,
                    on_click=clickable,
                    style=focus_button_style(theme=theme, background=SURFACE),
                )
            episode_key = _episode_control_key(episode, scope)
            if edit_button is not None:
                return ft.Row(
                    [episode_button, edit_button],
                    spacing=4,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                    key=episode_key,
                )
            if episode_key is not None:
                episode_button.key = episode_key
            return episode_button

        def update_thumbnail_in_place(uri, thumbnail_path, media_identity=None):
            uri = str(uri or "").strip()
            thumbnail_path = str(thumbnail_path or "").strip()
            media_identity = str(media_identity or "").strip()
            if not uri or not thumbnail_path:
                return False
            updated_holder = None
            for episode in episodes:
                if episode.get("missing"):
                    continue
                episode_uri = str(episode.get("path") or "").strip()
                episode_identity = str(episode.get("media_identity") or "").strip()
                if episode_uri != uri and (not media_identity or episode_identity != media_identity):
                    continue
                episode_id = episode.get("id")
                if episode_id is None:
                    continue
                episode_artwork[str(episode_id)] = {
                    "entity_type": "episode",
                    "entity_id": str(episode_id),
                    "artwork_type": "episode_thumbnail",
                    "source": "generated",
                    "local_path": thumbnail_path,
                    "status": "ready",
                }
                holder = episode_thumbnail_bindings.get(int(episode_id))
                if holder is not None and callable(is_active) and is_active():
                    holder.content = media_artwork(thumbnail_path, 72, width=112, icon_size=20)
                    updated_holder = holder
                break
            if updated_holder is not None:
                performance.counter("details.thumbnail.control_updates")
                updated_holder.update()
            return updated_holder is not None

        if isinstance(view_state, dict):
            view_state["_update_thumbnail"] = update_thumbnail_in_place

        def load_more_episodes(_event=None):
            visible_episode_count[0] += 48
            render_episodes()
            page.update()

        def load_more_specials(_event=None):
            visible_special_count[0] += 48
            render_episodes()
            page.update()

        def build_episode_controls():
            """Build the Details episode projection without mutating the mounted tree."""
            episode_artwork.clear()
            episode_thumbnail_bindings.clear()
            new_controls = []

            if is_movie:
                visible_movies = movie_episodes[:visible_episode_count[0]]
                _prepare_episode_artwork(visible_movies)
                if visible_movies:
                    new_controls.extend(
                        episode_item(item, scope="movie")
                        for item in visible_movies
                    )
                    if len(movie_episodes) > len(visible_movies):
                        new_controls.append(
                            ft.OutlinedButton(
                                f"Carregar mais • {len(movie_episodes) - len(visible_movies)} restantes",
                                on_click=load_more_episodes,
                            )
                        )
                else:
                    new_controls.append(
                        ft.Text(
                            "Nenhum arquivo de filme foi indexado.",
                            color=theme.text_muted,
                            size=13,
                        )
                    )
                return new_controls

            if not seasons and not special_episodes:
                new_controls.append(
                    ft.Container(
                        content=ft.Text(
                            "Nenhum episódio foi indexado para este anime.",
                            color=theme.text_muted,
                            size=13,
                        ),
                        padding=14,
                        bgcolor=SURFACE,
                        border_radius=RADIUS,
                    )
                )
                return new_controls

            if seasons:
                selected = seasons[min(selected_season[0], len(seasons) - 1)]
                if resolve_artwork:
                    season_number = selected.get("season")
                    if season_number is not None:
                        resolved_season = resolve_artwork(
                            "season",
                            f"{anime_group['id']}:season:{season_number}",
                            "season_poster",
                            allow_network=False,
                        )
                        if resolved_season:
                            season_path = (
                                resolved_season.get("local_path")
                                or resolved_season.get("external_url")
                            )
                            if season_path:
                                new_controls.append(
                                    media_artwork(
                                        season_path,
                                        150,
                                        width=100,
                                        icon_size=24,
                                    )
                                )
                season_items = selected.get("episodes", [])
                visible_regular = season_items[:visible_episode_count[0]]
                _prepare_episode_artwork(visible_regular)
                new_controls.extend(
                    episode_item(item, scope="episode")
                    for item in visible_regular
                )
                if len(season_items) > len(visible_regular):
                    new_controls.append(
                        ft.OutlinedButton(
                            f"Carregar mais • {len(season_items) - len(visible_regular)} episódios restantes",
                            on_click=load_more_episodes,
                        )
                    )

            if special_episodes:
                new_controls.append(section_title("Especiais", ft.Icons.STAR_OUTLINE))
                visible_special = special_episodes[:visible_special_count[0]]
                _prepare_episode_artwork(visible_special)
                new_controls.extend(
                    episode_item(item, scope="special")
                    for item in visible_special
                )
                if len(special_episodes) > len(visible_special):
                    new_controls.append(
                        ft.OutlinedButton(
                            f"Carregar mais especiais • {len(special_episodes) - len(visible_special)} restantes",
                            on_click=load_more_specials,
                        )
                    )
            return new_controls

        def render_episodes():
            # Rebuilds after the initial mount replace the collection atomically.
            # The caller owns the page-level update because this function is also
            # exercised against unmounted controls by the Python regression suite.
            episode_column.controls = build_episode_controls()

        episode_column = ft.Column(
            controls=build_episode_controls(),
            spacing=8,
        )

        def change_season(event):
            selected_season[0] = int(event.control.value)
            visible_episode_count[0] = 48
            if seasons:
                season_picker.helper_text = season_progress_text(seasons[selected_season[0]])
            render_episodes()
            page.update()

        season_picker = ft.Dropdown(
            value="0", options=[
                ft.dropdown.Option(
                    key=str(index),
                    text=f"{season.get('season_name') or f'Temporada {index + 1}'} • {sum(1 for item in season.get('episodes', []) if not item.get('missing'))}/{len(season.get('episodes', []))} locais",
                )
                for index, season in enumerate(seasons)
            ],
            color=theme.text, text_size=13, bgcolor=theme.surface,
            border_color=theme.border, border_radius=12, visible=bool(seasons) and len(seasons) > 1 and not is_movie,
        )
        def season_progress_text(season):
            items = season.get("episodes", [])
            available_items = [item for item in items if not item.get("missing")]
            watched_items = [item for item in available_items if is_completed(item)]
            active_items = [item for item in available_items if is_in_progress(item)]
            remaining = max(0, len(available_items) - len(watched_items))
            return (
                f"{len(watched_items)}/{len(available_items)} concluídos • {remaining} restantes"
                + (f" • {len(active_items)} em andamento" if active_items else "")
            )

        season_picker.helper_text = season_progress_text(seasons[0]) if seasons else None
        season_picker.on_select = change_season

        progress_section = []
        # current_episode also represents the next unwatched episode.  Only an
        # actual in-progress episode is a valid "CONTINUAR" projection.
        if current and is_in_progress(current):
            current_ratio = ratio(current)
            season = current.get("season")
            number = current.get("number")
            progress_section = [
                ft.Text("CONTINUAR", size=12, weight=ft.FontWeight.BOLD, color=theme.text_muted),
                ft.Container(content=ft.Column([
                    ft.Text(("Filme" if is_movie else f"Temporada {season or '—'} • Episódio {number if number is not None else '—'}"), color=theme.text, size=13, weight=ft.FontWeight.BOLD),
                    ft.Text(f"{int((current_ratio or 0) * 100)}% assistido" if current_ratio is not None else "Em andamento", color=theme.secondary, size=11),
                    ft.ProgressBar(value=current_ratio, color=contextual_accent[0], bgcolor=theme.surface_variant, bar_height=4,
                                   visible=current_ratio is not None),
                ], spacing=6), padding=12, bgcolor=SURFACE, border_radius=RADIUS),
            ]

        additional = []
        for label, value in (("Studio(s)", metadata.get("studio")), ("Temporada", metadata.get("season"))):
            if value:
                additional.append(ft.Row([
                    ft.Text(label, color=theme.text_muted, size=12, width=120),
                    ft.Text(str(value), color=theme.text, size=12, expand=True, max_lines=3, overflow=ft.TextOverflow.ELLIPSIS),
                ], vertical_alignment=ft.CrossAxisAlignment.START))

        for label, value in title_variants:
            additional.append(ft.Row([
                ft.Text(f"Título {label}", color=theme.text_muted, size=12, width=120),
                ft.Text(value, color=theme.text, size=12, expand=True, max_lines=2, overflow=ft.TextOverflow.ELLIPSIS),
            ], vertical_alignment=ft.CrossAxisAlignment.START))

        header = ft.Row([
            ft.IconButton(icon=ft.Icons.ARROW_BACK, icon_color=theme.text, tooltip="Voltar", on_click=lambda _: on_back()),
            ft.Text("Detalhes", size=17, weight=ft.FontWeight.BOLD, color=TEXT, expand=True),
            pin_button, favorite_button,
        ])
        hero_text = ft.Column([
            ft.Text(title, size=22, weight=ft.FontWeight.BOLD, color=TEXT, max_lines=4, overflow=ft.TextOverflow.ELLIPSIS),
            ft.Text(alternate_title, size=12, color=theme.text_muted, max_lines=2, overflow=ft.TextOverflow.ELLIPSIS, visible=bool(alternate_title)),
            ft.Row(facts, wrap=True, spacing=6, run_spacing=6),
            ft.Row(genre_controls, wrap=True, spacing=6, run_spacing=6, visible=bool(genre_controls)),
            ft.Row(([ft.Text(metadata_state, size=11, color=TEXT_MUTED)] + ([refresh_button] if refresh_button else [])), spacing=10, vertical_alignment=ft.CrossAxisAlignment.CENTER),
            primary_button,
            ft.Text(f"{missing_count} indisponível{'is' if missing_count != 1 else ''} na biblioteca local", size=11, color=theme.warning, visible=missing_count > 0),
        ], spacing=9, expand=True)

        layout_controls = []
        if backdrop:
            layout_controls.append(backdrop)
        layout_controls.extend([
            header,
            ft.Row([hero_accent_indicator, poster, hero_text], spacing=10, vertical_alignment=ft.CrossAxisAlignment.START),
        ])
        if description:
            layout_controls.extend([section_title("Sinopse", ft.Icons.SUBJECT_OUTLINED), description_text, expand_button])
        if on_set_user_tags:
            layout_controls.extend([
                section_title("Etiquetas pessoais", ft.Icons.LOCAL_OFFER_OUTLINED),
                ft.Row([tags_row, ft.OutlinedButton("Adicionar", icon=ft.Icons.ADD, on_click=add_tag)], wrap=True, spacing=8),
            ])
        if on_set_personal_note:
            layout_controls.extend([
                section_title("Nota pessoal", ft.Icons.STICKY_NOTE_2_OUTLINED),
                ft.Container(ft.Column([note_summary, note_button], spacing=8), padding=12, bgcolor=SURFACE, border_radius=RADIUS),
            ])
        layout_controls.extend(progress_section)
        layout_controls.extend([
            section_title("Filme" if is_movie else "Episódios", ft.Icons.MOVIE_OUTLINED if is_movie else ft.Icons.FORMAT_LIST_NUMBERED),
            season_picker,
            episode_column,
        ])
        if additional:
            layout_controls.extend([section_title("Informações adicionais", ft.Icons.INFO_OUTLINE),
                                    ft.Container(ft.Column(additional, spacing=9), padding=12, bgcolor=SURFACE, border_radius=RADIUS)])

        async def load_contextual_palette():
            if resolve_artwork_palette is None:
                return
            try:
                palette = await asyncio.to_thread(
                    resolve_artwork_palette,
                    artwork_entity,
                    anime_group["id"],
                    "poster",
                    mode=theme.mode,
                )
            except Exception:
                DetailView._logger.exception("Contextual artwork palette failed", extra={"screen": "details"})
                return
            if not palette:
                return
            if callable(is_active) and not is_active():
                return
            accent = str(palette.get("accent") or "").strip()
            on_accent = str(palette.get("on_accent") or "").strip()
            soft = str(palette.get("accent_soft") or "").strip()
            if not accent:
                return
            # The Details tree owns this exact button instance. Apply the palette
            # only when it actually changes; this prevents redundant page.update()
            # calls from looking like a visual state transition.
            next_on_accent = on_accent or theme.text_on_accent
            next_soft = soft or theme.surface_variant
            palette_changed = (
                contextual_accent[0] != accent
                or contextual_on_accent[0] != next_on_accent
                or contextual_soft[0] != next_soft
            )
            if not palette_changed:
                return
            contextual_accent[0] = accent
            contextual_on_accent[0] = next_on_accent
            contextual_soft[0] = next_soft
            hero_accent_indicator.bgcolor = accent
            primary_button.style = ft.ButtonStyle(
                bgcolor=accent,
                color=contextual_on_accent[0],
                side={
                    ft.ControlState.DEFAULT: ft.BorderSide(0, accent),
                    ft.ControlState.FOCUSED: ft.BorderSide(0, accent),
                },
                shape=ft.RoundedRectangleBorder(radius=12),
            )
            pin_button.icon_color = accent if pinned[0] else theme.text
            for progress_bar in progress_bars:
                progress_bar.color = accent
            page.update()

        layout = ft.Column(layout_controls, scroll=ft.ScrollMode.AUTO, expand=True, spacing=14)
        # episode_column was already built declaratively above. Do not invoke
        # render_episodes() during build: the control is not mounted yet.
        run_task = getattr(page, "run_task", None)
        if callable(run_task):
            run_task(load_contextual_palette)
        result = ft.Container(
            content=layout,
            padding=ft.Padding(left=PAGE_PADDING, right=PAGE_PADDING, top=14, bottom=18),
            bgcolor=BACKGROUND,
            expand=True,
        )
        performance.record_ui_build("details", (performance.now()-build_started)*1000.0,
                                    controls=performance.control_count(result), cached=False)
        return result
