package com.anymindgroup.http

import scala.annotation.tailrec

import sttp.client4.Backend
import sttp.client4.curl.zio.CurlZioBackend

import zio.{Cause, Scope, Task, ZIO, ZLayer}

private[http] trait HttpClientBackendPlatformSpecific {
  def httpBackendLayer(verbose: Boolean = false): ZLayer[Any, Throwable, Backend[Task]] =
    CurlZioBackend.layer(verbose)

  def httpBackendScoped(verbose: Boolean = false): ZIO[Scope, Throwable, Backend[Task]] = CurlZioBackend.scoped(verbose)
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
