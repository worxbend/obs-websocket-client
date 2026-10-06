"""Immutable decoded input records; no filesystem or Scala dependencies."""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Documentation:
    summary: str = ""
    initial_version: str = ""
    rpc_version: str = ""
    deprecated: bool = False


@dataclass(frozen=True, slots=True)
class SchemaField:
    name: str
    value_type: str
    optional: bool = False
    restrictions: str | None = None
    description: str = ""
    optional_behavior: str | None = None


@dataclass(frozen=True, slots=True)
class SchemaRequest:
    name: str
    request_fields: tuple[SchemaField, ...] = ()
    response_fields: tuple[SchemaField, ...] = ()
    documentation: Documentation = Documentation()
    category: str = "general"


@dataclass(frozen=True, slots=True)
class SchemaEvent:
    name: str
    fields: tuple[SchemaField, ...] = ()
    documentation: Documentation = Documentation()


@dataclass(frozen=True, slots=True)
class SchemaEnumEntry:
    identifier: str
    value: str
    description: str = ""


@dataclass(frozen=True, slots=True)
class SchemaEnum:
    name: str
    entries: tuple[SchemaEnumEntry, ...] = ()


@dataclass(frozen=True, slots=True)
class Schema:
    requests: tuple[SchemaRequest, ...] = ()
    events: tuple[SchemaEvent, ...] = ()
    enums: tuple[SchemaEnum, ...] = ()


@dataclass(frozen=True, slots=True)
class Overrides:
    nullable_fields: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class Provenance:
    repository: str
    revision: str
    sha256: str

    @property
    def upstream_docs(self) -> str:
        return f"{self.repository}/blob/{self.revision}/docs/generated/protocol.md"
