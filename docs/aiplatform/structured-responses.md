# Structured responses with ZIO Schema

Define a case class deriving a [ZIO Schema](https://zio.dev/zio-schema/) and the client will instruct the model to return JSON matching that shape, then decode the response into your type. The schema is automatically converted to the OpenAPI schema format expected by the Vertex AI API, so you get type-safe structured output without writing the schema by hand:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_schema.scala{scala}

## Custom requests

For more control over the conversation you can build a `GenerateContentRequest` yourself — supplying multiple messages, system instructions and a response schema — and pass it to `generate`:

<<< @/../examples/shared/src/main/scala/express_vertex_ai_custom_req.scala{scala}
