package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.AuthedBackend
import com.anymindgroup.http.httpBackendScoped
import com.anymindgroup.pubsub.PubsubConnectionConfig
import sttp.client4.{GenericRequest, Response}
import sttp.monad.MonadError

import zio.{Scope, Task, ZIO}

trait EmulatorBackend extends AuthedBackend

object EmulatorBackend:
  def apply(backend: HttpPlatformBackend, config: PubsubConnectionConfig.Emulator): EmulatorBackend =
    new EmulatorBackend {
      override def send[T](req: GenericRequest[T, HttpPlatformCapabilities]): Task[Response[T]] =
        backend.send(req.method(req.method, req.uri.scheme("http").host(config.host).port(config.port)))

      override def close(): Task[Unit] = backend.close()

      override def monad: MonadError[Task] = backend.monad
    }

  def withDefaultBackend(
    config: PubsubConnectionConfig.Emulator
  ): ZIO[Scope, Throwable, EmulatorBackend] =
    httpBackendScoped().map(backend => apply(backend, config))
