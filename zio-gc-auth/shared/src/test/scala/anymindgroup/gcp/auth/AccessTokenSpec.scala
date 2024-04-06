package com.anymindgroup.gcp.auth

import zio.test.*
import zio.test.Assertion.*
import zio.{Config, Duration}

object AccessTokenSpec extends ZIOSpecDefault {
  override def spec: Spec[Any, Any] = suite("AccessTokenSpec")(
    test("read from json string") {
      val jsonStr     = """{ "access_token": "abc123", "expires_in": 3599 }"""
      val fromJsonRes = AccessToken.fromJsonString(jsonStr)

      for {
        _    <- assert(fromJsonRes)(isRight)
        token = fromJsonRes.toOption.get
        _    <- assertTrue(token.token == Config.Secret("abc123"))
        _    <- assertTrue(token.expiresIn == Duration.fromSeconds(3599))
      } yield assertCompletes
    },
    test("calculate expiration by percentage") {
      val token = AccessToken(Config.Secret(""), Duration.fromSeconds(100))

      assertTrue(token.expiresInOfPercent(0.85) == Duration.fromSeconds(85)) &&
        assertTrue(token.expiresInOfPercent(0.009) == Duration.fromSeconds(1)) &&
        assertTrue(token.expiresInOfPercent(0.004) == Duration.fromSeconds(0))
    },
  )
}
