# AI Platform

Client for [Google Cloud Vertex AI API](https://cloud.google.com/vertex-ai/docs/reference/rest).

The `zio-gcp-aiplatform` module provides an `ExpressModelClient` convenience layer built on top of the generated API client, with integrated support for [ZIO Schema](https://zio.dev/zio-schema/). You can define case classes with `derives Schema` to use as structured response schemas (enforcing JSON output shape) or as function parameter/return types in function calling — schemas are automatically converted to Vertex AI's `GoogleCloudAiplatformV1Schema` format.

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-aiplatform" % "@VERSION@",
)
```

## Express client usage examples

#### Simple text generation:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_text.scala{scala}

#### Generate structured output from text:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_schema.scala{scala}

#### Generate structured output with a custom request:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_custom_req.scala{scala}

####  Generate structured output with a custom request and functions:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_function_calling.scala{scala}

#### Generate content via the raw API client:

<<< @/../examples/shared/src/main/scala/vertex_ai_generate_content.scala{scala}
