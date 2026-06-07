import zio.*, zio.schema.*, com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.v1.schemas.*
import com.anymindgroup.gcp.aiplatform.*
import com.github.plokhotnyuk.jsoniter_scala as json

object express_vertex_ai_request extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    express        = ExpressModelClient(
                backend = authedBackend,
                projectsId = "my-gcp-project",
                locationsId = "global",
                publishersId = "google",
                modelsId = "gemini-3.5-flash",
              )

    msg = GenerateContentRequest(
            contents = Chunk(
              GoogleCloudAiplatformV1Content(
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("hello how are you doing?"))),
                role = Some("user"),
              )
            ),
            responseSchema = Schema[Joke],
          ).withSystemInstructions("just reply to the question")

    joke <- express.generate[Joke](msg)
    _     = println("Joke: " + joke.setup + " \u2014 " + joke.punchline)
  yield ()

  case class Joke(setup: String, punchline: String) derives Schema
  given json.core.JsonValueCodec[Joke] = json.macros.JsonCodecMaker.make
