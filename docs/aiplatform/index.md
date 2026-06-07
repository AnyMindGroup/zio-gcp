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

The module provides an `ExpressModelClient` convenience layer built on top of the generated API client, with integrated support for [ZIO Schema](https://zio.dev/zio-schema/). You can define case classes with `derives Schema` to use as structured response schemas (enforcing JSON output shape) or as function parameter/return types in function calling — schemas are automatically converted to Vertex AI's `GoogleCloudAiplatformV1Schema` format.

## Usage examples

#### Generate text via the Express client (text-only input):

<<< @/../examples/shared/src/main/scala/express_vertex_ai_from_text.scala{scala}

#### Generate structured output via the Express client (request construction):

<<< @/../examples/shared/src/main/scala/express_vertex_ai_request.scala{scala}

#### Generate content via the raw API client:

<<< @/../examples/shared/src/main/scala/vertex_ai_generate_content.scala{scala}

#### Function calling via the Express client:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_function_calling.scala{scala}