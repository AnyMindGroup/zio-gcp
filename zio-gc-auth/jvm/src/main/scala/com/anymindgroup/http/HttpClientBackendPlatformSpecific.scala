package com.anymindgroup.http

import java.net.http.HttpClient

import scala.annotation.tailrec

import com.anymindgroup.gcp.auth.{AuthedBackend, Token, TokenProvider}
import sttp.capabilities.zio.ZioStreams
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend

import zio.{Cause, Task, ZIO, ZLayer}

trait HttpClientBackendPlatformSpecific {
  def httpBackendLayer(): ZLayer[Any, Throwable, GenericBackend[Task, ZioStreams]] =
    ZLayer.scoped(
      ZIO
        .fromAutoCloseable(
          ZIO.attempt(
            HttpClient
              .newBuilder()
              .followRedirects(HttpClient.Redirect.NEVER)
              .version(HttpClient.Version.HTTP_2)
              .build()
          )
        )
        .map(HttpClientZioBackend.usingClient(_))
    )

  def authedHttpBackendLayer()
    : ZLayer[TokenProvider[Token] & GenericBackend[Task, ZioStreams], Nothing, GenericBackend[Task, ZioStreams]] =
    ZLayer.fromFunction(AuthedBackend[ZioStreams](_, _))
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
