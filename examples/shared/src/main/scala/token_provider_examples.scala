//> using scala 3.7.1
//> using dep com.anymindgroup::zio-gcp-auth::0.1.2

import zio.*, zio.Console.*, com.anymindgroup.gcp.auth.*, com.anymindgroup.http.*

object AccessTokenByUser extends ZIOAppDefault:
  def run =
    for
      // choose the required token provider
      //
      // use TokenProvider[AccessToken] if the application doesn't require identity information
      // see https://cloud.google.com/docs/authentication/token-types#access for more information
      //
      // use TokenProvider[IdToken] if the token needs to be inspected by the application
      // see https://cloud.google.com/docs/authentication/token-types#id for more information
      //
      // use TokenProvider[Token] if it doesn't matter whether the provided token is an Access or ID token
      tokenProvider: TokenProvider[Token] <-
        httpBackendScoped().flatMap: backend =>
          // Default token provider looks up credentials in the following order
          // 1. Credentials key file under the location set via GOOGLE_APPLICATION_CREDENTIALS environment variable
          // 2. Default applications credentials
          //    Linux, macOS: $HOME/.config/gcloud/application_default_credentials.json
          //    Windows: %APPDATA%\gcloud\application_default_credentials.json
          // 3. Attached service account via compute metadata service https://cloud.google.com/compute/docs/metadata/overview
          TokenProvider.defaultAccessTokenProvider(
            backend = backend,
            // Optional parameter: whether to lookup credentials from the compute metadata service before applications credentials
            // Default: false
            lookupComputeMetadataFirst = false,
            // Optional parameter: retry Schedule on token retrieval failures.
            // Dafault: Schedule.recurs(5)
            refreshRetrySchedule = Schedule.recurs(5),
            // Optional parameter: at what stage of expiration in percent to request a new token.
            // Default: 0.9 (90%)
            // e.g. a token that expires in 3600 seconds, will be refreshed after 3240 seconds (6 mins before expiry)
            refreshAtExpirationPercent = 0.9,
          )
      tokenReceipt <- tokenProvider.token
      token         = tokenReceipt.token
      _            <- printLine(s"Pass as bearer token to a Google Cloud API: ${token.token}")
      _            <- printLine(s"Received token at ${tokenReceipt.receivedAt}")
      _            <- printLine(s"Token expires in ${token.expiresIn.getSeconds()}s")
    yield ()

// access token retrieval without caching and auto refreshing
object SimpleTokenRetrieval extends ZIOAppDefault:
  def run = httpBackendScoped()
    .flatMap(TokenProvider.defaultAccessTokenProvider(_).flatMap(_.token))
    .flatMap(r => printLine(s"got access token: ${r.token.token} at ${r.receivedAt}"))

object PassSpecificUserAccount extends ZIOAppDefault:
  def run =
    httpBackendScoped().flatMap: backend =>
      TokenProvider
        .accessTokenProvider(
          Credentials.UserAccount(
            refreshToken = "refresh_token",
            clientId = "123.apps.googleusercontent.com",
            clientSecret = Config.Secret("user_secret"),
          ),
          backend,
        )

object SetLogLevelToDebug extends ZIOAppDefault:
  def run = ZIO.logLevel(LogLevel.Debug)(httpBackendScoped().flatMap(TokenProvider.defaultAccessTokenProvider(_)))
