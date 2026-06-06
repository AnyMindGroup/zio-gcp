import zio.*, zio.schema.*, com.anymindgroup.gcp.*, auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.*
import com.anymindgroup.gcp.aiplatform.v1.*, v1.resources.*, v1.schemas.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

object express_vertex_ai_from_text extends ZIOAppDefault:
  case class Joke(setup: String, punchline: String) derives Schema
  given JsonValueCodec[Joke] = JsonCodecMaker.make
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    express        = Express(
                       backend = authedBackend,
                       defaultProjectsId = "my-gcp-project",
                       defaultLocationsId = Endpoint.`asia-northeast1`.location,
                       defaultPublishersId = "google",
                       defaultModelsId = "gemini-3.5-flash",
                     )

    txt  <- express.generateTextFromText("hello how are you doing?")
    _    <- ZIO.logInfo("Text response: " + txt)

    joke <- express.generateStructuredFromText[Joke]("Tell me a joke")
    _    <- ZIO.logInfo("Joke: " + joke.setup + " \u2014 " + joke.punchline)
  yield ()
