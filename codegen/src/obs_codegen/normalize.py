"""Resolve schema semantics once, independently of rendering and filesystem I/O."""

import re
from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass

from .errors import GenerationError
from .model import (
    EnumConstant,
    EnumDefinition,
    EventDefinition,
    Field,
    InventoryEntry,
    NormalizedSchema,
    RequestCategory,
    RequestDefinition,
)
from .scala import quote
from .schema import Overrides, Schema, SchemaEnum, SchemaField

_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_RESERVED = frozenset(
    {
        "toJson",
        "decodeResponse",
        "copy",
        "productPrefix",
        "productArity",
        "productElement",
        "productIterator",
    }
)
_ENVELOPE = frozenset({"requestType", "requestData", "eventType", "eventData"})


@dataclass(frozen=True, slots=True)
class TypeMapping:
    scala_type: str
    codec: str
    placeholder: str


_TYPES = {
    "String": TypeMapping("String", "ValueCodec.string", '""'),
    "Number": TypeMapping("BigDecimal", "ValueCodec.number", "BigDecimal(0)"),
    "Boolean": TypeMapping("Boolean", "ValueCodec.boolean", "false"),
    "Object": TypeMapping("JsonObject", "ValueCodec.obj", "JsonObject.empty"),
    "Any": TypeMapping("JsonValue", "ValueCodec.json", "JsonValue.Null"),
    "Array<Object>": TypeMapping(
        "Vector[JsonObject]", "ValueCodec.array(ValueCodec.obj)", "Vector.empty"
    ),
    "Array<String>": TypeMapping(
        "Vector[String]", "ValueCodec.array(ValueCodec.string)", "Vector.empty"
    ),
}


def renamed(name: str) -> str:
    return "payload" + name[0].upper() + name[1:] if name in _ENVELOPE else name


def reject_duplicates(kind: str, names: Iterable[str]) -> None:
    duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
    if duplicates:
        raise GenerationError(f"Duplicate {kind} names in schema: {', '.join(duplicates)}")


def validate_identifier(owner: str, kind: str, name: str) -> None:
    if not name:
        raise GenerationError(f"Empty field name at {owner}.{kind}")
    if not all(_IDENTIFIER.fullmatch(segment) for segment in name.split(".")):
        raise GenerationError(f"Invalid Scala identifier at {owner}.{kind}.{name}")
    if name in _RESERVED:
        raise GenerationError(
            f"Field name at {owner}.{kind}.{name} collides with a generated member"
        )


def _validate_type_name(kind: str, name: str) -> None:
    # Type names become filenames and unquoted Scala names. Never allow paths/code.
    if not _IDENTIFIER.fullmatch(name):
        raise GenerationError(f"Invalid generated {kind} name: {name!r}")


def _fields(
    owner: str, kind: str, fields: tuple[SchemaField, ...], nullable: frozenset[str]
) -> tuple[Field, ...]:
    reject_duplicates(f"field of {owner}.{kind}", (renamed(field.name) for field in fields))
    result = []
    for field in fields:
        validate_identifier(owner, kind, field.name)
        mapping = _TYPES.get(field.value_type)
        if mapping is None:
            raise GenerationError(
                f"Unsupported schema type: {field.value_type} at {owner}.{kind}.{field.name}"
            )
        accepts_null = f"{owner}.{kind}.{field.name}" in nullable
        description = [field.description]
        if field.restrictions is not None:
            description.append(f"Restrictions: {field.restrictions}")
        if field.optional_behavior is not None:
            description.append(f"When omitted: {field.optional_behavior}")
        result.append(
            Field(
                field.name,
                renamed(field.name),
                mapping.scala_type,
                mapping.codec,
                field.optional,
                accepts_null,
                " ".join(part for part in description if part),
                "None" if accepts_null else mapping.placeholder,
            )
        )
    return tuple(result)


def category_name(category: str) -> str:
    words = category.split(" ")
    name = words[0] + "".join(word[:1].upper() + word[1:] for word in words[1:])
    return "configuration" if name == "config" else name


def _categories(requests: tuple[RequestDefinition, ...]) -> tuple[RequestCategory, ...]:
    groups: dict[str, list[RequestDefinition]] = {}
    for request in requests:
        groups.setdefault(request.category, []).append(request)
    reject_duplicates("category", (category_name(category) for category in groups))
    result = []
    for category, members in sorted(groups.items()):
        name = category_name(category)
        validate_identifier("RequestApi", "category", name)
        result.append(RequestCategory(name, name[0].upper() + name[1:] + "Api", tuple(members)))
    return tuple(result)


