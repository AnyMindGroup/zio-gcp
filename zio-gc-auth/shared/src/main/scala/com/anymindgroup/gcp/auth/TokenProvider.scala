package com.anymindgroup.gcp.auth

import com.anymindgroup.gcp.auth.Credentials.{ComputeServiceAccount, ServiceAccountKey, UserAccount}
import com.anymindgroup.http.*
import sttp.model.*

import zio.{Clock, IO, Ref, Schedule, UIO, ZIO}

sealed trait TokenProviderException
object TokenProviderException {
  final case class TokenRequestFailure(message: String)            extends TokenProviderException
  case object CredentialsNotFound                                  extends TokenProviderException
  final case class CredentialsFailure(cause: CredentialsException) extends TokenProviderException
  final case class Unexpected(cause: Throwable)                    extends TokenProviderException
}

trait TokenProvider[T <: Token] {
  def token: UIO[TokenReceipt[T]]
}

object TokenProvider {
  // POST https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/PRIV_SA:generateIdToken
  val tokenBase: Uri = uri"https://oauth2.googleapis.com/token"

  object defaults {
    val refreshRetrySchedule: Schedule[Any, Any, Any] = Schedule.recurs(10)
    val refreshAtExpirationPercent: Double            = 0.9
  }

  private[auth] def responseToToken[T <: Token](
    res: Response[Either[String, T]]
  ): IO[TokenProviderException, T] =
    res.body match {
      case Right(token) => ZIO.succeed(token)
      case Left(err)    => ZIO.fail(TokenProviderException.TokenRequestFailure(s"Failure on getting token: $err"))
    }

  private[auth] def userAccountTokenReq(creds: Credentials.UserAccount) =
    basicRequest
      .post(tokenBase)
      .body(
        s"""|{
            |  "grant_type": "refresh_token",
            |  "refresh_token": "${creds.refreshToken}",
            |  "client_id": "${creds.clientId}",
            |  "client_secret": "${creds.clientSecret.value.mkString}"
            |}""".stripMargin
      )
      .header(Header.contentType(MediaType.ApplicationJson) /*, replaceExisting = true*/ )
      .mapResponse(_.flatMap(AccessToken.fromJsonString))

  private[auth] def autoRefreshTokenProviderByRequest[T <: Token](
    backend: HttpBackend,
    req: Request[Either[String, T]],
    refreshRetrySchedule: Schedule[Any, Any, Any],
    refreshAtExpirationPercent: Double,
  ): IO[TokenProviderException, TokenProvider[T]] = {
    def requestToken: IO[TokenProviderException, TokenReceipt[T]] = for {
      _ <- ZIO.log("Requesting new access token...")
      token <- backend
                 .send(req)
                 .mapError(e => TokenProviderException.Unexpected(e))
                 .flatMap(responseToToken)
      now <- Clock.instant
      _   <- ZIO.log(s"Retrieved new token with expiry in ${token.expiresIn.getSeconds()}s")
    } yield TokenReceipt(token, now)

    def refreshTokenJob(ref: Ref[TokenReceipt[T]]) =
      (for {
        current   <- ref.get
        _         <- ZIO.sleep(current.token.expiresInOfPercent(refreshAtExpirationPercent))
        refreshed <- requestToken.retry(refreshRetrySchedule)
        _         <- ref.update(_ => refreshed)
      } yield ()).repeat[Any, Long](Schedule.forever)

    for {
      token <- requestToken
      ref   <- zio.Ref.make(token)
      _     <- refreshTokenJob(ref).fork
    } yield new TokenProvider[T] {
      override def token: UIO[TokenReceipt[T]] = ref.get
    }
  }

  def defaultAutoRefreshTokenProvider(
    refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[HttpBackend, TokenProviderException, TokenProvider[AccessToken]] =
    Credentials.auto.mapError(e => TokenProviderException.CredentialsFailure(e)).flatMap {
      case None              => ZIO.fail(TokenProviderException.CredentialsNotFound)
      case Some(credentials) => autoRefreshTokenProvider(credentials, refreshRetrySchedule, refreshAtExpirationPercent)
    }

  def autoRefreshTokenProvider(
    credentials: Credentials,
    refreshRetrySchedule: Schedule[Any, Any, Any] = TokenProvider.defaults.refreshRetrySchedule,
    refreshAtExpirationPercent: Double = TokenProvider.defaults.refreshAtExpirationPercent,
  ): ZIO[HttpBackend, TokenProviderException, TokenProvider[AccessToken]] =
    credentials match {
      case ComputeServiceAccount(_) =>
        ZIO.serviceWithZIO[HttpBackend] { backend =>
          autoRefreshTokenProviderByRequest(
            backend,
            ComputeServiceAccount.accessTokenRequest,
            refreshRetrySchedule,
            refreshAtExpirationPercent,
          )
        }
      case user: UserAccount =>
        ZIO.serviceWithZIO[HttpBackend] { backend =>
          autoRefreshTokenProviderByRequest(
            backend,
            userAccountTokenReq(user),
            refreshRetrySchedule,
            refreshAtExpirationPercent,
          )
        }
      // Service account key credentials might be supported later (at least on JVM as it requires signed JWT)
      case ServiceAccountKey(email, _) =>
        ZIO.fail(
          TokenProviderException.CredentialsFailure(
            CredentialsException.InvalidCredentialsFile(
              s"Got credentials for service account $email. Service account key credentials are not supported yet."
            )
          )
        )
    }
}
