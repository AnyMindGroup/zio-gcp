// import com.anymindgroup.gcp.auth.*
// import com.anymindgroup.http.HttpClientBackendPlatformSpecific

// import zio.*

// object Main extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
//   def run: ZIO[Any, Any, ExitCode] =
//     (for {
//       // Looks up credentials in the following order
//       // 1. Credentials key file under the location set via GOOGLE_APPLICATION_CREDENTIALS environment variable
//       // 2. Default applications credentials
//       //    Linux, macOS: $HOME/.config/gcloud/application_default_credentials.json
//       //    Windows: %APPDATA%\gcloud\application_default_credentials.json
//       // 3. Attached service account via compute metadata service https://cloud.google.com/compute/docs/metadata/overview
//       tokenProvider <- TokenProvider.defaultAutoRefreshTokenProvider(
//                          // Optional parameter: retry Schedule on token retrieval failures.
//                          // Dafault: Schedule.recurs(5)
//                          refreshRetrySchedule = Schedule.recurs(5),
//                          // Optional parameter: at what stage of expiration in percent to request a new token.
//                          // Default: 0.9 (90%)
//                          // e.g. a token that expires in 3600 seconds, will be refreshed after 3240 seconds (6 mins before expiry)
//                          refreshAtExpirationPercent = 0.9,
//                        )
//       // ^^^ pass tokenProvider to the service that requires authentication
//       tokenReceipt <- tokenProvider.token
//       token         = tokenReceipt.token
//       _            <- Console.printLine(s"Pass as bearer token to a Google Cloud API: ${token.token}")
//       _            <- Console.printLine(s"Received token at ${tokenReceipt.receivedAt}")
//       _            <- Console.printLine(s"Token expires in ${token.expiresIn.getSeconds()}s")
//     } yield ExitCode.success).provide(httpBackendLayer())
// }

// object LookupComputeMetadataFirst extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
//   def run: ZIO[Any, Any, Any] =
//     Credentials.computeServiceAccount.flatMap {
//       case Some(c) => ZIO.some(c)
//       case None    => Credentials.applicationCredentials
//     }.flatMap {
//       case None              => ZIO.dieMessage("No credentials found")
//       case Some(credentials) => TokenProvider.autoRefreshTokenProvider(credentials)
//     }.provide(httpBackendLayer())
// }

// object PassSpecificUserAccount extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
//   def run: ZIO[Any, Any, Any] =
//     TokenProvider
//       .autoRefreshTokenProvider(
//         Credentials.UserAccount(
//           refreshToken = "refresh_token",
//           clientId = "123.apps.googleusercontent.com",
//           clientSecret = Config.Secret("user_secret"),
//         )
//       )
//       .provide(httpBackendLayer())
// }

// object SetLogLevelToDebug extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
//   def run: ZIO[Any, Any, Any] =
//     ZIO.logLevel(LogLevel.Debug)(TokenProvider.defaultAutoRefreshTokenProvider()).provide(httpBackendLayer())
// }
