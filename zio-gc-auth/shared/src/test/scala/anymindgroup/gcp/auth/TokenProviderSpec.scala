package com.anymindgroup.gcp.auth

import zio.Scope
import zio.test.*

object TokenProviderSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] = suite("TokenProviderSpec")(
    test("TODO add test") {
      assertCompletes
    }
  )
}
