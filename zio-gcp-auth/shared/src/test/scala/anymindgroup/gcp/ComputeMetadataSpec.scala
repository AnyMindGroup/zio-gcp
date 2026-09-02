package com.anymindgroup.gcp.auth

import zio.test.*
import zio.{Task, ZIO, ZLayer, ZLogger}

import com.anymindgroup.gcp.ComputeMetadata
import sttp.client4.*
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.*
import sttp.model.*

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
    }.provide(metadataStubLayer),
    suite("baseUriForHost")(
      test("defaults to the metadata server when GCE_METADATA_HOST is unset or blank") {
        val default = uri"http://metadata.google.internal/computeMetadata/v1"
        assertTrue(
          ComputeMetadata.baseUriForHost(None) == default,
          ComputeMetadata.baseUriForHost(Some("")) == default,
          ComputeMetadata.baseUriForHost(Some("   ")) == default,
        )
      },
      test("takes a host, with or without a port") {
        assertTrue(
          ComputeMetadata.baseUriForHost(Some("127.0.0.1:8080")) ==
            uri"http://127.0.0.1:8080/computeMetadata/v1",
          ComputeMetadata.baseUriForHost(Some("metadata.example.internal")) ==
            uri"http://metadata.example.internal/computeMetadata/v1",
          // The address the metadata server is also reachable on, which is what
          // GCE_METADATA_HOST is normally set to when DNS is unavailable.
          ComputeMetadata.baseUriForHost(Some("169.254.169.254")) ==
            uri"http://169.254.169.254/computeMetadata/v1",
        )
      },
      test("tolerates a scheme and a trailing slash rather than silently ignoring the value") {
        // Not part of the format, but the obvious thing to put in a variable
        // whose value ends up in a URL. Left in place it would produce
        // "http://http://..." — unparseable, and so indistinguishable from
        // having set nothing at all.
        assertTrue(
          ComputeMetadata.baseUriForHost(Some("http://127.0.0.1:8080")) ==
            uri"http://127.0.0.1:8080/computeMetadata/v1",
          ComputeMetadata.baseUriForHost(Some("https://127.0.0.1:8080/")) ==
            uri"http://127.0.0.1:8080/computeMetadata/v1",
        )
      },
      test("every request is built from the overridden host") {
        // The point of the override: the request values are vals derived from
        // baseUri, so this pins that none of them kept the default.
        val overridden = ComputeMetadata.baseUriForHost(Some("127.0.0.1:8080"))
        assertTrue(
          overridden.host.contains("127.0.0.1"),
          overridden.port.contains(8080),
          overridden.path == List("computeMetadata", "v1"),
        )
      },
    ),
  )

  val metadataStubBackend: Backend[Task] =
    BackendStub[Task](new RIOMonadAsyncError[Any])
      .whenRequestMatches(r =>
        r.method != Method.GET ||
        // Against `ComputeMetadata`'s own host rather than the literal default:
        // `GCE_METADATA_HOST` moves it, and a machine that has one set — a dev
        // container with a metadata emulator, for instance — would otherwise
        // fail this suite for the wrong reason.
        r.uri.host != ComputeMetadata.baseUri.host ||
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
