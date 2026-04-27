package com.anymindgroup.http

import java.io.InputStream

import scala.scalanative.libc.stdlib.*
import scala.scalanative.libc.string.memcpy
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import curl.all.*
import sttp.capabilities.Effect
import sttp.capabilities.zio.ZioStreams
import sttp.client4.*
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.internal.{BodyFromResponseAs, SttpFile}
import sttp.client4.ws.{GotAWebSocketException, NotAWebSocketException}
import sttp.model.*
import sttp.monad.MonadError

import zio.*
import zio.Ref.Synchronized
import zio.stream.{Take, ZStream}

// scalafix:off DisableSyntax.null
// scalafix:off DisableSyntax.throw
// scalafix:off DisableSyntax.return

// Dynamic grow buffer: (data_ptr: CString, filled_size: CSize)
private type FetchBuf = CStruct2[CString, CSize]

private final case class PendingRequest(
  easy: Ptr[CURL],
  zone: Zone,
  slist: Ptr[curl_slist],
  bodyBuf: Ptr[FetchBuf],
  headerBuf: Ptr[FetchBuf],
  headerPromise: Promise[Throwable, ResponseMetadata],
  bodyQueue: Queue[Take[Throwable, Byte]],
)

private final case class ActiveRequest(
  req: PendingRequest,
  headerCode: Option[Long],
) {
  def headersDone: Boolean = headerCode.exists(_ >= 200L)
}

/**
 * Asynchronous sttp backend for Scala Native backed by libcurl multi handles
 * and ZIO. A single background fiber owns the curl event loop; `send` enqueues
 * requests and awaits their results via Promise/Queue.
 */
