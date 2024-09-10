import zio.*, zio.Console.*, com.anymindgroup.gcp.auth.*, com.anymindgroup.http.*

object AccessTokenByUser extends ZIOAppDefault with HttpClientBackendPlatformSpecific:
  def run =
    (for {
      // choose the required token provider
      //
      // use TokenProvider[AccessToken] if the application doesn't require identity information
      // see https://cloud.google.com/docs/authentication/token-types#access for more information
      //
      // use TokenProvider[IdToken] if the token needs to be inspected by the application
      // see https://cloud.google.com/docs/authentication/token-types#id for more information
      //
      // use TokenProvider[Token] if it doesn't matter whether the provided token is an Access or ID token
      tokenProvider <- ZIO.service[TokenProvider[AccessToken]]
      tokenReceipt  <- tokenProvider.token
      token          = tokenReceipt.token
      _             <- printLine(s"Pass as bearer token to a Google Cloud API: ${token.token}")
      _             <- printLine(s"Received token at ${tokenReceipt.receivedAt}")
      _             <- printLine(s"Token expires in ${token.expiresIn.getSeconds()}s")
    } yield ()).provide(
      // Default token provider looks up credentials in the following order
      // 1. Credentials key file under the location set via GOOGLE_APPLICATION_CREDENTIALS environment variable
      // 2. Default applications credentials
      //    Linux, macOS: $HOME/.config/gcloud/application_default_credentials.json
      //    Windows: %APPDATA%\gcloud\application_default_credentials.json
      // 3. Attached service account via compute metadata service https://cloud.google.com/compute/docs/metadata/overview
      TokenProvider.defaultAccessTokenProviderLayer(
        // Optional parameter: retry Schedule on token retrieval failures.
        // Dafault: Schedule.recurs(5)
        refreshRetrySchedule = Schedule.recurs(5),
        // Optional parameter: at what stage of expiration in percent to request a new token.
        // Default: 0.9 (90%)
        // e.g. a token that expires in 3600 seconds, will be refreshed after 3240 seconds (6 mins before expiry)
        refreshAtExpirationPercent = 0.9,
      ),
      httpBackendLayer(),
    )

// access token retrieval without caching and auto refreshing
object SimpleTokenRetrieval extends ZIOAppDefault with HttpClientBackendPlatformSpecific:
  def run = for {
    receipt <- ZIO.scoped(TokenProvider.defaultAccessTokenProvider().flatMap(_.token)).provide(httpBackendLayer())
    _       <- printLine(s"got access token: ${receipt.token.token.value.mkString} at ${receipt.receivedAt}")
  } yield ()

object LookupComputeMetadataFirst extends ZIOAppDefault with HttpClientBackendPlatformSpecific:
  def run =
    Credentials.computeServiceAccount.flatMap {
      case Some(c) => ZIO.some(c)
      case None    => Credentials.applicationCredentials
    }.flatMap {
      case None              => ZIO.dieMessage("No credentials found")
      case Some(credentials) => ZIO.scoped(TokenProvider.accessTokenProvider(credentials))
    }.provide(httpBackendLayer())

object PassSpecificUserAccount extends ZIOAppDefault with HttpClientBackendPlatformSpecific:
  def run =
    TokenProvider
      .accessTokenProvider(
        Credentials.UserAccount(
          refreshToken = "refresh_token",
          clientId = "123.apps.googleusercontent.com",
          clientSecret = Config.Secret("user_secret"),
        )
      )
      .provideSome[Scope](httpBackendLayer())

object SetLogLevelToDebug extends ZIOAppDefault with HttpClientBackendPlatformSpecific:
  def run =
    ZIO
      .logLevel(LogLevel.Debug)(TokenProvider.defaultAccessTokenProvider())
      .provideSome[Scope](httpBackendLayer())
