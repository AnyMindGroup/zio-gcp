# Getting Started

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  // add clients based on needs
  "com.anymindgroup" %% "zio-gcp-storage" % "@VERSION@", // includes zio-gcp-storage-v1 and zio-gcp-iamcredentials-v1
  "com.anymindgroup" %% "zio-gcp-sheets" % "@VERSION@", // includes zio-gcp-sheets-v4

  // pubsub:
  // to use the http implementation (includes zio-gcp-pubsub-v1)
  "com.anymindgroup" %% "zio-pubsub-http"  % "@VERSION@",
  
  // or to use the implementation with Google's Java library
  // "com.anymindgroup" %% "zio-pubsub-google"  % "@VERSION@",

  // generated clients
  "com.anymindgroup" %% "zio-gcp-aiplatform-v1" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-bigquery-v2" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-pubsub-v1" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-storage-v1" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-iamcredentials-v1" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-sheets-v4" % "@VERSION@",
)
```

In a cross-platform project via [sbt-crossproject](https://github.com/portable-scala/sbt-crossproject) use `%%%` operator:
```scala
libraryDependencies += "com.anymindgroup" %%% "zio-gcp-auth" % "@VERSION@"
// etc.
```
