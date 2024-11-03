package com.anymindgroup.http

import scala.annotation.tailrec

import com.anymindgroup.gcp.auth.{AuthedBackend, Token, TokenProvider}
import sttp.client4.GenericBackend
import sttp.client4.impl.zio.CurlZioBackend

import zio.{Cause, Task, ZIO, ZLayer}

trait HttpClientBackendPlatformSpecific {
  def httpBackendLayer(): ZLayer[Any, Throwable, GenericBackend[Task, Any]] =
    ZLayer.scoped(
      ZIO.acquireRelease(ZIO.attempt(CurlZioBackend()))(c => ZIO.attempt(c.close()).ignore)
    )

  def authedHttpBackendLayer()
    : ZLayer[TokenProvider[Token] & GenericBackend[Task, Any], Throwable, GenericBackend[Task, Any]] =
    ZLayer.fromFunction(AuthedBackend[Any](_, _))
}

class UnresolvedAddressException(underlying: Throwable) extends Throwable(underlying)

object UnresolvedAddressException {
  def unapply(cause: Cause[Throwable]): Option[UnresolvedAddressException] = findCause(cause.failures)
  def unapply(cause: Throwable): Option[UnresolvedAddressException]        = findCause(List(cause))

  @tailrec
  private def findCause(errs: List[Throwable]): Option[UnresolvedAddressException] =
    errs match {
      case e :: _ if e.getMessage.contains("COULDNT_RESOLVE_HOST") => Some(new UnresolvedAddressException(e))
      case e :: errs                                               => findCause(Option(e.getCause()).toList ::: errs)
      case Nil                                                     => None
    }
}
