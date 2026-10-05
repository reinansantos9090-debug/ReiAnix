import os
import time
import asyncio
import logging
import json
import math
import datetime
import uuid
import flet as ft
from flet.auth import OAuthProvider
from app_config import GOOGLE_CLIENT_ID as CONFIG_GOOGLE_CLIENT_ID, GOOGLE_REDIRECT_URL as CONFIG_GOOGLE_REDIRECT_URL, GOOGLE_WEB_CLIENT_ID as CONFIG_GOOGLE_WEB_CLIENT_ID
from core.android_bridge import AndroidBridge
from core.compose_library_bridge import ComposeLibraryBridge
from core.compose_settings_bridge import ComposeSettingsBridge
from core.navigation import NavigationController, SafSelectionState
from core.scan_coordinator import ScanCoordinator, ScanOrigin, ScanState, ScanTarget
from core.storage_access import (
    StorageAccessState,
    StorageCapabilities,
    ScanUiState,
    scan_ui_state_from_native,
    storage_access_state,
    storage_source_states,
    dedupe_saf_roots,
    saf_source_identity,
        configured_library_saf_roots,
)
from core.diagnostics import DiagnosticTimeline
from core.performance import get_performance_monitor
from core.settings_focus import SettingsTaskRegistry
from core.build_identity import as_dict as build_identity
from core.diagnostic_service import DiagnosticsService
from core.backup import BackupError, BackupService
from core.library_store import LibraryStore
from core.library_service import LibraryService
from core.library_discovery import format_duration
from core.settings import SettingsStore
from core.ui import apply_page_theme
from core.recovery import RecoveryService
from views.recovery_view import RecoveryView
from core.google_account import normalize_google_profile
from views.home_view import HomeView
from views.details_view import DetailView
from views.organize_view import OrganizeView
from views.settings_view import SettingsView
from views.collector_view import CollectorView

logger = logging.getLogger("reiflix")

GOOGLE_CLIENT_ID = os.getenv('REIFLIX_GOOGLE_CLIENT_ID', CONFIG_GOOGLE_CLIENT_ID)
GOOGLE_REDIRECT_URL = os.getenv('REIFLIX_GOOGLE_REDIRECT_URL', CONFIG_GOOGLE_REDIRECT_URL)
GOOGLE_WEB_CLIENT_ID = os.getenv('REIFLIX_GOOGLE_WEB_CLIENT_ID', CONFIG_GOOGLE_WEB_CLIENT_ID)

