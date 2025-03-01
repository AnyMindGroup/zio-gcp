package com.anymindgroup.gcp.iamcredentials.v1

import zio.test.*
import zio.*
import com.anymindgroup.gcp.auth.TokenProvider
import com.anymindgroup.gcp.auth.AuthedBackend
import com.anymindgroup.http.httpBackendLayer
import sttp.client4.GenericBackend
import java.nio.charset.StandardCharsets
import sttp.client4.Response

import com.anymindgroup.gcp.iamcredentials.v1 as iamc
import java.util.Base64
import iamc.schemas.SignBlobRequest

object IamcredentialsV1Spec extends ZIOSpecDefault:
  def spec = suite("IamcredentialsV1Spec")(
    test("sign blob") {
      (for {
        tp         <- TokenProvider.defaultAccessTokenProvider()
        ab         <- ZIO.serviceWith[GenericBackend[Task, Any]](AuthedBackend(tp, _))
        body        = "payload to sign".getBytes(StandardCharsets.UTF_8)
        serviceAcc <- ZIO.systemWith(_.env("GCP_TEST_SERVICE_ACCOUNT")).someOrFail("GCP_TEST_SERVICE_ACCOUNT not set")
        _ <- ab.send(
               iamc.resources.projects.ServiceAccounts
                 .signBlob(
                   projectsId = "-",
                   serviceAccountsId = serviceAcc,
                   request = SignBlobRequest(payload = Base64.getEncoder().encodeToString(body)),
                 )
             ).flatMap {
               case Response(Right(body), _, _, _, _, _) =>
                 Console.printLine(s"Signed blob: ${body.signedBlob.getOrElse("")}")
               case Response(Left(err), _, _, _, _, _) => Console.printError(s"Failure on signing: $err")
             }
      } yield assertCompletes).provideSome[Scope](httpBackendLayer())
    }
  ) @@ TestAspect.withLiveSystem @@ TestAspect.ifEnvNotSet("CI")
