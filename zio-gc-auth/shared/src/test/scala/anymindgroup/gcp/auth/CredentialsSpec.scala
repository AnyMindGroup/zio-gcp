package com.anymindgroup.gcp.auth

import zio.Scope
import zio.test.*

object CredentialsSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment & Scope, Any] = suite("CredentialsSpec")(
    test("TODO add test") {
      assertCompletes
    }
  )
}
