package com.anymindgroup.http

import java.net.http.HttpClient

import scala.annotation.tailrec

import sttp.capabilities.zio.ZioStreams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.httpclient.zio.HttpClientZioBackend

import zio.{Cause, Scope, Task, ZIO, ZLayer}

private[http] trait HttpClientBackendPlatformSpecific {
  def httpBackendLayer(): ZLayer[Any, Throwable, WebSocketStreamBackend[Task, ZioStreams]] =
    ZLayer.scoped(httpBackendScoped())

  def httpBackendScoped(): ZIO[Scope, Throwable, WebSocketStreamBackend[Task, ZioStreams]] = ZIO
    .acquireRelease(
      ZIO.attempt(
        HttpClientZioBackend.usingClient(
          HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_2)
            .build()
        )
      )
    )(_.close().ignore)
}

class UnresolvedAddressException(underlying: Throwable) extends Throwable(underlying)

object UnresolvedAddressException {
  def unapply(cause: Cause[Throwable]): Option[UnresolvedAddressException] = findCause(cause.failures)
  def unapply(cause: Throwable): Option[UnresolvedAddressException]        = findCause(List(cause))

  @tailrec
  private def findCause(errs: List[Throwable]): Option[UnresolvedAddressException] =
    errs match {
      case (e: java.nio.channels.UnresolvedAddressException) :: _ => Some(new UnresolvedAddressException(e))
      case e :: errs                                              => findCause(Option(e.getCause()).toList ::: errs)
      case Nil                                                    => None
    }
}
