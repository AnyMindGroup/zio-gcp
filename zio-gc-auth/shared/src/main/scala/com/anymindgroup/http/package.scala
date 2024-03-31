package com.anymindgroup

import sttp.model.{Uri, UriInterpolator}

import zio.Task

package object http {
  // to allow for easy switch between sttp client versions
  import sttp.client4 as sttp_client
  // import sttp.client3 as sttp_client

  type HttpBackend          = sttp_client.GenericBackend[Task, Any]
  type Request[T]           = sttp_client.Request[T]
  type Response[T]          = sttp_client.Response[T]
  type SttpConnectException = sttp_client.SttpClientException.ConnectException
  val basicRequest = sttp_client.basicRequest

  // from sttp.client4.UriContext
  implicit class UriContext(val sc: StringContext) {
    def uri(args: Any*): Uri = UriInterpolator.interpolate(sc, args: _*)
  }
}
