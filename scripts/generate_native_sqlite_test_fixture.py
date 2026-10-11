#!/usr/bin/env python3
"""Generate an isolated JVM-test schema from the canonical LibraryStore.

The generated JSON is a test resource, not an application database. It contains
DDL exported from a fresh temporary database initialized by LibraryStore._init,
including the real migrations/indexes for the currently supported schema.
"""
from __future__ import annotations

import argparse
import json
import re
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

            indexes = []
            foreign_keys: dict[str, list[dict[str, object]]] = {}
            for table in tables:
                escaped = table.replace('"', '""')
                for row in connection.execute(f'PRAGMA index_list("{escaped}")').fetchall():
                    index_name = row[1]
                    index_sql_row = connection.execute(
                        "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
                        (index_name,),
                    ).fetchone()
                    index_sql = index_sql_row[0] if index_sql_row else None
                    index_columns = [
                        {
                            "name": item[2],
                            "descending": bool(item[3]),
                            "collation": item[4],
                        }
                        for item in connection.execute(
                            f'PRAGMA index_xinfo("{index_name.replace(chr(34), chr(34) * 2)}")'
                        ).fetchall()
                        if len(item) > 5 and item[5]
                    ]
                    predicate = None
                    if index_sql:
                        match = re.search(r"(?is)\bWHERE\b(.*)$", index_sql)
                        predicate = " ".join(match.group(1).strip().rstrip(";").split()) if match else None
                    indexes.append({
                        "name": index_name,
                        "table": table,
                        "unique": bool(row[2]),
                        "origin": row[3],
                        "partial": bool(row[4]),
                        "columns": index_columns,
                        "predicate": predicate,
                        "sql": index_sql,
                    })

                foreign_keys[table] = [
                    {
                        "table": row[2],
                        "from": row[3],
                        "to": row[4],
                        "on_update": row[5],
                        "on_delete": row[6],
                        "match": row[7],
                    }
                    for row in connection.execute(f'PRAGMA foreign_key_list("{escaped}")').fetchall()
                ]

            user_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
            return {
                "schema_version": schema_version,
                "sqlite_user_version": user_version,
                "tables": tables,
                "columns": columns,
                "indexes": indexes,
                "foreign_keys": foreign_keys,
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
        f"{len(payload['tables'])} tables and {len(payload['indexes'])} index entries -> {output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
