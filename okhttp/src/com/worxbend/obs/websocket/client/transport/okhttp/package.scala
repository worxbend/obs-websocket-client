package com.worxbend.obs.websocket.client.transport

import com.worxbend.obs.websocket.client.transport.sttp.SttpOptions

/** Configuration surface of the OkHttp adapter, so consumers need not import the sttp module. */
package object okhttp:
  /** Transport options for the OkHttp adapter; literally `SttpOptions` under the adapter's own name. */
  type OkHttpOptions = SttpOptions
  val OkHttpOptions: SttpOptions.type = SttpOptions
