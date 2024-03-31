package com.anymindgroup.http

import scala.annotation.tailrec

import sttp.client4.httpclient.zio.HttpClientZioBackend

import zio.{Cause, ZLayer}

trait HttpClientBackendPlatformSpecific {
  def httpBackendLayer(): ZLayer[Any, Throwable, HttpBackend] = HttpClientZioBackend.layer()
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