def _enum(enumeration: SchemaEnum) -> EnumDefinition:
    name = enumeration.name
    reject_duplicates(
        f"identifier of enum {name}", (entry.identifier for entry in enumeration.entries)
    )
    for entry in enumeration.entries:
        if not _IDENTIFIER.fullmatch(entry.identifier):
            raise GenerationError(f"Invalid enum identifier at {name}.{entry.identifier}")
    values = tuple(entry.value for entry in enumeration.entries)
    if "" in values:
        raise GenerationError(f"Enum {name} has an empty enum value (JSON null in the schema)")
    textual = tuple(
        value
        for value in values
        if not (
            re.fullmatch(r"-?[0-9]+", value)
            or re.fullmatch(r"\([0-9A-Za-z_|<>&\s]+\)", value, re.ASCII)
        )
    )
    if textual and len(textual) != len(values):
        raise GenerationError(
            f"Enum {name} mixes numeric/bitmask and string values: {', '.join(sorted(textual))}"
        )
    scala_type = "String" if textual else "Long"
    siblings = {entry.identifier for entry in enumeration.entries}
    constants = []
    for entry in enumeration.entries:
        raw = entry.value
        if scala_type == "String":
            expression = quote(raw)
        elif raw.startswith("("):
            unknown = sorted(set(_IDENTIFIER.findall(raw)) - siblings)
            if unknown:
                raise GenerationError(
                    f"Enum {name} mask {raw} references identifiers that are not declared enum members: {', '.join(unknown)}"
                )
            expression = _IDENTIFIER.sub(lambda match: f"`{match[0]}`.value", raw)
        else:
            if not -(2**63) <= int(raw) < 2**63:
                raise GenerationError(f"Enum {name} value outside signed Long range: {raw}")
            expression = raw
        constants.append(EnumConstant(entry.identifier, expression, entry.description))
    return EnumDefinition(name, scala_type, tuple(constants))


def normalize(schema: Schema, overrides: Overrides) -> NormalizedSchema:
    """Validate catalog-wide invariants, then resolve ordered immutable definitions."""
    for kind, names in (
        ("request", tuple(request.name for request in schema.requests)),
        ("event", tuple(event.name for event in schema.events)),
        ("enum", tuple(enumeration.name for enumeration in schema.enums)),
    ):
        reject_duplicates(kind, names)
        for name in names:
            _validate_type_name(kind, name)
    request_names = {request.name for request in schema.requests}
    collisions = sorted(
        name + "Response" for name in request_names if name + "Response" in request_names
    )
    if collisions:
        raise GenerationError(
            f"Request names collide with generated response classes: {', '.join(collisions)}"
        )
    keys = {
        f"{request.name}.{kind}.{field.name}"
        for request in schema.requests
        for kind, fields in (
            ("request", request.request_fields),
            ("response", request.response_fields),
        )
        for field in fields
    } | {f"{event.name}.event.{field.name}" for event in schema.events for field in event.fields}
    nullable = frozenset(overrides.nullable_fields)
    unmatched = sorted(nullable - keys)
    if unmatched:
        raise GenerationError(f"Unmatched nullableFields overrides: {', '.join(unmatched)}")
    requests = tuple(
        RequestDefinition(
            request.name,
            _fields(request.name, "request", request.request_fields, nullable),
            _fields(request.name, "response", request.response_fields, nullable),
            request.documentation,
            request.category,
            request.name[0].lower() + request.name[1:],
        )
        for request in sorted(schema.requests, key=lambda request: request.name)
    )
    events = tuple(
        EventDefinition(
            event.name,
            _fields(event.name, "event", event.fields, nullable),
            event.documentation,
        )
        for event in sorted(schema.events, key=lambda event: event.name)
    )
    enums = tuple(
        _enum(enumeration)
        for enumeration in sorted(schema.enums, key=lambda enumeration: enumeration.name)
    )
    inventory = tuple(
        InventoryEntry(
            "request",
            request.name,
            request.documentation.initial_version,
            "; ".join(
                field.restrictions
                for field in request.request_fields
                if field.restrictions is not None
            ),
        )
        for request in schema.requests
    ) + tuple(
        InventoryEntry(
            "event",
            event.name,
            event.documentation.initial_version,
            "; ".join(
                field.restrictions for field in event.fields if field.restrictions is not None
            ),
        )
        for event in schema.events
    )
    return NormalizedSchema(requests, events, enums, _categories(requests), inventory)
