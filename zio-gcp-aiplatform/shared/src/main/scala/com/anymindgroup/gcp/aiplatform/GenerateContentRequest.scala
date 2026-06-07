package com.anymindgroup.gcp.aiplatform

import com.anymindgroup.gcp.aiplatform.v1.schemas.{
  GoogleCloudAiplatformV1Content,
  GoogleCloudAiplatformV1GenerateContentRequest,
  GoogleCloudAiplatformV1GenerationConfig,
  GoogleCloudAiplatformV1Part,
  GoogleCloudAiplatformV1Schema,
}

import zio.Chunk
import zio.schema.Schema

object GenerateContentRequest {
  def apply(text: String): GoogleCloudAiplatformV1GenerateContentRequest =
    GoogleCloudAiplatformV1GenerateContentRequest(
      contents = Chunk(
        GoogleCloudAiplatformV1Content(
          parts = Chunk(GoogleCloudAiplatformV1Part(text = Some(text))),
          role = Some("user"),
        )
      )
    )

  def apply(
    text: String,
    responseSchema: Schema[?],
  ): GoogleCloudAiplatformV1GenerateContentRequest =
    apply(
      Chunk(
        GoogleCloudAiplatformV1Content(
          parts = Chunk(GoogleCloudAiplatformV1Part(text = Some(text))),
          role = Some("user"),
        )
      ),
      AiPlatformSchema.toGoogleCloudAiplatformV1Schema(responseSchema),
    )

  def apply(
    contents: Chunk[GoogleCloudAiplatformV1Content],
    responseSchema: Schema[?],
  ): GoogleCloudAiplatformV1GenerateContentRequest =
    apply(contents, AiPlatformSchema.toGoogleCloudAiplatformV1Schema(responseSchema))

  def apply(
    contents: Chunk[GoogleCloudAiplatformV1Content],
    responseSchema: GoogleCloudAiplatformV1Schema,
  ): GoogleCloudAiplatformV1GenerateContentRequest =
    GoogleCloudAiplatformV1GenerateContentRequest(
      contents = contents,
      generationConfig = Some(
        GoogleCloudAiplatformV1GenerationConfig(
          responseSchema = Some(responseSchema),
          responseMimeType = Some("application/json"),
        )
      ),
    )
}
