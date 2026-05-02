package com.anymindgroup.gcp.auth

import java.time.Instant

import sttp.capabilities.zio.ZioStreams
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.WebSocketStreamBackendStub
import sttp.client4.{UriContext, basicRequest}

import zio.test.{TestEnvironment, ZIOSpecDefault, *}
import zio.{Duration, Scope, Task, ZIO}

object AuthedBackendSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] = suite("AuthedBackendSpec") {
    val dummyToken = "123"

    val dummyTokenProvider: TokenProvider[Token] = new TokenProvider[Token]:
      def token = ZIO.succeed(TokenReceipt(AccessToken(dummyToken, Duration.Infinity), Instant.now()))

    val backendStub = WebSocketStreamBackendStub[Task, ZioStreams](new RIOMonadAsyncError[Any])
      .whenRequestMatches(r =>
        r.headers.exists(h => h.name.equalsIgnoreCase("Authorization") && h.value == s"Bearer $dummyToken")
      )
      .thenRespondOk()

    test("add token to request") {
      for {
        res <- AuthedBackend(dummyTokenProvider, backendStub).send(basicRequest.get(uri"http://example.com"))
        _   <- assertTrue(res.is200)
      } yield assertCompletes
    }
  }
}
