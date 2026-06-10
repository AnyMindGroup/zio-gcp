import zio.*, zio.schema.*, com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.anymindgroup.gcp.aiplatform.v1.schemas.*
import com.anymindgroup.gcp.aiplatform.*
import com.github.plokhotnyuk.jsoniter_scala as json

object express_vertex_ai_function_calling extends ZIOAppDefault:
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
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("What is the weather like in Tokyo?"))),
                role = Some("user"),
              )
            ),
            responseSchema = Schema[WeatherResult],
          )

    res <- express.send(
             baseRequest = msg,
             functions = Map(
                "get_weather" -> FunctionDeclaration(
                  function =
                    (city: City) => ZIO.succeed(WeatherResult(temperature = 22, unit = "celsius", conditions = "Sunny")),
                  description = Some("Get the current weather for a given city"),
                )
             ),
           )
    _ = println(s"Result: $res")
  yield ()

  case class City(name: String) derives Schema
  given json.core.JsonValueCodec[City] = json.macros.JsonCodecMaker.make
  case class WeatherResult(temperature: Int, unit: String, conditions: String) derives Schema
  given json.core.JsonValueCodec[WeatherResult] = json.macros.JsonCodecMaker.make
