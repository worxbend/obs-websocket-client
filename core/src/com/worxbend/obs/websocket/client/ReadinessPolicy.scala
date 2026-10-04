package com.worxbend.obs.websocket.client

import scala.concurrent.duration.*

/** Explicit bounded retry of server NotReady rejections, never uncertain transport failures. */
final case class ReadinessPolicy(maxAttempts: Int = 3, delay: FiniteDuration = 100.millis):
  private[client] def validate: Either[ObsError, Unit] =
    if maxAttempts <= 0 || delay < Duration.Zero then
      Left(ObsError.InvalidConfiguration("Readiness attempts must be positive and delay nonnegative"))
    else Right(())

object ReadinessPolicy:
  /** Reviewed read operations only; raw extension requests and mutations cannot opt in. */
  val supportedRequests: Set[String] = Set(
    "GetVersion",
    "GetStats",
    "GetSceneList",
    "GetInputList",
    "GetCurrentProgramScene",
    "GetCurrentPreviewScene",
    "GetRecordStatus",
    "GetStreamStatus",
    "GetStudioModeEnabled"
  )
