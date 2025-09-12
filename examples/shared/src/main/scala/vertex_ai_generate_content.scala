//> using scala 3.7.2
//> using dep com.anymindgroup::zio-gcp-auth::0.2.3
//> using dep com.anymindgroup::zio-gcp-aiplatform-v1::0.2.3

import zio.*, com.anymindgroup.gcp.*, auth.defaultAccessTokenBackend
import aiplatform.v1.*, aiplatform.v1.resources.*, aiplatform.v1.schemas.*

object vertex_ai_generate_content extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    endpoint       = Endpoint.`asia-northeast1`
    request        = projects.locations.publishers.Models.generateContent(
                projectsId = "my-gcp-project",
                locationsId = endpoint.location,
                publishersId = "google",
                modelsId = "gemini-1.5-flash",
                request = GoogleCloudAiplatformV1GenerateContentRequest(
                  contents = Chunk(
                    GoogleCloudAiplatformV1Content(
                      parts = Chunk(
                        GoogleCloudAiplatformV1Part(
                          text = Some("hello how are you doing?")
                        )
                      ),
                      role = Some("user"),
                    )
                  )
                ),
                endpointUrl = endpoint.url,
              )
    _ <- authedBackend
           .send(request)
           .flatMap:
             _.body match
               case Right(body) => ZIO.logInfo(s"Response ok: $body")
               case Left(err)   => ZIO.logError(s"Failure: $err")
  yield ()
