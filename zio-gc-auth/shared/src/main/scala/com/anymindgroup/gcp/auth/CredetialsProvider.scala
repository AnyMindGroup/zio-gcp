package com.anymindgroup.gcp.auth

import zio.{ZIO, Task}
import java.nio.file.Path
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import sttp.client4.*
import sttp.model.*

sealed trait Credentials
sealed trait CredentialsKey extends Credentials
object Credentials {
  final case class UserAccount(refreshToken: String, clientId: String, clientSecret: String) extends CredentialsKey
  final case class ServiceAccountKey(email: String, privateKey: String)                      extends CredentialsKey
  final case class ComputeServiceAccount(email: String)                                      extends Credentials
}

object CredentialsProvider {

  private def defaultApplicationCredentialsPath: Task[Option[Path]] =
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
          case Some(p) => ZIO.some(Path.of(p))
        }
    }

  private def parseApplicationCredentialsJson(p: Path): Task[Json.Obj] =
    ZIO.readFile(p).flatMap(c => ZIO.fromEither(c.fromJson[Json.Obj]).mapError(err => new Throwable(err)))

  private def credentialsFromJson(json: Json.Obj): Either[String, CredentialsKey] =
    json.get(JsonCursor.field("type").isString).map(_.value) match {
      case Right("service_account") =>
        for {
          refreshToken <- json.get(JsonCursor.field("refresh_token").isString).map(_.value)
          clientId     <- json.get(JsonCursor.field("client_id").isString).map(_.value)
          clientSecret <- json.get(JsonCursor.field("client_secret").isString).map(_.value)
        } yield Credentials.UserAccount(refreshToken = refreshToken, clientId = clientId, clientSecret = clientSecret)
      case Right("authorized_user") =>
        for {
          email <- json.get(JsonCursor.field("client_email").isString).map(_.value)
          key   <- json.get(JsonCursor.field("private_key").isString).map(_.value)
        } yield Credentials.ServiceAccountKey(email = email, privateKey = key)
      case Right(value) => Left(s"Unknwon credentials key type: $value")
      case Left(err)    => Left(s"Missing type in credentials file: $err")
    }

  def defaultApplicationCredentials: Task[Option[CredentialsKey]] = for {
    path <- defaultApplicationCredentialsPath
    json <- path match {
              case Some(p) => parseApplicationCredentialsJson(p).map(Some(_))
              case None    => ZIO.none
            }
    creds <- json match {
               case Some(j) => ZIO.fromEither(credentialsFromJson(j)).map(Some(_)).mapError(new Throwable(_))
               case None    => ZIO.none
             }
  } yield creds

  def computeServiceAccount: ZIO[GenericBackend[Task, Any], Throwable, Option[Credentials.ComputeServiceAccount]] =
    ZIO
      .serviceWithZIO[GenericBackend[Task, Any]] { backend =>
        backend.send(
          basicRequest
            .get(
              uri"http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email"
            )
            .header(Header("Metadata-Flavor", "Google"))
            .mapResponse {
              case Right(email) => Some(Credentials.ComputeServiceAccount(email))
              case Left(_)      => None
            }
        )
      }
      .map(_.body)

  def auto: ZIO[GenericBackend[Task, Any], Throwable, Option[Credentials]] = for {
    defaultCreds <- defaultApplicationCredentials
    creds <- defaultCreds match {
               case Some(u: Credentials.UserAccount) => ZIO.some(u)
               case Some(_: Credentials.ServiceAccountKey) =>
                 ZIO.logWarning(
                   "Authentication via service account key is no supported yet. Trying to get compute service account..."
                 ) *> computeServiceAccount
               case None => computeServiceAccount
             }
  } yield creds
}
