package com.worxbend.obs.websocket.client.codegen

import model.NormalizedSchema
import schema.Provenance
import templates.*

/** Pure output orchestration. Schema interpretation lives in SchemaNormalizer and source layout in templates. */
private[codegen] object CodeGenerator:
  /** Returns relative filenames and complete contents in the established deterministic order. */
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
