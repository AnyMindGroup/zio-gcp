---
id: index
title: "ZIO Google Cloud Auth"
sidebar_label: "ZIO Google Cloud Auth"
---

Google Cloud authentitcation methods over HTTP with [zio](https://zio.dev) and [sttp](https://sttp.softwaremill.com). 

Supported credentials for token provider:
 - ✅ [user account](https://cloud.google.com/docs/authentication#user-accounts)
 - ✅ attached [service account](https://cloud.google.com/docs/authentication#service-accounts) via [compute metadata server](https://cloud.google.com/compute/docs/metadata/overview)
 - ❌ service account private key (can be added in the future if needed, requires signed JWT) 

Cross-platform support: 
 - ✅ JVM 
   - tested java versions: 17, 21
 - ✅ Native with LLVM (via [scala-native](https://scala-native.org/))
   - backed by [libuv](https://libuv.org) and [libcurl](https://curl.se/libcurl)
   - http backend uses sync curl implementation, [async backend](https://github.com/softwaremill/sttp/issues/1424) might be added at some point...
 - ❌ JavaScript (via [scala-js](https://www.scala-js.org), could be potentially added)
  
Scala versions support: 
 - ✅ 3
 - ✅ 2.13
 - ❌ 2.12 and below

## Getting started
To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies += "com.anymindgroup" %% "zio-gc-auth" % "@VERSION@"
```

In a cross-platform project via [sbt-crossproject](https://github.com/portable-scala/sbt-crossproject) use:
```scala
libraryDependencies += "com.anymindgroup" %%% "zio-gc-auth" % "@VERSION@"
```

## Usage examples

### Using default auto refresh token provider

```scala
import zio.*
import com.anymindgroup.gcp.auth.*
import com.anymindgroup.http.HttpClientBackendPlatformSpecific

object Main extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
  def run: ZIO[Any, Any, ExitCode] =
    (for {
      // Looks up credentials in the following order
      // 1. Credentials key file under the location set via GOOGLE_APPLICATION_CREDENTIALS environment variable
      // 2. Default applications credentials
      //    Linux, macOS: $HOME/.config/gcloud/application_default_credentials.json
      //    Windows: %APPDATA%\gcloud\application_default_credentials.json
      // 3. Attached service account via compute metadata service https://cloud.google.com/compute/docs/metadata/overview
      tokenProvider <- TokenProvider.defaultAutoRefreshTokenProvider(
                         // Optional parameter: retry Schedule on token retrieval failures.
                         // Dafault: Schedule.recurs(5)
                         refreshRetrySchedule = Schedule.recurs(5),
                         // Optional parameter: at what stage of expiration in percent to request a new token.
                         // Default: 0.9 (90%)
                         // e.g. a token that expires in 3600 seconds, will be refreshed after 3240 seconds (6 mins before expiry)
                         refreshAtExpirationPercent = 0.9,
                       )
      // ^^^ pass tokenProvider to the service that requires authentication
      tokenReceipt <- tokenProvider.accessToken
      token         = tokenReceipt.token
      _            <- Console.printLine(s"Pass as bearer token to a Google Cloud API: ${token.token}")
      _            <- Console.printLine(s"Received token at ${tokenReceipt.receivedAt}")
      _            <- Console.printLine(s"Token expires in ${token.expiresIn.getSeconds()}s")
    } yield ExitCode.success).provide(httpBackendLayer())
}
```

### Change credentials lookup order

```scala
import zio.*
import com.anymindgroup.gcp.auth.*
import com.anymindgroup.http.HttpClientBackendPlatformSpecific

object LookupComputeMetadataFirst extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
  def run: ZIO[Any, Any, Any] =
    Credentials.computeServiceAccount.flatMap {
      case Some(c) => ZIO.some(c)
      case None    => Credentials.applicationCredentials
    }.flatMap {
      case None              => ZIO.dieMessage("No credentials found")
      case Some(credentials) => TokenProvider.autoRefreshTokenProvider(credentials)
    }.provide(httpBackendLayer())
}
```

### Use specific credentials

```scala
import zio.*
import com.anymindgroup.gcp.auth.*
import com.anymindgroup.http.HttpClientBackendPlatformSpecific

object PassSpecificUserAccount extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
  def run: ZIO[Any, Any, Any] =
    TokenProvider
      .autoRefreshTokenProvider(
        Credentials.UserAccount(
          refreshToken = "refresh_token",
          clientId = "123.apps.googleusercontent.com",
          clientSecret = Config.Secret("user_secret"),
        )
      )
      .provide(httpBackendLayer())
}
```

### Change log level

All logging is using `ZIO.log`. This allows you to override the log level
as e.g. described in this [zio logging tutorial guide](https://zio.dev/guides/tutorials/enable-logging-in-a-zio-application#overriding-log-levels).  
Example of setting the token provider log level to debug:
```scala
import zio.*
import com.anymindgroup.gcp.auth.*
import com.anymindgroup.http.HttpClientBackendPlatformSpecific

object SetLogLevelToDebug extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
  def run: ZIO[Any, Any, Any] =
    ZIO.logLevel(LogLevel.Debug)(TokenProvider.defaultAutoRefreshTokenProvider()).provide(httpBackendLayer())
}
```
