import zio.*, zio.schema.*, com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.v1.schemas.*
import com.anymindgroup.gcp.aiplatform.Express
import com.github.plokhotnyuk.jsoniter_scala as json

object express_vertex_ai_request extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    express        = Express(
                backend = authedBackend,
                defaultProjectsId = "my-gcp-project",
                defaultLocationsId = "global",
                defaultPublishersId = "google",
                defaultModelsId = "gemini-3.5-flash",
              )

    msg = GoogleCloudAiplatformV1GenerateContentRequest(
            contents = Chunk(
              GoogleCloudAiplatformV1Content(
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("hello how are you doing?"))),
                role = Some("user"),
              )
            )
          )

    txt <- express.generateText(msg)
    _   <- ZIO.logInfo("Text response: " + txt)

    joke <- express.generateStructured[Joke](msg)
    _    <- ZIO.logInfo("Joke: " + joke.setup + " \u2014 " + joke.punchline)
  yield ()

  case class Joke(setup: String, punchline: String) derives Schema
  given json.core.JsonValueCodec[Joke] = json.macros.JsonCodecMaker.make
