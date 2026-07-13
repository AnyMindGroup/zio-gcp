package com.anymindgroup.gcp.aiplatform

import zio.Chunk

import com.anymindgroup.gcp.aiplatform.v1.schemas.GoogleCloudAiplatformV1GenerateContentResponse
import com.github.plokhotnyuk.jsoniter_scala.core.*

extension (r: GoogleCloudAiplatformV1GenerateContentResponse)
  def textCandidates: Chunk[String] = r.candidates
    .getOrElse(Chunk.empty)
    .flatMap(_.content.to(Chunk))
    .flatMap(_.parts)
    .flatMap(_.text.to(Chunk))

  def text = textCandidates.mkString

  def decodeTextUnsafe[R](using c: JsonValueCodec[R]): R = readFromString[R](text)

  def decodeText[R](using c: JsonValueCodec[R]): Either[Throwable, R] =
    try Right(readFromString[R](text))
    catch case e: Throwable => Left(e)

  def decodeText[R](decode: String => Either[Throwable, R]): Either[Throwable, R] = decode(text)
