package com.anymindgroup.gcp.aiplatform

import com.anymindgroup.gcp.aiplatform.v1.resources.projects.locations.publishers
import com.anymindgroup.gcp.aiplatform.v1.schemas.{
  GoogleCloudAiplatformV1GenerateContentRequest,
  GoogleCloudAiplatformV1GenerateContentResponse,
}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.client4.Backend

import zio.schema.Schema
import zio.{Task, ZIO}

// express client with fixed location and model for generating content
class ExpressModelClient(
  backend: Backend[Task],
  projectsId: String,
  locationsId: String,
  publishersId: String,
  modelsId: String,
) {
  def send(
    req: GoogleCloudAiplatformV1GenerateContentRequest
  ): Task[GoogleCloudAiplatformV1GenerateContentResponse] =
    backend
      .send(
        publishers.Models.generateContent(
          projectsId = projectsId,
          locationsId = locationsId,
          publishersId = publishersId,
          modelsId = modelsId,
          request = req,
        )
      )
      .flatMap(res => ZIO.fromEither(res.body))

  def generateText(req: GoogleCloudAiplatformV1GenerateContentRequest): Task[String] =
    send(req).map(_.text)

  def generateText(text: String): Task[String] = generateText(GenerateContentRequest(text))

  def generate[R](text: String)(using schema: Schema[R], c: JsonValueCodec[R]): Task[R] =
    generate[R](GenerateContentRequest(text = text, responseSchema = schema))(using c)

  def generate[R](
    req: GoogleCloudAiplatformV1GenerateContentRequest
  )(using c: JsonValueCodec[R]): Task[R] =
    generate(req = req, decodeResponse = _.decodeText[R])

  def generate[R](
    req: GoogleCloudAiplatformV1GenerateContentRequest,
    decodeResponse: GoogleCloudAiplatformV1GenerateContentResponse => Either[Throwable, R],
  ): Task[R] =
    send(req).flatMap(res => ZIO.fromEither(decodeResponse(res)))
}
