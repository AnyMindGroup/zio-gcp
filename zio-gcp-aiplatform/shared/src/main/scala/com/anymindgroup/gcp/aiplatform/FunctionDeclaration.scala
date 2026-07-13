package com.anymindgroup.gcp.aiplatform

import zio.schema.Schema
import zio.{Task, ZIO}

import com.anymindgroup.gcp.aiplatform.v1.schemas.GoogleCloudAiplatformV1Schema
import com.anymindgroup.jsoniter.Json
import com.github.plokhotnyuk.jsoniter_scala.core.*

// functionDeclaration parameters schema should be of type OBJECT.
// Learn more: https://cloud.google.com/vertex-ai/generative-ai/docs/multimodal/function-calling
case class FunctionDeclaration[A <: Product, B <: Product](
  function: A => Task[B],
  description: Option[String],
  decodeInput: Json => Either[Throwable, A],
  encodeOutput: B => com.anymindgroup.jsoniter.Json,
)(using inSchema: Schema[A], outSchema: Schema[B]) {
  val inputSchema: GoogleCloudAiplatformV1Schema = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(inSchema)

  val outputSchema: GoogleCloudAiplatformV1Schema = AiPlatformSchema.toGoogleCloudAiplatformV1Schema(outSchema)

  def apply(input: Json): Task[B] = ZIO.fromEither(decodeInput(input)).flatMap(function(_))
}

object FunctionDeclaration {
  def apply[A <: Product, B <: Product](
    function: A => Task[B],
    description: Option[String],
  )(using
    inSchema: Schema[A],
    outSchema: Schema[B],
    inputCodec: JsonValueCodec[A],
    outputCodec: JsonValueCodec[B],
  ): FunctionDeclaration[A, B] = FunctionDeclaration(
    function = function,
    description = description,
    decodeInput = _.readAs[A],
    encodeOutput = (out: B) => Json.writeToJson(out),
  )
}
