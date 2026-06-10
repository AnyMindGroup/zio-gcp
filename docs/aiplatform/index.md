# AI Platform

Client for [Google Cloud Vertex AI API](https://cloud.google.com/vertex-ai/docs/reference/rest).

The `zio-gcp-aiplatform` module provides an `ExpressModelClient` convenience layer built on top of the generated API client, with integrated support for [ZIO Schema](https://zio.dev/zio-schema/) and [function-calling](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/tools/function-calling) tools. You can define case classes with `derives Schema` to use as structured response schemas (enforcing JSON output shape) or as function parameter/return types in function calling — schemas are automatically converted to [OpenAPI schema](https://spec.openapis.org/oas/v3.0.3#schema) format for the Vertex AI API.

## Getting Started

To get started with sbt, add the dependency to your project in `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.anymindgroup" %% "zio-gcp-auth" % "@VERSION@",
  "com.anymindgroup" %% "zio-gcp-aiplatform" % "@VERSION@",
)
```

## Text generation

The simplest way to use the `ExpressModelClient` is plain text in, plain text out. `generateText` sends a prompt and returns the model's text response:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_text.scala{scala}

## Structured responses with ZIO Schema

Define a case class deriving a [ZIO Schema](https://zio.dev/zio-schema/) and the client will instruct the model to return JSON matching that shape, then decode the response into your type. The schema is automatically converted to the OpenAPI schema format expected by the Vertex AI API, so you get type-safe structured output without writing the schema by hand:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_schema.scala{scala}

For more control over the conversation you can build a `GenerateContentRequest` yourself — supplying multiple messages, system instructions and a response schema — and pass it to `generate`:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_custom_req.scala{scala}

## Function calling

Provide a map of `FunctionDeclaration`s and the client will drive the [function-calling](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/tools/function-calling) loop for you: it forwards the declarations to the model as tools, invokes the matching function when the model requests a call, feeds the result back, and repeats until the model produces a final answer. Function parameter and return types are derived from ZIO Schema, just like structured responses:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_function_calling.scala{scala}

## Using the raw generated API client

When you need full access to the Vertex AI REST API beyond what the `ExpressModelClient` exposes, the generated client under `com.anymindgroup.gcp.aiplatform.v1` can be used directly with an authenticated backend:

<<< @/../examples/shared/src/main/scala/vertex_ai_generate_content.scala{scala}
