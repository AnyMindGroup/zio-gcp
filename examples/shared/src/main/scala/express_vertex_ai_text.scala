import zio.*, com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.ExpressModelClient

object express_vertex_ai_text extends ZIOAppDefault:
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
  yield ()
