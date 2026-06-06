import zio.*, zio.schema.*, com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.Express
import com.github.plokhotnyuk.jsoniter_scala as json

object express_vertex_ai_from_text extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    express        = Express(
                backend = authedBackend,
                defaultProjectsId = "my-gcp-project",
                defaultLocationsId = "global",
                defaultPublishersId = "google",
                defaultModelsId = "gemini-3.5-flash",
              )

    txt <- express.generateTextFromText("hello how are you doing?")
    _   <- ZIO.logInfo("Text response: " + txt)

    joke <- express.generateStructuredFromText[Joke]("Tell me a joke")
    _    <- ZIO.logInfo("Joke: " + joke.setup + " \u2014 " + joke.punchline)
  yield ()

  case class Joke(setup: String, punchline: String) derives Schema
  given json.core.JsonValueCodec[Joke] = json.macros.JsonCodecMaker.make
