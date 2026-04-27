# Cloud Storage

Client for [Google Cloud Storage API](https://cloud.google.com/storage/docs/json_api).

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-storage" % "@VERSION@", // includes zio-gcp-storage-v1 and zio-gcp-iamcredentials-v1
)
```

## Usage example

#### Upload file to storage bucket, create signed url, delete file

<<< @/../examples/shared/src/main/scala/storage_example.scala{scala}
