package com.anymindgroup.gcp.aiplatform

import com.anymindgroup.gcp.aiplatform.v1.schemas.*
import com.anymindgroup.jsoniter.Json
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.client4.{Backend, StringBody}
import sttp.model.StatusCode

import zio.schema.*
import zio.test.*
import zio.{Chunk, Ref, Task, ZIO}

object ExpressModelClientSpec extends ZIOSpecDefault {

  // input/output types for the stubbed functions
  case class City(name: String) derives Schema
  given JsonValueCodec[City] = JsonCodecMaker.make
  case class WeatherResult(temperature: Int, unit: String, conditions: String) derives Schema
  given JsonValueCodec[WeatherResult] = JsonCodecMaker.make
  case class TimeZone(zone: String) derives Schema
  given JsonValueCodec[TimeZone] = JsonCodecMaker.make
  case class TimeResult(time: String) derives Schema
  given JsonValueCodec[TimeResult] = JsonCodecMaker.make

  // builds a response with a single text part
  private def textResponse(text: String): String =
    GoogleCloudAiplatformV1GenerateContentResponse(
      candidates = Some(
        Chunk(
          GoogleCloudAiplatformV1Candidate(
            content = Some(
              GoogleCloudAiplatformV1Content(
                parts = Chunk(GoogleCloudAiplatformV1Part(text = Some(text))),
                role = Some("model"),
              )
            )
          )
        )
      )
    ).toJsonString

  // builds a response with one candidate holding one function call part per provided call.
  // a thoughtSignature must be present for the client to pick up the function call.
  private def functionCallResponse(calls: (String, Json, String)*): String =
    GoogleCloudAiplatformV1GenerateContentResponse(
      candidates = Some(
        Chunk(
          GoogleCloudAiplatformV1Candidate(
            content = Some(
              GoogleCloudAiplatformV1Content(
                parts = Chunk.fromIterable(calls.map { case (name, args, signature) =>
                  GoogleCloudAiplatformV1Part(
                    functionCall = Some(GoogleCloudAiplatformV1FunctionCall(name = Some(name), args = Some(args))),
                    thoughtSignature = Some(signature),
                  )
                }),
                role = Some("model"),
              )
            )
          )
        )
      )
    ).toJsonString

  // stub http backend that replies to the :generateContent endpoint with the n-th response
  // for the n-th request, recording every request body it received.
  private def stubBackend(responses: Chunk[String], requests: Ref[Chunk[String]]): Backend[Task] =
    BackendStub[Task](new RIOMonadAsyncError[Any])
      .whenRequestMatches(_.uri.pathToString.endsWith(":generateContent"))
      .thenRespondF { req =>
        val body = req.body match {
          case StringBody(s, _, _) => s
          case _                   => ""
        }
        requests
          .modify(prev => (prev.size, prev :+ body))
          .map(idx => ResponseStub.adjust(responses.lift(idx).getOrElse(responses.last), StatusCode.Ok))
      }

  private def client(backend: Backend[Task]): ExpressModelClient =
    ExpressModelClient(
      backend = backend,
      projectsId = "test-project",
      locationsId = "global",
      publishersId = "google",
      modelsId = "gemini-test",
    )

  private val requestCodec: JsonValueCodec[GoogleCloudAiplatformV1GenerateContentRequest] = summon

