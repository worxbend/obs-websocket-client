package com.worxbend.obs.websocket.client.protocol

import munit.FunSuite
import java.nio.charset.StandardCharsets.UTF_8

class RequestApiSuite extends FunSuite:
  private val stream = getClass.getResourceAsStream("/catalog-fixtures.json")
  private val fixtures =
    try
      JsonValue
        .parse(String(stream.readAllBytes(), UTF_8))
        .toOption
        .get
        .asInstanceOf[JsonValue.Arr]
        .value
        .map(_.asInstanceOf[JsonObject])
    finally stream.close()

  test("every category method submits the independent catalog fixture with all named arguments"):
    var submitted = Vector.empty[Request[?]]
    val api = new RequestApi[String]:
      def request[A](request: Request[A]): Either[String, A] =
        submitted = submitted :+ request
        Left("recorded")
    val categories = api.getClass.getMethods.filter(method =>
      method.getParameterCount == 0 && method.getReturnType.getSimpleName.endsWith("Api")
    )
    assertEquals(categories.length, 14)
    categories.foreach: category =>
      val target = category.invoke(api)
      target.getClass.getDeclaredMethods
        .filter(!_.getName.contains("$default$"))
        .foreach: method =>
          val name = s"${method.getName.head.toUpper}${method.getName.tail}"
          val fixture = fixtures.find(f => f.string("kind").contains("request") && f.string("name").contains(name)).get
          val request = Catalog.decodeRequest(name, fixture.obj("valid").toOption.get).toOption.get
          val args = request.asInstanceOf[Product].productIterator.map(_.asInstanceOf[Object]).toArray
          assertEquals(method.invoke(target, args*), Left("recorded"))
          assertEquals(submitted.last.requestType, name)
          assertEquals(submitted.last.requestData, request.requestData)
      target.getClass.getDeclaredMethods
        .filter(_.getName.contains("$default$"))
        .foreach: method =>
          assertEquals(method.invoke(target), Field.Missing)
    assertEquals(submitted.map(_.requestType).sorted, Catalog.requestNames.toVector.sorted)

  test("every event selector selects its decoded subtype and rejects an unrelated event"):
    fixtures
      .filter(_.string("kind").contains("event"))
      .foreach: fixture =>
        val name = fixture.string("name").toOption.get
        val companion = Class.forName(s"com.worxbend.obs.websocket.client.protocol.events.$name$$")
        val singleton = companion.getField("MODULE$").get(null)
        val selector = companion.getMethod("selector").invoke(singleton).asInstanceOf[EventSelector[Event]]
        val event = Event.decode(name, fixture.obj("valid").toOption.get).toOption.get
        assertEquals(selector.eventType, name)
        assertEquals(selector.select(event), Some(event))
        assertEquals(selector.select(UnknownEvent(name, JsonObject.empty)), None)
