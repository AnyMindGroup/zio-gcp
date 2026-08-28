package com.anymindgroup.gcp.aiplatform

import zio.Chunk
import zio.test.*

import com.anymindgroup.gcp.aiplatform.v1.schemas.*
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object GenerateContentResponseSpec extends ZIOSpecDefault {

  case class Answer(answer: String)
  given JsonValueCodec[Answer] = JsonCodecMaker.make

  private def response(parts: GoogleCloudAiplatformV1Part*): GoogleCloudAiplatformV1GenerateContentResponse =
    GoogleCloudAiplatformV1GenerateContentResponse(
      candidates = Some(
        Chunk(
          GoogleCloudAiplatformV1Candidate(
            content = Some(
              GoogleCloudAiplatformV1Content(
                parts = Chunk.fromIterable(parts),
                role = Some("model"),
              )
            )
          )
        )
      )
    )

  private def thoughtPart(text: String): GoogleCloudAiplatformV1Part =
    GoogleCloudAiplatformV1Part(text = Some(text), thought = Some(true))

  private def answerPart(text: String): GoogleCloudAiplatformV1Part =
    GoogleCloudAiplatformV1Part(text = Some(text))

  override def spec = suite("GenerateContentResponseSpec")(
    test("text returns all text parts when no thinking summary is present") {
      val res = response(answerPart("Hello, "), answerPart("world!"))

      assertTrue(
        res.textCandidates == Chunk("Hello, ", "world!"),
        res.text == "Hello, world!",
        res.thoughtTextCandidates.isEmpty,
        res.thoughtText.isEmpty,
      )
    },
    test("text excludes thinking summary parts") {
      val res = response(
        thoughtPart("The user wants a greeting. "),
        thoughtPart("I will keep it short."),
        answerPart("Hello, world!"),
      )

      assertTrue(
        res.textCandidates == Chunk("Hello, world!"),
        res.text == "Hello, world!",
        res.thoughtTextCandidates == Chunk("The user wants a greeting. ", "I will keep it short."),
        res.thoughtText == "The user wants a greeting. I will keep it short.",
      )
    },
    test("a structured response decodes with a thinking summary in front of it") {
      val res = response(
        thoughtPart("Let me think about what to answer."),
        answerPart("""{"answer":"42"}"""),
      )

      assertTrue(res.decodeText[Answer] == Right(Answer("42")))
    },
    test("parts of a part-less response are empty") {
      val res = GoogleCloudAiplatformV1GenerateContentResponse()

      assertTrue(
        res.textCandidates.isEmpty,
        res.text.isEmpty,
        res.thoughtTextCandidates.isEmpty,
        res.thoughtText.isEmpty,
      )
    },
  )
}