class CurlMultiZioBackend private (
  verbose: Boolean,
  multi: Ptr[CURLM],
  workQueue: Queue[PendingRequest],
) extends StreamBackend[Task, ZioStreams] {
  import CurlMultiZioBackend.curlBackendsCount

  override val monad: MonadError[Task] = new RIOMonadAsyncError[Any]()

  given MonadError[Task] = monad

  override def close(): Task[Unit] =
    curlBackendsCount.getAndUpdateZIO { i =>
      val remaining = i - 1

      ZIO.attempt {
        curl_multi_cleanup(multi)
        if remaining <= 0 then curl_global_cleanup()
      }.as(remaining)
    }.unit

  override def send[T](request: GenericRequest[T, ZioStreams & Effect[Task]]): Task[Response[T]] =
    for
      zone                              <- ZIO.attempt(Zone.open())
      (easy, bodyBuf, headerBuf, slist) <- ZIO.attempt {
                                             val easy      = initEasy()(using zone)
                                             val bodyBuf   = mallocFetchBuf()
                                             val headerBuf = mallocFetchBuf()
                                             val slist     = configureEasy(easy, request, bodyBuf, headerBuf)(using zone)
                                             (easy, bodyBuf, headerBuf, slist)
                                           }.tapError(_ => ZIO.attempt(zone.close()).ignore)
      headerPromise <- Promise.make[Throwable, ResponseMetadata]
      bodyQueue     <- Queue.unbounded[Take[Throwable, Byte]]
      _             <- workQueue.offer(PendingRequest(easy, zone, slist, bodyBuf, headerBuf, headerPromise, bodyQueue))
      meta          <- headerPromise.await
      stream         = ZStream.fromQueue(bodyQueue).flattenTake
      decoded       <- bodyDecoder(request.response, meta, Left(stream))
    yield Response(decoded, meta.code, meta.statusText, meta.headers, Nil, request.onlyMetadata)

  // ── Background event loop ─────────────────────────────────────────

  private[http] def runLoop: Task[Unit] =
    ZIO.suspendSucceed {
      val active = scala.collection.mutable.Map.empty[Ptr[CURL], ActiveRequest]

      ZIO
        .whileLoop(true) {
          for
            // Block on the queue when idle; drain all pending when already active
            newPendings <-
              if active.isEmpty
              then workQueue.takeBetween(1, Int.MaxValue)
              else workQueue.takeAll

            running <- ZIO.attempt:
                         newPendings.foreach: p =>
                           curl_multi_add_handle(multi, p.easy)
                           active.update(p.easy, ActiveRequest(p, None))

                         Zone:
                           val runningHandles = alloc[CInt](1)
                           val mc             = curl_multi_perform(multi, runningHandles)
                           if mc != CURLMcode.CURLM_OK then
                             throw new RuntimeException(s"curl_multi_perform failed: ${mc.int}")
                           !runningHandles

            // Drain body buffers and detect header arrival for every active handle
            _ <- ZIO.foreachDiscard(active) { (easy, ar) =>
                   for
                     _ <- ZIO.suspendSucceed {
                            val bodyChunk = drainBuf(ar.req.bodyBuf)
                            if bodyChunk.nonEmpty then ar.req.bodyQueue.offer(Take.chunk(bodyChunk)) else ZIO.unit
                          }
                     _ <-
                       if ar.headersDone then ZIO.succeed(ar)
                       else {
                         Zone {
                           val httpCode = alloc[Long](1)
                           val res      = curl_easy_getinfo(easy, CURLINFO.CURLINFO_RESPONSE_CODE, httpCode)
                           (res, !httpCode)
                         } match {
                           case (CURLcode.CURLE_OK, code) if code >= 200L =>
                             val (statusText, headers) =
                               parseHeadersAndStatus(fromCString((!ar.req.headerBuf)._1))
                             ar.req.headerPromise
                               .succeed(ResponseMetadata(StatusCode(code.toInt), statusText, headers))
                               .as(active.update(easy, ar.copy(headerCode = Some(code))))
                           case (CURLcode.CURLE_OK, code) =>
                             ZIO.succeed(active.update(easy, ar.copy(headerCode = Some(code))))
                           case (other, _) =>
                             ZIO.succeed(active.update(easy, ar.copy(headerCode = None)))
                         }
                       }
                   yield ()
                 }

            // Signal completion or failure, then free native resources
            _ <- ZIO.foreachDiscard(
                   // Collect handles that curl has finished transferring
                   Zone {
                     val msgsLeft = alloc[CInt](1)
                     val done     = scala.collection.mutable.Map.empty[Ptr[CURL], Int]
                     var msg      = curl_multi_info_read(multi, msgsLeft)
                     while msg != null do
                       if (!msg).msg == CURLMSG.CURLMSG_DONE then
                         done.update((!msg).easy_handle, (!msg).data.result.int)
                       msg = curl_multi_info_read(multi, msgsLeft)
                     done
                   }
                 ) { (easy, resultCode) =>
                   active.remove(easy) match
                     case None     => ZIO.unit
                     case Some(ar) =>
                       val finalChunk = drainBuf(ar.req.bodyBuf)
                       for
                         _ <- ZIO.when(finalChunk.nonEmpty)(ar.req.bodyQueue.offer(Take.chunk(finalChunk)))
                         _ <-
                           if resultCode == 0 then ar.req.bodyQueue.offer(Take.end)
                           else
                             val codeName =
                               CURLcode.getName(CURLcode.define(resultCode.toLong)).getOrElse(s"CURLcode_$resultCode")
                             ar.req.bodyQueue.offer(
                               Take.fail(new RuntimeException(s"curl transfer failed: $codeName"))
                             )
                         _ <- ZIO.unless(ar.headersDone)(
                                ar.req.headerPromise.fail(
                                  new RuntimeException(
                                    if resultCode == 0 then s"Transfer completed without headers: '${ar.headerCode}'"
                                    else
                                      CURLcode
                                        .getName(CURLcode.define(resultCode.toLong))
                                        .getOrElse(s"CURLcode_$resultCode")
                                  )
                                )
                              )
                         _ <- ZIO.attempt(freeRequest(ar.req))
                       yield ()
                 }

            // Short poll to avoid busy-waiting while transfers are still in flight
            _ <-
              if running > 0 && active.nonEmpty then
                ZIO.attempt {
                  val pc = curl_multi_poll(
                    multi,
                    null.asInstanceOf[Ptr[curl_waitfd]],
                    0.toUInt,
                    100,
                    null.asInstanceOf[Ptr[CInt]],
                  )
                  if pc != CURLMcode.CURLM_OK then throw new RuntimeException(s"curl_multi_poll failed: ${pc.int}")
                }
              else ZIO.unit
          yield ()
        }(_ => ())
    }

  // ── Body decoder ──────────────────────────────────────────────────

  private lazy val bodyDecoder
    : BodyFromResponseAs[Task, ZStream[Any, Throwable, Byte], Nothing, ZStream[Any, Throwable, Byte]] =
    new BodyFromResponseAs[Task, ZStream[Any, Throwable, Byte], Nothing, ZStream[Any, Throwable, Byte]] {

      override protected def withReplayableBody(
        response: ZStream[Any, Throwable, Byte],
        replayableBody: Either[Array[Byte], SttpFile],
      ): Task[ZStream[Any, Throwable, Byte]] =
        replayableBody match {
          case Left(bytes) => ZIO.succeed(ZStream.fromChunk(Chunk.ByteArray(bytes, 0, bytes.length)))
          case Right(_)    => ZIO.fail(new UnsupportedOperationException("File responses not supported on Native"))
        }

      override protected def regularIgnore(response: ZStream[Any, Throwable, Byte]): Task[Unit] =
        response.runDrain

      override protected def regularAsByteArray(response: ZStream[Any, Throwable, Byte]): Task[Array[Byte]] =
        response.runCollect.map(_.toArray)

      override protected def regularAsFile(
        response: ZStream[Any, Throwable, Byte],
        file: SttpFile,
      ): Task[SttpFile] =
        ZIO.fail(new UnsupportedOperationException("File responses not supported on Native"))

      override protected def regularAsStream(
        response: ZStream[Any, Throwable, Byte]
      ): Task[(ZStream[Any, Throwable, Byte], () => Task[Unit])] =
        ZIO.succeed((response, () => ZIO.unit))

      override protected def regularAsInputStream(response: ZStream[Any, Throwable, Byte]): Task[InputStream] =
        ZIO.scoped(response.toInputStream)

      override protected def handleWS[T](
        responseAs: GenericWebSocketResponseAs[T, ?],
        meta: ResponseMetadata,
        ws: Nothing,
      ): Task[T] = ws

      override protected def cleanupWhenNotAWebSocket(
        response: ZStream[Any, Throwable, Byte],
        e: NotAWebSocketException,
      ): Task[Unit] = ZIO.unit

      override protected def cleanupWhenGotWebSocket(response: Nothing, e: GotAWebSocketException): Task[Unit] =
        response
    }

  // ── Native helpers ────────────────────────────────────────────────

  private def withZone[A](f: Zone ?=> A): Task[A] = ZIO.attempt {
    val z = Zone.open()
    try f(using z)
    finally z.close()
  }

  private def mallocFetchBuf(): Ptr[FetchBuf] = {
    val buf = malloc(sizeof[FetchBuf]).asInstanceOf[Ptr[FetchBuf]]
    (!buf)._1 = calloc(4096.toUInt, sizeof[CChar])
    (!buf)._2 = 0.toUInt
    buf
  }

  private def drainBuf(buf: Ptr[FetchBuf]): Chunk[Byte] = {
    val len = (!buf)._2.toInt
    if len == 0 then Chunk.empty
    else
      val bytes = Array.ofDim[Byte](len)
      val src   = (!buf)._1
      var i     = 0
      while i < len do { bytes(i) = !(src + i); i += 1 }
      (!buf)._2 = 0.toUInt
      Chunk.fromArray(bytes)
  }

  private def initEasy()(using Zone): Ptr[CURL] = {
    val easy = curl_easy_init()
    if (easy == null) throw new RuntimeException("curl_easy_init failed")
    if (verbose) curl_easy_setopt(easy, CURLoption.CURLOPT_VERBOSE, 1L)
    curl_easy_setopt(easy, CURLoption.CURLOPT_NOSIGNAL, 1L)
    curl_easy_setopt(easy, CURLoption.CURLOPT_FOLLOWLOCATION, 1L)
    easy
  }

  private def configureEasy[T](
    easy: Ptr[CURL],
    request: GenericRequest[T, ZioStreams & Effect[Task]],
    bodyBuf: Ptr[FetchBuf],
    headerBuf: Ptr[FetchBuf],
  )(using Zone): Ptr[curl_slist] = {
    curl_easy_setopt(easy, CURLoption.CURLOPT_WRITEFUNCTION, CurlMultiZioBackend.writeCallback)
    curl_easy_setopt(easy, CURLoption.CURLOPT_WRITEDATA, bodyBuf)
    curl_easy_setopt(easy, CURLoption.CURLOPT_HEADERFUNCTION, CurlMultiZioBackend.writeCallback)
    curl_easy_setopt(easy, CURLoption.CURLOPT_HEADERDATA, headerBuf)
    curl_easy_setopt(easy, CURLoption.CURLOPT_URL, toCString(request.uri.toString))
    curl_easy_setopt(easy, CURLoption.CURLOPT_TIMEOUT_MS, request.options.readTimeout.toMillis)

    request.method match {
      case Method.GET  => curl_easy_setopt(easy, CURLoption.CURLOPT_HTTPGET, 1L)
      case Method.HEAD => curl_easy_setopt(easy, CURLoption.CURLOPT_NOBODY, 1L)
      case Method.POST => curl_easy_setopt(easy, CURLoption.CURLOPT_POST, 1L)
      case Method(m)   => curl_easy_setopt(easy, CURLoption.CURLOPT_CUSTOMREQUEST, toCString(m))
    }

    val reqHeaders = collection.mutable.ListBuffer[Header](request.headers*)
    request.body match {
      case _: MultipartBody[?] => reqHeaders += Header.contentType(MediaType.MultipartFormData)
      case _                   =>
    }
    reqHeaders += Header("Expect", "")

    var slist: Ptr[curl_slist] = null.asInstanceOf[Ptr[curl_slist]]
    reqHeaders.foreach { h =>
      slist = curl_slist_append(slist, toCString(s"${h.name}: ${h.value}"))
    }
    if (slist != null) curl_easy_setopt(easy, CURLoption.CURLOPT_HTTPHEADER, slist)

    setRequestBody(easy, request.body)
    slist
  }

  private def setRequestBody(easy: Ptr[CURL], body: GenericRequestBody[ZioStreams & Effect[Task]])(using Zone): Unit =
    body match {
      case b: StringBody =>
        curl_easy_setopt(easy, CURLoption.CURLOPT_POSTFIELDSIZE, b.s.length.toLong)
        curl_easy_setopt(easy, CURLoption.CURLOPT_COPYPOSTFIELDS, toCString(b.s))
      case b: ByteArrayBody =>
        val tmp = malloc(b.b.length.toUInt).asInstanceOf[CString]
        var i   = 0; while (i < b.b.length) { !(tmp + i) = b.b(i); i += 1 }
        curl_easy_setopt(easy, CURLoption.CURLOPT_POSTFIELDSIZE, b.b.length.toLong)
        curl_easy_setopt(easy, CURLoption.CURLOPT_COPYPOSTFIELDS, tmp)
        free(tmp.asInstanceOf[Ptr[Byte]])
      case _ => ()
    }

  private def freeRequest(req: PendingRequest): Unit = {
    curl_multi_remove_handle(multi, req.easy)
    curl_easy_cleanup(req.easy)
    free((!req.bodyBuf)._1)
    free(req.bodyBuf.asInstanceOf[Ptr[Byte]])
    free((!req.headerBuf)._1)
    free(req.headerBuf.asInstanceOf[Ptr[Byte]])
    if req.slist != null then curl_slist_free_all(req.slist)
    req.zone.close()
  }

  private def parseHeadersAndStatus(raw: String): (String, List[Header]) = {
    val lines = raw.split("\n").map(_.trim).filter(_.nonEmpty)
    if (lines.isEmpty) return ("", Nil)
    // Skip any 1xx intermediate responses by finding the last HTTP/ status line
    val lastHttpIdx = lines.lastIndexWhere(_.startsWith("HTTP/"))
    val respLines   = if lastHttpIdx >= 0 then lines.drop(lastHttpIdx) else lines
    val statusLine  = respLines.head
    val statusText  = {
      val i = statusLine.indexOf(' ')
      if (i < 0) ""
      else {
        val j = statusLine.indexOf(' ', i + 1)
        if (j < 0) "" else statusLine.substring(j + 1).trim
      }
    }
    val headers = respLines.tail.flatMap { line =>
      val colon = line.indexOf(':')
      if (colon > 0) Some(Header(line.substring(0, colon).trim, line.substring(colon + 1).trim))
      else None
    }.toList
    (statusText, headers)
  }
}

