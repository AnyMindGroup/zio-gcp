package com.anymindgroup.gcp.iamcredentials.v1

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.anymindgroup.gcp.auth.defaultAccessTokenBackend
import sttp.client4.Response

import zio.*
import zio.test.*

object IamcredentialsV1Spec extends ZIOSpecDefault:
  def spec = suite("IamcredentialsV1Spec")(
    test("sign blob") {
      for {
        ab         <- defaultAccessTokenBackend()
        body        = "payload to sign".getBytes(StandardCharsets.UTF_8)
        serviceAcc <- ZIO.systemWith(_.env("GCP_TEST_SERVICE_ACCOUNT")).someOrFail("GCP_TEST_SERVICE_ACCOUNT not set")
        _ <- ab.send(
               resources.projects.ServiceAccounts
                 .signBlob(
                   projectsId = "-",
                   serviceAccountsId = serviceAcc,
                   request = schemas.SignBlobRequest(payload = Base64.getEncoder().encodeToString(body)),
                 )
             ).flatMap {
               case Response(Right(body), _, _, _, _, _) =>
                 Console.printLine(s"Signed blob: ${body.signedBlob.getOrElse("")}")
               case Response(Left(err), _, _, _, _, _) => Console.printError(s"Failure on signing: $err")
             }
      } yield assertCompletes
    }
  ) @@ TestAspect.withLiveSystem @@ TestAspect.ifEnvNotSet("CI")
