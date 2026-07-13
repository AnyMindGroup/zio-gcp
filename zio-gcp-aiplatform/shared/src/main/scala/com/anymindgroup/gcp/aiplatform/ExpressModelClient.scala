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
import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.client4.Backend

import zio.schema.Schema
import zio.{Chunk, Task, ZIO}

sealed abstract class FunctionCallException(message: String) extends Throwable(message)
object FunctionCallException {
  final case class UnknownFunction(name: String) extends FunctionCallException(s"Unknown function: $name")
  final case class MissingArguments(name: String)
      extends FunctionCallException(s"Missing arguments for function call: $name")
  case object MissingFunctionName extends FunctionCallException("Received a function call without a name")
}

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
        fn     <- ZIO.fromEither(functions.get(fnName).toRight(FunctionCallException.UnknownFunction(fnName)))
        input  <- ZIO.fromOption(fnCall.args).orElseFail(FunctionCallException.MissingArguments(fnName))
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
          .getOrElse(Chunk.empty)
          .flatMap(_.content.to(Chunk))
          .flatMap(_.parts)
          .flatMap(part => part.functionCall.map((part, _)))

        if fnCalls.isEmpty then ZIO.succeed(resp)
        else
          ZIO
            .foreachPar(fnCalls) { (part, fnCall) =>
              for
                name          <- ZIO.fromOption(fnCall.name).orElseFail(FunctionCallException.MissingFunctionName)
                fnRespContent <- processFnCall(name, fnCall)
              yield {
                // echo back the function call with its thought signature (if present)
                val modelFnContent = GoogleCloudAiplatformV1Content(
                  parts = Chunk(
                    GoogleCloudAiplatformV1Part(
                      functionCall = Some(fnCall),
                      thoughtSignature = part.thoughtSignature,
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
