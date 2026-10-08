"""Validation boundary for non-sensitive Google profile events."""
from __future__ import annotations


def normalize_google_profile(payload: dict) -> dict | None:
    """Keep only the durable profile fields allowed in SQLite.

    Tokens must never cross the Android/Python mailbox. A malformed native
    event is rejected rather than creating a false connected state.
    """
    if not isinstance(payload, dict):
        return None
    subject = payload.get("id")
    email = payload.get("email")
    if not isinstance(subject, str) or not subject.strip():
        return None
    if not isinstance(email, str) or "@" not in email:
        return None
    return {
        "id": subject.strip(),
        "name": payload.get("name", "") if isinstance(payload.get("name", ""), str) else "",
        "email": email.strip(),
        "picture": payload.get("picture", "") if isinstance(payload.get("picture", ""), str) else "",
    }
