package com.anymindgroup.pubsub.http

import sttp.client4.impl.zio.CurlZioBackend
import zio.{ZLayer, Task, ZIO}
import sttp.client4.GenericBackend

trait HttpClientBackendPlatformSpecific {
  def backendLayer(): ZLayer[Any, Throwable, GenericBackend[Task, Any]] =
    ZLayer.scoped(
      ZIO.acquireRelease(ZIO.attempt(CurlZioBackend[Any]()))(c => ZIO.attempt(c.close()).orDie)
    )
}
