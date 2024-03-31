---
id: index
title: "Getting Started with ZIO Google Auth"
sidebar_label: "Getting Started"
---

# Google Cloud authentication over HTTP

Google Cloud authentitcation methods over HTTP with [zio](https://zio.dev) and [sttp](https://sttp.softwaremill.com). 

Supported credentials for token provider:
 - ✅ [user account](https://cloud.google.com/docs/authentication#user-accounts)
 - ✅ attached [service account](https://cloud.google.com/docs/authentication#service-accounts) via [compute metadata server](https://cloud.google.com/compute/docs/metadata/overview)
 - ❌ service account private key (can be added in the future if needed, requires signed JWT) 

Cross-platform support: 
 - ✅ JVM 
   - tested java versions: 17, 21
 - ✅ Native with LLVM (via [scala-native](https://scala-native.org/))
   - requires [libuv](https://libuv.org) and [curl](https://curl.se/libcurl)
   - http backend uses sync curl implementation, [async backend](https://github.com/softwaremill/sttp/issues/1424) might be added soon...
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

In a cross-platform project use:
```scala
libraryDependencies += "com.anymindgroup" %%% "zio-gc-auth" % "@VERSION@"
```

## Usage example
Example using default auto refresh token provider:

```scala
import zio.*
import com.anymindgroup.gcp.auth.*
import com.anymindgroup.http.HttpClientBackendPlatformSpecific

object Main extends ZIOAppDefault with HttpClientBackendPlatformSpecific {
  override def run: ZIO[Any, Any, ExitCode] =
    (for {
      // Looks up credentials in the following order
      // 1. Credentials key file under the location set via GOOGLE_APPLICATION_CREDENTIALS environment variable
      // 2. Default applications credentials
      //    Linux, macOS: $HOME/.config/gcloud/application_default_credentials.json
      //    Windows: %APPDATA%\gcloud\application_default_credentials.json
      // 3. Attached service account via compute metadata service https://cloud.google.com/compute/docs/metadata/overview
      tokenProvider <- TokenProvider.defaultAutoRefreshTokenProvider()
      // ^^^ pass tokenProvider to the service that requires authentication
      tokenReceipt <- tokenProvider.accessToken
      token         = tokenReceipt.token
      _            <- Console.printLine(s"Pass as bearer token to a Google Cloud API: ${token.token}")
      _            <- Console.printLine(s"Received token at ${tokenReceipt.receivedAt}")
      _            <- Console.printLine(s"Token expires in ${token.expiresIn.getSeconds()}s")
    } yield ExitCode.success).provide(httpBackendLayer())
}
```

More docs/examples placeholder...