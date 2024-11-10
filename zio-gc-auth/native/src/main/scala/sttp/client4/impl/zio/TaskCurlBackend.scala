package sttp.client4.impl.zio

import scala.collection.immutable.Seq
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.scalanative.libc.stdio.{FILE, fclose, fopen}
import scala.scalanative.libc.stdlib.*
import scala.scalanative.unsafe.{Ptr, *}
import scala.scalanative.unsigned.*

import _root_.zio.{Task, ZIO}
import sttp.capabilities.Effect
import sttp.client4.*
import sttp.client4.curl.AbstractCurlBackend
import sttp.client4.curl.internal.*
import sttp.client4.curl.internal.CurlApi.*
import sttp.client4.curl.internal.CurlCode.CurlCode
import sttp.client4.curl.internal.CurlInfo.*
import sttp.client4.curl.internal.CurlOption.{Header as _, *}
import sttp.client4.internal.*
import sttp.client4.ws.{GotAWebSocketException, NotAWebSocketException}
import sttp.model.*
import sttp.monad.MonadError

// copied from sttp.client4.curl.AbstractCurlBackend
// temprorary to fix IllegalStateException("Zone {this} is already closed.") due to multiple calls on zone.close()
// scalafix:off
abstract class TaskCurlBackend(verbose: Boolean) extends GenericBackend[Task, Any] {
  override implicit val monad: MonadError[Task] = new RIOMonadAsyncError[Any]

  /** Given a [[CurlHandle]], perform the request and return a [[CurlCode]]. */
  protected def performCurl(c: CurlHandle): Task[CurlCode] = ZIO.attemptBlocking(c.perform)

  /**
   * Same as [[performCurl]], but also checks and throws runtime exceptions on
   * bad [[CurlCode]]s.
   */
  private final def perform(c: CurlHandle) = performCurl(c).flatMap(lift)

  type R = Any & Effect[Task]

  override def close(): Task[Unit] = ZIO.succeed(())

  /** A request-specific context, with allocated zones and headers. */
  private class Context() extends AutoCloseable {
    implicit val zone: Zone = Zone.open()
    private val headers     = ArrayBuffer[CurlList]()

    /**
     * Create a new Headers list that gets cleaned up when the context is
     * destroyed.
     */
    def transformHeaders(reqHeaders: Iterable[Header]): CurlList = {
      val h = reqHeaders
        .map(header => s"${header.name}: ${header.value}")
        .foldLeft(new CurlList(null)) { case (acc, h) =>
          new CurlList(acc.ptr.append(h))
        }
      headers += h
      h
    }

    override def close() = {
      zone.close()
      headers.foreach(l => if (l.ptr != null) l.ptr.free())
    }
  }

  private object Context {

    /**
     * Create a new context and evaluates the body with it. Closes the context
     * at the end.
     */
    def evaluateUsing[T](body: Context => Task[T]): Task[T] =
      ZIO.scoped(
        ZIO.fromAutoCloseable(ZIO.attempt(new Context())).flatMap(body(_))
      )
  }

  override def send[T](request: GenericRequest[T, R]): Task[Response[T]] =
    adjustExceptions(request) {
      def perform(implicit ctx: Context): Task[Response[T]] = {
        implicit val z = ctx.zone
        val curl       = CurlApi.init
        if (verbose) {
          curl.option(Verbose, parameter = true)
        }

        if (request.tags.nonEmpty) {
          ZIO.fail(new UnsupportedOperationException("Tags are not supported"))
        } else {
          val reqHeaders = request.headers
          if (reqHeaders.nonEmpty) {
            reqHeaders.find(_.name == "Accept-Encoding").foreach(h => curl.option(AcceptEncoding, h.value))
            val headers = request.body match {
              case _: MultipartBody[?] =>
                ctx.transformHeaders(
                  reqHeaders :+ Header.contentType(MediaType.MultipartFormData)
                )
              case _ =>
                ctx.transformHeaders(reqHeaders)
            }
            curl.option(HttpHeader, headers.ptr)
          }

          val spaces = responseSpace
          FileHelpers.getFilePath(request.response.delegate) match {
            case Some(file) => handleFile(request, curl, file, spaces)
            case None       => handleBase(request, curl, spaces)
          }
        }
      }

      Context.evaluateUsing(perform(_))
    }

