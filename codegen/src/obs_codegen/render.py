"""Repository-owned complete-file Jinja templates, rendered entirely in memory."""

from pathlib import Path

from jinja2 import Environment, FileSystemLoader, StrictUndefined

from .model import Field, NormalizedSchema, RenderedFile
from .scala import BASE_PACKAGE, collapse, quote
from .schema import Provenance

TEMPLATE_ROOT = Path(__file__).resolve().parents[2] / "templates"


def _dotted(fields: tuple[Field, ...]) -> bool:
    return any("." in field.name for field in fields)


class Renderer:
    """Own the single dynamic boundary; templates receive only normalized records."""

    def __init__(self, template_root: Path = TEMPLATE_ROOT) -> None:
        self.environment = Environment(
            loader=FileSystemLoader(str(template_root)),
            undefined=StrictUndefined,
            autoescape=False,
            keep_trailing_newline=True,
            newline_sequence="\n",
            trim_blocks=True,
            lstrip_blocks=True,
        )
        self.environment.filters.update(quote=quote, collapse=collapse, dotted=_dotted)

    def render(self, schema: NormalizedSchema, provenance: Provenance) -> tuple[RenderedFile, ...]:
        context = {"schema": schema, "provenance": provenance, "base": BASE_PACKAGE}
        outputs = []
        for request in schema.requests:
            outputs.append(
                RenderedFile(
                    f"requests/{request.name}.scala",
                    self.environment.get_template("request.scala.j2").render(
                        **context, request=request
                    ),
                )
            )
        for event in schema.events:
            outputs.append(
                RenderedFile(
                    f"events/{event.name}.scala",
                    self.environment.get_template("event.scala.j2").render(**context, event=event),
                )
            )
        for enumeration in schema.enums:
            outputs.append(
                RenderedFile(
                    f"enums/{enumeration.name}.scala",
                    self.environment.get_template("enum.scala.j2").render(
                        **context, enumeration=enumeration
                    ),
                )
            )
        for path, template in (
            ("Event.scala", "event_dispatch.scala.j2"),
            ("catalog-inventory.tsv", "inventory.tsv.j2"),
            ("Catalog.scala", "catalog.scala.j2"),
            ("RequestApi.scala", "request_api.scala.j2"),
        ):
            outputs.append(
                RenderedFile(path, self.environment.get_template(template).render(**context))
            )
        return tuple(outputs)
