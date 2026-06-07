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

#### Generate text via the Express client (text-only input):

<<< @/../examples/shared/src/main/scala/express_vertex_ai_from_text.scala{scala}

#### Generate structured output via the Express client (request construction):

<<< @/../examples/shared/src/main/scala/express_vertex_ai_request.scala{scala}

#### Generate content via the raw API client:

<<< @/../examples/shared/src/main/scala/vertex_ai_generate_content.scala{scala}