  private def adjustExceptions[T](request: GenericRequest[?, ?])(t: => Task[T]): Task[T] =
    SttpClientException.adjustExceptions(monad)(t)(
      SttpClientException.defaultExceptionToSttpClientException(request, _)
    )

  private def handleBase[T](request: GenericRequest[T, R], curl: CurlHandle, spaces: CurlSpaces)(implicit
    ctx: Context
  ) = {
    implicit val z = ctx.zone
    curl.option(WriteFunction, AbstractCurlBackend.wdFunc)
    curl.option(WriteData, spaces.bodyResp)
    curl.option(TimeoutMs, request.options.readTimeout.toMillis)
    curl.option(HeaderData, spaces.headersResp)
    curl.option(Url, request.uri.toString)
    setMethod(curl, request.method)
    setRequestBody(curl, request.body, request.method)
    perform(curl).flatMap { _ =>
      curl.info(ResponseCode, spaces.httpCode)
      val responseBody     = fromCString((!spaces.bodyResp)._1)
      val responseHeaders_ = parseHeaders(fromCString((!spaces.headersResp)._1))
      val httpCode         = StatusCode((!spaces.httpCode).toInt)
      free((!spaces.bodyResp)._1)
      free((!spaces.headersResp)._1)
      free(spaces.bodyResp.asInstanceOf[Ptr[CSignedChar]])
      free(spaces.headersResp.asInstanceOf[Ptr[CSignedChar]])
      free(spaces.httpCode.asInstanceOf[Ptr[CSignedChar]])
      curl.cleanup()

      val (statusText, responseHeaders) =
        if (responseHeaders_.nonEmpty) (responseHeaders_.head.name.split(" ").last, responseHeaders_.tail)
        else ("", Seq.empty)
      val responseMetadata = ResponseMetadata(httpCode, statusText, responseHeaders)

      bodyFromResponseAs(request.response, responseMetadata, Left(responseBody)).map { b =>
        Response[T](
          body = b,
          code = httpCode,
          statusText = statusText,
          headers = responseHeaders,
          history = Nil,
          request = request.onlyMetadata,
        )
      }
    }
  }

  private def handleFile[T](request: GenericRequest[T, R], curl: CurlHandle, file: SttpFile, spaces: CurlSpaces)(
    implicit ctx: Context
  ) = {
    implicit val z               = ctx.zone
    val outputPath               = file.toPath.toString
    val outputFilePtr: Ptr[FILE] = fopen(toCString(outputPath), toCString("wb"))
    curl.option(WriteData, outputFilePtr)
    curl.option(Url, request.uri.toString)
    setMethod(curl, request.method)
    setRequestBody(curl, request.body, request.method)
    perform(curl).flatMap { _ =>
      curl.info(ResponseCode, spaces.httpCode)
      val httpCode = StatusCode((!spaces.httpCode).toInt)
      free(spaces.httpCode.asInstanceOf[Ptr[CSignedChar]])
      fclose(outputFilePtr)
      curl.cleanup()
      val responseMetadata = ResponseMetadata(httpCode, "", List.empty)
      bodyFromResponseAs(request.response, responseMetadata, Left(outputPath)).map { b =>
        Response[T](
          body = b,
          code = httpCode,
          statusText = "",
          headers = List(Header.contentLength(file.size)),
          history = Nil,
          request = request.onlyMetadata,
        )
      }
    }
  }

  private def setMethod(handle: CurlHandle, method: Method)(implicit z: Zone): Task[CurlCode] = {
    val m = method match {
      case Method.GET     => handle.option(HttpGet, true)
      case Method.HEAD    => handle.option(Head, true)
      case Method.POST    => handle.option(Post, true)
      case Method.PUT     => handle.option(CustomRequest, "PUT")
      case Method.DELETE  => handle.option(CustomRequest, "DELETE")
      case Method.OPTIONS => handle.option(RtspRequest, true)
      case Method.PATCH   => handle.option(CustomRequest, "PATCH")
      case Method.CONNECT => handle.option(ConnectOnly, true)
      case Method.TRACE   => handle.option(CustomRequest, "TRACE")
      case Method(m)      => handle.option(CustomRequest, m)
    }
    lift(m)
  }

