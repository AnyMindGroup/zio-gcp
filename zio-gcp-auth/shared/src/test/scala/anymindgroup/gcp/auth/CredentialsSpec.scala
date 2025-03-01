package com.anymindgroup.gcp.auth

import java.nio.file.Path

import sttp.client4.*
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.*
import sttp.model.*

import zio.test.*
import zio.test.Assertion.*
import zio.{Task, ZIO, ZLayer, ZLogger}

object CredentialsSpec extends ZIOSpecDefault with com.anymindgroup.http.HttpClientBackendPlatformSpecific {
  override def spec: Spec[TestEnvironment, Any] = suite("CredentialsSpec")(
    test("return none if no credentials were found") {
      for {
        _     <- TestSystem.putProperty("user.home", "non_existing")
        creds <- ZIO.serviceWithZIO[Backend[Task]](Credentials.auto(_)).exit
        _     <- assert(creds)(succeeds(isNone))
      } yield assertCompletes
    }.provideLayer(defaultTestLayer),
    test("read credentials from internal google meta server") {
      for {
        _     <- TestSystem.putProperty("user.home", "non_existing")
        creds <- ZIO.serviceWithZIO[Backend[Task]](Credentials.auto(_))
        _ <-
          assert(creds)(isSome(equalTo(Credentials.ComputeServiceAccount("test@gcp-project.iam.gserviceaccount.com"))))
      } yield assertCompletes
    }.provideLayer(metadataStubLayer),
    test("read credentials from default directory or internal google meta server based on config") {
      for {
        backend <- ZIO.service[Backend[Task]]
        _       <- TestSystem.putProperty("user.home", resourcesDir.toString())
        _ <- Credentials
               .auto(backend, lookupComputeMetadataFirst = false)
               .flatMap: credentials =>
                 assertTrue(credentials match {
                   case Some(Credentials.UserAccount("refresh_token", "123.apps.googleusercontent.com", _)) => true
                   case _                                                                                   => false
                 })
        _ <- Credentials
               .auto(backend, lookupComputeMetadataFirst = true)
               .flatMap: credentials =>
                 assertTrue(credentials match {
                   case Some(Credentials.ComputeServiceAccount("test@gcp-project.iam.gserviceaccount.com")) => true
                   case _                                                                                   => false
                 })
      } yield assertCompletes
    }.provideLayer(metadataStubLayer),
    test("read credentials from path set by GOOGLE_APPLICATION_CREDENTIALS") {
      val userCredsPath =
        Path
          .of(resourcesDir.toString(), "application_default_credentials.json")

      for {
        _         <- TestSystem.putEnv("GOOGLE_APPLICATION_CREDENTIALS", userCredsPath.toString())
        userCreds <- ZIO.serviceWithZIO[Backend[Task]](Credentials.auto(_))
        _ <- assertTrue(userCreds match {
               case Some(Credentials.UserAccount("refresh_token", "123.apps.googleusercontent.com", _)) => true
               case _                                                                                   => false
             })
        serviceCredsPath = Path.of(resourcesDir.toString(), "service_account.json").toAbsolutePath()
        _               <- TestSystem.putEnv("GOOGLE_APPLICATION_CREDENTIALS", serviceCredsPath.toString())
        serviceCreds    <- ZIO.serviceWithZIO[Backend[Task]](Credentials.auto(_))
        _ <- assertTrue(serviceCreds match {
               case Some(Credentials.ServiceAccountKey("test@gcp-project.iam.gserviceaccount.com", _)) => true
               case _                                                                                  => false
             })
      } yield assertCompletes
    }.provideLayer(defaultTestLayer),
  )

  val resourcesDir: Path = Path.of("zio-gcp-auth", "shared", "src", "test", "resources").toAbsolutePath()

  val metadataStubBackend: Backend[Task] =
    BackendStub[Task](new RIOMonadAsyncError[Any])
      .whenRequestMatches(r =>
        r.method != Method.GET ||
        !r.uri.host.contains("metadata.google.internal") ||
        !r.headers.exists(h => h.name.equalsIgnoreCase("Metadata-Flavor") && h.value.equalsIgnoreCase("Google"))
      )
      .thenRespondServerError()
      .whenRequestMatches(
        _.uri.toString.endsWith("computeMetadata/v1/instance/service-accounts/default/email")
      )
      .thenRespondAdjust("test@gcp-project.iam.gserviceaccount.com")
      .whenRequestMatches(_.uri.toString.endsWith("computeMetadata/v1/instance/service-accounts/default/email"))
      .thenRespondAdjust("""{"access_token":"abc123","expires_in":3599,"token_type":"Bearer"}""")

  val defaultTestLayer: ZLayer[Any, Throwable, Backend[Task]] =
    (zio.Runtime.removeDefaultLoggers >>> ZLayer.succeed(ZLogger.none)) >>> httpBackendLayer()

  val metadataStubLayer: ZLayer[Any, Nothing, Backend[Task]] =
    (zio.Runtime.removeDefaultLoggers >>> ZLayer.succeed(ZLogger.none)) >>> ZLayer.succeed(metadataStubBackend)
}
