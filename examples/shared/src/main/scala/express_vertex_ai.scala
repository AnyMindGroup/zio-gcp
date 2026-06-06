import zio.*, zio.schema.*, com.anymindgroup.gcp.*, auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.*
import com.anymindgroup.gcp.aiplatform.v1.*, v1.resources.*, v1.schemas.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

case class Joke(setup: String, punchline: String) derives Schema
given JsonValueCodec[Joke] = JsonCodecMaker.make

object express_vertex_ai extends ZIOAppDefault:
  def run = for
    authedBackend <- defaultAccessTokenBackend()
    express        = Express(
                       backend = authedBackend,
                       defaultProjectsId = "my-gcp-project",
                       defaultLocationsId = Endpoint.`asia-northeast1`.location,
                       defaultPublishersId = "google",
                       defaultModelsId = "gemini-3.5-flash",
                     )
    msg            = GoogleCloudAiplatformV1GenerateContentRequest(
                       contents = Chunk(
                         GoogleCloudAiplatformV1Content(
                           parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("hello how are you doing?"))),
                           role  = Some("user"),
                         )
                       ),
                     )
    txt           <- express.generateContentString(msg)
    _             <- ZIO.logInfo("Text response: " + txt)
    joke          <- express.generateContent[Joke](msg)
    _             <- ZIO.logInfo("Joke: " + joke.setup + " \u2014 " + joke.punchline)
  yield ()
