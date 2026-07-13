import zio.*
import zio.schema.*

import com.anymindgroup.gcp.aiplatform.*
import com.anymindgroup.gcp.aiplatform.v1.schemas.*
import com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import com.github.plokhotnyuk.jsoniter_scala as json

object express_vertex_ai_custom_req extends ZIOAppDefault:
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
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("hello, my name is John"))),
                role = Some("user"),
              ),
              GoogleCloudAiplatformV1Content(
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("I'm 24 years old"))),
                role = Some("user"),
              ),
              GoogleCloudAiplatformV1Content(
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("I am from the US"))),
                role = Some("user"),
              ),
            ),
            responseSchema = Schema[Person],
          ).withSystemInstructions("extract person's information from the conversation")

    person <- express.generate[Person](msg)
    _       = println("Name: " + person.name)
    _       = println("Age: " + person.age)
    _       = println("Country: " + person.country)
  yield ()

  case class Person(name: Option[String], age: Option[Int], country: Option[String]) derives Schema
  given json.core.JsonValueCodec[Person] = json.macros.JsonCodecMaker.make
