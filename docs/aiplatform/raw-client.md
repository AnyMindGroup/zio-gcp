# Raw API client

When you need full access to the Vertex AI REST API beyond what the `ExpressModelClient` exposes, the generated client under `com.anymindgroup.gcp.aiplatform.v1` can be used directly with an authenticated backend:

<<< @/../examples/shared/src/main/scala/vertex_ai_generate_content.scala{scala}
