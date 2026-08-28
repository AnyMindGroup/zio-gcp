package com.anymindgroup.gcp.aiplatform

import zio.Chunk

import com.anymindgroup.gcp.aiplatform.v1.schemas.{
  GoogleCloudAiplatformV1GenerateContentResponse,
  GoogleCloudAiplatformV1Part,
}
import com.github.plokhotnyuk.jsoniter_scala.core.*

private def responseParts(r: GoogleCloudAiplatformV1GenerateContentResponse): Chunk[GoogleCloudAiplatformV1Part] =
  r.candidates
    .getOrElse(Chunk.empty)
    .flatMap(_.content.to(Chunk))
    .flatMap(_.parts)

extension (r: GoogleCloudAiplatformV1GenerateContentResponse)
  // Text parts of the model's answer.
  // Parts marked as `thought` (a thinking summary) are excluded: they are not part of the answer
  // and would otherwise be glued in front of it, breaking e.g. decoding of a structured response.
  def textCandidates: Chunk[String] = responseParts(r)
    .filterNot(_.thought.contains(true))
    .flatMap(_.text.to(Chunk))

  def text: String = textCandidates.mkString

  // Text parts of the model's thought process, returned when thinking summaries are requested.
  def thoughtTextCandidates: Chunk[String] = responseParts(r)
    .filter(_.thought.contains(true))
    .flatMap(_.text.to(Chunk))

  def thoughtText: String = thoughtTextCandidates.mkString

  def decodeTextUnsafe[R](using c: JsonValueCodec[R]): R = readFromString[R](text)

  def decodeText[R](using c: JsonValueCodec[R]): Either[Throwable, R] =
    try Right(readFromString[R](text))
    catch case e: Throwable => Left(e)

  def decodeText[R](decode: String => Either[Throwable, R]): Either[Throwable, R] = decode(text)