  private def setRequestBody(curl: CurlHandle, body: GenericRequestBody[R], method: Method)(implicit
    ctx: Context
  ): Task[CurlCode] = {
    implicit val z = ctx.zone
    body match { // todo: assign to monad object
      case b: BasicBodyPart =>
        val str = basicBodyToString(b)
        lift(curl.option(PostFields, toCString(str)))
      case m: MultipartBody[_] =>
        val mime = curl.mime
        m.parts.foreach { case p @ Part(name, partBody, _, headers) =>
          val part = mime.addPart()
          part.withName(name)
          val str = basicBodyToString(partBody)
          part.withData(str)
          p.fileName.foreach(part.withFileName(_))
          p.contentType.foreach(part.withMimeType(_))

          val otherHeaders = headers.filterNot(_.is(HeaderNames.ContentType))
          if (otherHeaders.nonEmpty) {
            val curlList = ctx.transformHeaders(otherHeaders)
            part.withHeaders(curlList.ptr)
          }
        }
        lift(curl.option(Mimepost, mime))
      case StreamBody(_) =>
        ZIO.fail(new IllegalStateException("CurlBackend does not support stream request body"))
      case NoBody =>
        // POST with empty body might wait for the input on stdin
        if (method.is(Method.POST)) lift(curl.option(PostFields, c""))
        else ZIO.succeed(CurlCode.Ok)
    }
  }

  private def basicBodyToString(body: BodyPart[?]): String =
    body match {
      case StringBody(b, _, _)   => b
      case ByteArrayBody(b, _)   => new String(b)
      case ByteBufferBody(b, _)  => new String(b.array)
      case InputStreamBody(b, _) => Source.fromInputStream(b).mkString
      case FileBody(f, _)        => Source.fromFile(f.toFile).mkString
      case _                     => throw new IllegalArgumentException(s"Unsupported body: $body")
    }

  private def responseSpace: CurlSpaces = {
    val bodyResp = malloc(sizeof[CurlFetch]).asInstanceOf[Ptr[CurlFetch]]
    (!bodyResp)._1 = calloc(4096.toUInt, sizeof[CChar])
    (!bodyResp)._2 = 0.toUInt
    val headersResp = malloc(sizeof[CurlFetch]).asInstanceOf[Ptr[CurlFetch]]
    (!headersResp)._1 = calloc(4096.toUInt, sizeof[CChar])
    (!headersResp)._2 = 0.toUInt
    val httpCode = malloc(sizeof[Long]).asInstanceOf[Ptr[Long]]
    new CurlSpaces(bodyResp, headersResp, httpCode)
  }

  private def parseHeaders(str: String): Seq[Header] = {
    val array = str
      .split("\n")
      .filter(_.trim.length > 0)
    array.toSeq.map { line =>
      val split = line.split(":", 2)
      if (split.size == 2)
        Header(split(0).trim, split(1).trim)
      else
        Header(split(0).trim, "")
    }
  }

  private lazy val bodyFromResponseAs: BodyFromResponseAs[Task, String, Nothing, Nothing] =
    new BodyFromResponseAs[Task, String, Nothing, Nothing] {
      override protected def withReplayableBody(
        response: String,
        replayableBody: Either[Array[Byte], SttpFile],
      ): Task[String] = ZIO.succeed(response)

      override protected def regularIgnore(response: String): Task[Unit] = ZIO.unit

      override protected def regularAsByteArray(response: String): Task[Array[Byte]] = ZIO.succeed(response.getBytes)

      override protected def regularAsFile(response: String, file: SttpFile): Task[SttpFile] =
        ZIO.succeed(file)

      override protected def regularAsStream(response: String): Task[(Nothing, () => Task[Unit])] =
        throw new IllegalStateException("CurlBackend does not support streaming responses")

      override protected def handleWS[T](
        responseAs: GenericWebSocketResponseAs[T, ?],
        meta: ResponseMetadata,
        ws: Nothing,
      ): Task[T] = ws

      override protected def cleanupWhenNotAWebSocket(
        response: String,
        e: NotAWebSocketException,
      ): Task[Unit] = ZIO.unit

      override protected def cleanupWhenGotWebSocket(response: Nothing, e: GotAWebSocketException): Task[Unit] =
        response
    }

  private def lift(code: CurlCode): Task[CurlCode] =
    code match {
      case CurlCode.Ok => ZIO.succeed(code)
      case _           => ZIO.fail(new RuntimeException(s"Command failed with status $code"))
    }
}
// scalafix:on
