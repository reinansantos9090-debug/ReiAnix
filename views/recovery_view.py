"""Minimal explicit recovery UI for an inconsistent ReiAnix database."""
from __future__ import annotations

import json
import logging
import flet as ft

from core.ui import BACKGROUND, PAGE_PADDING, TEXT, TEXT_MUTED, activate_theme_for_page

logger = logging.getLogger("reiflix.recovery")


class RecoveryView:
    @staticmethod
    def build(page, status, *, on_diagnostic, on_snapshot, on_restore):
        theme = activate_theme_for_page(page)
        BACKGROUND = theme.background
        TEXT = theme.text
        TEXT_MUTED = theme.text_muted
        message = ft.Text("", color=TEXT_MUTED, size=12)
        operation_busy = {"value": False}
        details = ft.Text(
            "O banco local apresenta uma inconsistência. O ReiAnix não iniciou a biblioteca "
            "normalmente e não apagará o banco automaticamente.",
            color=TEXT_MUTED,
            size=12,
        )

        def notice(value, error=False):
            message.value = value
            message.color = theme.error if error else TEXT_MUTED
            try:
                page.update()
            except Exception:
                logger.debug("recovery UI update skipped", exc_info=True)

        async def diagnostic(_):
            try:
                raw = await on_diagnostic()
                stamp = __import__("time").strftime("%Y%m%d-%H%M%S")
                path = await ft.FilePicker().save_file(
                    dialog_title="Exportar diagnóstico de recuperação",
                    file_name=f"reiflix-recovery-{stamp}.json",
                    file_type=ft.FilePickerFileType.CUSTOM,
                    allowed_extensions=["json"],
                    src_bytes=raw,
                )
                notice("Diagnóstico exportado." if path else "Exportação cancelada.")
            except Exception:
                logger.exception("recovery diagnostic failed")
                notice("Não foi possível exportar o diagnóstico.", True)

        async def snapshot(_):
            if operation_busy["value"]:
                notice("Outra operação de Recovery já está em execução.", True)
                return
            operation_busy["value"] = True
            try:
                notice("Criando snapshot de segurança…")
                page.update()
                path = await on_snapshot()
                notice(f"Snapshot de segurança criado: {path}")
            except Exception as exc:
                logger.exception("recovery snapshot failed")
                notice(str(exc), True)
            finally:
                operation_busy["value"] = False
                try:
                    page.update()
                except Exception:
                    logger.debug("recovery UI update skipped", exc_info=True)

        async def restore(_):
            if operation_busy["value"]:
                notice("Outra operação de Recovery já está em execução.", True)
                return
            operation_busy["value"] = True
            try:
                notice("Selecione o backup para validar…")
                page.update()
                files = await ft.FilePicker().pick_files(
                    dialog_title="Selecionar backup ReiAnix",
                    allow_multiple=False,
                    with_data=True,
                    file_type=ft.FilePickerFileType.CUSTOM,
                    allowed_extensions=["zip"],
                )
                if not files:
                    operation_busy["value"] = False
                    notice("Restore cancelado.")
                    return
                raw = files[0].bytes or b""
                preview = await on_restore(raw, preview_only=True)
                counts = preview.get("counts") or {}

                async def confirm(_event):
                    page.pop_dialog()
                    try:
                        notice("Validando novamente e aplicando restauração…")
                        result = await on_restore(raw, preview_only=False)
                        if result.get("reauthentication_required"):
                            notice(
                                "Restore concluído. O banco foi recuperado, mas a sessão atual não pôde ser preservada; "
                                "faça login novamente quando necessário."
                            )
                        else:
                            notice(
                                "Restore concluído com segurança. Feche e abra o ReiAnix para "
                                "carregar o banco restaurado."
                            )
                    except Exception as exc:
                        logger.exception("recovery restore failed")
                        notice(str(exc), True)
                    finally:
                        operation_busy["value"] = False
                        try:
                            page.update()
                        except Exception:
                            logger.debug("recovery UI update skipped", exc_info=True)

                def cancel_restore(_event):
                    page.pop_dialog()
                    operation_busy["value"] = False
                    notice("Restore cancelado.")
                    try:
                        page.update()
                    except Exception:
                        logger.debug("recovery UI update skipped", exc_info=True)

                page.show_dialog(ft.AlertDialog(
                    modal=True,
                    title=ft.Text("Restaurar backup em Recovery Mode?"),
                    content=ft.Text(
                        f"Backup v{preview.get('format_version')} • schema {preview.get('schema_version')}\n"
                        f"Animes: {counts.get('anime', 0)} • episódios: {counts.get('episodes', 0)}\n"
                        "Será preservado um snapshot do banco atual antes da substituição. "
                        "Autenticação atual será preservada quando puder ser lida; se o SQLite estiver corrompido, "
                        "o catálogo ainda poderá ser restaurado e o login poderá precisar ser refeito.",
                        color=TEXT_MUTED,
                        size=11,
                    ),
                    actions=[
                        ft.TextButton("Cancelar", on_click=cancel_restore),
                        ft.FilledButton("Restaurar", on_click=confirm),
                    ],
                ))
                page.update()
            except Exception:
                logger.exception("recovery restore preview failed")
                notice("O backup é inválido, incompatível ou não pôde ser validado.", True)
                operation_busy["value"] = False
            finally:
                if not operation_busy["value"]:
                    return
                # Keep busy until the confirmation action finishes. This blocks
                # duplicate taps while the validation dialog is open.
                try:
                    page.update()
                except Exception:
                    logger.debug("recovery UI update skipped", exc_info=True)

        db = status
        status_lines = [
            f"SQLite quick_check: {db.get('quick_check') or 'indisponível'}",
            f"Foreign keys: {'OK' if db.get('foreign_key_ok') else 'ERRO/indisponível'}",
            f"Tamanho: {int(db.get('size_bytes') or 0)} bytes",
        ]
        return ft.Container(
            bgcolor=BACKGROUND,
            padding=PAGE_PADDING,
            expand=True,
            content=ft.Column([
                ft.Text("Recovery Mode", size=24, weight=ft.FontWeight.BOLD, color=TEXT),
                details,
                ft.Column([ft.Text(line, color=TEXT_MUTED, size=11) for line in status_lines]),
                ft.Row([
                    ft.FilledButton("Exportar diagnóstico", icon=ft.Icons.BUG_REPORT_OUTLINED, on_click=diagnostic),
                    ft.OutlinedButton("Criar snapshot de segurança", icon=ft.Icons.BACKUP_OUTLINED, on_click=snapshot),
                    ft.FilledButton("Restaurar backup", icon=ft.Icons.RESTORE_OUTLINED, on_click=restore),
                ], wrap=True, spacing=8),
                message,
            ], spacing=14, scroll=ft.ScrollMode.AUTO),
        )
