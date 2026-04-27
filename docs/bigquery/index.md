# BigQuery

Client for [Google Cloud BigQuery API](https://cloud.google.com/bigquery/docs/reference/rest).

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-bigquery-v2" % "@VERSION@"
)
```

## Usage examples

<<< @/../examples/shared/src/main/scala/bigquery_v2_example.scala{scala}