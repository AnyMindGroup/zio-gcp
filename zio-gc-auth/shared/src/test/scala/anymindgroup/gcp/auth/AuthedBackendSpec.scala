package com.anymindgroup.gcp.auth

import java.time.Instant

import com.anymindgroup.http.{HttpClientBackendPlatformSpecific, basicRequest}
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.BackendStub
import sttp.client4.{GenericBackend, UriContext}

import zio.test.{TestEnvironment, ZIOSpecDefault, *}
import zio.{Duration, Scope, Task, UIO, ULayer, ZIO, ZLayer}

object AuthedBackendSpec extends ZIOSpecDefault with HttpClientBackendPlatformSpecific {
  override def spec: Spec[TestEnvironment with Scope, Any] = suite("AuthedBackendSpec") {
    test("add token to request") {
      for {
        res <- ZIO.serviceWithZIO[GenericBackend[Task, Any]](_.send(basicRequest.get(uri"http://example.com")))
        _   <- assertTrue(res.is200)
      } yield assertCompletes
    }.provide(
      (backendStub ++ dummyTokenProvider) >>> ZLayer.fromFunction(AuthedBackend[Any](_, _))
    )
  }

  val dummyToken = "123"

  val dummyTokenProvider: ULayer[TokenProvider[Token]] = ZLayer.succeed(new TokenProvider[Token] {
    override def token: UIO[TokenReceipt[Token]] =
      ZIO.succeed(TokenReceipt(AccessToken(dummyToken, Duration.Infinity), Instant.now()))
  })

  val backendStub: ULayer[GenericBackend[Task, Any]] = ZLayer.succeed(
    BackendStub[Task](new RIOMonadAsyncError[Any])
      .whenRequestMatches(r =>
        r.headers.exists(h => h.name.equalsIgnoreCase("Authorization") && h.value == s"Bearer $dummyToken")
      )
      .thenRespondOk()
  )
}
