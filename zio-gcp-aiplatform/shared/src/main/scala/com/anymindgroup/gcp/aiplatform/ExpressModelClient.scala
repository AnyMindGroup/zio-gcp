package com.anymindgroup.gcp.aiplatform

import com.anymindgroup.gcp.aiplatform.v1.resources.projects.locations.publishers
import com.anymindgroup.gcp.aiplatform.v1.schemas.{
  GoogleCloudAiplatformV1Content,
  GoogleCloudAiplatformV1FunctionCall,
  GoogleCloudAiplatformV1FunctionResponse,
  GoogleCloudAiplatformV1GenerateContentRequest,
  GoogleCloudAiplatformV1GenerateContentResponse,
  GoogleCloudAiplatformV1Part,
}
import com.anymindgroup.jsoniter.Json
import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.client4.Backend

import zio.schema.Schema
import zio.{Chunk, Task, ZIO}

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

  def send(
    baseRequest: GoogleCloudAiplatformV1GenerateContentRequest,
    functions: Map[String, FunctionDeclaration[?, ?]],
  ): Task[GoogleCloudAiplatformV1GenerateContentResponse] = ZIO.suspendSucceed {
    val baseReqWithFunctions = baseRequest.withFunctions(functions)

    def processFnCall(
      fnName: String,
      fnCall: GoogleCloudAiplatformV1FunctionCall,
    ): Task[GoogleCloudAiplatformV1Content] =
      for
        fn     <- ZIO.fromEither(functions.get(fnName).toRight(Throwable(s"Unknown function: $fnName")))
        input   = fnCall.args.getOrElse(Json.codec.nullValue)
        output <- fn(input)
      yield GoogleCloudAiplatformV1Content(
        parts = Chunk(
          GoogleCloudAiplatformV1Part(
            functionResponse = Some(
              GoogleCloudAiplatformV1FunctionResponse(
                name = fnName,
                response = fn.encodeOutput(output),
              )
            )
          )
        ),
        role = Some("user"),
      )

    def loop(contents: Chunk[GoogleCloudAiplatformV1Content]): Task[GoogleCloudAiplatformV1GenerateContentResponse] =
      val req = baseReqWithFunctions.copy(contents = contents)
      send(req).flatMap { resp =>
        val fnCalls = resp.candidates
          .to(Chunk)
          .flatMap(_.flatMap(_.content.to(Chunk)))
          .flatMap: content =>
            content.parts.collect:
              case GoogleCloudAiplatformV1Part(
                    _,
                    _,
                    _,
                    Some(fn @ GoogleCloudAiplatformV1FunctionCall(_, Some(name), _, _)),
                    _,
                    _,
                    _,
                    _,
                    _,
                    Some(thoughtSignature),
                    _,
                  ) =>
                (thoughtSignature, name, fn)

        if fnCalls.isEmpty then ZIO.succeed(resp)
        else
          ZIO
            .foreachPar(fnCalls) { (thoughtSignature, name, fn) =>
              processFnCall(name, fn).map { fnRespContent =>
                // echo back the function call with its thoughtSignature
                val modelFnContent = GoogleCloudAiplatformV1Content(
                  parts = Chunk(
                    GoogleCloudAiplatformV1Part(
                      functionCall = Some(fn),
                      thoughtSignature = Some(thoughtSignature),
                    )
                  ),
                  role = Some("model"),
                )
                Chunk(modelFnContent, fnRespContent)
              }
            }
            .flatMap(results => loop(contents ++ results.flatten))
      }

    loop(baseRequest.contents)
  }

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
    baseRequest: GoogleCloudAiplatformV1GenerateContentRequest,
    functions: Map[String, FunctionDeclaration[?, ?]],
  )(using c: JsonValueCodec[R]): Task[R] =
    generate(baseRequest = baseRequest, functions = functions, decodeResponse = _.decodeText[R])

  def generate[R](
    req: GoogleCloudAiplatformV1GenerateContentRequest,
    decodeResponse: GoogleCloudAiplatformV1GenerateContentResponse => Either[Throwable, R],
  ): Task[R] =
    send(req).flatMap(res => ZIO.fromEither(decodeResponse(res)))

  def generate[R](
    baseRequest: GoogleCloudAiplatformV1GenerateContentRequest,
    functions: Map[String, FunctionDeclaration[?, ?]],
    decodeResponse: GoogleCloudAiplatformV1GenerateContentResponse => Either[Throwable, R],
  ): Task[R] =
    send(baseRequest, functions).flatMap(res => ZIO.fromEither(decodeResponse(res)))
}
