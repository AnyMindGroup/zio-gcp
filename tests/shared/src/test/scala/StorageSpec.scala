package com.anymindgroup.gcp.storage
package v1

import java.nio.charset.StandardCharsets

import com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import sttp.client4.{Response, quick}
import sttp.model.{Header, MediaType, Method}

import zio.*
import zio.Console.{printError, printLine}
import zio.test.*

// test for manual execution
object StorageSpec extends ZIOSpecDefault:
  def spec = suite("StorageSpec")(
    test("insert object, create signed url, delete object") {
      for {
        (bucket, svcAcc) <- ZIO.fromEither:
                              for
                                b <- sys.env.get("GCP_TEST_STORAGE_BUCKET").toRight("Missing GCP_TEST_STORAGE_BUCKET")
                                s <- sys.env.get("GCP_TEST_SERVICE_ACCOUNT").toRight("Missing GCP_TEST_SERVICE_ACCOUNT")
                              yield (b, s)
        backend <- defaultAccessTokenBackend()
        objPath  = List("manual_test", """zio_gcp_test_?=!#$&'()*+,:;@[]".txt""")
        body     = "this is a test body".getBytes(StandardCharsets.UTF_8)

        // insert object
        _ <- resources.Objects
               .insert(bucket = bucket, name = Some(objPath.mkString("/")))
               .headers(Header.contentType(MediaType.TextPlain), Header.contentLength(body.length))
               .body(body)
               .send(backend)
               .flatMap:
                 _.body match
                   case Left(err)   => ZIO.dieMessage(s"❌ Failure on creating object: $err")
                   case Right(body) => printLine(s"✅ Object created with id: ${body.id.getOrElse("")}")

        // create signed url
        signedUrl <- V4SignUrlRequestBuilder
                       .create()
                       .signUrlRequest(
                         bucket = bucket,
                         resourcePath = objPath,
                         contentType = None,
                         method = Method.GET,
                         serviceAccountEmail = svcAcc,
                         signAlgorithm = V4SignAlgorithm.`GOOG4-RSA-SHA256`,
                         expiresInSeconds = V4SignatureExpiration.inSeconds(300),
                       )
                       .flatMap(_.send(backend).flatMap(r => ZIO.fromEither(r.body)))
        _ <- printLine(s"✅ Created signed url: $signedUrl")

        // test signed url
        _ <- quick.backend.send(quick.basicRequest.get(signedUrl)) match
               case res if res.code.isSuccess => printLine("✅ Signed url ok")
               case res                       => printError(s"❌ Signed url failure: ${res.code.code} / ${res.body}")

        // delete object
        _ <- backend.send(resources.Objects.delete(`object` = objPath.mkString("/"), bucket = bucket)).flatMap {
               case Response(Right(body), _, _, _, _, _) => printLine(s"✅ Object deleted: $body")
               case Response(Left(err), _, _, _, _, _)   => printError(s"❌ Failure on deleting object: $err")
             }
      } yield assertCompletes
    }
  ) @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@ TestAspect.ifEnvNotSet("CI")
