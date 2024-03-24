package com.anymindgroup.http

import zio.{ZLayer, Task}
import sttp.client4.GenericBackend
import sttp.client4.httpclient.zio.HttpClientZioBackend

trait HttpClientBackendPlatformSpecific {
  def backendLayer(): ZLayer[Any, Throwable, GenericBackend[Task, Any]] = HttpClientZioBackend.layer()
}
