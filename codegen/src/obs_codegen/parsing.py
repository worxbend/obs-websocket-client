"""Strict JSON boundary: unknown metadata is ignored, modeled values are checked."""

import json
from collections.abc import Callable
from decimal import Decimal
from pathlib import Path
from typing import cast

from .errors import GenerationError
from .schema import (
    Documentation,
    Overrides,
    Provenance,
    Schema,
    SchemaEnum,
    SchemaEnumEntry,
    SchemaEvent,
    SchemaField,
    SchemaRequest,
)


def _object(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise GenerationError("Expected a JSON object")
    return cast(dict[str, object], value)


def _string(value: object) -> str:
    if not isinstance(value, str):
        raise GenerationError(f"Expected a string, got {value!r}")
    return value


def _boolean(value: object) -> bool:
    if not isinstance(value, bool):
        raise GenerationError(f"Expected a boolean, got {value!r}")
    return value


def _optional_string(value: object) -> str | None:
    return None if value is None else _string(value)


def _array[T](value: object, decode: Callable[[object], T]) -> tuple[T, ...]:
    # jsoniter's collection defaults accept missing/null as empty.
    if value is None:
        return ()
    if not isinstance(value, list):
        raise GenerationError("Expected an array")
    return tuple(decode(item) for item in value)


def _pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise GenerationError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _constant(value: str) -> object:
    raise GenerationError(f"Nonstandard JSON number: {value}")


def _parse[T](data: bytes, path: Path, description: str, decode: Callable[[object], T]) -> T:
    try:
        return decode(
            json.loads(
                data, parse_float=Decimal, parse_constant=_constant, object_pairs_hook=_pairs
            )
        )
    except (ValueError, KeyError, UnicodeError) as error:
        raise GenerationError(f"Failed to parse {description} from {path}: {error}") from error


def _documentation(value: dict[str, object]) -> Documentation:
    return Documentation(
        _string(value.get("description", "")),
        _string(value.get("initialVersion", "")),
        _string(value.get("rpcVersion", "")),
        _boolean(value.get("deprecated", False)),
    )


def _field(value: object) -> SchemaField:
    obj = _object(value)
    return SchemaField(
        _string(obj["valueName"]),
        _string(obj["valueType"]),
        _boolean(obj.get("valueOptional", False)),
        _optional_string(obj.get("valueRestrictions")),
        _string(obj.get("valueDescription", "")),
        _optional_string(obj.get("valueOptionalBehavior")),
    )


def _request(value: object) -> SchemaRequest:
    obj = _object(value)
    return SchemaRequest(
        _string(obj["requestType"]),
        _array(obj.get("requestFields"), _field),
        _array(obj.get("responseFields"), _field),
        _documentation(obj),
        _string(obj.get("category", "general")),
    )


def _event(value: object) -> SchemaEvent:
    obj = _object(value)
    return SchemaEvent(
        _string(obj["eventType"]), _array(obj.get("dataFields"), _field), _documentation(obj)
    )


def _enum_entry(value: object) -> SchemaEnumEntry:
    obj = _object(value)
    raw = obj["enumValue"]
    if raw is None:
        text = ""
    elif isinstance(raw, str):
        text = raw
    elif type(raw) is int and -(2**63) <= raw < 2**63:
        text = str(raw)
    else:
        raise GenerationError(f"Expected a string or signed 64-bit integer enum value, got {raw!r}")
    return SchemaEnumEntry(
        _string(obj["enumIdentifier"]), text, _string(obj.get("description", ""))
    )


def _enum(value: object) -> SchemaEnum:
    obj = _object(value)
    return SchemaEnum(_string(obj["enumType"]), _array(obj.get("enumIdentifiers"), _enum_entry))


def _schema(value: object) -> Schema:
    obj = _object(value)
    return Schema(
        _array(obj.get("requests"), _request),
        _array(obj.get("events"), _event),
        _array(obj.get("enums"), _enum),
    )


def _overrides(value: object) -> Overrides:
    return Overrides(_array(_object(value).get("nullableFields"), _string))


def _provenance(value: object) -> Provenance:
    obj = _object(value)
    return Provenance(_string(obj["repository"]), _string(obj["revision"]), _string(obj["sha256"]))


def parse_schema(data: bytes, path: Path) -> Schema:
    return _parse(data, path, "protocol schema", _schema)


def parse_overrides(data: bytes, path: Path) -> Overrides:
    return _parse(data, path, "generation overrides", _overrides)


def parse_provenance(data: bytes, path: Path) -> Provenance:
    return _parse(data, path, "schema provenance", _provenance)
