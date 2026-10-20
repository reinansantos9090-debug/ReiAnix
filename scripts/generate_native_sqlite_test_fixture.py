#!/usr/bin/env python3
"""Export a canonical SQLite fixture and runtime contract from LibraryStore.

The schema is initialized by the real Python LibraryStore in an isolated
temporary directory. The test fixture includes DDL for creating an isolated
Robolectric database; the runtime contract contains only expected metadata.
Neither output is a production database.
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


def normalize_sql_expression(sql: str) -> str:
    """Normalize SQL tokens while preserving quoted identifiers and literals."""
    out: list[str] = []
    quote: str | None = None
    pending_space = False
    index = 0
    while index < len(sql):
        char = sql[index]
        if quote is not None:
            out.append(char)
            if char == quote:
                if index + 1 < len(sql) and sql[index + 1] == quote:
                    out.append(sql[index + 1])
                    index += 1
                else:
                    quote = None
        elif char in ("'", '"', "`"):
            if pending_space and out:
                out.append(" ")
            pending_space = False
            quote = char
            out.append(char)
        elif char == "[":
            if pending_space and out:
                out.append(" ")
            pending_space = False
            quote = "]"
            out.append(char)
        elif char.isspace():
            pending_space = True
        else:
            if pending_space and out:
                out.append(" ")
            pending_space = False
            out.append(char.upper())
        index += 1
    return "".join(out).strip().rstrip(";").strip()


def _skip_quoted_or_comment(sql: str, index: int) -> int:
    """Return the first character after one quoted token or SQL comment."""
    char = sql[index]
    if char in ("'", '"', "`"):
        quote = char
        index += 1
        while index < len(sql):
            if sql[index] == quote:
                if index + 1 < len(sql) and sql[index + 1] == quote:
                    index += 2
                    continue
                return index + 1
            index += 1
        return index
    if char == "[":
        close = sql.find("]", index + 1)
        return len(sql) if close < 0 else close + 1
    if sql.startswith("--", index):
        newline = sql.find(chr(10), index + 2)
        return len(sql) if newline < 0 else newline + 1
    if sql.startswith("/*", index):
        close = sql.find("*/", index + 2)
        return len(sql) if close < 0 else close + 2
    return index


def extract_parenthesized_sql(sql: str, keyword: str) -> list[str]:
    """Extract balanced keyword(...) expressions, ignoring strings/comments."""
    values: list[str] = []
    target = keyword.upper()
    index = 0
    while index < len(sql):
        char = sql[index]
        if char in ("'", '"', "`", "[") or sql.startswith("--", index) or sql.startswith("/*", index):
            index = _skip_quoted_or_comment(sql, index)
            continue

        before_is_word = index > 0 and (sql[index - 1].isalnum() or sql[index - 1] == "_")
        end_keyword = index + len(target)
        after_is_word = end_keyword < len(sql) and (
            sql[end_keyword].isalnum() or sql[end_keyword] == "_"
        )
        if not before_is_word and not after_is_word and sql[index:end_keyword].upper() == target:
            open_index = end_keyword
            while open_index < len(sql) and sql[open_index].isspace():
                open_index += 1
            if open_index < len(sql) and sql[open_index] == "(":
                depth = 1
                cursor = open_index + 1
                while cursor < len(sql) and depth:
                    token = sql[cursor]
                    if token in ("'", '"', "`", "[") or sql.startswith("--", cursor) or sql.startswith("/*", cursor):
                        cursor = _skip_quoted_or_comment(sql, cursor)
                        continue
                    if token == "(":
                        depth += 1
                    elif token == ")":
                        depth -= 1
                    cursor += 1
                if depth:
                    raise RuntimeError(f"Unbalanced {keyword} clause in SQLite DDL: {sql}")
                values.append(normalize_sql_expression(sql[open_index + 1:cursor - 1]))
                index = cursor
                continue
        index += 1
    return values

def _table_ddl(connection: sqlite3.Connection, table: str) -> str:
    row = connection.execute(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table,),
    ).fetchone()
    if not row or not row[0]:
        raise RuntimeError(f"Canonical table {table!r} has no DDL in sqlite_master")
    return str(row[0])


def build_fixture() -> dict[str, object]:
    with tempfile.TemporaryDirectory(prefix="reianix-sqlite-schema-") as temporary:
        store = LibraryStore(temporary)
        if store.recovery_error:
            raise RuntimeError(
                f"LibraryStore could not initialize its temporary schema: {store.recovery_error}"
            )

        with sqlite3.connect(store.db_path) as connection:
            version_row = connection.execute(
                "SELECT MAX(version) FROM schema_migrations"
            ).fetchone()
            schema_version = int(version_row[0] or 0)
            if schema_version != EXPECTED_SCHEMA_VERSION or schema_version != LibraryStore.SCHEMA_VERSION:
                raise RuntimeError(
                    f"Canonical schema version is {schema_version}; this Kotlin contract expects "
                    f"{EXPECTED_SCHEMA_VERSION}. Update the compatibility contract before proceeding."
                )

            user_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
            tables = sorted(
                row[0] for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
                )
            )
            missing_tables = EXPECTED_TABLES - set(tables)
            if missing_tables:
                raise RuntimeError(f"Canonical schema is missing expected tables: {sorted(missing_tables)}")

            table_columns: dict[str, list[dict[str, object]]] = {}
            table_contract: dict[str, dict[str, object]] = {}
            table_sql: dict[str, str] = {}
            for table in tables:
                escaped = table.replace('"', '""')
                ddl = _table_ddl(connection, table)
                table_sql[table] = ddl
                rows = connection.execute(f'PRAGMA table_xinfo("{escaped}")').fetchall()
                table_columns[table] = [
                    {
                        "name": row[1],
                        "type": row[2] or "",
                        "not_null": bool(row[3]),
                        "default": row[4],
                        "primary_key_position": row[5],
                        "hidden": row[6],
                    }
                    for row in rows
                ]
                table_contract[table] = {
                    "strict": bool(re.search(r"\bSTRICT\s*;?\s*$", ddl, re.IGNORECASE)),
                    "without_rowid": bool(re.search(r"\bWITHOUT\s+ROWID\b", ddl, re.IGNORECASE)),
                    "checks": extract_parenthesized_sql(ddl, "CHECK"),
                }

            indexes: list[dict[str, object]] = []
            for table in tables:
                escaped = table.replace('"', '""')
                for row in connection.execute(f'PRAGMA index_list("{escaped}")').fetchall():
                    index_name = str(row[1])
                    index_sql_row = connection.execute(
                        "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
                        (index_name,),
                    ).fetchone()
                    index_sql = index_sql_row[0] if index_sql_row else None
                    index_columns = [
                        {
                            "name": item[2],
                            "column_id": int(item[1]),
                            "expression": int(item[1]) == -2,
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
                        predicate = normalize_sql_expression(match.group(1)) if match else None
                    indexes.append({
                        "name": index_name,
                        "table": table,
                        "unique": bool(row[2]),
                        "origin": str(row[3]),
                        "partial": bool(row[4]),
                        "columns": index_columns,
                        "predicate": predicate,
                        "sql": index_sql,
                    })

            foreign_keys: dict[str, list[dict[str, object]]] = {}
            for table in tables:
                escaped = table.replace('"', '""')
                foreign_keys[table] = [
                    {
                        "id": int(row[0]),
                        "sequence": int(row[1]),
                        "table": row[2],
                        "from": row[3],
                        "to": row[4],
                        "on_update": str(row[5]).upper(),
                        "on_delete": str(row[6]).upper(),
                        "match": str(row[7]).upper(),
                    }
                    for row in connection.execute(f'PRAGMA foreign_key_list("{escaped}")').fetchall()
                ]

            triggers = [
                {
                    "name": row[0],
                    "table": row[1],
                    "sql": normalize_sql_expression(row[2] or ""),
                }
                for row in connection.execute(
                    "SELECT name, tbl_name, sql FROM sqlite_master "
                    "WHERE type = 'trigger' AND name NOT LIKE 'sqlite_%' ORDER BY name"
                ).fetchall()
            ]

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
            return {
                "schema_version": schema_version,
                "sqlite_user_version": user_version,
                "tables": tables,
                "columns": table_columns,
                "table_contract": table_contract,
                "indexes": indexes,
                "foreign_keys": foreign_keys,
                "triggers": triggers,
                "statements": [sql for _, _, sql in objects],
            }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Destination JSON test fixture")
    parser.add_argument(
        "--contract-output",
        type=Path,
        help="Optional production runtime contract JSON, generated from the same canonical snapshot",
    )
    args = parser.parse_args()
    fixture_path = args.output.resolve()
    fixture_path.parent.mkdir(parents=True, exist_ok=True)
    payload = build_fixture()
    fixture_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.contract_output:
        contract_path = args.contract_output.resolve()
        contract_path.parent.mkdir(parents=True, exist_ok=True)
        contract = {key: value for key, value in payload.items() if key != "statements"}
        contract_path.write_text(
            json.dumps(contract, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    print(
        f"Generated canonical SQLite schema v{payload['schema_version']}: "
        f"{len(payload['tables'])} tables and {len(payload['indexes'])} index entries -> {fixture_path}"
    )
    if args.contract_output:
        print(f"Generated runtime contract -> {args.contract_output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