  def spec = suite("ExpressModelClientSpec")(
    test("send executes a provided function and returns the final text response") {
      for {
        requests <- Ref.make(Chunk.empty[String])
        fnInputs <- Ref.make(Chunk.empty[City])
        responses = Chunk(
                      functionCallResponse(("get_weather", Json.writeToJson(City("Tokyo")), "sig-weather")),
                      textResponse("The weather in Tokyo is sunny, 22°C."),
                    )
        functions = Map(
                      "get_weather" -> FunctionDeclaration(
                        function = (c: City) => fnInputs.update(_ :+ c).as(WeatherResult(22, "celsius", "Sunny")),
                        description = Some("Get the current weather for a given city"),
                      )
                    )
        res <- client(stubBackend(responses, requests)).send(
                 GenerateContentRequest("What is the weather like in Tokyo?"),
                 functions,
               )
        sentRequests <- requests.get
        calledWith   <- fnInputs.get
      } yield assertTrue(
        res.text == "The weather in Tokyo is sunny, 22°C.",
        calledWith == Chunk(City("Tokyo")),
        sentRequests.size == 2,
      )
    },
    test("send attaches the function declarations as tools on the request") {
      for {
        requests <- Ref.make(Chunk.empty[String])
        responses = Chunk(textResponse("done"))
        functions = Map(
                      "get_weather" -> FunctionDeclaration(
                        function = (_: City) => ZIO.succeed(WeatherResult(0, "celsius", "")),
                        description = Some("Get the current weather for a given city"),
                      )
                    )
        _            <- client(stubBackend(responses, requests)).send(GenerateContentRequest("hi"), functions)
        sentRequests <- requests.get
        firstReq      = readFromString(sentRequests.head)(using requestCodec)
        declarations  = firstReq.tools
                         .getOrElse(Chunk.empty)
                         .flatMap(_.functionDeclarations.getOrElse(Chunk.empty))
      } yield assertTrue(
        declarations.map(_.name) == Chunk("get_weather"),
        declarations.head.description == Some("Get the current weather for a given city"),
        declarations.head.parameters.flatMap(_.`type`) == Some(GoogleCloudAiplatformV1Schema.Type.OBJECT),
      )
    },
    test("send echoes the function call and its response back to the model on the next request") {
      for {
        requests <- Ref.make(Chunk.empty[String])
        fnInputs <- Ref.make(Chunk.empty[City])
        responses = Chunk(
                      functionCallResponse(("get_weather", Json.writeToJson(City("Osaka")), "sig-1")),
                      textResponse("It is rainy."),
                    )
        functions = Map(
                      "get_weather" -> FunctionDeclaration(
                        function = (c: City) => fnInputs.update(_ :+ c).as(WeatherResult(18, "celsius", "Rainy")),
                        description = None,
                      )
                    )
        _            <- client(stubBackend(responses, requests)).send(GenerateContentRequest("weather?"), functions)
        sentRequests <- requests.get
        secondReq     = readFromString(sentRequests(1))(using requestCodec)
        parts         = secondReq.contents.flatMap(_.parts)
      } yield assertTrue(
        // the model's function call is echoed back with its thought signature
        parts.exists(p => p.functionCall.flatMap(_.name) == Some("get_weather") && p.thoughtSignature == Some("sig-1")),
        // the executed function result is sent back as a function response
        parts.exists(_.functionResponse.exists(_.name == "get_weather")),
      )
    },
    test("send returns the text response directly when the model requests no function call") {
      for {
        requests <- Ref.make(Chunk.empty[String])
        fnInputs <- Ref.make(Chunk.empty[City])
        responses = Chunk(textResponse("No tools needed here."))
        functions = Map(
                      "get_weather" -> FunctionDeclaration(
                        function = (c: City) => fnInputs.update(_ :+ c).as(WeatherResult(0, "celsius", "")),
                        description = None,
                      )
                    )
        res          <- client(stubBackend(responses, requests)).send(GenerateContentRequest("hello"), functions)
        sentRequests <- requests.get
        calledWith   <- fnInputs.get
      } yield assertTrue(
        res.text == "No tools needed here.",
        calledWith.isEmpty,
        sentRequests.size == 1,
      )
    },
    test("send fails when the model requests an unknown function") {
      for {
        requests <- Ref.make(Chunk.empty[String])
        responses = Chunk(functionCallResponse(("unknown_fn", Json.writeToJson(City("Tokyo")), "sig")))
        functions = Map(
                      "get_weather" -> FunctionDeclaration(
                        function = (_: City) => ZIO.succeed(WeatherResult(0, "celsius", "")),
                        description = None,
                      )
                    )
        error <- client(stubBackend(responses, requests)).send(GenerateContentRequest("hi"), functions).flip
      } yield assertTrue(error.getMessage == "Unknown function: unknown_fn")
    },
    test("send executes multiple function calls returned in a single turn") {
      for {
        requests     <- Ref.make(Chunk.empty[String])
        weatherCalls <- Ref.make(Chunk.empty[City])
        timeCalls    <- Ref.make(Chunk.empty[TimeZone])
        responses     = Chunk(
                      functionCallResponse(
                        ("get_weather", Json.writeToJson(City("Tokyo")), "sig-w"),
                        ("get_time", Json.writeToJson(TimeZone("JST")), "sig-t"),
                      ),
                      textResponse("It is sunny at 10:00 in Tokyo."),
                    )
        functions = Map(
                      "get_weather" -> FunctionDeclaration(
                        function = (c: City) => weatherCalls.update(_ :+ c).as(WeatherResult(22, "celsius", "Sunny")),
                        description = None,
                      ),
                      "get_time" -> FunctionDeclaration(
                        function = (t: TimeZone) => timeCalls.update(_ :+ t).as(TimeResult("10:00")),
                        description = None,
                      ),
                    )
        res <- client(stubBackend(responses, requests)).send(
                 GenerateContentRequest("weather and time in Tokyo?"),
                 functions,
               )
        sentRequests <- requests.get
        weather      <- weatherCalls.get
        time         <- timeCalls.get
      } yield assertTrue(
        res.text == "It is sunny at 10:00 in Tokyo.",
        weather == Chunk(City("Tokyo")),
        time == Chunk(TimeZone("JST")),
        sentRequests.size == 2,
      )
    },
    test("send loops over multiple function calling turns until a text response is returned") {
      for {
        requests <- Ref.make(Chunk.empty[String])
        fnInputs <- Ref.make(Chunk.empty[City])
        responses = Chunk(
                      functionCallResponse(("get_weather", Json.writeToJson(City("Tokyo")), "sig-1")),
                      functionCallResponse(("get_weather", Json.writeToJson(City("Osaka")), "sig-2")),
                      textResponse("Both cities checked."),
                    )
        functions = Map(
                      "get_weather" -> FunctionDeclaration(
                        function = (c: City) => fnInputs.update(_ :+ c).as(WeatherResult(20, "celsius", "Cloudy")),
                        description = None,
                      )
                    )
        res          <- client(stubBackend(responses, requests)).send(GenerateContentRequest("weather?"), functions)
        sentRequests <- requests.get
        calledWith   <- fnInputs.get
      } yield assertTrue(
        res.text == "Both cities checked.",
        calledWith == Chunk(City("Tokyo"), City("Osaka")),
        sentRequests.size == 3,
      )
    },
  )
}
