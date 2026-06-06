package com.anymindgroup.gcp.aiplatform

import com.anymindgroup.gcp.aiplatform.v1.resources.projects.locations.publishers
import com.anymindgroup.gcp.aiplatform.v1.schemas.{
  GoogleCloudAiplatformV1GenerateContentRequest,
  GoogleCloudAiplatformV1GenerationConfig,
}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.client4.Backend

import zio.schema.Schema
import zio.{Chunk, Task, ZIO}

class Express(
  backend: Backend[Task],
  defaultProjectsId: String,
  defaultLocationsId: String,
  defaultPublishersId: String,
  defaultModelsId: String,
) {
  def generateContentString(
    req: GoogleCloudAiplatformV1GenerateContentRequest,
    projectsId: String = defaultProjectsId,
    locationsId: String = defaultLocationsId,
    publishersId: String = defaultPublishersId,
    modelsId: String = defaultModelsId,
  ): Task[String] = {
    val httpRequest = publishers.Models.generateContent(
      projectsId = projectsId,
      locationsId = locationsId,
      publishersId = publishersId,
      modelsId = modelsId,
      request = req,
    )
    backend.send(httpRequest).flatMap { response =>
      response.body match {
        case Right(r) =>
          ZIO.succeed(
            r.candidates
              .getOrElse(Chunk.empty)
              .flatMap(_.content.to(Chunk))
              .flatMap(_.parts)
              .flatMap(_.text.to(Chunk))
              .mkString
          )
        case Left(err) => ZIO.fail(new RuntimeException(s"Generate content API error: $err"))
      }
    }
  }

  def generateContent[R](
    req: GoogleCloudAiplatformV1GenerateContentRequest,
    projectsId: String = defaultProjectsId,
    locationsId: String = defaultLocationsId,
    publishersId: String = defaultPublishersId,
    modelsId: String = defaultModelsId,
  )(using schema: Schema[R], c: JsonValueCodec[R]): Task[R] = {
    val aiSchema = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(schema)

    val updatedReq = req.copy(
      generationConfig = Some(
        req.generationConfig
          .getOrElse(GoogleCloudAiplatformV1GenerationConfig())
          .copy(
            responseSchema = Some(aiSchema),
            responseMimeType = Some("application/json"),
          )
      )
    )

    generateContentString(updatedReq, projectsId, locationsId, publishersId, modelsId)
      .flatMap(text => ZIO.attempt(readFromArray[R](text.getBytes("UTF-8"))))
  }
}
