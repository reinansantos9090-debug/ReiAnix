"""Stable identity helpers for local media discovered by different Android sources.

The same physical file can be exposed as a file:// URI by the broad-storage
scanner, a content:// MediaStore URI, or a SAF document URI.  The library stores
one logical episode when the available metadata lets us prove the sources point
at the same shared-storage location.
"""
from __future__ import annotations

import re
from urllib.parse import unquote, urlparse

_PRIMARY_ALIASES = {"external_primary", "primary", "external"}
_EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
_STORAGE_RE = re.compile(r"^/(?:storage|mnt/media_rw)/([^/]+)(?:/(.*))?$", re.I)


def _clean_relative(value: str) -> str:
    value = unquote(str(value or "")).replace("\\", "/")
    value = value.strip()
    if value.startswith("/"):
        value = "/" + value.lstrip("/")
    parts = [part for part in value.split("/") if part not in ("", ".")]
    safe = []
    for part in parts:
        if part == "..":
            if safe:
                safe.pop()
            continue
        safe.append(part)
    return "/".join(safe).casefold()


def _volume_key(volume: str | None) -> str:
    value = str(volume or "").strip().casefold()
    if value in _PRIMARY_ALIASES or not value:
        return "primary"
    return value


def _normalize_volume_relative(volume: str, relative: str) -> tuple[str, str]:
    value = volume.casefold().strip()
    relative = relative.strip("/")
    # Emulated primary storage is normally /storage/emulated/0.
    if value == "emulated" and (relative == "0" or relative.startswith("0/")):
        return "primary", relative[1:].lstrip("/")
    return _volume_key(volume), relative


def identity_from_file_uri(uri: str) -> str | None:
    parsed = urlparse(uri)
    path = unquote(parsed.path or "")
    match = _STORAGE_RE.match(path)
    if not match:
        return None
    volume, relative_raw = _normalize_volume_relative(match.group(1), match.group(2) or "")
    relative = _clean_relative(relative_raw)
    if not relative:
        return None
    return f"shared:{volume}:{relative}"


def identity_from_relative_path(relative_path: str, volume_name: str | None = None) -> str | None:
    value = unquote(str(relative_path or "")).replace("\\", "/")
    value = value.strip()
    if not value:
        return None

    # Accept full Android shared-storage paths produced by the broad scanner.
    match = _STORAGE_RE.match(value)
    if match:
        volume, relative_raw = _normalize_volume_relative(match.group(1), match.group(2) or "")
        relative = _clean_relative(relative_raw)
        return f"shared:{volume}:{relative}" if relative else None

    # MediaStore and SAF expose paths relative to a volume/tree.
    relative = _clean_relative(value)
    if not relative:
        return None
    return f"shared:{_volume_key(volume_name)}:{relative}"


def identity_from_document(
    uri: str,
    relative_path: str | None = None,
    volume_name: str | None = None,
    tree_uri: str | None = None,
) -> str | None:
    """Return a stable identity when the source exposes shared-storage metadata.

    For SAF external-storage trees such as primary:Movies, the tree volume/path
    is combined with the document's relative path so it can match a MediaStore
    or file-based discovery of the same physical file.
    """
    if uri.startswith("file://"):
        identity = identity_from_file_uri(uri)
        if identity:
            return identity

    if tree_uri:
        parsed_tree = urlparse(tree_uri)
        tree_path = unquote(parsed_tree.path or "")
        marker = "/tree/"
        if marker in tree_path and parsed_tree.netloc == _EXTERNAL_STORAGE_AUTHORITY:
            tree_id = tree_path.split(marker, 1)[1]
            if ":" in tree_id:
                tree_volume, tree_root = tree_id.split(":", 1)
                tree_root = tree_root.strip("/")
                document_relative = _clean_relative(relative_path or "")
                combined = "/".join(p for p in (tree_root, document_relative) if p)
                if combined:
                    return f"shared:{_volume_key(tree_volume)}:{_clean_relative(combined)}"

    # Generic/cloud DocumentsProvider paths do not prove shared local storage.
    # Only use a relative path when MediaStore explicitly supplied a volume.
    if volume_name:
        relative_identity = identity_from_relative_path(relative_path or "", volume_name)
        if relative_identity:
            return relative_identity
    # Non-filesystem DocumentsProviders cannot be safely correlated with local
    # paths. Keep a provider-scoped identity so repeated scans remain stable.
    parsed = urlparse(uri)
    if parsed.scheme == "content" and parsed.netloc and parsed.path:
        return f"uri:{parsed.netloc.casefold()}:{_clean_relative(parsed.path)}"
    return None


def local_media_identity(*, uri, source_kind="unknown", relative_path=None, size=None, modified_at=None, volume_id=None):
    """Backward-compatible identity facade used by scanner/tests.

    Size and modified time are accepted as discovery metadata but are not part
    of the logical identity: the stable location/volume evidence remains the
    source of truth, so rename/move reconciliation does not manufacture a
    different consumption identity when the physical media identity is known.
    """
    _ = source_kind, size, modified_at
    return identity_from_document(uri, relative_path, volume_id, None)
