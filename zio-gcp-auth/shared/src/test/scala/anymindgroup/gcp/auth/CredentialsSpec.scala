package com.anymindgroup.gcp.auth

import java.nio.file.Path

import sttp.client4.*
import sttp.client4.impl.zio.RIOMonadAsyncError
import sttp.client4.testing.*
import sttp.model.*

import zio.test.*
import zio.test.Assertion.*
import zio.{Task, ZLayer, ZLogger}

object CredentialsSpec extends ZIOSpecDefault with com.anymindgroup.http.HttpClientBackendPlatformSpecific {
  override def spec: Spec[TestEnvironment, Any] = suite("CredentialsSpec")(
    test("return none if no credentials were found") {
      for {
        _     <- TestSystem.putProperty("user.home", "non_existing")
        creds <- Credentials.auto.exit
        _     <- assert(creds)(succeeds(isNone))
      } yield assertCompletes
    }.provideLayer(defaultTestLayer),
    test("readcredentials from internal google meta server") {
      for {
        _     <- TestSystem.putProperty("user.home", "non_existing")
        creds <- Credentials.auto
        _ <-
          assert(creds)(isSome(equalTo(Credentials.ComputeServiceAccount("test@gcp-project.iam.gserviceaccount.com"))))
      } yield assertCompletes
    }.provideLayer(metadataStubLayer),
    test("read credentials from default directory") {
      for {
        _     <- TestSystem.putProperty("user.home", homeDir.toString())
        creds <- Credentials.auto
        _ <- assertTrue(creds match {
               case Some(Credentials.UserAccount("refresh_token", "123.apps.googleusercontent.com", _)) => true
               case _                                                                                   => false
             })
      } yield assertCompletes
    }.provideLayer(defaultTestLayer),
    test("read credentials from path set by GOOGLE_APPLICATION_CREDENTIALS") {
      val userCredsPath =
        Path
          .of("zio-gc-auth/shared/src/test/resources/application_default_credentials.json")
          .toAbsolutePath()

      for {
        _         <- TestSystem.putEnv("GOOGLE_APPLICATION_CREDENTIALS", userCredsPath.toString())
        userCreds <- Credentials.auto
        _ <- assertTrue(userCreds match {
               case Some(Credentials.UserAccount("refresh_token", "123.apps.googleusercontent.com", _)) => true
               case _                                                                                   => false
             })
        serviceCredsPath = Path.of("zio-gc-auth/shared/src/test/resources/service_account.json").toAbsolutePath()
        _               <- TestSystem.putEnv("GOOGLE_APPLICATION_CREDENTIALS", serviceCredsPath.toString())
        serviceCreds    <- Credentials.auto
        _ <- assertTrue(serviceCreds match {
               case Some(Credentials.ServiceAccountKey("test@gcp-project.iam.gserviceaccount.com", _)) => true
               case _                                                                                  => false
             })
      } yield assertCompletes
    }.provideLayer(defaultTestLayer),
  )

  val homeDir: Path = Path.of("zio-gc-auth/shared/src/test/resources/").toAbsolutePath()

  val metadataStubBackend: GenericBackend[Task, Any] =
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
      .thenRespond("test@gcp-project.iam.gserviceaccount.com")
      .whenRequestMatches(_.uri.toString.endsWith("computeMetadata/v1/instance/service-accounts/default/email"))
      .thenRespond("""{"access_token":"abc123","expires_in":3599,"token_type":"Bearer"}""")

  val defaultTestLayer: ZLayer[Any, Throwable, GenericBackend[Task, Any]] =
    (zio.Runtime.removeDefaultLoggers >>> ZLayer.succeed(ZLogger.none)) >>> httpBackendLayer()

  val metadataStubLayer: ZLayer[Any, Nothing, GenericBackend[Task, Any]] =
    (zio.Runtime.removeDefaultLoggers >>> ZLayer.succeed(ZLogger.none)) >>> ZLayer.succeed(metadataStubBackend)
}
