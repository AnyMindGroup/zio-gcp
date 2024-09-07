package com.anymindgroup.gcp.auth

import java.nio.file.Path

import com.anymindgroup.http.*
import sttp.model.*

import zio.Config.Secret
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import zio.{IO, ZIO}

sealed trait CredentialsException
object CredentialsException {
  final case class InvalidCredentialsFile(message: String)               extends CredentialsException
  final case class InvalidPathException(message: String)                 extends CredentialsException
  final case class IOException(cause: java.io.IOException)               extends CredentialsException
  final case class SecurityException(cause: java.lang.SecurityException) extends CredentialsException
  final case class Unexpected(cause: Throwable)                          extends CredentialsException
}

sealed trait Credentials
sealed trait CredentialsKey extends Credentials
object Credentials {
  final case class UserAccount(refreshToken: String, clientId: String, clientSecret: Secret) extends CredentialsKey
  final case class ServiceAccountKey(email: String, privateKey: Secret)                      extends CredentialsKey

  // https://cloud.google.com/compute/docs/metadata/overview
  // https://cloud.google.com/compute/docs/metadata/predefined-metadata-keys#instance-metadata
  final case class ComputeServiceAccount(email: String) extends Credentials
  object ComputeServiceAccount {
    private[auth] val baseUri            = uri"http://metadata.google.internal"
    private[auth] val computeMetadataUri = uri"$baseUri/computeMetadata/v1/instance/service-accounts/default"
    private[auth] val email              = uri"$computeMetadataUri/email"
    private[auth] val token              = uri"$computeMetadataUri/token"
    private[auth] val identity           = uri"$computeMetadataUri/identity"
    private[auth] val baseReq            = basicRequest.header(Header("Metadata-Flavor", "Google"))

    val emailRequest: Request[Either[String, ComputeServiceAccount]] =
      baseReq.get(email).mapResponseRight(email => Credentials.ComputeServiceAccount(email))

    val accessTokenRequest: Request[Either[String, AccessToken]] =
      basicRequest.get(token).mapResponse(_.flatMap(AccessToken.fromJsonString))

    def idTokenRequest(audience: String): Request[Either[String, IdToken]] =
      basicRequest.get(identity.addParam("audience", audience)).mapResponse(_.flatMap(IdToken.fromString))
  }

  private def applicationCredentialsPath: IO[CredentialsException, Option[Path]] =
    ZIO.systemWith { system =>
      system
        .env("GOOGLE_APPLICATION_CREDENTIALS")
        .flatMap {
          case None =>
            system.property("os.name").zipPar(system.property("user.home")).flatMap {
              case (os, _) if os.exists(_.indexOf("windows") >= 0) =>
                system.env("APPDATA").map(_.map(h => Path.of(h, "gcloud", "application_default_credentials.json")))
              case (_, Some(h)) =>
                ZIO.some(Path.of(h, ".config", "gcloud", "application_default_credentials.json"))
              case _ => ZIO.none
            }
          case Some(p) => ZIO.attempt(Some(Path.of(p)))
        }
    }.mapError {
      case e: java.lang.SecurityException        => CredentialsException.SecurityException(e)
      case e: java.nio.file.InvalidPathException => CredentialsException.InvalidPathException(e.getMessage())
      case e: Throwable                          => CredentialsException.Unexpected(e)
    }

  private def findApplicationCredentialsJson(p: Path): IO[CredentialsException, Option[Json.Obj]] =
    ZIO
      .readFile(p)
      .map(Some(_))
      .catchSome { case _: java.io.FileNotFoundException =>
        ZIO.none
      }
      .mapError(CredentialsException.IOException(_))
      .flatMap {
        case Some(c) =>
          ZIO
            .fromEither(c.fromJson[Json.Obj])
            .mapError(err => CredentialsException.InvalidCredentialsFile(err))
            .map(Some(_))
        case _ => ZIO.none
      }

  private def credentialsFromJson(json: Json.Obj): Either[String, CredentialsKey] =
    json.get(JsonCursor.field("type").isString).map(_.value) match {
      case Right("authorized_user") =>
        for {
          refreshToken <- json.get(JsonCursor.field("refresh_token").isString).map(_.value)
          clientId     <- json.get(JsonCursor.field("client_id").isString).map(_.value)
          clientSecret <- json.get(JsonCursor.field("client_secret").isString).map(j => Secret(j.value))
        } yield Credentials.UserAccount(refreshToken = refreshToken, clientId = clientId, clientSecret = clientSecret)
      case Right("service_account") =>
        for {
          email      <- json.get(JsonCursor.field("client_email").isString).map(_.value)
          privateKey <- json.get(JsonCursor.field("private_key").isString).map(j => Secret(j.value))
        } yield Credentials.ServiceAccountKey(email = email, privateKey = privateKey)
      case Right(value) => Left(s"Unknown credentials key type: $value")
      case Left(err)    => Left(s"Missing type in credentials file: $err")
    }

  def applicationCredentials: IO[CredentialsException, Option[CredentialsKey]] = for {
    path <- applicationCredentialsPath.tapSome { case Some(p) =>
              ZIO.log(s"Attempting to read application credentials from $p")
            }
    json <- path match {
              case Some(p) => findApplicationCredentialsJson(p)
              case None    => ZIO.none
            }
    creds <- json match {
               case Some(j) =>
                 ZIO
                   .fromEither(credentialsFromJson(j))
                   .map(Some(_))
                   .mapError(e => CredentialsException.InvalidCredentialsFile(e))
               case None => ZIO.none
             }
  } yield creds

  def computeServiceAccount: ZIO[HttpBackend, CredentialsException, Option[Credentials.ComputeServiceAccount]] =
    ZIO
      .serviceWithZIO[HttpBackend](backend =>
        ZIO.log(s"Attempting to reach internal compute metadata service...") *>
          backend.send(ComputeServiceAccount.emailRequest.mapResponse(_.toOption)).map(_.body)
      )
      .catchSome { case UnresolvedAddressException(_) =>
        ZIO.log(s"Unable to resolve address ${ComputeServiceAccount.baseUri.toString()}").as(None)
      }
      .mapError { case e =>
        CredentialsException.Unexpected(e)
      }

  def auto: ZIO[HttpBackend, CredentialsException, Option[Credentials]] = applicationCredentials.flatMap {
    case Some(c: Credentials.UserAccount) =>
      ZIO.log(s"Found user credentials with client id ${c.clientId}").as(Some(c))
    case Some(c: Credentials.ServiceAccountKey) =>
      ZIO.log(s"Found service account credentials for ${c.email}").as(Some(c))
    case None =>
      ZIO.log(s"No application credentials found.") *>
        computeServiceAccount.tap {
          case Some(Credentials.ComputeServiceAccount(email)) =>
            ZIO.log(s"Found service account credentials for $email via compute metadata")
          case _ => ZIO.log(s"No credentials were found")
        }
  }
}
