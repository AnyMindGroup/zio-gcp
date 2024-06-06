package com.anymindgroup.gcp.auth

import java.time.Instant

import zio.Config.Secret
import zio.Duration
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import java.util.Base64
import java.nio.charset.StandardCharsets
import scala.util.Try

sealed trait Token {
  def token: Secret
  def expiresIn: Duration

  def expiresInOfPercent(percentage: Double): Duration =
    Duration.fromSeconds((expiresIn.getSeconds() * percentage).round)
}

final case class AccessToken(token: Secret, expiresIn: Duration) extends Token

final case class IdToken(
  token: Secret,
  issuedAt: Instant,
  expiresAt: Instant,
  head: Json,
  payload: Json,
  signature: String,
) extends Token {
  def expiresIn: Duration = Duration.fromSeconds(expiresAt.getEpochSecond - issuedAt.getEpochSecond)
}

object IdToken {
  private def base64ToJson(v: String): Either[String, Json.Obj] =
    Try(new String(Base64.getDecoder.decode(v), StandardCharsets.UTF_8)).toEither.left
      .map(_.getMessage())
      .flatMap(_.fromJson[Json.Obj])

  def fromString(token: String): Either[String, IdToken] =
    token.split('.') match {
      case Array(h, p, signature, _*) =>
        for {
          head    <- base64ToJson(h)
          payload <- base64ToJson(p)
          iat <-
            payload.get("iat").get.asNumber.map(n => Instant.ofEpochSecond(n.value.longValue())).toRight("Missing iat")
          exp <-
            payload.get("exp").get.asNumber.map(n => Instant.ofEpochSecond(n.value.longValue())).toRight("Missing exp")
        } yield IdToken(
          token = Secret(token),
          head = head,
          payload = payload,
          signature = signature,
          issuedAt = iat,
          expiresAt = exp,
        )
      case _ => Left(s"Ivalid identity token: $token")
    }
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