object CurlMultiZioBackend {

  private val writeCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[FetchBuf], CSize] = {
    (ptr: Ptr[Byte], size: CSize, nmemb: CSize, data: Ptr[FetchBuf]) =>
      val bytes      = size * nmemb
      val currentLen = (!data)._2
      val newLen     = currentLen + bytes
      val newPtr     = realloc((!data)._1.asInstanceOf[Ptr[Byte]], newLen + 1.toUInt).asInstanceOf[CString]
      if newPtr == null then 0.toUSize
      else
        (!data)._1 = newPtr
        memcpy((!data)._1 + currentLen, ptr, bytes)
        (!data)._2 = newLen
        !((!data)._1 + newLen) = 0.toByte
        size * nmemb
  }

  def layer(verbose: Boolean = false): ZLayer[Any, Throwable, CurlMultiZioBackend] =
    ZLayer.scoped(scoped(verbose))

  private val curlBackendsCount: Synchronized[Int] = zio.Unsafe.unsafe(Ref.Synchronized.unsafe.make(0))

  def scoped(verbose: Boolean = false): ZIO[Scope, Throwable, CurlMultiZioBackend] =
    for
      _ <- curlBackendsCount.getAndUpdateZIO { i =>
             val inc = i + 1
             if inc <= 1 then ZIO.attempt(curl_global_init(3)).as(inc) else ZIO.succeed(inc)
           }
      workQueue <- Queue.unbounded[PendingRequest]
      backend   <- ZIO.acquireRelease(
                   ZIO.attempt {
                     val multi = curl_multi_init()
                     if multi == null then throw new RuntimeException("curl_multi_init failed")
                     CurlMultiZioBackend(
                       verbose = verbose,
                       multi = multi,
                       workQueue = workQueue,
                     )
                   }
                 )(_.close().ignore)
      _ <- backend.runLoop.forkScoped
    yield backend
}
