#!/usr/bin/env python3
"""Generate an isolated JVM-test schema from the canonical LibraryStore.

The generated JSON is a test resource, not an application database. It contains
DDL exported from a fresh temporary database initialized by LibraryStore._init,
including the real migrations/indexes for the currently supported schema.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from core.library_store import LibraryStore  # noqa: E402

EXPECTED_SCHEMA_VERSION = 29
EXPECTED_TABLES = {
    "folders", "anime", "episodes", "episode_observations", "artwork",
    "associations", "pending_matches", "account", "preferences", "genres",
    "genre_aliases", "anime_genres", "schema_migrations", "scan_runs",
}


def build_fixture() -> dict[str, object]:
    with tempfile.TemporaryDirectory(prefix="reianix-sqlite-schema-") as temporary:
        store = LibraryStore(temporary)
        if store.recovery_error:
            raise RuntimeError(f"LibraryStore could not initialize its temporary schema: {store.recovery_error}")

        with sqlite3.connect(store.db_path) as connection:
            version_row = connection.execute(
                "SELECT MAX(version) FROM schema_migrations"
            ).fetchone()
            schema_version = int(version_row[0] or 0)
            if schema_version != EXPECTED_SCHEMA_VERSION or schema_version != LibraryStore.SCHEMA_VERSION:
                raise RuntimeError(
                    f"Canonical schema version is {schema_version}; this Kotlin contract expects "
                    f"{EXPECTED_SCHEMA_VERSION}. Update the Kotlin compatibility contract before proceeding."
                )

            objects = connection.execute(
                """
                SELECT type, name, sql
                FROM sqlite_master
                WHERE type IN ('table', 'index')
                  AND sql IS NOT NULL
                  AND name NOT LIKE 'sqlite_%'
                ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, name
                """
            ).fetchall()
            tables = sorted(
                row[0] for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
                )
            )
            missing_tables = EXPECTED_TABLES - set(tables)
            if missing_tables:
                raise RuntimeError(f"Canonical schema is missing expected tables: {sorted(missing_tables)}")

            columns: dict[str, list[dict[str, object]]] = {}
            for table in tables:
                escaped = table.replace('"', '""')
                rows = connection.execute(f'PRAGMA table_info("{escaped}")').fetchall()
                columns[table] = [
                    {
                        "name": row[1],
                        "type": row[2],
                        "not_null": bool(row[3]),
                        "default": row[4],
                        "primary_key_position": row[5],
                    }
                    for row in rows
                ]

            indexes = [
                {"name": name, "sql": sql}
                for object_type, name, sql in objects
                if object_type == "index"
            ]
            return {
                "schema_version": schema_version,
                "tables": tables,
                "columns": columns,
                "indexes": indexes,
                "statements": [sql for _, _, sql in objects],
            }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Destination JSON test resource")
    args = parser.parse_args()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = build_fixture()
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"Generated isolated schema fixture v{payload['schema_version']}: "
        f"{len(payload['tables'])} tables and {len(payload['indexes'])} explicit indexes -> {output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
