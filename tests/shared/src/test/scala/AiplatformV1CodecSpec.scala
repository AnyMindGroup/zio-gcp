package com.anymindgroup.gcp.aiplatform.v1

import com.anymindgroup.gcp.aiplatform.v1.schemas.GoogleCloudAiplatformV1FunctionCall
import com.anymindgroup.jsoniter.Json
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, *}
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import zio.*
import zio.test.*

object AiplatformV1CodecSpec extends ZIOSpecDefault:
  def spec = suite("AiplatformV1CodecSpec")(
    test("decode/encode GoogleCloudAiplatformV1FunctionCall") {
      val json = """|{
                    |  "name": "func name",
                    |  "args": {
                    |     "arg1": 1,
                    |     "arg2": {
                    |       "nestedArg1": "a",
                    |       "nestedArg2": true
                    |     }
                    |   }
                    |}""".stripMargin

      type Args = (arg1: Int, arg2: (nestedArg1: String, nestedArg2: Boolean, nestedArg3: Option[String]))
      given JsonValueCodec[Args] = JsonCodecMaker.make

      val decoded    = readFromString[GoogleCloudAiplatformV1FunctionCall](json)
      val argsResult = decoded.args.map(_.readAs[Args])

      for
        _ <- assertTrue(decoded.name == Some("func name"))
        _ <- assertTrue(argsResult.nonEmpty)
        _ <- assertTrue(argsResult.exists(_.isRight))

        args         = argsResult.flatMap(_.toOption).get
        expectedArgs = (arg1 = 1, arg2 = (nestedArg1 = "a", nestedArg2 = true, nestedArg3 = None))

        _ <- assertTrue(args == expectedArgs)
        // decoding after encoding yields same result
        _ <- assertTrue(Json.writeToJson(args).readAsUnsafe[Args] == expectedArgs)
      yield assertCompletes
    }
  )
