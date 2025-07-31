package com.anymindgroup.gcp.aiplatform.v1

import com.anymindgroup.gcp.aiplatform.v1.schemas.*
import com.anymindgroup.gcp.auth.defaultAccessTokenBackend

import zio.*
import zio.test.*

object AiplatformV1Spec extends ZIOSpecDefault:
  def spec = suite("AiplatformV1Spec")(
    test("generate content") {
      for {
        backend    <- defaultAccessTokenBackend()
        endpoint    = Endpoint.`asia-northeast1`
        gcpProject <- ZIO.systemWith(_.env("GCP_TEST_PROJECT")).someOrFail("GCP_TEST_PROJECT not set")
        endpoint   <-
          ZIO
            .systemWith(
              _.env("GCP_TEST_LOCATION").map(_.flatMap(l => Endpoint.values.find(_.location.equalsIgnoreCase(l))))
            )
            .someOrFail("GCP_TEST_LOCATION not set or invalid")
        req = resources.projects.locations.publishers.Models.generateContent(
                projectsId = gcpProject,
                locationsId = endpoint.location,
                publishersId = "google",
                modelsId = "gemini-1.5-flash",
                request = GoogleCloudAiplatformV1GenerateContentRequest(
                  contents = Chunk(
                    GoogleCloudAiplatformV1Content(
                      parts = Chunk(GoogleCloudAiplatformV1Part(text = Some("hello how are doing?"))),
                      role = Some("user"),
                    )
                  )
                ),
                endpointUrl = endpoint.url,
              )
        res <- backend.send(req)
        _   <- res.body match
               case Left(err)   => ZIO.dieMessage(s"Failure $err")
               case Right(body) => Console.printLine(s"Response ok: $body")
      } yield assertCompletes
    }
  ) @@ TestAspect.withLiveSystem @@ TestAspect.ifEnvNotSet("CI")
