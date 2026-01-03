package com.anymindgroup.gcp.storage

import java.time.Instant

import sttp.model.{MediaType, Method}

import zio.ZIO
import zio.test.{ZIOSpecDefault, *}

object V4CanonicalRequestBuilderSpec extends ZIOSpecDefault:
  override def spec: Spec[Any, Any] = suite("V4CanonicalRequestBuilderSpec")(
    test("toCanonicalRequest (PUT, no content type)") {
      val testTimestamp  = Instant.parse("2019-12-01T19:08:59Z")
      val testSvcAccount = "test@gcp.iam.gserviceaccount.com"

      for
        builder <- ZIO.succeed(V4CanonicalRequestBuilder())
        req     <- ZIO.fromEither(
                 builder.toCanonicalRequest(
                   method = Method.PUT,
                   timestamp = testTimestamp,
                   resourcePath = List("test", "image.png"),
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
      yield assertCompletes
    },
    test("toCanonicalRequest (GET, with content type)") {
      val testTimestamp  = Instant.parse("2019-12-01T19:08:59Z")
      val testSvcAccount = "test@gcp.iam.gserviceaccount.com"

      for
        builder <- ZIO.succeed(V4CanonicalRequestBuilder())
        req     <- ZIO.fromEither(
                 builder.toCanonicalRequest(
                   method = Method.GET,
                   timestamp = testTimestamp,
                   resourcePath = List("test", "image.png"),
                   contentType = Some(MediaType.ImagePng),
                   bucket = "test-bucket",
                   serviceAccountEmail = testSvcAccount,
                   signAlgorithm = V4SignAlgorithm.`GOOG4-RSA-SHA256`,
                   expiresInSeconds = V4SignatureExpiration.inSeconds(900),
                 )
               )
        _ <-
          assertTrue(
            req.payloadPlain ==
              """|GET
                 |/test-bucket/test/image.png
                 |X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=test%40gcp.iam.gserviceaccount.com%2F20191201%2Fauto%2Fstorage%2Fgoog4_request&X-Goog-Date=20191201T190859Z&X-Goog-Expires=900&X-Goog-SignedHeaders=content-type%3Bhost
                 |content-type:image/png
                 |host:storage.googleapis.com
                 |
                 |content-type;host
                 |UNSIGNED-PAYLOAD""".stripMargin
          )
        _ <- assertTrue(
               req.stringToSign == """|GOOG4-RSA-SHA256
                                      |20191201T190859Z
                                      |20191201/auto/storage/goog4_request
                                      |012eb316298a5202f1b404bb5d0be0c6c270d1442eda75b055303df96b05c137""".stripMargin
             )
      yield assertCompletes
    },
    test("toCanonicalRequest (resource path is URL encoded)") {
      for
        builder <- ZIO.succeed(V4CanonicalRequestBuilder())
        req     <- ZIO.fromEither(
                 builder.toCanonicalRequest(
                   method = Method.GET,
                   timestamp = Instant.parse("2019-12-01T19:08:59Z"),
                   // all characters from the docs https://cloud.google.com/storage/docs/authentication/canonical-requests#about-resource-path
                   resourcePath = List("test", """?=!#$&'()*+,:;@[]"~ .png"""),
                   contentType = Some(MediaType.ImagePng),
                   bucket = "test-bucket",
                   serviceAccountEmail = "test@gcp.iam.gserviceaccount.com",
                   signAlgorithm = V4SignAlgorithm.`GOOG4-RSA-SHA256`,
                   expiresInSeconds = V4SignatureExpiration.inSeconds(900),
                 )
               )
        _ <-
          assertTrue(
            req.payloadPlain.linesIterator
              .drop(1)
              .next == """/test-bucket/test/%3F%3D%21%23%24%26%27%28%29%2A%2B%2C%3A%3B%40%5B%5D%22~%20.png"""
          )
      yield assertCompletes
    },
  )
