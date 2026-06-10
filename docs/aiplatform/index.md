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

## Usage

- [Text generation](./text-generation) — plain text in, plain text out.
- [Structured responses with ZIO Schema](./structured-responses) — decode the model output into your own types.
- [Function calling](./function-calling) — let the model call your functions.
- [Raw API client](./raw-client) — use the generated Vertex AI client directly.
