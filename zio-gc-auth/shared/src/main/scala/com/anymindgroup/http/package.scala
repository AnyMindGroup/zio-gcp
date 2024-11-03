package com.anymindgroup

import sttp.model.{Uri, UriInterpolator}

package object http {
  // to allow for easy switch between sttp client versions
  import sttp.client4 as sttp_client
  // import sttp.client3 as sttp_client

  type Request[T]              = sttp_client.Request[T]
  type Response[T]             = sttp_client.Response[T]
  type SttpConnectException    = sttp_client.SttpClientException.ConnectException
  type DuplicateHeaderBehavior = sttp_client.DuplicateHeaderBehavior
  val DuplicateHeaderBehavior = sttp_client.DuplicateHeaderBehavior
  val basicRequest            = sttp_client.basicRequest

  // from sttp.client4.UriContext
  implicit class UriContext(val sc: StringContext) {
    def uri(args: Any*): Uri = UriInterpolator.interpolate(sc, args*)
  }
}
