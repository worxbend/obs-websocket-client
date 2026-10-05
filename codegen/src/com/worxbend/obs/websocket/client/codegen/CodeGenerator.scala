package com.worxbend.obs.websocket.client.codegen

import model.NormalizedSchema
import schema.Provenance
import templates.*

/** Maps a normalized catalog to the seven output families without filesystem access.
  *
  * Schema interpretation belongs to `schema.SchemaNormalizer`; exact source layout belongs to `templates`. This layer
  * owns filenames and constructs each template's typed context. Adding a new output family therefore requires a
  * renderer and an explicit entry here, without teaching the runner about source syntax.
  */
private[codegen] object CodeGenerator:
  /** Renders complete files, preserving the normalized request/event/enum order.
    *
    * Each request produces `requests/<name>.scala` containing both request and response types; events and enums produce
    * `events/<name>.scala` and `enums/<name>.scala`. Four shared outputs follow: event dispatch (`Event.scala`), the
    * TSV inventory, request discovery/decoding (`Catalog.scala`), and category facades (`RequestApi.scala`).
    *
    * @param schema
    *   already validated and ordered definitions; this method does not repeat normalization
    * @param provenance
    *   pinned checksum and documentation origin passed to Scala source templates
    * @return
    *   relative paths paired with complete contents, ready for Generate to write beneath its output root
    */
  def generate(schema: NormalizedSchema, provenance: Provenance): Vector[(String, String)] =
    val requests = schema.requests.map: request =>
      s"requests/${request.name}.scala" -> RequestTemplate.render(RequestTemplate.Context(request, provenance))
    val events = schema.events.map: event =>
      s"events/${event.name}.scala" -> EventTemplate.render(EventTemplate.Context(event, provenance))
    val enums = schema.enums.map: enumeration =>
      s"enums/${enumeration.name}.scala" -> EnumTemplate.render(EnumTemplate.Context(enumeration, provenance))
    (requests ++ events ++ enums).toVector ++ Vector(
      "Event.scala" -> EventDispatchTemplate.render(
        EventDispatchTemplate.Context(schema.events.map(_.name), provenance)
      ),
      "catalog-inventory.tsv" -> InventoryTemplate.render(InventoryTemplate.Context(schema.inventory)),
      "Catalog.scala" -> CatalogTemplate.render(CatalogTemplate.Context(schema.requests.map(_.name), provenance)),
      "RequestApi.scala" -> RequestApiTemplate.render(RequestApiTemplate.Context(schema.categories, provenance))
    )
