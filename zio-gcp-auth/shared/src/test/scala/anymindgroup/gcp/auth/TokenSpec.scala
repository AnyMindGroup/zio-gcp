package com.anymindgroup.gcp.auth

import zio.Duration
import zio.test.*
import zio.test.Assertion.*

object TokenSpec extends ZIOSpecDefault {
  override def spec: Spec[Any, Any] = suite("TokenSpec")(
    test("read access token from json string") {
      val jsonStr     = """{ "access_token": "abc123", "expires_in": 3599 }"""
      val fromJsonRes = AccessToken.fromJsonString(jsonStr)

      for {
        _    <- assert(fromJsonRes)(isRight)
        token = fromJsonRes.toOption.get
        _    <- assertTrue(token.token == "abc123")
        _    <- assertTrue(token.expiresIn == Duration.fromSeconds(3599))
      } yield assertCompletes
    },
    test("read id token from string") {
      // token data
      // {
      //   "sub": "1234567890",
      //   "name": "John Doe",
      //   "iat": 1516239022,
      //   "exp": 1516239122
      // }
      val tokenStr =
        """eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE1MTYyMzkxMjJ9.-fM8Z-u88K5GGomqJxRCilYkjXZusY_Py6kdyzh1EAg"""
      val fromStrRes = IdToken.fromString(tokenStr)

      for {
        _    <- assert(fromStrRes)(isRight)
        token = fromStrRes.toOption.get
        _    <- assertTrue(token.token == tokenStr)
        _    <- assertTrue(token.expiresIn == Duration.fromSeconds(100))
        _    <- assertTrue(token.signature == "-fM8Z-u88K5GGomqJxRCilYkjXZusY_Py6kdyzh1EAg")
      } yield assertCompletes
    },
    test("calculate expiration by percentage") {
      val token = AccessToken("", Duration.fromSeconds(100))

      assertTrue(token.expiresInOfPercent(0.85) == Duration.fromSeconds(85)) &&
        assertTrue(token.expiresInOfPercent(0.009) == Duration.fromSeconds(1)) &&
        assertTrue(token.expiresInOfPercent(0.004) == Duration.fromSeconds(0))
    },
    test("noop token never expires") {
      assertTrue(Token.noop.expiresIn == Duration.Infinity)
    },
  )
}
