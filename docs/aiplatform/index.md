# AI Platform

Client for [Google Cloud Vertex AI API](https://cloud.google.com/vertex-ai/docs/reference/rest).

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-aiplatform-v1" % "@VERSION@",
)
```

## Usage examples

#### Generate content via Vertex AI API:

<<< @/../examples/shared/src/main/scala/vertex_ai_generate_content.scala{scala}