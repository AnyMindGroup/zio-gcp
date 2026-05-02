# Authentication

The module `zio-gcp-auth` provides methods for authentication.  
It's primarily meant to run on a VM in Google Cloud and make use of [compute metadata](https://cloud.google.com/compute/docs/metadata/overview).

Currently supported credentials and tokens:

| Credentials | [Access token](https://cloud.google.com/docs/authentication/token-types#access) | [ID token](https://cloud.google.com/docs/authentication/token-types#id) |
| --- | --- | --- |
| [Service account](https://cloud.google.com/docs/authentication#service-accounts) (via [compute metadata](https://cloud.google.com/compute/docs/metadata/overview)) | ✅ | ✅ |
| [User credentials](https://cloud.google.com/docs/authentication/application-default-credentials#personal) | ✅ | ❌ |
| Service account (via private key) | ❌ | ❌ |

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@"
)
```

## Token provider usage examples

<<< @/../examples/shared/src/main/scala/token_provider_examples.scala{scala}

