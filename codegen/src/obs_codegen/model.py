"""Typed, deeply immutable handoff from normalization to Jinja."""

from dataclasses import dataclass

from .schema import Documentation


@dataclass(frozen=True, slots=True)
class Field:
    name: str
    identifier: str
    scala_type: str
    codec: str
    optional: bool
    nullable: bool
    description: str
    placeholder: str


@dataclass(frozen=True, slots=True)
class RequestDefinition:
    name: str
    request_fields: tuple[Field, ...]
    response_fields: tuple[Field, ...]
    documentation: Documentation
    category: str
    method_name: str


@dataclass(frozen=True, slots=True)
class EventDefinition:
    name: str
    fields: tuple[Field, ...]
    documentation: Documentation


@dataclass(frozen=True, slots=True)
class EnumConstant:
    identifier: str
    expression: str
    description: str


@dataclass(frozen=True, slots=True)
class EnumDefinition:
    name: str
    scala_type: str
    constants: tuple[EnumConstant, ...]


@dataclass(frozen=True, slots=True)
class RequestCategory:
    name: str
    class_name: str
    requests: tuple[RequestDefinition, ...]


@dataclass(frozen=True, slots=True)
class InventoryEntry:
    kind: str
    name: str
    initial_version: str
    restrictions: str


@dataclass(frozen=True, slots=True)
class NormalizedSchema:
    requests: tuple[RequestDefinition, ...]
    events: tuple[EventDefinition, ...]
    enums: tuple[EnumDefinition, ...]
    categories: tuple[RequestCategory, ...]
    inventory: tuple[InventoryEntry, ...]


@dataclass(frozen=True, slots=True)
class RenderedFile:
    path: str
    content: str