async def main(page: ft.Page):
    performance = get_performance_monitor()
    startup_started = performance.now()
    performance.counter("startup.python_main")
    page.title='ReiAnix Local'; page.padding=0
    apply_page_theme(page, "dark")
    data_dir=os.getenv("FLET_APP_STORAGE_DATA") or os.path.join(os.path.dirname(__file__),'.reiflix-data')
    store_started = performance.now()
    store=LibraryStore(data_dir)
    performance.event("startup.library_store", duration_ms=(performance.now()-store_started)*1000.0,
                      metadata={"schema_version": getattr(store, "SCHEMA_VERSION", None)})
    recovery_service = RecoveryService(store)
    recovery_status = recovery_service.diagnose()
    if store.recovery_error or recovery_status.get("required"):
        logger.error("[RECOVERY] database inconsistency detected: %s", store.recovery_error or recovery_status.get("error") or recovery_status.get("quick_check"))
        async def recovery_diagnostic():
            return await asyncio.to_thread(recovery_service.diagnostic_bytes)
        async def recovery_snapshot():
            return await asyncio.to_thread(recovery_service.create_safety_snapshot)
        async def recovery_restore(raw, *, preview_only=False):
            if preview_only:
                return await asyncio.to_thread(BackupService(store).inspect_bytes, raw)
            return await asyncio.to_thread(recovery_service.restore_backup, raw)
        page.views.clear()
        page.views.append(
            ft.View(
                route="/recovery",
                controls=[RecoveryView.build(
                    page,
                    recovery_status,
                    on_diagnostic=recovery_diagnostic,
                    on_snapshot=recovery_snapshot,
                    on_restore=recovery_restore,
                )],
                padding=0,
            )
        )
        page.update()
        return
    settings_started = performance.now()
    settings=SettingsStore(store)
    performance.event("startup.settings_store", duration_ms=(performance.now()-settings_started)*1000.0)
    apply_page_theme(page, settings.get("appearance.theme"))
    recovered_scans=store.interrupted_scans()
    library=LibraryService(store, settings=settings)
    bridge_started = performance.now()
    bridge=AndroidBridge(data_dir, page)
    performance.event("startup.android_bridge", duration_ms=(performance.now()-bridge_started)*1000.0)
    compose_library_bridge = ComposeLibraryBridge(
        data_dir,
        library,
        store,
        enabled=bridge.available,
    )
    compose_settings_bridge = ComposeSettingsBridge(
        data_dir,
        settings,
        account_provider=lambda: store.account(),
        account_state_provider=lambda: account_state[0],
        storage_available=bridge.available,
        enabled=bridge.available,
    )
    current=[None]
    account_state=["connected" if store.account().get("email") else "disconnected"]

    def set_account_state(state, reason="account_state_changed"):
        normalized = str(state or "disconnected").strip().lower()
        account_state[0] = normalized
        if compose_settings_bridge.enabled:
            compose_settings_bridge.request_publish(reason)
    if compose_library_bridge.enabled:
        compose_library_bridge.request_publish("startup")
    if compose_settings_bridge.enabled:
        compose_settings_bridge.request_publish("startup")
    diagnostics = DiagnosticTimeline()
    backup_service = BackupService(store, settings=settings, app_version="0.2.1")
    diagnostic_service = DiagnosticsService(store, timeline=diagnostics, app_version="0.2.1")
    diagnostics.record("APP_START", result="python_ui_initialized")
    logger.info("[BUILD] identity=%s", build_identity())
    diagnostics.record("BUILD_IDENTITY", result=json.dumps(build_identity(), sort_keys=True))
    scan_state = [{
        "state": ScanUiState.IDLE.value,
        "source": None,
        "volume": None,
        "scanId": None,
        "found": 0,
        "files": 0,
        "directories": 0,
        "error": None,
        "timestamp": None,
    }]
    compose_library_bridge.set_scan_state_provider(lambda: scan_state[0])
    compose_library_bridge.request_publish("startup_scan_state")
    ui_alive = [True]
    native_poll_task = [None]
    account_action_task = [None]
    player_transition_task = {"task": None}
    settings_tasks = SettingsTaskRegistry()
    home_refresh_context = {
        "active": False,
        "state": "IDLE",
        "phase": "IDLE",
        "refresh_id": None,
        "request_id": None,
        "started_at": None,
        "scan_started": False,
        "scan_started_at": None,
        "scan_terminal": False,
        "db_updated": False,
        "source": None,
    }
    HOME_REFRESH_PHASES = {
        "IDLE",
        "REQUESTED",
        "RUNNING",
        "CATALOG_UPDATING",
        "UI_COMMIT",
        "SUCCESS",
        "ERROR",
        "CANCELLED",
    }

    def _set_home_refresh_phase(phase, *, request_id=None, reason=None):
        normalized = str(phase or "IDLE").upper()
        if normalized not in HOME_REFRESH_PHASES:
            raise ValueError(f"invalid Home refresh phase: {phase!r}")
        previous = home_refresh_context.get("phase") or "IDLE"
        home_refresh_context["phase"] = normalized
        if previous != normalized:
            diagnostics.record(
                "HOME_REFRESH_PHASE_CHANGED",
                request_id=request_id if request_id is not None else home_refresh_context.get("request_id"),
                source=reason or None,
                result=normalized,
                refresh_id=home_refresh_context.get("refresh_id"),
                previous_phase=previous,
                phase=normalized,
                transition_reason=reason or None,
            )

    def _handle_page_disconnect(_event=None):
        nonlocal thumbnail_dispatch_task, thumbnail_reconciliation_task, thumbnail_reconciliation_pending
        ui_alive[0] = False
        task = native_poll_task[0]
        if task is not None:
            try:
                task.cancel()
            except Exception as exc:
                logger.debug("[FLET] mailbox poll task cancellation failed: %s", exc)
        account_task = account_action_task[0]
        if account_task is not None and not account_task.done():
            try:
                account_task.cancel()
            except Exception as exc:
                logger.debug("[ACCOUNT] account action task cancellation failed: %s", exc)
        account_action_task[0] = None
        transition_task = player_transition_task.get("task")
        if transition_task is not None and not transition_task.done():
            try:
                transition_task.cancel()
            except Exception as exc:
                logger.debug("[FLET] player transition task cancellation failed: %s", exc)
        player_transition_task["task"] = None
        for task in (thumbnail_dispatch_task, thumbnail_reconciliation_task):
            if task is not None:
                try:
                    task.cancel()
                except Exception as exc:
                    logger.debug("[FLET] thumbnail background task cancellation failed: %s", exc)
        thumbnail_dispatch_task = None
        thumbnail_reconciliation_task = None
        thumbnail_reconciliation_pending = False
        home_refresh_context["active"] = False
        home_refresh_context["db_updated"] = False
        home_refresh_context["request_id"] = None
        settings_tasks.invalidate()

    try:
        page.on_disconnect = _handle_page_disconnect
    except Exception as exc:
        logger.warning("[FLET] on_disconnect hook unavailable: %s", exc)

    def safe_update():
        if not ui_alive[0]:
            return
        try:
            page.update()
        except Exception as exc:
            logger.debug("[FLET] safe_update ignored stale lifecycle callback: %s", exc)
    def set_scan_state(state, *, source=None, volume=None, scan_id=None, found=None,
                       files=None, directories=None, error=None, timestamp=None):
        current = scan_state[0]
        incoming = str(state.value if isinstance(state, ScanUiState) else state)
        terminal_states = {
            ScanUiState.COMPLETED.value,
            ScanUiState.CANCELLED.value,
            ScanUiState.FAILED.value,
            ScanUiState.PARTIAL.value,
        }
        # Ignore a late progress callback from the same native operation after
        # a terminal result has already reached Python.
        if (
            str(current.get("state") or "") in terminal_states
            and incoming in {ScanUiState.SCANNING.value, ScanUiState.CHECKING.value}
            and scan_id is not None
            and str(scan_id) == str(current.get("scanId") or "")
        ):
            logger.warning(
                "[SCAN] stale progress ignored scan_id=%s state=%s current=%s",
                scan_id, incoming, current.get("state"),
            )
            return
        scan_state[0] = {
            "state": str(state.value if isinstance(state, ScanUiState) else state),
            "source": source if source is not None else current.get("source"),
            "volume": volume if volume is not None else current.get("volume"),
            "scanId": scan_id if scan_id is not None else current.get("scanId"),
            "found": int(found if found is not None else current.get("found") or 0),
            "files": int(files if files is not None else current.get("files") or 0),
            "directories": int(directories if directories is not None else current.get("directories") or 0),
            "error": error,
            "timestamp": timestamp or current.get("timestamp"),
        }

    def _scan_coordinator_state_changed(snapshot):
        state = snapshot.state
        ui_state = {
            ScanState.QUEUED: ScanUiState.SCANNING,
            ScanState.RUNNING: ScanUiState.SCANNING,
            ScanState.CANCELLING: ScanUiState.SCANNING,
            ScanState.COMPLETED: ScanUiState.COMPLETED,
            ScanState.CANCELLED: ScanUiState.CANCELLED,
            ScanState.FAILED: ScanUiState.FAILED,
            ScanState.PARTIAL: ScanUiState.PARTIAL,
            ScanState.BLOCKED: ScanUiState.IDLE,
            ScanState.IDLE: ScanUiState.IDLE,
        }.get(state, ScanUiState.IDLE)
        set_scan_state(
            ui_state,
            source=snapshot.source,
            scan_id=snapshot.request_id,
            error=snapshot.last_result if state in {ScanState.FAILED, ScanState.PARTIAL} else None,
        )
        if compose_library_bridge.enabled:
            compose_library_bridge.request_publish("scan_state_changed")
        if home_refresh_context["active"] and (
            home_refresh_context.get("request_id") is None
            or snapshot.request_id == home_refresh_context.get("request_id")
        ):
            refresh_source = home_refresh_context.get("source") or "button"
            if state in {ScanState.RUNNING, ScanState.CANCELLING} and not home_refresh_context["scan_started"]:
                home_refresh_context["scan_started"] = True
                home_refresh_context["scan_started_at"] = time.monotonic()
                _set_home_refresh_phase(
                    "RUNNING",
                    request_id=snapshot.request_id,
                    reason="scan_started",
                )
                diagnostics.record(
                    "HOME_REFRESH_STARTED",
                    refresh_id=home_refresh_context["refresh_id"],
                    request_id=snapshot.request_id,
                    source=refresh_source,
                    scan_source=snapshot.source or "all",
                )
                diagnostics.record(
                    "HOME_REFRESH_SCAN_STARTED",
                    refresh_id=home_refresh_context["refresh_id"],
                    request_id=snapshot.request_id,
                    source=refresh_source,
                    scan_source=snapshot.source or "all",
                )
                performance.counter("home.refresh.started")
            if state == ScanState.BLOCKED and not home_refresh_context["scan_terminal"]:
                home_refresh_context["scan_terminal"] = True
                _set_home_refresh_phase(
                    "ERROR",
                    request_id=snapshot.request_id,
                    reason="no_authorized_scan_source",
                )
                home_refresh_context["active"] = False
                home_refresh_context["db_updated"] = False
                home_refresh_context["request_id"] = None
                diagnostics.record(
                    "HOME_REFRESH_FAILED",
                    refresh_id=home_refresh_context["refresh_id"],
                    request_id=snapshot.request_id,
                    source=refresh_source,
                    scan_source=snapshot.source or "all",
                    transition_reason="no_authorized_scan_source",
                )
                performance.counter("home.refresh.failed")
                _publish_home_refresh_state("ERROR")
                resetter = home_state.get("_reset_refresh_state")
                if callable(resetter):
                    resetter("ERROR", 1.6)
            if state in {ScanState.COMPLETED, ScanState.PARTIAL, ScanState.FAILED, ScanState.CANCELLED} and not home_refresh_context["scan_terminal"]:
                home_refresh_context["scan_terminal"] = True
                terminal_success = state in {ScanState.COMPLETED, ScanState.PARTIAL}
                if state == ScanState.FAILED:
                    _set_home_refresh_phase(
                        "ERROR",
                        request_id=snapshot.request_id,
                        reason=f"scan_terminal:{state.value}",
                    )
                elif state == ScanState.CANCELLED:
                    _set_home_refresh_phase(
                        "CANCELLED",
                        request_id=snapshot.request_id,
                        reason=f"scan_terminal:{state.value}",
                    )
                scan_started = home_refresh_context.get("scan_started_at")
                diagnostics.record(
                    "HOME_REFRESH_SCAN_COMPLETED" if terminal_success else "HOME_REFRESH_FAILED",
                    refresh_id=home_refresh_context["refresh_id"],
                    request_id=snapshot.request_id,
                    status=state.value,
                    source=refresh_source,
                    scan_source=snapshot.source or "all",
                    duration_ms=int((time.monotonic() - scan_started) * 1000) if scan_started else None,
                )
                performance.counter("home.refresh.scan_completed" if terminal_success else "home.refresh.failed")
                if not terminal_success:
                    home_refresh_context["active"] = False
                    home_refresh_context["db_updated"] = False
                    home_refresh_context["request_id"] = None
                    _publish_home_refresh_state("ERROR")
                    resetter = home_state.get("_reset_refresh_state")
                    if callable(resetter):
                        resetter("ERROR", 1.6)
                elif navigation.current != "home":
                    # The scan may finish while Home is not mounted. Keep the
                    # logical refresh alive until its durable catalog update has
                    # been reflected when Home returns.
                    home_state["_manual_refresh_pending"] = True
        safe_update()

    # Resolve the callback and its runtime capability state before constructing
    # ScanCoordinator. Python binds function definitions as local names only when
    # execution reaches the definition; constructing the coordinator first caused
    # startup-time UnboundLocalError before the storage UI could render.
    storage_onboarding = {"dismissed": False, "dialog_open": False, "waiting_for_result": False}
    storage_capabilities = [StorageCapabilities.unknown()]
    if compose_library_bridge.enabled:
        compose_library_bridge.set_storage_state_provider(lambda: {"capabilities": storage_capabilities[0], "safSelectionPending": saf_selection.pending})
        compose_library_bridge.request_publish("storage_startup")
    if compose_settings_bridge.enabled:
        compose_settings_bridge.set_storage_state_provider(
            lambda: {
                "capabilities": storage_capabilities[0],
                "safSelectionPending": saf_selection.pending,
            }
        )
        compose_settings_bridge.request_publish("storage_startup")

    def _authorized_scan_targets(source=None, scope_ref=None):
        normalized = ScanCoordinator.normalize_source(source)
        caps = storage_capabilities[0]

        # Library discovery is deliberately scoped to persisted SAF tree
        # permissions explicitly granted by the user. MediaStore and broad
        # storage remain diagnostic capabilities, never library sources.
        if normalized not in (None, "saf"):
            logger.warning(
                "[LIBRARY_SOURCE] SCAN_SOURCE_REJECTED source=%s scope_ref=%s reason=non_library_scanner",
                normalized or "all",
                scope_ref or "-",
            )
            return []
        if not caps.known:
            logger.warning("[LIBRARY_SOURCE] SCAN_SOURCE_REJECTED reason=capabilities_unknown")
            return []

        raw_roots = tuple(caps.saf_roots)
        invalid_count = sum(
            1 for value in raw_roots
            if str(value or "").strip() and not saf_source_identity(value)
        )
        if invalid_count:
            logger.warning("[LIBRARY_SOURCE] LIBRARY_SOURCE_INVALID count=%d", invalid_count)

        configured_folders = [
            folder for folder in store.folders()
            if str(folder.get("kind") or "").casefold() == "saf"
            and str(folder.get("path") or "").strip()
        ]
        roots = configured_library_saf_roots(
            raw_roots,
            configured_folders,
            scope_ref=scope_ref,
        )
        persisted_ids = {
            identity
            for identity in (saf_source_identity(uri) for uri in raw_roots)
            if identity
        }
        for folder in configured_folders:
            reference = str(folder.get("path") or "").strip()
            identity = str(folder.get("saf_identity") or "").strip() or saf_source_identity(reference)
            if not identity:
                logger.warning("[LIBRARY_SOURCE] LIBRARY_SOURCE_INVALID reason=unparseable_configured_source")
                continue
            if identity not in persisted_ids:
                logger.warning("[LIBRARY_SOURCE] LIBRARY_SOURCE_PERMISSION_LOST identity=%s", identity)

        if not roots:
            logger.info("[LIBRARY_SOURCE] SCAN_SOURCE_REJECTED reason=no_configured_library_source")
            return []

        for uri in roots:
            logger.info(
                "[LIBRARY_SOURCE] LIBRARY_SOURCE_LOADED identity=%s",
                saf_source_identity(uri) or "invalid",
            )
        return [ScanTarget("saf", uri) for uri in roots]

    scan_coordinator = ScanCoordinator(
        bridge,
        store,
        _authorized_scan_targets,
        on_state=_scan_coordinator_state_changed,
    )
    pending_folder_removals=set()
    # View-local query/filter state survives Details/Player round-trips while
    # the catalog itself is still read afresh from SQLite on each view entry.
    home_state = {}

    def _publish_home_refresh_state(state):
        normalized = str(state or "IDLE").upper()
        home_refresh_context["state"] = normalized
        home_state["_refresh_state"] = normalized
        setter = home_state.get("_set_refresh_state")
        if callable(setter):
            try:
                setter(normalized)
            except Exception:
                logger.debug("[HOME_REFRESH] stale refresh state callback ignored", exc_info=True)

    def _fail_home_refresh(reason, *, request_id=None, source=None):
        if not home_refresh_context["active"]:
            return
        refresh_id = home_refresh_context.get("refresh_id")
        refresh_source = home_refresh_context.get("source") or "button"
        diagnostics.record(
            "HOME_REFRESH_FAILED",
            refresh_id=refresh_id,
            request_id=request_id,
            source=refresh_source,
            operation_source=source,
            transition_reason=str(reason),
        )
        performance.counter("home.refresh.failed")
        _set_home_refresh_phase("ERROR", request_id=request_id, reason=str(reason))
        home_refresh_context["active"] = False
        home_refresh_context["db_updated"] = False
        home_refresh_context["request_id"] = None
        home_state["_manual_refresh_pending"] = False
        _publish_home_refresh_state("ERROR")
        resetter = home_state.get("_reset_refresh_state")
        if callable(resetter):
            resetter("ERROR", 1.6)

    def _home_refresh_ui_updated():
        if not home_refresh_context["active"]:
            return
        if not (home_refresh_context["scan_terminal"] and home_refresh_context["db_updated"]):
            return
        refresh_id = home_refresh_context.get("refresh_id")
        request_id = home_refresh_context.get("request_id")
        _set_home_refresh_phase("UI_COMMIT", request_id=request_id, reason="home_ui_updated")
        total_started = home_refresh_context.get("started_at") or time.monotonic()
        duration_ms = int((time.monotonic() - total_started) * 1000)
        diagnostics.record("HOME_REFRESH_UI_UPDATED", refresh_id=refresh_id, duration_ms=duration_ms)
        diagnostics.record("HOME_REFRESH_COMPLETED", refresh_id=refresh_id, duration_ms=duration_ms)
        performance.counter("home.refresh.ui_updated")
        performance.event("home.refresh", duration_ms=(time.monotonic() - total_started) * 1000.0, screen="home", metadata={"refresh_id": refresh_id, "rebuild": False, "db_updated": True})
        _set_home_refresh_phase("SUCCESS", request_id=request_id, reason="ui_commit_complete")
        home_refresh_context["active"] = False
        home_refresh_context["request_id"] = None
        home_state["_manual_refresh_pending"] = False
        _publish_home_refresh_state("SUCCESS")
        resetter = home_state.get("_reset_refresh_state")
        if callable(resetter):
            resetter("SUCCESS", 1.2)
    organize_state = {}
    details_state = {}
    settings_state = {}
    device_interaction_profile = {}
    navigation = NavigationController()
    performance.set_screen_provider(lambda: navigation.current)
    performance.install_page_hooks(page)
    details_instance_generation = [0]
    collector_instance_generation = [0]
    saf_selection = SafSelectionState()
    # One Python navigation stack, one persistent Flet host, and cached
    # top-level screens. Returning to a screen must not destroy its scroll,
    # search, filter or focus state.
    screen_cache = {}
    # Cache the Flet navigation shells separately from the content controls.
    # Replacing one screen's content therefore does not recreate its View/SafeArea
    # wrapper or unnecessarily churn page.views.
    view_shell_cache = {}
    render_state = {
        "dirty": True,
        "signature": None,
    }
    # Settings background tasks are generation-bound to the current Settings
    # control tree. Navigation/render replacement invalidates the previous tree.
    # Settings nested levels are part of NavigationController, so Android Back
    # never consults a second Settings-specific navigation authority.
    # Flet's page.views is the navigation surface consumed by the Android/system
    # Back dispatcher. The existing NavigationController remains the single
    # logical source of truth; page.views mirrors its stack without introducing
    # a second navigation model.
    # Runtime snapshots are deliberately not stored in SQLite: only Android is
    # proof of a current grant. ``dismissed`` prevents an automatic onboarding loop.
    processed_native_operations = set()
    native_operation_states = {}
    back_state = {
        "last_at": 0.0,
        "last_action": None,
        "flet_pop_count": 0,
        "navigation_count": 0,
    }
    BACK_DEBOUNCE_SECONDS = 0.30
    navigation_state_path = os.path.join(data_dir, "navigation_state.json")

    def load_navigation_state():
        """
        Restore durable UI preferences only.

        Runtime navigation is intentionally process-local. A fresh Python process
        always starts from NavigationController's Home root; persisted route/stack
        data from older versions is ignored rather than replayed.
        """
        try:
            with open(navigation_state_path, "r", encoding="utf-8") as handle:
                state = json.load(handle)
        except (OSError, ValueError, TypeError):
            return {}
        if not isinstance(state, dict):
            logger.warning("[NAV] persisted UI state is not an object; starting from Home")
            return {}

        restored = state.get("home_state")
        if isinstance(restored, dict):
            home_state.update(
                {str(key): value for key, value in restored.items() if not callable(value)}
            )
        for target, key in ((organize_state, "organize_state"), (settings_state, "settings_state")):
            restored_view = state.get(key)
            if isinstance(restored_view, dict):
                target.update(
                    {str(name): value for name, value in restored_view.items() if not callable(value)}
                )

        logger.info(
            "[NAV] startup route reset to Home; durable ui_state restored "
            "home_keys=%s organize_keys=%s settings_keys=%s",
            len(home_state),
            len(organize_state),
            len(settings_state),
        )
        return state

    if navigation.current != "home":
        navigation.reset_to_root()
        logger.warning("[NAV] startup invariant repaired: runtime route was not Home")

    navigation_persist = {"pending": False, "running": False, "closing": False}

    # Restore durable Home/Organize/Settings UI state only. Runtime route/stack
    # remains process-local and therefore starts at Home on every fresh process.
    load_navigation_state()

    def _navigation_state_payload():
        return {
            "version": 3,
            "home_state": {
                str(key): value
                for key, value in home_state.items()
                if not callable(value)
            },
            "organize_state": {
                str(key): value
                for key, value in organize_state.items()
                if not callable(value)
            },
            "settings_state": {
                str(key): value
                for key, value in settings_state.items()
                if not callable(value)
            },
        }

    def _write_navigation_state(state):
        temporary = navigation_state_path + ".tmp"
        try:
            with open(temporary, "w", encoding="utf-8") as handle:
                json.dump(state, handle, ensure_ascii=False, separators=(",", ":"))
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, navigation_state_path)
        except (OSError, TypeError, ValueError):
            try:
                if os.path.exists(temporary):
                    os.unlink(temporary)
            except OSError:
                pass
            logger.exception("[NAV] failed to persist navigation snapshot")

    async def _flush_navigation_state():
        navigation_persist["running"] = True
        try:
            while navigation_persist["pending"] and not navigation_persist["closing"] and ui_alive[0]:
                navigation_persist["pending"] = False
                await asyncio.to_thread(
                    _write_navigation_state,
                    _navigation_state_payload(),
                )
        finally:
            navigation_persist["running"] = False
            if navigation_persist["pending"] and not navigation_persist["closing"] and ui_alive[0]:
                page.run_task(_flush_navigation_state)

    def persist_navigation_state():
        if navigation_persist["closing"] or not ui_alive[0]:
            return
        navigation_persist["pending"] = True
        if not navigation_persist["running"]:
            page.run_task(_flush_navigation_state)

    def clear_persisted_navigation_state():
        # Kept as a lifecycle hook for the existing navigation contract. Runtime
        # navigation is no longer persisted, so closing the app must not erase
        # durable UI preferences such as filters and scroll positions.
        navigation_persist["closing"] = True
        navigation_persist["pending"] = False
        logger.info("[NAV] process exit: runtime route is not persisted")

    def _route_for_screen(screen):
        return {
            "home": "/",
            "library": "/library",
            "organize": "/organize",
            "details": "/details",
            "collector": "/collector",
            "settings": "/settings",
        }.get(screen, "/" + str(screen))

    def _ui_render_signature():
        current_id = None
        if isinstance(current[0], dict):
            current_id = current[0].get("id")
        return (
            tuple(navigation.stack),
            tuple(navigation.settings_path),
            current_id,
        )

    def _mark_ui_dirty():
        render_state["dirty"] = True

    def _drop_screen_cache(route):
        screen_cache.pop(route, None)
        _mark_ui_dirty()

    def _clear_screen_cache():
        screen_cache.clear()
        _mark_ui_dirty()

    def _view_shell(route, view_route, control, *, key):
        cached = view_shell_cache.get(key)
        if cached is None:
            safe_area = ft.SafeArea(
                expand=True,
                content=control,
            )
            view = ft.View(
                route=view_route,
                bgcolor=page.bgcolor,
                controls=[safe_area],
                padding=0,
            )
            view_shell_cache[key] = (view, safe_area)
            return view

        view, safe_area = cached
        safe_area.content = control
        view.route = view_route
        view.bgcolor = page.bgcolor
        return view

    def _invalidate_cached_view(state, route):
        """Retire callbacks before dropping a cached Flet control tree."""
        invalidate_tasks = state.pop('_invalidate_view_tasks', None)
        if callable(invalidate_tasks):
            invalidate_tasks()
        state.pop('_update_thumbnail', None)
        screen_cache.pop(route, None)
        _mark_ui_dirty()

    def _invalidate_catalog_views():
        # Details mutations are durable Store changes. Invalidate only the
        # cached projections that can display those fields when we return.
        _invalidate_cached_view(home_state, "home")
        _invalidate_cached_view(organize_state, "organize")

    def _toggle_favorite_from_details(anime_id):
        value = store.toggle_favorite(anime_id)
        _invalidate_catalog_views()
        return value

    def _toggle_pin_from_details(anime_id):
        value = library.toggle_pinned(anime_id)
        _invalidate_catalog_views()
        return value

    def _set_tags_from_details(anime_id, tags):
        value = library.set_user_tags(anime_id, tags)
        _invalidate_catalog_views()
        return value

    def _set_note_from_details(anime_id, note):
        value = library.set_personal_note(anime_id, note)
        _invalidate_catalog_views()
        return value

    def _set_episode_identification_from_details(path, **values):
        result = store.set_episode_identification(path, **values)
        _invalidate_catalog_views()
        return result

    def _build_screen(route, *, force=False, settings_path_override=None):
        build_started = performance.now()
        cache_key = None if route == "collector" else (route if settings_path_override is None else None)
        if force and cache_key is not None:
            screen_cache.pop(cache_key, None)
        control = screen_cache.get(cache_key) if cache_key is not None else None
        if control is not None:
            performance.record_ui_build(route, (performance.now()-build_started)*1000.0,
                                        controls=performance.control_count(control), cached=True)
            return control
        if route == "home":
            control = HomeView.build(
                page, library, navigate_details, navigate_settings, play_episode,
                navigate_organize, view_state=home_state,
                on_request_thumbnail=request_missing_thumbnail,
                on_open_library=navigate_library,
                on_open_collector=navigate_collector,
                on_refresh_library=request_home_refresh,
                on_refresh_ui_updated=_home_refresh_ui_updated,
                on_refresh_ui_failed=lambda: _fail_home_refresh("home_ui_refresh_failed"),
                is_active=lambda: ui_alive[0] and navigation.current == "home",
            )
        elif route == "library":
            # The existing Python NavigationController owns the logical route;
            # Compose is mounted by MainActivity as its reversible visual host.
            control = ft.Container(expand=True)
        elif route == "organize":
            control = OrganizeView.build(
                page, library, navigate_details,
                lambda: navigate_back("visual:organize"), navigate_settings,
                on_request_storage_access=open_broad_storage_access,
                on_scan_storage=refresh_library,
                on_request_video_access=request_video_access,
                on_add_folder=add_folder,
                view_state=organize_state,
                is_active=lambda: ui_alive[0] and navigation.current == "organize",
            )
        elif route == "details":
            details_instance_generation[0] += 1
            detail_instance_token = details_instance_generation[0]
            detail_anime_id = (current[0] or {}).get("id")
            control = DetailView.build(
                page, current[0], play_episode,
                lambda: navigate_back("visual:details"),
                _toggle_favorite_from_details, library.playback_target,
                _set_tags_from_details, _toggle_pin_from_details, _set_note_from_details,
                _set_episode_identification_from_details, refresh_current_details,
                refresh_current_metadata, library.resolve_artwork, library.resolve_artwork_batch,
                on_open_marathon=open_marathon,
                resolve_artwork_palette=library.resolve_artwork_palette,
                on_request_thumbnail=request_missing_thumbnail,
                is_active=lambda token=detail_instance_token, anime_id=detail_anime_id: (
                    ui_alive[0]
                    and navigation.current == "details"
                    and details_instance_generation[0] == token
                    and (current[0] or {}).get("id") == anime_id
                ),
                view_state=details_state,
            )
        elif route == "collector":
            collector_instance_generation[0] += 1
            collector_instance_token = collector_instance_generation[0]
            control = CollectorView.build(
                page,
                library,
                lambda: navigate_back("visual:collector"),
                is_active=lambda token=collector_instance_token: (
                    ui_alive[0]
                    and navigation.current == "collector"
                    and collector_instance_generation[0] == token
                ),
            )
        elif route == "settings":
            fixed_settings_path = (
                tuple(settings_path_override)
                if settings_path_override is not None
                else None
            )
            if fixed_settings_path == () and bridge.available:
                # The root Settings surface is fully replaced by Compose on
                # Android. Keep only the empty Flet shell required by the
                # existing Python NavigationController/page.views projection.
                control = ft.Container(expand=True)
            else:
                # Nested Settings pages still belong to the existing Python/Flet
                # flow, and this also remains the fallback when the native Compose
                # host is unavailable.
                control = SettingsView.build(
                    page, store, library,
                    lambda: navigate_back("visual:settings"),
                    on_catalog_changed, add_folder, remove_folder, refresh_library,
                    request_video_access, open_broad_storage_access, login, logout,
                    account(), account_state[0],
                    folder_selection_pending=lambda: saf_selection.pending,
                    on_resolve_match=resolve_match,
                    storage_snapshot=storage_capabilities[0], scan_snapshot=scan_state[0],
                    settings=settings,
                    view_state=settings_state,
                    on_check_video_access=check_video_access,
                    on_create_backup=create_backup,
                    on_inspect_backup=inspect_backup,
                    on_restore_backup=restore_backup,
                    on_export_diagnostics=export_diagnostics,
                    on_integrity_check=integrity_check,
                    on_reconcile_after_restore=request_restore_reconciliation,
                    on_settings_changed=apply_settings_runtime,
                    on_open_settings_category=navigate_settings_category,
                    settings_path_provider=(
                        (lambda path=fixed_settings_path: path)
                        if fixed_settings_path is not None
                        else (lambda: navigation.settings_path)
                    ),
                    settings_is_active=(
                        lambda path=fixed_settings_path: (
                            navigation.current == "settings"
                            and tuple(navigation.settings_path) == tuple(path or ())
                        )
                    ),
                    settings_generation_provider=lambda: settings_tasks.generation,
                    register_settings_task=settings_tasks.register,
                )
        else:
            raise RuntimeError(f"Unknown navigation route: {route}")
        if cache_key is not None:
            screen_cache[cache_key] = control
        performance.record_ui_build(route, (performance.now()-build_started)*1000.0,
                                    controls=performance.control_count(control), cached=False)
        return control

    def _settings_view_paths():
        # page.views is only a projection of the existing NavigationController:
        # one top-level Settings view plus one View per existing settings_path level.
        paths = [()]
        current_path = tuple(navigation.settings_path)
        paths.extend(current_path[:index] for index in range(1, len(current_path) + 1))
        return paths

    def render_current(force=False, *, reason="unknown"):
        render_started = performance.now()
        reason_key = "".join(
            char if char.isalnum() else "_" for char in str(reason or "unknown")
        ).strip("_").lower() or "unknown"
        performance.counter("ui.render_current.requested")
        performance.counter(f"ui.render_current.request.{reason_key}")
        signature = _ui_render_signature()

        # Repeated render requests are common around navigation/back and external
        # callbacks. When neither navigation nor the mounted screen changed, do
        # not rebuild, invalidate Settings focus, churn page.views or call update.
        if (
            not force
            and not render_state["dirty"]
            and render_state["signature"] == signature
        ):
            performance.counter("ui.render_current.skipped_unchanged")
            performance.counter(f"ui.render_current.skip.{reason_key}")
            performance.event(
                "ui.render_current",
                status="skipped_unchanged",
                screen=navigation.current,
                metadata={
                    "reason": reason,
                    "settings_depth": len(navigation.settings_path),
                },
            )
            return

        if navigation.current == "settings":
            # Settings is intentionally not cached in screen_cache: every executed
            # Settings render creates a new control tree. Invalidate its tasks before
            # that replacement so callbacks capture the new generation.
            settings_tasks.invalidate()

        views = []
        for route in navigation.stack:
            if route != "settings":
                control = _build_screen(
                    route,
                    force=force and route == navigation.current,
                )
                views.append(
                    _view_shell(
                        route,
                        _route_for_screen(route),
                        control,
                        key=(route, None),
                    )
                )
                continue

            # Each nested Settings level gets its own Flet View, but all of them
            # are derived from the one NavigationController.settings_path.
            # This makes Android/Flet Back consume exactly one Settings level.
            for path in _settings_view_paths():
                control = _build_screen(
                    "settings",
                    force=force and path == navigation.settings_path,
                    settings_path_override=path,
                )
                route_suffix = "/".join(path)
                view_route = "/settings" + (f"/{route_suffix}" if route_suffix else "")
                views.append(
                    _view_shell(
                        "settings",
                        view_route,
                        control,
                        key=("settings", tuple(path)),
                    )
                )

        previous_view_ids = tuple(id(view) for view in page.views)
        next_view_ids = tuple(id(view) for view in views)
        page_views_replaced = previous_view_ids != next_view_ids
        if page_views_replaced:
            page.views.clear()
            page.views.extend(views)
            performance.counter("ui.render_current.page_views_replaced")
        else:
            performance.counter("ui.render_current.page_views_reused")

        render_state["signature"] = signature
        render_state["dirty"] = False
        performance.counter("ui.render_current")
        performance.counter("ui.render_current.executed")
        performance.counter(f"ui.render_current.execute.{reason_key}")
        if navigation.current == "home" and home_state.get("_manual_refresh_pending"):
            refresh = home_state.get("_refresh_from_catalog")
            if callable(refresh):
                refresh()

        performance.event(
            "ui.render_current",
            duration_ms=(performance.now()-render_started)*1000.0,
            screen=navigation.current,
            metadata={
                "force": force,
                "reason": reason,
                "settings_depth": len(navigation.settings_path),
                "view_count": len(views),
                "controls": sum(
                    performance.control_count(view) or 0 for view in views
                ),
                "page_views_replaced": page_views_replaced,
            },
        )
        safe_update()
        logger.info(
            "NAV_RENDER_CURRENT duration_ms=%s current=%s settings_depth=%s "
            "view_count=%s force=%s reason=%s page_views_replaced=%s",
            int((time.perf_counter() - render_started) * 1000),
            navigation.current,
            len(navigation.settings_path),
            len(views),
            force,
            reason,
            page_views_replaced,
        )

    async def _show_compose_library():
        if not bridge.available or navigation.current != "library" or not ui_alive[0]:
            return
        try:
            await bridge.open_library()
        except Exception as exc:
            logger.exception("[COMPOSE_LIBRARY] host open failed", exc_info=True)
            if navigation.current == "library" and ui_alive[0]:
                navigation.back()
                render_current(reason="compose_library_open_failed")
                persist_navigation_state()
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível abrir a Biblioteca Compose agora."))
                page.snack_bar.open = True
                safe_update()

    async def _hide_compose_library():
        if not bridge.available:
            return
        try:
            await bridge.hide_library()
        except Exception:
            logger.exception("[COMPOSE_LIBRARY] host hide failed", exc_info=True)

    async def _show_compose_settings():
        if (
            not bridge.available
            or navigation.current != "settings"
            or navigation.settings_path
            or not ui_alive[0]
        ):
            return
        try:
            await bridge.open_settings()
        except Exception as exc:
            logger.exception("[COMPOSE_SETTINGS] host open failed", exc_info=True)
            if navigation.current == "settings" and not navigation.settings_path and ui_alive[0]:
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível abrir as Configurações Compose agora."))
                page.snack_bar.open = True
                safe_update()

    async def _hide_compose_settings():
        if not bridge.available:
            return
        try:
            await bridge.hide_settings()
        except Exception:
            logger.exception("[COMPOSE_SETTINGS] host hide failed", exc_info=True)

    def handle_flet_view_pop(_event):
        back_state["flet_pop_count"] += 1
        pop_id = back_state["flet_pop_count"]
        logger.info(
            "BACK_FLET_VIEW_POP_RECEIVED id=%s nav=%s settings_depth=%s views=%s",
            pop_id,
            navigation.current,
            len(navigation.settings_path),
            len(page.views),
        )
        navigate_back(f"flet_view_pop:{pop_id}")

    def navigate_home():
        previous = navigation.current
        with performance.interaction("return_home", source=previous, target="home"):
            if previous == "library":
                page.run_task(_hide_compose_library)
            navigation.reset_to_root()
            render_current(reason="return_home")
            persist_navigation_state()

    def navigate_library():
        if not bridge.available:
            page.snack_bar = ft.SnackBar(ft.Text("A Biblioteca Compose está disponível somente no APK Android."))
            page.snack_bar.open = True
            safe_update()
            return
        if navigation.current == "library":
            return
        previous = navigation.current
        with performance.interaction("open_library", source=previous, target="library"):
            navigation.push("library")
            render_current(reason="open_library")
            persist_navigation_state()
            page.run_task(_show_compose_library)

    def navigate_organize():
        previous = navigation.current
        with performance.interaction("open_organize", source=previous, target="organize"):
            navigation.push("organize")
            render_current(reason="open_organize")
            persist_navigation_state()
    def navigate_collector():
        previous = navigation.current
        with performance.interaction("open_collector", source=previous, target="collector"):
            navigation.push("collector")
            render_current(reason="open_collector")
            persist_navigation_state()
    player_transition_inflight = {"value": False}
    player_launch_inflight = {"value": False}
    player_transition_generation = {"value": 0}
    player_active_request_id = {"value": None}
    player_active_session_id = {"value": None}
    player_active_activity_instance_id = {"value": None}
    player_active_episode_id = {"value": None}
    player_active_anime_id = {"value": None}
    player_active_uri = {"value": None}
    player_active_player_generation = {"value": 0}
    player_resume_context = {"value": None}
    player_session_active = {"value": False}
    player_command_sequence = {"value": 0}
    player_command_seen = set()
    player_last_command = {"sequence": 0, "request_id": None, "direction": None, "created_at_ms": 0}
    pending_next_transition = {"value": None}
    pending_previous_transition = {"value": None}

    def cancel_player_transition(reason="unknown"):
        player_transition_generation["value"] += 1
        task = player_transition_task["task"]
        current_task = asyncio.current_task()
        if task is not None and task is not current_task and not task.done():
            task.cancel()
        player_transition_task["task"] = None
        player_transition_inflight["value"] = False
        next_context = pending_next_transition["value"]
        if isinstance(next_context, dict):
            performance.event(
                "NEXT_TRANSITION_INVALIDATED",
                screen=navigation.current,
                metadata={
                    "request_id": next_context.get("origin_request_id"),
                    "target_request_id": next_context.get("target_request_id"),
                    "age_ms": max(0, int(time.time() * 1000) - int(next_context.get("created_at_ms") or 0)),
                    "origin_generation": next_context.get("native_transition_generation"),
                    "current_generation": player_transition_generation["value"],
                    "player_session_id": next_context.get("player_session_id"),
                    "origin_monotonic_ns": next_context.get("command_monotonic_ns"),
                    "reason": reason,
                },
            )
            performance.event(
                "NEXT_REQUEST_CANCELLED",
                screen=navigation.current,
                metadata={
                    "request_id": next_context.get("origin_request_id"),
                    "reason": reason,
                    "player_session_id": next_context.get("player_session_id"),
                    "origin_monotonic_ns": next_context.get("command_monotonic_ns"),
                },
            )
            diagnostics.record(
                "NEXT_TRANSITION_CANCELLED",
                request_id=next_context.get("origin_request_id"),
                source="native_player",
                result=reason,
            )
            pending_next_transition["value"] = None

        previous_context = pending_previous_transition["value"]
        if isinstance(previous_context, dict):
            performance.event(
                "PREVIOUS_TRANSITION_INVALIDATED",
                screen=navigation.current,
                metadata={
                    "request_id": previous_context.get("origin_request_id"),
                    "target_request_id": previous_context.get("target_request_id"),
                    "age_ms": max(0, int(time.time() * 1000) - int(previous_context.get("created_at_ms") or 0)),
                    "origin_generation": previous_context.get("native_transition_generation"),
                    "current_generation": player_transition_generation["value"],
                    "player_session_id": previous_context.get("player_session_id"),
                    "origin_monotonic_ns": previous_context.get("command_monotonic_ns"),
                    "reason": reason,
                },
            )
            performance.event(
                "PREVIOUS_REQUEST_CANCELLED",
                screen=navigation.current,
                metadata={
                    "request_id": previous_context.get("origin_request_id"),
                    "reason": reason,
                    "player_session_id": previous_context.get("player_session_id"),
                    "origin_monotonic_ns": previous_context.get("command_monotonic_ns"),
                },
            )
            diagnostics.record(
                "PREVIOUS_TRANSITION_CANCELLED",
                request_id=previous_context.get("origin_request_id"),
                source="native_player",
                result=reason,
            )
            pending_previous_transition["value"] = None
        performance.event(
            "PLAYER_TRANSITION_INVALIDATED",
            screen=navigation.current,
            metadata={"request_id": player_active_request_id["value"],
                      "generation": player_transition_generation["value"],
                      "reason": reason},
        )
        diagnostics.record(
            "PLAYER_TRANSITION_CANCELLED",
            request_id=player_active_request_id["value"],
            source="native_player",
            result=reason,
        )
        logger.info(
            "[PLAYER] transition cancelled generation=%s reason=%s",
            player_transition_generation["value"],
            reason,
        )

    def invalidate_player_session(reason="unknown", expected_session_id=None, expected_request_id=None):
        current_session_id = player_active_session_id["value"]
        current_request_id = player_active_request_id["value"]
        if expected_session_id and current_session_id not in (None, expected_session_id):
            performance.event(
                "PLAYER_CALLBACK_STALE",
                screen=navigation.current,
                status="ignored",
                metadata={"request_id": expected_request_id or current_request_id, "player_session_id": expected_session_id, "current_player_session_id": current_session_id, "reason": "session_mismatch_on_invalidation"},
            )
            return False
        if expected_request_id and current_request_id not in (None, expected_request_id):
            performance.event(
                "PLAYER_CALLBACK_STALE",
                screen=navigation.current,
                status="ignored",
                metadata={"request_id": expected_request_id, "current_request_id": current_request_id, "reason": "request_mismatch_on_invalidation"},
            )
            return False
        if current_session_id:
            performance.event(
                "PLAYER_SESSION_INVALIDATED",
                screen=navigation.current,
                metadata={"request_id": current_request_id, "player_session_id": current_session_id, "player_generation": player_active_player_generation["value"], "reason": reason},
            )
        cancel_player_transition(reason)
        try:
            store.invalidate_playback_session(current_session_id)
        except Exception:
            logger.exception("[PLAYER] failed to invalidate durable playback session")
        player_session_active["value"] = False
        player_active_session_id["value"] = None
        player_active_activity_instance_id["value"] = None
        player_active_request_id["value"] = None
        player_active_episode_id["value"] = None
        player_active_anime_id["value"] = None
        player_active_uri["value"] = None
        player_active_player_generation["value"] = 0
        return True

    def player_callback_identity_is_complete(payload):
        """Require the canonical playback identity before accepting durable progress callbacks."""
        if not isinstance(payload, dict):
            return False
        session_id = str(payload.get("playerSessionId") or payload.get("player_session_id") or "").strip()
        episode_id = str(payload.get("episodeId") or "").strip()
        media_id = str(payload.get("mediaId") or "").strip()
        uri = str(payload.get("uri") or "").strip()
        return bool(session_id and episode_id and media_id and uri)

    def player_callback_is_current(event_request_id, payload, *, require_active=True, episode_id=None, media_id=None, anime_id=None):
        session_id = str(payload.get("playerSessionId") or payload.get("player_session_id") or "").strip()
        activity_instance_id = str(
            payload.get("activityInstanceId")
            or payload.get("activity_instance_id")
            or ""
        ).strip()
        generation = int(payload.get("generation") or payload.get("playerGeneration") or 0)
        transition_gen = int(payload.get("transitionGeneration") or 0)
        if require_active and not player_session_active["value"]:
            return False, "stale_session"
        if session_id and player_active_session_id["value"] not in (None, session_id):
            return False, "stale_session"
        if (
            activity_instance_id
            and player_active_activity_instance_id["value"] not in (None, activity_instance_id)
        ):
            return False, "stale_activity_instance"
        if event_request_id and player_active_request_id["value"] not in (None, event_request_id):
            return False, "stale_request"
        if generation > 0 and player_active_player_generation["value"] > 0 and generation != player_active_player_generation["value"]:
            return False, "stale_player_generation"
        if transition_gen > 0 and player_transition_generation["value"] > 0 and transition_gen < player_transition_generation["value"]:
            return False, "stale_transition_generation"
        if episode_id and player_active_episode_id["value"] not in (None, episode_id):
            return False, "stale_episode"
        normalized_media_id = str(media_id or "").strip()
        if normalized_media_id:
            active_episode_id = player_active_episode_id["value"]
            if active_episode_id is not None:
                expected_media_id = f"episode:{active_episode_id}"
                if normalized_media_id != expected_media_id:
                    return False, "stale_media_identity"
            elif player_active_uri["value"] and normalized_media_id != str(player_active_uri["value"]).strip():
                return False, "stale_media_identity"
        normalized_anime_id = str(anime_id or "").strip()
        active_anime_id = str(player_active_anime_id["value"] or "").strip()
        if normalized_anime_id and active_anime_id and normalized_anime_id != active_anime_id:
            return False, "stale_anime"
        return True, ""

    def player_transition_is_current(generation, request_id, player_session_id=None):
        session_matches = (
            player_session_id is None
            or (
                bool(player_active_session_id["value"])
                and str(player_active_session_id["value"]) == str(player_session_id)
            )
        )
        return (
            generation == player_transition_generation["value"]
            and player_active_request_id["value"] in (None, request_id)
            and player_session_active["value"]
            and session_matches
            and ui_alive[0]
        )

    async def start_native_player(
        path,
        title,
        position_ms=0,
        *,
        episode_id=None,
        anime_id=None,
        navigation_snapshot=None,
        origin_request_id=None,
        origin_created_at_ms=0,
        origin_transition_generation=0,
        player_session_id=None,
        origin_player_session_id=None,
        origin_monotonic_ns=0,
        transition_direction=None,
        transition_guard=None,
    ):
        direction_label = str(transition_direction or "").strip().upper()
        stale_event = {"NEXT": "NEXT_REQUEST_STALE", "PREVIOUS": "PREVIOUS_REQUEST_STALE"}.get(direction_label, "NEXT_REQUEST_STALE")
        stale_rejected_event = {"NEXT": "PLAYER_NEXT_STALE_REJECTED", "PREVIOUS": "PLAYER_PREVIOUS_STALE_REJECTED"}.get(direction_label, "PLAYER_NEXT_STALE_REJECTED")
        invalidated_event = {"NEXT": "NEXT_TRANSITION_INVALIDATED", "PREVIOUS": "PREVIOUS_TRANSITION_INVALIDATED"}.get(direction_label, "PLAYER_TRANSITION_INVALIDATED")

        def transition_is_valid():
            return transition_guard is None or bool(transition_guard())

        if not transition_is_valid():
            if origin_request_id:
                performance.event(
                    stale_event,
                    screen=navigation.current,
                    status="rejected",
                    metadata={
                        "request_id": origin_request_id,
                        "reason": "stale_before_native_handoff",
                        "player_session_id": origin_player_session_id,
                        "age_ms": max(0, int(time.time() * 1000) - int(origin_created_at_ms or 0)),
                    },
                )
                performance.event(
                    stale_rejected_event,
                    screen=navigation.current,
                    metadata={
                        "request_id": origin_request_id,
                        "age_ms": max(0, int(time.time() * 1000) - int(origin_created_at_ms or 0)),
                        "origin_generation": origin_transition_generation,
                        "current_generation": player_transition_generation["value"],
                        "reason": "stale_before_native_handoff",
                    },
                )
            raise asyncio.CancelledError()

        performance.event(
            "NATIVE_PLAY_REQUEST_CREATED",
            screen=navigation.current,
            metadata={
                "path": path,
                "position_ms": position_ms,
                "episode_id": episode_id,
                "anime_id": anime_id,
                "origin_request_id": origin_request_id,
                "origin_created_at_ms": origin_created_at_ms,
                "origin_transition_generation": origin_transition_generation,
                "origin_monotonic_ns": origin_monotonic_ns,
                "transition_direction": direction_label,
            },
        )

        if navigation_snapshot is None:
            play_started_at = time.perf_counter()
            navigation_snapshot = await asyncio.to_thread(library.player_navigation, path)
            neighbor_resolution_ms = int((time.perf_counter() - play_started_at) * 1000)
            performance.event(
                "player.neighbor_resolution",
                duration_ms=neighbor_resolution_ms,
                screen=navigation.current,
                metadata={
                    "path": path,
                    "can_next": bool(navigation_snapshot.get("can_next")),
                    "can_previous": bool(navigation_snapshot.get("can_previous")),
                    "source": "player_navigation",
                },
            )
            performance.event(
                "EPISODE_RESOLVED",
                screen=navigation.current,
                metadata={
                    "episode_id": episode_id,
                    "anime_id": anime_id,
                    "can_next": bool(navigation_snapshot.get("can_next")),
                    "can_previous": bool(navigation_snapshot.get("can_previous")),
                    "source": "library.player_navigation",
                },
            )
            logger.info(
                "[PLAYER] PLAY_PREPARED path=%s neighbor_resolution_ms=%s source=player_navigation",
                path,
                neighbor_resolution_ms,
            )
        else:
            logger.info(
                "[PLAYER] PLAY_PREPARED path=%s source=navigation_snapshot can_next=%s can_previous=%s",
                path,
                bool(navigation_snapshot.get("can_next")),
                bool(navigation_snapshot.get("can_previous")),
            )

        performance.event(
            "PLAYER_NAVIGATION_REQUESTED",
            screen=navigation.current,
            metadata={"request_id": origin_request_id or player_active_request_id["value"],
                      "episode_id": episode_id, "anime_id": anime_id},
        )
        if not transition_is_valid():
            if origin_request_id:
                performance.event(
                    "NEXT_REQUEST_STALE",
                    screen=navigation.current,
                    status="rejected",
                    metadata={
                        "request_id": origin_request_id,
                        "reason": "stale_before_bridge_send",
                        "player_session_id": origin_player_session_id,
                    },
                )
                performance.event(
                    "PLAYER_NEXT_STALE_REJECTED",
                    screen=navigation.current,
                    metadata={
                        "request_id": origin_request_id,
                        "age_ms": max(0, int(time.time() * 1000) - int(origin_created_at_ms or 0)),
                        "origin_generation": origin_transition_generation,
                        "current_generation": player_transition_generation["value"],
                        "reason": "stale_before_bridge_send",
                    },
                )
            raise asyncio.CancelledError()

        request_id = await bridge.play(
            path,
            title,
            position_ms,
            episode_id=episode_id,
            anime_id=anime_id,
            player_session_id=player_session_id or origin_player_session_id,
            can_next=bool(navigation_snapshot.get("can_next")),
            can_previous=bool(navigation_snapshot.get("can_previous")),
            autoplay=settings.get("player.autoplay_next"),
            origin_request_id=origin_request_id,
            origin_created_at_ms=origin_created_at_ms,
            origin_transition_generation=origin_transition_generation,
            origin_player_session_id=origin_player_session_id,
            origin_monotonic_ns=origin_monotonic_ns,
            transition_direction=direction_label or None,
            player_settings={
                "player.default_speed": settings.get("player.default_speed"),
                "player.aspect_ratio": settings.get("player.aspect_ratio"),
                "player.zoom_enabled": settings.get("player.zoom_enabled"),
                "player.immersive": settings.get("player.immersive"),
                "player.rotation": settings.get("player.rotation"),
                "player.pip": settings.get("player.pip"),
                "player.auto_hide_seconds": settings.get("player.auto_hide_seconds"),
                "player.double_tap_seek_seconds": settings.get("player.double_tap_seek_seconds"),
                "player.long_press_speed": settings.get("player.long_press_speed"),
                "player.max_video_resolution": settings.get("player.max_video_resolution"),
                "player.max_video_frame_rate": settings.get("player.max_video_frame_rate"),
                "player.max_audio_channels": settings.get("player.max_audio_channels"),
                "gestures.volume": settings.get("gestures.volume"),
                "gestures.brightness": settings.get("gestures.brightness"),
                "gestures.double_tap": settings.get("gestures.double_tap"),
                "gestures.long_press": settings.get("gestures.long_press"),
                "audio.preferred_language": settings.get("audio.preferred_language"),
                "audio.preferred_subtitle_language": settings.get("audio.preferred_subtitle_language"),
                "audio.subtitles": settings.get("audio.subtitles"),
                "audio.subtitle_scale": settings.get("audio.subtitle_scale"),
                "audio.subtitle_bottom_padding": settings.get("audio.subtitle_bottom_padding"),
                "audio.subtitle_embedded_style": settings.get("audio.subtitle_embedded_style"),
            },
        )
        if not transition_is_valid():
            if origin_request_id:
                performance.event(
                    invalidated_event,
                    screen=navigation.current,
                    metadata={
                        "request_id": origin_request_id,
                        "target_request_id": request_id,
                        "reason": "session_invalidated_after_bridge",
                        "player_session_id": origin_player_session_id,
                    },
                )
            raise asyncio.CancelledError()

        performance.event(
            "URI_VALIDATED",
            screen=navigation.current,
            metadata={
                "request_id": request_id,
                "player_session_id": player_session_id or origin_player_session_id,
                "episode_id": episode_id,
                "anime_id": anime_id,
                "source": "android_bridge.normalize_local_media_reference",
            },
        )
        performance.event(
            "NATIVE_HANDOFF_ACCEPTED",
            screen=navigation.current,
            metadata={
                "request_id": request_id,
                "episode_id": episode_id,
                "anime_id": anime_id,
                "origin_request_id": origin_request_id,
                "origin_created_at_ms": origin_created_at_ms,
                "origin_transition_generation": origin_transition_generation,
            },
        )
        logger.info(
            "[PLAYER] PLAY_COMMAND_CONFIRMED request_id=%s origin_request_id=%s",
            request_id,
            origin_request_id or "-",
        )
        return request_id

    def play_episode(path, title, on_next=None, progress_seconds=0, *, episode_id=None, anime_id=None):
        performance.event("player.click", screen=navigation.current,
                          metadata={"path": path, "progress_seconds": progress_seconds,
                                    "episode_id": episode_id, "anime_id": anime_id})
        try:
            normalized_episode_id = int(episode_id)
        except (TypeError, ValueError):
            normalized_episode_id = 0
        if normalized_episode_id <= 0:
            performance.event(
                "ASSIST_REQUEST_REJECTED",
                screen=navigation.current,
                status="rejected",
                metadata={
                    "reason": "EPISODE_NOT_FOUND",
                    "error_code": "EPISODE_NOT_FOUND",
                    "anime_id": anime_id,
                },
            )
            diagnostics.record(
                "PLAYER_COMMAND_REJECTED",
                source="details",
                result="EPISODE_NOT_FOUND",
            )
            return
        if not str(path or "").strip():
            performance.event(
                "ASSIST_REQUEST_REJECTED",
                screen=navigation.current,
                status="rejected",
                metadata={
                    "reason": "MEDIA_URI_MISSING",
                    "error_code": "MEDIA_URI_MISSING",
                    "episode_id": normalized_episode_id,
                    "anime_id": anime_id,
                },
            )
            diagnostics.record(
                "PLAYER_COMMAND_REJECTED",
                source="details",
                result="MEDIA_URI_MISSING",
            )
            return
        episode_id = normalized_episode_id
        if not settings.get("player.resume"):
            progress_seconds = 0
        requested_position_ms = max(0, int(progress_seconds * 1000))
        if player_launch_inflight["value"]:
            logger.info("[PLAYER] duplicate launch ignored path=%s", path)
            performance.event(
                "ASSIST_REQUEST_REJECTED",
                screen=navigation.current,
                status="rejected",
                metadata={
                    "reason": "OPEN_REQUEST_IN_FLIGHT",
                    "episode_id": episode_id,
                    "anime_id": anime_id,
                },
            )
            diagnostics.record(
                "PLAYER_HANDOFF_DUPLICATE_IGNORED",
                source="android_bridge",
                result="launch_inflight",
            )
            return
        player_launch_inflight["value"] = True
        launch_session_id = uuid.uuid4().hex
        if not store.activate_playback_session(launch_session_id):
            player_launch_inflight["value"] = False
            performance.event(
                "CONTINUE_REQUEST_REJECTED",
                screen=navigation.current,
                status="rejected",
                metadata={"reason": "PLAYBACK_SESSION_CREATE_FAILED"},
            )
            diagnostics.record(
                "PLAYER_COMMAND_REJECTED",
                source="continue_resume",
                result="PLAYBACK_SESSION_CREATE_FAILED",
            )
            return
        player_active_session_id["value"] = launch_session_id
        player_active_request_id["value"] = None
        player_active_episode_id["value"] = episode_id
        player_active_anime_id["value"] = anime_id
        player_active_uri["value"] = path
        player_active_player_generation["value"] = 0
        player_session_active["value"] = True
        performance.event(
            "ASSIST_REQUEST_CREATED",
            screen=navigation.current,
            metadata={
                "player_session_id": launch_session_id,
                "episode_id": episode_id,
                "anime_id": anime_id,
                "origin": "details_or_episode_card",
            },
        )
        performance.event(
            "PLAYER_SESSION_CREATED",
            screen=navigation.current,
            metadata={
                "player_session_id": launch_session_id,
                "episode_id": episode_id,
                "anime_id": anime_id,
                "origin": "python_launch",
            },
        )

        async def launch_native_player():
            launch_path = str(path or "").strip()
            launch_anime_id = anime_id
            launch_progress_seconds = requested_position_ms / 1000.0
            continue_lookup_started = performance.now()
            handoff_confirmed = False
            current_session_guard = lambda: (
                player_session_active["value"]
                and player_active_session_id["value"] == launch_session_id
                and ui_alive[0]
            )
            try:
                performance.event(
                    "CONTINUE_REQUEST_CREATED",
                    screen=navigation.current,
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": episode_id,
                        "anime_id": anime_id,
                    },
                )
                performance.event(
                    "PROGRESS_LOOKUP_STARTED",
                    screen=navigation.current,
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": episode_id,
                        "anime_id": anime_id,
                    },
                )
                fresh_episode = await asyncio.to_thread(store.episode_by_id, episode_id)
                performance.event(
                    "PROGRESS_LOOKUP_COMPLETED",
                    screen=navigation.current,
                    duration_ms=(performance.now() - continue_lookup_started) * 1000.0,
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": episode_id,
                        "found": bool(fresh_episode),
                    },
                )
                if not current_session_guard():
                    performance.event(
                        "CONTINUE_REQUEST_REJECTED",
                        screen=navigation.current,
                        status="rejected",
                        metadata={"reason": "STALE_SESSION_AFTER_PROGRESS_LOOKUP", "player_session_id": launch_session_id},
                    )
                    return
                if not fresh_episode or int(fresh_episode.get("id") or 0) != int(episode_id):
                    performance.event(
                        "CONTINUE_REQUEST_REJECTED",
                        screen=navigation.current,
                        status="rejected",
                        metadata={
                            "reason": "EPISODE_NOT_FOUND",
                            "episode_id": episode_id,
                            "player_session_id": launch_session_id,
                        },
                    )
                    diagnostics.record(
                        "PLAYER_COMMAND_REJECTED",
                        source="continue_resume",
                        result="EPISODE_NOT_FOUND",
                    )
                    return
                launch_path = str(fresh_episode.get("path") or "").strip()
                launch_anime_id = fresh_episode.get("anime_id")
                player_active_episode_id["value"] = fresh_episode.get("id")
                player_active_anime_id["value"] = launch_anime_id
                player_active_uri["value"] = launch_path
                if not launch_path or fresh_episode.get("missing"):
                    performance.event(
                        "CONTINUE_REQUEST_REJECTED",
                        screen=navigation.current,
                        status="rejected",
                        metadata={
                            "reason": "MEDIA_URI_MISSING" if not launch_path else "FILE_NOT_FOUND",
                            "episode_id": episode_id,
                            "player_session_id": launch_session_id,
                        },
                    )
                    diagnostics.record(
                        "PLAYER_COMMAND_REJECTED",
                        source="continue_resume",
                        result="MEDIA_URI_MISSING" if not launch_path else "FILE_NOT_FOUND",
                    )
                    return
                if settings.get("player.resume"):
                    raw_progress = fresh_episode.get("progress")
                    raw_duration = fresh_episode.get("duration")
                    try:
                        raw_progress_seconds = float(raw_progress or 0.0)
                        duration_seconds = max(0.0, float(raw_duration or 0.0))
                    except (TypeError, ValueError):
                        raw_progress_seconds = 0.0
                        duration_seconds = 0.0
                    progress_invalid = (
                        not math.isfinite(raw_progress_seconds)
                        or raw_progress_seconds < 0.0
                        or (duration_seconds > 0.0 and raw_progress_seconds > duration_seconds)
                    )
                    if progress_invalid:
                        performance.event(
                            "PROGRESS_REJECTED",
                            screen=navigation.current,
                            status="rejected",
                            metadata={
                                "player_session_id": launch_session_id,
                                "episode_id": fresh_episode.get("id"),
                                "raw_progress": raw_progress,
                                "duration_seconds": duration_seconds,
                                "reason": "INVALID_PERSISTED_PROGRESS",
                            },
                        )
                        launch_progress_seconds = 0.0
                    else:
                        launch_progress_seconds = raw_progress_seconds
                if launch_progress_seconds > 0.0:
                    player_resume_context["value"] = {
                        "session_id": launch_session_id,
                        "episode_id": fresh_episode.get("id"),
                        "anime_id": fresh_episode.get("anime_id"),
                        "resume_seconds": launch_progress_seconds,
                    }
                else:
                    player_resume_context["value"] = None
                performance.event(
                    "EPISODE_RESOLVED",
                    screen=navigation.current,
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": fresh_episode.get("id"),
                        "anime_id": fresh_episode.get("anime_id"),
                        "uri_source": "canonical_sqlite_row",
                    },
                )
                performance.event(
                    "PROGRESS_VALIDATED",
                    screen=navigation.current,
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": fresh_episode.get("id"),
                        "progress_seconds": launch_progress_seconds,
                        "duration_seconds": max(0.0, float(fresh_episode.get("duration") or 0.0)),
                    },
                )
                performance.event(
                    "MEDIA_RESOLVED",
                    screen=navigation.current,
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": fresh_episode.get("id"),
                        "anime_id": fresh_episode.get("anime_id"),
                        "uri_source": "canonical_sqlite_row",
                    },
                )
                if not current_session_guard():
                    performance.event(
                        "CONTINUE_REQUEST_REJECTED",
                        screen=navigation.current,
                        status="rejected",
                        metadata={"reason": "STALE_SESSION_BEFORE_HANDOFF", "player_session_id": launch_session_id},
                    )
                    return
                performance.event(
                    "PLAYER_HANDOFF_STARTED",
                    screen=navigation.current,
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": fresh_episode.get("id"),
                        "anime_id": launch_anime_id,
                    },
                )
                request_id = await start_native_player(
                    launch_path,
                    title,
                    int(max(0.0, launch_progress_seconds) * 1000),
                    episode_id=fresh_episode.get("id"),
                    anime_id=launch_anime_id,
                    player_session_id=launch_session_id,
                    origin_player_session_id=launch_session_id,
                    transition_guard=current_session_guard,
                )
                handoff_confirmed = True
                performance.event(
                    "PLAYER_HANDOFF_ACCEPTED",
                    screen=navigation.current,
                    metadata={
                        "request_id": request_id,
                        "player_session_id": launch_session_id,
                        "episode_id": fresh_episode.get("id"),
                        "anime_id": launch_anime_id,
                    },
                )
                performance.event(
                    "CONTINUE_REQUEST_ACCEPTED",
                    screen=navigation.current,
                    status="accepted",
                    metadata={
                        "request_id": request_id,
                        "player_session_id": launch_session_id,
                        "episode_id": fresh_episode.get("id"),
                        "anime_id": launch_anime_id,
                        "reason": "native_handoff_confirmed",
                    },
                )
                performance.event(
                    "ASSIST_REQUEST_ACCEPTED",
                    screen=navigation.current,
                    status="accepted",
                    metadata={
                        "request_id": request_id,
                        "player_session_id": launch_session_id,
                        "episode_id": episode_id,
                        "anime_id": anime_id,
                        "reason": "native_handoff_confirmed",
                    },
                )
                performance.event(
                    "player.launch_complete",
                    screen=navigation.current,
                    metadata={
                        "request_id": request_id,
                        "player_session_id": launch_session_id,
                        "episode_id": episode_id,
                        "anime_id": anime_id,
                    },
                )
            except Exception as exc:
                if player_active_session_id["value"] == launch_session_id:
                    invalidate_player_session("launch_failed", expected_session_id=launch_session_id)
                logger.exception("[PLAYER] native handoff failed path=%s", path)
                page.snack_bar = ft.SnackBar(
                    ft.Text("Não foi possível enviar este episódio ao player Android.")
                )
                page.snack_bar.open = True
                performance.event(
                    "CONTINUE_FAILED",
                    screen=navigation.current,
                    status="failed",
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": episode_id,
                        "anime_id": anime_id,
                        "reason": "native_handoff_exception",
                        "error_type": type(exc).__name__,
                    },
                )
                performance.event(
                    "ASSIST_FAILED",
                    screen=navigation.current,
                    status="failed",
                    metadata={
                        "player_session_id": launch_session_id,
                        "episode_id": episode_id,
                        "anime_id": anime_id,
                        "reason": "native_handoff_exception",
                        "error_type": type(exc).__name__,
                    },
                )
                diagnostics.record(
                    "PLAYER_HANDOFF_PYTHON_FAILED",
                    source="android_bridge",
                    error=str(exc),
                )
                safe_update()
            finally:
                player_launch_inflight["value"] = False
                if (
                    not handoff_confirmed
                    and player_active_session_id["value"] == launch_session_id
                ):
                    invalidate_player_session(
                        "continue_launch_rejected",
                        expected_session_id=launch_session_id,
                    )

        # NativePlayerActivity is the only player. Do not push a synthetic Flet
        # route before launching it; the current Details/Home screen remains the
        # origin to which Android back returns.
        page.run_task(launch_native_player)
    def open_marathon(anime_id, current_path=None):
        async def load_and_show():
            try:
                report = await asyncio.to_thread(library.marathon, int(anime_id), current_path=current_path)
            except Exception:
                logger.exception("Marathon calculation failed", extra={"anime_id": anime_id})
                page.pop_dialog()
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível calcular a maratona agora."))
                page.snack_bar.open = True
                safe_update()
                return

            items = report.get("items") or []
            if not items:
                dialog.content = ft.Column([
                    ft.Text("Nenhum episódio restante foi encontrado."),
                    ft.Text("Episódios concluídos não entram no cálculo. Durações desconhecidas não são inventadas.", size=12),
                ], tight=True)
                dialog.actions = [ft.TextButton("Fechar", on_click=lambda _: page.pop_dialog())]
                page.update()
                return

            known_seconds = float(report.get("known_duration_seconds") or 0)
            unknown_count = int(report.get("unknown_duration_count") or 0)
            start = datetime.datetime.now()
            estimated_end = start + datetime.timedelta(seconds=known_seconds)
            lines = [
                ft.Text(f"{report.get('episode_count', 0)} episódios na sequência", weight=ft.FontWeight.BOLD),
                ft.Text(f"Tempo conhecido: {format_duration(known_seconds)}"),
                ft.Text(f"Início: {start.strftime('%H:%M')}"),
            ]
            if unknown_count:
                lines.append(ft.Text(f"{unknown_count} episódio(s) sem duração — término parcialmente desconhecido.", color=ft.Colors.ORANGE_300))
            else:
                lines.append(ft.Text(f"Término estimado: {estimated_end.strftime('%H:%M')}"))
                lines.append(ft.Text("Estimativa em velocidade normal; pausas e interrupções não estão incluídas.", size=11))
            episode_lines = []
            for index, item in enumerate(items[:48], 1):
                label = item.get("episode_title") or item.get("file_name") or f"Episódio {index}"
                remaining = item.get("remaining_seconds")
                remaining_label = format_duration(remaining) if remaining is not None else "duração desconhecida"
                prefix = "Agora • " if item.get("is_current") else ""
                episode_lines.append(ft.Text(f"{prefix}{label} — {remaining_label}", size=11))
            lines.append(ft.Column(episode_lines, spacing=4, scroll=ft.ScrollMode.AUTO, height=min(320, max(160, len(episode_lines) * 26))))
            dialog.content = ft.Column(lines, tight=True, spacing=8, width=min(520, max(280, float(page.width or 480) - 48)))
            dialog.actions = [ft.TextButton("Fechar", on_click=lambda _: page.pop_dialog())]
            page.update()

        dialog = ft.AlertDialog(
            modal=True,
            title=ft.Text("Maratona"),
            content=ft.Row([ft.ProgressRing(), ft.Text("Calculando…")], tight=True),
            actions=[ft.TextButton("Cancelar", on_click=lambda _: page.pop_dialog())],
        )
        page.show_dialog(dialog)
        page.run_task(load_and_show)
    def navigate_details(anime, on_back=None):
        previous = navigation.current
        anime_id = (anime or {}).get("id") if isinstance(anime, dict) else None
        current_id = (current[0] or {}).get("id") if isinstance(current[0], dict) else None
        if navigation.current == "details" and anime_id == current_id:
            performance.counter("navigation.duplicate_details_ignored")
            logger.info("[NAV] duplicate Details navigation ignored anime_id=%s", anime_id)
            return
        with performance.interaction("open_details", source=previous, target="details",
                                      metadata={"anime_id": anime_id}):
            current[0] = anime
            _drop_screen_cache("details")
            if navigation.current != "details":
                navigation.push("details")
            render_current(reason="open_details")
            persist_navigation_state()
    async def refresh_current_details():
        """Reload the current Details anime from the canonical catalog by anime id."""
        refresh_started = performance.now()
        anime_id = current[0].get("id") if current[0] else None
        details_token = details_instance_generation[0]
        if navigation.current != "details" or anime_id is None:
            return
        catalog = await asyncio.to_thread(library.catalog)
        if (
            navigation.current != "details"
            or details_instance_generation[0] != details_token
            or (current[0] or {}).get("id") != anime_id
        ):
            logger.info("[DETAILS] stale refresh ignored anime_id=%s token=%s", anime_id, details_token)
            return
        current[0] = next((item for item in catalog if item["id"] == anime_id), current[0])
        render_current(force=True, reason="details_refresh")
        performance.event("details.refresh", duration_ms=(performance.now()-refresh_started)*1000.0,
                          screen="details", metadata={"anime_id": anime_id})
    async def refresh_current_metadata(e=None):
        """Refresh only editorial metadata; never rescans or mutates playback state."""
        metadata_started = performance.now()
        request_id = str(uuid.uuid4())
        anime = current[0] or {}
        anime_id = anime.get("id")
        lookup = (anime.get("meta") or {}).get("lookup_title")
        title = anime.get("main_title") or (anime.get("meta") or {}).get("title") or "Anime local"
        logger.info(
            "METADATA_ACTION_START requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=details",
            request_id,
            anime_id or "-",
            lookup or "-",
            (anime.get("meta") or {}).get("anilist_id") or "-",
        )
        if not lookup:
            return
        if not settings.get("metadata.anilist_enabled"):
            page.snack_bar = ft.SnackBar(ft.Text("AniList está desativado nas configurações."))
            page.snack_bar.open = True
            safe_update()
            return
        try:
            logger.info(
                "METADATA_ACTION_LOOKUP requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=details",
                request_id,
                anime_id or "-",
                lookup,
                (anime.get("meta") or {}).get("anilist_id") or "-",
            )
            await asyncio.to_thread(
                library.refresh_metadata,
                lookup,
                title,
                force=True,
                local_anime_id=anime_id,
                request_id=request_id,
            )
            logger.info(
                "METADATA_ACTION_CATALOG_REFRESH requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=details",
                request_id,
                anime_id or "-",
                lookup,
                (anime.get("meta") or {}).get("anilist_id") or "-",
            )
            if navigation.current != "details" or (current[0] or {}).get("id") != anime_id:
                logger.info("[METADATA] stale refresh result ignored anime_id=%s request_id=%s", anime_id, request_id)
                return
            await refresh_current_details()
            logger.info(
                "METADATA_ACTION_UI_COMMIT requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=details",
                request_id,
                anime_id or "-",
                lookup,
                (anime.get("meta") or {}).get("anilist_id") or "-",
            )
            performance.event("details.metadata_refresh", duration_ms=(performance.now()-metadata_started)*1000.0,
                              screen="details", status="ok", metadata={"anime_id": anime_id})
            page.snack_bar.open = True
            safe_update()
        except Exception as exc:
            logger.exception("[METADATA] refresh failed anime_id=%s request_id=%s", anime_id, request_id)
            logger.info(
                "METADATA_ACTION_ERROR requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=details error=%s",
                request_id,
                anime_id or "-",
                lookup or "-",
                (anime.get("meta") or {}).get("anilist_id") or "-",
                exc,
            )
            page.snack_bar = ft.SnackBar(ft.Text("Não foi possível atualizar a metadata agora."))
            page.snack_bar.open = True
            safe_update()
    def on_catalog_changed(*, refresh_details=True, refresh_request_id=None):
        compose_library_bridge.request_publish("catalog_changed")
        catalog_started = performance.now()
        schedule_thumbnail_reconciliation("catalog_changed")
        diagnostics.record("UI_REFRESHED", result="catalog_changed", source=navigation.current)
        # Home/Organize keep their cached control tree across Details/Player.
        # Refresh their current dataset in place instead of rebuilding the whole
        # screen and losing its viewport/window state.
        current_refresh_request_id = home_refresh_context.get("request_id")
        refresh_matches = (
            home_refresh_context["active"]
            and home_refresh_context["scan_terminal"]
            and refresh_request_id is not None
            and current_refresh_request_id == refresh_request_id
        )
        if refresh_matches:
            refresh_id = home_refresh_context.get("refresh_id")
            refresh_source = home_refresh_context.get("source") or "button"
            _set_home_refresh_phase(
                "CATALOG_UPDATING",
                request_id=refresh_request_id,
                reason="catalog_changed",
            )
            diagnostics.record(
                "HOME_REFRESH_DB_UPDATED",
                refresh_id=refresh_id,
                request_id=refresh_request_id,
                source=refresh_source,
                screen=navigation.current,
            )
            home_refresh_context["db_updated"] = True
            # Whether Home is mounted or not, the refreshed catalog remains
            # pending until the current Home control tree has consumed it.
            home_state["_manual_refresh_pending"] = True
        if navigation.current == "home":
            refresh = home_state.get("_refresh_from_catalog")
            if callable(refresh):
                refresh()
                return
        if navigation.current == "organize":
            refresh = organize_state.get("_refresh_from_catalog")
            if callable(refresh):
                refresh()
                return
        if navigation.current == "details":
            if not refresh_details:
                return
            # Details must rehydrate from the canonical catalog by anime_id.
            # The async refresh already guards against navigation/detail-instance
            # changes, so stale catalog snapshots cannot replace a newer screen.
            page.run_task(refresh_current_details)
            performance.event(
                "ui.catalog_changed.details_refresh_scheduled",
                screen="details",
                metadata={"refresh_details": refresh_details},
            )
            return
        _drop_screen_cache(navigation.current)
        render_current(reason="catalog_changed")
        performance.event("ui.catalog_changed", duration_ms=(performance.now()-catalog_started)*1000.0,
                          screen=navigation.current, metadata={"refresh_details": refresh_details})

    def apply_settings_runtime(key, _value):
        setting_key = str(key)
        compose_settings_bridge.request_publish("setting_changed")
        if setting_key == "appearance.theme":
            # Theme changes invalidate only Python/Flet control trees. Navigation,
            # query/filter state, scroll snapshots and all domain/storage/player
            # state remain owned by their existing controllers.
            apply_page_theme(page, settings.get("appearance.theme"))
            _invalidate_cached_view(home_state, "home")
            _invalidate_cached_view(organize_state, "organize")
            _clear_screen_cache()
            render_current(force=True, reason="theme_changed")
            return
        library.configure_settings(settings)
        if setting_key.startswith(("appearance.", "library.")):
            _invalidate_cached_view(home_state, "home")
            _invalidate_cached_view(organize_state, "organize")
            current_route = navigation.current
            if current_route in {"home", "organize"}:
                render_current(reason="runtime_setting_changed")

    def handle_platform_brightness_change(_event=None):
        if settings.get("appearance.theme") != "system":
            return
        apply_page_theme(page, "system")
        _invalidate_cached_view(home_state, "home")
        _invalidate_cached_view(organize_state, "organize")
        _clear_screen_cache()
        render_current(force=True, reason="platform_brightness")
    async def remove_folder(reference):
        if scan_coordinator.active or saf_selection.pending:
            page.snack_bar = ft.SnackBar(ft.Text("Aguarde a atualização ou a seleção de pasta terminar antes de remover uma pasta."))
            page.snack_bar.open = True
            safe_update()
            return
        folder = next((item for item in store.folders() if item.get("path") == reference), None)
        if folder and folder.get("kind") == "saf" and bridge.available:
            try:
                pending_folder_removals.add(reference)
                await bridge.release_tree(reference)
                page.snack_bar = ft.SnackBar(ft.Text("Liberando a permissão da pasta…"))
                page.snack_bar.open = True
                safe_update()
            except Exception as exc:
                pending_folder_removals.discard(reference)
                page.snack_bar = ft.SnackBar(ft.Text(f"Não foi possível liberar a pasta: {exc}"))
                page.snack_bar.open = True
                safe_update()
            return
        store.remove_folder(reference)
        on_catalog_changed()
    async def resolve_match(lookup_title, anilist_id):
        request_id = str(uuid.uuid4())
        local_row = store.anime_metadata(lookup_title) or {}
        anime_id = local_row.get("id")
        logger.info(
            "METADATA_ACTION_START requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=settings",
            request_id, anime_id or "-", lookup_title, anilist_id,
        )
        try:
            await asyncio.to_thread(
                library.resolve_match,
                lookup_title,
                anilist_id,
                local_anime_id=anime_id,
                request_id=request_id,
            )
            logger.info(
                "METADATA_ACTION_CATALOG_REFRESH requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=settings",
                request_id, anime_id or "-", lookup_title, anilist_id,
            )
            on_catalog_changed(refresh_details=False)
            logger.info(
                "METADATA_ACTION_UI_COMMIT requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=settings",
                request_id, anime_id or "-", lookup_title, anilist_id,
            )
            page.snack_bar = ft.SnackBar(ft.Text("Associação AniList salva e catálogo local preservado."))
            page.snack_bar.open = True
            refresh_settings_if_active()
        except Exception as exc:
            logger.exception("[METADATA] manual resolve failed request_id=%s", request_id)
            logger.info(
                "METADATA_ACTION_ERROR requestId=%s animeId=%s lookupTitle=%s anilistId=%s screen=settings error=%s",
                request_id, anime_id or "-", lookup_title, anilist_id, exc,
            )
            page.snack_bar = ft.SnackBar(ft.Text(str(exc)))
            page.snack_bar.open = True
            safe_update()

    async def create_backup():
        started = time.monotonic()
        raw = await asyncio.to_thread(backup_service.create_backup_bytes)
        diagnostics.record(
            "BACKUP_CREATED",
            result="success",
            backup_duration_ms=int((time.monotonic() - started) * 1000),
        )
        return raw

    async def inspect_backup(raw):
        return await asyncio.to_thread(backup_service.inspect_bytes, raw)

    async def restore_backup(raw):
        reserved = await scan_coordinator.begin_exclusive("restore")
        if not reserved:
            raise BackupError(
                "SCAN_IN_PROGRESS",
                "Finalize a atualização da biblioteca antes de restaurar um backup.",
            )

        try:
            def run_restore():
                with library._metadata_lock:
                    library.artwork.invalidate_generation("restore_started")
                    try:
                        result = backup_service.restore_bytes(raw)
                    except Exception:
                        library.artwork.invalidate_generation("restore_failed")
                        raise
                    library.artwork.invalidate_generation("restore_completed")
                    return result

            started = time.monotonic()
            result = await asyncio.to_thread(run_restore)
            diagnostics.record(
                "RESTORE_COMPLETED",
                result="success",
                restore_duration_ms=int((time.monotonic() - started) * 1000),
                counts=(result.get("preview") or {}).get("counts") or {},
            )
        finally:
            await scan_coordinator.end_exclusive()

        on_catalog_changed()
        return result

    async def request_restore_reconciliation():
        if scan_coordinator.active:
            return {"accepted": False, "message": "scan_already_running"}
        return await scan_coordinator.request_restore_reconciliation(
            reason="user_requested_post_restore",
        )

    async def export_diagnostics():
        raw = await asyncio.to_thread(
            diagnostic_service.diagnostic_bytes,
            storage_snapshot=storage_capabilities[0],
            scan_snapshot=scan_state[0],
            text=False,
        )
        return raw

    async def integrity_check():
        return await asyncio.to_thread(
            diagnostic_service.report,
            storage_snapshot=storage_capabilities[0],
            scan_snapshot=scan_state[0],
        )

    def account(): return store.account()
    def navigate_settings():
        previous = navigation.current
        if navigation.current == "settings" and not navigation.settings_path:
            performance.counter("navigation.duplicate_settings_ignored")
            logger.info("[NAV] duplicate Settings navigation ignored")
            return
        with performance.interaction("open_settings", source=previous, target="settings"):
            if navigation.current != "settings":
                navigation.push("settings")
            _drop_screen_cache("settings")
            render_current(reason="open_settings")
            persist_navigation_state()
            if bridge.available:
                diagnostics.record("PERMISSION_CHECK", source="android")
                page.run_task(bridge.check_storage_access)
                if not navigation.settings_path:
                    page.run_task(_show_compose_settings)
    def navigate_settings_category(label):
        if str(label or "").strip() == "Armazenamento" and bridge.available:
            logger.info("[COMPOSE_STORAGE] opening native storage settings")
            page.run_task(bridge.open_storage_settings)
            return
        previous = navigation.current
        with performance.interaction("settings_category", source=previous, target="settings",
                                      metadata={"category": label}):
            if navigation.current != "settings":
                navigation.push("settings")
            navigation.push_settings(label)
            _drop_screen_cache("settings")
            render_current(reason="open_settings_category")
            persist_navigation_state()

    def close_home_search():
        if not home_state.get("search_visible"):
            return False
        home_state["search_visible"] = False
        home_state["query"] = ""
        _invalidate_cached_view(home_state, "home")
        logger.info("[NAV] SEARCH_BACK consumed on Home")
        render_current(reason="home_search_closed")
        persist_navigation_state()
        return True

    def navigate_back(source="unknown"):
        # One user Back gesture/button owns one logical operation. This protects
        # against Android + Flutter delivering the same physical Back twice.
        back_policy_started = performance.now()
        now = time.monotonic()
        route_before = navigation.current
        if now - back_state["last_at"] < BACK_DEBOUNCE_SECONDS:
            logger.info(
                "[NAV] duplicate BACK suppressed source=%s route=%s delta_ms=%.0f",
                source, route_before, (now - back_state["last_at"]) * 1000,
            )
            performance.event("interaction.back", duration_ms=(performance.now()-back_policy_started)*1000.0,
                              status="duplicate_suppressed", screen=route_before,
                              metadata={"source": source, "from": route_before})
            return
        back_state.update(last_at=now, last_action=source)
        logger.info("[NAV] BACK received source=%s route=%s", source, route_before)

        try:
            dialog = page.pop_dialog()
        except Exception as exc:
            logger.exception(
                "[NAV] DIALOG_BACK lookup failed",
                extra={"screen": route_before, "requestId": "-", "event": source},
            )
            dialog = None
        if dialog is not None:
            logger.info("[NAV] DIALOG_BACK source=%s route=%s", source, route_before)
            performance.event("interaction.back", duration_ms=(performance.now()-back_policy_started)*1000.0,
                              status="dialog", screen=route_before,
                              metadata={"source": source, "from": route_before})
            safe_update()
            return

        # Search is a transient Home state, not a second route. Close it before
        # delegating Back to the top-level NavigationController.
        if route_before == "home" and close_home_search():
            performance.event("interaction.back", duration_ms=(performance.now()-back_policy_started)*1000.0,
                              status="search_closed", screen=route_before,
                              metadata={"source": source, "from": route_before})
            return

        action = navigation.back()
        performance.event("interaction.back", duration_ms=(performance.now()-back_policy_started)*1000.0,
                          screen=navigation.current,
                          metadata={"source": source, "from": route_before, "action": action, "to": navigation.current})
        logger.info(
            "NAV_BACK_POLICY duration_ms=%s source=%s from=%s action=%s",
            int((performance.now() - back_policy_started) * 1000),
            source,
            route_before,
            action,
        )
        back_state["navigation_count"] += 1
        navigation_event_id = back_state["navigation_count"]
        logger.info(
            "BACK_NAVIGATION_EXECUTED id=%s source=%s from=%s action=%s to=%s",
            navigation_event_id, source, route_before, action, navigation.current,
        )
        logger.info(
            "[NAV] NAVIGATE_BACK source=%s from=%s action=%s to=%s",
            source, route_before, action, navigation.current,
        )
        if action in {"previous", "settings_inner"}:
            # Settings content is rebuilt whenever its nested path changes, while
            # top-level screens remain cached for scroll/filter/search continuity.
            # A dialog-only Back was already returned above and therefore does not
            # invalidate the active Settings task generation.
            if route_before == "settings" and (
                action == "settings_inner" or navigation.current != "settings"
            ):
                settings_tasks.invalidate()
            if action == "settings_inner":
                _drop_screen_cache("settings")
            elif navigation.current == "settings":
                _drop_screen_cache("settings")
            # Details can mutate favorite/pin/progress state in LibraryStore while
            # Organize is cached for scroll/filter continuity. Refresh only when
            # returning to Organize so its collection reflects durable state
            # without triggering a scan or permission flow.
            if navigation.current == "organize":
                _drop_screen_cache("organize")
            render_current(reason="back")
            if navigation.current == "library" and route_before != "library":
                page.run_task(_show_compose_library)
            elif (
                navigation.current == "settings"
                and not navigation.settings_path
                and route_before == "settings"
            ):
                page.run_task(_show_compose_settings)
            persist_navigation_state()
        elif action == "prompt_exit":
            persist_navigation_state()
            page.snack_bar=ft.SnackBar(ft.Text("Pressione voltar novamente para sair"))
            page.snack_bar.open=True
            safe_update()
        elif action == "exit":
            logger.info("[NAV] NAVIGATE_BACK exit source=%s", source)
            clear_persisted_navigation_state()
            page.window.close()
    page.on_view_pop = handle_flet_view_pop
    try:
        page.on_platform_brightness_change = handle_platform_brightness_change
    except Exception as exc:
        logger.warning("[FLET] platform brightness callback unavailable: %s", exc)
    def refresh_settings_if_active():
        if navigation.current == "settings":
            render_current(force=True, reason="settings_refresh")
    async def add_folder(_=None):
        # A scan already running must not block the user from choosing another
        # folder. ScanCoordinator already queues/coalesces the follow-up rescan.
        if scan_coordinator.exclusive or saf_selection.pending:
            return False
        if not saf_selection.begin():
            return False
        if compose_library_bridge.enabled:
            compose_library_bridge.request_publish("saf_selection_started")
        try:
            await bridge.select_tree()
            return True
        except Exception as exc:
            saf_selection.finish()
            page.snack_bar=ft.SnackBar(ft.Text(str(exc))); page.snack_bar.open=True; safe_update()
            raise
    async def check_video_access(_=None):
        if not bridge.available:
            return
        logger.info("[STORAGE] action=check_storage_access python_callback=dispatch")
        await bridge.check_storage_access()

    async def request_video_access(_=None):
        if not bridge.available:
            return
        logger.info("[STORAGE] action=request_media_access python_callback=dispatch")
        await bridge.request_media_access()

    async def open_broad_storage_access(_=None):
        if not bridge.available:
            return
        logger.info("[STORAGE] action=broad_storage python_callback=dispatch")
        await bridge.open_broad_storage_settings()

    thumbnail_requests = set()
    thumbnail_request_started_at = {}
    thumbnail_latest_key_by_uri = {}
    thumbnail_latest_at = {}
    thumbnail_completed_request_by_key = {}
    thumbnail_retry_counts = {}
    thumbnail_pending = {}
    thumbnail_queue = asyncio.PriorityQueue(maxsize=128)
    thumbnail_queue_sequence = 0
    thumbnail_dispatch_task = None
    thumbnail_reconciliation_task = None
    thumbnail_reconciliation_pending = False
    thumbnail_dispatch_slots = asyncio.Semaphore(4)

    def _prune_thumbnail_requests():
        now = time.monotonic()
        for key, started_at in list(thumbnail_request_started_at.items()):
            if now - started_at > 180.0:
                thumbnail_request_started_at.pop(key, None)
                thumbnail_requests.discard(key)
                thumbnail_pending.pop(key, None)
                uri = key[0]
                if thumbnail_latest_key_by_uri.get(uri) == key:
                    thumbnail_latest_key_by_uri.pop(uri, None)
                    thumbnail_latest_at.pop(uri, None)
        if len(thumbnail_latest_at) > 2048:
            oldest = sorted(thumbnail_latest_at.items(), key=lambda item: item[1])[:512]
            for uri, _ in oldest:
                thumbnail_latest_at.pop(uri, None)
                thumbnail_latest_key_by_uri.pop(uri, None)
        if len(thumbnail_completed_request_by_key) > 2048:
            for key in list(thumbnail_completed_request_by_key)[:512]:
                thumbnail_completed_request_by_key.pop(key, None)
        if len(thumbnail_retry_counts) > 2048:
            for key in list(thumbnail_retry_counts)[:512]:
                thumbnail_retry_counts.pop(key, None)

    def _thumbnail_key(episode):
        return (
            str(episode.get("path") or "").strip(),
            int(episode.get("file_size") or 0),
            int(episode.get("modified_at") or 0),
            str(episode.get("media_identity") or "").strip(),
        )

    def _thumbnail_priority(item, requested_priority):
        if requested_priority is not None:
            return int(requested_priority)
        return 150 if float(item.get("last_played_at") or 0) > 0 else 100

    def _enqueue_thumbnail_item(key, item, priority):
        nonlocal thumbnail_queue_sequence
        existing = thumbnail_pending.get(key)
        if existing is not None and priority <= existing["priority"]:
            return True
        if existing is not None:
            thumbnail_pending.pop(key, None)
        while thumbnail_queue.full():
            lowest_key = None
            lowest_rank = None
            for candidate_key, pending in thumbnail_pending.items():
                rank = (pending["priority"], pending["sequence"])
                if lowest_rank is None or rank < lowest_rank:
                    lowest_rank = rank
                    lowest_key = candidate_key
            if lowest_key is None or int(priority) <= int(lowest_rank[0]):
                return False
            thumbnail_pending.pop(lowest_key, None)
            thumbnail_requests.discard(lowest_key)
            thumbnail_request_started_at.pop(lowest_key, None)
            if thumbnail_latest_key_by_uri.get(lowest_key[0]) == lowest_key:
                thumbnail_latest_key_by_uri.pop(lowest_key[0], None)
                thumbnail_latest_at.pop(lowest_key[0], None)
            performance.counter("artwork.thumbnail.queue_evicted")
        thumbnail_queue_sequence += 1
        entry = {"priority": int(priority), "sequence": thumbnail_queue_sequence}
        thumbnail_pending[key] = entry
        thumbnail_requests.add(key)
        thumbnail_request_started_at[key] = time.monotonic()
        thumbnail_queue.put_nowait((-int(priority), thumbnail_queue_sequence, key, dict(item)))
        performance.gauge("artwork.thumbnail.queue_depth", thumbnail_queue.qsize())
        return True

    async def _thumbnail_dispatcher():
        nonlocal thumbnail_dispatch_task
        try:
            while ui_alive[0]:
                _, sequence, key, item = await thumbnail_queue.get()
                try:
                    current = thumbnail_pending.get(key)
                    if current is None or current["sequence"] != sequence:
                        continue
                    thumbnail_pending.pop(key, None)
                    if thumbnail_latest_key_by_uri.get(key[0]) not in (None, key):
                        thumbnail_requests.discard(key)
                        thumbnail_request_started_at.pop(key, None)
                        continue
                    await thumbnail_dispatch_slots.acquire()

                    async def send(item=item, key=key):
                        try:
                            await bridge.request_thumbnail(
                                str(item.get("path") or ""),
                                key[1],
                                key[2],
                                key[3],
                            )
                            performance.counter("artwork.thumbnail.dispatched")
                        except Exception as exc:
                            thumbnail_requests.discard(key)
                            thumbnail_request_started_at.pop(key, None)
                            if thumbnail_latest_key_by_uri.get(key[0]) == key:
                                count = int(thumbnail_retry_counts.get(key, 0))
                                if count < 2:
                                    thumbnail_retry_counts[key] = count + 1
                                    delay = 1.0 if count == 0 else 5.0
                                    async def retry_later():
                                        await asyncio.sleep(delay)
                                        if ui_alive[0]:
                                            request_missing_thumbnail(item, priority=200)
                                    asyncio.create_task(retry_later())
                                    performance.counter("artwork.thumbnail.retry_scheduled")
                                else:
                                    thumbnail_retry_counts.pop(key, None)
                                    diagnostics.record("THUMBNAIL_ERROR", request_id="-", result="COMMAND_FAILED_TERMINAL", error=str(exc))
                            logger.debug("[ARTWORK] native thumbnail request failed: %s", exc)
                        finally:
                            thumbnail_dispatch_slots.release()

                    asyncio.create_task(send())
                finally:
                    thumbnail_queue.task_done()
                    performance.gauge("artwork.thumbnail.queue_depth", thumbnail_queue.qsize())
        except asyncio.CancelledError:
            pass
        finally:
            thumbnail_dispatch_task = None

    def _ensure_thumbnail_dispatcher():
        nonlocal thumbnail_dispatch_task
        if thumbnail_dispatch_task is None or thumbnail_dispatch_task.done():
            thumbnail_dispatch_task = asyncio.create_task(_thumbnail_dispatcher())

    def request_missing_thumbnail(item, *, priority=None):
        thumbnail_started = performance.now()
        performance.counter("artwork.thumbnail.request")
        if not bridge.available or not isinstance(item, dict):
            performance.counter("artwork.thumbnail.rejected")
            return False
        episode = item if item.get("path") else item.get("current_episode") or {}
        path_ref = str(episode.get("path") or "").strip()
        if not path_ref or episode.get("missing"):
            return False
        _prune_thumbnail_requests()
        key = _thumbnail_key(episode)
        if key in thumbnail_requests and key not in thumbnail_pending:
            return True
        previous = thumbnail_latest_key_by_uri.get(path_ref)
        if previous is not None and previous != key:
            if thumbnail_pending.pop(previous, None) is not None:
                thumbnail_requests.discard(previous)
                thumbnail_request_started_at.pop(previous, None)
            thumbnail_latest_at[path_ref] = time.monotonic()
        thumbnail_latest_key_by_uri[path_ref] = key
        thumbnail_latest_at[path_ref] = time.monotonic()
        try:
            resolved_exact = library.artwork.get(
                "episode",
                episode.get("id"),
                "episode_thumbnail",
                allow_network=False,
            )
            exact_ready = bool(
                resolved_exact
                and resolved_exact.get("artwork_type") == "episode_thumbnail"
                and not resolved_exact.get("fallback")
                and resolved_exact.get("local_path")
                and os.path.isfile(str(resolved_exact.get("local_path")))
            )
        except Exception:
            exact_ready = False
        if exact_ready:
            thumbnail_requests.discard(key)
            thumbnail_pending.pop(key, None)
            thumbnail_request_started_at.pop(key, None)
            thumbnail_retry_counts.pop(key, None)
            thumbnail_latest_key_by_uri.pop(path_ref, None)
            thumbnail_latest_at.pop(path_ref, None)
            performance.counter("artwork.thumbnail.cache_hit")
            return True
        if key in thumbnail_pending:
            return True
        priority_value = _thumbnail_priority(episode, priority)
        try:
            accepted = _enqueue_thumbnail_item(key, episode, priority_value)
        except (asyncio.QueueFull, RuntimeError):
            accepted = False
        if not accepted:
            performance.counter("artwork.thumbnail.queue_deferred")
            return False
        performance.event(
            "artwork.thumbnail",
            duration_ms=(performance.now()-thumbnail_started)*1000.0,
            status="queued",
            screen=navigation.current,
            metadata={"path": path_ref, "media_identity": key[3], "priority": priority_value},
        )
        _ensure_thumbnail_dispatcher()
        return True

    async def reconcile_missing_thumbnails(reason="catalog_changed"):
        nonlocal thumbnail_reconciliation_pending, thumbnail_reconciliation_task
        thumbnail_reconciliation_pending = False
        try:
            if not bridge.available:
                return
            cursor = 0
            while ui_alive[0]:
                rows = await asyncio.to_thread(library.thumbnail_candidates, after_id=cursor, limit=128)
                if not rows:
                    break
                for item in rows:
                    while ui_alive[0] and bridge.available and not request_missing_thumbnail(item, priority=100):
                        await asyncio.sleep(0.10)
                    cursor = max(cursor, int(item.get("id") or 0))
                if len(rows) < 128:
                    break
        except Exception:
            logger.exception("[ARTWORK] thumbnail reconciliation failed reason=%s", reason)
        finally:
            thumbnail_reconciliation_task = None
            if thumbnail_reconciliation_pending and ui_alive[0]:
                schedule_thumbnail_reconciliation("pending")

    def schedule_thumbnail_reconciliation(reason="catalog_changed"):
        nonlocal thumbnail_reconciliation_pending, thumbnail_reconciliation_task
        thumbnail_reconciliation_pending = True
        if thumbnail_reconciliation_task is None or thumbnail_reconciliation_task.done():
            thumbnail_reconciliation_task = asyncio.create_task(reconcile_missing_thumbnails(reason))

    def storage_state():
        caps = storage_capabilities[0]
        return storage_access_state(
            caps.media_read_state,
            caps.broad_storage_state == "available",
            bool(caps.saf_roots),
            dismissed=storage_onboarding["dismissed"],
        )

    def apply_storage_capabilities(payload):
        raw = payload.get("capabilities") if isinstance(payload, dict) else None
        if isinstance(raw, dict):
            storage_capabilities[0] = StorageCapabilities.from_native(raw)
        elif isinstance(payload, dict) and ("mediaReadState" in payload or "broadStorageState" in payload):
            storage_capabilities[0] = StorageCapabilities.from_native(payload)
        if compose_library_bridge.enabled:
            compose_library_bridge.request_publish("storage_capabilities_changed")
        if compose_settings_bridge.enabled:
            compose_settings_bridge.request_publish("storage_capabilities_changed")

    def update_saf_capabilities(current_uris):
        current = storage_capabilities[0]
        roots = dedupe_saf_roots(current_uris)
        scanners = set(current.scanner_capabilities)
        if roots:
            scanners.add("saf")
        else:
            scanners.discard("saf")
        storage_capabilities[0] = StorageCapabilities(
            media_read_state=current.media_read_state,
            broad_storage_state=current.broad_storage_state,
            saf_roots=roots,
            removable_volumes=current.removable_volumes,
            scanner_capabilities=frozenset(scanners),
            reconciliation_capabilities=current.reconciliation_capabilities,
            lifecycle_state=current.lifecycle_state,
            api=current.api,
        )
        if compose_library_bridge.enabled:
            compose_library_bridge.request_publish("saf_inventory_changed")

    def maybe_show_storage_onboarding():
        """Ask for an explicit library folder, independent of media/broad grants."""
        if not bridge.available or storage_onboarding["dialog_open"] or storage_onboarding["waiting_for_result"]:
            return
        if not storage_capabilities[0].known:
            return
        if dedupe_saf_roots(storage_capabilities[0].saf_roots):
            return

        dialog = ft.AlertDialog(
            modal=True,
            title=ft.Text("Fonte da biblioteca necessária"),
            content=ft.Text(
                "Escolha uma pasta que pertença à sua biblioteca do ReiAnix. "
                "Somente vídeos dentro dessa pasta e de suas subpastas serão considerados."
            ),
        )

        async def choose_folder(_event):
            storage_onboarding["dialog_open"] = False
            storage_onboarding["waiting_for_result"] = True
            page.pop_dialog()
            try:
                started = await add_folder()
                if not started:
                    storage_onboarding["waiting_for_result"] = False
                    page.snack_bar = ft.SnackBar(
                        ft.Text("A seleção de pasta já está em andamento ou a biblioteca está sendo atualizada.")
                    )
                    page.snack_bar.open = True
                    safe_update()
            except Exception:
                storage_onboarding["waiting_for_result"] = False
                page.snack_bar = ft.SnackBar(
                    ft.Text("Não foi possível abrir o seletor de pasta.")
                )
                page.snack_bar.open = True
                safe_update()

        async def allow_media(_event):
            # Retained for lifecycle compatibility with the existing storage
            # permission flow. A MediaStore grant never creates a library source.
            storage_onboarding["dialog_open"] = False
            storage_onboarding["waiting_for_result"] = True
            page.pop_dialog()
            try:
                await request_video_access()
            except Exception:
                storage_onboarding["waiting_for_result"] = False
                page.snack_bar = ft.SnackBar(
                    ft.Text("Não foi possível abrir a solicitação de acesso.")
                )
                page.snack_bar.open = True
                safe_update()

        def cancel(_event):
            logger.info("[LIBRARY_SOURCE] onboarding cancelled")
            storage_onboarding["dialog_open"] = False
            storage_onboarding["dismissed"] = True
            page.pop_dialog()
            safe_update()

        dialog.actions = [
            ft.TextButton("CANCELAR", on_click=cancel),
            ft.TextButton("ESCOLHER PASTA", on_click=choose_folder),
        ]
        storage_onboarding["dialog_open"] = True
        logger.info("[LIBRARY_SOURCE] LIBRARY_SOURCE_INVALID reason=no_configured_library_source")
        page.show_dialog(dialog)
        safe_update()

    async def refresh_library(_=None, *, _home_refresh_context=None):
        if saf_selection.pending:
            return "Conclua ou cancele a seleção da pasta antes de atualizar a biblioteca.", False
        caps = storage_capabilities[0]
        if bridge.available and not caps.known:
            set_scan_state(ScanUiState.CHECKING, source="permissions")
            safe_update()
            await bridge.check_storage_access()
            return "Verificando as permissões do armazenamento…", True
        transition = await scan_coordinator.request(
            ScanOrigin.USER_REFRESH,
            source=None,
            full=False,
            reason="explicit_user_refresh",
        )
        if _home_refresh_context is not None:
            _home_refresh_context["request_id"] = transition.request_id
        if transition.kind == "ignored":
            return "Nenhuma fonte da biblioteca está configurada. Escolha uma pasta primeiro.", False
        if transition.kind == "blocked":
            return "Nenhuma fonte da biblioteca está configurada. Escolha uma pasta primeiro.", False
        if transition.kind == "deduped":
            return "Uma atualização da biblioteca já está em andamento.", True
        if transition.kind == "queued":
            return "Atualização enfileirada; a varredura atual será concluída primeiro.", True
        return "Atualização iniciada. Verificando as fontes locais…", True

    async def request_home_refresh(source="button"):
        refresh_source = str(source or "button").strip().casefold()
        if refresh_source not in {"button", "pull"}:
            refresh_source = "button"
        if home_refresh_context["active"]:
            diagnostics.record(
                "HOME_REFRESH_REJECTED",
                refresh_id=home_refresh_context.get("refresh_id"),
                source=refresh_source,
                transition_reason="already_refreshing",
            )
            performance.counter("home.refresh.rejected")
            return "Uma atualização da biblioteca já está em andamento.", True
        refresh_id = uuid.uuid4().hex
        home_refresh_context.update({
            "active": True,
            "state": "REFRESHING",
            "phase": "REQUESTED",
            "refresh_id": refresh_id,
            "request_id": None,
            "started_at": time.monotonic(),
            "scan_started": False,
            "scan_started_at": None,
            "scan_terminal": False,
            "db_updated": False,
            "source": refresh_source,
        })
        home_state["_manual_refresh_pending"] = False
        _publish_home_refresh_state("REFRESHING")
        _set_home_refresh_phase(
            "REQUESTED",
            request_id=None,
            reason=refresh_source,
        )
        diagnostics.record("HOME_REFRESH_REQUESTED", refresh_id=refresh_id, source=refresh_source)
        performance.counter("home.refresh.requested")
        try:
            message, waiting = await refresh_library(_home_refresh_context=home_refresh_context)
        except Exception:
            logger.exception("[HOME_REFRESH] request failed refreshId=%s", refresh_id)
            _fail_home_refresh("request_exception")
            return "Não foi possível atualizar a biblioteca agora.", False
        if waiting:
            diagnostics.record(
                "HOME_REFRESH_ACCEPTED",
                refresh_id=refresh_id,
                source=refresh_source,
                transition_reason="scan_coordinator_acceptance",
            )
            performance.counter("home.refresh.accepted")
            snapshot = scan_coordinator.snapshot
            if (
                scan_coordinator.active
                and not home_refresh_context["scan_started"]
                and snapshot.request_id == home_refresh_context.get("request_id")
                and snapshot.state in {ScanState.RUNNING, ScanState.CANCELLING}
            ):
                home_refresh_context["scan_started"] = True
                home_refresh_context["scan_started_at"] = time.monotonic()
                diagnostics.record(
                    "HOME_REFRESH_STARTED",
                    refresh_id=refresh_id,
                    request_id=snapshot.request_id,
                    source=refresh_source,
                    scan_source=snapshot.source or "all",
                )
                diagnostics.record(
                    "HOME_REFRESH_SCAN_STARTED",
                    refresh_id=refresh_id,
                    request_id=snapshot.request_id,
                    source=refresh_source,
                    scan_source=snapshot.source or "all",
                )
                performance.counter("home.refresh.started")
            return message, True
        diagnostics.record(
            "HOME_REFRESH_REJECTED",
            refresh_id=refresh_id,
            source=refresh_source,
            transition_reason=message or "coordinator_rejected",
        )
        performance.counter("home.refresh.rejected")
        _set_home_refresh_phase(
            "ERROR",
            request_id=home_refresh_context.get("request_id"),
            reason=message or "coordinator_rejected",
        )
        home_refresh_context["active"] = False
        home_refresh_context["request_id"] = None
        _publish_home_refresh_state("ERROR")
        resetter = home_state.get("_reset_refresh_state")
        if callable(resetter):
            resetter("ERROR", 1.6)
        return message, False

    async def refresh_home_library(_=None, *, source="button"):
        """Compatibility wrapper; Home enters refresh through request_home_refresh."""
        return await request_home_refresh(source=source)

    async def login(_=None):
        if account_state[0] in {"connecting", "awaiting_google", "disconnecting"}:
            return
        if bridge.available:
            if not GOOGLE_WEB_CLIENT_ID:
                set_account_state("configuration_required", "google_login_configuration_required")
                navigate_settings()
                page.snack_bar = ft.SnackBar(
                    ft.Text("Login Google não configurado neste APK. Configure um Web Client ID público antes de tentar novamente.")
                )
                page.snack_bar.open = True
                safe_update()
                return
            set_account_state("connecting", "google_login_requested")
            navigate_settings()
            try:
                await bridge.sign_in(GOOGLE_WEB_CLIENT_ID)
            except Exception as exc:
                set_account_state("error", "google_login_command_failed")
                logger.exception("[ACCOUNT] Google sign-in command failed: %s", exc)
                page.snack_bar = ft.SnackBar(ft.Text("Não foi possível iniciar o login Google."))
                page.snack_bar.open = True
                safe_update()
            return
        set_account_state("connecting", "google_login_requested")
        navigate_settings()
        if not GOOGLE_CLIENT_ID or not GOOGLE_REDIRECT_URL:
            set_account_state("error", "google_login_configuration_required")
            navigate_settings()
            page.snack_bar=ft.SnackBar(ft.Text("Configure REIFLIX_GOOGLE_CLIENT_ID e REIFLIX_GOOGLE_REDIRECT_URL para entrar com Google."))
            page.snack_bar.open=True; safe_update(); return
        provider=OAuthProvider(
            client_id=GOOGLE_CLIENT_ID,
            client_secret='',
            authorization_endpoint='https://accounts.google.com/o/oauth2/v2/auth',
            token_endpoint='https://oauth2.googleapis.com/token',
            redirect_url=GOOGLE_REDIRECT_URL,
            scopes=['openid','email','profile'],
            user_endpoint='https://openidconnect.googleapis.com/v1/userinfo',
            user_id_fn=lambda u:u.get('sub'),
            authorization_params={'access_type':'offline','prompt':'select_account'},
        )
        await page.login(provider,fetch_user=True)

    async def logout(_=None):
        if account_state[0] == "disconnecting":
            return
        set_account_state("disconnecting", "google_logout_requested")
        navigate_settings()
        try:
            if bridge.available:
                await bridge.sign_out()
            else:
                page.logout()
            store.clear_account()
            set_account_state("disconnected", "logout_completed")
            navigate_settings()
        except Exception as exc:
            set_account_state("error", "logout_failed")
            logger.exception("[ACCOUNT] Google sign-out failed: %s", exc)
            page.snack_bar = ft.SnackBar(ft.Text("Não foi possível encerrar a sessão Google com segurança."))
            page.snack_bar.open = True
            safe_update()

    async def switch_account(_=None):
        if account_state[0] in {"connecting", "awaiting_google", "disconnecting"}:
            return
        if store.account().get("email"):
            await logout()
            if account_state[0] != "disconnected":
                return
        await login()

    async def execute_account_action(action):
        if action == "login":
            await login()
        elif action == "logout":
            await logout()
        elif action == "switch":
            await switch_account()

    async def login_done(e):
        if e.error:
            set_account_state("error", "flet_login_error")
            page.snack_bar=ft.SnackBar(ft.Text(f'Não foi possível entrar: {e.error_description or e.error}')); page.snack_bar.open=True; safe_update(); return
        user=page.auth.user
        if user:
            store.save_account({'id':str(user.id),'name':str(user.get('name','')),'email':str(user.get('email','')),'picture':str(user.get('picture',''))})
            set_account_state("connected", "login_completed")
        navigate_settings()

    async def poll_native_bridge():
        async def ingest_native_batch(event_type, payload, event_request_id):
            source_map = {
                "saf_scan_batch": ("saf", "root"),
                "broad_storage_scan_batch": ("broad_storage", "volume"),
                "mediastore_scan_batch": ("mediastore", "volume"),
            }
            source_kind, default_scope_kind = source_map[event_type]
            documents = payload.get("documents") or []
            scope_kind = payload.get("scopeKind") or default_scope_kind
            scope_ref = payload.get("scopeRef") or payload.get("scope") or payload.get("volumeId") or event_type
            source_reference = (
                payload.get("treeUri")
                if source_kind == "saf"
                else payload.get("source")
                or ("broad-storage" if source_kind == "broad_storage" else "mediastore:external:video")
            )
            scope_scan_id = str(payload.get("scopeScanId") or payload.get("scanId") or "").strip()
            if scope_kind == "volume" and scope_ref and scope_scan_id and ":" not in scope_scan_id:
                scope_scan_id = f"{scope_scan_id}:{scope_ref}"
            result = await asyncio.to_thread(
                library.ingest_documents_batch,
                source_reference or source_kind,
                documents,
                source_kind=source_kind,
                scan_id=scope_scan_id or payload.get("scanId"),
                scope_kind=scope_kind,
                scope_ref=scope_ref,
                scan_generation=payload.get("scanGeneration"),
                generation_id=payload.get("generationId"),
                request_id=event_request_id,
                batch_id=payload.get("batchId"),
                batch_number=payload.get("batchNumber") or 0,
                batch_size=payload.get("batchSize"),
                folder_name=payload.get("name") or None,
                enforce_library_source=True,
            )
            diagnostics.record(
                "SCAN_BATCH",
                request_id=event_request_id,
                scan_id=scope_scan_id or payload.get("scanId"),
                source=source_kind,
                result="INGESTED" if not result.get("ignored") else "IGNORED",
                counts={
                    "batchId": payload.get("batchId"),
                    "batchNumber": payload.get("batchNumber") or 0,
                    "batchSize": payload.get("batchSize") or len(documents),
                    "processed": result.get("files", 0),
                    "discovered": len(documents),
                    "inserted": result.get("new", 0),
                    "updated": result.get("updated", 0),
                    "unchanged": result.get("unchanged", 0),
                    "duplicates": result.get("duplicates", 0),
                    "errors": len(result.get("errors") or []),
                    "elapsedMs": result.get("elapsed_ms", 0),
                },
            )
            return result

        poll_interval = 0.08
        while ui_alive[0]:
            try:
                mailbox_started = performance.now()
                mailbox_backlog_before = bridge.pending_count()
                events = bridge.drain()
                performance.gauge("android.mailbox.backlog", mailbox_backlog_before)
                performance.counter("android.mailbox.events_drained", len(events))
                failed_event_ids = set()
                seen_native_event_ids = set()
                for event in events:
                    try:
                        if not isinstance(event, dict):
                            continue
                        event["receivedAtMs"] = int(time.time() * 1000)
                        performance.record_native_event(event)
                        event_id = str(event.get('eventId') or '').strip()
                        if not event_id:
                            logger.error("[ANDROID] EVENT_REJECTED reason=missing_event_id type=%s", event.get('type'))
                            continue
                        if event_id in seen_native_event_ids:
                            performance.counter("android.events.duplicate")
                            logger.warning("[ANDROID] EVENT_DUPLICATE_IN_BATCH eventId=%s", event_id)
                            continue
                        seen_native_event_ids.add(event_id)
                        if store.has_native_event(event_id):
                            performance.counter("android.events.duplicate")
                            logger.info("[ANDROID] EVENT_DUPLICATE eventId=%s result=already_processed", event_id)
                            continue
                        event_type = event.get('type')
                        payload = event.get('payload')
                        if payload is None:
                            payload = {}
                        if not isinstance(payload, dict):
                            continue
                        # Resolve AndroidBridge command waiters only from the
                        # existing NativeMailbox event emitted by MainActivity.
                        # This makes launch acceptance distinct from real intent
                        # delivery; no secondary IPC channel is introduced.
                        bridge.observe_native_event(event)
                        event_request_id = event.get('requestId') or payload.get('requestId')
                        event_scan_id = payload.get('scanId') or event.get('scanId')
                        operation_state = str(
                            event.get('operationState')
                            or payload.get('operationState')
                            or ''
                        ).strip().upper()
                        if event_request_id and operation_state:
                            native_operation_states[str(event_request_id)] = operation_state
                            if len(native_operation_states) > 128:
                                native_operation_states.pop(next(iter(native_operation_states)))
                        if event_type == 'compose_settings_set':
                            setting_key = str(payload.get('key') or '').strip()
                            setting_value = payload.get('value')
                            request_id = str(event_request_id or payload.get('requestId') or '').strip()
                            supported_compose_settings = {
                                'app.confirm_destructive',
                                'appearance.theme',
                                'appearance.card_size',
                                'appearance.show_thumbnails',
                                *(
                                    key
                                    for key in settings.EXPORT_KEYS
                                    if key.startswith(("player.", "gestures.", "audio."))
                                ),
                            }
                            if setting_key not in supported_compose_settings:
                                logger.warning(
                                    "[COMPOSE_SETTINGS] write rejected key=%s requestId=%s",
                                    setting_key or '-',
                                    request_id or '-',
                                )
                            else:
                                try:
                                    normalized = await asyncio.to_thread(settings.set, setting_key, setting_value)
                                    apply_settings_runtime(setting_key, normalized)
                                    logger.info(
                                        "[COMPOSE_SETTINGS] setting persisted key=%s requestId=%s",
                                        setting_key,
                                        request_id or '-',
                                    )
                                except Exception as exc:
                                    logger.exception(
                                        "[COMPOSE_SETTINGS] setting write failed key=%s requestId=%s",
                                        setting_key or '-',
                                        request_id or '-',
                                    )

                        if event_type == 'compose_settings_action':
                            action = str(payload.get('action') or '').strip().lower()
                            request_id = str(event_request_id or payload.get('requestId') or '').strip()
                            if action == 'reset_player':
                                try:
                                    await asyncio.to_thread(settings.reset_category, "player")
                                    compose_settings_bridge.request_publish("player_settings_reset")
                                    logger.info(
                                        "[COMPOSE_SETTINGS] player settings reset requestId=%s",
                                        request_id or '-',
                                    )
                                except Exception:
                                    logger.exception(
                                        "[COMPOSE_SETTINGS] player settings reset failed requestId=%s",
                                        request_id or '-',
                                    )
                            else:
                                logger.warning(
                                    "[COMPOSE_SETTINGS] action rejected action=%s requestId=%s",
                                    action or '-',
                                    request_id or '-',
                                )

                        if event_type == 'compose_account_action':
                            action = str(payload.get('action') or '').strip().lower()
                            if action in {'login', 'logout', 'switch'}:
                                current_task = account_action_task[0]
                                if current_task is None or current_task.done():
                                    account_action_task[0] = page.run_task(execute_account_action, action)
                            else:
                                logger.warning("[COMPOSE_ACCOUNT] action rejected action=%s", action or "-")

                        if event_type == 'compose_settings_navigation':
                            destination = str(payload.get('destination') or '').strip().lower()
                            category = str(payload.get('category') or '').strip()
                            if destination == 'back':
                                navigate_back('compose_settings_back')
                            elif destination == 'category':
                                valid_categories = {
                                    'Conta', 'Geral', 'Aparência', 'Biblioteca', 'Player',
                                    'Gestos', 'Áudio e Legendas', 'Metadata', 'Artwork',
                                    'Armazenamento', 'Dados e Cache', 'Backup e Restauração',
                                    'Privacidade', 'Varredura', 'Diagnóstico', 'Sobre',
                                }
                                if category in valid_categories:
                                    navigate_settings_category(category)
                                else:
                                    logger.warning(
                                        "[COMPOSE_SETTINGS] navigation rejected category=%s",
                                        category or '-',
                                    )
                            else:
                                logger.warning(
                                    "[COMPOSE_SETTINGS] navigation ignored destination=%s",
                                    destination or '-',
                                )

                        if event_type == 'compose_library_navigation':
                            destination = str(payload.get('destination') or '').strip().lower()
                            if destination == 'back':
                                navigate_back('compose_library_back')
                            elif destination == 'details':
                                try:
                                    anime_id = int(payload.get('animeId') or 0)
                                except (TypeError, ValueError):
                                    anime_id = 0
                                if anime_id > 0:
                                    catalog = await asyncio.to_thread(library.catalog)
                                    anime = next(
                                        (item for item in (catalog or []) if int(item.get('id') or 0) == anime_id),
                                        None,
                                    )
                                    if anime is not None:
                                        navigate_details(anime)
                                    else:
                                        logger.warning(
                                            "[COMPOSE_LIBRARY] details request ignored; anime not found id=%s",
                                            anime_id,
                                        )
                                else:
                                    logger.warning("[COMPOSE_LIBRARY] details request rejected; invalid animeId")
                            else:
                                logger.warning(
                                    "[COMPOSE_LIBRARY] navigation event ignored destination=%s",
                                    destination or '-',
                                )

                        if event_type == 'compose_library_command':
                            action = str(payload.get('action') or '').strip().lower()
                            command_request_id = str(
                                event_request_id or payload.get('requestId') or ''
                            ).strip()
                            command_status = 'COMPLETED'
                            command_error = None
                            try:
                                if action == 'toggle_favorite':
                                    anime_id = int(payload.get('animeId') or 0)
                                    if anime_id <= 0:
                                        raise ValueError('animeId inválido.')
                                    existing = await asyncio.to_thread(store.catalog, anime_ids=[anime_id])
                                    if not existing:
                                        raise ValueError('Anime não encontrado.')
                                    await asyncio.to_thread(store.toggle_favorite, anime_id)
                                    compose_library_bridge.request_publish('compose_toggle_favorite')
                                    on_catalog_changed(refresh_details=False)
                                elif action == 'set_watched':
                                    episode_id = int(payload.get('episodeId') or 0)
                                    if episode_id <= 0:
                                        raise ValueError('episodeId inválido.')
                                    watched = bool(payload.get('watched'))
                                    updated = await asyncio.to_thread(
                                        store.set_watched,
                                        str(payload.get('uri') or '').strip(),
                                        watched,
                                        episode_id=episode_id,
                                    )
                                    if not updated:
                                        raise ValueError('Episódio não encontrado ou referência local incompatível.')
                                    compose_library_bridge.request_publish('compose_set_watched')
                                    on_catalog_changed(refresh_details=False)
                                elif action == 'select_saf':
                                    started = await add_folder()
                                    if not started:
                                        command_status = 'BLOCKED'
                                        command_error = (
                                            'A seleção de pasta já está em andamento ou a biblioteca '
                                            'está sendo atualizada.'
                                        )
                                    else:
                                        command_status = 'QUEUED'
                                elif action == 'refresh':
                                    source = str(payload.get('source') or '').strip() or None
                                    transition = await scan_coordinator.request(
                                        ScanOrigin.USER_REFRESH,
                                        source=source,
                                        full=False,
                                        reason='compose_refresh',
                                        request_id=command_request_id or None,
                                    )
                                    if transition.kind == 'blocked':
                                        command_status = 'BLOCKED'
                                        command_error = str(transition.message or transition.kind)
                                    elif not transition.accepted or transition.kind in {'failed', 'error'}:
                                        command_status = 'FAILED'
                                        command_error = str(transition.message or transition.kind)
                                    else:
                                        command_status = 'QUEUED'
                                elif action == 'open_media':
                                    episode_id = int(payload.get('episodeId') or 0)
                                    if episode_id <= 0:
                                        raise ValueError('episodeId inválido.')
                                    fresh_episode = await asyncio.to_thread(store.episode_by_id, episode_id)
                                    if not fresh_episode:
                                        raise ValueError('Episódio não encontrado.')
                                    path_ref = str(fresh_episode.get('path') or '').strip()
                                    if not path_ref or bool(fresh_episode.get('missing')):
                                        raise ValueError('Este episódio não possui uma mídia local disponível.')
                                    anime_id = fresh_episode.get('anime_id')
                                    title = (
                                        str(fresh_episode.get('episode_title') or '').strip()
                                        or str(fresh_episode.get('file_name') or '').strip()
                                        or 'Episódio'
                                    )
                                    try:
                                        progress_seconds = float(fresh_episode.get('progress') or 0.0)
                                    except (TypeError, ValueError):
                                        progress_seconds = 0.0
                                    command_status = 'QUEUED'
                                    play_episode(
                                        path_ref,
                                        title,
                                        progress_seconds=max(0.0, progress_seconds),
                                        episode_id=episode_id,
                                        anime_id=anime_id,
                                    )
                                else:
                                    command_status = 'FAILED'
                                    command_error = 'Comando de biblioteca Compose desconhecido.'
                            except Exception as exc:
                                command_status = 'FAILED'
                                command_error = str(exc)[:500]
                                logger.exception(
                                    "[COMPOSE_LIBRARY] command failed action=%s requestId=%s",
                                    action or '-',
                                    command_request_id or '-',
                                )
                            compose_library_bridge.write_command_result(
                                command_request_id,
                                action,
                                command_status,
                                error=command_error,
                            )
                            logger.info(
                                "[COMPOSE_LIBRARY] command=%s requestId=%s status=%s",
                                action or '-',
                                command_request_id or '-',
                                command_status,
                            )

                        player_event_types = {
                            "player_progress",
                            "player_paused",
                            "player_completed",
                            "player_exited",
                        }
                        if event_type in player_event_types and event_type != "player_exited":
                            callback_current, callback_reason = player_callback_is_current(
                                event_request_id,
                                payload,
                                require_active=bool(player_session_active["value"]),
                                episode_id=payload.get("episodeId") if event_type in {"player_progress", "player_paused", "player_completed"} else None,
                                media_id=payload.get("mediaId"),
                                anime_id=payload.get("animeId"),
                            )
                            if not callback_current:
                                performance.event(
                                    "MAILBOX_STALE_COMMAND_DISCARDED",
                                    screen=navigation.current,
                                    status="discarded",
                                    metadata={
                                        "request_id": event_request_id,
                                        "player_session_id": payload.get("playerSessionId") or payload.get("player_session_id"),
                                        "age_ms": max(
                                            0,
                                            int(time.time() * 1000)
                                            - int(event.get("createdAt") or payload.get("createdAt") or int(time.time() * 1000)),
                                        ),
                                        "reason": callback_reason,
                                    },
                                )
                                performance.event(
                                    "PLAYER_COMMAND_REJECTED",
                                    screen=navigation.current,
                                    status="rejected",
                                    metadata={"request_id": event_request_id, "reason": callback_reason},
                                )
                                continue
                        if event_type == 'diagnostic':
                            diagnostic_event = str(payload.get('event') or 'NATIVE_DIAGNOSTIC').strip()
                            if diagnostic_event.startswith(('COMMAND_', 'OPERATION_')):
                                logger.info(
                                    "[ANDROID] %s requestId=%s action=%s state=%s result=%s",
                                    diagnostic_event,
                                    event_request_id or "-",
                                    payload.get('action') or "-",
                                    operation_state or "-",
                                    payload.get('result') or "-",
                                )
                            diagnostic_event = str(payload.get('event') or 'NATIVE_DIAGNOSTIC').strip()
                            if diagnostic_event == "PLAYER_SESSION_INVALIDATED":
                                invalid_session = str(payload.get("playerSessionId") or "").strip()
                                invalid_activity = str(payload.get("activityInstanceId") or "").strip()
                                if (
                                    invalid_session
                                    and player_active_session_id["value"] == invalid_session
                                    and (
                                        not invalid_activity
                                        or player_active_activity_instance_id["value"] in (None, invalid_activity)
                                    )
                                ):
                                    invalidate_player_session(
                                        str(payload.get("reason") or "native_session_invalidated"),
                                        expected_session_id=invalid_session,
                                        expected_request_id=event_request_id or None,
                                    )
                                else:
                                    performance.event(
                                        "PLAYER_CALLBACK_STALE",
                                        screen=navigation.current,
                                        status="ignored",
                                        metadata={
                                            "request_id": event_request_id,
                                            "player_session_id": invalid_session,
                                            "activity_instance_id": invalid_activity,
                                            "current_player_session_id": player_active_session_id["value"],
                                            "current_activity_instance_id": player_active_activity_instance_id["value"],
                                            "reason": "stale_session_invalidated",
                                        },
                                    )
                            elif diagnostic_event == "PLAYER_SESSION_CREATED":
                                session_id = str(payload.get("playerSessionId") or payload.get("result") or "").strip()
                                activity_instance_id = str(payload.get("activityInstanceId") or "").strip()
                                if session_id:
                                    if player_session_active["value"] and player_active_session_id["value"] not in (None, session_id):
                                        performance.event(
                                            "PLAYER_CALLBACK_STALE",
                                            screen=navigation.current,
                                            status="ignored",
                                            metadata={
                                                "request_id": event_request_id,
                                                "player_session_id": session_id,
                                                "current_player_session_id": player_active_session_id["value"],
                                                "reason": "stale_session_created",
                                            },
                                        )
                                        performance.event(
                                            "MAILBOX_STALE_COMMAND_DISCARDED",
                                            screen=navigation.current,
                                            status="discarded",
                                            metadata={"request_id": event_request_id, "player_session_id": session_id, "reason": "stale_session_created"},
                                        )
                                        continue
                                    player_active_session_id["value"] = session_id
                                    if activity_instance_id:
                                        player_active_activity_instance_id["value"] = activity_instance_id
                                    player_active_request_id["value"] = event_request_id or player_active_request_id["value"]
                                    player_session_active["value"] = True
                                    player_active_player_generation["value"] = int(payload.get("playerGeneration") or player_active_player_generation["value"])
                                    performance.event(
                                        "PLAYER_SESSION_CREATED",
                                        screen=navigation.current,
                                        metadata={
                                            "request_id": event_request_id,
                                            "player_session_id": session_id,
                                            "player_generation": player_active_player_generation["value"],
                                        },
                                    )
                            elif diagnostic_event == "PLAYER_HANDOFF_DISPATCHED" and event_request_id:
                                session_id = str(payload.get("playerSessionId") or "").strip()
                                current_session_id = player_active_session_id["value"]
                                if (
                                    not session_id
                                    or not player_session_active["value"]
                                    or current_session_id != session_id
                                ):
                                    performance.event(
                                        "PLAYER_CALLBACK_STALE",
                                        screen=navigation.current,
                                        status="ignored",
                                        metadata={
                                            "request_id": event_request_id,
                                            "player_session_id": session_id,
                                            "current_player_session_id": current_session_id,
                                            "reason": "stale_handoff_dispatch",
                                        },
                                    )
                                    direction = str(payload.get("transitionDirection") or "").strip().upper()
                                    stale_event = "PREVIOUS_REQUEST_STALE" if direction == "PREVIOUS" else "NEXT_REQUEST_STALE"
                                    stale_rejected_event = "PLAYER_PREVIOUS_STALE_REJECTED" if direction == "PREVIOUS" else "PLAYER_NEXT_STALE_REJECTED"
                                    performance.event(
                                        stale_event,
                                        screen=navigation.current,
                                        status="rejected",
                                        metadata={
                                            "request_id": event_request_id,
                                            "player_session_id": session_id,
                                            "current_player_session_id": current_session_id,
                                            "reason": "stale_handoff_dispatch",
                                        },
                                    )
                                    performance.event(
                                        stale_rejected_event,
                                        screen=navigation.current,
                                        status="rejected",
                                        metadata={
                                            "request_id": event_request_id,
                                            "player_session_id": session_id,
                                            "current_player_session_id": current_session_id,
                                            "reason": "stale_handoff_dispatch",
                                        },
                                    )
                                    continue
                                player_active_request_id["value"] = event_request_id
                                performance.event(
                                    "PLAYER_COMMAND_ACCEPTED",
                                    screen=navigation.current,
                                    metadata={"request_id": event_request_id, "player_session_id": current_session_id, "reason": "handoff_dispatched"},
                                )
                            elif diagnostic_event == "PLAYER_ACTIVITY_RESULT":
                                controlled_result = bool(payload.get("controlled"))
                                result_activity_instance = str(payload.get("activityInstanceId") or "").strip()
                                result_is_current = (
                                    not result_activity_instance