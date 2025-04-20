package com.anymindgroup.gcp.auth

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import zio.Duration

sealed trait Token {
  def token: String
  def expiresIn: Duration

  def expiresInOfPercent(percentage: Double): Duration =
    Duration.fromSeconds((expiresIn.getSeconds() * percentage).round)
}

object Token {
  val noop: Token = new Token {
    override val token: String       = ""
    override val expiresIn: Duration = Duration.Infinity
  }
}

final case class AccessToken(token: String, expiresIn: Duration) extends Token
object AccessToken {
  private given JsonValueCodec[Duration] = new JsonValueCodec[Duration] {
    def decodeValue(in: JsonReader, default: Duration): Duration = Duration.fromSeconds(in.readLong())
    def encodeValue(x: Duration, out: JsonWriter): Unit          = out.writeVal(x.getSeconds())
    // scalafix:off DisableSyntax.null
    val nullValue: Duration = null.asInstanceOf[Duration]
    // scalafix:on DisableSyntax.null
  }

  private given jsonCodec(using JsonValueCodec[Duration]): JsonValueCodec[AccessToken] =
    JsonCodecMaker.make(CodecMakerConfig.withFieldNameMapper {
      case "token"     => "access_token"
      case "expiresIn" => "expires_in"
    })

  def fromJsonString(json: String): Either[Throwable, AccessToken] =
    try Right(readFromString[AccessToken](json))
    catch case e: Throwable => Left(e)
}

// could potentially include more data if needed like structured head and payload
final case class IdToken(
  token: String,
  issuedAt: Instant,
  expiresAt: Instant,
  signature: String,
) extends Token {
  def expiresIn: Duration = Duration.fromSeconds(expiresAt.getEpochSecond - issuedAt.getEpochSecond)
}

object IdToken {
  private case class Payload(iat: Long, exp: Long)
  private object Payload:
    given jsonCodec: JsonValueCodec[Payload] = JsonCodecMaker.make[Payload]

  private def base64ToPayload(v: String): Either[Throwable, Payload] =
    try Right(readFromString[Payload](String(Base64.getDecoder.decode(v), StandardCharsets.UTF_8)))
    catch case e: Throwable => Left(e)

  def fromString(token: String): Either[Throwable, IdToken] =
    token.split('.') match {
      case Array(_, p, signature, _*) =>
        base64ToPayload(p).map: payload =>
          IdToken(
            token = token,
            signature = signature,
            issuedAt = Instant.ofEpochSecond(payload.iat),
            expiresAt = Instant.ofEpochSecond(payload.exp),
          )
      case _ => Left(Throwable(s"Ivalid identity token: $token"))
    }
}

case class TokenReceipt[+T <: Token](token: T, receivedAt: Instant)
