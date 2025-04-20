package com.anymindgroup.gcp.auth

import java.nio.file.Path

import com.anymindgroup.gcp.ComputeMetadata
import com.anymindgroup.http.UnresolvedAddressException
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import sttp.client4.*

import zio.Config.Secret
import zio.{IO, Task, ZIO}

sealed abstract class CredentialsException(cause: Throwable) extends Throwable(cause)
object CredentialsException {
  final case class InvalidCredentialsFile(message: String)               extends CredentialsException(new Throwable(message))
  final case class InvalidPathException(message: String)                 extends CredentialsException(new Throwable(message))
  final case class IOException(cause: java.io.IOException)               extends CredentialsException(cause)
  final case class SecurityException(cause: java.lang.SecurityException) extends CredentialsException(cause)
  final case class Unexpected(cause: Throwable)                          extends CredentialsException(cause)
}

sealed trait Credentials
sealed trait CredentialsKey extends Credentials
object Credentials {
  final case class UserAccount(refreshToken: String, clientId: String, clientSecret: Secret) extends CredentialsKey
  final case class ServiceAccountKey(email: String, privateKey: Secret)                      extends CredentialsKey

  // https://cloud.google.com/compute/docs/metadata/overview
  // https://cloud.google.com/compute/docs/metadata/predefined-metadata-keys#instance-metadata
  final case class ComputeServiceAccount(email: String) extends Credentials

  private enum ApplicationCredentials:
    case authorized_user(refresh_token: String, client_id: String, client_secret: String)
    case service_account(client_email: String, private_key: String)
  private object ApplicationCredentials:
    given JsonValueCodec[ApplicationCredentials] =
      JsonCodecMaker.make:
        CodecMakerConfig.withRequireDiscriminatorFirst(false).withDiscriminatorFieldName(Some("type"))

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

  private def findApplicationCredentials(p: Path): IO[CredentialsException, Option[ApplicationCredentials]] =
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
            .attempt(readFromString[ApplicationCredentials](c))
            .mapError(err => CredentialsException.InvalidCredentialsFile(err.getMessage()))
            .map(Some(_))
        case _ => ZIO.none
      }

  def applicationCredentials: IO[CredentialsException, Option[CredentialsKey]] = for {
    path <- applicationCredentialsPath.tapSome { case Some(p) =>
              ZIO.log(s"Attempting to read application credentials from $p")
            }
    creds <- path match
               case Some(p) =>
                 findApplicationCredentials(p).map:
                   _.map:
                     case ApplicationCredentials.authorized_user(refreshToken, clientId, secret) =>
                       Credentials.UserAccount(
                         refreshToken = refreshToken,
                         clientId = clientId,
                         clientSecret = Secret(secret),
                       )
                     case ApplicationCredentials.service_account(email, key) =>
                       Credentials.ServiceAccountKey(
                         email = email,
                         privateKey = Secret(key),
                       )
               case None => ZIO.none
  } yield creds

  def computeServiceAccount(
    backend: GenericBackend[Task, Any]
  ): ZIO[Any, CredentialsException, Option[Credentials.ComputeServiceAccount]] =
    (
      ZIO.log(s"Attempting to reach internal compute metadata service...") *>
        backend.send(ComputeMetadata.serviceAccountEmailReq).map(r => Some(r.body))
    ).catchSome { case UnresolvedAddressException(_) =>
      ZIO.log(s"Unable to resolve address ${ComputeMetadata.serviceAccountEmailReq.uri.toString()}").as(None)
    }.mapError { case e =>
      CredentialsException.Unexpected(e)
    }

  def auto(
    backend: GenericBackend[Task, Any],
    lookupComputeMetadataFirst: Boolean = false,
  ): ZIO[Any, CredentialsException, Option[Credentials]] =
    if lookupComputeMetadataFirst then
      computeServiceAccount(backend).flatMap {
        case Some(c: Credentials.ComputeServiceAccount) =>
          ZIO.log(s"Found service account credentials for ${c.email}").as(Some(c))
        case _ =>
          applicationCredentials.flatMap {
            case Some(c: Credentials.UserAccount) =>
              ZIO.log(s"Found user credentials with client id ${c.clientId}").as(Some(c))
            case Some(c: Credentials.ServiceAccountKey) =>
              ZIO.log(s"Found service account credentials for ${c.email}").as(Some(c))
            case None =>
              ZIO.log(s"No credentials were found.").as(None)
          }
      }
    else
      applicationCredentials.flatMap {
        case Some(c: Credentials.UserAccount) =>
          ZIO.log(s"Found user credentials with client id ${c.clientId}").as(Some(c))
        case Some(c: Credentials.ServiceAccountKey) =>
          ZIO.log(s"Found service account credentials for ${c.email}").as(Some(c))
        case None =>
          ZIO.log(s"No application credentials found.") *>
            computeServiceAccount(backend).tap {
              case Some(Credentials.ComputeServiceAccount(email)) =>
                ZIO.log(s"Found service account credentials for $email via compute metadata")
              case _ => ZIO.log(s"No credentials were found")
            }
      }
}
