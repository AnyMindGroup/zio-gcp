package com.anymindgroup.gcp.auth

import com.anymindgroup.gcp.ComputeMetadata
import sttp.client4.*
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.*
import sttp.model.*

import zio.test.*
import zio.{Task, ZIO, ZLayer, ZLogger}

object ComputeMetadataSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] = suite("ComputeMetadataSpec")(
    test("request compute metadata") {
      for {
        backend      <- ZIO.service[Backend[Task]]
        projectId    <- ComputeMetadata.projectIdReq.send(backend).map(_.body)
        _            <- assertTrue(projectId == "gcp-project")
        numProjectId <- ComputeMetadata.numericProjectIdReq.send(backend).map(_.body)
        _            <- assertTrue(numProjectId == "123456")
        instanceZone <- ComputeMetadata.instanceZoneReq.send(backend).map(_.body)
        _            <- assertTrue(instanceZone == "projects/123456/zones/asia-northeast1-b")
      } yield assertCompletes
    }
  ).provide(metadataStubLayer)

  val metadataStubBackend: Backend[Task] =
    BackendStub[Task](new RIOMonadAsyncError[Any])
      .whenRequestMatches(r =>
        r.method != Method.GET ||
        !r.uri.host.contains("metadata.google.internal") ||
        !r.headers.exists(h => h.name.equalsIgnoreCase("Metadata-Flavor") && h.value.equalsIgnoreCase("Google"))
      )
      .thenRespondServerError()
      .whenRequestMatches(_.uri.toString.endsWith("computeMetadata/v1/project/numeric-project-id"))
      .thenRespondAdjust("123456")
      .whenRequestMatches(_.uri.toString.endsWith("computeMetadata/v1/project/project-id"))
      .thenRespondAdjust("gcp-project")
      .whenRequestMatches(_.uri.toString.endsWith("computeMetadata/v1/instance/zone"))
      .thenRespondAdjust("projects/123456/zones/asia-northeast1-b")

  val metadataStubLayer: ZLayer[Any, Nothing, Backend[Task]] =
    (zio.Runtime.removeDefaultLoggers >>> ZLayer.succeed(ZLogger.none)) >>> ZLayer.succeed(metadataStubBackend)
}
