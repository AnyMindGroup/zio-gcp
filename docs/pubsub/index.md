# Google Cloud Pub/Sub clients

[Google Cloud Pub/Sub](https://cloud.google.com/pubsub) client providing stream-based, declarative, high-level API with [zio](https://zio.dev) and [zio-streams](https://zio.dev/reference/stream) to help to concentrate on the business logic.

Released for Scala 3 targeting JVM and Native via [scala-native](https://scala-native.org) with exception of `zio-pubsub-google` due to Java dependencies.   
[Scala.js](https://www.scala-js.org) support could be potentially added.  

## Modules

| Name | Description | JVM | Native |
| ---- | ----------- | --- | ------ |
| `zio-pubsub` | Core components/interfaces/models | ✅ | ✅ |
| `zio-pubsub-http` | Implementation using Pub/Sub REST API based on clients from [AnyMindGroup/zio-gcp](https://github.com/AnyMindGroup/zio-gcp) | ✅ | ✅ |
| `zio-pubsub-google` | Provides gRPC based client implementations via [Google's Java](https://cloud.google.com/java/docs/reference/google-cloud-pubsub/latest/overview) library by using the [StreamingPull API](https://cloud.google.com/pubsub/docs/pull#streamingpull_api) for subscriptions. | ✅ | ❌ |
| `zio-pubsub-serde-zio-schema` | Provides Serializer/Deserializer using the [zio-schema](https://github.com/zio/zio-schema) binary codec | ✅ | ✅ |

## Getting started

To get started with sbt, add the following to your build.sbt file:

```scala
libraryDependencies ++= Seq(
    // core components
    "com.anymindgroup" %% "zio-pubsub"         % "@VERSION@",
    
    // to use the implementation with Google's Java library
    "com.anymindgroup" %% "zio-pubsub-google"  % "@VERSION@",
    
    // or to use the http implementation
    // "com.anymindgroup" %% "zio-pubsub-http"  % "@VERSION@",

    // include for testing support
    "com.anymindgroup" %% "zio-pubsub-testkit" % "@VERSION@" % Test
)
```