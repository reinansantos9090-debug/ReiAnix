"""Deterministic storage authorization states.

Android is authoritative for current grants. Python consumes the native
capability snapshot for UI, onboarding, Settings and scan orchestration.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any
from urllib.parse import unquote, urlparse


class StorageAccessState(str, Enum):
    UNKNOWN = "unknown"
    MEDIA_DENIED = "media_denied"
    MEDIA_PARTIAL = "media_partial"
    MEDIA_FULL = "media_full"
    SAF_AVAILABLE = "saf_available"
    SAF_REVOKED = "saf_revoked"
    BROAD_STORAGE_AVAILABLE = "broad_storage_available"
    BROAD_STORAGE_UNAVAILABLE = "broad_storage_unavailable"
    NEEDS_MEDIA_PERMISSION = "needs_media_permission"
    NEEDS_BROAD_STORAGE = "needs_broad_storage"
    READY = "ready"
    DECLINED = "declined"


class ScanUiState(str, Enum):
    IDLE = "IDLE"
    CHECKING = "CHECKING"
    SCANNING = "SCANNING"
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"
    WAITING_FOR_MEDIASTORE = "WAITING_FOR_MEDIASTORE"
    VOLUME_UNAVAILABLE = "VOLUME_UNAVAILABLE"


def storage_snapshot_is_stale(event_at_ms: int | float | None, latest_mutation_at_ms: int | float | None) -> bool:
    """Return whether a native storage snapshot predates a newer user mutation."""
    try:
        event_at = int(event_at_ms or 0)
        mutation_at = int(latest_mutation_at_ms or 0)
    except (TypeError, ValueError):
        return False
    return event_at > 0 and mutation_at > event_at


def pending_saf_inventory_selection_candidate(
    trees: Any,
    baseline_identities,
    *,
    inventory_started_at_ms: int | float | None,
    selection_started_at_ms: int | float | None,
    inventory_complete: bool,
) -> dict[str, Any] | None:
    """Find one new persisted root that proves an explicit pending selection."""
    if not inventory_complete:
        return None
    try:
        inventory_started = int(inventory_started_at_ms or 0)
        selection_started = int(selection_started_at_ms or 0)
    except (TypeError, ValueError):
        return None
    if inventory_started <= 0 or selection_started <= 0 or inventory_started < selection_started:
        return None
    baseline = {
        str(value).strip() for value in (baseline_identities or ()) if str(value).strip()
    }
    candidates: dict[str, dict[str, Any]] = {}
    for item in trees or ():
        if not isinstance(item, dict) or not item.get("persisted"):
            continue
        if str(item.get("status") or "").strip().upper() not in {"COMPLETED", "EMPTY_COMPLETE"}:
            continue
        uri = str(item.get("treeUri") or "").strip()
        identity = saf_source_identity(uri) or str(item.get("identity") or "").strip()
        if not uri or not identity or identity in baseline:
            continue
        candidates[identity] = dict(item)
    if len(candidates) != 1:
        return None
    return next(iter(candidates.values()))


def scan_ui_state_from_native(status: str | None, *, errors: bool = False, cancelled: bool = False,
                              waiting_for_mediastore: bool = False, volume_available: bool = True) -> ScanUiState:
    if waiting_for_mediastore:
        return ScanUiState.WAITING_FOR_MEDIASTORE
    if not volume_available:
        return ScanUiState.VOLUME_UNAVAILABLE
    value = str(status or "").strip().upper()
    if cancelled or value in {"CANCELLED", "CANCELED"}:
        return ScanUiState.CANCELLED
    if value in {"FAILED", "ERROR"}:
        return ScanUiState.FAILED
    if value in {"PARTIAL", "UNAVAILABLE"} or errors:
        return ScanUiState.PARTIAL
    if value in {"COMPLETED", "EMPTY_COMPLETE"}:
        return ScanUiState.COMPLETED
    if value in {"CHECKING"}:
        return ScanUiState.CHECKING
    if value in {"SCANNING", "RUNNING", "STARTED"}:
        return ScanUiState.SCANNING
    return ScanUiState.IDLE


@dataclass(frozen=True)
class StorageCapabilities:
    media_read_state: str = "denied"
    broad_storage_state: str = "unavailable"
    saf_roots: tuple[str, ...] = ()
    removable_volumes: tuple[str, ...] = ()
    scanner_capabilities: frozenset[str] = frozenset()
    reconciliation_capabilities: frozenset[str] = frozenset()
    lifecycle_state: str = "unknown"
    api: int | None = None

    @classmethod
    def unknown(cls) -> "StorageCapabilities":
        return cls()

    @classmethod
    def from_native(cls, payload: dict | None) -> "StorageCapabilities":
        if not isinstance(payload, dict):
            return cls.unknown()
        media = str(payload.get("mediaReadState") or "denied").casefold()
        if media not in {"denied", "partial", "full"}:
            media = "denied"
        broad = str(payload.get("broadStorageState") or "unavailable").casefold()
        if broad not in {"available", "unavailable"}:
            broad = "unavailable"
        saf = tuple(dict.fromkeys(
            str(value).strip() for value in (payload.get("safRoots") or [])
            if str(value).strip()
        ))
        removable = tuple(dict.fromkeys(
            str(value).strip() for value in (payload.get("removableVolumes") or [])
            if str(value).strip()
        ))
        scanners = frozenset(
            str(value).strip() for value in (payload.get("scannerCapabilities") or [])
            if str(value).strip()
        )
        reconciliators = frozenset(
            str(value).strip() for value in (payload.get("reconciliationCapabilities") or [])
            if str(value).strip()
        )
        try:
            api = int(payload["api"]) if payload.get("api") is not None else None
        except (TypeError, ValueError):
            api = None
        return cls(
            media_read_state=media,
            broad_storage_state=broad,
            saf_roots=saf,
            removable_volumes=removable,
            scanner_capabilities=scanners,
            reconciliation_capabilities=reconciliators,
            lifecycle_state=str(payload.get("lifecycleState") or "unknown").casefold(),
            api=api,
        )

    def as_mapping(self) -> dict[str, object]:
        return {
            "mediaReadState": self.media_read_state,
            "broadStorageState": self.broad_storage_state,
            "safRoots": list(self.saf_roots),
            "removableVolumes": list(self.removable_volumes),
            "scannerCapabilities": list(self.scanner_capabilities),
            "reconciliationCapabilities": list(self.reconciliation_capabilities),
            "lifecycleState": self.lifecycle_state,
            "api": self.api,
        }

    def get(self, key: str, default=None):
        if not isinstance(key, str):
            return default
        mapping = {
            "mediaReadState": "media_read_state",
            "broadStorageState": "broad_storage_state",
            "safRoots": "saf_roots",
            "removableVolumes": "removable_volumes",
            "scannerCapabilities": "scanner_capabilities",
            "reconciliationCapabilities": "reconciliation_capabilities",
            "lifecycleState": "lifecycle_state",
            "api": "api",
        }
        attr = mapping.get(key, key)
        if hasattr(self, attr):
            return getattr(self, attr, default)
        return default

    @property
    def known(self) -> bool:
        return self.api is not None or self.lifecycle_state != "unknown"

    def can_scan(self, source: str) -> bool:
        if not isinstance(source, str):
            return False
        value = source.strip().casefold()
        if value == "mediastore":
            return self.media_read_state in {"partial", "full"}
        if value == "broad-storage":
            return self.broad_storage_state == "available"
        if value == "saf":
            return bool(self.saf_roots)
        return value in {str(item).strip().casefold() for item in self.scanner_capabilities}

    def can_reconcile(self, source: str) -> bool:
        if not isinstance(source, str):
            return False
        value = source.strip().casefold()
        if value == "mediastore":
            return self.media_read_state == "full"
        if value == "broad-storage":
            return self.broad_storage_state == "available"
        if value == "saf":
            return bool(self.saf_roots)
        return value in {str(item).strip().casefold() for item in self.reconciliation_capabilities}


def saf_source_identity(value: str | None) -> str | None:
    """Return a stable identity for a persisted SAF tree URI."""
    raw = str(value or "").strip()
    if not raw:
        return None
    try:
        parsed = urlparse(raw)
    except ValueError:
        return None
    if parsed.scheme.casefold() != "content" or not parsed.netloc:
        return None
    path = parsed.path.rstrip("/")
    marker = "/tree/"
    if marker not in path:
        return f"saf-uri:{raw}"
    encoded_id = path.split(marker, 1)[1].split("/", 1)[0].strip()
    if not encoded_id:
        return None
    document_id = unquote(encoded_id).strip()
    if not document_id:
        return None
    return f"saf:{parsed.netloc.casefold()}:{document_id}"


def saf_folder_identity(folder: Any) -> str | None:
    """Resolve configured SAF folder identity from its URI before cached metadata.

    The stored identity is derived metadata and can be stale after an Android
    URI is re-encoded or migrated. A parseable SAF folder URI is authoritative.
    """
    if not isinstance(folder, dict):
        return None
    reference = str(folder.get("path") or "").strip()
    identity = saf_source_identity(reference)
    if identity:
        return identity
    stored = str(folder.get("saf_identity") or "").strip()
    return stored or None


def saf_inventory_by_identity(trees: Any) -> dict[str, dict[str, Any]]:
    """Collapse native SAF inventory rows by canonical tree identity.

    Providers may report different URI spellings for the same tree. When that
    happens, prefer a usable completed result over a transient/error duplicate
    so the same folder is not incorrectly treated as revoked or unavailable.
    """
    by_identity: dict[str, dict[str, Any]] = {}
    priority = {
        "COMPLETED": 4,
        "EMPTY_COMPLETE": 4,
        "UNAVAILABLE": 3,
        "FAILED": 3,
        "PARTIAL": 3,
        "REVOKED": 2,
    }
    for item in trees or ():
        if not isinstance(item, dict):
            continue
        uri = str(item.get("treeUri") or "").strip()
        identity = saf_source_identity(uri) or str(item.get("identity") or "").strip()
        if not uri or not identity:
            continue
        previous = by_identity.get(identity)
        current_rank = priority.get(str(item.get("status") or "").strip().upper(), 1)
        previous_rank = priority.get(str((previous or {}).get("status") or "").strip().upper(), 1)
        if previous is None or current_rank > previous_rank:
            by_identity[identity] = dict(item)
    return by_identity


def dedupe_saf_roots(values) -> tuple[str, ...]:
    """Normalize and de-duplicate SAF tree URIs by provider/document identity."""
    by_identity: dict[str, str] = {}
    for value in values or ():
        uri = str(value or "").strip()
        identity = saf_source_identity(uri)
        if not identity:
            continue
        by_identity.setdefault(identity, uri)
    return tuple(by_identity[key] for key in sorted(by_identity))


def library_saf_roots(values, *, scope_ref: str | None = None) -> tuple[str, ...]:
    """Resolve persisted SAF roots that are eligible for the requested library scope."""
    roots = dedupe_saf_roots(values)
    if scope_ref is None or not str(scope_ref).strip():
        return roots
    requested = saf_source_identity(scope_ref)
    if requested is None:
        return ()
    return tuple(uri for uri in roots if saf_source_identity(uri) == requested)


def configured_library_saf_roots(
    persisted_values,
    configured_folders,
    *,
    scope_ref: str | None = None,
) -> tuple[str, ...]:
    """Intersect current persisted SAF grants with explicitly configured library sources.

    A persisted Android grant is an authorization capability, not by itself a
    library-source selection. Only SAF folders explicitly configured in the
    existing LibraryStore and still marked authorized are eligible.
    """
    persisted_by_identity = {
        identity: uri
        for uri in dedupe_saf_roots(persisted_values)
        if (identity := saf_source_identity(uri))
    }
    configured_ids = set()
    for folder in configured_folders or ():
        if not isinstance(folder, dict):
            continue
        if str(folder.get("kind") or "").strip().casefold() != "saf":
            continue
        if str(folder.get("authorization") or "").strip().casefold() != "granted":
            continue
        reference = str(folder.get("path") or "").strip()
        # Never let stale cached metadata authorize a different persisted tree.
        # The configured URI is the durable source reference; cached identity is
        # only a fallback for legacy records whose reference cannot be parsed.
        identity = saf_folder_identity(folder)
        if identity:
            configured_ids.add(identity)

    roots = tuple(
        persisted_by_identity[identity]
        for identity in sorted(configured_ids & persisted_by_identity.keys())
    )
    if scope_ref is None or not str(scope_ref).strip():
        return roots
    requested = saf_source_identity(scope_ref)
    if requested is None:
        return ()
    return tuple(uri for uri in roots if saf_source_identity(uri) == requested)


def _mapping_from_snapshot(snapshot: Any) -> dict[str, Any]:
    if snapshot is None:
        return {}
    if isinstance(snapshot, dict):
        return snapshot
    if isinstance(snapshot, StorageCapabilities):
        return snapshot.as_mapping()
    candidate = getattr(snapshot, "as_mapping", None)
    if callable(candidate):
        mapped = candidate()
        if isinstance(mapped, dict):
            return mapped
    candidate = getattr(snapshot, "__dict__", None)
    if isinstance(candidate, dict):
        return candidate
    return {}


def normalize_storage_snapshot(snapshot: Any) -> StorageCapabilities:
    if snapshot is None:
        return StorageCapabilities.unknown()
    if isinstance(snapshot, StorageCapabilities):
        return snapshot
    payload = _mapping_from_snapshot(snapshot)
    return StorageCapabilities.from_native(payload)


def storage_access_state(
    media_access: str | None,
    broad_granted: bool,
    saf_available: bool = False,
    *,
    dismissed: bool = False,
    require_broad: bool = False,
) -> StorageAccessState:
    """Resolve effective readiness while keeping source grants independent."""
    if dismissed:
        return StorageAccessState.DECLINED

    access = str(media_access or "denied").casefold()
    if require_broad and not broad_granted:
        return StorageAccessState.NEEDS_BROAD_STORAGE

    if saf_available or broad_granted:
        return StorageAccessState.READY

    if access == "partial":
        return StorageAccessState.MEDIA_PARTIAL

    if access != "full":
        return StorageAccessState.NEEDS_MEDIA_PERMISSION

    return StorageAccessState.READY


def storage_source_states(
    media_access: str | None,
    broad_granted: bool,
    saf_uris: list[str] | tuple[str, ...] | None = None,
    *,
    saf_revoked: bool = False,
) -> dict[str, str]:
    """Return explicit per-source states without merging grants."""
    media = str(media_access or "denied").casefold()
    media_state = {
        "full": StorageAccessState.MEDIA_FULL.value,
        "partial": StorageAccessState.MEDIA_PARTIAL.value,
    }.get(media, StorageAccessState.MEDIA_DENIED.value)
    broad_state = (
        StorageAccessState.BROAD_STORAGE_AVAILABLE.value
        if bool(broad_granted)
        else StorageAccessState.BROAD_STORAGE_UNAVAILABLE.value
    )
    if saf_uris:
        saf_state = StorageAccessState.SAF_AVAILABLE.value
    elif saf_revoked:
        saf_state = StorageAccessState.SAF_REVOKED.value
    else:
        saf_state = StorageAccessState.UNKNOWN.value
    return {
        "media": media_state,
        "saf": saf_state,
        "broad": broad_state,
        "effective": storage_access_state(media_access, broad_granted, bool(saf_uris)).value,
    }


__all__ = [
    "StorageAccessState",
    "ScanUiState",
    "scan_ui_state_from_native",
    "StorageCapabilities",
    "saf_source_identity",
    "pending_saf_inventory_selection_candidate",
    "dedupe_saf_roots",
    "library_saf_roots",
    "normalize_storage_snapshot",
    "storage_access_state",
    "storage_source_states",
]
