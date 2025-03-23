package com.anymindgroup.gcp.auth

import com.anymindgroup.http.httpBackendScoped
import sttp.capabilities.Effect
import sttp.client4.{Backend, GenericRequest, Response}
import sttp.monad.MonadError

import zio.*

trait AuthedBackend extends Backend[Task]

object AuthedBackend:
  def apply(tp: TokenProvider[Token], backend: Backend[Task]): AuthedBackend =
    new AuthedBackend:
      override def send[T](request: GenericRequest[T, Effect[Task]]): Task[Response[T]] =
        tp.token.flatMap(t => backend.send(request.auth.bearer(t.token.token)))
      override def close(): Task[Unit]     = backend.close()
      override def monad: MonadError[Task] = backend.monad

  def defaultAccessTokenBackend(
    lookupComputeMetadataFirst: Boolean = false,
    refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[Scope, Throwable, AuthedBackend] =
    httpBackendScoped().flatMap: backend =>
      TokenProvider
        .defaultAccessTokenProvider(
          backend = backend,
          lookupComputeMetadataFirst = lookupComputeMetadataFirst,
          refreshRetrySchedule = refreshRetrySchedule,
          refreshAtExpirationPercent = refreshAtExpirationPercent,
        )
        .map(tp => AuthedBackend(tp, backend))

  def defaultIdTokenBackend(
    audience: String,
    lookupComputeMetadataFirst: Boolean = false,
    refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[Scope, Throwable, AuthedBackend] =
    httpBackendScoped().flatMap: backend =>
      TokenProvider
        .defaultIdTokenProvider(
          audience = audience,
          backend = backend,
          lookupComputeMetadataFirst = lookupComputeMetadataFirst,
          refreshRetrySchedule = refreshRetrySchedule,
          refreshAtExpirationPercent = refreshAtExpirationPercent,
        )
        .map(tp => AuthedBackend(tp, backend))

export AuthedBackend.apply as toAuthedBackend
export AuthedBackend.defaultAccessTokenBackend
export AuthedBackend.defaultIdTokenBackend
