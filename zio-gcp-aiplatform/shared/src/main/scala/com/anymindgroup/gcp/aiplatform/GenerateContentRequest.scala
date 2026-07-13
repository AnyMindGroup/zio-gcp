package com.anymindgroup.gcp.aiplatform

import zio.Chunk
import zio.schema.Schema

import com.anymindgroup.gcp.aiplatform.v1.schemas.{
  GoogleCloudAiplatformV1Content,
  GoogleCloudAiplatformV1FunctionDeclaration,
  GoogleCloudAiplatformV1GenerateContentRequest,
  GoogleCloudAiplatformV1GenerationConfig,
  GoogleCloudAiplatformV1Part,
  GoogleCloudAiplatformV1Schema,
  GoogleCloudAiplatformV1Tool,
}

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

extension (r: GoogleCloudAiplatformV1GenerateContentRequest)
  def withSystemInstructions(
    systemInstructions: String
  ): GoogleCloudAiplatformV1GenerateContentRequest = r.copy(
    systemInstruction = Some(
      GoogleCloudAiplatformV1Content(
        parts = Chunk(GoogleCloudAiplatformV1Part(text = Some(systemInstructions))),
        role = Some("user"),
      )
    )
  )

  def withFunctions(
    functions: Map[String, FunctionDeclaration[?, ?]]
  ): GoogleCloudAiplatformV1GenerateContentRequest =
    val funcTools = GoogleCloudAiplatformV1Tool(
      // sort by function name to keep the emitted declarations deterministic across runs
      functionDeclarations = Some(Chunk.fromIterable(functions.toSeq.sortBy(_._1).map { case (name, fn) =>
        GoogleCloudAiplatformV1FunctionDeclaration(
          name = name,
          description = fn.description,
          parameters = Some(fn.inputSchema),
          response = Some(fn.outputSchema),
        )
      }))
    )

    r.copy(tools =
      Some(
        r.tools match
          case None        => Chunk(funcTools)
          case Some(tools) =>
            tools.map(tool =>
              tool.copy(
                functionDeclarations =
                  // ensure functions with the same name are filtered out to prevent conflicts
                  tool.functionDeclarations.map(_.filterNot(f => functions.keySet.contains(f.name)))
              )
            ) :+ funcTools
      )
    )
