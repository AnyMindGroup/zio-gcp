package com.anymindgroup.gcp.auth

import java.time.Instant

import zio.Config.Secret
import zio.Duration
import zio.json.*
import zio.json.ast.{Json, JsonCursor}

final case class AccessToken(token: Secret, expiresIn: Duration) {
  def expiresInOfPercent(percentage: Double): Duration =
    Duration.fromSeconds((expiresIn.getSeconds() * percentage).round)
}

object AccessToken {
  def fromJsonString(json: String): Either[String, AccessToken] =
    json.fromJson[Json.Obj].flatMap { m =>
      (for {
        t <- m.get(JsonCursor.field("access_token").isString).map(j => Secret(j.value))
        e <- m.get(JsonCursor.field("expires_in").isNumber).map(bd => Duration.fromSeconds(bd.value.longValue()))
      } yield AccessToken(token = t, expiresIn = e))
    }
}

final case class AccessTokenReceipt(token: AccessToken, receivedAt: Instant)
