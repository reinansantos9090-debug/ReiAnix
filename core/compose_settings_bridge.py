"""Derived IPC projection for the native Compose Settings surface.

SettingsStore remains the only persistence/source-of-truth boundary. This bridge
publishes a compact snapshot for Compose and never handles setting mutations.
"""
from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from pathlib import Path
from typing import Any, Callable


class ComposeSettingsBridge:
    SNAPSHOT_DIR_NAME = "reianix-compose"
    SNAPSHOT_FILE_NAME = "settings.json"
    SCHEMA_VERSION = 1

    CATEGORY_KEYS = {
        "Geral": ("app.",),
        "Aparência": ("appearance.",),
        "Biblioteca": ("library.",),
        "Player": ("player.",),
        "Gestos": ("gestures.",),
        "Áudio e Legendas": ("audio.",),
        "Metadata": ("metadata.",),
        "Artwork": ("artwork.",),
        "Dados e Cache": (),
        "Backup e Restauração": (),
        "Privacidade": (),
        "Varredura": (),
        "Diagnóstico": (),
        "Sobre": (),
    }

    def __init__(
        self,
        data_dir: str,
        settings,
        *,
        account_provider: Callable[[], dict[str, Any]] | None = None,
        account_state_provider: Callable[[], str] | None = None,
        storage_available: bool = False,
        storage_state_provider: Callable[[], Any] | None = None,
        enabled: bool = True,
    ):
        self.data_dir = Path(data_dir)
        self.settings = settings
        self.account_provider = account_provider
        self.account_state_provider = account_state_provider
        self.storage_available = bool(storage_available)
        self.storage_state_provider = storage_state_provider
        self.enabled = bool(enabled)
        self.snapshot_dir = self.data_dir / self.SNAPSHOT_DIR_NAME
        self.snapshot_path = self.snapshot_dir / self.SNAPSHOT_FILE_NAME
        self._requested_revision = 0
        self._publish_task: asyncio.Task[Any] | None = None
        self._last_published_revision = 0
        self._pending_reason_text = "unknown"
        if self.enabled:
            self.snapshot_dir.mkdir(parents=True, exist_ok=True)

    def set_storage_available(self, available: bool) -> None:
        self.storage_available = bool(available)

    def set_storage_state_provider(self, provider: Callable[[], Any] | None) -> None:
        self.storage_state_provider = provider

    def request_publish(self, reason: str = "unknown") -> None:
        if not self.enabled:
            return
        self._requested_revision += 1
        self._pending_reason_text = str(reason or "unknown")
        if self._publish_task is None or self._publish_task.done():
            self._publish_task = asyncio.create_task(self._publish_loop())

    async def wait_for_idle(self) -> None:
        task = self._publish_task
        if task is not None:
            await task

    async def _publish_loop(self) -> None:
        while True:
            revision = self._requested_revision
            reason = self._pending_reason_text
            await asyncio.to_thread(self._build_and_write_snapshot, revision, reason)
            self._last_published_revision = revision
            if revision == self._requested_revision:
                return

    def _build_and_write_snapshot(self, revision: int, reason: str) -> None:
        generated_at = int(time.time() * 1000)
        try:
            values = self.settings.snapshot()
            account = self._account_snapshot()
            categories = self._category_presence(values)
            categories["Conta"] = True
            # Storage Settings is an always-available Compose category. Its
            # capabilities are represented by the real storage snapshot rather
            # than by hiding the category when access is currently unavailable.
            categories["Armazenamento"] = True
            storage = self._storage_snapshot()
            payload = {
                "schemaVersion": self.SCHEMA_VERSION,
                "revision": int(revision),
                "generatedAt": generated_at,
                "reason": str(reason),
                "status": "READY",
                "account": account,
                "categories": categories,
                "storage": storage,
                "settings": {
                    str(key): self._json_safe(value)
                    for key, value in values.items()
                },
            }
        except Exception as exc:
            payload = {
                "schemaVersion": self.SCHEMA_VERSION,
                "revision": int(revision),
                "generatedAt": generated_at,
                "reason": str(reason),
                "status": "ERROR",
                "account": {
                    "integrationAvailable": False,
                    "connected": False,
                    "name": "",
                    "email": "",
                    "state": "ERROR",
                },
                "categories": {},
                "storage": {},
                "settings": {},
                "error": str(exc)[:500],
            }
        self._atomic_write_json(self.snapshot_path, payload)

    def _storage_snapshot(self) -> dict[str, Any]:
        provider = self.storage_state_provider
        if not callable(provider):
            return {}
        try:
            snapshot = provider()
            if hasattr(snapshot, "as_mapping"):
                snapshot = snapshot.as_mapping()
            if not isinstance(snapshot, dict):
                return {}
            raw = snapshot.get("capabilities")
            if hasattr(raw, "as_mapping"):
                raw = raw.as_mapping()
            if isinstance(raw, dict):
                return {
                    "mediaReadState": str(raw.get("mediaReadState") or "unknown").strip().lower(),
                    "broadStorageState": str(raw.get("broadStorageState") or "unknown").strip().lower(),
                    "safRootCount": len(raw.get("safRoots") or ()),
                    "removableVolumeCount": len(raw.get("removableVolumes") or ()),
                    "lifecycleState": str(raw.get("lifecycleState") or "unknown").strip().lower(),
                    "api": raw.get("api"),
                    "safSelectionPending": bool(snapshot.get("safSelectionPending")),
                }
            return dict(snapshot)
        except Exception:
            return {}

    def _category_presence(self, values: dict[str, Any]) -> dict[str, bool]:
        keys = tuple(str(key) for key in values)
        return {
            category: (
                bool(prefixes)
                and any(key.startswith(prefixes) for key in keys)
            )
            if prefixes
            else True
            for category, prefixes in self.CATEGORY_KEYS.items()
        }

    def _account_snapshot(self) -> dict[str, Any]:
        profile: dict[str, Any] = {}
        if callable(self.account_provider):
            try:
                raw = self.account_provider() or {}
                if isinstance(raw, dict):
                    profile = raw
            except Exception:
                profile = {}
        state = "disconnected"
        if callable(self.account_state_provider):
            try:
                state = str(self.account_state_provider() or "disconnected").strip().lower()
            except Exception:
                state = "error"
        email = str(profile.get("email") or "").strip()
        name = str(profile.get("name") or "").strip()
        connected = bool(email)
        picture = str(profile.get("picture") or "").strip()
        snapshot = {
            "integrationAvailable": True,
            "connected": connected,
            "name": name,
            "email": email,
            "state": state,
        }
        if picture:
            snapshot["picture"] = picture
        return snapshot

    @staticmethod
    def _json_safe(value: Any) -> Any:
        if value is None or isinstance(value, (bool, int, float, str)):
            return value
        if isinstance(value, (list, tuple)):
            return [ComposeSettingsBridge._json_safe(item) for item in value]
        if isinstance(value, dict):
            return {
                str(key): ComposeSettingsBridge._json_safe(item)
                for key, item in value.items()
            }
        return str(value)

    @staticmethod
    def _atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        os.replace(temporary, path)