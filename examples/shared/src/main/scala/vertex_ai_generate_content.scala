//> using scala 3.6.3
//> using dep com.anymindgroup::zio-gcp-auth::0.0.4
//> using dep com.anymindgroup::zio-gcp-aiplatform-v1::0.0.4

import zio.*
import com.anymindgroup.gcp.*, auth.*
import aiplatform.v1.*, aiplatform.v1.resources.*, aiplatform.v1.schemas.*
import sttp.client4.Response

object vertex_ai_generate_content extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    endpoint       = Endpoint.`asia-northeast1`
    request = projects.locations.publishers.Models.generateContent(
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
    _ <- authedBackend
           .send(request)
           .flatMap:
             case Response(Right(body), _, _, _, _, _) => Console.printLine(s"Response ok: $body")
             case Response(Left(err), _, _, _, _, _)   => Console.printError(s"Failure: $err")
  yield ()
