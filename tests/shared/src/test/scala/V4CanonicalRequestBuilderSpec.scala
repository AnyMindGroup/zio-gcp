package com.anymindgroup.gcp.storage

import java.time.Instant

import sttp.model.Method

import zio.ZIO
import zio.test.{ZIOSpecDefault, *}

object V4CanonicalRequestBuilderSpec extends ZIOSpecDefault:
  override def spec: Spec[Any, Any] = suite("SigningServiceSpec")(
    test("toCanonicalRequest") {
      val testTimestamp  = Instant.parse("2019-12-01T19:08:59Z")
      val testSvcAccount = "test@gcp.iam.gserviceaccount.com"

      for {
        builder <- ZIO.succeed(V4CanonicalRequestBuilder())
        req <- ZIO.fromEither(
                 builder.toCanonicalRequest(
                   method = Method.PUT,
                   timestamp = testTimestamp,
                   resourcePath = "test/image.png",
                   contentType = None,
                   bucket = "test-bucket",
                   serviceAccountEmail = testSvcAccount,
                   signAlgorithm = V4SignAlgorithm.`GOOG4-RSA-SHA256`,
                   expiresInSeconds = V4SignatureExpiration.inSeconds(900),
                 )
               )
        _ <-
          assertTrue(
            req.payloadPlain ==
              """|PUT
                 |/test-bucket/test/image.png
                 |X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test%40gcp.iam.gserviceaccount.com%2F20191201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20191201T190859Z&X-Goog-Expires=900&X-Goog-SignedHeaders=host
                 |host:storage.googleapis.com
                 |
                 |host
                 |UNSIGNED-PAYLOAD""".stripMargin
          )
        _ <- assertTrue(
               req.stringToSign == """|GOOG4-RSA-SHA256
                                      |20191201T190859Z
                                      |20191201/auto/storage/goog4_request
                                      |d62c091b215aaae1a3ae90601dfae7623839ff5aab8980860bcea13748895465""".stripMargin
             )
      } yield assertCompletes
    }
  )
