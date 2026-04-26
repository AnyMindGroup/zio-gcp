package com.anymindgroup.http

import scala.annotation.tailrec

import sttp.capabilities.Effect
import sttp.capabilities.zio.ZioStreams

import zio.{Cause, Scope, Task, ZIO, ZLayer}

type HttpPlatformBackend      = sttp.client4.StreamBackend[Task, ZioStreams]
type HttpPlatformCapabilities = ZioStreams & Effect[Task]

private[http] trait HttpClientBackendPlatformSpecific {
  def httpBackendLayer(verbose: Boolean = false): ZLayer[Any, Throwable, CurlZioBackendV2] =
    CurlZioBackendV2.layer(verbose)

  def httpBackendScoped(verbose: Boolean = false): ZIO[Scope, Throwable, CurlZioBackendV2] =
    CurlZioBackendV2.scoped(verbose)
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
