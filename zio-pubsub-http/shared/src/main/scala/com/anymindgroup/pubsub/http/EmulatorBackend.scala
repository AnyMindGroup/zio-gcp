package com.anymindgroup.pubsub.http

import com.anymindgroup.gcp.auth.AuthedBackend
import com.anymindgroup.http.*
import com.anymindgroup.pubsub.PubsubConnectionConfig
import sttp.capabilities.Effect
import sttp.client4.{Backend, GenericRequest, Response}
import sttp.monad.MonadError

import zio.{Scope, Task, ZIO}

trait EmulatorBackend extends AuthedBackend

object EmulatorBackend:
  def apply(backend: Backend[Task], config: PubsubConnectionConfig.Emulator): EmulatorBackend =
    new EmulatorBackend {
      override def send[T](req: GenericRequest[T, Effect[Task]]): Task[Response[T]] =
        backend.send(req.method(req.method, req.uri.scheme("http").host(config.host).port(config.port)))

      override def close(): Task[Unit] = backend.close()

      override def monad: MonadError[Task] = backend.monad
    }

  def withDefaultBackend(
    config: PubsubConnectionConfig.Emulator
  ): ZIO[Scope, Throwable, EmulatorBackend] =
    httpBackendScoped().map(backend => apply(backend, config))
