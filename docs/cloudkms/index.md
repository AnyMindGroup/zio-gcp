# Cloud KMS

Client for [Google Cloud Key Management Service (KMS) API](https://cloud.google.com/kms/docs/reference/rest).

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-cloudkms-v1" % "@VERSION@"
)
```

## Usage examples

<<< @/../examples/shared/src/main/scala/cloudkms_v1_example.scala{scala}
