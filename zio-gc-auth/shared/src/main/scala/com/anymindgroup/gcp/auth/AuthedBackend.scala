package com.anymindgroup.gcp.auth

import com.anymindgroup.gcp.auth.{Token, TokenProvider}
import sttp.capabilities.Effect
import sttp.client4.{GenericBackend, GenericRequest, Response}
import sttp.monad.MonadError

import zio.Task

object AuthedBackend {
  def apply[P](tp: TokenProvider[Token], backend: GenericBackend[Task, P]) = new GenericBackend[Task, P] {
    override def send[T](request: GenericRequest[T, P & Effect[Task]]): Task[Response[T]] =
      tp.token.flatMap(t => backend.send(request.auth.bearer(t.token.token)))

    override def close(): Task[Unit] = backend.close()

    override def monad: MonadError[Task] = backend.monad
  }
}
