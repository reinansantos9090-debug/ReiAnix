"""Settings Center 2.0 for ReiAnix.

The view is a thin UI layer. Persistent values live in the existing
LibraryStore preferences table through SettingsStore; storage, scanner,
metadata, artwork and player remain owned by their existing services.
"""
from __future__ import annotations

import asyncio
import inspect
import logging

import flet as ft

from core.storage_access import normalize_storage_snapshot
from core.backup import BackupError
from core.settings import SettingsStore, SettingsValidationError
from core.performance import get_performance_monitor
from core.settings_focus import SettingsFocusState
from core.ui import BACKGROUND, PAGE_PADDING, RADIUS, SURFACE, TEXT, TEXT_MUTED, activate_theme_for_page, section_title, focus_button_style

logger = logging.getLogger("reiflix.settings")


class SettingsView:
    @staticmethod
    def build(
        page, store, library, on_back, on_catalog_changed, on_add_folder,
        on_remove_folder, on_refresh_library, on_request_video_access,
        on_open_broad_storage, on_login, on_logout, account,
        account_state="disconnected", folder_selection_pending=lambda: False,
        on_resolve_match=lambda _lookup, _id: None, storage_snapshot=None,
        on_check_video_access=None,
        scan_snapshot=None, settings: SettingsStore | None = None,
        on_create_backup=None, on_inspect_backup=None, on_restore_backup=None,
        on_export_diagnostics=None, on_integrity_check=None, on_reconcile_after_restore=None,
        on_settings_changed=None,
        on_open_settings_category=None,
        settings_path_provider=lambda: (),
        settings_is_active=lambda: True,
        settings_generation_provider=lambda: 0,
        register_settings_task=None,
        view_state=None,
    ):
        performance = get_performance_monitor()
        build_started = performance.now()
        performance.counter("settings.builds")
        settings = settings or SettingsStore(store)
        theme = activate_theme_for_page(page)
        BACKGROUND = theme.background
        SURFACE = theme.surface
        TEXT = theme.text
        TEXT_MUTED = theme.text_muted
        view_state = view_state if isinstance(view_state, dict) else {}
        view_generation = settings_generation_provider()
        focus_state = SettingsFocusState()
        focus_task = None
        initial_render_pending = True
        busy = {"scan": False, "folder": False, "permission": False, "cache": False}
        status = ft.Text("", size=12, color=TEXT_MUTED)
        search = ft.TextField(
            hint_text="Pesquisar configurações…",
            prefix_icon=ft.Icons.SEARCH,
            dense=True,
            border_radius=12,
        )
        sections_host = ft.Column(spacing=12, scroll=ft.ScrollMode.AUTO, expand=True)

        def safe_update():
            nonlocal initial_render_pending
            if not settings_is_active():
                return
            try:
                performance.counter("settings.page_updates")
                page.update()
            except Exception:
                logger.debug("settings update skipped")

        def notice(message: str, error: bool = False):
            status.value = message
            status.color = theme.error if error else TEXT_MUTED
            safe_update()

        backup_restore_busy = {"value": False}

        def start_task(handler, *args):
            task = page.run_task(handler, *args)
            if callable(register_settings_task):
                register_settings_task(task)
            return task

        async def execute_action():
            try:
                result = action()
                if inspect.isawaitable(result):
                    await result
            except Exception:
                logger.exception("settings action failed")
                notice("Não foi possível concluir a operação.", True)
            finally:
                safe_update()

        def confirm(title, body, action_label, action):
            if not settings.get("app.confirm_destructive"):
                start_task(execute_action)
                return

            async def run(_):
                page.pop_dialog()
                await execute_action()

            page.show_dialog(ft.AlertDialog(
                modal=True,
                title=ft.Text(title),
                content=ft.Text(body),
                actions=[
                    ft.TextButton("Cancelar", on_click=lambda _: page.pop_dialog()),
                    ft.FilledButton(action_label, on_click=run),
                ],
            ))
            safe_update()

        def save(key, value, control=None):
            try:
                normalized = settings.set(key, value)
                if control is not None:
                    control.value = normalized
                if on_settings_changed is not None:
                    try:
                        result = on_settings_changed(key, normalized)
                        if inspect.isawaitable(result):
                            start_task(result)
                    except Exception:
                        logger.exception("settings runtime apply failed: %s", key)
                        notice("Configuração salva, mas a aplicação em runtime falhou.", True)
                        return False
                notice("Configuração salva.")
                return True
            except (SettingsValidationError, ValueError, TypeError):
                logger.exception("invalid setting %s", key)
                notice("Valor inválido para esta configuração.", True)
                return False
            except Exception:
                logger.exception("setting persistence failed: %s", key)
                notice("Não foi possível salvar a configuração.", True)
                return False

        def row(key, label, description, kind="bool", choices=None, labels=None):
            value = settings.get(key)
            if kind == "bool":
                control = ft.Switch(
                    value=bool(value),
                    on_change=lambda e, k=key: save(k, e.control.value, e.control),
                )
            else:
                display = labels.get(value, str(value)) if labels else str(value)
                async def choose(_):
                    opts = tuple(choices or ())
                    buttons = []
                    dialog = ft.AlertDialog(modal=True, title=ft.Text(label))
                    for option in opts:
                        text = labels.get(option, str(option)) if labels else str(option)
                        async def pick(_event, selected=option):
                            save(key, selected)
                            page.pop_dialog()
                            rebuild()
                        buttons.append(ft.TextButton(text, on_click=pick))
                    dialog.content = ft.Column(buttons, tight=True)
                    page.show_dialog(dialog)
                    safe_update()
                control = ft.TextButton(display, on_click=choose)
            container = ft.Container(
                content=ft.Row([
                    ft.Column([
                        ft.Text(label, color=TEXT, size=13, weight=ft.FontWeight.BOLD),
                        ft.Text(description, color=TEXT_MUTED, size=10),
                    ], spacing=2, expand=True),
                    control,
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
                padding=ft.Padding(left=0, top=7, right=0, bottom=7),
            )
            container.data = f"{key} {label} {description}".casefold()
            return container

        def action_row(label, description, button_text, callback):
            container = ft.Container(
                content=ft.Row([
                    ft.Column([
                        ft.Text(label, color=TEXT, size=13, weight=ft.FontWeight.BOLD),
                        ft.Text(description, color=TEXT_MUTED, size=10),
                    ], spacing=2, expand=True),
                    ft.OutlinedButton(button_text, on_click=callback),
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
                padding=ft.Padding(left=0, top=7, right=0, bottom=7),
            )
            container.data = f"{label} {description}".casefold()
            return container

        def section(title, icon, items, tags=()):
            container = ft.Container(
                content=ft.Column([section_title(title, icon), *items], spacing=4),
                padding=14, bgcolor=SURFACE, border_radius=RADIUS,
            )
            item_terms = " ".join(str(getattr(item, "data", "")) for item in items)
            container.data = " ".join(
                (f"__category:{title.casefold()}__", title, *tags, item_terms)
            ).casefold()
            return container

        section_cache = []
        back_button = ft.IconButton(icon=ft.Icons.ARROW_BACK, tooltip="Voltar")
        header_title = ft.Text("Configurações", size=20, weight=ft.FontWeight.BOLD, color=TEXT)

        category_meta = {
            "Conta": ("Conta e autenticação", ft.Icons.PERSON_OUTLINE),
            "Geral": ("Comportamento geral do aplicativo", ft.Icons.SETTINGS_OUTLINED),
            "Aparência": ("Tema e apresentação", ft.Icons.DARK_MODE_OUTLINED),
            "Biblioteca": ("Catálogo, grade e Continue Watching", ft.Icons.VIDEO_LIBRARY_OUTLINED),
            "Player": ("Reprodução, vídeo, controles e tela", ft.Icons.PLAY_CIRCLE_OUTLINE),
            "Gestos": ("Interações de toque do player", ft.Icons.TOUCH_APP_OUTLINED),
            "Áudio e Legendas": ("Idiomas, legendas e áudio", ft.Icons.HEADPHONES_OUTLINED),
            "Metadata": ("AniList e matching", ft.Icons.MANAGE_SEARCH_OUTLINED),
            "Artwork": ("Capas, thumbnails e cache", ft.Icons.IMAGE_OUTLINED),
            "Armazenamento": ("Permissões, SAF, MediaStore e volumes", ft.Icons.STORAGE_OUTLINED),
            "Dados e Cache": ("Configurações, importação, exportação e cache", ft.Icons.CACHED_OUTLINED),
            "Backup e Restauração": ("Backup, restauração, integridade e reconciliação", ft.Icons.SECURITY_OUTLINED),
            "Privacidade": ("Dados locais e conectividade", ft.Icons.PRIVACY_TIP_OUTLINED),
            "Varredura": ("Estado e histórico das varreduras", ft.Icons.REFRESH_OUTLINED),
            "Diagnóstico": ("Informações técnicas e diagnóstico", ft.Icons.BUG_REPORT_OUTLINED),
            "Sobre": ("Versão e componentes do ReiAnix", ft.Icons.INFO_OUTLINE),
        }

        def current_settings_path():
            try:
                path = tuple(settings_path_provider() or ())
            except Exception:
                logger.exception("settings navigation path lookup failed")
                return ()
            return tuple(str(item) for item in path if str(item).strip())

        def current_category():
            path = current_settings_path()
            return path[-1] if path else None

        def open_category(label):
            search.value = ""
            if on_open_settings_category is not None:
                on_open_settings_category(label)
            else:
                logger.warning("settings category requested without navigation callback: %s", label)

        def back_to_categories():
            search.value = ""
            on_back()

        async def reveal_category_focus(key, focus_generation):
            async with performance.task_scope(
                "settings.reveal_category_focus",
                screen="settings",
                generation=(view_generation, focus_generation),
            ):
                if (
                    not settings_is_active()
                    or settings_generation_provider() != view_generation
                    or focus_state.generation != focus_generation
                ):
                    performance.counter("settings_stale_focus_tasks")
                    return
                try:
                    performance.counter("settings_focus_reveals")
                    performance.counter("settings_focus_scroll_requests")
                    performance.counter("settings_scroll_requests")
                    await sections_host.scroll_to(scroll_key=key, duration=120)
                    if (
                        settings_is_active()
                        and settings_generation_provider() == view_generation
                        and focus_state.generation == focus_generation
                    ):
                        performance.counter("settings_scroll_completed")
                        performance.event(
                            "settings.scroll_to",
                            screen="settings",
                            metadata={
                                "origin": "focus",
                                "key": key,
                                "settings_path": settings_path_provider(),
                            },
                        )
                    else:
                        performance.counter("settings_stale_focus_tasks")
                except asyncio.CancelledError:
                    performance.counter("settings_scroll_cancelled")
                    raise
                except Exception:
                    logger.debug("Settings focus scroll skipped key=%s", key, exc_info=True)

        def handle_category_focus(key):
            nonlocal focus_task
            if (
                not settings_is_active()
                or settings_generation_provider() != view_generation
            ):
                performance.counter("settings_stale_focus_events")
                return
            decision = focus_state.on_focus(key)
            performance.counter("settings_focus_events")
            performance.event(
                "settings.focus",
                screen="settings",
                metadata={
                    "key": key,
                    "reason": decision.reason,
                    "generation": decision.generation,
                    "settings_path": settings_path_provider(),
                },
            )
            if not decision.should_scroll:
                performance.counter(f"settings_focus_suppressed_{decision.reason}")
                return
            if focus_task is not None:
                try:
                    focus_task.cancel()
                except Exception:
                    logger.debug("previous Settings focus task cancellation failed", exc_info=True)
            focus_task = start_task(
                reveal_category_focus,
                key,
                decision.generation,
            )

        def build_category_tile(label):
            description, icon = category_meta.get(label, ("Configurações ReiAnix", ft.Icons.SETTINGS_OUTLINED))
            key = f"settings-category-{label}"
            return ft.OutlinedButton(
                key=key,
                height=70,
                on_focus=lambda _event, k=key: handle_category_focus(k),
                on_click=lambda _event, item=label: open_category(item),
                style=focus_button_style(theme=theme, background=SURFACE),
                content=ft.Row([
                    ft.Icon(icon, color=TEXT, size=24),
                    ft.Column([
                        ft.Text(label, color=TEXT, size=14, weight=ft.FontWeight.BOLD),
                        ft.Text(description, color=TEXT_MUTED, size=10),
                    ], spacing=2, expand=True),
                    ft.Icon(ft.Icons.CHEVRON_RIGHT, color=TEXT_MUTED),
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
            )

        def render_settings(_=None):
            nonlocal section_cache, initial_render_pending
            render_started = performance.now()
            performance.counter("settings.renders")
            query = (search.value or "").strip().casefold()
            active_category = current_category()
            if active_category is None and query and not section_cache:
                section_cache = build_sections()
            if active_category is None:
                labels = list(category_meta) if not section_cache else [
                    label for label in category_meta
                    if any(
                        f"__category:{label.casefold()}__" in str(getattr(item, "data", ""))
                        for item in section_cache
                    )
                ]
                controls = [
                    build_category_tile(label)
                    for label in labels
                    if not query
                    or query in label.casefold()
                    or query in str(category_meta.get(label, ("", None))[0]).casefold()
                    or any(
                        query in str(getattr(item, "data", ""))
                        for item in section_cache
                        if f"__category:{label.casefold()}__" in str(getattr(item, "data", ""))
                    )
                ]
            else:
                controls = [
                    item for item in section_cache
                    if f"__category:{str(active_category).casefold()}__" in str(getattr(item, "data", ""))
                    and (not query or query in getattr(item, "data", ""))
                ]
            sections_host.controls = controls or [
                ft.Text("Nenhuma configuração corresponde à pesquisa.", color=TEXT_MUTED, size=12),
            ]
            header_title.value = "Configurações" if active_category is None else str(active_category)
            back_button.tooltip = "Voltar ao menu de configurações" if active_category is not None else "Voltar"
            back_button.on_click = (lambda _event: back_to_categories()) if active_category is not None else (lambda _event: on_back())
            performance.event("settings.render", duration_ms=(performance.now()-render_started)*1000.0,
                              screen="settings",
                              metadata={"category": active_category, "query": bool(query), "controls": len(controls)})
            if initial_render_pending:
                initial_render_pending = False
            else:
                safe_update()

        def rebuild(_=None):
            nonlocal section_cache
            active_category = current_category()
            query = (search.value or "").strip()
            if active_category is None and not query:
                section_cache = []
            else:
                section_cache = build_sections()
            render_settings()

        search.on_change = render_settings

        normalized = normalize_storage_snapshot(storage_snapshot)
        # StorageCapabilities is a typed model; keep Settings on its public
        # attributes instead of depending on dict-style compatibility helpers.
        media_state = str(normalized.media_read_state or "denied").casefold()
        broad_state = str(normalized.broad_storage_state or "unavailable").casefold()
        saf_roots = tuple(normalized.saf_roots or ())
        volumes = tuple(normalized.removable_volumes or ())
        scan = scan_snapshot or {}
        runtime_status = str(scan.get("state") or "IDLE").upper()
        running_scan = runtime_status in {"CHECKING", "SCANNING", "WAITING_FOR_MEDIASTORE"}
        data_cache = {
            "last_scan": None,
            "folders": None,
            "summary": None,
            "database": None,
        }

        def get_last_scan():
            if running_scan:
                return None
            if data_cache["last_scan"] is None:
                data_cache["last_scan"] = store.last_scan()
            return data_cache["last_scan"]

        def get_folders():
            if data_cache["folders"] is None:
                data_cache["folders"] = store.folders()
            return data_cache["folders"]

        def get_summary():
            if data_cache["summary"] is None:
                data_cache["summary"] = store.library_summary()
            return data_cache["summary"]

        def get_database_check():
            if data_cache["database"] is None:
                if hasattr(store, "database_check"):
                    data_cache["database"] = store.database_check()
                else:
                    data_cache["database"] = (False, "não disponível")
            return data_cache["database"]

        def add_folder(_):
            if busy["folder"] or folder_selection_pending():
                return
            busy["folder"] = True
            async def run():
                try:
                    await on_add_folder()
                    notice("Abrindo seletor Android…")
                except Exception:
                    logger.exception("folder picker failed")
                    notice("Não foi possível abrir o seletor de pasta.", True)
                finally:
                    busy["folder"] = False
                    safe_update()
            start_task(run)

        def refresh(_):
            if busy["scan"]:
                return
            busy["scan"] = True
            async def run():
                try:
                    message, waiting = await on_refresh_library()
                    notice(message)
                    on_catalog_changed()
                except Exception:
                    logger.exception("library refresh failed")
                    notice("Não foi possível atualizar a biblioteca.", True)
                finally:
                    busy["scan"] = False
                    safe_update()
            start_task(run)

        def request_permission(_):
            if busy["permission"]:
                return
            busy["permission"] = True
            async def run():
                try:
                    await on_request_video_access()
                    notice("Solicitação de permissão enviada ao Android.")
                except Exception:
                    logger.exception("video permission request failed")
                    notice("Não foi possível solicitar a permissão.", True)
                finally:
                    busy["permission"] = False
                    safe_update()
            start_task(run)

        def verify_permission(_):
            if busy["permission"]:
                return
            busy["permission"] = True
            async def run():
                try:
                    if on_check_video_access is None:
                        raise RuntimeError("Verificação de armazenamento indisponível.")
                    await on_check_video_access()
                    notice("Estado atual da permissão foi solicitado ao Android.")
                except Exception:
                    logger.exception("video permission verification failed")
                    notice("Não foi possível verificar a permissão atual.", True)
                finally:
                    busy["permission"] = False
                    safe_update()
            start_task(run)

        def broad(_):
            if busy["permission"]:
                return
            busy["permission"] = True
            async def run():
                try:
                    await on_open_broad_storage()
                    notice("Abrindo as configurações de armazenamento do Android.")
                except Exception:
                    logger.exception("broad storage request failed")
                    notice("Não foi possível abrir o armazenamento.", True)
                finally:
                    busy["permission"] = False
                    safe_update()
            start_task(run)

        def clear_cache_action():
            cache_started = performance.now()
            busy["cache"] = True
            try:
                removed = library.clear_anilist_cache()
                performance.event("settings.clear_cache", duration_ms=(performance.now()-cache_started)*1000.0,
                                  screen="settings", metadata={"removed": removed})
                notice(f"Cache AniList limpo ({removed} capa(s) removida(s)).")
            except Exception:
                performance.event("settings.clear_cache", duration_ms=(performance.now()-cache_started)*1000.0,
                                  status="error", screen="settings")
                logger.exception("clear cache failed")
                notice("Não foi possível limpar o cache AniList.", True)
            finally:
                busy["cache"] = False

        def clear_cache(_):
            confirm(
                "Limpar cache AniList?",
                "A biblioteca, progresso, favoritos, notas, pins e arquivos não serão apagados.",
                "Limpar",
                clear_cache_action,
            )

        def reset_player(_):
            confirm(
                "Restaurar Player?",
                "Somente as preferências do Player serão restauradas. Biblioteca, progresso e arquivos permanecem intactos.",
                "Restaurar",
                lambda: (settings.reset_category("player"), notice("Configurações do Player restauradas."), rebuild()),
            )

        def reset_all(_):
            def do_reset_all():
                settings.reset_all()
                apply_theme_from_settings()
                notice("Configurações restauradas.")
                rebuild()
            confirm(
                "Restaurar todas as configurações?",
                "Somente preferências do ReiAnix serão restauradas. Biblioteca, consumo, metadata manual, artwork, arquivos e permissões não serão apagados.",
                "Restaurar",
                do_reset_all,
            )


        def language_row(key, label, description):
            value = settings.get(key)
            field = ft.TextField(
                value=value,
                hint_text="Automático",
                width=150,
                dense=True,
                on_submit=lambda e, k=key: save(k, e.control.value, e.control),
            )
            return ft.Container(
                content=ft.Row([
                    ft.Column([
                        ft.Text(label, color=TEXT, size=13, weight=ft.FontWeight.BOLD),
                        ft.Text(description, color=TEXT_MUTED, size=10),
                    ], spacing=2, expand=True),
                    field,
                    ft.OutlinedButton("Salvar", on_click=lambda _, k=key, control=field: save(k, control.value, control)),
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
                padding=ft.Padding(left=0, top=7, right=0, bottom=7),
            )

        def apply_theme_from_settings():
            if on_settings_changed is not None:
                try:
                    result = on_settings_changed("appearance.theme", settings.get("appearance.theme"))
                    if inspect.isawaitable(result):
                        start_task(result)
                    return
                except Exception:
                    logger.exception("settings runtime theme apply failed")
            activate_theme_for_page(page, settings.get("appearance.theme"))

        async def export_settings(_):
            try:
                raw = settings.export_json().encode("utf-8")
                path = await ft.FilePicker().save_file(
                    dialog_title="Exportar configurações",
                    file_name="reiflix-settings.json",
                    file_type=ft.FilePickerFileType.CUSTOM,
                    allowed_extensions=["json"],
                    src_bytes=raw,
                )
                if path:
                    notice("Configurações exportadas com sucesso.")
                else:
                    notice("Exportação cancelada.")
            except Exception:
                logger.exception("settings export failed")
                notice("Não foi possível exportar as configurações.", True)

        async def import_settings(_):
            try:
                files = await ft.FilePicker().pick_files(
                    dialog_title="Importar configurações",
                    allow_multiple=False,
                    with_data=True,
                    file_type=ft.FilePickerFileType.CUSTOM,
                    allowed_extensions=["json"],
                )
                if not files:
                    notice("Importação cancelada.")
                    return
                selected = files[0]
                raw = selected.bytes or b""
                if not raw:
                    notice("O arquivo selecionado está vazio.", True)
                    return
                result = settings.import_json(raw.decode("utf-8"))
                apply_theme_from_settings()
                on_catalog_changed()
                unknown = len(result.get("unknown", []))
                message = f"{result['imported']} configurações importadas."
                if unknown:
                    message += f" {unknown} chave(s) desconhecida(s) foram ignoradas."
                notice(message)
                rebuild()
            except (UnicodeDecodeError, SettingsValidationError):
                logger.exception("invalid settings import")
                notice("Arquivo de configurações inválido ou incompatível.", True)
            except Exception:
                logger.exception("settings import failed")
                notice("Não foi possível importar as configurações.", True)

        async def call_callback(callback, *args):
            if callback is None:
                raise RuntimeError("Operação não disponível nesta versão.")
            result = callback(*args)
            if inspect.isawaitable(result):
                return await result
            return result

        async def create_backup_file(_):
            if backup_restore_busy["value"]:
                notice("Outra operação de backup/restauração já está em execução.", True)
                return
            backup_restore_busy["value"] = True
            try:
                notice("Preparando backup…")
                raw = await call_callback(on_create_backup)
                if not raw:
                    raise RuntimeError("Backup vazio.")
                stamp = __import__("time").strftime("%Y%m%d-%H%M%S")
                path = await ft.FilePicker().save_file(
                    dialog_title="Salvar backup ReiAnix",
                    file_name=f"reiflix-backup-{stamp}.zip",
                    file_type=ft.FilePickerFileType.CUSTOM,
                    allowed_extensions=["zip"],
                    src_bytes=raw,
                )
                notice("Backup concluído e validado." if path else "Backup cancelado.")
            except Exception:
                logger.exception("backup export failed")
                notice("Não foi possível concluir o backup.", True)
            finally:
                backup_restore_busy["value"] = False
                safe_update()

        async def restore_backup_file(_):
            if backup_restore_busy["value"]:
                notice("Outra operação de backup/restauração já está em execução.", True)
                return
            backup_restore_busy["value"] = True
            try:
                notice("Selecione o backup para validar…")
                files = await ft.FilePicker().pick_files(
                    dialog_title="Selecionar backup ReiAnix",
                    allow_multiple=False,
                    with_data=True,
                    file_type=ft.FilePickerFileType.CUSTOM,
                    allowed_extensions=["zip"],
                )
                if not files:
                    notice("Restore cancelado.")
                    return
                raw = files[0].bytes or b""
                notice("Validando formato, checksum e schema…")
                preview = await call_callback(on_inspect_backup, raw)
                counts = preview.get("counts") or {}
                content = ft.Column([
                    ft.Text(f"Data: {preview.get('created_at') or 'desconhecida'}", color=TEXT, size=11),
                    ft.Text(f"Versão: formato {preview.get('format_version')} • app {preview.get('app_version')} • schema {preview.get('schema_version')}", color=TEXT_MUTED, size=10),
                    ft.Text(
                        f"Animes: {counts.get('anime', 0)} • Episódios: {counts.get('episodes', 0)} • Favoritos: {counts.get('favorites', 0)} • Tags: {counts.get('tags', 0)} • Notas: {counts.get('notes', 0)}",
                        color=TEXT_MUTED, size=10,
                    ),
                    ft.Text(
                        f"Progresso: {counts.get('progress', 0)} • Missing: {counts.get('missing_files', 0)} • AniList: {counts.get('anilist_matches', 0)} • Artwork refs: {counts.get('artwork_references', 0)}",
                        color=TEXT_MUTED, size=10,
                    ),
                    ft.Text(f"Integridade: {preview.get('integrity')}", color=TEXT, size=11),
                    ft.Text(
                        "Antes do restore será criado um snapshot de segurança. Autenticação Google não é restaurada; "
                        "uma sessão existente é preservada quando o SQLite atual puder ser lido.",
                        color=TEXT_MUTED, size=10,
                    ),
                    *(
                        [ft.Text(
                            "Artwork opcional corrompido/ausente será ignorado quando possível; o banco continuará sendo validado.",
                            color=TEXT_MUTED, size=10,
                        )]
                        if preview.get("integrity_warnings") else []
                    ),
                ], tight=True, spacing=6)

                async def confirm_restore(_event):
                    page.pop_dialog()
                    try:
                        notice("Preparando snapshot de segurança e restore…")
                        result = await call_callback(on_restore_backup, raw)
                        report = result.get("report") or {}
                        notice(
                            f"Restore concluído. {report.get('missing_files', 0)} arquivo(s) permaneceram como missing."
                        )
                        rebuild()
                    except BackupError as exc:
                        logger.exception("backup restore failed: %s", getattr(exc, "code", "RESTORE_FAILED"))
                        notice(str(exc), True)
                    except Exception:
                        logger.exception("backup restore failed")
                        notice("Restore recusado ou falhou; o estado anterior foi preservado.", True)
                    finally:
                        backup_restore_busy["value"] = False
                        safe_update()

                def cancel_restore(_event):
                    page.pop_dialog()
                    backup_restore_busy["value"] = False
                    notice("Restore cancelado.")

                page.show_dialog(ft.AlertDialog(
                    modal=True,
                    title=ft.Text("Restaurar backup?"),
                    content=content,
                    actions=[
                        ft.TextButton("Cancelar", on_click=cancel_restore),
                        ft.FilledButton("Restaurar", on_click=confirm_restore),
                    ],
                ))
                safe_update()
                return
            except Exception:
                logger.exception("backup restore preview failed")
                notice("O backup selecionado é inválido ou incompatível.", True)
                backup_restore_busy["value"] = False
                safe_update()

        async def reconcile_after_restore(_):
            try:
                notice("Reconciliando referências de arquivos…")
                result = await call_callback(on_reconcile_after_restore)
                if result and result.get("accepted") is False:
                    notice("A reconciliação não foi iniciada porque há outra atualização em execução.", True)
                else:
                    notice("Reconciliação solicitada ao ScanCoordinator.")
            except Exception:
                logger.exception("restore reconciliation failed")
                notice("Não foi possível iniciar a reconciliação.", True)

        async def export_diagnostic(_):
            try:
                notice("Gerando diagnóstico técnico…")
                raw = await call_callback(on_export_diagnostics)
                stamp = __import__("time").strftime("%Y%m%d-%H%M%S")
                path = await ft.FilePicker().save_file(
                    dialog_title="Exportar diagnóstico",
                    file_name=f"reiflix-diagnostic-{stamp}.json",
                    file_type=ft.FilePickerFileType.CUSTOM,
                    allowed_extensions=["json"],
                    src_bytes=raw,
                )
                notice("Diagnóstico exportado." if path else "Exportação cancelada.")
            except Exception:
                logger.exception("diagnostic export failed")
                notice("Não foi possível exportar o diagnóstico.", True)

        async def verify_integrity(_):
            try:
                notice("Verificando SQLite, foreign keys e inconsistências…")
                report = await call_callback(on_integrity_check)
                db = report.get("database") or {}
                text = (
                    f"Estado: {report.get('overall')}\n"
                    f"SQLite: {db.get('quick_check')}\n"
                    f"Foreign keys: {'OK' if db.get('foreign_key_ok') else 'ERRO'}\n"
                    f"Missing files: {(report.get('files') or {}).get('missing', 0)}\n"
                    f"Duplicidades: {report.get('duplicates')}\n"
                    f"Órfãos: {report.get('orphans')}"
                )
                page.show_dialog(ft.AlertDialog(
                    modal=True,
                    title=ft.Text("Verificação de integridade"),
                    content=ft.Text(text, color=TEXT_MUTED, size=11),
                    actions=[ft.TextButton("Fechar", on_click=lambda _: page.pop_dialog())],
                ))
                safe_update()
            except Exception:
                logger.exception("integrity check failed")
                notice("Não foi possível verificar a integridade.", True)

        def build_sections():
            sections_started = performance.now()
            performance.counter("settings.build_sections")
            items = []
            active_category = current_category()
            query_text = (search.value or "").strip().casefold()
            materialize_all = active_category is None and bool(query_text)

            def should_materialize_section(label):
                return active_category == label or materialize_all
            connected = bool(account.get("email"))
            account_text = account.get("name") or account.get("email") or "Não conectado"
            account_status = {
                "connecting": "Conectando…", "awaiting_google": "Aguardando Google…",
                "connected": "Conectada", "error": "Erro ao conectar",
                "configuration_required": "Configuração necessária",
            }.get(account_state, "Conectada" if connected else "Não conectada")
            account_control = (
                ft.FilledButton("Entrar com Google", on_click=lambda _: start_task(on_login))
                if not connected else
                ft.OutlinedButton("Sair", on_click=lambda _: start_task(on_logout))
            )
            if should_materialize_section("Conta"):
                items.append(section("Conta", ft.Icons.PERSON_OUTLINE, [
                ft.Row([
                    ft.Icon(ft.Icons.ACCOUNT_CIRCLE_OUTLINED, size=42),
                    ft.Column([
                        ft.Text(account_text, color=TEXT, weight=ft.FontWeight.BOLD),
                        ft.Text(account_status, color=TEXT_MUTED, size=11),
                    ], expand=True),
                    account_control,
                ]),
            ], ("google", "login", "conta")))

            if should_materialize_section("Geral"):
                items.append(section("Geral", ft.Icons.SETTINGS_OUTLINED, [
                row("app.confirm_destructive", "Confirmar ações destrutivas", "Pede confirmação antes de ações como limpar cache e restaurar configurações."),
            ], ("geral", "confirmação", "animações")))

            def choose_theme(mode):
                def handle(_event):
                    if save("appearance.theme", mode):
                        label = {"system": "Sistema", "light": "Claro", "dark": "Escuro"}[mode]
                        notice(f"Tema: {label}.")
                selected = settings.get("appearance.theme") == mode
                label = {"system": "Sistema", "light": "Claro", "dark": "Escuro"}[mode]
                button_label = f"✓ {label}" if selected else label
                button_cls = ft.FilledButton if selected else ft.OutlinedButton
                return button_cls(button_label, on_click=handle)

            selected_theme = settings.get("appearance.theme")
            selected_theme_label = {"system": "Sistema", "light": "Claro", "dark": "Escuro"}[selected_theme]
            theme_choices = ft.Row(
                [choose_theme("system"), choose_theme("light"), choose_theme("dark")],
                wrap=True,
                spacing=8,
            )
            if should_materialize_section("Aparência"):
                items.append(section("Aparência", ft.Icons.DARK_MODE_OUTLINED, [
                ft.Row([
                    ft.Column([
                        ft.Text("Tema", color=TEXT, weight=ft.FontWeight.BOLD),
                        ft.Text(
                            f"Atual: {selected_theme_label}. "
                            "A alteração é aplicada imediatamente sem tocar na biblioteca, scanner ou player.",
                            color=TEXT_MUTED,
                            size=10,
                        ),
                    ], spacing=2, expand=True),
                    theme_choices,
                ], vertical_alignment=ft.CrossAxisAlignment.CENTER),
            ], ("aparência", "tema", "dark", "light", "system", "sistema", "claro", "escuro")))

            if should_materialize_section("Biblioteca"):
                items.append(section("Biblioteca", ft.Icons.VIDEO_LIBRARY_OUTLINED, [
                row("appearance.card_size", "Tamanho dos cards", "Controla o tamanho visual dos cards da Home."),
                row("appearance.show_thumbnails", "Mostrar miniaturas", "Quando desativado, a Home mantém o espaço do card mas não carrega imagens."),
                row("library.sort_default", "Ordenação padrão", "Define a ordenação inicial da Home quando não há outra ordenação salva na tela.", "enum",
                    ("added_desc", "title_asc", "title_desc", "recently_watched"),
                    {"added_desc":"Mais recentes","title_asc":"Nome A-Z","title_desc":"Nome Z-A","recently_watched":"Assistidos recentemente"}),
                row("library.grid_density", "Densidade da grade", "Controla a largura efetiva dos cards na Home.", "enum",
                    ("small", "medium", "large"), {"small":"Menos cards","medium":"Equilibrada","large":"Mais cards"}),
                row("library.page_size", "Itens por página", "Controla o tamanho da paginação do catálogo, sem criar consultas paralelas.", "enum",
                    (24, 36, 48, 72), {24:"24",36:"36",48:"48",72:"72"}),
                row("library.continue_watching", "Continue Watching", "Controla a preferência global para a seção quando consumida pela Home."),
                row("library.continue_watching_limit", "Limite de Continue Watching", "Limite persistido para a seção.", "enum", (5, 10, 15, 20), {5:"5",10:"10",15:"15",20:"20"}),
            ], ("biblioteca", "home", "continue watching", "grid", "organize", "cards", "ordenação", "paginação")))

            if should_materialize_section("Player"):
                player = [
                    row("player.autoplay_next", "Autoplay do próximo episódio", "Permite avanço automático no player local."),
                    row("player.resume", "Continuar reprodução", "Usa a posição de progresso já salva; desligar não apaga o progresso."),
                    row("player.default_speed", "Velocidade padrão", "Aplicada quando um episódio é aberto.", "enum", (0.5,0.75,1.0,1.25,1.5,1.75,2.0), {x:f"{x:.2f}x" for x in (0.5,0.75,1.0,1.25,1.5,1.75,2.0)}),
                    row("player.aspect_ratio", "Modo de vídeo", "Ajustar preserva toda a imagem; Preencher ocupa a tela cortando somente o excedente, sem esticar o vídeo.", "enum",
                        ("fit","fill"), {"fit":"Ajustar","fill":"Preencher"}),
                    row("player.zoom_enabled", "Zoom por gesto", "Permitir ampliar e mover o vídeo com gesto de pinça."),
                    row("player.double_tap_seek_seconds", "Salto no double tap", "Define quantos segundos são avançados/retrocedidos pelo double tap.", "enum", (5,10,15,30), {5:"5s",10:"10s",15:"15s",30:"30s"}),
                    row("player.long_press_speed", "Velocidade da pressão longa", "Velocidade temporária aplicada enquanto a pressão longa estiver ativa.", "enum", (1.5,1.75,2.0), {1.5:"1,50x",1.75:"1,75x",2.0:"2,00x"}),
                    row("player.max_video_resolution", "Resolução máxima", "Limita a faixa de vídeo selecionada pelo Media3 quando o arquivo oferece múltiplas tracks.", "enum",
                        ("auto","480p","720p","1080p","1440p","2160p"), {"auto":"Automática","480p":"480p","720p":"720p","1080p":"1080p","1440p":"1440p","2160p":"2160p"}),
                    row("player.max_video_frame_rate", "FPS máximo", "Limita a taxa de frames da track de vídeo selecionada.", "enum",
                        (0,24,30,60), {0:"Automático",24:"24 fps",30:"30 fps",60:"60 fps"}),
                    row("player.max_audio_channels", "Canais de áudio máximos", "Limita a seleção de áudio sem criar um mixer ou decoder alternativo.", "enum",
                        (0,2,6,8), {0:"Automático",2:"2",6:"5.1 / 6",8:"7.1 / 8"}),
                    row("player.immersive", "Modo imersivo", "Controla as barras do sistema somente no player.", "enum", ("always","landscape","never"), {"always":"Sempre","landscape":"Somente landscape","never":"Nunca"}),
                    row("player.rotation", "Rotação", "Orientação do player, sem forçar o aplicativo inteiro.", "enum", ("auto","portrait","landscape"), {"auto":"Automática","portrait":"Portrait","landscape":"Landscape"}),
                    row("player.pip", "Picture-in-Picture", "Permite PiP quando suportado."),
                    row("player.auto_hide_seconds", "Auto-hide dos controles", "0 significa nunca.", "enum", (5,10,15,30,0), {5:"5s",10:"10s",15:"15s",30:"30s",0:"Nunca"}),
                    action_row("Restaurar Player", "Volta somente as preferências do Player aos defaults.", "Restaurar", reset_player),
                ]
                items.append(section("Player", ft.Icons.PLAY_CIRCLE_OUTLINE, player, ("player","autoplay","resume","velocidade","aspect","zoom","immersive","pip","rotation")))

            if should_materialize_section("Gestos"):
                items.append(section("Gestos", ft.Icons.TOUCH_APP_OUTLINED, [
                row("gestures.volume", "Gestos de volume", "Swipe vertical no lado direito ajusta o volume quando ativado."),
                row("gestures.brightness", "Gestos de brilho", "Swipe vertical no lado esquerdo ajusta o brilho quando ativado."),
                row("gestures.double_tap", "Double tap para seek", "Controla o double tap existente."),
                row("gestures.long_press", "Pressão longa", "Controla a ação de long press existente."),
            ], ("gestos","volume","brilho","double tap","long press","swipe")))

            if should_materialize_section("Áudio e Legendas"):
                items.append(section("Áudio e Legendas", ft.Icons.HEADPHONES_OUTLINED, [
                row("audio.subtitle_scale", "Escala da legenda", "Aplica o tamanho relativo usando o SubtitleView do Media3.", "enum",
                    (0.75,1.0,1.25,1.5), {0.75:"75%",1.0:"100%",1.25:"125%",1.5:"150%"}),
                row("audio.subtitle_bottom_padding", "Margem inferior da legenda", "Controla a margem inferior quando a cue não especifica uma linha fixa.", "enum",
                    (4,8,12,16), {4:"4%",8:"8% (padrão)",12:"12%",16:"16%"}),
                row("audio.subtitle_embedded_style", "Estilo embutido da legenda", "Permite que o estilo declarado pela própria faixa seja aplicado.", "bool"),
                language_row(
                    "audio.preferred_language",
                    "Idioma de áudio",
                    "Use uma tag BCP-47 como pt-BR, en ou ja. Se não existir no arquivo, o Media3 usa fallback seguro.",
                ),
                language_row(
                    "audio.preferred_subtitle_language",
                    "Idioma da legenda",
                    "Use uma tag BCP-47. A seleção ocorre somente entre tracks existentes no arquivo.",
                ),
                row(
                    "audio.subtitles",
                    "Legendas",
                    "Automático respeita as preferências do arquivo; Sempre tenta selecionar uma legenda; Nunca desativa a track de texto.",
                    "enum",
                    ("auto", "always", "never"),
                    {"auto": "Automático", "always": "Sempre", "never": "Nunca"},
                ),
                ft.Text("Delay global de legenda: NÃO IMPLEMENTADO. Media3 1.11.1 não expõe uma preferência persistente de offset nessa camada; nenhuma configuração falsa é exibida.", color=TEXT_MUTED, size=10),
            ], ("áudio","legenda","subtitle","audio","pt-br","en","ja")))

            if should_materialize_section("Metadata"):
                items.append(section("Metadata", ft.Icons.MANAGE_SEARCH_OUTLINED, [
                row("metadata.anilist_enabled", "Usar AniList", "Permite ou bloqueia chamadas remotas do cliente AniList. O catálogo local continua disponível sem rede."),
                row("metadata.auto_match", "Auto-match AniList", "Quando desativado, a biblioteca não dispara novas buscas automáticas; associações já existentes continuam sendo usadas."),
                ft.Text("Alterar Settings não dispara sincronização em massa.", color=TEXT_MUTED, size=10),
            ], ("metadata","anilist","matching","offline","rede")))

            if should_materialize_section("Artwork"):
                items.append(section("Artwork", ft.Icons.IMAGE_OUTLINED, [
                row("artwork.enabled", "Artwork remoto", "Permite que o Artwork Engine faça download de capas remotas. Artwork local e manual continuam utilizáveis."),
                row("artwork.cache_limit_mb", "Limite do cache de artwork", "Limite aplicado ao único Artwork Engine existente.", "enum",
                    (64,128,256,512), {64:"64 MB",128:"128 MB",256:"256 MB",512:"512 MB"}),
                ft.Text("O Artwork Engine existente continua sendo a única fonte do cache de artwork.", color=TEXT_MUTED, size=11),
                action_row("Limpar cache AniList", "Remove somente o cache temporário administrado pelo catálogo.", "Limpar", clear_cache),
            ], ("artwork","cache","thumbnail","offline","limite","poster")))

            if should_materialize_section("Armazenamento"):
                folder_lines = []
                folders = get_folders()
                for folder in folders:
                    name = folder.get("name") or folder.get("path") or "Pasta"
                    path = str(folder.get("path") or "")
                    def remove_folder(_event, ref=path, display_name=name):
                        confirm(
                            "Remover pasta da biblioteca?",
                            f'"{display_name}" será removida somente da configuração da biblioteca. Nenhum arquivo físico será apagado.',
                            "Remover",
                            lambda: on_remove_folder(ref),
                        )
                    folder_lines.append(
                        ft.Row([
                            ft.Text(f"• {name}", color=TEXT_MUTED, size=11, expand=True),
                            ft.TextButton("Remover", on_click=remove_folder),
                        ])
                    )
                media_label = {"full":"Permitida","partial":"Parcial","denied":"Negada"}.get(media_state, "Desconhecida")
                broad_label = "Disponível" if broad_state == "available" else "Indisponível"
            if should_materialize_section("Armazenamento"):
                items.append(section("Armazenamento", ft.Icons.STORAGE_OUTLINED, [
                ft.Text(f"Permissão de vídeos: {media_label}", color=TEXT, size=12),
                ft.Text(f"Acesso amplo: {broad_label} • SAF autorizadas: {len(saf_roots)} • volumes ativos: {len(volumes)}", color=TEXT_MUTED, size=11),
                ft.Column(folder_lines or [ft.Text("Nenhuma pasta indexada.", color=TEXT_MUTED, size=11)], spacing=2),
                ft.Row([
                    ft.OutlinedButton("Adicionar pasta", icon=ft.Icons.CREATE_NEW_FOLDER, on_click=add_folder),
                    ft.OutlinedButton("Atualizar biblioteca", icon=ft.Icons.REFRESH, on_click=refresh),
                ], wrap=True),
                ft.Row([
                    ft.OutlinedButton("Verificar permissão de vídeos", on_click=verify_permission),
                    ft.OutlinedButton("Solicitar permissão de vídeos", on_click=request_permission),
                    ft.OutlinedButton("Armazenamento amplo", on_click=broad),
                ], wrap=True),
            ], ("storage","armazenamento","permission","saf","mediastore","scan")))

            if should_materialize_section("Dados e Cache"):
                items.append(section("Dados e Cache", ft.Icons.CACHED_OUTLINED, [
                ft.Text(f"{get_summary()['folders']} pasta(s) • {get_summary()['animes']} anime(s) • {get_summary()['episodes']} episódio(s)", color=TEXT, size=12),
                ft.Text("Limpar cache não remove catálogo, consumo, favoritos, tags, notas, pins, IDs AniList ou arquivos.", color=TEXT_MUTED, size=10),
                ft.Row([
                    ft.OutlinedButton("Exportar configurações", icon=ft.Icons.UPLOAD_FILE, on_click=lambda e: start_task(export_settings, e)),
                    ft.OutlinedButton("Importar configurações", icon=ft.Icons.DOWNLOAD, on_click=lambda e: start_task(import_settings, e)),
                ], wrap=True, spacing=8),
                action_row("Restaurar configurações", "Reseta somente Settings; não é backup/restore completo.", "Restaurar", reset_all),
            ], ("dados","cache","reset","exportar","importar")))

            if should_materialize_section("Backup e Restauração"):
                items.append(section("Backup e Restauração", ft.Icons.SECURITY_OUTLINED, [
                ft.Text(
                    "Backup v1 guarda o estado lógico do SQLite, preferências suportadas e referências de mídia. "
                    "Vídeos, autenticação, tokens, credenciais e identificadores do dispositivo não entram no arquivo.",
                    color=TEXT, size=11,
                ),
                ft.Row([
                    ft.OutlinedButton("Fazer backup", icon=ft.Icons.BACKUP_OUTLINED, on_click=lambda e: start_task(create_backup_file, e)),
                    ft.OutlinedButton("Restaurar backup", icon=ft.Icons.RESTORE_OUTLINED, on_click=lambda e: start_task(restore_backup_file, e)),
                    ft.OutlinedButton("Verificar integridade", icon=ft.Icons.VERIFIED_OUTLINED, on_click=lambda e: start_task(verify_integrity, e)),
                    ft.OutlinedButton("Reconciliar arquivos", icon=ft.Icons.REFRESH, on_click=lambda e: start_task(reconcile_after_restore, e)),
                ], wrap=True, spacing=8),
                ft.Text(
                    "Restore: valida formato, schema, SHA-256, tabelas, referências e foreign keys antes de alterar o banco. "
                    "Missing file nunca apaga a entidade lógica. Restore não inicia full scan; a reconciliação posterior usa o ScanCoordinator existente.",
                    color=TEXT_MUTED, size=10,
                ),
                ft.Text(
                    "Migração: registry de formato v1; o repositório atual só possui o schema SQLite 29/backup v1. "
                    "Versões incompatíveis são rejeitadas explicitamente, sem inventar migrações inexistentes.",
                    color=TEXT_MUTED, size=10,
                ),
            ], ("backup","restore","migração","integridade","checksum","recovery","offline")))

            if should_materialize_section("Privacidade"):
                items.append(section("Privacidade", ft.Icons.PRIVACY_TIP_OUTLINED, [
                ft.Text("Biblioteca, histórico e caminhos locais permanecem locais.", color=TEXT, size=12),
                ft.Text("Não há analytics, tracking ou upload da biblioteca. Google Login não é requisito para reprodução local.", color=TEXT_MUTED, size=10),
            ], ("privacidade","local","offline","google")))

            if should_materialize_section("Varredura"):

                items.append(section("Varredura", ft.Icons.REFRESH_OUTLINED, [
                ft.Text("Varredura em andamento:" if running_scan else "Nenhuma varredura em andamento.", color=TEXT if running_scan else TEXT_MUTED, size=11),
                ft.Text(f"Status: {runtime_status}", color=TEXT_MUTED, size=11),
                ft.Text(f"Última varredura: {(get_last_scan() or {}).get("status") or "nenhuma"}", color=TEXT_MUTED, size=10),
            ], ("scan", "varredura", "status", "biblioteca")))

            if should_materialize_section("Diagnóstico"):
                database_ok, database_detail = get_database_check()
                database_label = "OK" if database_ok else f"ERRO ({database_detail})"
                items.append(section("Diagnóstico", ft.Icons.BUG_REPORT_OUTLINED, [
                ft.Text(f"Database: {database_label} • Schema SQLite: {getattr(store, 'SCHEMA_VERSION', '—')}", color=TEXT if database_ok else theme.error, size=11),
                ft.Text(f"Scan: {scan.get('state') or 'IDLE'} • encontrados: {int(scan.get('found') or 0)} • arquivos: {int(scan.get('files') or 0)}", color=TEXT_MUTED, size=11),
                ft.Text(f"Volumes removíveis: {len(volumes)} • SAF: {len(saf_roots)}", color=TEXT_MUTED, size=11),
                ft.Text("Python/Flet: Flet 0.86.5 • Android target 36", color=TEXT_MUTED, size=11),
                ft.Text("Player, scanner e storage mantêm logs técnicos separados da mensagem exibida ao usuário.", color=TEXT_MUTED, size=10),
                action_row("Exportar diagnóstico", "Gera informações técnicas sem misturar restore de biblioteca ou configurações.", "Exportar", export_diagnostic),
            ], ("diagnóstico","logs","database","index","player","android","exportar","técnico")))

            if should_materialize_section("Sobre"):
                items.append(section("Sobre", ft.Icons.INFO_OUTLINE, [
                ft.Text("ReiAnix Local", color=TEXT, size=14, weight=ft.FontWeight.BOLD),
                ft.Text("Versão real do projeto: 0.2.1 • Flet 0.86.5", color=TEXT_MUTED, size=11),
                ft.Text("Licença do projeto: não declarada no repositório atual.", color=TEXT_MUTED, size=11),
                ft.Text("Player nativo: Media3. Storage: MediaStore / SAF / scanner nativo.", color=TEXT_MUTED, size=11),
            ], ("sobre","versão","build","licença","media3")))
            performance.event("settings.build_sections", duration_ms=(performance.now()-sections_started)*1000.0,
                              screen="settings",
                              metadata={"sections": len(items), "categories": len(category_meta),
                                        "active_category": active_category, "materialize_all": materialize_all})
            return items

        rebuild()
        result = ft.Container(
            content=ft.Column([
                ft.Row([back_button, header_title]),
                search,
                sections_host,
                status,
            ], spacing=10, expand=True),
            padding=PAGE_PADDING,
            bgcolor=BACKGROUND,
            expand=True,
        )
        performance.record_ui_build("settings", (performance.now()-build_started)*1000.0,
                                    controls=performance.control_count(result),
                                    sections=len(section_cache), cached=False)
        return result
