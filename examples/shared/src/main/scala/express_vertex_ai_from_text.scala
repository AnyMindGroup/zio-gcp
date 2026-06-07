import zio.*, zio.schema.*, com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.ExpressModelClient
import com.github.plokhotnyuk.jsoniter_scala as json

object express_vertex_ai_from_text extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    express        = ExpressModelClient(
                backend = authedBackend,
                projectsId = "my-gcp-project",
                locationsId = "global",
                publishersId = "google",
                modelsId = "gemini-3.5-flash",
              )

    txt <- express.generateText("hello how are you doing?")
    _    = println("Text response: " + txt)

    joke <- express.generate[Joke]("Tell me a joke")
    _     = println("Joke: " + joke.setup + " \u2014 " + joke.punchline)
  yield ()

  case class Joke(setup: String, punchline: String) derives Schema
  given json.core.JsonValueCodec[Joke] = json.macros.JsonCodecMaker.make
