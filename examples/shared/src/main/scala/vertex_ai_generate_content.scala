//> using scala 3.6.3
//> using dep com.anymindgroup::zio-gcp-auth::0.0.4
//> using dep com.anymindgroup::zio-gcp-aiplatform-v1::0.0.4

import zio.*, com.anymindgroup.gcp.auth.*
import com.anymindgroup.gcp.aiplatform.v1.*, resources.*, schemas.*

object vertex_ai_generate_content extends ZIOAppDefault:
  def run =
    defaultAccessTokenBackend().flatMap:
      _.send {
        val endpoint = Endpoint.`asia-northeast1`
        projects.locations.publishers.Models.generateContent(
          projectsId = "anychat-staging",
          locationsId = endpoint.location,
          publishersId = "google",
          modelsId = "gemini-1.5-flash",
          request = GoogleCloudAiplatformV1GenerateContentRequest(
            contents = Chunk(
              GoogleCloudAiplatformV1Content(
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("hello how are doing?"))),
                role = Some("user"),
              )
            )
          ),
          endpointUrl = endpoint.url,
        )
      }.debug
