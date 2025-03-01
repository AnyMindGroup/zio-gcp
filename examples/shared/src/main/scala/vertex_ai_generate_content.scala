//> using scala 3.6.3
//> using dep com.anymindgroup::zio-gcp-auth::0.0.4
//> using dep com.anymindgroup::zio-gcp-aiplatform-v1::0.0.4

import zio.*, Console.{printLine, printError}
import com.anymindgroup.gcp.*, auth.*
import aiplatform.v1.*, aiplatform.v1.resources.*, aiplatform.v1.schemas.*

object vertex_ai_generate_content extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    endpoint       = Endpoint.`asia-northeast1`
    request = projects.locations.publishers.Models.generateContent(
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
           .map(_.body)
           .flatMap:
             case Right(body) => printLine(s"Response ok: $body")
             case Left(err)   => printError(s"Failure: $err")
  yield ()
