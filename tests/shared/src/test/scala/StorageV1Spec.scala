package com.anymindgroup.gcp.storage.v1

import zio.test.*, zio.*, zio.Console.{printLine, printError}
import com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import sttp.model.Header
import sttp.model.MediaType
import java.nio.charset.StandardCharsets
import sttp.client4.Response

object StorageV1Spec extends ZIOSpecDefault:
  def spec = suite("StorageV1Spec")(
    test("create and delete object") {
      for {
        backend <- defaultAccessTokenBackend()
        objName  = "manual_test/zio_gcp_test.txt"
        bucket  <- ZIO.systemWith(_.env("GCP_TEST_STORAGE_BUCKET")).someOrFail("GCP_TEST_STORAGE_BUCKET not set")
        body     = "this is a test body".getBytes(StandardCharsets.UTF_8)
        req = resources.Objects
                .insert(bucket = bucket, name = Some(objName))
                .headers(Header.contentType(MediaType.TextPlain), Header.contentLength(body.length))
                .body(body)
        res <- backend.send(req)
        _ <- res.body match
               case Left(err)   => ZIO.dieMessage(s"Failure on creating object: $err")
               case Right(body) => printLine(s"Object created with id: ${body.id.getOrElse("")}")
        _ <- backend.send(resources.Objects.delete(`object` = objName, bucket = bucket)).flatMap {
               case Response(Right(body), _, _, _, _, _) => printLine(s"Object deleted: $body")
               case Response(Left(err), _, _, _, _, _)   => printError(s"Failure on deleting object: $err")
             }
      } yield assertCompletes
    }
  ) @@ TestAspect.withLiveSystem @@ TestAspect.ifEnvNotSet("CI")
