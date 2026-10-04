package com.worxbend.obs.websocket.client

import com.worxbend.obs.websocket.client.protocol.JsonObject

/** One successful server response, retaining its responseData even when typed decoding fails. */
final case class ResponseEnvelope[A](raw: JsonObject, decoded: Either[ObsError, A])
