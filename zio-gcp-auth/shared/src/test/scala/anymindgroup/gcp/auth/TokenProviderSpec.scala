package com.anymindgroup.gcp.auth

import java.time.Duration

import sttp.client4.*
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.*
import sttp.model.*

import zio.test.*
import zio.test.Assertion.*
import zio.{Config, Ref, Schedule, Scope, Task, ULayer, ZIO, ZLayer, ZLogger}

object TokenProviderSpec extends ZIOSpecDefault {

  val tokenExpirySeconds: Int = 4000

  val okUserAccount: Credentials.UserAccount   = Credentials.UserAccount("token", "user", Config.Secret("123"))
  val failUserAccount: Credentials.UserAccount = Credentials.UserAccount("token", "fail_user", Config.Secret("123"))
  val testIdToken                              =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyMzkxMjJ9.-fM8Z-u88K5GGomqJxRCilYkjXZusY_Py6kdyzh1EAg"

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("TokenProviderSpec")(
    test("no token provider") {
      for {
        token <- TokenProvider.noTokenProvider.token
        _     <- assertTrue(token.token == Token.noop)
      } yield assertCompletes
    },
    test("request access token for user account") {
      for {
        backend <- ZIO.service[Backend[Task]]
        tp      <- TokenProvider.accessTokenProvider(okUserAccount, backend)
        token   <- tp.token
        _       <- assertTrue(token.token.token == "user")
      } yield assertCompletes
    }.provideSome[Scope](googleStubBackendLayer()),
    test("fail on service account as it's not supported (yet)") {
      val svcAcc = Credentials.ServiceAccountKey("email", Config.Secret("123"))
      for {
        backend <- ZIO.service[Backend[Task]]
        _       <- assertZIO(TokenProvider.accessTokenProvider(svcAcc, backend).exit)(
               failsWithA[TokenProviderException.CredentialsFailure]
             )
        _ <- assertZIO(TokenProvider.idTokenProvider("", svcAcc, backend).exit)(
               failsWithA[TokenProviderException.CredentialsFailure]
             )
      } yield assertCompletes
    }.provideSome[Scope](googleStubBackendLayer()),
    test("fail on getting id token for a user account as it's not supported (yet)") {
      val user = Credentials.UserAccount("", "", Config.Secret(""))
      for {
        backend <- ZIO.service[Backend[Task]]
        _       <- assertZIO(TokenProvider.idTokenProvider("", user, backend).exit)(
               failsWithA[TokenProviderException.CredentialsFailure]
             )
      } yield assertCompletes
    }.provideSome[Scope](googleStubBackendLayer()),
    test("request access token from compute metadata server") {
      for {
        backend <- ZIO.service[Backend[Task]]
        tp      <- TokenProvider.accessTokenProvider(Credentials.ComputeServiceAccount(""), backend)
        token   <- tp.token
        _       <- assertTrue(token.token.token == "compute")
      } yield assertCompletes
    }.provideSome[Scope](googleStubBackendLayer()),
    test("request id token from compute metadata server") {
      val audience = "http://test.com"
      (for {
        backend <- ZIO.service[Backend[Task]]
        tp      <- TokenProvider.idTokenProvider(audience = audience, Credentials.ComputeServiceAccount(""), backend)
        token   <- tp.token
        _       <- assertTrue(token.token.token == testIdToken)
      } yield assertCompletes).provideSome[Scope](googleStubBackendLayer(audience))
    },
    test("token is refreshed automatically at given expiry stage") {
      checkN(10)(Gen.double(0.1, 0.9)) { expiryPercent =>
        for {
          backend <- ZIO.service[Backend[Task]]
          tp      <- TokenProvider.accessTokenProvider(
                  credentials = Credentials.ComputeServiceAccount(""),
                  backend = backend,
                  refreshAtExpirationPercent = expiryPercent,
                )
          tokenA   <- tp.token
          adustSecs = Duration.ofSeconds((tokenExpirySeconds * expiryPercent).round).plusSeconds(1)
          _        <- TestClock.adjust(adustSecs)
          tokenB   <- tp.token
          _        <- assertTrue(tokenA.receivedAt.isBefore(tokenB.receivedAt))
        } yield assertCompletes
      }
    }.provideSome[Scope](googleStubBackendLayer()),
    test("retry on failures by given schedule") {
      for {
        retries   <- Ref.make(0)
        maxRetries = 5
        tp        <- ZIO
                .serviceWithZIO[Backend[Task]](backend =>
                  TokenProvider
                    .accessTokenProvider(
                      credentials = failUserAccount,
                      backend = backend,
                      refreshRetrySchedule = Schedule.recurs(maxRetries),
                    )
                )
                .provideSome[Scope](googleStubBackendLayerWithFailureCount(retries))
                .exit
        _ <- assert(tp)(fails(anything))
        _ <- assertZIO(retries.get)(equalTo(maxRetries))
      } yield assertCompletes
    },
  ).provideSomeLayerShared[Scope](zio.Runtime.removeDefaultLoggers >>> ZLayer.succeed(ZLogger.none))

  def googleStubBackendLayerWithFailureCount(ref: Ref[Int]): ULayer[Backend[Task]] =
    ZLayer.succeed(googleStubBackend(ref, ""))

  def googleStubBackendLayer(idTokenAudience: String = ""): ZLayer[Any, Nothing, Backend[Task]] = ZLayer {
    Ref.make(0).map { ref =>
      googleStubBackend(ref, idTokenAudience)
    }
  }

  def googleStubBackend(failures: Ref[Int], idTokenAudience: String): Backend[Task] =
    BackendStub[Task](new RIOMonadAsyncError[Any])
      .whenRequestMatches(
        _.uri.toString.endsWith("computeMetadata/v1/instance/service-accounts/default/email")
      )
      .thenRespondAdjust("test@gcp-project.iam.gserviceaccount.com")
      .whenRequestMatches(_.uri.toString.endsWith("computeMetadata/v1/instance/service-accounts/default/token"))
      .thenRespondAdjust(s"""{"access_token":"compute","expires_in":$tokenExpirySeconds,"token_type":"Bearer"}""")
      .whenRequestMatches(
        _.uri.toString.endsWith(
          s"computeMetadata/v1/instance/service-accounts/default/identity?audience=$idTokenAudience"
        )
      )
      .thenRespondAdjust(testIdToken)
      .whenRequestMatches { r =>
        r.method == Method.POST && r.uri.toString() == "https://oauth2.googleapis.com/token" &&
        (r.body match {
          case StringBody(s, _, _) if !s.contains(failUserAccount.clientId) =>
            Set("grant_type", "refresh_token", "client_id", "client_secret").exists(s.contains(_))
          case _ => false
        })
      }
      .thenRespondAdjust(
        s"""{"access_token":"user","expires_in":$tokenExpirySeconds,"token_type":"Bearer"}"""
      )
      .whenRequestMatches { r =>
        r.uri.toString() == "https://oauth2.googleapis.com/token" && (r.body match {
          case StringBody(s, _, _) => s.contains(failUserAccount.clientId)
          case _                   => false
        })
      }
      .thenRespondF(_ =>
        failures
          .getAndUpdate(_ + 1)
          .as(ResponseStub.adjust("", StatusCode.Forbidden))
      )
}
