from __future__ import annotations

import re
from pathlib import Path

from core.settings import SettingsDefaults


ROOT = Path(__file__).resolve().parents[1]
KOTLIN_CONTRACT = (
    ROOT
    / "android/app/src/main/kotlin/com/reiflix/reiflix_local/data/settings/ReiAnixSettingsContract.kt"
)


def _split_arguments(raw: str) -> list[str]:
    values: list[str] = []
    start = 0
    depth = 0
    quoted = False
    escaped = False
    for index, char in enumerate(raw):
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            values.append(raw[start:index].strip())
            start = index + 1
    values.append(raw[start:].strip())
    return values


def _unquote(value: str) -> str:
    value = value.strip()
    if value.startswith('"') and value.endswith('"'):
        return value[1:-1]
    return value


def _kotlin_definitions() -> dict[str, tuple[str, str, set[str]]]:
    source = KOTLIN_CONTRACT.read_text(encoding="utf-8")
    definitions: dict[str, tuple[str, str, set[str]]] = {}
    for line in source.splitlines():
        line = line.strip()
        match = re.match(r"(bool|int|decimal|enum)\((.*)\),?$", line)
        if match:
            helper, raw_arguments = match.groups()
            arguments = _split_arguments(raw_arguments)
            key = _unquote(arguments[0])
            default_raw = arguments[1].strip()
            kind = {"bool": "bool", "int": "int", "decimal": "float", "enum": "enum"}[helper]
            default = default_raw.lower() if kind == "bool" else _unquote(default_raw)
            choices = {_unquote(value) for value in arguments[2:]}
            definitions[key] = (kind, default, choices)
            continue

        match = re.match(
            r'ReiAnixSettingDefinition\("([^"]+)",\s*ReiAnixSettingKind\.(\w+),\s*("[^"]*")\)',
            line,
        )
        if match:
            key, raw_kind, raw_default = match.groups()
            kind = {
                "BOOLEAN": "bool",
                "INTEGER": "int",
                "DECIMAL": "float",
                "ENUM": "enum",
                "LANGUAGE": "language",
            }[raw_kind]
            definitions[key] = (kind, _unquote(raw_default), set())
    return definitions


def _python_default(definition) -> str:
    if definition.kind == "bool":
        return "true" if definition.default else "false"
    if definition.kind == "float":
        return str(float(definition.default))
    return str(definition.default)


def test_kotlin_settings_contract_has_exact_python_keys_types_defaults_and_choices():
    kotlin = _kotlin_definitions()
    python = {definition.key: definition for definition in SettingsDefaults.DEFINITIONS}

    assert set(kotlin) == set(python), (
        f"Python-only keys: {sorted(set(python) - set(kotlin))}; "
        f"Kotlin-only keys: {sorted(set(kotlin) - set(python))}"
    )

    mismatches = []
    for key, definition in python.items():
        kotlin_kind, kotlin_default, kotlin_choices = kotlin[key]
        expected_choices = {str(choice) for choice in definition.choices}
        expected_default = _python_default(definition)
        if kotlin_kind != definition.kind:
            mismatches.append(f"{key}: kind Python={definition.kind} Kotlin={kotlin_kind}")
        if kotlin_default != expected_default:
            mismatches.append(f"{key}: default Python={expected_default!r} Kotlin={kotlin_default!r}")
        if kotlin_choices != expected_choices:
            mismatches.append(
                f"{key}: choices Python={sorted(expected_choices)!r} Kotlin={sorted(kotlin_choices)!r}"
            )
    assert not mismatches, "Settings contract drift detected:\n" + "\n".join(mismatches)
