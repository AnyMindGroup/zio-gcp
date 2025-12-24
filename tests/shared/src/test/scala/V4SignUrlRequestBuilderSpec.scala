package com.anymindgroup.gcp.storage

import java.time.Instant

import sttp.model.Method

import zio.ZIO
import zio.test.{ZIOSpecDefault, *}

object V4SignUrlRequestBuilderSpec extends ZIOSpecDefault:

  val reqBuilder = V4CanonicalRequestBuilder()

  override def spec: Spec[Any, Any] = suite("V4SignUrlRequestBuilderSpec")(
    test("return signed url") {
      val testBucket        = "example-bucket"
      val testResource      = List("test", """cat_?=!#$&'()*+,:;@[]"~.jpeg""")
      val signatureResponse =
        "PszqKoIc7Q3TU22ouo9YWxtMk9jtKZBWMdqEwKb57+XlritZsYnaBiNhRaE5YvARf421zqS/M2kKPuPbYJc9c2GyEk66Y/J8o2QFpo65tKaFIvADvkBUiNg6IXSs3YL4udg+roLeMPi6r5NJqjp3Rf+7FT6xN8xImtw33DGjbffkp6BhUuHSt9USOCzbDOSmjTAiTuuo5eNUSbL6f5xZroKq07wTj3ETDDICV/QkB6VlxGffi1TVKF14dgrBuE0jwATsWyVBFFVXJ7pB+uUc8UDgZQzAVTjahJUFWAevg9+QgA2HQlc5a0u3Cs+/tJZjWnsx3hm0S8NJqGIOa8mO0w=="
      val expectedSignature =
        "3eccea2a821ced0dd3536da8ba8f585b1b4c93d8ed29905631da84c0a6f9efe5e5ae2b59b189da06236145a13962f0117f8db5cea4bf33690a3ee3db60973d7361b2124eba63f27ca36405a68eb9b4a68522f003be405488d83a2174acdd82f8b9d83eae82de30f8baaf9349aa3a7745ffbb153eb137cc489adc37dc31a36df7e4a7a06152e1d2b7d512382cdb0ce4a68d30224eeba8e5e35449b2fa7f9c59ae82aad3bc138f71130c320257f42407a565c467df8b54d5285d78760ac1b84d23c004ec5b254114555727ba41fae51cf140e0650cc05538da8495055807af83df90800d874257396b4bb70acfbfb496635a7b31de19b44bc349a8620e6bc98ed3"

      for {
        canonicalRequest <- ZIO.fromEither:
                              reqBuilder.toCanonicalRequest(
                                method = Method.PUT,
                                timestamp = Instant.parse("2018-10-26T18:13:09Z"),
                                resourcePath = testResource,
                                contentType = None,
                                bucket = testBucket,
                                serviceAccountEmail = "example@example-project.iam.gserviceaccount.com",
                                signAlgorithm = V4SignAlgorithm.`GOOG4-RSA-SHA256`,
                                expiresInSeconds = V4SignatureExpiration.inSeconds(900),
                              )
        expectedPath = "/example-bucket/test/cat_%3F%3D%21%23%24%26%27%28%29%2A%2B%2C%3A%3B%40%5B%5D%22~.jpeg"
        _           <- assertTrue(canonicalRequest.payloadPlain.linesIterator.drop(1).next == expectedPath)
        signedUrl   <- ZIO.fromEither:
                       V4SignUrlRequestBuilder
                         .toSignedUrl(
                           signatureResponse = signatureResponse,
                           canonicalQueryParams = canonicalRequest.canonicalQueryParams,
                           bucket = testBucket,
                           resourcePath = testResource,
                         )
                         .map(_.toString())
        expectedUrl =
          s"https://storage.googleapis.com$expectedPath" +
            "?X-Goog-Algorithm=GOOG4-RSA-SHA256" +
            "&X-Goog-Credential=example@example-project.iam.gserviceaccount.com/20181026/auto/storage/goog4_request" +
            "&X-Goog-Date=20181026T181309Z" +
            "&X-Goog-Expires=900" +
            "&X-Goog-SignedHeaders=host" +
            s"&X-Goog-Signature=$expectedSignature"
        _ <- assertTrue(signedUrl == expectedUrl)
      } yield assertCompletes
    }
  )
